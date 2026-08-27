"""Zero-disk streaming: source -> (optional ffmpeg) -> HTTP response.

Nothing is written to disk and nothing is buffered to completion. Two paths:

* **Direct** — a single progressive/audio stream over http(s) is proxied byte
  for byte with httpx. Upstream ``Content-Length`` is forwarded, so the phone's
  download manager shows a real progress bar.
* **ffmpeg** — needed for merged video+audio and for playlist protocols
  (HLS/DASH). ffmpeg is given the stream URLs *as inputs* and writes to
  ``pipe:1``; output is fragmented MP4 (or Matroska for VP9/Opus) so it is
  valid from the first byte and needs no seek-back to finalise.

  Note on PRD section 7.2: a single ffmpeg process cannot read two separate
  streams from one stdin, so piping both streams in is not possible. Passing the
  two URLs as inputs achieves the same goal — ffmpeg streams them itself and
  still never touches disk.
"""

from __future__ import annotations

import asyncio
import logging
import re
import unicodedata
from collections import deque
from collections.abc import AsyncIterator
from urllib.parse import quote, urlsplit

import anyio
import httpx

from .config import get_settings
from .errors import AppError, ErrorCode
from .extractor import DownloadPlan, MediaSource
from .platforms import is_private_host

logger = logging.getLogger(__name__)

# Caps simultaneous downloads so one client can't exhaust a free-tier instance.
_download_slots = asyncio.Semaphore(get_settings().max_concurrent_downloads)

_UNSAFE_FILENAME = re.compile(r"[^\w \-.\[\]()&'!,]+", re.UNICODE)
_FIRST_CHUNK_TIMEOUT = 45  # ffmpeg cold start + upstream connect


# --------------------------------------------------------------------------
# Concurrency
# --------------------------------------------------------------------------


class DownloadSlot:
    """Fail-fast concurrency guard. Raises BUSY instead of queueing forever."""

    def __init__(self) -> None:
        self._acquired = False

    async def acquire(self) -> None:
        # No await between the check and the acquire, so on a single event loop
        # this cannot race.
        if _download_slots.locked():
            raise AppError(
                ErrorCode.BUSY,
                "A download is already in progress. Try again in a moment.",
                status_code=503,
            )
        await _download_slots.acquire()
        self._acquired = True

    def release(self) -> None:
        if self._acquired:
            self._acquired = False
            _download_slots.release()


# --------------------------------------------------------------------------
# Filenames
# --------------------------------------------------------------------------


def build_filename(title: str | None, ext: str) -> str:
    base = (title or "download").strip()
    base = unicodedata.normalize("NFKC", base)
    base = _UNSAFE_FILENAME.sub("_", base).strip("._ ")
    base = re.sub(r"\s+", " ", base) or "download"
    return f"{base[:120]}.{ext}"


def content_disposition(filename: str) -> str:
    """RFC 6266 header with an ASCII fallback for older mobile browsers."""
    ascii_name = filename.encode("ascii", "ignore").decode("ascii") or "download"
    ascii_name = ascii_name.replace('"', "")
    return (
        f'attachment; filename="{ascii_name}"; '
        f"filename*=UTF-8''{quote(filename, safe='')}"
    )


# --------------------------------------------------------------------------
# Shared guards
# --------------------------------------------------------------------------


def _assert_public(source: MediaSource) -> None:
    """Reject media URLs pointing at private address space (SSRF defence)."""
    if not get_settings().block_private_media_hosts:
        return
    host = urlsplit(source.url).hostname or ""
    if is_private_host(host):
        logger.error("blocked non-public media host host=%s", host)
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "The platform returned a media address that can't be fetched.",
            status_code=502,
        )


async def _assert_public_async(*sources: MediaSource | None) -> None:
    for source in sources:
        if source is not None:
            await anyio.to_thread.run_sync(_assert_public, source)


# --------------------------------------------------------------------------
# Direct passthrough
# --------------------------------------------------------------------------


async def open_direct_stream(
    source: MediaSource,
) -> tuple[int | None, AsyncIterator[bytes]]:
    """Open an upstream stream and return (content_length, chunk iterator)."""
    settings = get_settings()
    client = httpx.AsyncClient(
        timeout=httpx.Timeout(settings.upstream_timeout, read=None),
        follow_redirects=True,
        limits=httpx.Limits(max_connections=4),
    )
    request = client.build_request("GET", source.url, headers=source.headers)
    try:
        response = await client.send(request, stream=True)
    except httpx.HTTPError as exc:
        await client.aclose()
        logger.warning("upstream connect failed: %s", exc)
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "Couldn't reach the media host. Fetch the link again.",
            status_code=502,
        ) from exc

    if response.status_code >= 400:
        status = response.status_code
        await response.aclose()
        await client.aclose()
        logger.warning("upstream rejected request status=%s", status)
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "The media link expired or was rejected. Fetch the link again.",
            status_code=502,
        )

    raw_length = response.headers.get("content-length")
    content_length = int(raw_length) if raw_length and raw_length.isdigit() else None

    async def iterator() -> AsyncIterator[bytes]:
        try:
            async for chunk in response.aiter_bytes(settings.chunk_size):
                yield chunk
        except httpx.HTTPError as exc:
            # The response has already started; log and end the stream. The
            # client sees a truncated file, which is the only honest outcome.
            logger.warning("upstream stream interrupted: %s", exc)
        finally:
            await response.aclose()
            await client.aclose()

    return content_length, iterator()


# --------------------------------------------------------------------------
# ffmpeg mux
# --------------------------------------------------------------------------


def _input_args(source: MediaSource) -> list[str]:
    args: list[str] = []
    if source.protocol in ("http", "https"):
        args += ["-reconnect", "1", "-reconnect_streamed", "1",
                 "-reconnect_delay_max", "5"]
    if source.headers:
        header_blob = "".join(f"{k}: {v}\r\n" for k, v in source.headers.items())
        args += ["-headers", header_blob]
    args += ["-i", source.url]
    return args


def build_ffmpeg_args(plan: DownloadPlan) -> list[str]:
    settings = get_settings()
    args = [settings.ffmpeg_path, "-hide_banner", "-loglevel", "error", "-nostdin"]

    inputs = [source for source in (plan.video, plan.audio) if source is not None]
    if not inputs:
        raise AppError(ErrorCode.EXTRACTION_FAILED, "No stream to download.", 500)

    for source in inputs:
        args += _input_args(source)

    if plan.video is not None and plan.audio is not None:
        args += ["-map", "0:v:0", "-map", "1:a:0"]
    else:
        args += ["-map", "0"]

    # Stream copy only: no re-encode, so CPU stays near zero and output starts
    # essentially immediately.
    args += ["-c", "copy"]

    container = plan.container or "mp4"
    if container == "mp4":
        args += ["-movflags", "frag_keyframe+empty_moov+default_base_moof"]
    args += ["-f", container, "pipe:1"]
    return args


async def open_ffmpeg_stream(plan: DownloadPlan) -> tuple[None, AsyncIterator[bytes]]:
    """Start ffmpeg and return (None, chunk iterator).

    The first chunk is read here so a failing pipeline surfaces as a JSON error
    instead of a zero-byte "successful" download.
    """
    settings = get_settings()
    args = build_ffmpeg_args(plan)

    try:
        process = await asyncio.create_subprocess_exec(
            *args,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            limit=settings.chunk_size * 4,
        )
    except FileNotFoundError as exc:
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "ffmpeg isn't installed on the server, so merged formats are "
            "unavailable. Pick a single-stream quality instead.",
            status_code=503,
        ) from exc

    stderr_tail: deque[str] = deque(maxlen=20)

    async def drain_stderr() -> None:
        assert process.stderr is not None
        async for line in process.stderr:
            text = line.decode("utf-8", "replace").rstrip()
            if text:
                stderr_tail.append(text)

    stderr_task = asyncio.create_task(drain_stderr())

    async def terminate() -> None:
        stderr_task.cancel()
        if process.returncode is None:
            process.kill()
        try:
            await process.wait()
        except ProcessLookupError:
            pass

    assert process.stdout is not None
    try:
        first_chunk = await asyncio.wait_for(
            process.stdout.read(settings.chunk_size), _FIRST_CHUNK_TIMEOUT
        )
    except asyncio.TimeoutError as exc:
        await terminate()
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "The media host was too slow to respond. Try a lower quality.",
            status_code=504,
        ) from exc

    if not first_chunk:
        await process.wait()
        await terminate()
        detail = " | ".join(stderr_tail)
        logger.error("ffmpeg produced no output rc=%s stderr=%s",
                     process.returncode, detail)
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "Couldn't build this format. Try a different quality.",
            status_code=502,
        )

    async def iterator() -> AsyncIterator[bytes]:
        try:
            yield first_chunk
            while True:
                chunk = await process.stdout.read(settings.chunk_size)
                if not chunk:
                    break
                yield chunk
            await process.wait()
            if process.returncode not in (0, None):
                logger.warning(
                    "ffmpeg exited rc=%s stderr=%s",
                    process.returncode,
                    " | ".join(stderr_tail),
                )
        finally:
            # Covers normal completion and client disconnect (GeneratorExit /
            # CancelledError), so no orphaned ffmpeg process is left behind.
            await terminate()

    return None, iterator()


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------


async def open_stream(plan: DownloadPlan) -> tuple[int | None, AsyncIterator[bytes]]:
    await _assert_public_async(plan.video, plan.audio)
    if plan.needs_ffmpeg:
        return await open_ffmpeg_stream(plan)
    source = plan.video or plan.audio
    assert source is not None
    return await open_direct_stream(source)
