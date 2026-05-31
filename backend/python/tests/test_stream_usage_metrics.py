"""流式 token 记账: record_stream_usage 入队真实 usage (修 ai_query_chat 记成 0 的盲区)。"""
from __future__ import annotations

import common.llm_metrics as m


def _drain():
    while not m._usage_queue.empty():
        m._usage_queue.get_nowait()


def test_record_stream_usage_enqueues(monkeypatch):
    _drain()
    monkeypatch.setattr(m, "_enabled", True)
    m._llm_caller.set("ai_query_chat")
    m._llm_factory.set("F001")
    m.record_stream_usage("aliyun_b", "qwen-max", 100, 200, 300)
    rec = m._usage_queue.get_nowait()
    assert rec["caller"] == "ai_query_chat"
    assert rec["factory_id"] == "F001"
    assert rec["provider"] == "aliyun_b" and rec["model"] == "qwen-max"
    assert rec["input_tokens"] == 100
    assert rec["output_tokens"] == 200
    assert rec["total_tokens"] == 300
    assert rec["status_code"] == 200


def test_record_stream_usage_disabled_noop(monkeypatch):
    _drain()
    monkeypatch.setattr(m, "_enabled", False)
    m.record_stream_usage("p", "mdl", 1, 2, 3)
    assert m._usage_queue.empty()


def test_record_stream_usage_handles_none(monkeypatch):
    _drain()
    monkeypatch.setattr(m, "_enabled", True)
    m._llm_caller.set("x")
    m.record_stream_usage("p", "mdl", None, None, None)
    rec = m._usage_queue.get_nowait()
    assert rec["input_tokens"] == 0 and rec["total_tokens"] == 0


if __name__ == "__main__":
    import pytest
    raise SystemExit(pytest.main([__file__, "-v"]))
