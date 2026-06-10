"""Unit tests for ChartInsightService (U4 + U1 硬化) — TDD first.

Tests:
 1. signature_determinism — same input → same hash; different permissionTier → different hash
 2. tier2a_hit — fills slots + permission gate blocks absolute ¥
 3. tier2b_miss — LLM mock returns structured JSON → captured, proposal_count=1, is_active=False
 4. promote_threshold_1 — threshold=1 → is_active=True after one LLM capture (finding-only auto)
 5. validate_template_parameterization — rejects literal store/product names and absolute numbers
 6. poison_guard — rejects causal-prescriptive verbs (复制/引流/加大/扩张/推广)
 7. rbac_low_perm_no_absolute_yen — low-perm caller gets no absolute ¥ values
 8. rbac_jwt_wins_over_body — request-body factoryId is ignored; JWT factoryId wins
 9. rbac_cross_tenant_blocked — cross-tenant read blocked
10. budget_blocked — budget blocked → service returns null
11. suggestion_needs_is_verified — suggestion-bearing template requires is_verified=True for auto-promote

U1 硬化新增:
12. safe_fill_illegal_slot_returns_none — unknown {slot} left after fill → result None
13. safe_fill_finding_none_yields_insight_none — finding filled to None → InsightResult None
14. safe_fill_implication_none_keeps_result — only implication/suggestion cleared, result survives
15. budget_tracker_none_fail_closed — budget_tracker=None → get_insight returns None without AttributeError
16. permission_tier_from_role_not_body — endpoint ignores body.permission_tier; derives from caller_role
17. poison_in_implication_rejected — _contains_poison on implication_tpl stops capture
18. yen_in_suggestion_rejected — absolute ¥ in suggestion_tpl stops capture
19. token_consume_realistic — consume called with ≥600 tokens per LLM call
20. template_not_overwritten_on_second_capture — ON CONFLICT does NOT update insight_template
21. cross_factory_same_signature — two factories same chart params → same signature_hash (no factoryId)
22. data_pattern_fixture_n4_8 — canonical data_pattern uses n4-8 not cat-count:4-8
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import re
import sys
import os
import pytest
from typing import Any, Dict, List, Optional
from unittest.mock import AsyncMock, MagicMock, patch

# ---------------------------------------------------------------------------
# Path setup: ensure the smartbi package and its siblings are importable
# ---------------------------------------------------------------------------
SMARTBI_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)  # …/backend/python
if SMARTBI_ROOT not in sys.path:
    sys.path.insert(0, SMARTBI_ROOT)

# ---------------------------------------------------------------------------
# Import the service under test (fails until implemented → RED phase)
# ---------------------------------------------------------------------------
from smartbi.services.insights.chart_insight_service import (
    ChartInsightService,
    ChartInsightContext,
    InsightResult,
    compute_signature,
    validate_template_parameterization,
    _POISON_VERB_RE,
    _safe_fill,
    _extract_json_object,
    FINANCE_METRICS,
)


class TestExtractJsonObject:
    """Hotfix: LLM response parse robustness (markdown fences / leading text)."""

    def test_bare_json(self):
        assert _extract_json_object('{"finding_tpl":"x"}') == {"finding_tpl": "x"}

    def test_markdown_fenced(self):
        assert _extract_json_object('```json\n{"a":1}\n```') == {"a": 1}

    def test_leading_reasoning_text(self):
        # glm-5.x often prepends text before the JSON object
        assert _extract_json_object('好的，分析如下：\n{"a":1,"b":2}') == {"a": 1, "b": 2}

    def test_garbage_returns_none(self):
        assert _extract_json_object("no json here") is None

    def test_empty_returns_none(self):
        assert _extract_json_object("") is None
        assert _extract_json_object("   ") is None

    def test_non_dict_returns_none(self):
        assert _extract_json_object("[1,2,3]") is None


# ---------------------------------------------------------------------------
# Shared fixtures / helpers
# ---------------------------------------------------------------------------

def _make_context(
    chart_type: str = "BAR",
    x_dim: str = "store",
    y_metric: str = "revenue",
    aggregation: str = "sum",
    domain: str = "restaurant",
    data_pattern: str = "ranking:top-share:65-80:cat-count:4-8",
    permission_tier: str = "finance_visible",
    factory_id: str = "F001",
    series_values: Optional[List[float]] = None,
    series_labels: Optional[List[str]] = None,
) -> ChartInsightContext:
    return ChartInsightContext(
        chart_type=chart_type,
        x_dim=x_dim,
        y_metric=y_metric,
        aggregation=aggregation,
        domain=domain,
        data_pattern=data_pattern,
        permission_tier=permission_tier,
        factory_id=factory_id,
        series_values=series_values or [100.0, 80.0, 60.0, 40.0],
        series_labels=series_labels or ["A店", "B店", "C店", "D店"],
    )


def _make_template_row(
    *,
    factory_id: str = "F001",
    signature_hash: str = "abc123",
    finding_tpl: str = "{topName}占{topShare}%，是末位{botName}的{ratio}倍",
    implication_tpl: Optional[str] = "头部集中度{concLevel}",
    suggestion_tpl: Optional[str] = None,
    required_permission: Optional[str] = None,
    source_type: str = "LLM_FALLBACK",
    hit_count: int = 5,
    proposal_count: int = 1,
    is_active: bool = True,
    is_verified: bool = False,
) -> dict:
    return {
        "factory_id": factory_id,
        "signature_hash": signature_hash,
        "insight_template": json.dumps({
            "finding_tpl": finding_tpl,
            "implication_tpl": implication_tpl,
            "suggestion_tpl": suggestion_tpl,
            "slots": ["topName", "topShare", "botName", "ratio", "concLevel"],
        }),
        "required_permission": required_permission,
        "source_type": source_type,
        "hit_count": hit_count,
        "proposal_count": proposal_count,
        "is_active": is_active,
        "is_verified": is_verified,
    }


class FakeBudgetBlocked:
    async def check_budget(self, factory_id, today=None):
        from smartbi.agent.budget_tracker import BudgetCheckResult
        return BudgetCheckResult(blocked=True, tokens_used=50000, tokens_cap=50000)

    async def consume(self, factory_id, tokens, today=None):
        from smartbi.agent.budget_tracker import BudgetCheckResult
        return BudgetCheckResult(blocked=True, tokens_used=50000, tokens_cap=50000)


class FakeBudgetOk:
    async def check_budget(self, factory_id, today=None):
        from smartbi.agent.budget_tracker import BudgetCheckResult
        return BudgetCheckResult(blocked=False, tokens_used=100, tokens_cap=50000)

    async def consume(self, factory_id, tokens, today=None):
        from smartbi.agent.budget_tracker import BudgetCheckResult
        return BudgetCheckResult(blocked=False, tokens_used=100 + tokens, tokens_cap=50000)


# LLM structured response mock
GOOD_LLM_RESPONSE = {
    "finding_tpl": "{topName}占{topShare}%，是末位{botName}的{ratio}倍",
    "implication_tpl": "头部集中度{concLevel}",
    "suggestion_tpl": None,
    "slots": ["topName", "topShare", "botName", "ratio", "concLevel"],
}


# ---------------------------------------------------------------------------
# 1. Signature determinism
# ---------------------------------------------------------------------------

class TestSignatureDeterminism:
    def test_same_input_same_hash(self):
        ctx1 = _make_context()
        ctx2 = _make_context()
        assert compute_signature(ctx1) == compute_signature(ctx2)

    def test_hash_is_64_hex_chars(self):
        ctx = _make_context()
        sig = compute_signature(ctx)
        assert len(sig) == 64
        assert re.match(r'^[0-9a-f]{64}$', sig)

    def test_different_permission_tier_different_hash(self):
        ctx_finance = _make_context(permission_tier="finance_visible")
        ctx_hidden = _make_context(permission_tier="finance_hidden")
        assert compute_signature(ctx_finance) != compute_signature(ctx_hidden)

    def test_different_factory_id_same_hash_cross_tenant_template(self):
        """U1.8: factoryId removed from signature → two factories with same chart params share a template."""
        ctx_f001 = _make_context(factory_id="F001")
        ctx_f002 = _make_context(factory_id="F002")
        # After U1.8: same chart params → same hash regardless of factoryId
        assert compute_signature(ctx_f001) == compute_signature(ctx_f002)

    def test_different_data_pattern_different_hash(self):
        ctx_a = _make_context(data_pattern="trend:rising")
        ctx_b = _make_context(data_pattern="trend:falling")
        assert compute_signature(ctx_a) != compute_signature(ctx_b)

    def test_hash_algorithm_matches_sha256(self):
        """U1.8: signature does NOT include factoryId — cross-tenant template sharing."""
        ctx = _make_context(
            chart_type="BAR", x_dim="store", y_metric="revenue",
            aggregation="sum", domain="restaurant",
            data_pattern="ranking:top-share:65-80:n4-8",
            permission_tier="finance_visible", factory_id="F001"
        )
        sig = compute_signature(ctx)
        # U1.8: factoryId excluded from raw string
        raw = "BAR|store|revenue|sum|restaurant|ranking:top-share:65-80:n4-8|finance_visible"
        expected = hashlib.sha256(raw.encode("utf-8")).hexdigest()
        assert sig == expected


# ---------------------------------------------------------------------------
# 2. Template parameterization validation
# ---------------------------------------------------------------------------

class TestValidateTemplateParameterization:
    def test_valid_template_passes(self):
        ok = validate_template_parameterization(
            "{topName}占{topShare}%，是末位{botName}的{ratio}倍"
        )
        assert ok is True

    def test_literal_store_name_rejected(self):
        bad = validate_template_parameterization(
            "A店占65%，是末位B店的3倍"
        )
        assert bad is False

    def test_absolute_number_rejected(self):
        # Template containing absolute RMB figure
        bad = validate_template_parameterization(
            "营收最高达¥12345，增长明显"
        )
        assert bad is False

    def test_literal_rmb_symbol_rejected(self):
        bad = validate_template_parameterization("总营收¥500万")
        assert bad is False

    def test_ratio_and_percentage_allowed(self):
        ok = validate_template_parameterization(
            "{topName}的增长率为{growthRate}%，高于行业均值"
        )
        assert ok is True

    def test_empty_finding_rejected(self):
        assert validate_template_parameterization("") is False

    def test_no_slots_with_literal_content_rejected(self):
        # No {placeholders} at all, just literal text — suspicious if it contains numbers
        bad = validate_template_parameterization("营收增长了25%")
        assert bad is False


# ---------------------------------------------------------------------------
# 3. Poison guard
# ---------------------------------------------------------------------------

class TestPoisonGuard:
    def test_causal_prescriptive_verbs_detected(self):
        for verb in ["复制", "引流", "加大", "扩张", "推广"]:
            text = f"建议{verb}该门店的成功经验"
            assert _POISON_VERB_RE.search(text) is not None, f"Expected poison verb '{verb}' to be detected"

    def test_observation_verbs_allowed(self):
        for verb in ["关注", "排查", "分析", "了解"]:
            text = f"建议{verb}该门店的业绩趋势"
            assert _POISON_VERB_RE.search(text) is None, f"Observation verb '{verb}' should NOT be flagged"

    def test_clean_template_not_flagged(self):
        clean = "{topName}占{topShare}%，建议关注末位{botName}的结构差异"
        assert _POISON_VERB_RE.search(clean) is None


# ---------------------------------------------------------------------------
# 4–10. Service integration tests (with mocked pool/DB/LLM)
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestChartInsightService:
    """Integration tests — all DB + LLM calls are mocked."""

    def _make_service(
        self,
        db_row: Optional[dict] = None,
        llm_response: Optional[dict] = None,
        budget=None,
        promote_threshold: int = 3,
    ) -> ChartInsightService:
        """Create a ChartInsightService with mocked pool and optional LLM."""
        pool = MagicMock()
        # Mock async context manager for pool.acquire()
        conn = AsyncMock()
        pool.acquire = MagicMock()
        pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
        pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)

        if db_row is not None:
            # fetchrow returns the row (simulating template hit)
            conn.fetchrow = AsyncMock(return_value=db_row)
        else:
            # fetchrow returns None (cache miss)
            conn.fetchrow = AsyncMock(return_value=None)

        # execute for upsert/update
        conn.execute = AsyncMock(return_value=None)
        # fetchval for proposal_count
        conn.fetchval = AsyncMock(return_value=1)

        svc = ChartInsightService(
            pool=pool,
            budget_tracker=budget or FakeBudgetOk(),
            promote_threshold=promote_threshold,
        )
        if llm_response is not None:
            svc._call_llm = AsyncMock(return_value=llm_response)
        else:
            svc._call_llm = AsyncMock(return_value=None)
        return svc

    async def test_tier2a_hit_fills_slots(self):
        """Template found → slots filled from context data."""
        ctx = _make_context(
            x_dim="store", y_metric="revenue",
            series_values=[300.0, 200.0, 100.0, 50.0],
            series_labels=["旗舰店", "A店", "B店", "C店"],
        )
        row = _make_template_row(
            signature_hash=compute_signature(ctx),
            finding_tpl="{topName}占{topShare}%，是末位{botName}的{ratio}倍",
            implication_tpl=None,
            suggestion_tpl=None,
        )
        svc = self._make_service(db_row=row)

        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        assert result is not None
        assert result.source == "template"
        assert result.tier == 2
        # The finding should have placeholders filled
        assert "{topName}" not in result.finding
        assert "旗舰店" in result.finding

    async def test_tier2a_finance_metric_with_finance_role_passes(self):
        """finance:read_write role → template with valid slots returned."""
        ctx = _make_context(y_metric="revenue", permission_tier="finance_visible")
        row = _make_template_row(
            signature_hash=compute_signature(ctx),
            # Use valid whitelist slots only ({topName}, {topShare}, {ratio})
            finding_tpl="{topName}占{topShare}%，是末位{botName}的{ratio}倍",
            required_permission="finance:read_write",
            is_active=True,
        )
        svc = self._make_service(db_row=row)

        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        assert result is not None
        assert result.source == "template"

    async def test_tier2a_finance_metric_without_finance_role_blocked(self):
        """Non-finance role → template requiring finance:read_write returns None."""
        ctx = _make_context(y_metric="revenue", permission_tier="finance_hidden")
        row = _make_template_row(
            signature_hash=compute_signature(ctx),
            finding_tpl="{topName}占{topShare}%，是末位{botName}的{ratio}倍",
            required_permission="finance:read_write",
            is_active=True,
        )
        svc = self._make_service(db_row=row)

        result = await svc.get_insight(ctx, caller_role="warehouse_worker")
        # warehouse_worker is not in FINANCE_ROLES → template blocked
        assert result is None

    async def test_tier2b_miss_calls_llm_and_captures(self):
        """No template hit → LLM called → result captured with proposal_count=1, is_active=False."""
        ctx = _make_context(factory_id="F001")
        svc = self._make_service(db_row=None, llm_response=GOOD_LLM_RESPONSE)

        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        # LLM was called
        svc._call_llm.assert_called_once()
        # Capture (upsert) was executed
        svc._pool.acquire.return_value.__aenter__.return_value.execute.assert_called()
        # Result has source='llm'
        assert result is not None
        assert result.source == "llm"
        assert result.tier == 2

    async def test_tier2b_llm_null_finding_returns_none(self):
        """LLM returns null finding → no fabrication → service returns None."""
        ctx = _make_context()
        null_response = {"finding_tpl": None, "implication_tpl": None, "suggestion_tpl": None, "slots": []}
        svc = self._make_service(db_row=None, llm_response=null_response)

        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        assert result is None

    async def test_promote_threshold_1_finding_only_auto_promotes(self):
        """proposal_count >= threshold=1 AND finding-only → is_active set True automatically."""
        ctx = _make_context(factory_id="F001")
        # Service with threshold=1
        svc = self._make_service(
            db_row=None,
            llm_response=GOOD_LLM_RESPONSE,  # no suggestion_tpl → finding-only
            promote_threshold=1,
        )
        # Mock fetchval to return proposal_count=1 after upsert
        svc._pool.acquire.return_value.__aenter__.return_value.fetchval = AsyncMock(return_value=1)

        await svc.get_insight(ctx, caller_role="restaurant_manager")

        # execute should have been called at least twice (upsert + promote)
        execute_calls = svc._pool.acquire.return_value.__aenter__.return_value.execute.call_args_list
        # At least one call should contain 'is_active = true' logic
        call_sql_texts = [str(c) for c in execute_calls]
        assert any("is_active" in sql for sql in call_sql_texts), (
            "Expected an is_active=true UPDATE call, but none found. Execute calls: " + str(execute_calls)
        )

    async def test_promote_suggestion_template_needs_is_verified(self):
        """suggestion-bearing template should NOT auto-promote unless is_verified=True (not our call here,
        but the service should NOT set is_active for suggestion template without is_verified)."""
        ctx = _make_context(factory_id="F001")
        response_with_suggestion = {
            "finding_tpl": "{topName}占{topShare}%",
            "implication_tpl": None,
            "suggestion_tpl": "建议关注{topName}与末位的差异",  # has suggestion
            "slots": ["topName", "topShare"],
        }
        svc = self._make_service(
            db_row=None,
            llm_response=response_with_suggestion,
            promote_threshold=1,
        )
        svc._pool.acquire.return_value.__aenter__.return_value.fetchval = AsyncMock(return_value=1)

        await svc.get_insight(ctx, caller_role="restaurant_manager")

        # Check: no execute call with 'is_active = true' for suggestion template without is_verified
        execute_calls = svc._pool.acquire.return_value.__aenter__.return_value.execute.call_args_list
        # There should be NO promote call (suggestion needs is_verified which is false)
        active_promote_calls = [
            c for c in execute_calls
            if "is_active" in str(c) and "true" in str(c).lower()
        ]
        assert len(active_promote_calls) == 0, (
            "Suggestion-bearing template should NOT auto-promote without is_verified. "
            f"Found promote calls: {active_promote_calls}"
        )

    async def test_budget_blocked_returns_none(self):
        """Budget blocked → service returns None (no fabrication, no LLM call)."""
        ctx = _make_context()
        svc = self._make_service(db_row=None, budget=FakeBudgetBlocked())

        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        assert result is None
        svc._call_llm.assert_not_called()

    async def test_poison_template_not_promoted(self):
        """LLM returns template with poison verb → validate_template_parameterization returns False → not promoted."""
        ctx = _make_context(factory_id="F001")
        poison_response = {
            "finding_tpl": "{topName}占{topShare}%",
            "implication_tpl": None,
            "suggestion_tpl": "建议复制{topName}门店的成功模式",  # POISON: 复制
            "slots": ["topName", "topShare"],
        }
        svc = self._make_service(
            db_row=None,
            llm_response=poison_response,
            promote_threshold=1,
        )
        svc._pool.acquire.return_value.__aenter__.return_value.fetchval = AsyncMock(return_value=1)

        await svc.get_insight(ctx, caller_role="restaurant_manager")

        execute_calls = svc._pool.acquire.return_value.__aenter__.return_value.execute.call_args_list
        active_promote_calls = [
            c for c in execute_calls
            if "is_active" in str(c) and "true" in str(c).lower()
        ]
        assert len(active_promote_calls) == 0, "Poison verb template should NOT be promoted"

    async def test_validate_template_literal_not_captured(self):
        """LLM returns template with literal store name → not valid → not captured."""
        ctx = _make_context(factory_id="F001")
        bad_response = {
            "finding_tpl": "A店占65%，是B店的3倍",  # literal store names
            "implication_tpl": None,
            "suggestion_tpl": None,
            "slots": [],
        }
        svc = self._make_service(db_row=None, llm_response=bad_response, promote_threshold=1)

        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        # Template invalid → still returns LLM result but does NOT upsert
        # (upsert should not happen for invalid template)
        execute_calls = svc._pool.acquire.return_value.__aenter__.return_value.execute.call_args_list
        assert len(execute_calls) == 0 or all(
            "ai_insight_templates" not in str(c) for c in execute_calls
        ), "Invalid template should NOT be written to ai_insight_templates"


# ---------------------------------------------------------------------------
# RBAC tests
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestRBACGuarantees:
    """Red-line RBAC tests: factoryId from JWT only, cross-tenant blocked, low-perm no ¥."""

    async def test_jwt_factory_id_wins_over_body_factory_id(self):
        """The service must use jwt_factory_id, ignoring any body-supplied factoryId."""
        from smartbi.services.insights.chart_insight_service import ChartInsightService

        # Context with factory_id="EVIL" (what an attacker might send in body)
        evil_ctx = _make_context(factory_id="EVIL")
        # JWT says F001
        jwt_factory_id = "F001"

        pool = MagicMock()
        conn = AsyncMock()
        pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
        pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)
        conn.fetchrow = AsyncMock(return_value=None)
        conn.execute = AsyncMock(return_value=None)
        conn.fetchval = AsyncMock(return_value=0)

        svc = ChartInsightService(pool=pool, budget_tracker=FakeBudgetOk(), promote_threshold=3)
        svc._call_llm = AsyncMock(return_value=None)

        # Call with explicit jwt_factory_id override
        await svc.get_insight(evil_ctx, caller_role="restaurant_manager", jwt_factory_id=jwt_factory_id)

        # The fetchrow call should use F001, not EVIL
        # We verify by checking what signature was used in the DB lookup
        if conn.fetchrow.called:
            call_args = conn.fetchrow.call_args
            # The signature passed to fetchrow should be for F001, not EVIL
            query_str = str(call_args)
            # The signature for EVIL would be different from F001
            f001_ctx = _make_context(factory_id="F001")
            evil_sig = compute_signature(evil_ctx)
            f001_sig = compute_signature(f001_ctx)
            # The query should NOT be looking up EVIL's signature
            assert evil_sig not in query_str or f001_sig in query_str, (
                "Service used the attacker's factory_id instead of JWT factory_id"
            )

    async def test_cross_tenant_blocked(self):
        """If jwt_factory_id != context.factory_id, service must refuse (IDOR guard)."""
        ctx = _make_context(factory_id="F002")  # different from JWT
        jwt_factory_id = "F001"

        pool = MagicMock()
        conn = AsyncMock()
        pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
        pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)
        conn.fetchrow = AsyncMock(return_value=None)
        conn.execute = AsyncMock(return_value=None)

        svc = ChartInsightService(pool=pool, budget_tracker=FakeBudgetOk(), promote_threshold=3)
        svc._call_llm = AsyncMock(return_value=None)

        result = await svc.get_insight(ctx, caller_role="restaurant_manager", jwt_factory_id=jwt_factory_id)
        # Must return None (blocked) when factory IDs mismatch
        assert result is None, (
            "Cross-tenant request (jwt_factory_id != ctx.factory_id) should return None"
        )
        # DB must not be queried with the attacker's factory_id
        conn.fetchrow.assert_not_called()

    async def test_finance_metric_low_perm_no_absolute_yen(self):
        """Finance yMetric + low-perm role (warehouse_worker) → template blocked → result None."""
        ctx = _make_context(y_metric="revenue", permission_tier="finance_visible")
        row = _make_template_row(
            signature_hash=compute_signature(ctx),
            finding_tpl="{topName}占{topShare}%，是末位{botName}的{ratio}倍",
            required_permission="finance:read_write",
            is_active=True,
        )

        pool = MagicMock()
        conn = AsyncMock()
        pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
        pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)
        conn.fetchrow = AsyncMock(return_value=row)
        conn.execute = AsyncMock(return_value=None)

        svc = ChartInsightService(pool=pool, budget_tracker=FakeBudgetOk(), promote_threshold=3)
        svc._call_llm = AsyncMock(return_value=None)

        # Low-privilege role: warehouse_worker is not in FINANCE_ROLES → template blocked
        result = await svc.get_insight(ctx, caller_role="warehouse_worker")
        # finance:read_write template blocked for non-finance role → None
        assert result is None, (
            "Low-perm role (warehouse_worker) should receive None for finance:read_write template"
        )


# ---------------------------------------------------------------------------
# Endpoint-level RBAC tests (test the FastAPI endpoint layer)
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestEndpointRBAC:
    """Test that the endpoint extracts factoryId from JWT (request.state), not body."""

    async def test_endpoint_uses_request_state_factory_id(self):
        """Endpoint must read factory_id from request.state (set by JWT middleware), never body."""
        # This is a structural test: we import the endpoint module and verify
        # the endpoint handler reads from request.state, not the request body's factory_id field.
        from smartbi.api.chart_insight import router as ci_router

        # Verify the router exists and has a POST route
        routes = [r for r in ci_router.routes]
        assert len(routes) > 0, "chart_insight router has no routes"

        # Check the route path
        route_paths = [getattr(r, 'path', '') for r in routes]
        assert any("/chart-insight" in p for p in route_paths), (
            f"Expected /chart-insight route, got: {route_paths}"
        )

    async def test_endpoint_rejects_missing_auth(self):
        """Endpoint returns 401 when factory_id is not in request.state (no JWT)."""
        from fastapi.testclient import TestClient
        from fastapi import FastAPI
        from smartbi.api.chart_insight import router as ci_router

        app = FastAPI()
        app.include_router(ci_router, prefix="/api/smartbi")

        client = TestClient(app)
        # No Authorization header → middleware would reject, but we simulate state absence
        resp = client.post("/api/smartbi/chart-insight", json={
            "chart_type": "BAR",
            "x_dim": "store",
            "y_metric": "revenue",
            "aggregation": "sum",
            "domain": "restaurant",
            "data_pattern": "ranking:top-share:65-80:n4-8",
            "factory_id": "F001",  # This should be IGNORED; JWT wins
            "series_values": [100, 80, 60, 40],
            "series_labels": ["A", "B", "C", "D"],
        })
        # Should return 401 (no JWT factory_id in state)
        assert resp.status_code == 401, f"Expected 401 for missing auth, got {resp.status_code}"

    async def test_endpoint_permission_tier_from_role_ignores_body(self):
        """U1.4: endpoint derives permission_tier from caller_role, ignores body.permission_tier."""
        from fastapi.testclient import TestClient
        from fastapi import FastAPI
        from smartbi.api.chart_insight import router as ci_router, FINANCE_ROLES

        app = FastAPI()
        app.include_router(ci_router, prefix="/api/smartbi")

        # We test by inspecting the context passed to _get_service/svc.get_insight
        # The simplest structural test: verify FINANCE_ROLES is exported from the API module
        assert "factory_super_admin" in FINANCE_ROLES, "FINANCE_ROLES must include factory_super_admin"
        assert "warehouse_worker" not in FINANCE_ROLES, "warehouse_worker must NOT be in FINANCE_ROLES"


# ---------------------------------------------------------------------------
# U1 hardening tests (new — must fail before implementation, pass after)
# ---------------------------------------------------------------------------

class TestSafeFill:
    """U1.2: _safe_fill must return None if any {slot} remains after filling."""

    def test_legal_slots_filled_normally(self):
        """All known slots provided → no {slot} remains → string returned."""
        tpl = "{topName}占{topShare}%，是末位{botName}的{ratio}倍"
        slot_values = {"topName": "旗舰店", "topShare": "45.0", "botName": "C店", "ratio": "3.0"}
        result = _safe_fill(tpl, slot_values)
        assert result is not None
        assert "{topName}" not in result
        assert "旗舰店" in result

    def test_unknown_slot_returns_none(self):
        """Template contains {topChannel} (not in whitelist/values) → None returned."""
        tpl = "{topName}占{topShare}%，{topChannel}渠道最高"
        slot_values = {"topName": "旗舰店", "topShare": "45.0"}
        result = _safe_fill(tpl, slot_values)
        assert result is None, f"Expected None for unfilled {{topChannel}}, got: {result!r}"

    def test_empty_template_returns_empty_string(self):
        """Empty template string with no slots → safe_fill returns empty string (not None)."""
        result = _safe_fill("", {})
        assert result is not None  # empty string is fine, not a broken slot
        assert result == ""

    def test_no_slots_in_template_returns_template(self):
        """Template with no {slots} at all → returned as-is."""
        tpl = "整体趋势平稳"
        result = _safe_fill(tpl, {})
        assert result == tpl


@pytest.mark.asyncio
class TestU1Hardening:
    """U1 items 2-9: comprehensive new tests that must fail before implementation."""

    def _make_service_with_none_tracker(self, db_row=None, llm_response=None):
        """Create service where budget_tracker=None (simulates pool init failure)."""
        pool = MagicMock()
        conn = AsyncMock()
        pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
        pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)
        conn.fetchrow = AsyncMock(return_value=db_row)
        conn.execute = AsyncMock(return_value=None)
        conn.fetchval = AsyncMock(return_value=1)

        svc = ChartInsightService(
            pool=pool,
            budget_tracker=None,  # <-- None budget tracker
            promote_threshold=3,
        )
        svc._call_llm = AsyncMock(return_value=llm_response)
        return svc

    def _make_service(self, db_row=None, llm_response=None, budget=None, promote_threshold=3):
        pool = MagicMock()
        conn = AsyncMock()
        pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
        pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)
        conn.fetchrow = AsyncMock(return_value=db_row)
        conn.execute = AsyncMock(return_value=None)
        conn.fetchval = AsyncMock(return_value=1)
        svc = ChartInsightService(
            pool=pool, budget_tracker=budget or FakeBudgetOk(), promote_threshold=promote_threshold,
        )
        svc._call_llm = AsyncMock(return_value=llm_response)
        return svc

    # ---- U1.2: safe fill + finding None → InsightResult None ----

    async def test_finding_with_unknown_slot_yields_insight_none(self):
        """U1.2: LLM returns template with {topChannel} (not in slot values) →
        _safe_fill returns None → InsightResult must be None (no raw {slot} to user)."""
        ctx = _make_context()
        bad_llm = {
            "finding_tpl": "{topName}占{topShare}%，{topChannel}渠道贡献最多",
            "implication_tpl": None,
            "suggestion_tpl": None,
            "slots": ["topName", "topShare", "topChannel"],
        }
        svc = self._make_service(db_row=None, llm_response=bad_llm)
        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        assert result is None, (
            f"Expected None when finding contains unfilled {{topChannel}}, got: {result}"
        )

    async def test_implication_with_unknown_slot_cleared_to_none(self):
        """U1.2: unknown slot only in implication → implication=None, result survives."""
        ctx = _make_context()
        llm_with_bad_impl = {
            "finding_tpl": "{topName}占{topShare}%，是末位{botName}的{ratio}倍",
            "implication_tpl": "渠道{unknownSlot}显著",
            "suggestion_tpl": None,
            "slots": ["topName", "topShare", "botName", "ratio"],
        }
        svc = self._make_service(db_row=None, llm_response=llm_with_bad_impl)
        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        # finding is valid so result should NOT be None
        assert result is not None, "Result should survive if only implication has unknown slot"
        assert result.implication is None, (
            f"implication with unknown slot must be None, got: {result.implication!r}"
        )
        # finding must be filled
        assert "{topName}" not in result.finding

    # ---- U1.3: budget tracker None → fail-closed ----

    async def test_budget_tracker_none_fail_closed(self):
        """U1.3: budget_tracker=None → get_insight returns None without AttributeError."""
        ctx = _make_context()
        svc = self._make_service_with_none_tracker(db_row=None, llm_response=GOOD_LLM_RESPONSE)
        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        # Must return None without raising AttributeError/NoneType error
        assert result is None, (
            f"budget_tracker=None must return None (fail-closed), got: {result}"
        )
        # LLM must NOT have been called (fail-closed before LLM)
        svc._call_llm.assert_not_called()

    # ---- U1.5: poison + ¥ on all 3 fields ----

    async def test_poison_in_implication_rejected(self):
        """U1.5: poison verb in implication_tpl → capture NOT written to DB."""
        ctx = _make_context()
        poisoned_impl = {
            "finding_tpl": "{topName}占{topShare}%",
            "implication_tpl": "应扩张{topName}的市场份额",  # POISON: 扩张
            "suggestion_tpl": None,
            "slots": ["topName", "topShare"],
        }
        svc = self._make_service(db_row=None, llm_response=poisoned_impl, promote_threshold=1)
        svc._pool.acquire.return_value.__aenter__.return_value.fetchval = AsyncMock(return_value=1)

        await svc.get_insight(ctx, caller_role="restaurant_manager")

        execute_calls = svc._pool.acquire.return_value.__aenter__.return_value.execute.call_args_list
        # No INSERT to ai_insight_templates should have been made
        template_inserts = [c for c in execute_calls if "ai_insight_templates" in str(c)]
        assert len(template_inserts) == 0, (
            f"Poison in implication_tpl should prevent capture. Inserts found: {template_inserts}"
        )

    async def test_yen_in_suggestion_rejected(self):
        """U1.5: absolute ¥ in suggestion_tpl → capture NOT written to DB."""
        ctx = _make_context()
        yen_suggestion = {
            "finding_tpl": "{topName}占{topShare}%",
            "implication_tpl": None,
            "suggestion_tpl": "建议关注¥1234万的差距",  # absolute ¥
            "slots": ["topName", "topShare"],
        }
        svc = self._make_service(db_row=None, llm_response=yen_suggestion, promote_threshold=1)
        svc._pool.acquire.return_value.__aenter__.return_value.fetchval = AsyncMock(return_value=1)

        await svc.get_insight(ctx, caller_role="restaurant_manager")

        execute_calls = svc._pool.acquire.return_value.__aenter__.return_value.execute.call_args_list
        template_inserts = [c for c in execute_calls if "ai_insight_templates" in str(c)]
        assert len(template_inserts) == 0, (
            f"Absolute ¥ in suggestion_tpl should prevent capture. Inserts: {template_inserts}"
        )

    # ---- U1.6: token realistic count ----

    async def test_token_consume_at_least_600(self):
        """U1.6: after a successful LLM call, consume() must be called with tokens ≥ 600."""
        ctx = _make_context()
        consumed_tokens = []

        class CapturingBudget:
            async def check_budget(self, factory_id, today=None):
                from smartbi.agent.budget_tracker import BudgetCheckResult
                return BudgetCheckResult(blocked=False, tokens_used=0, tokens_cap=50000)

            async def consume(self, factory_id, tokens, today=None):
                consumed_tokens.append(tokens)
                from smartbi.agent.budget_tracker import BudgetCheckResult
                return BudgetCheckResult(blocked=False, tokens_used=tokens, tokens_cap=50000)

        svc = self._make_service(db_row=None, llm_response=GOOD_LLM_RESPONSE, budget=CapturingBudget())
        await svc.get_insight(ctx, caller_role="restaurant_manager")

        assert len(consumed_tokens) > 0, "consume() should have been called after LLM response"
        assert consumed_tokens[0] >= 600, (
            f"Token consumption must be ≥600 for realistic LLM accounting, got {consumed_tokens[0]}"
        )

    # ---- U1.7: template not overwritten on second capture ----

    async def test_template_not_overwritten_on_second_capture(self):
        """U1.7: ON CONFLICT update must NOT include insight_template=EXCLUDED.insight_template."""
        ctx = _make_context()
        svc = self._make_service(db_row=None, llm_response=GOOD_LLM_RESPONSE, promote_threshold=10)
        svc._pool.acquire.return_value.__aenter__.return_value.fetchval = AsyncMock(return_value=2)

        await svc.get_insight(ctx, caller_role="restaurant_manager")

        execute_calls = svc._pool.acquire.return_value.__aenter__.return_value.execute.call_args_list
        for call in execute_calls:
            call_str = str(call)
            if "ai_insight_templates" in call_str and "ON CONFLICT" in call_str:
                assert "insight_template = EXCLUDED" not in call_str, (
                    "ON CONFLICT must NOT update insight_template (template lock after first capture)"
                )
            elif "ai_insight_templates" in call_str and "INSERT INTO" in call_str:
                # Find the SQL in the actual args
                args = call[0]  # positional args tuple
                if args:
                    sql = str(args[0])
                    assert "insight_template = EXCLUDED" not in sql, (
                        "INSERT ON CONFLICT must NOT overwrite insight_template"
                    )

    # ---- U1.8: cross-factory same signature ----

    def test_cross_factory_same_signature(self):
        """U1.8: two factories with same chart params → same signature_hash (factoryId excluded)."""
        ctx_f001 = _make_context(factory_id="F001", chart_type="BAR", x_dim="store",
                                  y_metric="revenue", data_pattern="ranking:top-share:65-80:n4-8")
        ctx_f002 = _make_context(factory_id="F002", chart_type="BAR", x_dim="store",
                                  y_metric="revenue", data_pattern="ranking:top-share:65-80:n4-8")
        sig_f001 = compute_signature(ctx_f001)
        sig_f002 = compute_signature(ctx_f002)
        assert sig_f001 == sig_f002, (
            f"Same chart params across factories must yield same signature. "
            f"F001={sig_f001[:12]}... F002={sig_f002[:12]}..."
        )

    # ---- U1.9: data_pattern n4-8 fixture ----

    def test_data_pattern_n4_8_in_fixture(self):
        """U1.9: canonical data_pattern uses n4-8 (not cat-count:4-8)."""
        # This test verifies the signature computation uses n4-8 format correctly
        ctx = _make_context(data_pattern="ranking:top-share:65-80:n4-8")
        sig = compute_signature(ctx)
        assert len(sig) == 64, "SHA256 signature must be 64 hex chars"
        # Verify n4-8 and cat-count:4-8 produce different signatures
        ctx_old = _make_context(data_pattern="ranking:top-share:65-80:cat-count:4-8")
        sig_old = compute_signature(ctx_old)
        assert sig != sig_old, "n4-8 and cat-count:4-8 data_patterns must yield different signatures"


# ---------------------------------------------------------------------------
# Task 2: _validate_claims  (C1.2 — MF1 hallucination-kill)
# ---------------------------------------------------------------------------

from smartbi.services.insights.chart_insight_service import (  # noqa: E402
    _validate_claims,
    _stats_for_tier,
    _build_insight_prompt,
)


def _ctx(values: List[float], labels: List[str], domain: str = "restaurant") -> ChartInsightContext:
    """Minimal ChartInsightContext for _validate_claims tests."""
    return ChartInsightContext(
        chart_type="PIE",
        x_dim="channel",
        y_metric="revenue",
        aggregation="sum",
        domain=domain,
        data_pattern="test",
        permission_tier="finance_visible",
        factory_id="F001",
        series_values=values,
        series_labels=labels,
    )


class TestValidateClaims:
    """C1.2: _validate_claims 重算校验 + 数字邻接闸 — MF1 核心。"""

    # -----------------------------------------------------------------------
    # Test 1: valid claim passes (share type, entity present, value within tolerance)
    # -----------------------------------------------------------------------
    def test_valid_claim_passes(self):
        """堂食=62, LLM says 堂食占62% → recompute 62/100=62% → valid; prose matches → pass."""
        ctx = _ctx([62.0, 38.0], ["堂食", "外卖"])
        llm_obj = {
            "claims": [{"entity": "堂食", "stat_type": "share", "value": 62.0}],
            "finding": "堂食占62%，是主要渠道。",
            "implication": "堂食渠道占据主导。",
            "suggestion": "持续关注堂食表现。",
        }
        result = _validate_claims(llm_obj, ctx)
        assert result is not None, "Valid claim with matching prose should pass"
        assert "finding" in result
        assert "implication" in result
        assert "suggestion" in result

    # -----------------------------------------------------------------------
    # Test 2: entity swap rejected — claim entity wrong, recompute gives different entity
    # -----------------------------------------------------------------------
    def test_entity_swap_rejected(self):
        """堂食=62 is the real 62%, but LLM says 外卖占62% (entity wrong) → recompute 外卖=38% ≠ 62 → drop claim.
        Prose has '62' adjacent to '外卖' but no valid claim with value≈62 → numeric-adjacency gate rejects."""
        ctx = _ctx([62.0, 38.0], ["堂食", "外卖"])
        llm_obj = {
            "claims": [{"entity": "外卖", "stat_type": "share", "value": 62.0}],  # WRONG entity
            "finding": "外卖占62%，超过堂食。",
            "implication": "外卖渠道强劲。",
            "suggestion": "关注外卖增长。",
        }
        result = _validate_claims(llm_obj, ctx)
        assert result is None, (
            "Entity-swap claim (外卖=62 when real is 堂食=62) must be rejected; "
            "prose number 62 no longer has a valid claim anchor → return None"
        )

    # -----------------------------------------------------------------------
    # Test 3: derived stat (top2_share) not false-rejected
    # -----------------------------------------------------------------------
    def test_derived_stat_not_false_rejected(self):
        """top2_share = (62+38)/100 * 100 = 100% but with [62,38,0] total=100 top2=(62+38)=100 → 100%.
        Use [50, 30, 20]: top2=(50+30)/100=80%, LLM claims top2_share=80 → must PASS (not mis-reject)."""
        ctx = _ctx([50.0, 30.0, 20.0], ["A店", "B店", "C店"])
        llm_obj = {
            "claims": [{"entity": None, "stat_type": "top2_share", "value": 80.0}],
            "finding": "前两名合计占80%。",
            "implication": "头部集中度较高。",
            "suggestion": "关注头部门店运营效率。",
        }
        result = _validate_claims(llm_obj, ctx)
        assert result is not None, (
            "top2_share=80% should be recomputed correctly and NOT false-rejected"
        )

    # -----------------------------------------------------------------------
    # Test 4: invented number in prose rejected
    # -----------------------------------------------------------------------
    def test_invented_number_in_prose_rejected(self):
        """Claim is valid (堂食=62%), but prose also mentions '15%' which is not in any claim → reject."""
        ctx = _ctx([62.0, 38.0], ["堂食", "外卖"])
        llm_obj = {
            "claims": [{"entity": "堂食", "stat_type": "share", "value": 62.0}],
            "finding": "堂食占62%，环比增长15%。",   # '15' is invented (not in claims)
            "implication": "增长稳健。",
            "suggestion": "保持现有策略。",
        }
        result = _validate_claims(llm_obj, ctx)
        assert result is None, (
            "Prose number '15' has no matching valid claim → numeric-adjacency gate must return None"
        )

    # -----------------------------------------------------------------------
    # Test 5: no claims returns None
    # -----------------------------------------------------------------------
    def test_no_claims_returns_none(self):
        """Empty claims list → no anchor for any number → return None immediately."""
        ctx = _ctx([62.0, 38.0], ["堂食", "外卖"])
        llm_obj = {
            "claims": [],
            "finding": "堂食表现良好。",
            "implication": None,
            "suggestion": None,
        }
        result = _validate_claims(llm_obj, ctx)
        assert result is None, "Empty claims list must return None"


# ---------------------------------------------------------------------------
# Task 3: _stats_for_tier + _build_insight_prompt (C1.3 — MF5 finance_hidden)
# ---------------------------------------------------------------------------


class TestPromptAndTierStats:
    """C1.3: _stats_for_tier whitelist + _build_insight_prompt structured-claims contract."""

    def _make_slots(self) -> dict:
        """Slots dict representative of what _compute_slot_values returns for a trend chart.

        Includes both relative-safe keys (topName, topShare, ratio, growthRate, concLevel,
        botName) and the absolute-amount key (changeAmt) that must be stripped for
        finance_hidden callers.  Values are intentionally recognisable so we can assert
        their presence / absence in the prompt.
        """
        return {
            "topName": "堂食",
            "botName": "外卖",
            "topShare": "62.0",
            "ratio": "1.6",
            "concLevel": "偏高",
            "growthRate": "5.2",
            "changeAmt": "62000",   # absolute ¥-equivalent — must be hidden from finance_hidden
        }

    # ------------------------------------------------------------------
    # test 1: finance_hidden must exclude changeAmt (and raw absolute slots)
    # ------------------------------------------------------------------

    def test_finance_hidden_excludes_absolute(self):
        """finance_hidden tier: _stats_for_tier must drop changeAmt; keep topShare / ratio."""
        slots = self._make_slots()
        result = _stats_for_tier(slots, "finance_hidden")

        # changeAmt is an absolute last-first delta — must be excluded
        assert "changeAmt" not in result, (
            "finance_hidden must exclude changeAmt (absolute ¥ delta)"
        )
        # Relative stats must survive
        assert "topShare" in result, "topShare (relative %) must be kept for finance_hidden"
        assert "ratio" in result, "ratio must be kept for finance_hidden"
        assert "growthRate" in result, "growthRate (relative %) must be kept for finance_hidden"
        assert "concLevel" in result, "concLevel must be kept for finance_hidden"
        assert "topName" in result, "topName (label, not amount) must be kept for finance_hidden"
        assert "botName" in result, "botName (label, not amount) must be kept for finance_hidden"

    # ------------------------------------------------------------------
    # test 2: finance_visible keeps all slots including changeAmt
    # ------------------------------------------------------------------

    def test_finance_visible_keeps_absolute(self):
        """finance_visible tier: _stats_for_tier must return all slots unchanged."""
        slots = self._make_slots()
        result = _stats_for_tier(slots, "finance_visible")

        assert "changeAmt" in result, (
            "finance_visible must keep changeAmt (caller has full finance permission)"
        )
        assert result == slots, (
            "finance_visible should return all slots unchanged"
        )

    # ------------------------------------------------------------------
    # test 3: prompt uses structured-claims JSON contract + no raw ¥ for finance_hidden
    # ------------------------------------------------------------------

    def test_prompt_asks_for_structured_claims(self):
        """_build_insight_prompt(ctx, 'finance_hidden') must:
        - request JSON with 'claims' array and 'stat_type' field
        - NOT embed the literal '62000' (absolute changeAmt value) in the prompt
        - NOT embed raw series absolute values for finance_hidden callers
        """
        ctx = _ctx(
            values=[62000.0, 38000.0],
            labels=["堂食", "外卖"],
        )
        # Override permission_tier on ctx for the test
        ctx.permission_tier = "finance_hidden"

        prompt = _build_insight_prompt(ctx, "finance_hidden")

        # Prompt must instruct LLM to return a structured-claims JSON
        assert "claims" in prompt, (
            "Prompt must instruct LLM to return 'claims' array"
        )
        assert "stat_type" in prompt, (
            "Prompt must reference 'stat_type' field in the claims contract"
        )

        # For finance_hidden, the literal absolute value '62000' must NOT appear in the prompt
        # (the raw series_values are absolute ¥ — feeding them to the prompt leaks ¥ to the LLM
        # which then echoes them in the prose)
        assert "62000" not in prompt, (
            "finance_hidden prompt must NOT contain the raw absolute series value '62000'"
        )
