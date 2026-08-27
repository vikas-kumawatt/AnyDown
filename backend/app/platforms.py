"""Platform allow-list and URL validation.

The allow-list is the primary SSRF control (PRD section 9): nothing reaches
yt-dlp unless its hostname matches a known platform. A second check runs later
against the *media* URLs yt-dlp hands back, since those are attacker-influenced
in a different way.
"""

from __future__ import annotations

import ipaddress
import socket
from dataclasses import dataclass
from urllib.parse import urlsplit

from .errors import AppError, ErrorCode


@dataclass(frozen=True, slots=True)
class Platform:
    id: str
    label: str
    domains: tuple[str, ...]


# Registered domains only. Subdomains are matched automatically (see
# _host_matches), so "m.youtube.com" and "www.youtube.com" both resolve here.
PLATFORMS: tuple[Platform, ...] = (
    Platform(
        "youtube",
        "YouTube",
        ("youtube.com", "youtu.be", "youtube-nocookie.com"),
    ),
    Platform("tiktok", "TikTok", ("tiktok.com", "vm.tiktok.com", "vt.tiktok.com")),
    Platform("twitter", "X / Twitter", ("twitter.com", "x.com", "t.co")),
    Platform("dailymotion", "Dailymotion", ("dailymotion.com", "dai.ly")),
    Platform("instagram", "Instagram", ("instagram.com", "instagr.am", "ig.me")),
    Platform("facebook", "Facebook", ("facebook.com", "fb.watch", "fb.com")),
    Platform("pinterest", "Pinterest", ("pinterest.com", "pin.it")),
    Platform("threads", "Threads", ("threads.net", "threads.com")),
    Platform("snapchat", "Snapchat", ("snapchat.com",)),
)

_DOMAIN_INDEX: dict[str, Platform] = {
    domain: platform for platform in PLATFORMS for domain in platform.domains
}

# Pinterest also serves country TLDs (pinterest.co.uk, pinterest.de, ...).
_PINTEREST_PREFIX = "pinterest."

MAX_URL_LENGTH = 2048


def _host_matches(host: str, domain: str) -> bool:
    return host == domain or host.endswith("." + domain)


def detect_platform(url: str) -> Platform | None:
    """Return the platform for a URL, or None if it isn't on the allow-list."""
    try:
        parts = urlsplit(url.strip())
    except ValueError:
        return None

    if parts.scheme not in ("http", "https") or not parts.hostname:
        return None

    host = parts.hostname.lower().rstrip(".")

    for domain, platform in _DOMAIN_INDEX.items():
        if _host_matches(host, domain):
            return platform

    # pinterest.<cctld> and www.pinterest.<cctld>
    bare = host[4:] if host.startswith("www.") else host
    if bare.startswith(_PINTEREST_PREFIX) and bare.count(".") <= 2:
        return _DOMAIN_INDEX["pinterest.com"]

    return None


def validate_source_url(url: str) -> Platform:
    """Validate a user-supplied URL, or raise AppError.

    This is the only gate between untrusted input and yt-dlp.
    """
    candidate = (url or "").strip()

    if not candidate:
        raise AppError(ErrorCode.INVALID_REQUEST, "No URL provided.")
    if len(candidate) > MAX_URL_LENGTH:
        raise AppError(ErrorCode.INVALID_REQUEST, "That URL is too long.")

    platform = detect_platform(candidate)
    if platform is None:
        supported = ", ".join(p.label for p in PLATFORMS)
        raise AppError(
            ErrorCode.UNSUPPORTED_URL,
            f"Only links from these platforms are supported: {supported}.",
        )
    return platform


def is_private_host(host: str) -> bool:
    """True if a hostname resolves to a non-public address.

    Used on the media URLs yt-dlp returns so a crafted page can't point the
    server at its own metadata service or an internal host.
    """
    if not host:
        return True
    try:
        infos = socket.getaddrinfo(host, None, proto=socket.IPPROTO_TCP)
    except OSError:
        return True

    for info in infos:
        address = info[4][0]
        try:
            ip = ipaddress.ip_address(address)
        except ValueError:
            return True
        if (
            ip.is_private
            or ip.is_loopback
            or ip.is_link_local
            or ip.is_reserved
            or ip.is_multicast
            or ip.is_unspecified
        ):
            return True
    return False
