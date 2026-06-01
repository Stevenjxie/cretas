"""Unit tests for the shared distillation-capture helper.

Covers:
- input_hash idempotence (same inputs → same hash; differs by input_text).
- fire-and-forget contract: NEVER raises, even when the pool's acquire blows up.
- pool=None / disabled-env / empty-input short-circuit cleanly (no write).
- a happy-path INSERT carries the expected positional args.

All DB access is mocked — NO real connection.
"""
from __future__ import annotations

from smartbi.services.distillation_capture import (
    compute_input_hash,
    persist_distillation_sample,
)


# ---- fakes ----

class _RecordingConn:
    def __init__(self, sink):
        self._sink = sink

    async def execute(self, sql, *args):
        self._sink.append((sql, args))
        return "INSERT 0 1"

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _RecordingPool:
    def __init__(self, sink):
        self._sink = sink

    def acquire(self):
        return _RecordingConn(self._sink)


class _ExplodingPool:
    """acquire() raises — simulates a broken / exhausted pool."""

    def acquire(self):
        raise RuntimeError("pool acquire boom")


# ---- input_hash idempotence ----

def test_input_hash_is_deterministic():
    a = compute_input_hash("营业额是多少")
    b = compute_input_hash("营业额是多少")
    assert a == b
    assert len(a) == 64  # sha256 hex


def test_input_hash_differs_by_input_text():
    assert compute_input_hash("问题甲") != compute_input_hash("问题乙")


def test_input_hash_handles_none_safely():
    # never raises; empty string hash is stable
    assert compute_input_hash("") == compute_input_hash(None)  # type: ignore[arg-type]


# ---- fire-and-forget: never raises ----

async def test_never_raises_when_pool_acquire_errors():
    # The whole point: a broken pool must not propagate out of capture.
    await persist_distillation_sample(
        _ExplodingPool(),
        source="chat_qa",
        task_type="qa",
        input_text="问题",
        teacher_output="答案",
    )
    # reaching here (no exception) is the assertion


async def test_none_pool_is_quiet_noop():
    # No pool → quiet return, no error.
    await persist_distillation_sample(
        None,
        source="chat_qa",
        task_type="qa",
        input_text="问题",
        teacher_output="答案",
    )


async def test_empty_input_or_output_skips_write():
    sink: list = []
    pool = _RecordingPool(sink)
    await persist_distillation_sample(
        pool, source="chat_qa", task_type="qa",
        input_text="", teacher_output="答案",
    )
    await persist_distillation_sample(
        pool, source="chat_qa", task_type="qa",
        input_text="问题", teacher_output="",
    )
    assert sink == [], "NOT NULL columns guarded — no INSERT attempted"


async def test_disabled_by_env_skips_write(monkeypatch):
    sink: list = []
    monkeypatch.setenv("SMARTBI_DISTILL_CAPTURE", "0")
    await persist_distillation_sample(
        _RecordingPool(sink),
        source="chat_qa", task_type="qa",
        input_text="问题", teacher_output="答案",
    )
    assert sink == [], "capture disabled by env → no write"


# ---- happy path INSERT ----

async def test_happy_path_inserts_expected_args(monkeypatch):
    sink: list = []
    monkeypatch.setenv("SMARTBI_DISTILL_CAPTURE", "1")
    await persist_distillation_sample(
        _RecordingPool(sink),
        source="agent_insight",
        task_type="insight",
        input_text="用户问：本月营收如何",
        teacher_output="本月营收稳健，建议...",
        business_type="restaurant",
        factory_id="RES_TEST_001",
        system_prompt="你是分析师",
        teacher_model="qwen3-max",
        template_codes=["top_n_by_dim", "monthly_trend"],
        metadata={"slot": "insights"},
    )
    assert len(sink) == 1
    sql, args = sink[0]
    assert "smart_bi_distillation_samples" in sql
    assert "ON CONFLICT (input_hash)" in sql
    # positional args: source, business_type, factory_id, task_type,
    #   template_codes, system_prompt, input_text, teacher_model,
    #   teacher_output, input_hash, quality, metadata
    assert args[0] == "agent_insight"
    assert args[1] == "restaurant"
    assert args[2] == "RES_TEST_001"
    assert args[3] == "insight"
    assert args[4] == "top_n_by_dim,monthly_trend"
    assert args[6] == "用户问：本月营收如何"
    assert args[7] == "qwen3-max"
    assert args[9] == compute_input_hash("用户问：本月营收如何")
    assert len(args[9]) == 64
    assert args[10] is None  # quality not provided
    assert '"slot"' in args[11]  # metadata json-encoded


async def test_business_type_none_normalized_to_unknown():
    sink: list = []
    await persist_distillation_sample(
        _RecordingPool(sink),
        source="chat_qa", task_type="qa",
        input_text="问题", teacher_output="答案",
        business_type=None,  # type: ignore[arg-type]
    )
    assert len(sink) == 1
    _, args = sink[0]
    assert args[1] == "unknown"


async def test_no_template_codes_and_no_metadata_pass_none():
    sink: list = []
    await persist_distillation_sample(
        _RecordingPool(sink),
        source="chat_qa", task_type="qa",
        input_text="问题", teacher_output="答案",
    )
    assert len(sink) == 1
    _, args = sink[0]
    assert args[4] is None   # template_codes
    assert args[11] is None  # metadata
