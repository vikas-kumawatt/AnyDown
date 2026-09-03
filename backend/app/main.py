"""FastAPI application: routes, CORS, rate limiting, error shape.

Deliberately no ``from __future__ import annotations`` here. slowapi wraps each
route function, and FastAPI resolves string annotations against the *wrapper's*
module globals — where the app's own models aren't defined. The result is a
pydantic body model being silently misread as a query parameter.
"""

import logging
import shutil
import subprocess
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import yt_dlp
from fastapi import FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse
from slowapi import Limiter
from slowapi.errors import RateLimitExceeded

from . import __version__
from .config import get_settings
from .errors import AppError, ErrorCode
from . import resolvers
from .extractor import (
    DownloadPlan,
    build_plans,
    build_plans_from_direct,
    pick_fallback_plan,
    resolve,
)
from .platforms import MAX_URL_LENGTH, validate_source_url
from .url_normalizer import normalize
from .schemas import (
    ErrorResponse,
    FormatOption,
    HealthResponse,
    ResolveRequest,
    ResolveResponse,
)
from .streaming import (
    DownloadSlot,
    build_filename,
    content_disposition,
    open_stream,
)

settings = get_settings()

logging.basicConfig(
    level=settings.log_level.upper(),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("app")

# slowapi needs a limit string at decoration time. An empty APP_RATE_LIMITS
# means "effectively unlimited" (useful for local dev) rather than no decorator.
_RATE_LIMIT = ";".join(settings.rate_limit_list) or "10000/minute"

MEDIA_TYPES = {
    "mp4": "video/mp4",
    "mkv": "video/x-matroska",
    "webm": "video/webm",
    "m4a": "audio/mp4",
    "mp3": "audio/mpeg",
    "opus": "audio/ogg",
    "jpg": "image/jpeg",
    "png": "image/png",
}


def client_key(request: Request) -> str:
    """Rate-limit key. Render terminates TLS at a proxy, so trust X-Forwarded-For."""
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


# headers_enabled stays off: slowapi can only inject X-RateLimit-* when the
# endpoint takes a `response: Response` parameter, which would force every route
# to return a raw Response instead of a model. Not worth it for headers nothing
# in this app reads.
limiter = Limiter(key_func=client_key, headers_enabled=False)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    logger.info(
        "starting v%s yt-dlp=%s ffmpeg=%s max_height=%s",
        __version__,
        yt_dlp.version.__version__,
        ffmpeg_version() or "missing",
        settings.max_height,
    )
    yield


app = FastAPI(
    title="All-in-One Video Downloader",
    version=__version__,
    description="Personal-use media resolver. Public content only.",
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)

app.state.limiter = limiter

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Content-Type", "X-App-Key"],
    expose_headers=["Content-Disposition", "X-Fallback-Applied"],
    max_age=600,
)


# --------------------------------------------------------------------------
# Optional shared secret (PRD section 9 upgrade path; off unless APP_APP_KEY set)
# --------------------------------------------------------------------------


@app.middleware("http")
async def shared_secret_guard(request: Request, call_next):
    expected = settings.shared_secret
    if expected and request.url.path.startswith("/api/"):
        if request.url.path != "/api/health" and request.method != "OPTIONS":
            provided = request.headers.get("x-app-key") or request.query_params.get("k")
            if provided != expected:
                return error_response(
                    ErrorCode.UNAUTHORIZED, "Missing or invalid app key.", 401
                )
    return await call_next(request)


# --------------------------------------------------------------------------
# Error handling — one JSON shape everywhere
# --------------------------------------------------------------------------


def error_response(code: ErrorCode, message: str, status_code: int) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content=ErrorResponse(error=str(code), message=message).model_dump(),
    )


@app.exception_handler(AppError)
async def handle_app_error(request: Request, exc: AppError) -> JSONResponse:
    return error_response(exc.code, exc.message, exc.status_code)


@app.exception_handler(RateLimitExceeded)
async def handle_rate_limit(request: Request, exc: RateLimitExceeded) -> JSONResponse:
    return error_response(
        ErrorCode.RATE_LIMITED,
        "Too many requests. Wait a minute and try again.",
        429,
    )


@app.exception_handler(RequestValidationError)
async def handle_validation(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    return error_response(
        ErrorCode.INVALID_REQUEST, "The request body was malformed.", 422
    )


@app.exception_handler(Exception)
async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("unhandled error path=%s", request.url.path)
    return error_response(
        ErrorCode.EXTRACTION_FAILED, "Something went wrong on the server.", 500
    )


# --------------------------------------------------------------------------
# Routes
# --------------------------------------------------------------------------


async def _plans_for(url: str, *, use_cache: bool = True):
    """Resolve a URL to plans, trying custom resolvers before yt-dlp.

    Returns ``(metadata, plans)``. Sites yt-dlp has no extractor for — Threads,
    TeraBox — are handled by hand first; a resolver returning nothing falls
    through, so this can only ever help.
    """
    normalized = normalize(url.strip())

    if resolvers.handles(normalized):
        direct = await resolvers.resolve(normalized)
        if direct is not None:
            return (
                {
                    "title": direct.title,
                    "thumbnail": direct.thumbnail,
                    "duration": direct.duration,
                    "uploader": direct.uploader,
                },
                build_plans_from_direct(direct),
            )
        logger.info("custom resolver found nothing; falling back to yt-dlp")

    info = await resolve(normalized, use_cache=use_cache)
    return info, build_plans(info)


@app.post("/api/resolve", response_model=ResolveResponse)
@limiter.limit(_RATE_LIMIT)
async def api_resolve(request: Request, payload: ResolveRequest) -> ResolveResponse:
    platform = validate_source_url(payload.url)
    info, plans = await _plans_for(payload.url)

    if not plans:
        raise AppError(
            ErrorCode.NO_FORMATS,
            "No downloadable media was found at that link.",
            status_code=422,
        )

    duration = info.get("duration")
    return ResolveResponse(
        # None when we don't recognise the site — it still works, just unlabelled.
        platform=platform.id if platform else "other",
        title=str(info.get("title") or "Untitled"),
        thumbnail=info.get("thumbnail"),
        duration=int(duration) if isinstance(duration, (int, float)) else None,
        uploader=info.get("uploader") or info.get("channel"),
        formats=[
            FormatOption(
                id=plan.id,
                label=plan.label,
                ext=plan.ext,
                filesize_approx=plan.filesize,
                kind=plan.kind,
            )
            for plan in plans.values()
        ],
    )


@app.get("/api/download")
@limiter.limit(_RATE_LIMIT)
async def api_download(
    request: Request,
    url: str = Query(min_length=1, max_length=MAX_URL_LENGTH),
    formatId: str = Query(min_length=1, max_length=128),  # noqa: N803 - public API
) -> StreamingResponse:
    """Stream the selected format straight through to the client.

    This is a GET (the PRD sketched a POST) because only a plain GET navigation
    hands the file to the browser's own download manager. A POST would force the
    SPA to buffer the whole response into a Blob first — exactly the double-hop
    the PRD rules out in section 8.
    """
    validate_source_url(url)
    clean_url = url.strip()

    info, plans = await _plans_for(clean_url)
    plan = plans.get(formatId)

    if plan is None:
        # The cached metadata may predate a platform-side format change.
        info, plans = await _plans_for(clean_url, use_cache=False)
        plan = plans.get(formatId)

    if plan is None:
        raise AppError(
            ErrorCode.INVALID_REQUEST,
            "That format is no longer available. Fetch the link again.",
            status_code=409,
        )

    plan, fell_back = _apply_size_guard(plan, plans)

    slot = DownloadSlot()
    await slot.acquire()
    try:
        content_length, chunks = await open_stream(plan)
    except BaseException:
        slot.release()
        raise

    filename = build_filename(info.get("title"), plan.ext)
    headers = {
        "Content-Disposition": content_disposition(filename),
        "Cache-Control": "no-store",
        "X-Accel-Buffering": "no",  # tell any intermediate proxy not to buffer
    }
    if content_length is not None:
        headers["Content-Length"] = str(content_length)
    if fell_back:
        headers["X-Fallback-Applied"] = plan.label

    logger.info(
        "download plan=%s kind=%s ffmpeg=%s size=%s",
        plan.id, plan.kind, plan.needs_ffmpeg, plan.filesize,
    )

    async def guarded() -> AsyncIterator[bytes]:
        try:
            async for chunk in chunks:
                yield chunk
        finally:
            slot.release()

    return StreamingResponse(
        guarded(),
        media_type=MEDIA_TYPES.get(plan.ext, "application/octet-stream"),
        headers=headers,
    )


@app.get("/api/health", response_model=HealthResponse)
async def api_health() -> HealthResponse:
    return HealthResponse(
        status="ok",
        version=__version__,
        ytdlp_version=yt_dlp.version.__version__,
        ffmpeg=ffmpeg_version(),
        max_height=settings.max_height,
    )


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------


def _apply_size_guard(
    plan: DownloadPlan, plans: dict[str, DownloadPlan]
) -> tuple[DownloadPlan, bool]:
    """Swap an oversized merge for the best single-stream format.

    Guards free-tier bandwidth and request-timeout limits, not memory: the
    pipeline is a true stream, so RAM use is bounded by the chunk size.
    """
    if plan.kind != "merge" or not plan.filesize:
        return plan, False
    if plan.filesize <= settings.max_merge_bytes:
        return plan, False

    fallback = pick_fallback_plan(plans, settings.max_merge_bytes)
    if fallback is None or fallback.id == plan.id:
        return plan, False

    logger.info(
        "size guard: %s (%s bytes) -> %s", plan.id, plan.filesize, fallback.id
    )
    return fallback, True


_ffmpeg_version: str | None = None
_ffmpeg_checked = False


def ffmpeg_version() -> str | None:
    """First line of `ffmpeg -version`, cached. None when ffmpeg is absent."""
    global _ffmpeg_version, _ffmpeg_checked
    if _ffmpeg_checked:
        return _ffmpeg_version
    _ffmpeg_checked = True

    binary = shutil.which(settings.ffmpeg_path)
    if binary is None:
        return None
    try:
        result = subprocess.run(  # noqa: S603 - fixed argv, no shell
            [binary, "-version"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    _ffmpeg_version = result.stdout.splitlines()[0].strip() if result.stdout else None
    return _ffmpeg_version
