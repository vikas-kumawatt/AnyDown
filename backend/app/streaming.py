"""Zero-disk streaming: source -> (optional ffmpeg) -> HTTP response.

Nothing is written to disk and nothing is buffered to completion. Two paths:

* **Direct** — a single progressive/audio stream over http(s) is proxied byte
  for byte with httpx. Upstream ``Content-Length`` is forwarded, so the phone's
  download manager shows a real progress bar.
* **ffmpeg** — needed for merged video+audio and for playlist protocols
  (HLS/DASH). ffmpeg is given the stream URLs *as inputs* and writes to
  ``pipe:1``; output is fragmented MP4 (or Matroska for VP9/Opus) so it is
  valid from the first byte and needs no seek-back to finalise.

  Note on PRD section 7.2: a single ffmpeg process cannot read two separate
  streams from one stdin, so piping both streams in is not possible. Passing the
  two URLs as inputs achieves the same goal — ffmpeg streams them itself and
  still never touches disk.
"""

from __future__ import annotations

import asyncio
import logging
import os
import re
import unicodedata
from collections import deque
from collections.abc import AsyncIterator
from urllib.parse import quote, urlsplit

import anyio
import httpx

from .config import get_settings
from .errors import AppError, ErrorCode
from .extractor import DownloadPlan, MediaSource
from .platforms import is_private_host

logger = logging.getLogger(__name__)

# Caps simultaneous downloads so one client can't exhaust a free-tier instance.
_download_slots = asyncio.Semaphore(get_settings().max_concurrent_downloads)

_UNSAFE_FILENAME = re.compile(r"[^\w \-.\[\]()&'!,]+", re.UNICODE)
_FIRST_CHUNK_TIMEOUT = 45  # ffmpeg cold start + upstream connect


# --------------------------------------------------------------------------
# Concurrency
# --------------------------------------------------------------------------


class DownloadSlot:
    """Fail-fast concurrency guard. Raises BUSY instead of queueing forever."""

    def __init__(self) -> None:
        self._acquired = False

    async def acquire(self) -> None:
        # No await between the check and the acquire, so on a single event loop
        # this cannot race.
        if _download_slots.locked():
            raise AppError(
                ErrorCode.BUSY,
                "A download is already in progress. Try again in a moment.",
                status_code=503,
            )
        await _download_slots.acquire()
        self._acquired = True

    def release(self) -> None:
        if self._acquired:
            self._acquired = False
            _download_slots.release()


# --------------------------------------------------------------------------
# Filenames
# --------------------------------------------------------------------------


def build_filename(title: str | None, ext: str) -> str:
    base = (title or "download").strip()
    base = unicodedata.normalize("NFKC", base)
    base = _UNSAFE_FILENAME.sub("_", base).strip("._ ")
    base = re.sub(r"\s+", " ", base) or "download"
    return f"{base[:120]}.{ext}"


def content_disposition(filename: str) -> str:
    """RFC 6266 header with an ASCII fallback for older mobile browsers."""
    ascii_name = filename.encode("ascii", "ignore").decode("ascii") or "download"
    ascii_name = ascii_name.replace('"', "")
    return (
        f'attachment; filename="{ascii_name}"; '
        f"filename*=UTF-8''{quote(filename, safe='')}"
    )


# --------------------------------------------------------------------------
# Shared guards
# --------------------------------------------------------------------------


def _assert_public(source: MediaSource) -> None:
    """Reject media URLs pointing at private address space (SSRF defence)."""
    if not get_settings().block_private_media_hosts:
        return
    host = urlsplit(source.url).hostname or ""
    if is_private_host(host):
        logger.error("blocked non-public media host host=%s", host)
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "The platform returned a media address that can't be fetched.",
            status_code=502,
        )


async def _assert_public_async(*sources: MediaSource | None) -> None:
    for source in sources:
        if source is not None:
            await anyio.to_thread.run_sync(_assert_public, source)


# --------------------------------------------------------------------------
# Direct passthrough
# --------------------------------------------------------------------------


async def open_direct_stream(
    source: MediaSource,
) -> tuple[int | None, AsyncIterator[bytes]]:
    """Open an upstream stream and return (content_length, chunk iterator).

    A source with a ``chunk_size`` is fetched as consecutive Range requests no
    larger than that, the way yt-dlp itself downloads it. YouTube serves a
    single whole-file request at roughly playback speed, so a long video took
    about as long to download as to watch.
    """
    settings = get_settings()
    client = httpx.AsyncClient(
        timeout=httpx.Timeout(settings.upstream_timeout, read=None),
        follow_redirects=True,
        limits=httpx.Limits(max_connections=4),
    )
    try:
        response = await _send(client, source, 0)
    except BaseException:
        await client.aclose()
        raise

    total = _total_length(response)

    async def iterator() -> AsyncIterator[bytes]:
        current = response
        offset = 0
        try:
            while True:
                async for chunk in current.aiter_bytes(settings.chunk_size):
                    offset += len(chunk)
                    yield chunk
                await current.aclose()
                # 200 means the host ignored the Range and sent everything.
                if current.status_code != 206 or total is None or offset >= total:
                    break
                current = await _send(client, source, offset)
                if current.status_code != 206:
                    # A full body now would restart from byte 0 mid-file.
                    logger.warning("upstream stopped honouring Range at %s", offset)
                    break
        except (httpx.HTTPError, AppError) as exc:
            # The response has already started; log and end the stream. The
            # client sees a truncated file, which is the only honest outcome.
            logger.warning("upstream stream interrupted: %s", exc)
        finally:
            await current.aclose()
            await client.aclose()

    return total, iterator()


async def _send(
    client: httpx.AsyncClient, source: MediaSource, offset: int
) -> httpx.Response:
    """Request the source from ``offset``, one chunk's worth when it's chunked."""
    headers = dict(source.headers)
    if source.chunk_size:
        headers["Range"] = f"bytes={offset}-{offset + source.chunk_size - 1}"
    request = client.build_request("GET", source.url, headers=headers)
    try:
        response = await client.send(request, stream=True)
    except httpx.HTTPError as exc:
        logger.warning("upstream connect failed: %s", exc)
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "Couldn't reach the media host. Fetch the link again.",
            status_code=502,
        ) from exc

    if response.status_code >= 400:
        status = response.status_code
        await response.aclose()
        logger.warning("upstream rejected request status=%s", status)
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "The media link expired or was rejected. Fetch the link again.",
            status_code=502,
        )
    return response


def _total_length(response: httpx.Response) -> int | None:
    """Full size of the resource: from Content-Range on a 206, else Content-Length."""
    if response.status_code == 206:
        raw = response.headers.get("content-range", "").rpartition("/")[2]
    else:
        raw = response.headers.get("content-length") or ""
    return int(raw) if raw.isdigit() else None


# --------------------------------------------------------------------------
# ffmpeg mux
# --------------------------------------------------------------------------


def _input_args(source: MediaSource, pipe_fd: int | None = None) -> list[str]:
    if pipe_fd is not None:
        return ["-i", f"pipe:{pipe_fd}"]
    args: list[str] = []
    if source.protocol in ("http", "https"):
        args += ["-reconnect", "1", "-reconnect_streamed", "1",
                 "-reconnect_delay_max", "5"]
    if source.headers:
        header_blob = "".join(f"{k}: {v}\r\n" for k, v in source.headers.items())
        args += ["-headers", header_blob]
    args += ["-i", source.url]
    return args


def build_ffmpeg_args(
    plan: DownloadPlan, pipe_fds: dict[int, int] | None = None
) -> list[str]:
    """``pipe_fds`` maps an input's index to the fd it's fed through instead."""
    settings = get_settings()
    args = [settings.ffmpeg_path, "-hide_banner", "-loglevel", "error", "-nostdin"]

    inputs = [source for source in (plan.video, plan.audio) if source is not None]
    if not inputs:
        raise AppError(ErrorCode.EXTRACTION_FAILED, "No stream to download.", 500)

    for index, source in enumerate(inputs):
        args += _input_args(source, (pipe_fds or {}).get(index))

    if plan.video is not None and plan.audio is not None:
        args += ["-map", "0:v:0", "-map", "1:a:0"]
    else:
        args += ["-map", "0"]

    # Stream copy only: no re-encode, so CPU stays near zero and output starts
    # essentially immediately.
    args += ["-c", "copy"]

    container = plan.container or "mp4"
    if container == "mp4":
        args += ["-movflags", "frag_keyframe+empty_moov+default_base_moof"]
    args += ["-f", container, "pipe:1"]
    return args


async def open_ffmpeg_stream(plan: DownloadPlan) -> tuple[None, AsyncIterator[bytes]]:
    """Start ffmpeg and return (None, chunk iterator).

    The first chunk is read here so a failing pipeline surfaces as a JSON error
    instead of a zero-byte "successful" download.
    """
    settings = get_settings()

    # ffmpeg can only fetch a URL as one request, which a throttling host (see
    # open_direct_stream) slows to playback speed. Those inputs are fetched here
    # in ranges and handed to ffmpeg through a pipe instead.
    inputs = [source for source in (plan.video, plan.audio) if source is not None]
    pipes = {
        index: os.pipe()
        for index, source in enumerate(inputs)
        if source.chunk_size and source.protocol in ("http", "https")
    }
    args = build_ffmpeg_args(plan, {index: read for index, (read, _) in pipes.items()})

    try:
        process = await asyncio.create_subprocess_exec(
            *args,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            limit=settings.chunk_size * 4,
            pass_fds=[read for read, _ in pipes.values()],
        )
    except FileNotFoundError as exc:
        for read, write in pipes.values():
            os.close(read)
            os.close(write)
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "ffmpeg isn't installed on the server, so merged formats are "
            "unavailable. Pick a single-stream quality instead.",
            status_code=503,
        ) from exc

    feeders = []
    for index, (read, write) in pipes.items():
        os.close(read)  # ffmpeg holds its own copy now
        feeders.append(asyncio.create_task(_feed_pipe(inputs[index], write)))

    stderr_tail: deque[str] = deque(maxlen=20)

    async def drain_stderr() -> None:
        assert process.stderr is not None
        async for line in process.stderr:
            text = line.decode("utf-8", "replace").rstrip()
            if text:
                stderr_tail.append(text)

    stderr_task = asyncio.create_task(drain_stderr())

    async def terminate() -> None:
        stderr_task.cancel()
        for feeder in feeders:
            feeder.cancel()
        if process.returncode is None:
            process.kill()
        try:
            await process.wait()
        except ProcessLookupError:
            pass

    assert process.stdout is not None
    try:
        first_chunk = await asyncio.wait_for(
            process.stdout.read(settings.chunk_size), _FIRST_CHUNK_TIMEOUT
        )
    except asyncio.TimeoutError as exc:
        await terminate()
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "The media host was too slow to respond. Try a lower quality.",
            status_code=504,
        ) from exc

    if not first_chunk:
        await process.wait()
        await terminate()
        detail = " | ".join(stderr_tail)
        logger.error("ffmpeg produced no output rc=%s stderr=%s",
                     process.returncode, detail)
        raise AppError(
            ErrorCode.UPSTREAM_ERROR,
            "Couldn't build this format. Try a different quality.",
            status_code=502,
        )

    async def iterator() -> AsyncIterator[bytes]:
        try:
            yield first_chunk
            while True:
                chunk = await process.stdout.read(settings.chunk_size)
                if not chunk:
                    break
                yield chunk
            await process.wait()
            if process.returncode not in (0, None):
                logger.warning(
                    "ffmpeg exited rc=%s stderr=%s",
                    process.returncode,
                    " | ".join(stderr_tail),
                )
        finally:
            # Covers normal completion and client disconnect (GeneratorExit /
            # CancelledError), so no orphaned ffmpeg process is left behind.
            await terminate()

    return None, iterator()


async def _feed_pipe(source: MediaSource, write_fd: int) -> None:
    """Stream ``source`` into a pipe ffmpeg reads as one of its inputs.

    Closing the pipe is ffmpeg's end-of-input, so it's closed however this
    ends. A failure here surfaces through ffmpeg: a truncated or missing input
    makes it error out, which open_ffmpeg_stream already reports.
    """
    loop = asyncio.get_running_loop()
    transport, protocol = await loop.connect_write_pipe(
        asyncio.streams.FlowControlMixin, os.fdopen(write_fd, "wb", buffering=0)
    )
    writer = asyncio.StreamWriter(transport, protocol, None, loop)
    chunks = None
    try:
        _, chunks = await open_direct_stream(source)
        async for chunk in chunks:
            writer.write(chunk)
            await writer.drain()
    except (AppError, ConnectionError) as exc:
        # ConnectionError: ffmpeg exited and closed its end. Not ours to report.
        logger.warning("ffmpeg input feed stopped: %s", exc)
    finally:
        if chunks is not None:
            await chunks.aclose()
        writer.close()


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------


async def open_stream(plan: DownloadPlan) -> tuple[int | None, AsyncIterator[bytes]]:
    await _assert_public_async(plan.video, plan.audio)
    if plan.needs_ffmpeg:
        return await open_ffmpeg_stream(plan)
    source = plan.video or plan.audio
    assert source is not None
    return await open_direct_stream(source)
