"""Cleans up a URL before yt-dlp sees it.

Straight port of ``android/.../domain/UrlNormalizer.kt``. Two real failures
drove it:

* **Threads** moved to ``threads.com``, but yt-dlp's extractor pattern still
  matches ``threads.net``, so a valid post comes back as "Unsupported URL".
* **Vimeo** now refuses anonymous extraction on ``vimeo.com`` ("The web client
  only works when logged-in"); ``player.vimeo.com`` still answers without
  credentials.

Plus share sheets bolt on tracking parameters that some extractors choke on.
"""

from __future__ import annotations

import re

# Hosts yt-dlp knows under a different name than the site now uses.
HOST_REWRITES = {
    "threads.com": "threads.net",
    "www.threads.com": "www.threads.net",
}

# Parameters that only ever carry analytics.
#
# Deliberately a deny-list: `v` on YouTube, `list`, `t` and countless per-site
# parameters are load-bearing, and stripping an unknown one breaks far more
# links than it fixes.
TRACKING_PARAMS = frozenset(
    {
        "igsh", "igshid", "xmt", "fbclid", "gclid", "si", "share_id",
        "share_app_id", "_nc_ht", "_r", "_d", "ref_src", "ref_url", "spm",
        "mibextid", "rdid", "slof", "app", "is_from_webapp", "sender_device",
        "sender_web_id", "web_id", "share_link_id", "social_share", "trk",
        "trkemail", "utm_source", "utm_medium", "utm_campaign", "utm_term",
        "utm_content",
    }
)

# Short/share hosts worth resolving before extraction.
SHORTENERS = frozenset(
    {
        "pin.it", "vm.tiktok.com", "vt.tiktok.com", "redd.it", "fb.watch",
        "lnkd.in", "dai.ly", "youtu.be", "t.co", "snd.sc", "ig.me",
        "instagr.am",
    }
)

_SPLIT = re.compile(
    r"^(https?)://([^/?#\s]+)([^?#\s]*)(?:\?([^#\s]*))?(?:#(\S*))?$",
    re.IGNORECASE,
)
_HOST = re.compile(r"^(https?)://([^/?#\s]+)", re.IGNORECASE)
_VIMEO_PATH = re.compile(r"^/(\d{6,})(?:/([0-9a-zA-Z]+))?/?$")


def _host_of(url: str) -> str | None:
    match = _HOST.match((url or "").strip())
    if not match:
        return None
    host = match.group(2).rsplit("@", 1)[-1].split(":")[0]
    return host.lower().rstrip(".") or None


def is_short_link(url: str) -> bool:
    """True when the URL is a redirect stub whose target we should resolve."""
    host = _host_of(url)
    if host is None:
        return False
    bare = host[4:] if host.startswith("www.") else host
    if bare in SHORTENERS:
        return True
    # Threads share links redirect to the real post. Exact match or a real
    # subdomain — a bare endswith would accept "notthreads.net".
    return any(
        bare == domain or bare.endswith(f".{domain}")
        for domain in ("threads.net", "threads.com")
    )


def _rewrite_vimeo(host: str, path: str) -> str | None:
    """``vimeo.com/<id>`` and ``vimeo.com/<id>/<hash>`` for unlisted videos.

    The unlisted-link hash moves to ``?h=``, which the player endpoint expects.
    """
    bare = host[4:] if host.startswith("www.") else host
    if bare != "vimeo.com":
        return None
    match = _VIMEO_PATH.match(path)
    if not match:
        return None
    video_id, unlisted_hash = match.group(1), match.group(2)
    suffix = f"?h={unlisted_hash}" if unlisted_hash else ""
    return f"player.vimeo.com/video/{video_id}{suffix}"


def normalize(raw_url: str) -> str:
    """Rewrite known-renamed hosts and drop tracking parameters."""
    url = (raw_url or "").strip()
    match = _SPLIT.match(url)
    if not match:
        return url

    scheme = match.group(1).lower()
    authority = match.group(2)
    path = match.group(3)
    query = match.group(4) or ""

    bare_authority = authority.rsplit("@", 1)[-1]
    host = bare_authority.split(":")[0].lower()

    # Vimeo replaces host and path together and brings its own query, so it
    # short-circuits the generic handling below.
    vimeo = _rewrite_vimeo(host, path)
    if vimeo is not None:
        return f"{scheme}://{vimeo}"

    rewritten_host = HOST_REWRITES.get(host, host)
    port = bare_authority.split(":", 1)[1] if ":" in bare_authority else ""
    rebuilt = rewritten_host if not port else f"{rewritten_host}:{port}"

    kept = [
        param
        for param in query.split("&")
        if param.strip() and param.split("=")[0].lower() not in TRACKING_PARAMS
    ]

    # The fragment is dropped: no extractor reads it, and share sheets append
    # junk there too.
    result = f"{scheme}://{rebuilt}{path}"
    if kept:
        result += "?" + "&".join(kept)
    return result
