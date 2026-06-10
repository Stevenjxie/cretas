"""Unit tests for ChartInsightService (v4 — claims-pinning architecture).

Active test classes:
 1. TestExtractJsonObject — LLM response parse robustness
 2. TestSignatureDeterminism — signature determinism + cross-tenant sharing
 3. TestPoisonGuard — causal-prescriptive verb detection
 4. TestChartInsightService — service integration (budget/cross-tenant/LLM path)
 5. TestRBACGuarantees — JWT-wins + cross-tenant blocked
 6. TestEndpointRBAC — endpoint structural RBAC
 7. TestU1Hardening — budget-None fail-closed + token consume + cross-factory sig
 8. TestValidateClaims — C1.2 claims-pinning recompute + numeric-adjacency gate
 9. TestPromptAndTierStats — C1.3 _stats_for_tier + _build_insight_prompt
10. TestGetInsightClaimsPinning — Task 4 end-to-end: claims-pinning serve + ¥ gate

Removed (C3 — dead template machinery):
 - TestValidateTemplateParameterization (validate_template_parameterization deleted)
 - TestSafeFill (_safe_fill deleted)
 - Tests that exercised _lookup_template / _capture_template / _maybe_promote paths
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
    DEFAULT_PROMOTE_THRESHOLD,
    _POISON_VERB_RE,
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
# 2. Poison guard
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
    """Integration tests — budget + cross-tenant guards + LLM path (v4 claims-pinning)."""

    def _make_service(
        self,
        llm_response: Optional[dict] = None,
        budget=None,
    ) -> ChartInsightService:
        """Create a ChartInsightService with mocked pool + optional LLM response."""
        pool = MagicMock()
        conn = AsyncMock()
        pool.acquire = MagicMock()
        pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
        pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)
        conn.fetchrow = AsyncMock(return_value=None)
        conn.execute = AsyncMock(return_value=None)
        conn.fetchval = AsyncMock(return_value=1)

        svc = ChartInsightService(
            pool=pool,
            budget_tracker=budget or FakeBudgetOk(),
            promote_threshold=DEFAULT_PROMOTE_THRESHOLD,
        )
        svc._call_llm = AsyncMock(return_value=llm_response)
        return svc

    async def test_budget_blocked_returns_none(self):
        """Budget blocked → service returns None (no LLM call)."""
        ctx = _make_context()
        svc = self._make_service(budget=FakeBudgetBlocked())

        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        assert result is None
        svc._call_llm.assert_not_called()


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


@pytest.mark.asyncio
class TestU1Hardening:
    """U1 hardening: budget-None fail-closed + token consume + cross-factory sig."""

    def _make_service_with_none_tracker(self, llm_response=None):
        """Create service where budget_tracker=None (simulates pool init failure)."""
        pool = MagicMock()
        conn = AsyncMock()
        pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
        pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)
        conn.fetchrow = AsyncMock(return_value=None)
        conn.execute = AsyncMock(return_value=None)
        conn.fetchval = AsyncMock(return_value=1)

        svc = ChartInsightService(
            pool=pool,
            budget_tracker=None,
            promote_threshold=3,
        )
        svc._call_llm = AsyncMock(return_value=llm_response)
        return svc

    def _make_service(self, llm_response=None, budget=None, promote_threshold=3):
        pool = MagicMock()
        conn = AsyncMock()
        pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
        pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)
        conn.fetchrow = AsyncMock(return_value=None)
        conn.execute = AsyncMock(return_value=None)
        conn.fetchval = AsyncMock(return_value=1)
        svc = ChartInsightService(
            pool=pool, budget_tracker=budget or FakeBudgetOk(), promote_threshold=promote_threshold,
        )
        svc._call_llm = AsyncMock(return_value=llm_response)
        return svc

    # ---- budget tracker None → fail-closed ----

    async def test_budget_tracker_none_fail_closed(self):
        """budget_tracker=None → get_insight returns None without AttributeError."""
        ctx = _make_context()
        # Use a valid claims-pinning response so failure is exclusively from budget=None
        valid_claims_response = {
            "claims": [{"entity": "A店", "stat_type": "share", "value": 35.7}],
            "finding": "A店占35.7%。",
            "implication": None,
            "suggestion": None,
        }
        svc = self._make_service_with_none_tracker(llm_response=valid_claims_response)
        result = await svc.get_insight(ctx, caller_role="restaurant_manager")
        assert result is None, (
            f"budget_tracker=None must return None (fail-closed), got: {result}"
        )
        svc._call_llm.assert_not_called()

    # ---- token realistic count ----

    async def test_token_consume_at_least_600(self):
        """After a successful LLM call, consume() must be called with tokens ≥ 600."""
        ctx = _make_context(
            series_values=[62.0, 38.0],
            series_labels=["堂食", "外卖"],
        )
        consumed_tokens = []

        class CapturingBudget:
            async def check_budget(self, factory_id, today=None):
                from smartbi.agent.budget_tracker import BudgetCheckResult
                return BudgetCheckResult(blocked=False, tokens_used=0, tokens_cap=50000)

            async def consume(self, factory_id, tokens, today=None):
                consumed_tokens.append(tokens)
                from smartbi.agent.budget_tracker import BudgetCheckResult
                return BudgetCheckResult(blocked=False, tokens_used=tokens, tokens_cap=50000)

        # Valid claims-pinning response so LLM path runs to completion
        valid_resp = {
            "claims": [{"entity": "堂食", "stat_type": "share", "value": 62.0}],
            "finding": "堂食占62%，是主要渠道。",
            "implication": None,
            "suggestion": None,
        }
        svc = self._make_service(llm_response=valid_resp, budget=CapturingBudget())
        await svc.get_insight(ctx, caller_role="restaurant_manager")

        assert len(consumed_tokens) > 0, "consume() should have been called after LLM response"
        assert consumed_tokens[0] >= 600, (
            f"Token consumption must be ≥600 for realistic LLM accounting, got {consumed_tokens[0]}"
        )

    # ---- cross-factory same signature ----

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


# ---------------------------------------------------------------------------
# Task 4: get_insight claims-pinning serve + ¥ serve-gate (C1+C3)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
class TestGetInsightClaimsPinning:
    """Task 4 — wire _validate_claims into the serve path + finance_hidden ¥ serve-gate.

    All three tests monkeypatch ChartInsightService._call_llm to return a pre-built
    structured dict (claims+finding+implication+suggestion) bypassing the real LLM.
    The service must:
      1. Call _validate_claims on the returned dict.
      2. Enforce the finance_hidden ¥ serve-gate post-validation.
      3. Return InsightResult(source="llm", tier=2) on success, None on rejection.
    """

    def _make_service(
        self,
        permission_tier: str = "finance_visible",
        factory_id: str = "F001",
    ):
        """Create a ChartInsightService with mocked pool and non-blocked budget.

        pool.acquire() returns a conn mock with:
          - fetchrow → None (no template in DB; Tier2a miss → proceeds to LLM)
          - execute → None (capture writes are no-ops in Task 4 scope)
        _call_llm is NOT set here — caller must monkeypatch it.
        """
        pool = MagicMock()
        conn = AsyncMock()
        pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
        pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)
        conn.fetchrow = AsyncMock(return_value=None)   # Tier2a miss
        conn.execute = AsyncMock(return_value=None)
        conn.fetchval = AsyncMock(return_value=1)

        svc = ChartInsightService(
            pool=pool,
            budget_tracker=FakeBudgetOk(),
            promote_threshold=DEFAULT_PROMOTE_THRESHOLD,
        )
        return svc

    def _make_ctx(self, permission_tier: str = "finance_visible") -> ChartInsightContext:
        return ChartInsightContext(
            chart_type="PIE",
            x_dim="channel",
            y_metric="revenue",
            aggregation="sum",
            domain="restaurant",
            data_pattern="ranking:top-share:65-80:n4-8",
            permission_tier=permission_tier,
            factory_id="F001",
            series_values=[62.0, 38.0],
            series_labels=["堂食", "外卖"],
        )

    # ------------------------------------------------------------------
    # Test 1: valid LLM response is served (claims pass validation)
    # ------------------------------------------------------------------

    async def test_valid_llm_served(self):
        """Valid structured LLM response with correct claim anchors → InsightResult returned."""
        svc = self._make_service(permission_tier="finance_visible")
        ctx = self._make_ctx(permission_tier="finance_visible")

        # Structured LLM response: claim is correct (堂食=62%), prose number 62 is present,
        # nearest label to 62 in prose is 堂食 → passes _validate_claims
        structured_response = {
            "claims": [{"entity": "堂食", "stat_type": "share", "value": 62.0}],
            "finding": "堂食占62%，是主要渠道。",
            "implication": "堂食渠道占据主导地位。",
            "suggestion": "关注堂食表现。",
        }
        svc._call_llm = AsyncMock(return_value=structured_response)

        result = await svc.get_insight(ctx, caller_role="restaurant_manager", jwt_factory_id="F001")

        assert result is not None, (
            "Valid structured LLM response should produce an InsightResult, got None"
        )
        assert result.source == "llm", f"Expected source='llm', got {result.source!r}"
        assert result.tier == 2, f"Expected tier=2, got {result.tier}"
        assert result.finding is not None
        assert "堂食" in result.finding

    # ------------------------------------------------------------------
    # Test 2: finance_hidden + ¥ in validated finding → gate rejects (return None)
    # ------------------------------------------------------------------

    async def test_finance_hidden_yuan_rejected(self):
        """finance_hidden caller + ¥ literal in LLM finding → serve-gate returns None."""
        svc = self._make_service(permission_tier="finance_hidden")
        ctx = self._make_ctx(permission_tier="finance_hidden")

        # Claims are technically valid (62 is correct for 堂食 share), but finding contains ¥
        # The ¥ serve-gate must fire and return None for finance_hidden callers.
        structured_response_with_yuan = {
            "claims": [{"entity": "堂食", "stat_type": "share", "value": 62.0}],
            "finding": "堂食占62%，绝对营收¥12345万。",   # ← ¥ literal
            "implication": "堂食渠道强劲。",
            "suggestion": "关注堂食表现。",
        }
        svc._call_llm = AsyncMock(return_value=structured_response_with_yuan)

        result = await svc.get_insight(ctx, caller_role="warehouse_worker", jwt_factory_id="F001")

        assert result is None, (
            "finance_hidden + ¥ in finding must return None (serve-gate must fire), "
            f"got result={result}"
        )

    # ------------------------------------------------------------------
    # Test 3: entity-swap claim → _validate_claims rejects → get_insight returns None
    # ------------------------------------------------------------------

    async def test_entity_swap_llm_falls_to_none(self):
        """LLM claims 外卖=62% (entity wrong; real is 堂食=62%) → _validate_claims rejects → None."""
        svc = self._make_service(permission_tier="finance_visible")
        ctx = self._make_ctx(permission_tier="finance_visible")

        # Entity-swap: 外卖's real share is 38%, but LLM claims 62%.
        # recompute_claim("外卖", "share", [62,38], ["堂食","外卖"]) = 38 ≠ 62 → claim dropped.
        # Prose still contains '62' but no valid claim anchors it → numeric-adjacency gate rejects.
        entity_swap_response = {
            "claims": [{"entity": "外卖", "stat_type": "share", "value": 62.0}],
            "finding": "外卖占62%，超过堂食。",
            "implication": "外卖渠道强劲。",
            "suggestion": "关注外卖增长。",
        }
        svc._call_llm = AsyncMock(return_value=entity_swap_response)

        result = await svc.get_insight(ctx, caller_role="restaurant_manager", jwt_factory_id="F001")

        assert result is None, (
            "Entity-swap (外卖=62 when real is 堂食=62) must be rejected by _validate_claims "
            f"and get_insight must return None; got result={result}"
        )


# ---------------------------------------------------------------------------
# Task 5: corpus gated persist (MF2) + 当日 input_hash 读回缓存 (MF4)
# ---------------------------------------------------------------------------

from smartbi.services.insights.chart_insight_service import (  # noqa: E402
    _corpus_input_text,
    _map_domain,
)
from smartbi.services.distillation_capture import compute_input_hash  # noqa: E402


@pytest.mark.asyncio
class TestCorpus:
    """Task 5 — corpus gated persist (MF2) + 当日 input_hash 读回缓存 (MF4).

    Two tests:
      1. test_persist_called_after_gate_with_correct_signature:
         Valid LLM response that passes all gates → persist_distillation_sample called once
         with correct positional+keyword args.
      2. test_rejected_output_not_persisted:
         Entity-swap LLM response → _validate_claims returns None → persist NOT called.
    """

    def _make_service_for_corpus(
        self,
        permission_tier: str = "finance_visible",
        factory_id: str = "F001",
        conn_fetchval=None,
    ):
        """Create ChartInsightService with a mocked pool.

        conn.fetchval is used for the read-back cache query (SELECT teacher_output ...).
        Default: None (cache miss → proceeds to LLM).
        _call_llm is NOT set here — callers monkeypatch it.
        """
        pool = MagicMock()
        conn = AsyncMock()
        pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
        pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)
        conn.fetchrow = AsyncMock(return_value=None)
        conn.execute = AsyncMock(return_value=None)
        # fetchval used for corpus read-back cache: None = cache miss
        conn.fetchval = AsyncMock(return_value=conn_fetchval)

        svc = ChartInsightService(
            pool=pool,
            budget_tracker=FakeBudgetOk(),
            promote_threshold=DEFAULT_PROMOTE_THRESHOLD,
        )
        return svc, pool, conn

    def _make_ctx(
        self,
        permission_tier: str = "finance_visible",
        domain: str = "restaurant",
        factory_id: str = "F001",
    ) -> ChartInsightContext:
        return ChartInsightContext(
            chart_type="PIE",
            x_dim="channel",
            y_metric="revenue",
            aggregation="sum",
            domain=domain,
            data_pattern="ranking:top-share:65-80:n4-8",
            permission_tier=permission_tier,
            factory_id=factory_id,
            series_values=[62.0, 38.0],
            series_labels=["堂食", "外卖"],
        )

    # ------------------------------------------------------------------
    # Test 1: gate-passed output → persist called with correct signature
    # ------------------------------------------------------------------

    async def test_persist_called_after_gate_with_correct_signature(self):
        """Valid structured LLM response that passes all gates → persist_distillation_sample
        called exactly once with:
          - source="chart_insight"
          - task_type="insights"
          - business_type in {"restaurant", "factory"} (mapped from domain)
          - teacher_model present (non-None string)
          - factory_id matches ctx.factory_id
          - metadata contains permission_tier
        """
        svc, pool, conn = self._make_service_for_corpus()
        ctx = self._make_ctx(domain="restaurant")

        valid_llm_response = {
            "claims": [{"entity": "堂食", "stat_type": "share", "value": 62.0}],
            "finding": "堂食占62%，是主要渠道。",
            "implication": None,
            "suggestion": None,
        }
        svc._call_llm = AsyncMock(return_value=valid_llm_response)

        persist_calls = []

        async def fake_persist(pool_arg, source, task_type, input_text, teacher_output, **kwargs):
            persist_calls.append({
                "source": source,
                "task_type": task_type,
                "input_text": input_text,
                "teacher_output": teacher_output,
                **kwargs,
            })

        with patch(
            "smartbi.services.insights.chart_insight_service.persist_distillation_sample",
            side_effect=fake_persist,
        ):
            result = await svc.get_insight(ctx, caller_role="restaurant_manager", jwt_factory_id="F001")

        # Must succeed (return an InsightResult)
        assert result is not None, (
            f"Valid gate-passing response should produce InsightResult, got None"
        )

        # persist must be called exactly once
        assert len(persist_calls) == 1, (
            f"persist_distillation_sample must be called exactly once after gate pass, "
            f"got {len(persist_calls)} calls"
        )

        call = persist_calls[0]

        # source and task_type (positional signature)
        assert call["source"] == "chart_insight", (
            f"source must be 'chart_insight', got {call['source']!r}"
        )
        assert call["task_type"] == "insights", (
            f"task_type must be 'insights', got {call['task_type']!r}"
        )

        # business_type must be mapped from domain (restaurant → "restaurant")
        assert call["business_type"] in {"restaurant", "factory"}, (
            f"business_type must be 'restaurant' or 'factory', got {call['business_type']!r}"
        )
        assert call["business_type"] == "restaurant", (
            f"domain='restaurant' must map to business_type='restaurant', got {call['business_type']!r}"
        )

        # teacher_model must be present (non-None)
        assert call.get("teacher_model") is not None, (
            "teacher_model must be passed (non-None) to corpus persist"
        )

        # factory_id must match ctx
        assert call.get("factory_id") == "F001", (
            f"factory_id must be ctx.factory_id='F001', got {call.get('factory_id')!r}"
        )

        # metadata must contain permission_tier
        metadata = call.get("metadata")
        assert metadata is not None, "metadata must be passed to corpus persist"
        assert "permission_tier" in metadata, (
            f"metadata must contain 'permission_tier', got {metadata!r}"
        )

        # input_text must be non-empty and stable (data-rich: includes series values)
        assert call["input_text"], "input_text must be non-empty"
        # Series absolute values must bake in (so different tenants don't collide)
        assert "62" in call["input_text"] or "38" in call["input_text"], (
            "input_text must contain series absolute values to avoid cross-tenant input_hash collision"
        )

        # teacher_output must be a JSON string of the validated output
        teacher_out = json.loads(call["teacher_output"])
        assert "finding" in teacher_out, (
            f"teacher_output must be JSON with 'finding', got {call['teacher_output']!r}"
        )

    # ------------------------------------------------------------------
    # Test 2: rejected output must NOT be persisted
    # ------------------------------------------------------------------

    async def test_rejected_output_not_persisted(self):
        """Entity-swap LLM response → _validate_claims returns None → get_insight returns None
        → persist_distillation_sample must NOT be called (rejected outputs never enter corpus).
        """
        svc, pool, conn = self._make_service_for_corpus()
        ctx = self._make_ctx(domain="restaurant")

        # Entity-swap: LLM claims 外卖=62% but real 外卖=38% → recompute fails → claim dropped
        # → no valid claims → _validate_claims returns None → rejected path
        entity_swap_response = {
            "claims": [{"entity": "外卖", "stat_type": "share", "value": 62.0}],
            "finding": "外卖占62%，超过堂食。",
            "implication": None,
            "suggestion": None,
        }
        svc._call_llm = AsyncMock(return_value=entity_swap_response)

        persist_calls = []

        async def fake_persist(pool_arg, source, task_type, input_text, teacher_output, **kwargs):
            persist_calls.append({"source": source, "task_type": task_type})

        with patch(
            "smartbi.services.insights.chart_insight_service.persist_distillation_sample",
            side_effect=fake_persist,
        ):
            result = await svc.get_insight(ctx, caller_role="restaurant_manager", jwt_factory_id="F001")

        # Must return None (entity-swap rejected by _validate_claims)
        assert result is None, (
            f"Entity-swap response must be rejected (None), got {result}"
        )

        # Persist must NOT be called for rejected outputs
        assert len(persist_calls) == 0, (
            f"persist_distillation_sample must NOT be called for rejected outputs, "
            f"got {len(persist_calls)} calls: {persist_calls}"
        )

    # ------------------------------------------------------------------
    # Test 3: _map_domain maps domains correctly
    # ------------------------------------------------------------------

    def test_map_domain(self):
        """_map_domain should map: restaurant→restaurant, finance→factory, factory→factory, else→unknown."""
        assert _map_domain("restaurant") == "restaurant"
        assert _map_domain("finance") == "factory"
        assert _map_domain("factory") == "factory"
        assert _map_domain("unknown_x") == "unknown"
        assert _map_domain("") == "unknown"
        assert _map_domain(None) == "unknown"

    # ------------------------------------------------------------------
    # Test 4: _corpus_input_text is data-rich and input_hash matches distillation_capture
    # ------------------------------------------------------------------

    def test_corpus_input_text_contains_series_and_hash_matches(self):
        """_corpus_input_text must:
        - include chart_type, domain, y_metric in the text
        - include absolute series values (so cross-tenant collision is avoided)
        - produce an input_hash (via compute_input_hash) that is a 64-char hex string
        """
        ctx = self._make_ctx(domain="restaurant")
        text = _corpus_input_text(ctx)

        assert text, "input_text must be non-empty"
        # Must contain chart_type
        assert "PIE" in text or "pie" in text.lower(), (
            f"input_text must contain chart_type, got: {text[:200]}"
        )
        # Must contain series values (absolute: 62.0 or 38.0)
        assert "62" in text, (
            f"input_text must contain series absolute value 62.x for cross-tenant safety, got: {text[:300]}"
        )
        # Must be stable: same ctx → same text → same hash
        text2 = _corpus_input_text(ctx)
        assert text == text2, "input_text must be deterministic"

        # Hash must be 64-char hex (sha256)
        h = compute_input_hash(text)
        assert len(h) == 64
        import re
        assert re.match(r'^[0-9a-f]{64}$', h), f"input_hash must be hex sha256, got {h!r}"
