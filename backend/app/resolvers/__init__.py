"""Sites yt-dlp can't handle, resolved here instead.

Runs *before* yt-dlp and only for URLs a resolver recognises; everything else
falls straight through. A resolver returning None also falls through, so a
failure here never makes a link worse than it was.

Mirrors ``android/.../data/CustomResolvers.kt``. The parsing is kept pure and
separate from the fetching for the same reason it is there — scraped markup is
the code most likely to be wrong, so it has to be testable without a network.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Literal

import anyio
import httpx

from ..config import get_settings
from . import terabox, threads

logger = logging.getLogger(__name__)

MediaKind = Literal["progressive", "image"]

BROWSER_UA = (
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
)


@dataclass(frozen=True, slots=True)
class DirectMedia:
    """Media a resolver located itself, as a direct URL."""

    url: str
    label: str
    ext: str
    height: int | None = None
    size_bytes: int | None = None
    kind: MediaKind = "progressive"
    # Headers the host requires — Referer for TeraBox, a browser UA for both.
    headers: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class DirectResult:
    title: str
    media: tuple[DirectMedia, ...]
    thumbnail: str | None = None
    duration: int | None = None
    uploader: str | None = None


def handles(url: str) -> bool:
    return threads.handles(url) or terabox.handles(url)


async def resolve(url: str) -> DirectResult | None:
    """None means "not mine, or I couldn't" — the caller then tries yt-dlp."""
    try:
        return await anyio.to_thread.run_sync(_resolve_sync, url)
    except Exception:  # noqa: BLE001 - never let a resolver break the request
        logger.warning("custom resolve failed for %s", url, exc_info=True)
        return None


def _resolve_sync(url: str) -> DirectResult | None:
    settings = get_settings()
    with httpx.Client(
        follow_redirects=True,
        timeout=httpx.Timeout(settings.upstream_timeout),
        headers={"User-Agent": BROWSER_UA, "Accept-Language": "en-US,en;q=0.9"},
        proxy=settings.proxy or None,
    ) as client:
        if threads.handles(url):
            return threads.resolve(client, url)
        if terabox.handles(url):
            return terabox.resolve(client, url)
    return None
