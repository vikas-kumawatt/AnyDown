"""End-to-end check of the ffmpeg mux path against a real local HTTP server.

The argv unit tests can't tell you whether ffmpeg actually produces a playable
file from two separate URL inputs piped to stdout. This does: it generates a
video-only and an audio-only file, serves them over HTTP, runs the real
pipeline, and probes the result.

Skipped automatically when ffmpeg/ffprobe aren't installed.
"""

from __future__ import annotations

import functools
import http.server
import json
import shutil
import socketserver
import subprocess
import tempfile
import threading
from pathlib import Path

import pytest

from app.extractor import DownloadPlan, MediaSource
from app.streaming import open_ffmpeg_stream

pytestmark = pytest.mark.skipif(
    not (shutil.which("ffmpeg") and shutil.which("ffprobe")),
    reason="ffmpeg/ffprobe not installed",
)


@pytest.fixture(scope="module")
def media_server():
    """Serve a generated video-only and audio-only file over HTTP."""
    directory = tempfile.mkdtemp(prefix="dl-test-")

    subprocess.run(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
         "-f", "lavfi", "-i", "testsrc=size=320x240:rate=15:duration=2",
         "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an",
         f"{directory}/video.mp4"],
        check=True, timeout=90,
    )
    subprocess.run(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
         "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
         "-c:a", "aac", "-vn", f"{directory}/audio.m4a"],
        check=True, timeout=90,
    )

    handler = functools.partial(
        http.server.SimpleHTTPRequestHandler, directory=directory
    )
    server = socketserver.TCPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    yield f"http://127.0.0.1:{server.server_address[1]}"

    server.shutdown()
    server.server_close()
    shutil.rmtree(directory, ignore_errors=True)


def _probe(path: Path) -> list[dict]:
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(path)],
        capture_output=True, text=True, timeout=30, check=True,
    )
    return json.loads(result.stdout)["streams"]


async def _collect(plan: DownloadPlan, tmp_path: Path) -> Path:
    _, chunks = await open_ffmpeg_stream(plan)
    output = tmp_path / f"out.{plan.ext}"
    with output.open("wb") as handle:
        async for chunk in chunks:
            handle.write(chunk)
    return output


async def test_merge_produces_playable_av_file(media_server: str, tmp_path: Path):
    plan = DownloadPlan(
        id="m-v+a", kind="merge", label="test", ext="mp4", height=240,
        filesize=None,
        video=MediaSource(f"{media_server}/video.mp4", "mp4", "https", {}),
        audio=MediaSource(f"{media_server}/audio.m4a", "m4a", "https", {}),
        needs_ffmpeg=True, container="mp4",
    )

    output = await _collect(plan, tmp_path)

    assert output.stat().st_size > 0
    kinds = sorted(s["codec_type"] for s in _probe(output))
    assert kinds == ["audio", "video"], "merged output must carry both streams"


async def test_merged_output_is_fragmented_from_the_first_bytes(
    media_server: str, tmp_path: Path
):
    """A plain MP4 puts `moov` at the end, which would break streaming."""
    plan = DownloadPlan(
        id="m-v+a", kind="merge", label="test", ext="mp4", height=240,
        filesize=None,
        video=MediaSource(f"{media_server}/video.mp4", "mp4", "https", {}),
        audio=MediaSource(f"{media_server}/audio.m4a", "m4a", "https", {}),
        needs_ffmpeg=True, container="mp4",
    )

    _, chunks = await open_ffmpeg_stream(plan)
    first = b""
    async for chunk in chunks:
        first += chunk
        break

    assert b"ftyp" in first[:64]
    assert b"moov" in first, "empty_moov must be emitted up front"


async def test_missing_upstream_raises_before_streaming(
    media_server: str, tmp_path: Path
):
    from app.errors import AppError

    plan = DownloadPlan(
        id="m-missing", kind="progressive", label="test", ext="mp4", height=240,
        filesize=None,
        video=MediaSource(f"{media_server}/nope.mp4", "mp4", "https", {}),
        audio=None, needs_ffmpeg=True, container="mp4",
    )

    with pytest.raises(AppError):
        await _collect(plan, tmp_path)


async def test_client_disconnect_kills_ffmpeg(media_server: str, monkeypatch):
    """Abandoning the iterator must not leave an orphaned ffmpeg process."""
    import asyncio

    spawned = []
    real_exec = asyncio.create_subprocess_exec

    async def spy(*args, **kwargs):
        process = await real_exec(*args, **kwargs)
        spawned.append(process)
        return process

    monkeypatch.setattr(asyncio, "create_subprocess_exec", spy)

    plan = DownloadPlan(
        id="m-v+a", kind="merge", label="test", ext="mp4", height=240,
        filesize=None,
        video=MediaSource(f"{media_server}/video.mp4", "mp4", "https", {}),
        audio=MediaSource(f"{media_server}/audio.m4a", "m4a", "https", {}),
        needs_ffmpeg=True, container="mp4",
    )

    _, chunks = await open_ffmpeg_stream(plan)
    async for _chunk in chunks:
        break
    await chunks.aclose()  # what Starlette does when the client goes away

    assert len(spawned) == 1
    assert spawned[0].returncode is not None, "ffmpeg was left running"
