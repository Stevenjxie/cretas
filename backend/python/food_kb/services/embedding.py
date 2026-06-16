"""Local embedding client for Food KB and SmartBI RAG.

This module keeps the historical public API used by Food KB, SmartBI template
RAG, semantic field mapping, and fallback-log similarity:

    configure(...)
    get_embedding(text)
    get_embeddings_batch(texts)
    close()

The implementation now calls the project's Java embedding-service over gRPC
instead of paid third-party embedding APIs. Default endpoint:
AI_EMBEDDING_GRPC_ENDPOINT or FOOD_KB_EMBEDDING_GRPC_ENDPOINT, falling back to
localhost:9090.
"""
from __future__ import annotations

import asyncio
import logging
import os
from typing import List, Optional

import grpc

logger = logging.getLogger(__name__)

DEFAULT_ENDPOINT = "localhost:9090"
DEFAULT_MODEL = "gte-base-zh"
DEFAULT_DIMS = 768

_grpc_endpoint: str = os.environ.get(
    "FOOD_KB_EMBEDDING_GRPC_ENDPOINT",
    os.environ.get("AI_EMBEDDING_GRPC_ENDPOINT", DEFAULT_ENDPOINT),
)
_model: str = DEFAULT_MODEL
_dims: int = DEFAULT_DIMS
_retry_attempts: int = int(os.environ.get(
    "FOOD_KB_EMBEDDING_RETRY_ATTEMPTS",
    os.environ.get("AI_EMBEDDING_RETRY_ATTEMPTS", "3"),
))
_retry_delay_s: float = float(os.environ.get(
    "FOOD_KB_EMBEDDING_RETRY_DELAY_S",
    os.environ.get("AI_EMBEDDING_RETRY_DELAY_S", "1.0"),
))

_channel: Optional[grpc.aio.Channel] = None
_stub: Optional[object] = None


def configure(
    api_key: str = "",
    base_url: str = "",
    model: str = DEFAULT_MODEL,
    dims: int = DEFAULT_DIMS,
    grpc_endpoint: Optional[str] = None,
) -> None:
    """Configure the embedding client.

    api_key/base_url are accepted for backward compatibility with existing
    startup code, but they are intentionally ignored. Embeddings are produced
    by the local gRPC service.
    """
    del api_key, base_url
    global _grpc_endpoint, _model, _dims, _channel, _stub
    _grpc_endpoint = (
        grpc_endpoint
        or os.environ.get("FOOD_KB_EMBEDDING_GRPC_ENDPOINT")
        or os.environ.get("AI_EMBEDDING_GRPC_ENDPOINT")
        or _grpc_endpoint
        or DEFAULT_ENDPOINT
    )
    _model = model or DEFAULT_MODEL
    _dims = int(dims or DEFAULT_DIMS)
    if _channel is not None:
        # Endpoint or config may have changed; reconnect lazily on next call.
        _channel = None
        _stub = None
    logger.info(
        "Embedding client configured for local gRPC: endpoint=%s, model=%s, dims=%s",
        _grpc_endpoint, _model, _dims,
    )


async def _get_stub():
    global _channel, _stub
    if _stub is None:
        _channel = grpc.aio.insecure_channel(_grpc_endpoint)
        from grpc_stubs.embedding import embedding_pb2_grpc
        _stub = embedding_pb2_grpc.EmbeddingServiceStub(_channel)
    return _stub


async def get_embedding(text: str) -> Optional[List[float]]:
    """Generate one embedding via local gRPC embedding-service."""
    if text is None or not str(text).strip():
        return None

    last_error: Optional[Exception] = None
    for attempt in range(1, _retry_attempts + 1):
        try:
            stub = await _get_stub()
            from grpc_stubs.embedding import embedding_pb2
            response = await stub.Encode(embedding_pb2.EncodeRequest(text=text))
            if not response.success:
                last_error = RuntimeError(response.error_message or "unknown")
                logger.warning(
                    "Embedding gRPC Encode attempt %d/%d returned success=false: %s",
                    attempt, _retry_attempts, response.error_message or "unknown",
                )
            else:
                return list(response.embedding)
        except grpc.RpcError as e:
            last_error = e
            logger.warning(
                "Embedding gRPC Encode attempt %d/%d failed: %s",
                attempt, _retry_attempts, e,
            )
        except Exception as e:
            last_error = e
            logger.warning(
                "Embedding gRPC Encode attempt %d/%d errored: %s",
                attempt, _retry_attempts, e,
            )

        if attempt < _retry_attempts:
            await asyncio.sleep(_retry_delay_s)

    logger.error("Embedding gRPC Encode failed after %d attempts: %s", _retry_attempts, last_error)
    return None


async def get_embeddings_batch(texts: List[str], batch_size: int = 20) -> List[Optional[List[float]]]:
    """Generate embeddings for multiple texts via local gRPC EncodeBatch."""
    if not texts:
        return []

    results: List[Optional[List[float]]] = [None] * len(texts)

    for start in range(0, len(texts), max(1, batch_size)):
        batch = texts[start:start + max(1, batch_size)]
        clean_batch = [text or "" for text in batch]
        last_error: Optional[Exception] = None

        for attempt in range(1, _retry_attempts + 1):
            try:
                stub = await _get_stub()
                from grpc_stubs.embedding import embedding_pb2
                request = embedding_pb2.EncodeBatchRequest(texts=clean_batch)
                response = await stub.EncodeBatch(request)
                if not response.success:
                    last_error = RuntimeError(response.error_message or "unknown")
                    logger.warning(
                        "Embedding gRPC EncodeBatch attempt %d/%d returned success=false: %s",
                        attempt, _retry_attempts, response.error_message or "unknown",
                    )
                else:
                    vectors = list(response.embeddings)
                    if len(vectors) != len(batch):
                        logger.warning(
                            "Embedding gRPC EncodeBatch returned %d vectors for %d texts",
                            len(vectors), len(batch),
                        )
                    for offset, vector in enumerate(vectors[:len(batch)]):
                        results[start + offset] = list(vector.values)
                    break
            except grpc.RpcError as e:
                last_error = e
                logger.warning(
                    "Embedding gRPC EncodeBatch attempt %d/%d failed: %s",
                    attempt, _retry_attempts, e,
                )
            except Exception as e:
                last_error = e
                logger.warning(
                    "Embedding gRPC EncodeBatch attempt %d/%d errored: %s",
                    attempt, _retry_attempts, e,
                )

            if attempt < _retry_attempts:
                await asyncio.sleep(_retry_delay_s)
        else:
            logger.error(
                "Embedding gRPC EncodeBatch failed after %d attempts at batch %d: %s",
                _retry_attempts, start, last_error,
            )

    return results


async def close() -> None:
    """Close the gRPC channel."""
    global _channel, _stub
    if _channel is not None:
        await _channel.close()
    _channel = None
    _stub = None
