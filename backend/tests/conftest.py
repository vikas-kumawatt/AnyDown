from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# Set before any app module imports, since settings are read at import time.
os.environ.setdefault("APP_CORS_ORIGINS", "http://testserver")
os.environ.setdefault("APP_RATE_LIMITS", "1000/minute")
os.environ.setdefault("APP_BLOCK_PRIVATE_MEDIA_HOSTS", "false")


@pytest.fixture(autouse=True)
def _clear_extractor_cache():
    from app.extractor import clear_cache

    clear_cache()
    yield
    clear_cache()


@pytest.fixture
def youtube_info() -> dict:
    """Trimmed shape of a real yt-dlp YouTube info dict: split streams only."""
    headers = {"User-Agent": "Mozilla/5.0", "Referer": "https://www.youtube.com/"}
    return {
        "id": "abc123",
        "title": "Example / Video: <test>",
        "thumbnail": "https://i.ytimg.com/vi/abc123/hq.jpg",
        "duration": 212,
        "uploader": "Example Channel",
        "formats": [
            {
                "format_id": "140",
                "url": "https://cdn.example.com/audio-140",
                "ext": "m4a",
                "protocol": "https",
                "vcodec": "none",
                "acodec": "mp4a.40.2",
                "abr": 129.0,
                "filesize": 3_400_000,
                "http_headers": headers,
            },
            {
                "format_id": "251",
                "url": "https://cdn.example.com/audio-251",
                "ext": "webm",
                "protocol": "https",
                "vcodec": "none",
                "acodec": "opus",
                "abr": 120.0,
                "filesize": 3_200_000,
                "http_headers": headers,
            },
            {
                "format_id": "137",
                "url": "https://cdn.example.com/video-137",
                "ext": "mp4",
                "protocol": "https",
                "vcodec": "avc1.640028",
                "acodec": "none",
                "height": 1080,
                "tbr": 4200.0,
                "filesize": 60_000_000,
                "http_headers": headers,
            },
            {
                "format_id": "248",
                "url": "https://cdn.example.com/video-248",
                "ext": "webm",
                "protocol": "https",
                "vcodec": "vp9",
                "acodec": "none",
                "height": 1080,
                "tbr": 3000.0,
                "filesize": 45_000_000,
                "http_headers": headers,
            },
            {
                "format_id": "18",
                "url": "https://cdn.example.com/prog-18",
                "ext": "mp4",
                "protocol": "https",
                "vcodec": "avc1.42001E",
                "acodec": "mp4a.40.2",
                "height": 360,
                "tbr": 700.0,
                "filesize": 18_000_000,
                "http_headers": headers,
            },
            {
                "format_id": "271",
                "url": "https://cdn.example.com/video-271",
                "ext": "webm",
                "protocol": "https",
                "vcodec": "vp9",
                "acodec": "none",
                "height": 1440,
                "tbr": 9000.0,
                "filesize": 150_000_000,
                "http_headers": headers,
            },
            {
                "format_id": "rtmp-legacy",
                "url": "rtmp://example.com/stream",
                "ext": "flv",
                "protocol": "rtmp",
                "vcodec": "avc1",
                "acodec": "mp4a",
                "height": 480,
            },
        ],
    }


@pytest.fixture
def tiktok_info() -> dict:
    """Extractor that reports no codec fields at all — treat as progressive."""
    return {
        "id": "999",
        "title": "TikTok clip",
        "duration": 15,
        "formats": [
            {
                "format_id": "download",
                "url": "https://v.tiktok.example/video.mp4",
                "ext": "mp4",
                "protocol": "https",
                "height": 1024,
                "filesize_approx": 2_500_000,
            }
        ],
    }


@pytest.fixture
def hls_info() -> dict:
    """Playlist protocol: must route through ffmpeg even though it's progressive."""
    return {
        "id": "hls1",
        "title": "Live clip",
        "duration": 60,
        "formats": [
            {
                "format_id": "hls-720",
                "url": "https://cdn.example.com/master.m3u8",
                "ext": "mp4",
                "protocol": "m3u8_native",
                "vcodec": "avc1.4d401f",
                "acodec": "mp4a.40.2",
                "height": 720,
                "tbr": 1800.0,
            }
        ],
    }
