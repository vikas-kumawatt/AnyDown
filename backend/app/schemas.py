"""Request/response models for the public API (PRD section 8)."""

from __future__ import annotations

from pydantic import BaseModel, Field

from .platforms import MAX_URL_LENGTH


class ResolveRequest(BaseModel):
    url: str = Field(min_length=1, max_length=MAX_URL_LENGTH)


class FormatOption(BaseModel):
    id: str
    label: str
    ext: str
    filesize_approx: int | None = None
    kind: str  # "progressive" | "merge" | "audio"


class ResolveResponse(BaseModel):
    platform: str
    title: str
    thumbnail: str | None = None
    duration: int | None = None
    uploader: str | None = None
    formats: list[FormatOption]


class HealthResponse(BaseModel):
    status: str
    version: str
    ytdlp_version: str
    ffmpeg: str | None
    max_height: int


class ErrorResponse(BaseModel):
    error: str
    message: str
