"""API contract tests. yt-dlp is stubbed — no network is touched."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app import extractor
from app.errors import AppError, ErrorCode
from app.main import app

YT_URL = "https://www.youtube.com/watch?v=abc123"


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


@pytest.fixture
def stub_resolve(monkeypatch, youtube_info: dict):
    calls: list[str] = []

    async def fake_resolve(url: str, *, use_cache: bool = True) -> dict:
        calls.append(url)
        return youtube_info

    monkeypatch.setattr("app.main.resolve", fake_resolve)
    return calls


def test_health_reports_engine_versions(client: TestClient) -> None:
    body = client.get("/api/health").json()
    assert body["status"] == "ok"
    assert body["ytdlp_version"]
    assert body["max_height"] == 1080


def test_resolve_returns_expected_shape(client: TestClient, stub_resolve) -> None:
    response = client.post("/api/resolve", json={"url": YT_URL})
    assert response.status_code == 200

    body = response.json()
    assert body["platform"] == "youtube"
    assert body["title"] == "Example / Video: <test>"
    assert body["duration"] == 212
    assert body["uploader"] == "Example Channel"
    assert body["formats"]

    labels = [f["label"] for f in body["formats"]]
    assert "1080p MP4" in labels
    assert any(label.startswith("Audio only") for label in labels)

    # Internal stream URLs and headers must never leak to the client.
    assert "cdn.example.com" not in response.text
    assert "http_headers" not in response.text


def test_resolve_rejects_private_addresses(client: TestClient) -> None:
    """The gate now guards the network, not the platform list."""
    response = client.post(
        "/api/resolve", json={"url": "http://169.254.169.254/latest/meta-data/"}
    )
    assert response.status_code == 400
    assert response.json()["error"] == ErrorCode.UNSUPPORTED_URL.value


def test_resolve_rejects_missing_url(client: TestClient) -> None:
    response = client.post("/api/resolve", json={})
    assert response.status_code == 422
    assert response.json()["error"] == ErrorCode.INVALID_REQUEST.value


def test_resolve_surfaces_private_content(client: TestClient, monkeypatch) -> None:
    async def fake_resolve(url: str, *, use_cache: bool = True) -> dict:
        raise AppError(
            ErrorCode.PRIVATE_CONTENT, "Not public.", status_code=404
        )

    monkeypatch.setattr("app.main.resolve", fake_resolve)
    response = client.post(
        "/api/resolve", json={"url": "https://www.instagram.com/reel/x/"}
    )
    assert response.status_code == 404
    assert response.json()["error"] == ErrorCode.PRIVATE_CONTENT.value


def test_resolve_reports_no_formats(client: TestClient, monkeypatch) -> None:
    async def fake_resolve(url: str, *, use_cache: bool = True) -> dict:
        return {"title": "empty", "formats": []}

    monkeypatch.setattr("app.main.resolve", fake_resolve)
    response = client.post("/api/resolve", json={"url": YT_URL})
    assert response.status_code == 422
    assert response.json()["error"] == ErrorCode.NO_FORMATS.value


def test_download_rejects_unknown_format_id(
    client: TestClient, stub_resolve
) -> None:
    response = client.get(
        "/api/download", params={"url": YT_URL, "formatId": "m-999+999"}
    )
    assert response.status_code == 409
    assert response.json()["error"] == ErrorCode.INVALID_REQUEST.value
    # It retries without the cache before giving up.
    assert len(stub_resolve) == 2


def test_download_rejects_private_addresses(client: TestClient) -> None:
    response = client.get(
        "/api/download", params={"url": "http://127.0.0.1/v", "formatId": "p-18"}
    )
    assert response.status_code == 400
    assert response.json()["error"] == ErrorCode.UNSUPPORTED_URL.value


def test_download_requires_format_id(client: TestClient) -> None:
    response = client.get("/api/download", params={"url": YT_URL})
    assert response.status_code == 422


def test_download_streams_progressive_format(
    client: TestClient, stub_resolve, monkeypatch
) -> None:
    async def fake_open_stream(plan):
        assert plan.needs_ffmpeg is False

        async def chunks():
            yield b"video-bytes"

        return 11, chunks()

    monkeypatch.setattr("app.main.open_stream", fake_open_stream)

    response = client.get("/api/download", params={"url": YT_URL, "formatId": "p-18"})
    assert response.status_code == 200
    assert response.content == b"video-bytes"
    assert response.headers["content-length"] == "11"
    assert response.headers["content-type"] == "video/mp4"
    assert "attachment;" in response.headers["content-disposition"]
    assert "Example _ Video_ _test.mp4" in response.headers["content-disposition"]
    assert response.headers["cache-control"] == "no-store"
    assert "x-fallback-applied" not in response.headers


def test_oversized_merge_falls_back_to_progressive(
    client: TestClient, stub_resolve, monkeypatch
) -> None:
    from app import main

    monkeypatch.setattr(main.settings, "max_merge_bytes", 20_000_000)

    seen = {}

    async def fake_open_stream(plan):
        seen["plan"] = plan

        async def chunks():
            yield b"x"

        return 1, chunks()

    monkeypatch.setattr("app.main.open_stream", fake_open_stream)

    merge_id = "m-137+140"  # 1080p avc1 + m4a, ~63 MB in the fixture

    response = client.get(
        "/api/download", params={"url": YT_URL, "formatId": merge_id}
    )
    assert response.status_code == 200
    assert seen["plan"].kind == "progressive"
    assert response.headers["x-fallback-applied"] == seen["plan"].label


def test_concurrency_cap_returns_busy(
    client: TestClient, stub_resolve, monkeypatch
) -> None:
    from app import streaming

    monkeypatch.setattr(streaming, "_download_slots", _AlwaysLocked())

    response = client.get("/api/download", params={"url": YT_URL, "formatId": "p-18"})
    assert response.status_code == 503
    assert response.json()["error"] == ErrorCode.BUSY.value


class _AlwaysLocked:
    def locked(self) -> bool:
        return True

    async def acquire(self) -> None:  # pragma: no cover - never reached
        raise AssertionError("should not acquire")

    def release(self) -> None:  # pragma: no cover
        raise AssertionError("should not release")


def test_cors_allows_configured_origin_only(client: TestClient) -> None:
    allowed = client.options(
        "/api/resolve",
        headers={
            "Origin": "http://testserver",
            "Access-Control-Request-Method": "POST",
        },
    )
    assert allowed.headers.get("access-control-allow-origin") == "http://testserver"

    blocked = client.options(
        "/api/resolve",
        headers={
            "Origin": "https://someone-else.example",
            "Access-Control-Request-Method": "POST",
        },
    )
    assert "access-control-allow-origin" not in blocked.headers


def test_openapi_and_docs_are_disabled(client: TestClient) -> None:
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404
