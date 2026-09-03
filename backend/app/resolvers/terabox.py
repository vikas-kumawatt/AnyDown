"""Reads TeraBox share links.

TeraBox isn't a media site — it's file storage, and yt-dlp has no extractor for
it. A share link resolves in two steps:

1. Load the share page. It embeds a ``jsToken``, an anti-CSRF value.
2. Call ``/api/shorturlinfo?shorturl=…&jsToken=…&root=1``, which returns the file
   list with ``server_filename``, ``size`` and a ``dlink`` direct URL.

**This is undocumented and will break.** It's a private API with no
compatibility promise, it rate-limits, and the token's location moves. The
parsing here is pure and tested; whether TeraBox still answers this way on any
given day isn't something this can guarantee.

Port of ``android/.../domain/TeraboxParser.kt``.
"""

from __future__ import annotations

import json
import re
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover
    import httpx

    from . import DirectResult

# Every domain TeraBox serves shares from. They rotate these often.
HOSTS = (
    "terabox.com", "terabox.app", "1024terabox.com", "teraboxapp.com",
    "teraboxlink.com", "terasharelink.com", "teraboxshare.com",
    "4funbox.com", "mirrobox.com", "nephobox.com", "momerybox.com",
    "tibibox.com", "freeterabox.com", "terafileshare.com",
)

_HOST = re.compile(r"^(https?)://([^/?#\s]+)", re.IGNORECASE)
_SURL_PARAM = re.compile(r"[?&]surl=([A-Za-z0-9_-]+)")
_SURL_PATH = re.compile(r"/s/([A-Za-z0-9_-]+)")

_TOKEN_PATTERNS = (
    re.compile(r"""jsToken["']?\s*[:=]\s*["']([0-9A-Fa-f]{20,})["']"""),
    re.compile(r"jsToken%22%3A%22([0-9A-Fa-f]{20,})%22"),
    re.compile(r"fn%28%22([0-9A-Fa-f]{20,})%22%29"),
    re.compile(r'fn\("([0-9A-Fa-f]{20,})"\)'),
)

_IMAGE_EXTS = frozenset({"jpg", "jpeg", "png", "webp", "gif", "heic"})

# The dlink refuses requests without a browser UA and a TeraBox referer.
REQUIRED_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    ),
    "Referer": "https://www.terabox.com/",
}


def handles(url: str) -> bool:
    match = _HOST.match((url or "").strip())
    if not match:
        return False
    host = match.group(2).rsplit("@", 1)[-1].split(":")[0].lower().rstrip(".")
    bare = host[4:] if host.startswith("www.") else host
    return any(bare == h or bare.endswith(f".{h}") for h in HOSTS)


def extract_surl(url: str) -> str | None:
    """The share id.

    ``terabox.com/s/1AbCdEf`` and ``?surl=AbCdEf`` both appear. The ``/s/`` form
    carries a leading ``1`` the API doesn't want, so it's stripped.
    """
    found = _SURL_PARAM.search(url)
    if found:
        return found.group(1)
    found = _SURL_PATH.search(url)
    if found:
        raw = found.group(1)
        return raw[1:] if raw.startswith("1") and len(raw) > 1 else raw
    return None


def extract_js_token(html: str) -> str | None:
    """The anti-CSRF token from the share page.

    It appears plainly, percent-encoded inside a script, or wrapped in an
    ``fn("…")`` call depending on which variant of the page is served.
    """
    for pattern in _TOKEN_PATTERNS:
        found = pattern.search(html)
        if found:
            return found.group(1)
    return None


def share_page_url(surl: str) -> str:
    return f"https://www.terabox.com/wap/share/filelist?surl={surl}"


def share_info_url(surl: str, js_token: str) -> str:
    return (
        "https://www.terabox.com/api/shorturlinfo"
        "?app_id=250528&web=1&channel=dubox&clienttype=0"
        f"&jsToken={js_token}&shorturl={surl}&root=1"
    )


def parse_share_info(
    body: str, fallback_title: str = "TeraBox file"
) -> "DirectResult | None":
    """Turn a ``shorturlinfo`` response into offerable media.

    Returns None when the response carries no usable ``dlink`` — typically
    ``errno`` is non-zero because the token expired or the share is private.
    """
    from . import DirectMedia, DirectResult

    try:
        payload: Any = json.loads(body)
    except (ValueError, json.JSONDecodeError):
        return None
    if not isinstance(payload, dict):
        return None

    entries = payload.get("list")
    if not isinstance(entries, list) or not entries:
        return None

    media: list[DirectMedia] = []
    title: str | None = None
    thumbnail: str | None = None

    for entry in entries:
        if not isinstance(entry, dict):
            continue
        # Directories have isdir 1 and no dlink; skip rather than offering a
        # download that can't work.
        if str(entry.get("isdir", "0")) == "1":
            continue
        dlink = entry.get("dlink")
        if not isinstance(dlink, str) or not dlink.startswith("http"):
            continue

        name = entry.get("server_filename") or entry.get("filename")
        name = name if isinstance(name, str) else None
        size = _as_int(entry.get("size"))
        if title is None:
            title = name
        if thumbnail is None:
            thumbnail = _thumbnail_from(entry)

        ext = "mp4"
        if name and "." in name:
            candidate = name.rsplit(".", 1)[1].lower()
            if 2 <= len(candidate) <= 5 and candidate.isalnum():
                ext = candidate

        media.append(
            DirectMedia(
                url=dlink,
                label=name or f"Original file ({ext.upper()})",
                ext=ext,
                size_bytes=size,
                kind="image" if ext in _IMAGE_EXTS else "progressive",
                headers=dict(REQUIRED_HEADERS),
            )
        )

    if not media:
        return None
    return DirectResult(
        title=title or fallback_title,
        thumbnail=thumbnail,
        media=tuple(media),
    )


def resolve(client: "httpx.Client", url: str) -> "DirectResult | None":
    import logging

    logger = logging.getLogger(__name__)

    surl = extract_surl(url)
    if not surl:
        logger.warning("terabox: no share id in %s", url)
        return None

    page = client.get(share_page_url(surl))
    page.raise_for_status()
    token = extract_js_token(page.text)
    if not token:
        logger.warning("terabox: no jsToken on the share page")
        return None

    # The API only answers with the cookies the share page just set; the shared
    # httpx client carries them automatically.
    info = client.get(
        share_info_url(surl, token),
        headers={"Referer": share_page_url(surl)},
    )
    info.raise_for_status()
    return parse_share_info(info.text)


def _as_int(value: Any) -> int | None:
    """`size` arrives as a quoted string as often as a number."""
    try:
        parsed = int(str(value))
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def _thumbnail_from(entry: dict[str, Any]) -> str | None:
    """`thumbs` holds url1/url2/url3 at increasing sizes; take the largest."""
    thumbs = entry.get("thumbs")
    if not isinstance(thumbs, dict):
        return None
    for key in ("url3", "url2", "url1"):
        value = thumbs.get(key)
        if isinstance(value, str) and value.startswith("http"):
            return value
    return None
