"""Bounded, deterministic restaurant evidence runtime.

The implementation is deliberately not a general autonomous agent. It runs a
typed route over the approved ReadToolGateway, persists a compact event for
every step, and refuses to manufacture a causal attribution where the current
evidence contracts do not support one.
"""

from __future__ import annotations

import time
import uuid
from dataclasses import replace
from decimal import Decimal, InvalidOperation
from typing import Callable, Iterable, Optional

from .contracts import EvidenceEnvelope, EvidenceStatus, TrustedExecutionContext
from .gateway import ReadToolGateway
from .numeric_truth import NumericTruthGuard
from .routes import gross_margin_decline_plan
from .run_contracts import (
    AgentEventType,
    GrossMarginDeclineRequest,
    NumericClaim,
    OutcomeStatus,
    RouteCode,
    RunState,
    RuntimeBudgets,
    RuntimeCounters,
    RuntimeResult,
    StructuredOutcome,
)
from .run_store import RunStore, RunStoreError


class RuntimeBudgetExceeded(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class RuntimeCancelled(RuntimeError):
    pass


class RuntimeSourceFailure(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class StepFailureEventPersistenceError(RuntimeError):
    pass


class BoundedRestaurantRuntime:
    def __init__(
        self,
        gateway: ReadToolGateway,
        store: RunStore,
        *,
        budgets: RuntimeBudgets | None = None,
        id_factory: Callable[[], str] | None = None,
        monotonic: Callable[[], float] | None = None,
    ) -> None:
        self._gateway = gateway
        self._store = store
        self._budgets = budgets or RuntimeBudgets()
        self._id_factory = id_factory or (lambda: str(uuid.uuid4()))
        self._monotonic = monotonic or time.monotonic

    async def execute(
        self,
        request: GrossMarginDeclineRequest,
        context: TrustedExecutionContext,
        *,
        cancelled: Callable[[], bool] | None = None,
    ) -> RuntimeResult:
        if not isinstance(context, TrustedExecutionContext):
            raise TypeError("trusted execution context is required")
        plan = gross_margin_decline_plan(request)
        run_id = self._id_factory()
        run_context = replace(context, run_id=run_id, step_id=None)
        counters = RuntimeCounters()
        evidence: list[EvidenceEnvelope] = []
        started = self._monotonic()
        is_cancelled = cancelled or (lambda: False)

        # If create_run itself fails, no durable run is claimed and the store
        # error propagates. Every operation after creation is covered by the
        # best-effort terminal failure path below.
        await self._store.create_run(
            run_id, run_context, plan.route_code, request.safe_dict()
        )
        try:
            await self._event(
                run_id,
                run_context,
                AgentEventType.RUN_STARTED,
                {"routeCode": plan.route_code.value},
                counters,
            )
            await self._event(
                run_id,
                run_context,
                AgentEventType.ROUTE_SELECTED,
                {"routeCode": plan.route_code.value},
                counters,
            )
            await self._event(
                run_id,
                run_context,
                AgentEventType.PLAN_CREATED,
                {
                    "routeCode": plan.route_code.value,
                    "stepCount": len(plan.steps),
                    "maxRounds": self._budgets.max_rounds,
                    "maxToolCalls": self._budgets.max_tool_calls,
                },
                counters,
            )
            for step in plan.steps:
                self._check_before_step(
                    step.round_number, counters, started, is_cancelled
                )
                step_context = replace(run_context, step_id=step.step_id)
                await self._event(
                    run_id,
                    step_context,
                    AgentEventType.STEP_STARTED,
                    {"round": step.round_number, "purposeCode": step.purpose_code},
                    counters,
                    step_id=step.step_id,
                    tool_name=step.tool_name,
                )
                try:
                    envelope = await self._gateway.execute(
                        step.tool_name, step.parameters, step_context
                    )
                except Exception as exc:
                    try:
                        await self._event(
                            run_id,
                            step_context,
                            AgentEventType.STEP_FAILED,
                            {"failureCode": "READ_TOOL_EXECUTION_FAILED"},
                            counters,
                            step_id=step.step_id,
                            tool_name=step.tool_name,
                        )
                    except Exception as persist_exc:
                        raise StepFailureEventPersistenceError() from persist_exc
                    raise RuntimeSourceFailure("READ_TOOL_EXECUTION_FAILED") from exc
                evidence.append(envelope)
                counters = counters.after(
                    round_number=step.round_number,
                    facts=len(envelope.facts),
                    evidence_bytes=envelope.limits.bytes_returned,
                )
                await self._event(
                    run_id,
                    step_context,
                    AgentEventType.STEP_COMPLETED,
                    {
                        "round": step.round_number,
                        "evidenceId": envelope.evidence_id,
                        "evidenceStatus": envelope.status.value,
                        "factCount": len(envelope.facts),
                        "evidenceBytes": envelope.limits.bytes_returned,
                        "warningCodes": [warning.code for warning in envelope.warnings],
                    },
                    counters,
                    step_id=step.step_id,
                    tool_name=step.tool_name,
                )
                self._check_after_step(counters, started, is_cancelled)
                if envelope.status is EvidenceStatus.ERROR:
                    raise RuntimeSourceFailure("READ_SOURCE_FAILED")

            outcome = self._build_outcome(evidence)
            NumericTruthGuard.validate_outcome(outcome, evidence)
            terminal_state = (
                RunState.COMPLETED
                if outcome.status is OutcomeStatus.COMPLETE
                else RunState.PARTIAL
            )
            await self._terminal(
                run_id,
                run_context,
                RunState.RUNNING,
                terminal_state,
                outcome,
                counters,
                terminal_event_type=AgentEventType.RUN_COMPLETED,
            )
            return RuntimeResult(
                run_id=run_id,
                state=terminal_state,
                route_plan=plan,
                outcome=outcome,
                evidence=tuple(evidence),
                counters=counters,
            )
        except RuntimeCancelled:
            outcome = StructuredOutcome(
                status=OutcomeStatus.CANCELLED,
                route_code=plan.route_code,
                blockers=("RUN_CANCELLED_BY_TRUSTED_CALLER",),
            )
            await self._terminal(
                run_id,
                run_context,
                RunState.RUNNING,
                RunState.CANCELLED,
                outcome,
                counters,
                failure_code="RUN_CANCELLED",
                terminal_event_type=AgentEventType.RUN_CANCELLED,
            )
            return RuntimeResult(
                run_id,
                RunState.CANCELLED,
                plan,
                outcome,
                tuple(evidence),
                counters,
                "RUN_CANCELLED",
            )
        except RuntimeBudgetExceeded as exc:
            outcome = StructuredOutcome(
                status=OutcomeStatus.BUDGET_EXCEEDED,
                route_code=plan.route_code,
                blockers=(exc.code,),
            )
            await self._terminal(
                run_id,
                run_context,
                RunState.RUNNING,
                RunState.BUDGET_EXCEEDED,
                outcome,
                counters,
                failure_code=exc.code,
                terminal_event_type=AgentEventType.BUDGET_EXCEEDED,
            )
            return RuntimeResult(
                run_id,
                RunState.BUDGET_EXCEEDED,
                plan,
                outcome,
                tuple(evidence),
                counters,
                exc.code,
            )
        except RuntimeSourceFailure as exc:
            outcome = StructuredOutcome(
                status=OutcomeStatus.FAILED,
                route_code=plan.route_code,
                blockers=(exc.code,),
            )
            await self._terminal(
                run_id,
                run_context,
                RunState.RUNNING,
                RunState.FAILED,
                outcome,
                counters,
                failure_code=exc.code,
                terminal_event_type=AgentEventType.RUN_FAILED,
            )
            return RuntimeResult(
                run_id,
                RunState.FAILED,
                plan,
                outcome,
                tuple(evidence),
                counters,
                exc.code,
            )
        except StepFailureEventPersistenceError:
            failure_code = "STEP_FAILURE_EVENT_PERSIST_FAILED"
            outcome = StructuredOutcome(
                status=OutcomeStatus.FAILED,
                route_code=plan.route_code,
                blockers=(failure_code,),
            )
            await self._terminal(
                run_id,
                run_context,
                RunState.RUNNING,
                RunState.FAILED,
                outcome,
                counters,
                failure_code=failure_code,
                terminal_event_type=AgentEventType.RUN_FAILED,
            )
            return RuntimeResult(
                run_id,
                RunState.FAILED,
                plan,
                outcome,
                tuple(evidence),
                counters,
                failure_code,
            )
        except Exception:
            # Never persist raw exception text/type. If persistence is also
            # unavailable this call raises, so callers cannot mistake the run
            # for a durable FAILED terminal.
            failure_code = "UNEXPECTED_RUNTIME_FAILURE"
            outcome = StructuredOutcome(
                status=OutcomeStatus.FAILED,
                route_code=plan.route_code,
                blockers=(failure_code,),
            )
            await self._terminal(
                run_id,
                run_context,
                RunState.RUNNING,
                RunState.FAILED,
                outcome,
                counters,
                failure_code=failure_code,
                terminal_event_type=AgentEventType.RUN_FAILED,
            )
            return RuntimeResult(
                run_id,
                RunState.FAILED,
                plan,
                outcome,
                tuple(evidence),
                counters,
                failure_code,
            )

    def _check_before_step(
        self,
        round_number: int,
        counters: RuntimeCounters,
        started: float,
        cancelled: Callable[[], bool],
    ) -> None:
        if cancelled():
            raise RuntimeCancelled()
        if round_number > self._budgets.max_rounds:
            raise RuntimeBudgetExceeded("ROUND_BUDGET_EXCEEDED")
        if counters.tool_calls_used >= self._budgets.max_tool_calls:
            raise RuntimeBudgetExceeded("TOOL_CALL_BUDGET_EXCEEDED")
        if self._monotonic() - started >= self._budgets.wallclock_seconds:
            raise RuntimeBudgetExceeded("WALLCLOCK_BUDGET_EXCEEDED")

    def _check_after_step(
        self,
        counters: RuntimeCounters,
        started: float,
        cancelled: Callable[[], bool],
    ) -> None:
        if cancelled():
            raise RuntimeCancelled()
        if counters.facts_used > self._budgets.max_facts:
            raise RuntimeBudgetExceeded("FACT_BUDGET_EXCEEDED")
        if counters.evidence_bytes_used > self._budgets.max_evidence_bytes:
            raise RuntimeBudgetExceeded("EVIDENCE_BYTE_BUDGET_EXCEEDED")
        if self._monotonic() - started >= self._budgets.wallclock_seconds:
            raise RuntimeBudgetExceeded("WALLCLOCK_BUDGET_EXCEEDED")

    async def _event(
        self,
        run_id: str,
        context: TrustedExecutionContext,
        event_type: AgentEventType,
        payload: dict,
        counters: RuntimeCounters,
        *,
        step_id: Optional[str] = None,
        tool_name: Optional[str] = None,
    ) -> None:
        await self._store.append_event(
            run_id,
            context,
            event_type,
            payload,
            counters=counters,
            step_id=step_id,
            tool_name=tool_name,
        )

    async def _terminal(
        self,
        run_id: str,
        context: TrustedExecutionContext,
        expected: RunState,
        terminal: RunState,
        outcome: StructuredOutcome,
        counters: RuntimeCounters,
        *,
        terminal_event_type: AgentEventType,
        failure_code: Optional[str] = None,
    ) -> None:
        changed = await self._store.compare_and_set_terminal(
            run_id,
            context,
            expected_state=expected,
            terminal_state=terminal,
            outcome=outcome,
            counters=counters,
            terminal_event_type=terminal_event_type,
            failure_code=failure_code,
        )
        if not changed:
            raise RunStoreError("terminal-state CAS lost")

    @staticmethod
    def _build_outcome(evidence: Iterable[EvidenceEnvelope]) -> StructuredOutcome:
        envelopes = tuple(evidence)
        period = _by_tool(envelopes, "restaurant_period_comparison_read.v1")
        store = _by_tool(envelopes, "restaurant_store_performance_read.v1")
        dish = _by_tool(envelopes, "restaurant_dish_margin_mix_read.v1")

        decline = _first_negative(
            period, ("gross_marginMomChange", "gross_marginYoyChange")
        )
        blockers: list[str] = []
        observations: list[str] = []
        claims: list[NumericClaim] = []

        if decline is None:
            blockers.append("GROSS_MARGIN_DECLINE_NOT_ESTABLISHED")
        else:
            claims.append(_claim(period, decline, "GROSS_MARGIN_DECLINE_OBSERVED"))
            observations.append("GROSS_MARGIN_DECLINE_CONFIRMED_FROM_PERIOD_EVIDENCE")

        current_revenue = _first_fact(period, "revenue", comparison="current")
        if current_revenue is not None:
            claims.append(_claim(period, current_revenue, "PERIOD_REVENUE_OBSERVED"))

        # The current store tool deliberately exposes sales operations, not
        # store profit or margin. Keep this blocker even when store sales exist.
        blockers.append("STORE_MARGIN_UNAVAILABLE")
        claims.extend(_claims_for_metric(store, "revenue", "STORE_REVENUE_OBSERVED", 3))

        dish_margins = _facts(dish, "dishGrossMargin")
        usable_dish_margins = [fact for fact in dish_margins if fact.value is not None]
        if not usable_dish_margins:
            blockers.append("DISH_MARGIN_UNAVAILABLE")
        else:
            claims.extend(
                _claim(dish, fact, "DISH_GROSS_MARGIN_OBSERVED")
                for fact in usable_dish_margins[:3]
            )
            blockers.append("DISH_PERIOD_DECOMPOSITION_UNAVAILABLE")
        claims.extend(_claims_for_metric(dish, "revenue", "DISH_REVENUE_OBSERVED", 3))

        blockers.append("CAUSAL_ATTRIBUTION_UNSUPPORTED_BY_READ_CONTRACTS")
        status = (
            OutcomeStatus.PARTIAL
            if decline is not None
            else OutcomeStatus.NOT_COMPUTABLE
        )
        outcome = StructuredOutcome(
            status=status,
            route_code=RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
            claims=tuple(claims),
            blockers=tuple(dict.fromkeys(blockers)),
            observations=tuple(observations),
            attribution_supported=False,
        )
        return outcome


def _by_tool(
    envelopes: tuple[EvidenceEnvelope, ...], tool_name: str
) -> EvidenceEnvelope:
    for envelope in envelopes:
        if envelope.tool_name == tool_name:
            return envelope
    raise RuntimeSourceFailure("REQUIRED_EVIDENCE_MISSING")


def _facts(envelope: EvidenceEnvelope, metric: str):
    return [fact for fact in envelope.facts if fact.metric == metric]


def _first_fact(envelope: EvidenceEnvelope, metric: str, **dimensions: str):
    for fact in _facts(envelope, metric):
        if all(fact.dimensions.get(key) == value for key, value in dimensions.items()):
            if fact.value is not None:
                return fact
    return None


def _first_negative(envelope: EvidenceEnvelope, metrics: tuple[str, ...]):
    for metric in metrics:
        for fact in _facts(envelope, metric):
            if fact.value is None:
                continue
            try:
                if Decimal(fact.value) < 0:
                    return fact
            except InvalidOperation:
                continue
    return None


def _claim(envelope: EvidenceEnvelope, fact, statement_code: str) -> NumericClaim:
    return NumericClaim(
        statement_code=statement_code,
        metric=fact.metric,
        value=fact.value,
        unit=fact.unit,
        evidence_id=envelope.evidence_id,
        fact_id=fact.fact_id,
        dimensions=dict(fact.dimensions),
    )


def _claims_for_metric(
    envelope: EvidenceEnvelope, metric: str, statement_code: str, limit: int
) -> list[NumericClaim]:
    return [
        _claim(envelope, fact, statement_code)
        for fact in _facts(envelope, metric)
        if fact.value is not None
    ][:limit]
