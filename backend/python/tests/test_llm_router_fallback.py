"""Tests for the free-quota DEEP fallback chains in common/llm_router.py.

Rebuilt 2026-05-31 (Steve directive: "每个 slot 至少 10 个 fallback, 把所有可用
免费模型都串进 fallback"). SLOT_MODELS[slot] is now an ordered LIST of
(account, model) entries — ≥10 free-tier models per text slot spanning
aliyun_c → aliyun_b → aliyun_a → zhipu. The router tries them in order; a 403
FreeTierOnly / 429 / 5xx on one entry falls to the NEXT. EVERY entry MUST be a
model that is FREE + "免费额度用完即停=已开启" on that account (console
2026-05-31, memory reference_dashscope_free_model_allowlist) — NO paid SKU is
ever in a chain, so paid billing is structurally impossible. Circuit breaker is
keyed per (account, model).

Mock httpx — no real API calls. The _ScriptedClient keys canned responses by
ACCOUNT, so "account X returns 403" applies to every model the chain tries on X.
"""
from __future__ import annotations

from typing import Dict, List, Tuple
from unittest.mock import MagicMock

import pytest

from common import llm_router
from common.llm_router import SLOT, call_chain


# Models that are FREE + 已开启 per account (DashScope console 2026-05-31).
# The router must ONLY ever use these — any other code would bill paid.
_FREE_BY_ACCOUNT: Dict[str, set] = {
    "aliyun_c": {
        "qwen-flash-2025-07-28", "qwen-turbo", "qwen-plus", "qwen3-max", "qwen-max",
        "qwen3-235b-a22b", "qwen3.5-397b-a17b", "qwen3.5-122b-a10b", "qwen3.6-flash",
        "qwen3-32b", "deepseek-v3", "deepseek-r1", "glm-5", "glm-4.6",
        "qwen3-max-2026-01-23", "qwen3-vl-plus-2025-12-19", "qwen-vl-max",
        "qwen3-vl-plus", "qwen3-vl-32b-instruct", "qwen3-vl-flash-2026-01-22",
        "qwen3-vl-30b-a3b-instruct",
    },
    "aliyun_b": {
        "qwen-flash", "qwen-plus-latest", "qwen3-max", "qwen3-235b-a22b",
        "qwen3.5-397b-a17b", "qwen3.5-122b-a10b", "deepseek-v3", "glm-4.6",
        "qwen3-vl-plus-2025-12-19", "qwen-vl-max", "qwen3-vl-plus", "qwen3-vl-32b-instruct",
    },
    "aliyun_a": {
        "qwen3.6-flash", "qwen3.6-plus", "qwen3.5-plus-2026-04-20", "qwen3.7-max-2026-05-17",
    },
    # tencent TokenHub 90-day free trial (2026-06-01) — OpenAI-compatible.
    # deepseek-v4-pro is FREE here (it is NOT on any aliyun free list, hence the
    # aliyun-scoped ban below). See common/llm_router.py top-of-file note.
    "tencent": {
        "deepseek-v4-pro", "deepseek-v4-flash", "glm-5.1", "glm-5",
        "kimi-k2.6", "qwen3.5-flash", "minimax-m2.7",
    },
    "zhipu": {"glm-4.5-air", "glm-4.6v"},
}

# NEVER allowed on ALIYUN accounts (paid or 未开启 → bills when free runs out).
# Scoped to aliyun_c/b/a because deepseek-v4-pro IS free on tencent's TokenHub
# trial — the "no deepseek-v4-pro" ban is an aliyun-specific free-list fact.
_FORBIDDEN_ON_ALIYUN = {
    "deepseek-v4-pro",          # not on any aliyun free list (free on tencent)
    "qwen3.7-max",              # C/A 未开启
    "qwen3.7-max-preview",      # C/A 未开启
    "qwen3.7-max-2026-05-20",   # C/A 未开启
}


@pytest.fixture(autouse=True)
def _reset_cb():
    """Reset circuit-breaker + quota-skip state between tests so they don't bleed."""
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()
    yield
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()


def _fake_response(status_code: int, body: str = "", json_payload: Dict | None = None):
    resp = MagicMock()
    resp.status_code = status_code
    resp.text = body
    resp.json = MagicMock(return_value=json_payload or {})
    return resp


class _ScriptedClient:
    """httpx-AsyncClient stand-in returning a canned response per ACCOUNT.

    aliyun_c/b/a all hit DashScope; differentiated by api_key (key_c/b/a).
    deepseek-* models on the aliyun_a key historically routed to a separate
    'aliyun_a_deepseek' bucket — the rebuilt chains no longer use deepseek-v4-pro
    so all aliyun_a models map to 'aliyun_a' here.
    """

    def __init__(self, route_responses: Dict[str, MagicMock]):
        self.route_responses = route_responses
        self.call_log: List[Tuple[str, str]] = []  # (account, model)

    async def post(self, url: str, headers=None, json=None, timeout=None):
        model = (json or {}).get("model", "?")
        account = None
        if "dashscope.aliyuncs.com" in url:
            api_key = (headers or {}).get("Authorization", "")
            if "key_c" in api_key:
                account = "aliyun_c"
            elif "key_b" in api_key:
                account = "aliyun_b"
            elif "key_a" in api_key:
                account = "aliyun_a"
        elif "open.bigmodel.cn" in url:
            account = "zhipu"
        elif "tokenhub.tencentmaas.com" in url:
            account = "tencent"
        self.call_log.append((account or "unknown", model))
        if account in self.route_responses:
            return self.route_responses[account]
        return _fake_response(500, "no canned response")


def _patch_provider_keys(monkeypatch):
    monkeypatch.setenv("LLM_ALIYUN_C_API_KEY", "key_c_fake")
    monkeypatch.setenv("LLM_ALIYUN_A_API_KEY", "key_a_fake")
    monkeypatch.setenv("LLM_ALIYUN_B_API_KEY", "key_b_fake")
    monkeypatch.setenv("LLM_ZHIPU_API_KEY", "key_zhipu_fake")


def _assert_all_calls_are_free(call_log: List[Tuple[str, str]]):
    """Every (account, model) the router tried MUST be a free+已开启 code."""
    for account, model in call_log:
        free_set = _FREE_BY_ACCOUNT.get(account, set())
        assert model in free_set, (
            f"router called {account}/{model} which is NOT a free-enabled code "
            f"→ would bill paid"
        )
        if account in ("aliyun_c", "aliyun_b", "aliyun_a"):
            assert model not in _FORBIDDEN_ON_ALIYUN, (
                f"router called aliyun-forbidden {account}/{model}"
            )


# ════════════════════════════════════════════════════════════════════════
# Fallback behaviour
# ════════════════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_first_model_success_returns_immediately(monkeypatch):
    """Chain head (aliyun_c) 200 → returns at the first model, no fallthrough."""
    _patch_provider_keys(monkeypatch)
    success = {"choices": [{"message": {"content": "ok"}}]}
    client = _ScriptedClient({"aliyun_c": _fake_response(200, json_payload=success)})
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)

    result = await call_chain(SLOT.CHAT, {"messages": [{"role": "user", "content": "hi"}]})

    assert result == success
    assert len(client.call_log) == 1            # only the head tried
    head_account, head_model = client.call_log[0]
    assert (head_account, head_model) == SLOT_HEADS[SLOT.CHAT]
    _assert_all_calls_are_free(client.call_log)


@pytest.mark.asyncio
async def test_account_c_fully_exhausted_falls_to_b(monkeypatch):
    """All aliyun_c models 403 → chain keeps trying (c models) then lands on the
    first aliyun_b model. Deep chain: many c attempts before b."""
    _patch_provider_keys(monkeypatch)
    success = {"choices": [{"message": {"content": "ok from b"}}]}
    client = _ScriptedClient({
        "aliyun_c": _fake_response(403, body='AllocationQuota.FreeTierOnly'),
        "aliyun_b": _fake_response(200, json_payload=success),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)

    result = await call_chain(SLOT.INSIGHTS, {"messages": []})

    assert result == success
    accounts = [a for a, _ in client.call_log]
    assert "aliyun_c" in accounts and accounts[-1] == "aliyun_b"   # fell through c → b
    assert accounts.index("aliyun_b") == len(accounts) - 1          # b is where it succeeded
    _assert_all_calls_are_free(client.call_log)


@pytest.mark.asyncio
async def test_all_aliyun_exhausted_falls_to_zhipu(monkeypatch):
    """Every aliyun account 403/429 → lands on zhipu (independent GLM pool)."""
    _patch_provider_keys(monkeypatch)
    success = {"choices": [{"message": {"content": "ok zhipu"}}]}
    client = _ScriptedClient({
        "aliyun_c": _fake_response(403, body='AllocationQuota.FreeTierOnly'),
        "aliyun_b": _fake_response(403, body='AllocationQuota.FreeTierOnly'),
        "aliyun_a": _fake_response(429),
        "zhipu": _fake_response(200, json_payload=success),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)

    result = await call_chain(SLOT.REASONING, {"messages": []})

    assert result == success
    assert client.call_log[-1][0] == "zhipu"
    _assert_all_calls_are_free(client.call_log)


@pytest.mark.asyncio
async def test_429_triggers_fallback(monkeypatch):
    _patch_provider_keys(monkeypatch)
    success = {"choices": [{"message": {"content": "ok"}}]}
    client = _ScriptedClient({
        "aliyun_c": _fake_response(429, body="rate limit"),
        "aliyun_b": _fake_response(200, json_payload=success),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    result = await call_chain(SLOT.MAPPER, {"messages": []})
    assert result == success
    assert client.call_log[-1][0] == "aliyun_b"
    _assert_all_calls_are_free(client.call_log)


@pytest.mark.asyncio
async def test_5xx_also_falls_through(monkeypatch):
    """Non-quota 5xx is transient → still walks to the next entry."""
    _patch_provider_keys(monkeypatch)
    success = {"choices": [{"message": {"content": "ok"}}]}
    client = _ScriptedClient({
        "aliyun_c": _fake_response(503, body="service unavailable"),
        "aliyun_b": _fake_response(200, json_payload=success),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    result = await call_chain(SLOT.CHART, {"messages": []})
    assert result == success
    assert client.call_log[-1][0] == "aliyun_b"


@pytest.mark.asyncio
async def test_all_exhausted_raises(monkeypatch):
    """Every account 403/429 → RuntimeError after trying the whole deep chain."""
    _patch_provider_keys(monkeypatch)
    monkeypatch.setenv("LLM_TENCENT_API_KEY", "key_tencent_fake")
    client = _ScriptedClient({
        "aliyun_c": _fake_response(403, body='AllocationQuota.FreeTierOnly'),
        "aliyun_b": _fake_response(403, body='AllocationQuota.FreeTierOnly'),
        "aliyun_a": _fake_response(403, body='AllocationQuota.FreeTierOnly'),
        "tencent": _fake_response(429),
        "zhipu": _fake_response(429),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)

    with pytest.raises(RuntimeError, match="All providers exhausted"):
        await call_chain(SLOT.CHAT, {"messages": []})

    accounts = {a for a, _ in client.call_log}
    # tried every account in the chain (tencent sits between aliyun + zhipu)
    assert accounts == {"aliyun_c", "aliyun_b", "aliyun_a", "tencent", "zhipu"}
    _assert_all_calls_are_free(client.call_log)


# ════════════════════════════════════════════════════════════════════════
# SLOT_MODELS structure (deep free-only chains)
# ════════════════════════════════════════════════════════════════════════

# Expected chain HEAD (first entry) per slot — the quality-first preferred model
# (Steve 2026-06-01: order by quality, all entries free so cost is equal). tencent
# deepseek-v4-pro heads the two quality slots (REASONING/INSIGHTS); high-freq
# CHAT/MAPPER keep a proven aliyun fast head for latency.
SLOT_HEADS = {
    SLOT.CHAT: ("aliyun_c", "qwen-flash-2025-07-28"),
    SLOT.INSIGHTS: ("tencent", "deepseek-v4-pro"),
    SLOT.CHART: ("aliyun_c", "glm-5"),
    SLOT.MAPPER: ("aliyun_c", "qwen-turbo"),
    SLOT.REASONING: ("tencent", "deepseek-v4-pro"),
    SLOT.VL: ("aliyun_c", "qwen3-vl-plus-2025-12-19"),
    SLOT.REVIEW: ("aliyun_c", "qwen3-max-2026-01-23"),
}


def test_every_text_slot_has_at_least_10_fallbacks():
    sm = llm_router.SLOT_MODELS
    for slot, chain in sm.items():
        assert isinstance(chain, list), f"{slot.value} chain must be a list"
        if slot == SLOT.VL:
            assert len(chain) >= 5, f"VL chain too short: {len(chain)}"
        else:
            assert len(chain) >= 10, f"{slot.value} chain has only {len(chain)} fallbacks (<10)"


def test_every_chain_entry_is_free_and_enabled():
    """HARD GUARANTEE: every (account, model) in every slot chain is free+已开启
    → paid billing is structurally impossible."""
    sm = llm_router.SLOT_MODELS
    for slot, chain in sm.items():
        for account, model in chain:
            assert account in _FREE_BY_ACCOUNT, f"{slot.value}: unknown account {account}"
            assert model in _FREE_BY_ACCOUNT[account], (
                f"{slot.value}: {account}/{model} is NOT a free-enabled code → would bill paid"
            )
            if account in ("aliyun_c", "aliyun_b", "aliyun_a"):
                assert model not in _FORBIDDEN_ON_ALIYUN, (
                    f"{slot.value}: aliyun-forbidden code {account}/{model}"
                )


def test_chain_heads_are_slot_tuned():
    sm = llm_router.SLOT_MODELS
    for slot, expected_head in SLOT_HEADS.items():
        assert sm[slot][0] == expected_head, (
            f"{slot.value} head is {sm[slot][0]}, expected {expected_head}"
        )


def test_no_deepseek_v4_pro_on_aliyun():
    """deepseek-v4-pro is not on any ALIYUN free list — must never appear on an
    aliyun account. It IS free on tencent's TokenHub trial, so tencent/
    deepseek-v4-pro is allowed (and used in the REASONING head)."""
    sm = llm_router.SLOT_MODELS
    for slot, chain in sm.items():
        for account, model in chain:
            if account in ("aliyun_c", "aliyun_b", "aliyun_a"):
                assert model != "deepseek-v4-pro", (
                    f"{slot.value} uses aliyun-billed deepseek-v4-pro on {account}"
                )


def test_vl_chain_is_vision_only():
    """VL slot must only contain vision-capable models (qwen*-vl* / glm-4.6v)."""
    chain = llm_router.SLOT_MODELS[SLOT.VL]
    for account, model in chain:
        assert ("vl" in model) or (model == "glm-4.6v"), (
            f"VL chain has non-vision model {account}/{model}"
        )


def test_chains_deduped():
    """No duplicate (account, model) within a slot chain."""
    sm = llm_router.SLOT_MODELS
    for slot, chain in sm.items():
        assert len(chain) == len(set(chain)), f"{slot.value} chain has duplicate entries"


# ════════════════════════════════════════════════════════════════════════
# _is_quota_exhausted detection matrix (unchanged helper)
# ════════════════════════════════════════════════════════════════════════

def test_quota_exhausted_detection():
    is_q = llm_router._is_quota_exhausted
    assert is_q(403, '{"code":"AllocationQuota.FreeTierOnly"}')
    assert is_q(403, "AllocationQuota")
    assert not is_q(403, "permission denied")
    assert is_q(429, "")
    assert is_q(429, "anything")
    assert not is_q(500, "FreeTierOnly")
    assert not is_q(401, "AllocationQuota")
    assert not is_q(200, "FreeTierOnly")


def test_402_insufficient_balance_is_quota_exhausted():
    is_q = llm_router._is_quota_exhausted
    assert is_q(402, '{"error":{"message":"Insufficient Balance","type":"insufficient_quota"}}')
    assert is_q(402, "Insufficient Balance")


def test_402_tencent_free_quota_exhausted_is_quota_exhausted():
    """Tencent TokenHub returns 402 with body 'endpoint is inactive:
    FREE_QUOTA_EXHAUSTED' once the 90-day free trial is consumed (probe
    2026-06-30). Must be classified as quota so the router marks the quota-skip
    cache + logs WARNING instead of re-probing every request as a generic ERROR."""
    is_q = llm_router._is_quota_exhausted
    assert is_q(402, '{"error":{"message":"endpoint is inactive: FREE_QUOTA_EXHAUSTED","code":"401008"}}')
    assert is_q(402, "FREE_QUOTA_EXHAUSTED")


def test_402_other_cases_not_quota_exhausted():
    is_q = llm_router._is_quota_exhausted
    assert not is_q(402, "Payment Required")
    assert not is_q(402, "")
    assert not is_q(402, '{"error":"some other 402 reason"}')
    assert not is_q(200, "Insufficient Balance")
    assert not is_q(500, "Insufficient Balance")
    assert not is_q(401, "Insufficient Balance")
