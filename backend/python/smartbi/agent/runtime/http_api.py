"""Internal HTTP/SSE adapter for the bounded restaurant runtime.

This is intentionally not a chat endpoint. It admits one fixed route, derives
all identity from authenticated request state, and streams only events read
back from the durable RunStore.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import uuid
from dataclasses import dataclass
from typing import AsyncIterator

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import StreamingResponse

from common.middleware.correlation import get_correlation_id
from smartbi.config import get_pg_pool
from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

from .bounded_runtime import BoundedRestaurantRuntime
from .contracts import DataClassification, TrustedExecutionContext
from .gateway import ReadToolGateway
from .http_contracts import StartRestaurantRunRequest, event_v1, run_replay_v1
from .restaurant_read_tools import build_restaurant_read_registry
from .run_store import PostgresRunStore, RunAccessError, RunStore

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/internal/smartbi/agent/runs")

_POLL_SECONDS = 0.2
_START_POLL_SECONDS = 0.01
_START_TIMEOUT_SECONDS = 5.0
_BACKGROUND_RUNS: set[asyncio.Task] = set()
_SAFE_CORRELATION_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


@dataclass(frozen=True)
class RuntimeComponents:
    runtime: BoundedRestaurantRuntime
    store: RunStore


async def get_runtime_components() -> RuntimeComponents:
    try:
        pool = await get_pg_pool()
    except Exception as exc:
        raise HTTPException(
            status_code=503, detail="AGENT_RUN_STORE_UNAVAILABLE"
        ) from exc
    if pool is None:
        raise HTTPException(status_code=503, detail="AGENT_RUN_STORE_UNAVAILABLE")
    store = PostgresRunStore(pool)
    gateway = ReadToolGateway(pool, build_restaurant_read_registry())
    return RuntimeComponents(BoundedRestaurantRuntime(gateway, store), store)


def require_internal_restaurant_context(request: Request) -> TrustedExecutionContext:
    if getattr(request.state, "auth_method", None) != "internal":
        raise HTTPException(status_code=401, detail="INTERNAL_AUTH_REQUIRED")
    if any(
        key in request.query_params
        for key in ("factoryId", "factory_id", "tenantId", "tenant_id")
    ):
        raise HTTPException(status_code=422, detail="TENANT_PARAMETER_FORBIDDEN")

    factory_id = getattr(request.state, "factory_id", None)
    user_id = getattr(request.state, "user_id", None)
    role = getattr(request.state, "role", None)
    business_type = getattr(request.state, "business_type", None)
    if not isinstance(factory_id, str) or not factory_id.strip():
        raise HTTPException(status_code=403, detail="TRUSTED_TENANT_REQUIRED")
    if not isinstance(user_id, str) or not user_id.strip():
        raise HTTPException(status_code=403, detail="TRUSTED_ACTOR_REQUIRED")
    if business_type != "RESTAURANT":
        raise HTTPException(status_code=403, detail="RESTAURANT_BUSINESS_REQUIRED")
    if not isinstance(role, str) or role not in PRICE_VIEW_ROLES:
        raise HTTPException(status_code=403, detail="FINANCIAL_ACCESS_REQUIRED")

    correlation_id = get_correlation_id()
    if correlation_id == "-":
        correlation_id = str(uuid.uuid4())
    elif not _SAFE_CORRELATION_ID.fullmatch(correlation_id):
        raise HTTPException(status_code=422, detail="INVALID_CORRELATION_ID")
    return TrustedExecutionContext(
        factory_id=factory_id,
        business_type="RESTAURANT",
        user_id=user_id,
        correlation_id=correlation_id,
        authorized_classifications=frozenset({DataClassification.FINANCIAL_RESTRICTED}),
    )


@router.post("")
async def start_restaurant_run(
    body: StartRestaurantRunRequest,
    request: Request,
    context: TrustedExecutionContext = Depends(require_internal_restaurant_context),
    components: RuntimeComponents = Depends(get_runtime_components),
):
    try:
        domain_request = body.to_domain()
    except ValueError as exc:
        raise HTTPException(status_code=422, detail="INVALID_RUN_WINDOW") from exc

    run_id = str(uuid.uuid4())
    cancellation_requested = asyncio.Event()
    task = asyncio.create_task(
        components.runtime.execute(
            domain_request,
            context,
            run_id=run_id,
            cancelled=cancellation_requested.is_set,
        ),
        name=f"restaurant-agent-run:{run_id}",
    )
    _track_background_run(task, run_id)

    # Do not return 200/SSE until the run itself exists durably. create_run is
    # the runtime's first store operation; failures remain an HTTP 503 rather
    # than an empty successful stream.
    try:
        await _wait_until_durable(components.store, run_id, context, task)
    except HTTPException:
        cancellation_requested.set()
        raise
    except asyncio.CancelledError:
        cancellation_requested.set()
        raise

    response = StreamingResponse(
        _persisted_event_stream(
            request,
            components.store,
            run_id,
            context,
            task,
            cancellation_requested,
        ),
        media_type="text/event-stream",
        headers={
            "X-Agent-Run-Id": run_id,
            "Cache-Control": "no-cache, no-transform",
            "X-Accel-Buffering": "no",
        },
    )
    return response


@router.get("/{run_id}/events")
async def replay_restaurant_run(
    run_id: uuid.UUID,
    after_sequence: int = Query(
        default=0,
        alias="afterSequence",
        ge=0,
        le=9_223_372_036_854_775_807,
    ),
    context: TrustedExecutionContext = Depends(require_internal_restaurant_context),
    components: RuntimeComponents = Depends(get_runtime_components),
):
    run_key = str(run_id)
    try:
        record = await components.store.load_run(run_key, context)
        events = await components.store.events_for(
            run_key, context, after_sequence=after_sequence
        )
    except RunAccessError as exc:
        # Missing and cross-tenant runs intentionally share one response.
        raise HTTPException(status_code=404, detail="RUN_NOT_FOUND") from exc
    except Exception as exc:
        raise HTTPException(
            status_code=503, detail="AGENT_RUN_STORE_UNAVAILABLE"
        ) from exc
    return run_replay_v1(record, events)


async def _wait_until_durable(
    store: RunStore,
    run_id: str,
    context: TrustedExecutionContext,
    task: asyncio.Task,
) -> None:
    deadline = asyncio.get_running_loop().time() + _START_TIMEOUT_SECONDS
    while True:
        try:
            await store.load_run(run_id, context)
            return
        except RunAccessError:
            if task.done():
                try:
                    task.result()
                except Exception as exc:
                    raise HTTPException(
                        status_code=503, detail="AGENT_RUN_START_FAILED"
                    ) from exc
                raise HTTPException(status_code=503, detail="AGENT_RUN_NOT_PERSISTED")
            if asyncio.get_running_loop().time() >= deadline:
                raise HTTPException(status_code=503, detail="AGENT_RUN_START_TIMEOUT")
            await asyncio.sleep(_START_POLL_SECONDS)
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(
                status_code=503, detail="AGENT_RUN_STORE_UNAVAILABLE"
            ) from exc


async def _persisted_event_stream(
    request: Request,
    store: RunStore,
    run_id: str,
    context: TrustedExecutionContext,
    task: asyncio.Task,
    cancellation_requested: asyncio.Event,
) -> AsyncIterator[str]:
    cursor = 0
    try:
        while True:
            if await request.is_disconnected():
                cancellation_requested.set()
                return

            try:
                events = await store.events_for(run_id, context, after_sequence=cursor)
                record = await store.load_run(run_id, context)
            except RunAccessError:
                # The run was proved durable before this generator started.
                # A later access failure is not replaced with an invented event.
                logger.error(
                    "Durable agent run became inaccessible (run_id=%s)", run_id
                )
                return
            except Exception:
                logger.error(
                    "Agent run event store became unavailable (run_id=%s)", run_id
                )
                return

            for event in events:
                cursor = event.sequence
                yield _sse_event(event)

            if record.state.terminal and cursor >= record.next_event_sequence:
                return
            if task.done() and not record.state.terminal:
                # The done callback records the controlled failure. There is no
                # truthful terminal Event v1 to emit if persistence itself failed.
                logger.error(
                    "Agent run task ended without durable terminal state (run_id=%s)",
                    run_id,
                )
                return
            await asyncio.sleep(_POLL_SECONDS)
    except asyncio.CancelledError:
        cancellation_requested.set()
        raise
    finally:
        # Best effort only. This requests a trusted cancellation outcome after
        # the current bounded read has completed or timed out and cleaned up; a
        # browser disconnect does not force-cancel the runtime task or invent an
        # immediate service-side CANCELLED terminal.
        cancellation_requested.set()


def _sse_event(event) -> str:
    body = json.dumps(
        event_v1(event), ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )
    return f"id: {event.sequence}\nevent: agent.event.v1\ndata: {body}\n\n"


def _track_background_run(task: asyncio.Task, run_id: str) -> None:
    _BACKGROUND_RUNS.add(task)

    def completed(done: asyncio.Task) -> None:
        _BACKGROUND_RUNS.discard(done)
        try:
            done.result()
        except asyncio.CancelledError:
            logger.error("Agent run task was force-cancelled (run_id=%s)", run_id)
        except Exception:
            # Runtime/store errors may contain database details. Log only the
            # controlled condition and run id, not raw exception text.
            logger.error(
                "Agent run task failed before a durable terminal (run_id=%s)",
                run_id,
            )

    task.add_done_callback(completed)
