"""yt-dlp wrapper: metadata extraction and download planning.

Two responsibilities, kept separate so the planning half is testable offline:

* ``resolve`` — call ``yt_dlp.YoutubeDL.extract_info`` (library call, never a
  shell) and cache the raw info dict.
* ``build_plans`` — turn an info dict into an ordered map of ``DownloadPlan``.
  Plan ids are derived deterministically from yt-dlp's own format ids, so the
  download endpoint can rebuild the exact same map and reject anything the
  client didn't legitimately receive from ``/api/resolve``.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Literal

import anyio
import yt_dlp
from yt_dlp.utils import DownloadError, ExtractorError, UnsupportedError

from .cache import TTLCache
from .config import get_settings
from .errors import AppError, ErrorCode, classify_extractor_error
from .resolvers import DirectResult

logger = logging.getLogger(__name__)

PlanKind = Literal["best", "progressive", "merge", "audio", "image"]

# Protocols we can actually stream. Anything else (rtmp, ism, ...) is dropped
# rather than half-supported.
_DIRECT_PROTOCOLS = frozenset({"http", "https"})
_FFMPEG_PROTOCOLS = frozenset({"m3u8", "m3u8_native", "http_dash_segments"})
_USABLE_PROTOCOLS = _DIRECT_PROTOCOLS | _FFMPEG_PROTOCOLS

# Containers that can be muxed with `-c copy` into a fragmented MP4. Anything
# else (vp9/opus/av1 in webm) goes into Matroska instead, which accepts them.
_MP4_COMPATIBLE_EXTS = frozenset({"mp4", "m4a", "m4v", "mov", "3gp", "aac"})

_AUDIO_EXT_PREFERENCE = {"m4a": 3, "mp4": 3, "webm": 2, "opus": 2, "mp3": 1}
_VIDEO_EXT_PREFERENCE = {"mp4": 3, "mov": 2, "webm": 1}

# Extensions that are audio even when the extractor reports no codecs.
_AUDIO_EXTS = frozenset({"m4a", "mp3", "opus", "aac", "ogg", "oga", "wav", "flac"})
# Pins, and any other extractor that serves stills.
_IMAGE_EXTS = frozenset({"jpg", "jpeg", "png", "webp", "gif", "heic", "avif"})
# More image sizes than this is noise rather than choice.
_MAX_IMAGE_OPTIONS = 3

_info_cache = TTLCache(
    maxsize=get_settings().resolve_cache_size,
    ttl=get_settings().resolve_cache_ttl,
)


@dataclass(frozen=True, slots=True)
class MediaSource:
    """One stream yt-dlp resolved, plus the headers required to fetch it."""

    url: str
    ext: str
    protocol: str
    headers: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class DownloadPlan:
    id: str
    kind: PlanKind
    label: str
    ext: str
    height: int | None
    filesize: int | None
    video: MediaSource | None
    audio: MediaSource | None
    needs_ffmpeg: bool
    container: str | None  # ffmpeg output format name, when needs_ffmpeg


# --------------------------------------------------------------------------
# Extraction
# --------------------------------------------------------------------------


def _ydl_opts() -> dict[str, Any]:
    settings = get_settings()
    return {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
        "no_color": True,
        "socket_timeout": settings.extract_timeout,
        "retries": 2,
        "extractor_retries": 1,
        # No cache dir and no cookies: nothing about a request persists, and the
        # app is public-content-only by design (PRD section 3).
        "cachedir": False,
        "geo_bypass": True,
        **({"proxy": settings.proxy} if settings.proxy else {}),
    }


def _extract_sync(url: str) -> dict[str, Any]:
    with yt_dlp.YoutubeDL(_ydl_opts()) as ydl:
        info = ydl.extract_info(url, download=False)
        if info is None:
            raise AppError(
                ErrorCode.EXTRACTION_FAILED, "The extractor returned no data."
            )
        # Playlists/multi-entry results: take the first playable entry so a
        # "watch?v=...&list=..." link still works.
        while info.get("_type") in ("playlist", "multi_video"):
            entries = [e for e in (info.get("entries") or []) if e]
            if not entries:
                raise AppError(
                    ErrorCode.EXTRACTION_FAILED, "That link contains no playable media."
                )
            info = entries[0]
        return ydl.sanitize_info(info)


async def resolve(url: str, *, use_cache: bool = True) -> dict[str, Any]:
    """Extract metadata for a URL. Blocking yt-dlp work runs off the event loop."""
    if use_cache:
        cached = _info_cache.get(url)
        if cached is not None:
            return cached

    try:
        info = await anyio.to_thread.run_sync(_extract_sync, url)
    except AppError:
        raise
    except UnsupportedError as exc:
        raise AppError(
            ErrorCode.UNSUPPORTED_URL,
            "This link isn't from a supported platform, or isn't a media page.",
        ) from exc
    except (DownloadError, ExtractorError) as exc:
        code, message = classify_extractor_error(str(exc))
        logger.warning("extraction failed url=%s error=%s", url, exc)
        status = 404 if code is ErrorCode.PRIVATE_CONTENT else 422
        raise AppError(code, message, status_code=status) from exc
    except Exception as exc:  # noqa: BLE001 - yt-dlp raises bare exceptions too
        logger.exception("unexpected extraction error url=%s", url)
        raise AppError(
            ErrorCode.EXTRACTION_FAILED,
            "Couldn't extract media from this link.",
            status_code=502,
        ) from exc

    _info_cache.set(url, info)
    return info


def clear_cache() -> None:
    _info_cache.clear()


# --------------------------------------------------------------------------
# Planning
# --------------------------------------------------------------------------


def _codec(value: Any) -> str | None:
    """Normalise yt-dlp's codec fields; it uses the string "none" for absent."""
    if value in (None, "", "none"):
        return None
    return str(value)


def _estimate_size(fmt: dict[str, Any], duration: float | None) -> int | None:
    for key in ("filesize", "filesize_approx"):
        value = fmt.get(key)
        if isinstance(value, (int, float)) and value > 0:
            return int(value)
    tbr = fmt.get("tbr")
    if duration and isinstance(tbr, (int, float)) and tbr > 0:
        return int(tbr * 1000 / 8 * duration)
    return None


def _usable_formats(info: dict[str, Any]) -> list[dict[str, Any]]:
    formats = info.get("formats")
    if not formats:
        # Simple extractors return a single stream on the info dict itself.
        formats = [info] if info.get("url") else []
    return [
        fmt
        for fmt in formats
        if fmt.get("url")
        and str(fmt.get("protocol") or "https").split("+")[0] in _USABLE_PROTOCOLS
    ]


def _to_source(fmt: dict[str, Any]) -> MediaSource:
    return MediaSource(
        url=str(fmt["url"]),
        ext=str(fmt.get("ext") or "mp4"),
        protocol=str(fmt.get("protocol") or "https").split("+")[0],
        headers={
            str(k): str(v) for k, v in (fmt.get("http_headers") or {}).items() if v
        },
    )


def _audio_rank(fmt: dict[str, Any]) -> tuple[int, float]:
    ext = str(fmt.get("ext") or "").lower()
    abr = fmt.get("abr") or fmt.get("tbr") or 0
    return (_AUDIO_EXT_PREFERENCE.get(ext, 0), float(abr))


def _video_rank(fmt: dict[str, Any]) -> tuple[int, float]:
    ext = str(fmt.get("ext") or "").lower()
    tbr = fmt.get("tbr") or fmt.get("vbr") or 0
    return (_VIDEO_EXT_PREFERENCE.get(ext, 0), float(tbr))


def _quality_label(height: int, ext: str) -> str:
    fps_free = f"{height}p"
    return f"{fps_free} {ext.upper()}"


def _bucket_key(fmt: dict[str, Any]) -> str:
    """Resolution where known, format id where it isn't.

    Keying everything by height was the Pinterest bug: its HLS renditions all
    report no height, so every one of them landed in the same bucket, only one
    survived, and the winner could be an audio-only rendition — which is why
    "Original quality (MP4)" downloaded audio.
    """
    height = fmt.get("height")
    if isinstance(height, int) and height > 0:
        return f"h{height}"
    return f"f{fmt.get('format_id', id(fmt))}"


def _height_of(fmt: dict[str, Any]) -> int:
    height = fmt.get("height")
    return height if isinstance(height, int) and height > 0 else 0


def build_plans(
    info: dict[str, Any], *, can_merge: bool = True
) -> dict[str, DownloadPlan]:
    """Build the ordered, de-duplicated set of offered downloads.

    One entry per distinct resolution, preferring a single progressive stream
    over a merge because that needs no ffmpeg. Plus a "Best available" row, one
    audio-only entry, and any still images.

    No resolution cap: it's the operator's own server and storage, so every
    resolution the platform reports is offered, matching the Android build.
    """
    duration = info.get("duration")
    if not isinstance(duration, (int, float)):
        duration = None

    progressive: dict[str, dict[str, Any]] = {}
    video_only: dict[str, dict[str, Any]] = {}
    audio_only: list[dict[str, Any]] = []
    images: dict[str, dict[str, Any]] = {}

    for fmt in _usable_formats(info):
        ext = str(fmt.get("ext") or "").lower()
        vcodec = _codec(fmt.get("vcodec"))
        acodec = _codec(fmt.get("acodec"))

        if ext in _IMAGE_EXTS:
            bucket = images
        elif vcodec and acodec:
            bucket = progressive
        elif vcodec:
            bucket = video_only
        elif acodec:
            audio_only.append(fmt)
            continue
        elif ext in _AUDIO_EXTS:
            # No codec information at all. Trust the extension: an .m4a with
            # unknown codecs is audio, not a video we should headline.
            audio_only.append(fmt)
            continue
        else:
            # Unknown codecs on a non-image format. Older builds threw these
            # away, which silently broke Pinterest — it reports pin media with
            # no codec information. yt-dlp can still fetch them.
            bucket = progressive

        key = _bucket_key(fmt)
        current = bucket.get(key)
        if current is None or _video_rank(fmt) > _video_rank(current):
            bucket[key] = fmt

    best_audio = max(audio_only, key=_audio_rank, default=None)

    plans: dict[str, DownloadPlan] = {}
    has_video = bool(progressive or video_only)

    def add(plan: DownloadPlan) -> None:
        plans[plan.id] = plan

    # Known resolutions descending, then bitrate descending.
    #
    # The bitrate tie-break matters: when an extractor reports no heights at all
    # (Pinterest), every key ties on height, and falling back to insertion order
    # would put the *worst* rendition first — yt-dlp lists formats worst-first.
    def order_key(key: str) -> tuple[int, float]:
        fmt = progressive.get(key) or video_only[key]
        tbr = fmt.get("tbr") or fmt.get("vbr") or 0
        return (-_height_of(fmt), -float(tbr))

    ordered = sorted(set(progressive) | set(video_only), key=order_key)

    for key in ordered:
        fmt = progressive.get(key)
        if fmt is not None:
            source = _to_source(fmt)
            needs_ffmpeg = source.protocol not in _DIRECT_PROTOCOLS
            container = _container_for(source.ext) if needs_ffmpeg else None
            ext = container_ext(container) if needs_ffmpeg else source.ext
            add(
                DownloadPlan(
                    id=f"p-{fmt.get('format_id', key)}",
                    kind="progressive",
                    label=_label_for(_height_of(fmt), ext),
                    ext=ext,
                    height=_height_of(fmt) or None,
                    filesize=_estimate_size(fmt, duration),
                    video=source,
                    audio=None,
                    needs_ffmpeg=needs_ffmpeg,
                    container=container,
                )
            )
            continue

        if not can_merge or best_audio is None:
            continue
        v_fmt = video_only[key]
        v_source = _to_source(v_fmt)
        a_source = _to_source(best_audio)
        container = _container_for(v_source.ext, a_source.ext)
        v_size = _estimate_size(v_fmt, duration)
        a_size = _estimate_size(best_audio, duration)
        add(
            DownloadPlan(
                id=f"m-{v_fmt.get('format_id', key)}+{best_audio.get('format_id', 'a')}",
                kind="merge",
                label=_label_for(_height_of(v_fmt), container_ext(container)),
                ext=container_ext(container),
                height=_height_of(v_fmt) or None,
                filesize=(v_size + a_size) if (v_size and a_size) else None,
                video=v_source,
                audio=a_source,
                needs_ffmpeg=True,
                container=container,
            )
        )

    if best_audio is not None:
        source = _to_source(best_audio)
        needs_ffmpeg = source.protocol not in _DIRECT_PROTOCOLS
        ext = "m4a" if needs_ffmpeg and source.ext in _MP4_COMPATIBLE_EXTS else source.ext
        add(
            DownloadPlan(
                id=f"a-{best_audio.get('format_id', 'audio')}",
                kind="audio",
                label=f"Audio only ({ext.upper()})",
                ext=ext,
                height=None,
                filesize=_estimate_size(best_audio, duration),
                video=None,
                audio=source,
                needs_ffmpeg=needs_ffmpeg,
                container=_container_for(source.ext) if needs_ffmpeg else None,
            )
        )

    for fmt in sorted(images.values(), key=_height_of, reverse=True)[:_MAX_IMAGE_OPTIONS]:
        source = _to_source(fmt)
        height = _height_of(fmt)
        add(
            DownloadPlan(
                id=f"i-{fmt.get('format_id', 'image')}",
                kind="image",
                label=(
                    f"Image {height}px ({source.ext.upper()})"
                    if height
                    else f"Image ({source.ext.upper()})"
                ),
                ext=source.ext,
                height=height or None,
                filesize=_estimate_size(fmt, duration),
                video=source,
                audio=None,
                needs_ffmpeg=False,
                container=None,
            )
        )

    # "Best available", offered first.
    #
    # The Android build passes yt-dlp the selector `bv*+ba/b` and lets it
    # choose. Here the server does its own selection and streams the result, so
    # there is nothing to hand a selector to — this aliases the top-ranked plan
    # instead. Same promise to the user, reached differently.
    if plans and (has_video or best_audio is not None):
        first = next(iter(plans.values()))
        best = DownloadPlan(
            id="best",
            kind="best",
            label="Best available",
            ext=first.ext,
            height=first.height,
            filesize=first.filesize,
            video=first.video,
            audio=first.audio,
            needs_ffmpeg=first.needs_ffmpeg,
            container=first.container,
        )
        plans = {"best": best, **plans}

    return plans


def build_plans_from_direct(result: "DirectResult") -> dict[str, DownloadPlan]:
    """Turn custom-resolver output into plans the streaming path already knows.

    A direct URL is just a progressive source with required headers, so it needs
    no new streaming code — `open_direct_stream` handles it unchanged.
    """
    plans: dict[str, DownloadPlan] = {}
    for index, media in enumerate(result.media):
        source = MediaSource(
            url=media.url,
            ext=media.ext,
            protocol="https",
            headers=dict(media.headers),
        )
        plan = DownloadPlan(
            id=f"d-{index}",
            kind="image" if media.kind == "image" else "progressive",
            label=media.label,
            ext=media.ext,
            height=media.height,
            filesize=media.size_bytes,
            video=source,
            audio=None,
            needs_ffmpeg=False,
            container=None,
        )
        plans[plan.id] = plan
    return plans


def _label_for(height: int, ext: str) -> str:
    if height:
        return _quality_label(height, ext)
    return f"Original quality ({ext.upper()})"


def _container_for(*exts: str) -> str:
    """Pick an ffmpeg output format that can hold these streams with -c copy."""
    if all(ext.lower() in _MP4_COMPATIBLE_EXTS for ext in exts):
        return "mp4"
    return "matroska"


def container_ext(container: str | None) -> str:
    return {"mp4": "mp4", "matroska": "mkv"}.get(container or "", "mp4")


def pick_fallback_plan(
    plans: dict[str, DownloadPlan], max_bytes: int
) -> DownloadPlan | None:
    """Best plan that avoids a merge and fits under the size guard."""
    candidates = [
        plan
        for plan in plans.values()
        if plan.kind == "progressive"
        and (plan.filesize is None or plan.filesize <= max_bytes)
    ]
    if not candidates:
        return None
    return max(candidates, key=lambda p: (p.height or 0))
