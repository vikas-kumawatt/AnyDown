from __future__ import annotations

import pytest

from app.errors import AppError, ErrorCode
from app.platforms import detect_platform, validate_source_url


@pytest.mark.parametrize(
    ("url", "expected"),
    [
        ("https://www.youtube.com/watch?v=abc", "youtube"),
        ("https://youtu.be/abc", "youtube"),
        ("https://m.youtube.com/watch?v=abc", "youtube"),
        ("https://www.tiktok.com/@u/video/1", "tiktok"),
        ("https://vm.tiktok.com/ZM123/", "tiktok"),
        ("https://x.com/u/status/1", "twitter"),
        ("https://twitter.com/u/status/1", "twitter"),
        ("https://www.dailymotion.com/video/x1", "dailymotion"),
        ("https://dai.ly/x1", "dailymotion"),
        ("https://www.instagram.com/reel/abc/", "instagram"),
        ("https://www.facebook.com/watch?v=1", "facebook"),
        ("https://fb.watch/abc/", "facebook"),
        ("https://www.pinterest.com/pin/1/", "pinterest"),
        ("https://pinterest.co.uk/pin/1/", "pinterest"),
        ("https://pin.it/abc", "pinterest"),
        ("https://www.threads.net/@u/post/1", "threads"),
        ("https://www.snapchat.com/spotlight/abc", "snapchat"),
    ],
)
def test_allowed_urls_map_to_platform(url: str, expected: str) -> None:
    platform = detect_platform(url)
    assert platform is not None
    assert platform.id == expected


@pytest.mark.parametrize(
    "url",
    [
        "https://evil.com/video",
        "http://localhost:8080/admin",
        "http://169.254.169.254/latest/meta-data/",
        "file:///etc/passwd",
        "ftp://youtube.com/x",
        # Look-alike hosts must not match by substring.
        "https://youtube.com.evil.com/watch?v=1",
        "https://notyoutube.com/watch?v=1",
        "https://tiktok.com.attacker.net/v/1",
        "",
        "not a url",
    ],
)
def test_rejected_urls(url: str) -> None:
    assert detect_platform(url) is None


def test_validate_raises_unsupported() -> None:
    with pytest.raises(AppError) as exc:
        validate_source_url("https://evil.com/video")
    assert exc.value.code is ErrorCode.UNSUPPORTED_URL


def test_validate_raises_on_empty() -> None:
    with pytest.raises(AppError) as exc:
        validate_source_url("   ")
    assert exc.value.code is ErrorCode.INVALID_REQUEST


def test_validate_rejects_overlong_url() -> None:
    with pytest.raises(AppError) as exc:
        validate_source_url("https://youtube.com/watch?v=" + "a" * 3000)
    assert exc.value.code is ErrorCode.INVALID_REQUEST


def test_trailing_dot_host_is_normalised() -> None:
    assert detect_platform("https://www.youtube.com./watch?v=abc") is not None
