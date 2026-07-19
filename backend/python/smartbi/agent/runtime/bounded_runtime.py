"""Bounded, deterministic restaurant evidence runtime.

The implementation is deliberately not a general autonomous agent. It runs a
typed route over the approved ReadToolGateway, persists a compact event for
every step, and refuses to manufacture a causal attribution where the current
evidence contracts do not support one.
"""

from __future__ import annotations

import json
import time
import uuid
from dataclasses import replace
from decimal import Decimal, InvalidOperation
from typing import Callable, Iterable, Optional

from .contracts import EvidenceEnvelope, EvidenceStatus, TrustedExecutionContext
from .gateway import ReadToolGateway, ReadToolTimeout
from .numeric_truth import NumericTruthGuard
from .routes import gross_margin_decline_plan, gross_margin_decline_replan
from .run_contracts import (
    ActionProposal,
    AgentEventType,
    EvidenceReference,
    GrossMarginDeclineRequest,
    NumericClaim,
    OutcomeStatus,
    RouteCode,
    RoutePlan,
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
        run_id: str | None = None,
        cancelled: Callable[[], bool] | None = None,
    ) -> RuntimeResult:
        if not isinstance(context, TrustedExecutionContext):
            raise TypeError("trusted execution context is required")
        plan = gross_margin_decline_plan(request)
        run_id = self._validated_run_id(run_id or self._id_factory())
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
            steps = list(plan.steps)
            step_index = 0
            replanned = False
            clarification_emitted = False
            while step_index < len(steps):
                step = steps[step_index]
                step_index += 1
                self._check_before_step(
                    step.round_number, counters, started, is_cancelled
                )
                if await self._cancel_requested(run_id, run_context, is_cancelled):
                    raise RuntimeCancelled()
                step_context = replace(run_context, step_id=step.step_id)
                counters = counters.start_tool(round_number=step.round_number)
                await self._event(
                    run_id,
                    step_context,
                    AgentEventType.STEP_STARTED,
                    {"round": step.round_number, "purposeCode": step.purpose_code},
                    counters,
                    step_id=step.step_id,
                    tool_name=step.tool_name,
                )
                if await self._cancel_requested(run_id, run_context, is_cancelled):
                    raise RuntimeCancelled()
                remaining_wallclock = self._remaining_wallclock(started)
                effective_timeout = min(
                    remaining_wallclock, self._budgets.per_tool_timeout_seconds
                )
                if (
                    effective_timeout - self._budgets.timeout_cleanup_grace_seconds
                    < 0.001
                ):
                    failure_code = "WALLCLOCK_BUDGET_EXCEEDED"
                    try:
                        await self._event(
                            run_id,
                            step_context,
                            AgentEventType.STEP_FAILED,
                            {"failureCode": failure_code},
                            counters,
                            step_id=step.step_id,
                            tool_name=step.tool_name,
                        )
                    except Exception as persist_exc:
                        raise StepFailureEventPersistenceError() from persist_exc
                    raise RuntimeBudgetExceeded(failure_code)
                wallclock_limits_call = (
                    remaining_wallclock <= self._budgets.per_tool_timeout_seconds
                )
                try:
                    envelope = await self._gateway.execute(
                        step.tool_name,
                        step.parameters,
                        step_context,
                        timeout_seconds=effective_timeout,
                        cleanup_grace_seconds=self._budgets.timeout_cleanup_grace_seconds,
                    )
                except ReadToolTimeout:
                    if await self._cancel_requested(run_id, run_context, is_cancelled):
                        raise RuntimeCancelled()
                    failure_code = (
                        "WALLCLOCK_BUDGET_EXCEEDED"
                        if wallclock_limits_call
                        else "READ_TOOL_TIMEOUT"
                    )
                    try:
                        await self._event(
                            run_id,
                            step_context,
                            AgentEventType.STEP_FAILED,
                            {"failureCode": failure_code},
                            counters,
                            step_id=step.step_id,
                            tool_name=step.tool_name,
                        )
                    except Exception as persist_exc:
                        raise StepFailureEventPersistenceError() from persist_exc
                    raise RuntimeBudgetExceeded(failure_code)
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
                counters = counters.add_evidence(
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
                if await self._cancel_requested(run_id, run_context, is_cancelled):
                    raise RuntimeCancelled()
                if envelope.status is EvidenceStatus.ERROR:
                    raise RuntimeSourceFailure("READ_SOURCE_FAILED")

                if (
                    not replanned
                    and step_index == len(steps)
                    and step.round_number == 1
                ):
                    gap_codes = _round_one_gap_codes(tuple(evidence))
                    if gap_codes:
                        resolvable = _can_replan(tuple(evidence))
                        await self._event(
                            run_id,
                            run_context,
                            AgentEventType.EVIDENCE_GAP,
                            {
                                "round": 1,
                                "gapCodes": list(gap_codes),
                                "resolvable": resolvable,
                            },
                            counters,
                        )
                        if resolvable:
                            added = gross_margin_decline_replan(request)
                            await self._event(
                                run_id,
                                run_context,
                                AgentEventType.REPLAN,
                                {
                                    "fromRound": 1,
                                    "toRound": 2,
                                    "gapCodes": list(gap_codes),
                                    "stepIds": [item.step_id for item in added.steps],
                                },
                                counters,
                            )
                            steps.extend(added.steps)
                            plan = RoutePlan(plan.route_code, tuple(steps))
                            replanned = True
                        else:
                            await self._emit_clarification(
                                run_id,
                                run_context,
                                counters,
                                "CONFIRM_DECLINE_WINDOW_AND_DATA_COVERAGE",
                                gap_codes,
                            )
                            clarification_emitted = True

            if replanned:
                final_gaps = _final_gap_codes(tuple(evidence))
                if final_gaps:
                    await self._event(
                        run_id,
                        run_context,
                        AgentEventType.EVIDENCE_GAP,
                        {
                            "round": 2,
                            "gapCodes": list(final_gaps),
                            "resolvable": False,
                        },
                        counters,
                    )
                    if not clarification_emitted:
                        await self._emit_clarification(
                            run_id,
                            run_context,
                            counters,
                            "PROVIDE_DISH_AND_STORE_COST_EVIDENCE",
                            final_gaps,
                        )
                        clarification_emitted = True

            outcome = self._build_outcome(evidence)
            NumericTruthGuard.validate_outcome(outcome, evidence)
            outcome = await self._persist_evidence_drilldowns(
                run_id, run_context, counters, tuple(evidence), outcome
            )
            NumericTruthGuard.validate_outcome(outcome, evidence)
            if await self._cancel_requested(run_id, run_context, is_cancelled):
                raise RuntimeCancelled()
            terminal_state = (
                RunState.COMPLETED
                if outcome.status is OutcomeStatus.COMPLETE
                else RunState.PARTIAL
            )
            if not await self._terminal(
                run_id,
                run_context,
                RunState.RUNNING,
                terminal_state,
                outcome,
                counters,
                terminal_event_type=AgentEventType.RUN_COMPLETED,
            ):
                return await self._finish_cancelled(
                    run_id, run_context, plan, evidence, counters
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
            return await self._finish_cancelled(
                run_id, run_context, plan, evidence, counters
            )
        except RuntimeBudgetExceeded as exc:
            outcome = StructuredOutcome(
                status=OutcomeStatus.BUDGET_EXCEEDED,
                route_code=plan.route_code,
                blockers=(exc.code,),
            )
            if not await self._terminal(
                run_id,
                run_context,
                RunState.RUNNING,
                RunState.BUDGET_EXCEEDED,
                outcome,
                counters,
                failure_code=exc.code,
                terminal_event_type=AgentEventType.BUDGET_EXCEEDED,
            ):
                return await self._finish_cancelled(
                    run_id, run_context, plan, evidence, counters
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
            if not await self._terminal(
                run_id,
                run_context,
                RunState.RUNNING,
                RunState.FAILED,
                outcome,
                counters,
                failure_code=exc.code,
                terminal_event_type=AgentEventType.RUN_FAILED,
            ):
                return await self._finish_cancelled(
                    run_id, run_context, plan, evidence, counters
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
            if not await self._terminal(
                run_id,
                run_context,
                RunState.RUNNING,
                RunState.FAILED,
                outcome,
                counters,
                failure_code=failure_code,
                terminal_event_type=AgentEventType.RUN_FAILED,
            ):
                return await self._finish_cancelled(
                    run_id, run_context, plan, evidence, counters
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
            if not await self._terminal(
                run_id,
                run_context,
                RunState.RUNNING,
                RunState.FAILED,
                outcome,
                counters,
                failure_code=failure_code,
                terminal_event_type=AgentEventType.RUN_FAILED,
            ):
                return await self._finish_cancelled(
                    run_id, run_context, plan, evidence, counters
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

    @staticmethod
    def _validated_run_id(value: str) -> str:
        """Return a canonical UUID without allowing caller-defined identifiers."""

        if not isinstance(value, str):
            raise TypeError("run_id must be a UUID string")
        try:
            parsed = uuid.UUID(value)
        except (ValueError, AttributeError) as exc:
            raise ValueError("run_id must be a valid UUID") from exc
        return str(parsed)

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

    def _remaining_wallclock(self, started: float) -> float:
        return self._budgets.wallclock_seconds - (self._monotonic() - started)

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

    async def _cancel_requested(
        self,
        run_id: str,
        context: TrustedExecutionContext,
        local_cancelled: Callable[[], bool],
    ) -> bool:
        if local_cancelled():
            return True
        return await self._store.is_cancellation_requested(run_id, context)

    async def _emit_clarification(
        self,
        run_id: str,
        context: TrustedExecutionContext,
        counters: RuntimeCounters,
        clarification_code: str,
        gap_codes: tuple[str, ...],
    ) -> None:
        await self._event(
            run_id,
            context,
            AgentEventType.CLARIFICATION,
            {
                "clarificationCode": clarification_code,
                "gapCodes": list(gap_codes),
            },
            counters,
        )

    async def _persist_evidence_drilldowns(
        self,
        run_id: str,
        context: TrustedExecutionContext,
        counters: RuntimeCounters,
        evidence: tuple[EvidenceEnvelope, ...],
        outcome: StructuredOutcome,
    ) -> StructuredOutcome:
        claimed = {
            (claim.evidence_id, claim.fact_id) for claim in outcome.claims
        }
        persisted: set[tuple[str, str]] = set()
        for envelope in evidence:
            facts = [
                fact
                for fact in envelope.facts
                if (envelope.evidence_id, fact.fact_id) in claimed
                and fact.value is not None
            ]
            if not facts:
                continue
            provenance_ids = {
                reference_id
                for fact in facts
                for reference_id in fact.provenance_refs
            }
            provenance = [
                reference
                for reference in envelope.provenance
                if reference.ref_id in provenance_ids
            ]
            warning_codes = [warning.code for warning in envelope.warnings]
            bounded_facts, bounded_provenance, truncated = _bounded_drilldown(
                facts,
                provenance,
                evidence_id=envelope.evidence_id,
                evidence_status=envelope.status.value,
                warning_codes=warning_codes,
            )
            if truncated:
                warning_codes.append("DRILLDOWN_REFERENCE_TRUNCATED")
            persisted.update(
                (envelope.evidence_id, str(fact["factId"]))
                for fact in bounded_facts
            )
            await self._event(
                run_id,
                context,
                AgentEventType.EVIDENCE_RECORDED,
                {
                    "evidenceId": envelope.evidence_id,
                    "evidenceStatus": envelope.status.value,
                    "factReferences": bounded_facts,
                    "provenance": bounded_provenance,
                    "warningCodes": list(dict.fromkeys(warning_codes))[:100],
                    "drilldownTruncated": truncated,
                },
                counters,
                step_id=envelope.step_id,
                tool_name=envelope.tool_name,
            )
        return _constrain_outcome_to_persisted_facts(outcome, persisted)

    async def _finish_cancelled(
        self,
        run_id: str,
        context: TrustedExecutionContext,
        plan: RoutePlan,
        evidence: list[EvidenceEnvelope],
        counters: RuntimeCounters,
    ) -> RuntimeResult:
        outcome = StructuredOutcome(
            status=OutcomeStatus.CANCELLED,
            route_code=plan.route_code,
            blockers=("RUN_CANCELLED_BY_TRUSTED_CALLER",),
        )
        changed = await self._terminal(
            run_id,
            context,
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
    ) -> bool:
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
            if terminal is RunState.CANCELLED:
                record = await self._store.load_run(run_id, context)
                if record.state is RunState.CANCELLED:
                    return False
            if terminal is not RunState.CANCELLED and await self._store.is_cancellation_requested(run_id, context):
                return False
            raise RunStoreError("terminal-state CAS lost")
        return True

    @staticmethod
    def _build_outcome(evidence: Iterable[EvidenceEnvelope]) -> StructuredOutcome:
        envelopes = tuple(evidence)
        period = _by_tool(envelopes, "restaurant_period_comparison_read.v1")
        store = _by_tool(envelopes, "restaurant_store_performance_read.v1")
        dish = _optional_by_tool(envelopes, "restaurant_dish_margin_mix_read.v1")

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

        if dish is None:
            usable_dish_margins = []
            blockers.append("DISH_EVIDENCE_NOT_REQUESTED")
        else:
            dish_margins = _facts(dish, "dishGrossMargin")
            usable_dish_margins = [
                fact for fact in dish_margins if fact.value is not None
            ]
            if not usable_dish_margins:
                blockers.append("DISH_MARGIN_UNAVAILABLE")
            else:
                claims.extend(
                    _claim(dish, fact, "DISH_GROSS_MARGIN_OBSERVED")
                    for fact in usable_dish_margins[:3]
                )
                blockers.append("DISH_PERIOD_DECOMPOSITION_UNAVAILABLE")
            claims.extend(
                _claims_for_metric(dish, "revenue", "DISH_REVENUE_OBSERVED", 3)
            )

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
            action_proposals=_build_action_proposals(tuple(claims), tuple(blockers)),
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


def _optional_by_tool(
    envelopes: tuple[EvidenceEnvelope, ...], tool_name: str
) -> EvidenceEnvelope | None:
    for envelope in envelopes:
        if envelope.tool_name == tool_name:
            return envelope
    return None


def _round_one_gap_codes(evidence: tuple[EvidenceEnvelope, ...]) -> tuple[str, ...]:
    period = _by_tool(evidence, "restaurant_period_comparison_read.v1")
    if _first_negative(period, ("gross_marginMomChange", "gross_marginYoyChange")) is None:
        return ("GROSS_MARGIN_DECLINE_NOT_ESTABLISHED",)
    return ("STORE_MARGIN_UNAVAILABLE", "DISH_MARGIN_EVIDENCE_REQUIRED")


def _can_replan(evidence: tuple[EvidenceEnvelope, ...]) -> bool:
    period = _by_tool(evidence, "restaurant_period_comparison_read.v1")
    return _first_negative(
        period, ("gross_marginMomChange", "gross_marginYoyChange")
    ) is not None


def _final_gap_codes(evidence: tuple[EvidenceEnvelope, ...]) -> tuple[str, ...]:
    dish = _optional_by_tool(evidence, "restaurant_dish_margin_mix_read.v1")
    gaps = ["STORE_MARGIN_UNAVAILABLE"]
    if dish is None or not any(
        fact.value is not None for fact in _facts(dish, "dishGrossMargin")
    ):
        gaps.append("DISH_MARGIN_UNAVAILABLE")
    else:
        gaps.append("DISH_PERIOD_DECOMPOSITION_UNAVAILABLE")
    gaps.append("CAUSAL_ATTRIBUTION_UNSUPPORTED_BY_READ_CONTRACTS")
    return tuple(gaps)


def _build_action_proposals(
    claims: tuple[NumericClaim, ...], blockers: tuple[str, ...]
) -> tuple[ActionProposal, ...]:
    decline_refs = tuple(
        EvidenceReference(claim.evidence_id, claim.fact_id)
        for claim in claims
        if claim.statement_code == "GROSS_MARGIN_DECLINE_OBSERVED"
    )[:1]
    dish_refs = tuple(
        EvidenceReference(claim.evidence_id, claim.fact_id)
        for claim in claims
        if claim.statement_code in {
            "DISH_GROSS_MARGIN_OBSERVED",
            "DISH_REVENUE_OBSERVED",
        }
    )[:3]
    proposals: list[ActionProposal] = []
    if "STORE_MARGIN_UNAVAILABLE" in blockers:
        proposals.append(
            ActionProposal(
                proposal_code="COMPLETE_STORE_COST_ALLOCATION_PROPOSAL",
                action_code="REVIEW_STORE_COST_ALLOCATION",
                rationale_codes=("STORE_MARGIN_UNAVAILABLE",),
                evidence_references=decline_refs,
            )
        )
    if "DISH_MARGIN_UNAVAILABLE" in blockers:
        proposals.append(
            ActionProposal(
                proposal_code="COMPLETE_DISH_COST_DATA_PROPOSAL",
                action_code="REVIEW_DISH_COST_DATA",
                rationale_codes=("DISH_MARGIN_UNAVAILABLE",),
                evidence_references=decline_refs,
            )
        )
    elif dish_refs:
        proposals.append(
            ActionProposal(
                proposal_code="REVIEW_DISH_MARGIN_MIX_PROPOSAL",
                action_code="REVIEW_DISH_PRICING_AND_COST",
                rationale_codes=("DISH_PERIOD_DECOMPOSITION_UNAVAILABLE",),
                evidence_references=dish_refs,
            )
        )
    return tuple(proposals)


def _constrain_outcome_to_persisted_facts(
    outcome: StructuredOutcome,
    persisted: set[tuple[str, str]],
) -> StructuredOutcome:
    """Make terminal references a closed subset of durable drilldown facts."""

    claims = tuple(
        claim
        for claim in outcome.claims
        if (claim.evidence_id, claim.fact_id) in persisted
    )
    reference_loss = len(claims) != len(outcome.claims)
    proposals: list[ActionProposal] = []
    for proposal in outcome.action_proposals:
        references = tuple(
            reference
            for reference in proposal.evidence_references
            if (reference.evidence_id, reference.fact_id) in persisted
        )
        reference_loss = reference_loss or len(references) != len(
            proposal.evidence_references
        )
        proposals.append(replace(proposal, evidence_references=references))

    if not reference_loss:
        return outcome

    blockers = tuple(
        dict.fromkeys((*outcome.blockers, "PERSISTED_EVIDENCE_REFERENCE_TRUNCATED"))
    )
    decline_retained = any(
        claim.statement_code == "GROSS_MARGIN_DECLINE_OBSERVED" for claim in claims
    )
    observations = tuple(
        observation
        for observation in outcome.observations
        if decline_retained
        or observation != "GROSS_MARGIN_DECLINE_CONFIRMED_FROM_PERIOD_EVIDENCE"
    )
    return replace(
        outcome,
        status=OutcomeStatus.PARTIAL if claims else OutcomeStatus.NOT_COMPUTABLE,
        claims=claims,
        blockers=blockers,
        observations=observations,
        action_proposals=tuple(proposals),
    )


def _bounded_drilldown(
    facts,
    provenance,
    *,
    evidence_id: str,
    evidence_status: str,
    warning_codes: list[str],
):
    """Build a deterministic, referentially complete subset within Event v1 bytes."""

    truncated = False
    bounded_facts: list[dict] = []
    bounded_provenance: list[dict] = []
    retained_ref_ids: set[str] = set()
    source_provenance = {str(item.ref_id): item for item in provenance}

    def fits(candidate_facts, candidate_provenance) -> bool:
        payload = {
            "evidenceId": evidence_id,
            "evidenceStatus": evidence_status,
            "factReferences": candidate_facts,
            "provenance": candidate_provenance,
            # Reserve the truncation marker even when the final payload does not need it.
            "warningCodes": list(dict.fromkeys([*warning_codes, "DRILLDOWN_REFERENCE_TRUNCATED"]))[:100],
            "drilldownTruncated": True,
        }
        return len(
            json.dumps(
                payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
            ).encode("utf-8")
        ) <= 32_768

    for fact in facts[:12]:
        dimension_items = sorted(
            (str(key), str(value)) for key, value in fact.dimensions.items()
        )
        if len(dimension_items) > 10:
            truncated = True
        dimensions = {}
        for key, value in dimension_items[:10]:
            safe_key = key[:128]
            safe_value = value[:256]
            if safe_key != key or safe_value != value:
                truncated = True
            dimensions[safe_key] = safe_value
        raw_refs = [str(reference) for reference in fact.provenance_refs]
        refs = [reference for reference in raw_refs if reference in source_provenance]
        if len(refs) != len(raw_refs) or len(refs) > 10:
            truncated = True
        refs = refs[:10]
        if not refs:
            truncated = True
            continue
        candidate_fact = {
            "factId": fact.fact_id,
            "metric": fact.metric,
            "value": fact.value,
            "unit": fact.unit,
            "dimensions": dimensions,
            "provenanceRefs": refs,
        }
        new_refs = [reference for reference in refs if reference not in retained_ref_ids]
        available_slots = 20 - len(bounded_provenance)
        if len(new_refs) > available_slots:
            truncated = True
            allowed_new = set(new_refs[:available_slots])
            refs = [reference for reference in refs if reference in retained_ref_ids or reference in allowed_new]
            candidate_fact["provenanceRefs"] = refs
            new_refs = [reference for reference in new_refs if reference in allowed_new]
        if not refs:
            continue
        candidate_provenance = [*bounded_provenance]
        for reference_id in new_refs:
            reference = source_provenance[reference_id]
            asset = str(reference.asset)[:256]
            if asset != reference.asset:
                truncated = True
            candidate_provenance.append(
                {
                    "refId": reference_id,
                    "sourceType": reference.source_type,
                    "asset": asset,
                    "queryId": reference.query_id,
                    "sourceVersion": reference.source_version,
                }
            )
        if not fits([*bounded_facts, candidate_fact], candidate_provenance):
            truncated = True
            break
        bounded_facts.append(candidate_fact)
        bounded_provenance = candidate_provenance
        retained_ref_ids.update(refs)
    if len(facts) > 12:
        truncated = True

    return bounded_facts, bounded_provenance, truncated


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
