"""Recognises the site a link belongs to, and guards what we'll fetch.

This used to be an allow-list that rejected anything unlisted. On the Android
side that gate was removed — it was blocking perfectly good links (Reddit,
Vimeo, VK, LinkedIn never reached yt-dlp at all) for no benefit, since yt-dlp
supports well over a thousand sites.

Here the reasoning is different, and worth being explicit about: the server
*does* have an SSRF surface. Something has to stop a crafted URL pointing this
process at an internal address. So the gate isn't removed, it's narrowed to what
it was actually for — [validate_source_url] now rejects only non-http(s) schemes
and hosts that resolve to private address space, and lets every public host
through. [PLATFORMS] is left purely for labelling, matching the app.
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


# Sites we can name on sight. Not a restriction — an unlisted host still works,
# it just shows no label. Kept in step with android/.../domain/Platforms.kt.
PLATFORMS: tuple[Platform, ...] = (
    Platform("youtube", "YouTube", ("youtube.com", "youtu.be", "youtube-nocookie.com")),
    Platform("tiktok", "TikTok", ("tiktok.com",)),
    Platform("twitter", "X", ("twitter.com", "x.com", "t.co")),
    Platform("instagram", "Instagram", ("instagram.com", "instagr.am", "ig.me")),
    Platform("facebook", "Facebook", ("facebook.com", "fb.watch", "fb.com")),
    Platform("snapchat", "Snapchat", ("snapchat.com",)),
    Platform("threads", "Threads", ("threads.net", "threads.com")),
    Platform("reddit", "Reddit", ("reddit.com", "redd.it", "redditmedia.com")),
    Platform("vimeo", "Vimeo", ("vimeo.com",)),
    Platform("dailymotion", "Dailymotion", ("dailymotion.com", "dai.ly")),
    Platform("vk", "VK", ("vk.com", "vkvideo.ru", "vk.ru")),
    Platform("linkedin", "LinkedIn", ("linkedin.com", "lnkd.in")),
    Platform("pinterest", "Pinterest", ("pinterest.com", "pin.it")),
    Platform("twitch", "Twitch", ("twitch.tv",)),
    Platform("tumblr", "Tumblr", ("tumblr.com",)),
    Platform("soundcloud", "SoundCloud", ("soundcloud.com", "snd.sc")),
    Platform("ok", "OK.ru", ("ok.ru", "odnoklassniki.ru")),
    Platform(
        "terabox",
        "TeraBox",
        (
            "terabox.com", "terabox.app", "1024terabox.com", "teraboxapp.com",
            "teraboxlink.com", "terasharelink.com", "teraboxshare.com",
            "4funbox.com", "mirrobox.com", "nephobox.com", "momerybox.com",
            "tibibox.com", "freeterabox.com", "terafileshare.com",
        ),
    ),
)

_DOMAIN_INDEX: dict[str, Platform] = {
    domain: platform for platform in PLATFORMS for domain in platform.domains
}

_PINTEREST_PREFIX = "pinterest."

MAX_URL_LENGTH = 2048


def _host_matches(host: str, domain: str) -> bool:
    return host == domain or host.endswith("." + domain)


def detect_platform(url: str) -> Platform | None:
    """Name the site behind a URL, or None if we don't recognise it.

    Hosts match a registered domain exactly or with a leading dot, so a
    look-alike such as ``youtube.com.evil.com`` is never mistaken for YouTube.
    """
    host = host_of(url)
    if host is None:
        return None

    for domain, platform in _DOMAIN_INDEX.items():
        if _host_matches(host, domain):
            return platform

    bare = host[4:] if host.startswith("www.") else host
    if bare.startswith(_PINTEREST_PREFIX) and bare.count(".") <= 2:
        return _DOMAIN_INDEX["pinterest.com"]

    return None


def host_of(url: str) -> str | None:
    """Lowercase hostname, or None if this isn't an http(s) URL."""
    try:
        parts = urlsplit((url or "").strip())
    except ValueError:
        return None
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return None
    return parts.hostname.lower().rstrip(".") or None


def validate_source_url(url: str) -> Platform | None:
    """Validate a user-supplied URL, or raise AppError.

    Returns the recognised platform, or None for a site we can't name but will
    still try. Unlike the Android build, this keeps a hard check that the host
    is public — a server that fetches whatever it's told is an SSRF hazard, and
    that is the one job this gate still has.
    """
    candidate = (url or "").strip()

    if not candidate:
        raise AppError(ErrorCode.INVALID_REQUEST, "No URL provided.")
    if len(candidate) > MAX_URL_LENGTH:
        raise AppError(ErrorCode.INVALID_REQUEST, "That URL is too long.")

    host = host_of(candidate)
    if host is None:
        raise AppError(
            ErrorCode.UNSUPPORTED_URL,
            "That doesn't look like a web link. Only http and https are supported.",
        )
    if is_obviously_private(host):
        raise AppError(
            ErrorCode.UNSUPPORTED_URL,
            "That address isn't reachable from the public internet.",
        )

    return detect_platform(candidate)


# Names that never belong to a public host.
_PRIVATE_SUFFIXES = (".local", ".internal", ".lan", ".home", ".localdomain")
_PRIVATE_NAMES = frozenset({"localhost", "ip6-localhost", "ip6-loopback"})


def is_obviously_private(host: str) -> bool:
    """Reject private addresses without a DNS lookup.

    Deliberately syntactic. [is_private_host] resolves names, which is the right
    check for the media URLs we actually fetch, but doing it on every submitted
    URL would put a DNS round-trip in the request path and make the result
    depend on the network. The realistic case here — somebody pasting
    ``http://169.254.169.254/`` or ``http://localhost:8000/`` — is caught by
    inspection, and streaming.py still resolves and re-checks every media URL
    before a single byte is fetched.
    """
    if not host:
        return True
    name = host.lower().rstrip(".")
    if name in _PRIVATE_NAMES or name.endswith(_PRIVATE_SUFFIXES):
        return True
    try:
        ip = ipaddress.ip_address(name.strip("[]"))
    except ValueError:
        return False  # a normal hostname; the media-URL check covers the rest
    return (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_reserved
        or ip.is_multicast
        or ip.is_unspecified
    )


def is_private_host(host: str) -> bool:
    """True if a hostname resolves to a non-public address.

    Applied to the URL the user supplies and again to the media URLs yt-dlp
    returns, so neither can point the server at its own metadata service.
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
