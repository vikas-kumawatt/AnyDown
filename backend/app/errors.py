"""Error taxonomy and the single JSON error shape the API returns.

Every failure the client can see is one of ``ErrorCode``; the response body is
always ``{"error": <code>, "message": <human readable>}`` per PRD section 8.
"""

from __future__ import annotations

import re
from enum import Enum


class ErrorCode(str, Enum):
    """String enum (not StrEnum, to stay compatible with Python 3.10)."""

    UNSUPPORTED_URL = "UNSUPPORTED_URL"
    EXTRACTION_FAILED = "EXTRACTION_FAILED"
    PRIVATE_CONTENT = "PRIVATE_CONTENT"
    RATE_LIMITED = "RATE_LIMITED"
    NO_FORMATS = "NO_FORMATS"
    BUSY = "BUSY"
    UPSTREAM_ERROR = "UPSTREAM_ERROR"
    INVALID_REQUEST = "INVALID_REQUEST"
    UNAUTHORIZED = "UNAUTHORIZED"

    def __str__(self) -> str:
        return self.value


class AppError(Exception):
    """Raised anywhere in the app; converted to the standard JSON shape."""

    def __init__(self, code: ErrorCode, message: str, status_code: int = 400) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


# Substrings yt-dlp emits when content exists but needs a logged-in session.
# Public-content-only is an explicit non-goal boundary (PRD section 3), so these
# map to PRIVATE_CONTENT rather than a generic failure.
_PRIVATE_PATTERNS = (
    "private",
    "login required",
    "log in",
    "sign in",
    "requires authentication",
    "members-only",
    "members only",
    "subscribe to this channel",
    "age-restricted",
    "age restricted",
    "confirm your age",
    "cookies",
    "not authorized",
    "this account is",
    "follow this account",
)

_UNSUPPORTED_PATTERNS = (
    "unsupported url",
    "no suitable extractor",
    "is not a valid url",
)

_GONE_PATTERNS = (
    "video unavailable",
    "does not exist",
    "not found",
    "has been removed",
    "no longer available",
    "account has been terminated",
)


def classify_extractor_error(raw_message: str) -> tuple[ErrorCode, str]:
    """Map a yt-dlp error string onto a client-facing code and message.

    yt-dlp has no structured error types, so string matching is the only option.
    Unrecognised errors deliberately fall through to EXTRACTION_FAILED rather
    than guessing.
    """
    text = _strip_ansi(raw_message).lower()

    if any(pattern in text for pattern in _UNSUPPORTED_PATTERNS):
        return (
            ErrorCode.UNSUPPORTED_URL,
            "This link isn't from a supported platform, or isn't a media page.",
        )
    if any(pattern in text for pattern in _PRIVATE_PATTERNS):
        return (
            ErrorCode.PRIVATE_CONTENT,
            "This content isn't publicly viewable. Only public content is supported.",
        )
    if any(pattern in text for pattern in _GONE_PATTERNS):
        return (
            ErrorCode.EXTRACTION_FAILED,
            "The platform says this content no longer exists.",
        )
    return (
        ErrorCode.EXTRACTION_FAILED,
        "Couldn't extract media from this link. The platform may have changed "
        "or the content may be unavailable.",
    )


_ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def _strip_ansi(text: str) -> str:
    return _ANSI_RE.sub("", text)
