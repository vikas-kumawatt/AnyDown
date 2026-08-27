"""Application settings.

Everything is overridable via environment variables prefixed with ``APP_``
(e.g. ``APP_CORS_ORIGINS``). Defaults are tuned for Render's free tier.

List-shaped settings are declared as ``str`` and exposed through properties.
pydantic-settings JSON-decodes complex field types straight out of the
environment *before* any validator runs, which makes the plain
``a,b,c`` form that env vars actually get written in fail hard.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


def _csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="APP_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- Network / access ---------------------------------------------------
    # Comma-separated exact origins allowed to call the API. Never "*".
    cors_origins: str = "http://localhost:5173,http://127.0.0.1:5173"
    # Comma-separated slowapi limits, applied per client IP.
    rate_limits: str = "10/minute,100/day"
    # Optional shared secret. When set, requests must send X-App-Key.
    app_key: str = ""

    # --- Extraction ---------------------------------------------------------
    max_height: int = 1080
    # Seconds yt-dlp may spend on a single socket operation.
    extract_timeout: int = 25
    resolve_cache_ttl: int = 300
    resolve_cache_size: int = 32

    # --- Streaming ----------------------------------------------------------
    ffmpeg_path: str = "ffmpeg"
    chunk_size: int = 256 * 1024
    upstream_timeout: int = 30
    max_concurrent_downloads: int = 2
    # Merged (video+audio) requests whose estimated size exceeds this fall back
    # to the best progressive format. See README for why this is a
    # bandwidth/timeout guard rather than a memory guard.
    max_merge_bytes: int = 200 * 1024 * 1024
    # Block media URLs that resolve to private/loopback addresses.
    block_private_media_hosts: bool = True

    # --- Ops ----------------------------------------------------------------
    log_level: str = "INFO"

    @property
    def cors_origin_list(self) -> list[str]:
        return _csv(self.cors_origins)

    @property
    def rate_limit_list(self) -> list[str]:
        return _csv(self.rate_limits)

    @property
    def shared_secret(self) -> str | None:
        return self.app_key.strip() or None


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
