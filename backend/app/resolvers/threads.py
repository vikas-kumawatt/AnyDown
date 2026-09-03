"""Finds the media in a Threads post's HTML.

yt-dlp has no Threads extractor — an open request since 2024 — so the post page
is read directly. Threads is Instagram's sibling and ships the same embedded
JSON, so the media sits in ``video_versions`` and ``image_versions2.candidates``
blobs inside the served HTML.

Written defensively: it tries the structured path first and falls back to
scanning for any Meta-CDN media URL. Meta reshapes these payloads regularly; the
fallback is what keeps it working through a rename.

Port of ``android/.../domain/ThreadsParser.kt``. [parse] is pure so it can be
tested without a network.
"""

from __future__ import annotations

import json
import re
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover - import cycle only matters to type checkers
    import httpx

    from . import DirectResult

_VIDEO_URL = re.compile(r"""https?://[^"'\\\s]+?\.mp4[^"'\\\s]*""", re.IGNORECASE)
_META = re.compile(
    r"""<meta[^>]+(?:property|name)=["']([^"']+)["'][^>]+content=["']([^"']*)["']""",
    re.IGNORECASE,
)
_META_REVERSED = re.compile(
    r"""<meta[^>]+content=["']([^"']*)["'][^>]+(?:property|name)=["']([^"']+)["']""",
    re.IGNORECASE,
)
_HANDLE = re.compile(r"\(@([A-Za-z0-9._]+)\)")

# Meta's media CDN hosts. Used to tell post media from site furniture.
_CDN_HINTS = ("cdninstagram", "fbcdn", "scontent")

_HOST = re.compile(r"^(https?)://([^/?#\s]+)", re.IGNORECASE)


def handles(url: str) -> bool:
    match = _HOST.match((url or "").strip())
    if not match:
        return False
    host = match.group(2).rsplit("@", 1)[-1].split(":")[0].lower().rstrip(".")
    bare = host[4:] if host.startswith("www.") else host
    # Exact match or a real subdomain. A bare endswith would accept
    # "notthreads.net" and "threads.net.evil.com".
    return any(
        bare == domain or bare.endswith(f".{domain}")
        for domain in ("threads.net", "threads.com")
    )


def resolve(client: "httpx.Client", url: str) -> "DirectResult | None":
    # Ask for the .net host: it serves the same post and is the form Meta's own
    # embeds use, so it's the more stable of the two.
    target = url.replace("threads.com", "threads.net")
    response = client.get(
        target,
        headers={
            # Without a browser-ish Accept header Threads serves a shell with no
            # embedded media JSON at all.
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Sec-Fetch-Mode": "navigate",
        },
    )
    response.raise_for_status()
    return parse(response.text)


def _unescape(text: str) -> str:
    """Resolve JSON/JS string escapes without a full parse."""
    if "\\" not in text:
        return text
    try:
        return json.loads(f'"{text.replace(chr(34), chr(92) + chr(34))}"')
    except (ValueError, json.JSONDecodeError):
        return (
            text.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace('\\"', '"')
            .replace("\\\\", "\\")
        )


def _meta_tags(html: str) -> dict[str, str]:
    tags: dict[str, str] = {}
    for match in _META.finditer(html):
        tags.setdefault(match.group(1).lower(), _decode_entities(match.group(2)))
    for match in _META_REVERSED.finditer(html):
        tags.setdefault(match.group(2).lower(), _decode_entities(match.group(1)))
    return tags


def _decode_entities(text: str) -> str:
    for entity, char in (
        ("&quot;", '"'), ("&#039;", "'"), ("&#39;", "'"),
        ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
    ):
        text = text.replace(entity, char)
    return text


def _objects_in(text: str, key: str) -> list[dict[str, Any]]:
    """Every ``"key":[ … ]`` array in the document, flattened and parsed.

    Uses a bracket scan to find each array, then the real json module to read
    it — the scan is only needed because these blobs are embedded in HTML rather
    than served as documents.
    """
    results: list[dict[str, Any]] = []
    marker = f'"{key}"'
    at = text.find(marker)
    while at >= 0:
        cursor = at + len(marker)
        while cursor < len(text) and text[cursor].isspace():
            cursor += 1
        if cursor < len(text) and text[cursor] == ":":
            cursor += 1
            while cursor < len(text) and text[cursor].isspace():
                cursor += 1
            if cursor < len(text) and text[cursor] == "[":
                close = _matching(text, cursor)
                if close > 0:
                    try:
                        parsed = json.loads(text[cursor : close + 1])
                    except (ValueError, json.JSONDecodeError):
                        parsed = []
                    results.extend(x for x in parsed if isinstance(x, dict))
                    at = text.find(marker, close)
                    continue
        at = text.find(marker, at + len(marker))
    return results


def _matching(text: str, open_at: int) -> int:
    opener = text[open_at]
    closer = "]" if opener == "[" else "}"
    depth = 0
    in_string = False
    i = open_at
    while i < len(text):
        char = text[i]
        if in_string:
            if char == "\\":
                i += 2
                continue
            if char == '"':
                in_string = False
            i += 1
            continue
        if char == '"':
            in_string = True
        elif char == opener:
            depth += 1
        elif char == closer:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def _extension_of(url: str, fallback: str) -> str:
    name = url.split("?")[0].rsplit("/", 1)[-1]
    if "." in name:
        ext = name.rsplit(".", 1)[1]
        if 2 <= len(ext) <= 5 and ext.isalnum():
            return ext.lower()
    return fallback


def _media(url: str, height: int | None, kind: str):
    from . import DirectMedia  # local import: avoids a package import cycle

    ext = _extension_of(url, "jpg" if kind == "image" else "mp4")
    if kind == "image":
        label = f"Image {height}px ({ext.upper()})" if height else f"Image ({ext.upper()})"
    else:
        label = f"{height}p {ext.upper()}" if height else f"Original quality ({ext.upper()})"
    return DirectMedia(
        url=url,
        label=label,
        ext=ext,
        height=height,
        kind="image" if kind == "image" else "progressive",
        headers=dict(REQUIRED_HEADERS),
    )


# Meta's CDN rejects requests without a browser UA and a Threads referer.
REQUIRED_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    ),
    "Referer": "https://www.threads.net/",
}


def parse(html: str, fallback_title: str = "Threads post") -> "DirectResult | None":
    from . import DirectResult

    text = _unescape(html)
    meta = _meta_tags(html)

    videos = _collect_videos(text)
    images = _collect_images(text, meta) if not videos else []
    if not videos and not images:
        return None

    raw_title = meta.get("og:title") or meta.get("twitter:title")
    title = _clean_title(raw_title) if raw_title else fallback_title

    uploader = None
    if meta.get("og:title"):
        found = _HANDLE.search(meta["og:title"])
        uploader = found.group(1) if found else None

    return DirectResult(
        title=title or fallback_title,
        thumbnail=meta.get("og:image"),
        uploader=uploader,
        media=tuple(videos + images),
    )


def _collect_videos(text: str) -> list:
    found: dict[str, Any] = {}
    for entry in _objects_in(text, "video_versions"):
        url = entry.get("url")
        if not isinstance(url, str) or ".mp4" not in url.lower():
            continue
        height = entry.get("height")
        height = height if isinstance(height, int) and height > 0 else None
        found.setdefault(url.split("?")[0], _media(url, height, "progressive"))

    if not found:
        for match in _VIDEO_URL.finditer(text):
            url = match.group(0).rstrip("\\,)")
            if not any(hint in url for hint in _CDN_HINTS):
                continue
            found.setdefault(url.split("?")[0], _media(url, None, "progressive"))

    return sorted(found.values(), key=lambda m: -(m.height or 0))


def _collect_images(text: str, meta: dict[str, str]) -> list:
    found: dict[str, Any] = {}
    for entry in _objects_in(text, "candidates"):
        url = entry.get("url")
        if not isinstance(url, str) or not any(hint in url for hint in _CDN_HINTS):
            continue
        height = entry.get("height")
        height = height if isinstance(height, int) and height > 0 else None
        found.setdefault(url.split("?")[0], _media(url, height, "image"))

    if not found:
        # Only accept og:image when it's on Meta's media CDN. Matching any image
        # URL would defeat the point — a site logo is not the post's media.
        og_image = meta.get("og:image")
        if og_image and any(hint in og_image for hint in _CDN_HINTS):
            found.setdefault(og_image.split("?")[0], _media(og_image, None, "image"))

    # A post's image ladder is the same picture at several sizes.
    return sorted(found.values(), key=lambda m: -(m.height or 0))[:3]


def _clean_title(raw: str) -> str:
    """og:title reads ``Name (@handle) on Threads``. Keep only the name.

    The handle is reported separately as the uploader, so repeating it in the
    title just makes the filename worse.
    """
    without_suffix = raw.split(" on Threads")[0]
    without_handle = _HANDLE.sub("", without_suffix).replace("  ", " ")
    return without_handle.strip() or without_suffix.strip() or raw.strip()
