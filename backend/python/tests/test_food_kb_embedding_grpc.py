"""food_kb embedding client uses local gRPC embedding-service."""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest


def _encode_response(values: list[float], success: bool = True, error: str = ""):
    response = MagicMock()
    response.embedding = values
    response.success = success
    response.error_message = error
    return response


def _batch_response(vectors: list[list[float]], success: bool = True, error: str = ""):
    response = MagicMock()
    response.success = success
    response.error_message = error
    response.embeddings = [
        MagicMock(values=values)
        for values in vectors
    ]
    return response


@pytest.mark.asyncio
async def test_get_embedding_uses_grpc_stub_not_http_embeddings():
    from food_kb.services import embedding

    await embedding.close()
    embedding.configure(api_key="ignored", base_url="https://example.invalid")

    with patch("food_kb.services.embedding._get_stub") as get_stub, \
            patch("httpx.AsyncClient") as http_client:
        stub = MagicMock()
        stub.Encode = AsyncMock(return_value=_encode_response([0.1, 0.2]))
        get_stub.return_value = stub

        vec = await embedding.get_embedding("食品安全")

    assert vec == [0.1, 0.2]
    stub.Encode.assert_awaited_once()
    http_client.assert_not_called()


@pytest.mark.asyncio
async def test_get_embeddings_batch_uses_grpc_encode_batch():
    from food_kb.services import embedding

    await embedding.close()
    embedding.configure(api_key="", base_url="")

    with patch("food_kb.services.embedding._get_stub") as get_stub:
        stub = MagicMock()
        stub.EncodeBatch = AsyncMock(return_value=_batch_response([[0.1], [0.2]]))
        get_stub.return_value = stub

        vectors = await embedding.get_embeddings_batch(["a", "b"], batch_size=10)

    assert vectors == [[0.1], [0.2]]
    stub.EncodeBatch.assert_awaited_once()
