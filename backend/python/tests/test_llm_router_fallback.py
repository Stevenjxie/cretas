"""Tests for the smart LLM router (2026-07-01 rebuild).

Spec: docs/superpowers/specs/2026-07-01-smart-llm-router-spec.md (6-agent audited).
Architecture: billing-safe per-(account,model) registry (Layer 1) + capability chains
(Layer 2) + per-slot param profiles (Layer 3) + outcome validation & 429/403 split
(Layer 4) + attribution (Layer 5, in llm_metrics).

The CENTRAL test is the billing-safety invariant (design-audit must-have c): every
model any SLOT chain could call is a confirmed-ON registry entry, and the registry
shares zero members with the per-account landmine set (the models that BILL). This
locks the money-path that caused the 6/11 silent-billing incident.
"""
from __future__ import annotations

import datetime
import asyncio
import time
from typing import Dict, List, Tuple
from unittest.mock import MagicMock

import pytest

from common import llm_router
from common.llm_router import SLOT, call_chain


# Per-account landmines — models that BILL if called (未开启 / 不支持开启 on that
# account, per console scrape 2026-06-30). NONE may ever be in _SAFE_MODELS.
#
# 2026-08-09: removed ("aliyun_b","kimi-k2.7-code") and ("aliyun_b","qwen3.5-ocr").
# That day aliyun_b returned 403 AllocationQuota.FreeTierOnly for several models
# (qwen3.7-flash / qwen3.7-plus-2026-05-26 / qwen3.7-max-2026-06-08 / deepseek-v3.2)
# — emitting that exact error code is only possible when the account's 「免费额度
# 用完即停」 toggle is ON, which proves a 200 from aliyun_b is served from free
# quota, not billed. The owner's aliyun_b console screenshot the same day lists
# both models with ~1,000,000 remaining free quota each, expiring 2026-09-14. The
# old entries were built from a 2026-06-30 console scrape and were simply stale.
# Both are now registered in _SAFE_MODELS (see the 08-09 audit section).
_LANDMINES = {
    ("aliyun_b", "deepseek-v4-pro"),  # 不支持开启 on b (ON on c)
    ("aliyun_b", "glm-5.2"),          # 不支持开启 on b (ON on c)
    ("aliyun_c", "deepseek-v4-pro"),  # only free on tencent; ON-but-check per acct
}


@pytest.fixture(autouse=True)
def _reset_caches():
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()
    yield
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()


# ════════════════════════════════════════════════════════════════════════
# Layer 1 — billing-safety invariants (THE money-path net)
# ════════════════════════════════════════════════════════════════════════

def test_every_chain_entry_is_a_registered_safe_model():
    """Nothing a SLOT chain can call is outside _SAFE_MODELS (ON-toggle allowlist).

    THE central billing-safety invariant. 2026-08-09: SLOT_MODELS is now
    _build_chain(slot) for every slot — computed from _SLOT_POOLS, which is
    itself authored only from pairs already present in _SAFE_MODELS (Task 4
    of the "llm-router expiry-first chain" plan). So this test is structurally
    satisfied by construction; it stays as a standing regression guard against
    a future hand-edit to _SLOT_POOLS that adds an unregistered pair.
    """
    for slot, chain in llm_router.SLOT_MODELS.items():
        for account, model in chain:
            assert (account, model) in llm_router._SAFE_MODELS, (
                f"{slot.value}: {account}/{model} not in _SAFE_MODELS → could bill"
            )


def test_registry_shares_no_members_with_landmines():
    """The ON allowlist must never contain a per-account BILLS model."""
    hits = [p for p in _LANDMINES if p in llm_router._SAFE_MODELS]
    # deepseek-v4-pro is only registered on tencent; assert the aliyun landmines absent.
    aliyun_hits = [p for p in hits if p[0].startswith("aliyun")]
    assert not aliyun_hits, f"landmines in registry → would bill: {aliyun_hits}"


def test_no_thinking_only_model_in_fast_slots():
    """thinking-only models (always reason, slow) belong to REASONING only."""
    fast = (SLOT.CHAT, SLOT.CHART, SLOT.MAPPER)
    for slot in fast:
        for _ac, m in llm_router.SLOT_MODELS[slot]:
            assert m not in llm_router._THINKING_ONLY, f"{slot.value} has thinking-only {m}"


def test_non_thinking_profile_slots_exclude_force_thinking_models():
    """Console quota does not make a model protocol-compatible.

    Live A/B/C probes showed the 05-17 and preview Max SKUs (and the rest of
    _THINKING_ONLY) reject enable_thinking=false. 2026-08-09 rewrite: the
    shared _QUALITY_TIER_POOL (INSIGHTS/REVIEW) now legitimately contains
    qwen3.7-max-2026-05-17 and kimi-k2.7-code — expiry-first sorting
    (_build_chain) puts a pool's whole authored content in the chain, not a
    hand-curated subset that dodges _THINKING_ONLY. The old policy ("keep
    these OUT of the chain entirely") is superseded by the structural guard
    already in _apply_slot_params: `model not in _THINKING_ONLY` skips
    injecting `enable_thinking` for exactly these models, regardless of which
    slot's chain they sit in. So the invariant this test must protect is not
    "never in a False-profile chain" but "never actually sent False" — assert
    that directly, against the real function, not a static exclusion list.
    """
    for slot, profile in llm_router._SLOT_PARAMS.items():
        if profile.get("enable_thinking") is not False:
            continue
        for account, model in llm_router.SLOT_MODELS[slot]:
            if model not in llm_router._THINKING_ONLY:
                continue
            p = llm_router._apply_slot_params(slot, account, model, {"messages": []})
            assert "enable_thinking" not in p, (
                f"{slot.value}: {account}/{model} is _THINKING_ONLY but would "
                f"still get enable_thinking=false → protocol 400"
            )


def test_no_denylist_name_in_registry():
    for account, model in llm_router._SAFE_MODELS:
        assert model not in llm_router._PAID_MODEL_DENYLIST, (
            f"denylisted {model} on {account} in registry"
        )


def test_chains_deduped():
    for slot, chain in llm_router.SLOT_MODELS.items():
        assert len(chain) == len(set(chain)), f"{slot.value} chain has duplicates"


def test_mapper_uses_bounded_fast_models_without_max_or_reasoners():
    """MAPPER pool: short JSON field mapping, bounded to models measured fast
    with thinking off. 2026-08-09 rewrite: the pool now legitimately includes
    deepseek-v3.2 / deepseek-v3.2-exp (0.9-1.1s per the pool's own latency
    notes) — a bare "deepseek" substring ban is no longer the right test (it
    would also false-positive on the floor's own "minimax-m2.7", which
    contains "max" as a substring of "mini-max"). The real, still-true policy
    is: no _THINKING_ONLY / _SLOW_MODELS reasoner and no Max-tier model in the
    authored pool (checked separately from the appended floor, which is
    allowed to be slow — see _SLOW_MODELS' comment on why minimax-m2.7 is
    exempt).
    """
    chain = llm_router.SLOT_MODELS[SLOT.MAPPER]
    assert chain[:4] == [
        ("aliyun_c", "qwen3-next-80b-a3b-instruct"),
        ("aliyun_c", "deepseek-v3.2-exp"),
        ("aliyun_c", "glm-4.6"),
        ("aliyun_c", "deepseek-v3.2"),
    ]
    assert ("tencent", "minimax-m2.7") in chain     # non-DashScope floor
    assert ("zhipu", "glm-4.5-air") in chain
    pool = llm_router._SLOT_POOLS[SLOT.MAPPER]      # pool only — floor excluded
    assert all(model not in llm_router._THINKING_ONLY for _a, model in pool)
    assert all(model not in llm_router._SLOW_MODELS for _a, model in pool)
    assert all(
        "qwen3.7-max" not in model and "qwen3.8-max" not in model
        for _a, model in pool
    )


def test_insights_and_review_share_the_quality_tier_pool():
    """2026-08-09 rewrite: INSIGHTS and REVIEW draw from the identical
    _QUALITY_TIER_POOL (same judging criteria — quality-first, relies on
    _build_chain's expiry-first sort rather than hand-curated "Plus before
    Max" ordering) plus the shared _TEXT_TAIL floor, so their built chains
    must be equal. This replaces the old per-slot hand-pinned head assertions
    (which named aliyun_b/aliyun_a qwen3.7-plus-2026-05-26 and
    qwen3.7-max-2026-06-08 — both retired from _SAFE_MODELS entirely by the
    Task 3 audit, see test_production_exhausted_aliyun_pairs_are_fully_retired)
    and guards against the two pools silently forking in a future edit.
    """
    insights = llm_router.SLOT_MODELS[SLOT.INSIGHTS]
    review = llm_router.SLOT_MODELS[SLOT.REVIEW]
    assert insights == review
    assert insights == list(llm_router._QUALITY_TIER_POOL) + list(llm_router._TEXT_TAIL)
    assert insights[:2] == [
        ("aliyun_c", "deepseek-v3.2"),
        ("aliyun_c", "glm-4.6"),
    ]


def test_review_still_reaches_a_non_aliyun_floor_after_aliyun_exhausts():
    """2026-08-09 rewrite inverts the old head-of-chain guarantee: expiry-first
    ordering means REVIEW now LEADS with the earliest-expiring aliyun pairs
    (from _QUALITY_TIER_POOL, real dates) and the non-aliyun floor sits at the
    structural tail (_TEXT_TAIL entries have expiry=None -> _FAR_FUTURE, which
    always sorts last). That reversal is the whole point of the rewrite (use
    the expiring grants before they're wasted) — what must still hold is that
    the non-aliyun floor is reachable at all, and strictly after every aliyun
    entry, once aliyun is exhausted.
    """
    chain = llm_router.SLOT_MODELS[SLOT.REVIEW]
    non_aliyun = [(a, m) for a, m in chain if a not in llm_router._ALIYUN_ACCOUNTS]
    assert non_aliyun, "REVIEW has no non-aliyun floor at all"
    last_aliyun_idx = max(
        i for i, (a, _m) in enumerate(chain) if a in llm_router._ALIYUN_ACCOUNTS
    )
    first_floor_idx = min(chain.index(p) for p in non_aliyun)
    assert first_floor_idx > last_aliyun_idx, (
        "non-aliyun floor precedes an aliyun pair -- floor should be the "
        "structural tail (None expiry -> _FAR_FUTURE)"
    )


def test_no_slot_keeps_an_aliyun_grant_expired_by_august_3():
    audit_date = datetime.date(2026, 8, 3)
    offenders = [
        (slot.value, account, model)
        for slot, chain in llm_router.SLOT_MODELS.items()
        for account, model in chain
        if account in llm_router._ALIYUN_ACCOUNTS
        and llm_router._refuse_reason(account, model, audit_date) == "expired"
    ]
    assert not offenders, f"expired Aliyun models remain reachable: {offenders}"


def test_production_exhausted_aliyun_pairs_are_fully_retired():
    retired = {
        ("aliyun_a", "qwen3.7-plus"),
        ("aliyun_b", "qwen3.7-plus"),
        ("aliyun_c", "qwen3.7-plus"),
        ("aliyun_c", "qwen3.7-plus-2026-05-26"),
    }
    reachable = {
        pair
        for chain in llm_router.SLOT_MODELS.values()
        for pair in chain
    }
    assert retired.isdisjoint(llm_router._SAFE_MODELS)
    assert retired.isdisjoint(reachable)


def test_flash_quota_pair_retired_from_b_and_c_survives_only_on_a():
    """2026-08-09 重审: qwen3.7-flash 系列过去以为 b/c 也有余量, 三账号截图+探针
    交叉核对后只有 aliyun_a 还有效(10/23); aliyun_b/aliyun_c 的同名条目从注册表
    整体移除。CHAT/CHART/MAPPER 链头仍是旧字面量(SLOT_MODELS 由 Task 4 重排),
    这里只钉注册表这张事实表, 不断言尚未重排的链头。
    """
    expected = [
        ("aliyun_a", "qwen3.7-flash-2026-07-15"),
        ("aliyun_a", "qwen3.7-flash"),
    ]
    for pair in expected:
        assert llm_router._SAFE_MODELS[pair] == datetime.date(2026, 10, 23)
        assert pair in llm_router._MINIMAL_SAFE_SET
    for retired in [
        ("aliyun_b", "qwen3.7-flash-2026-07-15"), ("aliyun_c", "qwen3.7-flash-2026-07-15"),
        ("aliyun_b", "qwen3.7-flash"), ("aliyun_c", "qwen3.7-flash"),
    ]:
        assert retired not in llm_router._SAFE_MODELS, (
            f"{retired} should have been retired by the 08-09 audit"
        )


def test_vl_chain_is_vision_only():
    for account, model in llm_router.SLOT_MODELS[SLOT.VL]:
        assert ("vl" in model) or (model == "glm-4.6v"), f"VL non-vision {account}/{model}"


# ════════════════════════════════════════════════════════════════════════
# _refuse_reason — the single shared billing gate
# ════════════════════════════════════════════════════════════════════════

_TODAY = datetime.date(2026, 8, 9)  # registry audit date → not stale
# (与 llm_router._REGISTRY_AUDIT_DATE 同步更新; call_chain 类测试用
# monkeypatch llm_router._today 冻结, 不再随真实日期漂移碎裂)


def test_refuse_allows_current_registered_model():
    assert llm_router._refuse_reason("aliyun_c", "qwen3.7-max-2026-05-17", _TODAY) is None
    assert llm_router._refuse_reason(
        "aliyun_a", "qwen3.7-flash-2026-07-15", _TODAY
    ) is None
    assert llm_router._refuse_reason(
        "aliyun_a", "qwen3.7-flash", _TODAY
    ) is None


def test_refuse_rejects_unregistered_landmine():
    # config-drift guard: even if someone put a landmine in a chain, the gate refuses.
    # 2026-08-09: aliyun_b/kimi-k2.7-code moved OFF the landmine list into
    # _SAFE_MODELS (see _LANDMINES comment above) — swapped in two landmines that
    # are still unregistered on their account.
    assert llm_router._refuse_reason("aliyun_b", "deepseek-v4-pro", _TODAY) == "not_allowlisted"
    assert llm_router._refuse_reason("aliyun_b", "glm-5.2", _TODAY) == "not_allowlisted"


def test_refuse_hard_drops_expired():
    # 2026-08-09 重审后 aliyun_a/qwen3.6-plus-2026-04-02 已从注册表整体移除
    # (not_allowlisted, 不会走到 expired 分支)。换成实测仍登记、到期日 08/13 的
    # aliyun_c/qwen3-next-80b-a3b-instruct — 过期当天即拒绝。
    after = datetime.date(2026, 8, 14)
    assert llm_router._refuse_reason(
        "aliyun_c", "qwen3-next-80b-a3b-instruct", after
    ) == "expired"


def test_refuse_denylist_veto():
    # Inject a denylisted name into the registry → still vetoed (belt & suspenders).
    key = ("aliyun_c", "qwen-max")
    llm_router._SAFE_MODELS[key] = datetime.date(2026, 9, 1)
    try:
        assert llm_router._refuse_reason("aliyun_c", "qwen-max", _TODAY) == "paid_denylist"
    finally:
        del llm_router._SAFE_MODELS[key]


def test_staleness_failsafe_narrows_to_minimal_set():
    stale = _TODAY + datetime.timedelta(days=llm_router._REGISTRY_MAX_AGE_DAYS + 1)
    # a normal (never-registered) model is refused when stale — the stale check
    # fires before allowlist membership is even consulted.
    assert llm_router._refuse_reason("aliyun_c", "qwen3.7-plus", stale) == "registry_stale"
    # …but a minimal-set survivor still allowed (if not itself expired).
    # 2026-08-09: glm-5.2 从 aliyun_c 注册表整体移除(见 08-09 重审), 换成实测
    # 仍在 _MINIMAL_SAFE_SET 里、到期日 09/14 的 aliyun_c/kimi-k2.7-code。
    assert llm_router._refuse_reason("aliyun_c", "kimi-k2.7-code", stale) is None


def test_future_date_every_slot_keeps_a_live_fallback():
    """After B+C bulk expiry (08/14) every TEXT slot still resolves ≥1 model (minimal
    set + never-expiring tencent/zhipu floor) — no hard 'all exhausted' (design-audit
    R3).

    VL is deliberately excluded: the 2026-08-09 audit killed zhipu/glm-4.6v (429
    balance-insufficient) and every remaining VL entry is aliyun_c, all expiring
    08/13 — so by 08/14 the VL floor is genuinely empty. That is intentional (see
    _MINIMAL_SAFE_SET comment: "新的最小集不含任何 VL 模型" / VL accepts an empty
    chain and reports explicitly per spec §9.1, pending the Task 5 VL exemption) —
    not a regression this test should paper over.
    """
    future = datetime.date(2026, 8, 14)
    for slot, chain in llm_router.SLOT_MODELS.items():
        if slot is SLOT.VL:
            continue
        live = [(a, m) for (a, m) in chain if llm_router._refuse_reason(a, m, future) is None]
        assert live, f"{slot.value} has ZERO live fallbacks at {future}"
    vl_live = [
        (a, m) for (a, m) in llm_router.SLOT_MODELS[SLOT.VL]
        if llm_router._refuse_reason(a, m, future) is None
    ]
    assert vl_live == [], (
        f"VL floor unexpectedly live at {future} ({vl_live}) — if the registry "
        "regained a VL grant, update this test's exclusion accordingly"
    )


# ════════════════════════════════════════════════════════════════════════
# Layer 3 — per-slot param profiles
# ════════════════════════════════════════════════════════════════════════

def test_fast_slots_disable_thinking_on_aliyun_hybrid():
    p = llm_router._apply_slot_params(SLOT.CHAT, "aliyun_c", "qwen3.5-flash",
                                      {"messages": [{"role": "user", "content": "hi"}]})
    assert p["enable_thinking"] is False


def test_reasoning_does_not_force_enable_thinking():
    # deepseek-v3.1 400s on enable_thinking=true (only supports false/absent); forced
    # deep thinking also times out call_chain. REASONING must NOT inject enable_thinking.
    p = llm_router._apply_slot_params(SLOT.REASONING, "aliyun_c", "deepseek-v3.1", {"messages": []})
    assert "enable_thinking" not in p


def test_thinking_only_model_gets_no_enable_thinking():
    p = llm_router._apply_slot_params(SLOT.REASONING, "aliyun_c", "deepseek-r1", {"messages": []})
    assert "enable_thinking" not in p


def test_tokenhub_qwen_gets_its_documented_enable_thinking_switch():
    p = llm_router._apply_slot_params(
        SLOT.CHAT, "tencent", "qwen3.5-plus", {"messages": []},
    )
    assert p["enable_thinking"] is False
    assert "thinking" not in p


@pytest.mark.parametrize("model", ["deepseek-v4-pro-202606", "glm-5.2", "minimax-m3"])
def test_tokenhub_common_models_get_the_documented_thinking_object(model):
    p = llm_router._apply_slot_params(
        SLOT.REVIEW, "tencent", model, {"messages": []},
    )
    assert p["thinking"] == {"type": "disabled"}
    assert "enable_thinking" not in p


def test_tokenhub_reasoning_slot_does_not_disable_thinking():
    p = llm_router._apply_slot_params(
        SLOT.REASONING, "tencent", "deepseek-v4-pro-202606", {"messages": []},
    )
    assert "thinking" not in p
    assert "enable_thinking" not in p


@pytest.mark.parametrize("model", ["glm-4.5-air", "glm-4.6v"])
def test_zhipu_glm45_plus_gets_the_documented_thinking_object(model):
    p = llm_router._apply_slot_params(
        SLOT.REVIEW, "zhipu", model, {"messages": []},
    )
    assert p["thinking"] == {"type": "disabled"}
    assert "enable_thinking" not in p


def test_zhipu_reasoning_slot_keeps_thinking_available():
    p = llm_router._apply_slot_params(
        SLOT.REASONING, "zhipu", "glm-4.5-air", {"messages": []},
    )
    assert "thinking" not in p
    assert "enable_thinking" not in p


def test_zhipu_thinking_translation_is_provider_scoped():
    p = llm_router._apply_slot_params(
        SLOT.REVIEW, "aliyun_c", "glm-4.5-air", {"messages": []},
    )
    assert "thinking" not in p
    assert p["enable_thinking"] is False


def test_chart_json_object_only_when_prompt_mentions_json():
    with_json = llm_router._apply_slot_params(
        SLOT.CHART, "aliyun_c", "qwen3.5-flash",
        {"messages": [{"role": "system", "content": "只返回 JSON"}], "max_tokens": 150})
    assert with_json["response_format"] == {"type": "json_object"}
    assert "max_tokens" not in with_json          # popped (truncation = parse fail)
    assert with_json["temperature"] == 0
    without = llm_router._apply_slot_params(
        SLOT.CHART, "aliyun_c", "qwen3.5-flash",
        {"messages": [{"role": "system", "content": "recommend a chart"}]})
    assert "response_format" not in without        # avoid the 400


# ════════════════════════════════════════════════════════════════════════
# Layer 4 — outcome validation + 429/403 split
# ════════════════════════════════════════════════════════════════════════

def test_validate_empty_is_invalid():
    assert llm_router._validate_output(SLOT.CHAT, "") == "empty"
    assert llm_router._validate_output(SLOT.CHAT, "   ") == "empty"


def test_validate_chart_json():
    assert llm_router._validate_output(SLOT.CHART, "not json at all") == "bad_json"
    assert llm_router._validate_output(SLOT.CHART, '{"chartType":"BAR"}') is None
    assert llm_router._validate_output(SLOT.CHART, '```json\n{"a":1}\n```') is None


def test_validate_insights_min_length():
    assert llm_router._validate_output(SLOT.INSIGHTS, "ok") == "too_short"
    assert llm_router._validate_output(SLOT.INSIGHTS, "销售增长由复购率提升驱动，忠诚度增强。") is None


def test_429_is_not_quota_exhausted():
    # The correctness fix: transient 429 must NOT trigger the 6h quota-skip.
    assert llm_router._is_quota_exhausted(429, "Too Many Requests") is False
    assert llm_router._is_quota_exhausted(429, "") is False
    assert llm_router._is_quota_exhausted(
        429,
        '{"error":{"code":"RateLimitExceeded","message":"request rate limit exceeded"}}',
    ) is False


def test_ark_set_limit_exceeded_is_persistent_quota_exhaustion():
    assert llm_router._is_quota_exhausted(
        429,
        '{"error":{"code":"SetLimitExceeded","message":"Your account has reached '
        'the set inference limit for this model, and the model service has been paused."}}',
    ) is True


def test_403_and_402_are_quota_exhausted():
    assert llm_router._is_quota_exhausted(403, "AllocationQuota.FreeTierOnly") is True
    assert llm_router._is_quota_exhausted(402, "Insufficient Balance") is True
    assert llm_router._is_quota_exhausted(402, "endpoint inactive: FREE_QUOTA_EXHAUSTED") is True
    assert llm_router._is_quota_exhausted(
        402,
        '{"error":{"code":"401008","message":"The free trial quota for the service '
        'has been exhausted and postpaid billing is not enabled"}}',
    ) is True
    assert llm_router._is_quota_exhausted(402, '{"error":{"code":401008}}') is True
    assert llm_router._is_quota_exhausted(500, "FreeTierOnly") is False


# ════════════════════════════════════════════════════════════════════════
# call_chain fallback smoke (mocked HTTP) — every attempted model is billing-safe
# ════════════════════════════════════════════════════════════════════════

def _fake_response(status_code: int, body: str = "", json_payload=None):
    resp = MagicMock()
    resp.status_code = status_code
    resp.text = body
    resp.json = MagicMock(return_value=json_payload or {})
    return resp


class _ScriptedClient:
    """httpx-AsyncClient stand-in returning a canned response per ACCOUNT (keyed by
    api-key substring). Records every (account, model) tried."""

    def __init__(self, by_account: Dict[str, MagicMock]):
        self.by_account = by_account
        self.call_log: List[Tuple[str, str]] = []

    async def post(self, url, headers=None, json=None, timeout=None):
        model = (json or {}).get("model", "?")
        auth = (headers or {}).get("Authorization", "")
        account = None
        if "dashscope" in url or "aliyuncs" in url:
            if "key_c" in auth:
                account = "aliyun_c"
            elif "key_b" in auth:
                account = "aliyun_b"
            elif "key_a" in auth:
                account = "aliyun_a"
        elif "tokenhub" in url:
            account = "tencent"
        elif "volces" in url:
            account = "ark"
        elif "bigmodel" in url:
            account = "zhipu"
        self.call_log.append((account or "unknown", model))
        return self.by_account.get(account, _fake_response(500, "no canned response"))


def _patch_keys(monkeypatch):
    monkeypatch.setenv("LLM_ALIYUN_A_API_KEY", "key_a_fake")
    monkeypatch.setenv("LLM_ALIYUN_B_API_KEY", "key_b_fake")
    monkeypatch.setenv("LLM_ALIYUN_C_API_KEY", "key_c_fake")
    monkeypatch.setenv("LLM_TENCENT_API_KEY", "key_tencent_fake")
    monkeypatch.setenv("LLM_ARK_API_KEY", "key_ark_fake")
    monkeypatch.setenv("LLM_ZHIPU_API_KEY", "key_zhipu_fake")


# 2026-08-09: the real SLOT_MODELS[SLOT.CHAT] head (qwen3.7-flash* on b/c) was
# retired by the 08-09 audit — Task 4 owns re-sorting SLOT_MODELS off the new
# registry, so these call_chain smoke tests monkeypatch a small deterministic
# chain out of CURRENTLY-registered pairs instead of relying on the real
# (not-yet-migrated) chain literal.
_CHAT_SMOKE_CHAIN = [("aliyun_a", "qwen3.7-flash"), ("aliyun_b", "qwen3.7-max-2026-05-17")]


@pytest.mark.asyncio
async def test_call_chain_falls_through_403_to_next(monkeypatch):
    """Routing smoke test: a 403 on the head candidate falls through to the next
    chain entry and returns its result.

    2026-08-09 review finding: this used to also assert
    `(account, model) in llm_router._SAFE_MODELS` for every attempted call. That
    was a real runtime cross-check back when it walked the REAL
    `SLOT_MODELS[SLOT.CHAT]` — it could fail if the live chain ever attempted an
    unregistered pair. Since this test now injects `_CHAT_SMOKE_CHAIN` (a list
    hand-picked FROM the registry) via monkeypatch, `call_chain` can only ever
    attempt entries from that list — the assertion became a tautology that can
    never fail, while the real chain-vs-registry invariant it used to police
    stayed silently unchecked. That invariant already has a dedicated (currently
    known-red, pending Task 4) test —
    `test_every_chain_entry_is_a_registered_safe_model` above — so it is not
    re-added here as a second, redundant known-red copy. This test now only
    verifies the fallback ROUTING behavior, which is independent of which
    models happen to be configured.
    """
    _patch_keys(monkeypatch)
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 8, 9))
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, _CHAT_SMOKE_CHAIN)
    ok = {"choices": [{"message": {"content": "今日入库3批，均合格。"}}]}
    client = _ScriptedClient({
        "aliyun_a": _fake_response(403, "AllocationQuota.FreeTierOnly"),
        "aliyun_b": _fake_response(200, json_payload=ok),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    result = await call_chain(SLOT.CHAT, {"messages": [{"role": "user", "content": "hi"}]})
    assert result == ok
    assert client.call_log == [("aliyun_a", "qwen3.7-flash"), ("aliyun_b", "qwen3.7-max-2026-05-17")]


@pytest.mark.asyncio
async def test_call_chain_persists_ark_set_limit_and_falls_through(monkeypatch):
    """Locks the SetLimitExceeded persistence behavior — generic to any provider,
    not ark-specific logic.

    2026-08-09: the ark section of _SAFE_MODELS was emptied outright (both
    entries SetLimitExceeded-paused; provider config kept, pending owner
    re-measurement). No ark (account, model) is registered right now, so a real
    ark call would be refused before ever reaching the HTTP layer — this test
    substitutes two currently-registered accounts to keep covering the same
    quota-skip-cache behavior the ark incident originally locked down.
    """
    _patch_keys(monkeypatch)
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 8, 9))
    first_account, first_model = "aliyun_a", "qwen3.7-flash"
    second_account, second_model = "zhipu", "glm-4.5-air"
    monkeypatch.setitem(
        llm_router.SLOT_MODELS,
        SLOT.CHAT,
        [(first_account, first_model), (second_account, second_model)],
    )
    good = {"choices": [{"message": {"content": "餐饮经营数据已完成分析。"}}]}
    client = _ScriptedClient({
        first_account: _fake_response(
            429,
            '{"error":{"code":"SetLimitExceeded","message":"model service has been paused"}}',
        ),
        second_account: _fake_response(200, json_payload=good),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)

    result = await call_chain(
        SLOT.CHAT,
        {"messages": [{"role": "user", "content": "分析餐饮经营数据"}]},
    )

    assert result == good
    assert client.call_log == [(first_account, first_model), (second_account, second_model)]
    assert llm_router._quota_should_skip(f"{first_account}/{first_model}") is True


@pytest.mark.asyncio
async def test_call_chain_rejects_empty_body_and_falls_back(monkeypatch):
    _patch_keys(monkeypatch)
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 8, 9))
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, _CHAT_SMOKE_CHAIN)
    empty = {"choices": [{"message": {"content": ""}}]}
    good = {"choices": [{"message": {"content": "今日入库3批，均合格。"}}]}
    client = _ScriptedClient({
        "aliyun_a": _fake_response(200, json_payload=empty),   # 200 but garbage
        "aliyun_b": _fake_response(200, json_payload=good),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    result = await call_chain(SLOT.CHAT, {"messages": [{"role": "user", "content": "hi"}]})
    assert result == good                                      # skipped the empty 200
    assert len(client.call_log) >= 2                           # did not stop at empty


@pytest.mark.asyncio
async def test_call_chain_total_timeout_caps_the_whole_provider_cascade(monkeypatch):
    _patch_keys(monkeypatch)
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 8, 9))
    # Needs enough live candidates that _MIN_ATTEMPT_TIMEOUT_SECONDS-floored
    # per-candidate consumption (each attempt still burns ≥0.05s of real wall
    # time even once its budgeted share is nearly gone) provably exhausts
    # total_timeout before the loop's pre-attempt deadline check runs again —
    # that check is what appends "chain: total_timeout" and breaks. With only
    # 2-3 candidates the floor leaves just enough slack that every attempt
    # individually times out instead (verified empirically); 4 candidates at
    # total_timeout=0.15 reliably trips the explicit branch while staying well
    # under the 0.8s wall-clock ceiling below.
    monkeypatch.setitem(
        llm_router.SLOT_MODELS,
        SLOT.CHAT,
        [("aliyun_a", "qwen3.7-flash"), ("aliyun_b", "qwen3.7-max-2026-05-17"),
         ("aliyun_c", "qwen3.7-max-2026-05-17"), ("zhipu", "glm-4.5-air")],
    )

    class _SlowClient(_ScriptedClient):
        async def post(self, url, headers=None, json=None, timeout=None):
            await asyncio.sleep(1)
            return await super().post(url, headers=headers, json=json, timeout=timeout)

    client = _SlowClient({})
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    started = time.monotonic()

    with pytest.raises(RuntimeError, match="total_timeout|timed out"):
        await call_chain(
            SLOT.CHAT,
            {"messages": [{"role": "user", "content": "hi"}]},
            timeout=0.2,
            total_timeout=0.15,
        )

    assert time.monotonic() - started < 0.8


# ════════════════════════════════════════════════════════════════════════
# 2026-07-30 evening — non-Aliyun floor: reachability, live TokenHub IDs,
# per-model payload constraints, and the caller-supplied content gate.
#
# What these lock down (all measured against prod keys on the real
# restaurant-T3 prompt, 5 diverse questions, production max_tokens=500):
#   * The floor was UNREACHABLE, not merely thin: aliyun_c/deepseek-v3.2
#     answers with confidence -0.95, which is a 200 as far as the router is
#     concerned, so the cascade stopped there and never reached _TEXT_TAIL.
#   * Three TokenHub IDs in the chain are dead (console: 已停止 / 余额 0) and
#     three live ones holding ~2.5M free tokens were never wired.
#   * TokenHub enforces per-model sampling rules the OpenAI-compatible schema
#     cannot express, and the router's normalizer was a passthrough.
# ════════════════════════════════════════════════════════════════════════

# Console scrape 2026-07-30 (状态=已停止, 免费额度余量=0). Calling these can only
# burn a request and then park the (account,model) in the 6h quota-skip cache.
_TOKENHUB_EXHAUSTED = {
    ("tencent", "qwen3.5-flash"),
    ("tencent", "glm-5.1"),
    ("tencent", "deepseek-v4-flash"),
}

# 2026-08-02: 5/5 on the real T3 prompt at production max_tokens=500 after
# applying each model family's documented thinking-off field.
_TOKENHUB_VERIFIED = {
    ("tencent", "deepseek-v4-pro-202606"),
    ("tencent", "glm-5.2"),
    ("tencent", "qwen3.5-plus"),
    ("tencent", "minimax-m3"),
}

_TOKENHUB_STALE_GENERAL_ROUTES = {
    ("tencent", "hy-mt2-pro"),
    ("tencent", "deepseek-v3.1-terminus"),
    ("tencent", "deepseek-v3.2"),
}

# 2026-08-09 重审: 上面 _TOKENHUB_VERIFIED 的 4 个 2026-08-02 测得的模型全部实测
# 401008 额度耗尽, 从 _SAFE_MODELS 移除(仍是 SLOT_MODELS 字面量里的 REVIEW 链结构,
# 那部分归 Task 4)。TokenHub 9 个条目当天实测只剩这 1 个还登记在册。
_TOKENHUB_REGISTERED_2026_08_09 = {
    ("tencent", "minimax-m2.7"),
}


def test_no_chain_calls_a_zero_balance_tokenhub_model():
    """Dead TokenHub IDs must not sit in any chain."""
    offenders = [
        (slot.value, account, model)
        for slot, chain in llm_router.SLOT_MODELS.items()
        for account, model in chain
        if (account, model) in _TOKENHUB_EXHAUSTED
    ]
    assert not offenders, f"zero-balance TokenHub models still in chains: {offenders}"


def test_no_chain_calls_a_stale_or_translation_only_tokenhub_model():
    offenders = [
        (slot.value, account, model)
        for slot, chain in llm_router.SLOT_MODELS.items()
        for account, model in chain
        if (account, model) in _TOKENHUB_STALE_GENERAL_ROUTES
    ]
    assert not offenders, f"stale TokenHub general routes remain: {offenders}"


def test_verified_tokenhub_models_are_registered_and_in_the_text_tail():
    """2026-08-09: only minimax-m2.7 survived the re-audit; the other three
    _TOKENHUB_VERIFIED pairs from 08-02 now 401008 quota-exhausted and were
    removed from _SAFE_MODELS. Task 4 rebuilt _TEXT_TAIL off the new registry,
    so this now also checks the survivor is actually wired into the floor
    (the test's name always promised this; it couldn't be checked until
    Task 4 replaced the chain literal).
    """
    for pair in _TOKENHUB_REGISTERED_2026_08_09:
        assert pair in llm_router._SAFE_MODELS, (
            f"{pair} not registered -> _refuse_reason blocks it"
        )
        assert pair in llm_router._TEXT_TAIL, f"{pair} registered but not wired into the floor"


def test_negative_confidence_pill_now_precedes_the_non_aliyun_floor_in_review():
    """Pre-2026-08-09 policy: REVIEW was hand-ordered so this pill
    (aliyun_c/deepseek-v3.2 -- correct plan, confidence pinned at -0.95/-1.0)
    sat AFTER the non-aliyun floor, because the router treated any HTTP 200 as
    success and stopped, so anything ordered after a poison pill was dead
    code. `_TOKENHUB_VERIFIED`'s four pairs from that era are gone from
    _SAFE_MODELS entirely (see test_verified_tokenhub_models_are_registered_
    and_in_the_text_tail); only minimax-m2.7 survives.

    2026-08-09 rewrite inverts the ordering on purpose: _build_chain sorts by
    expiry, and deepseek-v3.2 (a real, 08-13-dated grant) can never sort after
    the never-expiring floor (_TEXT_TAIL entries have expiry=None ->
    _FAR_FUTURE). The hand-curated safety-net ordering is structurally
    unachievable under expiry-first sorting now, so the safety net moved down
    a layer instead: `_t3_llm_parse` passes call_chain a content_validator
    that treats negative confidence as invalid output and keeps falling
    through, regardless of chain position -- ordering is no longer the
    enforcement point. This test pins the new, intentional fact.
    """
    chain = llm_router.SLOT_MODELS[SLOT.REVIEW]
    pill = chain.index(("aliyun_c", "deepseek-v3.2"))
    floor_positions = [chain.index(p) for p in llm_router._TEXT_TAIL if p in chain]
    assert floor_positions, "REVIEW cannot reach the non-aliyun floor at all"
    assert pill < min(floor_positions), (
        f"the pill at {pill} no longer sorts before the floor at "
        f"{sorted(floor_positions)} -- if this flips back, re-check that the "
        f"content_validator note above still matches call_chain's behavior"
    )


def test_tokenhub_kimi_is_forced_to_temperature_one():
    """TokenHub rejects any temperature but 1 for the kimi family (HTTP 400
    'invalid temperature: only 1 is allowed for this model')."""
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "tencent", "kimi-k2.6", {"model": "kimi-k2.6", "temperature": 0},
    )
    assert out["temperature"] == 1


def test_forced_temperature_is_tokenhub_scoped_not_model_name_scoped():
    """The constraint belongs to TokenHub, not to the model name -- the same kimi
    on DashScope accepts temperature=0 and must keep it."""
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "aliyun_c", "kimi-k2.6", {"model": "kimi-k2.6", "temperature": 0},
    )
    assert out["temperature"] == 0


def test_tokenhub_thinking_models_get_a_max_tokens_floor():
    """These ignore enable_thinking=false and spend the whole allowance on
    reasoning_content, returning EMPTY content at max_tokens=500 (measured:
    finish_reason='length', reasoning_tokens=500). Raising the ceiling is what
    makes them answer at all."""
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "tencent", "minimax-m2.7",
        {"model": "minimax-m2.7", "max_tokens": 500},
    )
    assert out["max_tokens"] >= 1600


def test_max_tokens_floor_never_lowers_a_callers_larger_budget():
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "tencent", "minimax-m2.7",
        {"model": "minimax-m2.7", "max_tokens": 4000},
    )
    assert out["max_tokens"] == 4000


def test_max_tokens_floor_does_not_touch_models_without_the_problem():
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "tencent", "hy-mt2-pro",
        {"model": "hy-mt2-pro", "max_tokens": 500},
    )
    assert out["max_tokens"] == 500


@pytest.mark.asyncio
async def test_call_chain_content_validator_rejects_a_200_and_falls_through(monkeypatch):
    """A caller-supplied content gate must make the cascade continue.

    This is the poison-pill fix: `_validate_output` cannot know that a
    syntactically fine JSON plan carries an out-of-contract confidence, so the
    caller that owns the contract supplies the predicate.
    """
    _patch_keys(monkeypatch)
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 8, 9))
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, _CHAT_SMOKE_CHAIN)
    pill = {"choices": [{"message": {"content": '{"intent":"X","confidence":-1.0}'}}]}
    good = {"choices": [{"message": {"content": '{"intent":"X","confidence":0.95}'}}]}
    client = _ScriptedClient({
        "aliyun_a": _fake_response(200, json_payload=pill),
        "aliyun_b": _fake_response(200, json_payload=good),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)

    def reject_negative_confidence(content):
        import json as _json
        try:
            conf = float(_json.loads(content).get("confidence"))
        except Exception:
            return "unparseable"
        return "negative_confidence" if conf < 0 else None

    result = await call_chain(
        SLOT.CHAT,
        {"messages": [{"role": "user", "content": "hi"}]},
        content_validator=reject_negative_confidence,
    )
    assert result == good
    assert len(client.call_log) >= 2


@pytest.mark.asyncio
async def test_call_chain_without_a_content_validator_keeps_the_first_200(monkeypatch):
    """No validator -> unchanged behavior (the gate is opt-in per caller)."""
    _patch_keys(monkeypatch)
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 8, 9))
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, _CHAT_SMOKE_CHAIN)
    pill = {"choices": [{"message": {"content": '{"intent":"X","confidence":-1.0}'}}]}
    client = _ScriptedClient({
        "aliyun_a": _fake_response(200, json_payload=pill),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    result = await call_chain(SLOT.CHAT, {"messages": [{"role": "user", "content": "hi"}]})
    assert result == pill


@pytest.mark.asyncio
async def test_a_raising_content_validator_does_not_kill_the_request(monkeypatch):
    """A buggy predicate must degrade to 'reject this candidate', never to a
    500 for the user."""
    _patch_keys(monkeypatch)
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 8, 9))
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, _CHAT_SMOKE_CHAIN)
    first = {"choices": [{"message": {"content": "text one"}}]}
    second = {"choices": [{"message": {"content": "text two"}}]}
    client = _ScriptedClient({
        "aliyun_a": _fake_response(200, json_payload=first),
        "aliyun_b": _fake_response(200, json_payload=second),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    calls = {"n": 0}

    def explodes_once(content):
        calls["n"] += 1
        if calls["n"] == 1:
            raise ValueError("boom")
        return None

    result = await call_chain(
        SLOT.CHAT,
        {"messages": [{"role": "user", "content": "hi"}]},
        content_validator=explodes_once,
    )
    assert result == second


# ════════════════════════════════════════════════════════════════════════
# ark (Volcengine 火山方舟) — provider wiring + the billing premise it rests on.
#
# Ark differs from every other provider in the registry on the ONE axis this
# registry exists to protect: it bills post-paid after the free grant unless
# 安心体验模式 is on. So the invariant tests below are not ceremony — an ark
# entry reachable from a chain while that mode is off is a live money leak the
# router cannot detect (it cannot tell a paid 200 from a free one).
# ════════════════════════════════════════════════════════════════════════

def test_ark_provider_config_reads_its_own_env(monkeypatch):
    monkeypatch.setenv("LLM_ARK_API_KEY", "ark_key_fake")
    monkeypatch.delenv("LLM_ARK_BASE_URL", raising=False)
    base, key = llm_router._provider_config("ark")
    assert key == "ark_key_fake"
    assert base == "https://ark.cn-beijing.volces.com/api/v3"


def test_ark_base_url_is_overridable(monkeypatch):
    monkeypatch.setenv("LLM_ARK_API_KEY", "ark_key_fake")
    monkeypatch.setenv("LLM_ARK_BASE_URL", "https://ark.example/api/v3")
    base, _ = llm_router._provider_config("ark")
    assert base == "https://ark.example/api/v3"


def test_ark_without_a_key_is_unreachable(monkeypatch):
    """No key → call_chain skips it. Keeps an un-provisioned environment (CI, a
    dev box) from turning ark entries into hard failures."""
    monkeypatch.delenv("LLM_ARK_API_KEY", raising=False)
    _base, key = llm_router._provider_config("ark")
    assert not key


def test_every_ark_chain_entry_is_registered():
    """Duplicates the global invariant on purpose, scoped to ark, so a failure
    names the provider whose billing premise is conditional.

    2026-08-09: ark's _SAFE_MODELS section is empty (_ARK_VIABLE is empty —
    both prior candidates SetLimitExceeded-paused), and the rebuilt
    _SLOT_POOLS/_TEXT_TAIL (Task 4) contain no ark entries at all, so this is
    vacuously true today. Stays as a standing regression guard: if ark regains
    a measured-viable model and someone wires it into a pool before it's
    registered, this catches it.
    """
    for slot, chain in llm_router.SLOT_MODELS.items():
        for account, model in chain:
            if account != "ark":
                continue
            assert (account, model) in llm_router._SAFE_MODELS, (
                f"{slot.value}: ark/{model} not registered — with 安心体验模式 off "
                f"an unregistered ark call bills silently"
            )


def test_ark_models_carry_a_dated_callable_id():
    """Ark rejects the console display name: `doubao-seed-1.6` → 404
    InvalidEndpointOrModel.NotFound, while `doubao-seed-1-6-251015` works. Every
    registered ark id must therefore end in a date-ish suffix, which is what
    GET /api/v3/models returns.

    Exception: `doubao-seed-evolving` is an undated id that genuinely exists —
    but the console shows it 未开通 for this account, so it must not be
    registered at all, and this test doubles as that check.
    """
    import re
    for account, model in llm_router._SAFE_MODELS:
        if account != "ark":
            continue
        assert re.search(r"-\d{6,8}$", model), (
            f"ark/{model} has no dated suffix — probably a console display name, "
            f"which Ark 404s. Take ids from GET /api/v3/models."
        )


# ── ark: measured-viable set, thinking translation, provider diversity ──────

# 2026-08-02 measured with the production key and thinking disabled: both models
# returned the correct contract for all five real restaurant T3 prompt shapes.
#
# 2026-08-09 重审: both now SetLimitExceeded-paused — the measured-viable set is
# empty. _SAFE_MODELS' ark section was cleared entirely (provider config kept;
# re-add per-model once the owner supplies a fresh measured-viable list). They
# are moved into _ARK_PAUSED below, alongside the previously-paused set — that
# is a TRUE positive, not a false one: both models are still literal entries in
# SLOT_MODELS[SLOT.REVIEW] / _TEXT_TAIL (Task 4 owns rewriting those chains off
# the new registry) AND both are now measured-paused, so
# test_ark_paused_or_contract_rejected_models_are_not_reachable below correctly
# reports them as reachable-while-paused. That test is therefore a third
# KNOWN RED alongside test_every_chain_entry_is_a_registered_safe_model /
# test_every_ark_chain_entry_is_registered, for the identical reason (Task 4
# has not yet removed ark from SLOT_MODELS/_TEXT_TAIL) — recording the
# measured fact here rather than omitting it to stay green.
_ARK_VIABLE: set = set()

# The former chain is now paused per model by SetLimitExceeded. Keeping any of these
# reachable would reintroduce a deterministic 429 before every healthy fallback.
_ARK_PAUSED = {
    "doubao-seed-2-0-mini-260428",
    "deepseek-v4-flash-260425",
    "doubao-seed-2-1-pro-260628",
    "glm-5-2-260617",
    "deepseek-v4-pro-260425",
    # 2026-08-09: joined the paused set (see _ARK_VIABLE comment above).
    "doubao-seed-2-1-turbo-260628",
    "doubao-seed-2-0-lite-260428",
}

# Callable, but its AOV plan returned intent=null/confidence=0.3 (4/5 overall).
_ARK_CONTRACT_REJECTED = {
    "doubao-seed-2-0-pro-260215",
}

# In GET /api/v3/models with no Shutdown status, yet 404 on call: the endpoint lists
# PLATFORM models, not this account's entitled set. Never treat it as entitlement.
_ARK_NOT_ENTITLED = {
    "glm-4-5-air-20250728",
    "qwen3-32b-20250429",
    "qwen3-14b-20250429",
    "qwen2-5-72b-20240919",
    "doubao-smart-router-250928",
    "doubao-seed-evolving",
}


def test_ark_registry_is_exactly_the_measured_viable_set():
    registered = {m for (a, m) in llm_router._SAFE_MODELS if a == "ark"}
    assert registered == _ARK_VIABLE, (
        "ark registry drifted from the measured set; re-measure before editing "
        f"(missing={_ARK_VIABLE - registered}, extra={registered - _ARK_VIABLE})"
    )


def test_ark_paused_or_contract_rejected_models_are_not_reachable():
    """2026-08-09: doubao-seed-2-1-turbo-260628 and doubao-seed-2-0-lite-260428
    (both SetLimitExceeded-paused, see _ARK_PAUSED) are no longer anywhere in
    SLOT_MODELS — Task 4's rebuilt _SLOT_POOLS/_TEXT_TAIL contain zero ark
    entries. Vacuously true today; stays as a standing regression guard for
    when ark is reintroduced.
    """
    for slot, chain in llm_router.SLOT_MODELS.items():
        for account, model in chain:
            assert not (
                account == "ark"
                and model in (_ARK_PAUSED | _ARK_CONTRACT_REJECTED)
            ), (
                f"{slot.value}: ark/{model} is paused or breaks the T3 contract"
            )


def test_ark_models_the_account_cannot_call_are_not_registered():
    for model in _ARK_NOT_ENTITLED:
        assert ("ark", model) not in llm_router._SAFE_MODELS, (
            f"ark/{model} returns 404 for this account — being listed by "
            f"GET /api/v3/models is not entitlement"
        )


def test_ark_gets_arks_own_thinking_switch_not_dashscopes():
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "ark", "doubao-seed-2-0-mini-260428",
        {"model": "doubao-seed-2-0-mini-260428"},
    )
    assert out["thinking"] == {"type": "disabled"}
    assert "enable_thinking" not in out, "enable_thinking is a DashScope param"


def test_ark_keeps_thinking_on_a_slot_that_wants_reasoning():
    """REASONING must not have its reasoning switched off."""
    out = llm_router._apply_slot_params(
        SLOT.REASONING, "ark", "deepseek-v4-pro-260425",
        {"model": "deepseek-v4-pro-260425"},
    )
    assert "thinking" not in out


def test_tokenhub_thinking_switch_is_model_family_specific():
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "tencent", "qwen3.5-plus", {"model": "qwen3.5-plus"},
    )
    assert "thinking" not in out
    assert out["enable_thinking"] is False


def test_non_aliyun_floor_interleaves_two_independent_providers():
    """The floor exists for "Aliyun is entirely gone". 2026-08-09 audit
    collapsed it from 7 candidates (tencent x6 interleaved with ark x2, per
    the pre-rewrite _TEXT_TAIL) down to 2 survivors that same day: 7 tencent
    SKUs hit 401008 FREE_QUOTA_EXHAUSTED and both ark SKUs hit
    SetLimitExceeded (see the new _TEXT_TAIL's comment). At this reduced size
    the invariant that must still hold is the same one that mattered before:
    it is not a single point of failure — the survivors must span 2 different
    providers, not one provider's two models.
    """
    floor = [
        (a, m) for (a, m) in llm_router._TEXT_TAIL
        if a not in llm_router._ALIYUN_ACCOUNTS
    ]
    assert len(floor) >= 2, floor
    assert len(set(a for a, _m in floor)) >= 2, (
        f"floor is single-provider ({floor}) — one outage empties it"
    )


def test_floor_is_exactly_the_2026_08_09_survivor_set():
    """Pins the measured production floor after the 08-09 TokenHub/Ark
    collapse (test above documents why it shrank from 7 to 2)."""
    floor = [
        (a, m) for (a, m) in llm_router._TEXT_TAIL
        if a not in llm_router._ALIYUN_ACCOUNTS
    ]
    assert floor == [
        ("tencent", "minimax-m2.7"),
        ("zhipu", "glm-4.5-air"),
    ]
