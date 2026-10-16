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
from typing import ClassVar

import pytest

from app.extractor import DownloadPlan, MediaSource
from app.streaming import open_ffmpeg_stream

pytestmark = pytest.mark.skipif(
    not (shutil.which("ffmpeg") and shutil.which("ffprobe")),
    reason="ffmpeg/ffprobe not installed",
)


@pytest.fixture(scope="module")
def media_dir():
    """A generated video-only and audio-only file."""
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
    yield directory
    shutil.rmtree(directory, ignore_errors=True)


def _serve(handler):
    server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


@pytest.fixture(scope="module")
def media_server(media_dir: str):
    """Serve the generated files over plain HTTP."""
    handler = functools.partial(
        http.server.SimpleHTTPRequestHandler, directory=media_dir
    )
    server = _serve(handler)
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()
    server.server_close()


# Small enough that the generated files take several ranges each.
RANGE_LIMIT = 4 * 1024


class _StrictRangeHandler(http.server.BaseHTTPRequestHandler):
    """Behaves like YouTube's media host: ranged requests only, and small ones.

    A request without a Range, or for more than RANGE_LIMIT bytes, is refused,
    so a test passes only if the client really fetched in chunks.
    """

    # Both replaced per server by the ranged_server fixture.
    directory = ""
    requests: ClassVar[list[str]] = []

    def do_GET(self):
        path = Path(self.directory) / self.path.lstrip("/")
        if not path.is_file():
            self.send_error(404)
            return
        data = path.read_bytes()
        spec = self.headers.get("Range", "")
        type(self).requests.append(spec)
        if not spec.startswith("bytes="):
            self.send_error(403, "ranged requests only")
            return
        start_s, _, end_s = spec[len("bytes="):].partition("-")
        start = int(start_s)
        end = min(int(end_s) if end_s else len(data) - 1, len(data) - 1)
        if end - start + 1 > RANGE_LIMIT:
            self.send_error(403, "range too large")
            return
        body = data[start : end + 1]
        self.send_response(206)
        self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


@pytest.fixture
def ranged_server(media_dir: str):
    handler = type("Handler", (_StrictRangeHandler,), {
        "directory": media_dir, "requests": [],
    })
    server = _serve(handler)
    yield f"http://127.0.0.1:{server.server_address[1]}", handler
    server.shutdown()
    server.server_close()


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


async def test_chunked_sources_are_fetched_in_ranges_and_merged(
    ranged_server, tmp_path: Path
):
    """YouTube-style sources: ffmpeg can't fetch them itself without throttling."""
    base, handler = ranged_server
    plan = DownloadPlan(
        id="m-v+a", kind="merge", label="test", ext="mp4", height=240,
        filesize=None,
        video=MediaSource(f"{base}/video.mp4", "mp4", "https", {}, RANGE_LIMIT),
        audio=MediaSource(f"{base}/audio.m4a", "m4a", "https", {}, RANGE_LIMIT),
        needs_ffmpeg=True, container="mp4",
    )

    output = await _collect(plan, tmp_path)

    kinds = sorted(s["codec_type"] for s in _probe(output))
    assert kinds == ["audio", "video"]
    assert len(handler.requests) > 2, "expected several ranged requests"


async def test_direct_stream_reassembles_ranges_exactly(ranged_server, media_dir):
    from app.streaming import open_direct_stream

    base, handler = ranged_server
    expected = (Path(media_dir) / "video.mp4").read_bytes()
    assert len(expected) > 2 * RANGE_LIMIT, "fixture too small to need ranges"

    length, chunks = await open_direct_stream(
        MediaSource(f"{base}/video.mp4", "mp4", "https", {}, RANGE_LIMIT)
    )
    body = b"".join([chunk async for chunk in chunks])

    assert length == len(expected), "Content-Length must be the whole file"
    assert body == expected
    assert handler.requests[0] == f"bytes=0-{RANGE_LIMIT - 1}"


async def test_unchunked_source_is_one_plain_request(media_server, media_dir):
    from app.streaming import open_direct_stream

    expected = (Path(media_dir) / "audio.m4a").read_bytes()
    length, chunks = await open_direct_stream(
        MediaSource(f"{media_server}/audio.m4a", "m4a", "https", {})
    )
    body = b"".join([chunk async for chunk in chunks])

    assert length == len(expected)
    assert body == expected


async def test_a_host_that_stops_honouring_range_never_duplicates_bytes(
    ranged_server, media_dir, monkeypatch
):
    """A 200 mid-file is the whole body again; appending it would corrupt the file."""
    from app.streaming import open_direct_stream

    base, handler = ranged_server
    real_do_get = handler.do_GET

    def flaky_do_get(self):
        if len(handler.requests) >= 2:
            del self.headers["Range"]
            handler.requests.append("")
            body = (Path(media_dir) / "video.mp4").read_bytes()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        real_do_get(self)

    monkeypatch.setattr(handler, "do_GET", flaky_do_get)

    expected = (Path(media_dir) / "video.mp4").read_bytes()
    _, chunks = await open_direct_stream(
        MediaSource(f"{base}/video.mp4", "mp4", "https", {}, RANGE_LIMIT)
    )
    body = b"".join([chunk async for chunk in chunks])

    assert body == expected[: 2 * RANGE_LIMIT], "truncated, never restarted"
