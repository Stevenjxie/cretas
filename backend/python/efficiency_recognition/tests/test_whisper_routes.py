"""Sprint 6 W2-C-2 — whisper_routes smoke tests.

Validates:
- /api/efficiency/whisper-transcribe accepts well-formed request
- Returns success=true with placeholder transcript under stub backend (default)
- Empty audio_url returns success=false with message
- Optional X-Internal-Secret header enforcement when configured
"""
from __future__ import annotations

import os

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from efficiency_recognition.api.whisper_routes import router as whisper_router


@pytest.fixture()
def client() -> TestClient:
    app = FastAPI()
    # Mirror main.py prefix
    app.include_router(whisper_router, prefix="/api/efficiency")
    return TestClient(app)


def test_transcribe_stub_returns_placeholder_transcript(client: TestClient) -> None:
    response = client.post(
        "/api/efficiency/whisper-transcribe",
        json={
            "audio_url": "https://cretas-media.example.com/F006/audio/test.mp3",
            "factory_id": "F006",
            "call_record_id": 42,
            "language": "zh",
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"] is not None
    assert "transcript" in body["data"]
    assert len(body["data"]["transcript"]) > 0
    # Stub returns a recognizable placeholder
    assert "Sprint 6 W2-C-2" in body["data"]["transcript"]
    assert body["data"]["backend"] == "stub"


def test_transcribe_empty_url_returns_failure(client: TestClient) -> None:
    response = client.post(
        "/api/efficiency/whisper-transcribe",
        json={
            "audio_url": "   ",  # blank URL
            "factory_id": "F006",
            "call_record_id": 1,
            "language": "zh",
        },
    )
    # Endpoint returns 200 OK with success=false (degraded response, mirror Java contract)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is False
    assert "audio_url" in body["message"]


def test_transcribe_missing_audio_url_returns_422(client: TestClient) -> None:
    # audio_url is required by pydantic schema
    response = client.post(
        "/api/efficiency/whisper-transcribe",
        json={"factory_id": "F006"},
    )
    assert response.status_code == 422


def test_internal_secret_header_check_when_configured(
    monkeypatch: pytest.MonkeyPatch, client: TestClient
) -> None:
    # Set the secret in module so the check kicks in
    monkeypatch.setattr(
        "efficiency_recognition.api.whisper_routes.INTERNAL_SECRET",
        "expected-secret-xyz",
    )

    # Without header → 401
    response_no_header = client.post(
        "/api/efficiency/whisper-transcribe",
        json={"audio_url": "https://example.com/test.mp3"},
    )
    assert response_no_header.status_code == 401

    # With wrong header → 401
    response_wrong = client.post(
        "/api/efficiency/whisper-transcribe",
        headers={"X-Internal-Secret": "wrong-value"},
        json={"audio_url": "https://example.com/test.mp3"},
    )
    assert response_wrong.status_code == 401

    # With correct header → 200
    response_ok = client.post(
        "/api/efficiency/whisper-transcribe",
        headers={"X-Internal-Secret": "expected-secret-xyz"},
        json={"audio_url": "https://example.com/test.mp3"},
    )
    assert response_ok.status_code == 200


def test_transcribe_response_includes_call_record_id_echo(client: TestClient) -> None:
    response = client.post(
        "/api/efficiency/whisper-transcribe",
        json={
            "audio_url": "https://example.com/audio.wav",
            "call_record_id": 1234,
        },
    )
    body = response.json()
    assert body["success"] is True
    assert body["data"]["call_record_id"] == 1234
