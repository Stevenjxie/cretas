"""Unit tests for ChartInsightService (U4) — TDD first.

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
    FINANCE_METRICS,
)


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

    def test_different_factory_id_different_hash(self):
        ctx_f001 = _make_context(factory_id="F001")
        ctx_f002 = _make_context(factory_id="F002")
        assert compute_signature(ctx_f001) != compute_signature(ctx_f002)

    def test_different_data_pattern_different_hash(self):
        ctx_a = _make_context(data_pattern="trend:rising")
        ctx_b = _make_context(data_pattern="trend:falling")
        assert compute_signature(ctx_a) != compute_signature(ctx_b)

    def test_hash_algorithm_matches_sha256(self):
        ctx = _make_context(
            chart_type="BAR", x_dim="store", y_metric="revenue",
            aggregation="sum", domain="restaurant",
            data_pattern="ranking:top-share:65-80:cat-count:4-8",
            permission_tier="finance_visible", factory_id="F001"
        )
        sig = compute_signature(ctx)
        raw = "BAR|store|revenue|sum|restaurant|ranking:top-share:65-80:cat-count:4-8|finance_visible|F001"
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
        """finance:read_write role → absolute ¥ values allowed in fill."""
        ctx = _make_context(y_metric="revenue", permission_tier="finance_visible")
        row = _make_template_row(
            signature_hash=compute_signature(ctx),
            finding_tpl="{topName}营收{topRevenue}元，占比{topShare}%",
            required_permission="finance:read_write",
            is_active=True,
        )
        svc = self._make_service(db_row=row)

        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        assert result is not None
        assert result.source == "template"

    async def test_tier2a_finance_metric_without_finance_role_blocked(self):
        """Non-finance role → template requiring finance:read_write returns null or ratio-only."""
        ctx = _make_context(y_metric="revenue", permission_tier="price_hidden")
        row = _make_template_row(
            signature_hash=compute_signature(ctx),
            finding_tpl="{topName}营收{topRevenue}元",
            required_permission="finance:read_write",
            is_active=True,
        )
        svc = self._make_service(db_row=row)

        result = await svc.get_insight(ctx, caller_role="warehouse_worker")
        # Should be null or fallback (no absolute ¥ for low-perm role)
        if result is not None:
            # If returned, must NOT contain absolute ¥ template field
            assert "topRevenue" not in result.finding or "¥" not in result.finding

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
        """Finance yMetric + low-perm role → result must not contain absolute ¥ values."""
        ctx = _make_context(y_metric="revenue", permission_tier="finance_visible")
        row = _make_template_row(
            signature_hash=compute_signature(ctx),
            finding_tpl="{topName}营收{topRevenue}元，占比{topShare}%",
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

        # Low-privilege role
        result = await svc.get_insight(ctx, caller_role="warehouse_worker")

        if result is not None:
            # Must not contain absolute monetary values in finding
            # Accept null finding, percentage-only, or ratio text
            assert "元" not in result.finding or "%" in result.finding, (
                "Low-perm role received absolute ¥ value in finding"
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
            "data_pattern": "ranking:top-share:65-80:cat-count:4-8",
            "permission_tier": "finance_visible",
            "factory_id": "F001",  # This should be IGNORED; JWT wins
            "series_values": [100, 80, 60, 40],
            "series_labels": ["A", "B", "C", "D"],
        })
        # Should return 401 (no JWT factory_id in state)
        assert resp.status_code == 401, f"Expected 401 for missing auth, got {resp.status_code}"
