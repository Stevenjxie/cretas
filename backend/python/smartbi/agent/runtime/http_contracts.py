"""HTTP/Event v1 contracts for the bounded restaurant runtime."""

from __future__ import annotations

from typing import Any, Literal, Mapping

from pydantic import BaseModel, ConfigDict, Field

from .run_contracts import AgentEvent, GrossMarginDeclineRequest, RunRecord
from .run_store import CancelRequest, StaleRunReconciliation


class StartRestaurantRunRequest(BaseModel):
    """The only admitted HTTP route; tenant identity is deliberately absent."""

    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["1.0"] = Field(alias="schemaVersion")
    route_code: Literal["GROSS_MARGIN_DECLINE_ATTRIBUTION"] = Field(alias="routeCode")
    start_date: str = Field(alias="startDate", pattern=r"^\d{4}-\d{2}-\d{2}$")
    end_date: str = Field(alias="endDate", pattern=r"^\d{4}-\d{2}-\d{2}$")
    store_top_n: int = Field(default=20, alias="storeTopN", ge=1, le=50)
    dish_top_n: int = Field(default=10, alias="dishTopN", ge=1, le=20)

    def to_domain(self) -> GrossMarginDeclineRequest:
        return GrossMarginDeclineRequest(
            start_date=self.start_date,
            end_date=self.end_date,
            store_top_n=self.store_top_n,
            dish_top_n=self.dish_top_n,
        )


def event_v1(event: AgentEvent) -> dict[str, Any]:
    """Serialize only the already-sanitized, persisted AgentEvent."""

    return {
        "schemaVersion": "1.0",
        "runId": event.run_id,
        "sequence": event.sequence,
        "eventType": event.event_type.value,
        "stepId": event.step_id,
        "toolName": event.tool_name,
        "payload": dict(event.payload),
    }


def run_replay_v1(record: RunRecord, events: tuple[AgentEvent, ...]) -> dict[str, Any]:
    outcome: Mapping[str, Any] | None = record.outcome_summary
    return {
        "schemaVersion": "1.0",
        "runId": record.run_id,
        "state": record.state.value,
        "routeCode": record.route_code.value,
        "nextEventSequence": record.next_event_sequence,
        "events": [event_v1(event) for event in events],
        "terminalOutcome": dict(outcome) if outcome is not None else None,
        "failureCode": record.failure_code,
    }


def stale_reconciliation_v1(
    run_id: str, reconciliation: StaleRunReconciliation
) -> dict[str, Any]:
    """Serialize only the exact stale-run reconciliation result contract."""

    return {
        "schemaVersion": "1.0",
        "runId": run_id,
        "result": reconciliation.result.value,
        "state": reconciliation.record.state.value,
        "failureCode": reconciliation.record.failure_code,
    }


def cancel_request_v1(run_id: str, cancellation: CancelRequest) -> dict[str, Any]:
    return {
        "schemaVersion": "1.0",
        "runId": run_id,
        "result": cancellation.result.value,
        "state": cancellation.record.state.value,
        "nextEventSequence": cancellation.record.next_event_sequence,
    }
