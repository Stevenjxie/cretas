"""P0 出境脱敏器单测 — 核心保证: 中文专名不出境 + 数字不动 + 还原无误 + retry 占位稳定。"""
from __future__ import annotations

import json

import pandas as pd
import pytest

from common.llm_redactor import (
    PlaceholderAllocator,
    RedactionScope,
    StreamRestorer,
    current_redaction_scope,
    extract_sensitive_values_from_df,
    extract_sensitive_values_from_fields,
    redact_dict,
    redact_payload,
    redact_payload_for_egress,
    redaction_scope,
    register_df_for_egress,
    register_df_in_scope,
    register_values_for_egress,
    restore_in_scope,
    restore_obj,
    restore_text,
    sensitive_type_for_column,
    stable_factory_alias,
)


# ── 列识别 ────────────────────────────────────────────────────────────────
def test_sensitive_column_detection_zh_en():
    assert sensitive_type_for_column("customerName") == "客户"
    assert sensitive_type_for_column("门店名称") == "门店"
    assert sensitive_type_for_column("供应商") == "供应商"
    assert sensitive_type_for_column("菜品名称") == "菜品"
    assert sensitive_type_for_column("联系电话") == "电话"
    assert sensitive_type_for_column("送货地址") == "地址"


def test_category_columns_not_redacted():
    # 类别/枚举列即使含敏感词也不脱敏 (否则把 VIP/牛肉/卤味 这类通用词占位掉)
    assert sensitive_type_for_column("客户类型") is None
    assert sensitive_type_for_column("菜品分类") is None
    assert sensitive_type_for_column("category") is None
    assert sensitive_type_for_column("品类") is None
    # 纯指标/数值列不脱敏
    assert sensitive_type_for_column("营业额") is None
    assert sensitive_type_for_column("销量") is None
    assert sensitive_type_for_column("科目名称") is None  # 财务科目名(营业收入/成本) 必须保留


# ── df 抽取 ──────────────────────────────────────────────────────────────
def test_extract_sensitive_values_from_df():
    df = pd.DataFrame({
        "门店名称": ["青花椒大融城店", "青花椒春熙店", "青花椒大融城店"],
        "营业额": [12000, 9000, 12000],
        "品类": ["火锅", "火锅", "串串"],
    })
    vbt = extract_sensitive_values_from_df(df)
    assert "门店" in vbt
    assert set(vbt["门店"]) == {"青花椒大融城店", "青花椒春熙店"}  # distinct
    # 数值列 / 类别列不抽
    assert "营业额" not in str(vbt)
    assert "火锅" not in str(vbt.get("门店", []))


# ── 中文专名脱敏 (核心 P0) ────────────────────────────────────────────────
def test_chinese_names_redacted_via_known_values():
    scope = RedactionScope()
    scope.register_values({"门店": ["青花椒大融城店", "青花椒春熙店"], "客户": ["张权"]})
    text = "青花椒大融城店本月营收 ¥12,000 最高, 张权负责; 青花椒春熙店次之"
    payload = {"messages": [{"role": "user", "content": text}]}
    out, red = redact_payload(payload, allocator=scope.allocator,
                              known_values=scope.known_values)
    sent = out["messages"][0]["content"]
    # 真名 0 残留
    assert "青花椒大融城店" not in sent
    assert "青花椒春熙店" not in sent
    assert "张权" not in sent
    # 有占位
    assert "门店A" in sent and "门店B" in sent and "客户A" in sent
    # 数字/金额不动
    assert "12,000" in sent


def test_substring_longest_first_no_collision():
    # '青花椒大融城店' 不能被 '青花椒' 误伤; 用 sorted by len desc 保证
    scope = RedactionScope()
    scope.register_values({"门店": ["青花椒", "青花椒大融城店"]})
    text = "青花椒大融城店 vs 青花椒(品牌)"
    out, _ = redact_payload({"messages": [{"role": "user", "content": text}]},
                            allocator=scope.allocator, known_values=scope.known_values)
    sent = out["messages"][0]["content"]
    assert "青花椒大融城店" not in sent and "青花椒(品牌)" not in sent
    # 还原回去 (长占位优先) — 仅验证 restore_text 调用不抛 (sister 原意未加断言, 保持现状)
    restore_text(sent, {v: k for k, v in {**scope.known_values}.items()})


def test_pii_regex_phone_email_id():
    alloc = PlaceholderAllocator()
    from common.llm_redactor import redact_text
    r = redact_text("联系 13812345678 邮箱 a@b.com 身份证 11010119900307391X",
                    allocator=alloc)
    assert "13812345678" not in r.text
    assert "a@b.com" not in r.text
    assert "11010119900307391X" not in r.text
    assert r.redacted_count >= 3


def test_numbers_dates_untouched():
    alloc = PlaceholderAllocator()
    from common.llm_redactor import redact_text
    r = redact_text("营收 ¥1,234,567.89 同比 +12.3% 日期 2025-12-01", allocator=alloc)
    assert r.text == "营收 ¥1,234,567.89 同比 +12.3% 日期 2025-12-01"  # 0 改动


def test_redact_dict_structural():
    rows = [{"customerName": "张权", "amount": 5000, "category": "牛肉"}]
    alloc = PlaceholderAllocator()
    out, red = redact_dict(rows, allocator=alloc)
    assert out[0]["customerName"].startswith("客户")
    assert out[0]["amount"] == 5000          # 数字不动
    assert out[0]["category"] == "牛肉"        # 非敏感字段不动
    assert "张权" not in json.dumps(out, ensure_ascii=False)


def test_factory_alias_stable():
    a1 = stable_factory_alias("RES_3101_009")
    a2 = stable_factory_alias("RES_3101_009")
    assert a1 == a2 and a1.startswith("FACTORY_")


# ── 还原 round-trip ───────────────────────────────────────────────────────
def test_restore_roundtrip_obj():
    scope = RedactionScope()
    scope.register_values({"门店": ["青花椒大融城店"]})
    # 模拟 LLM 用占位输出
    insights = [{"text": "门店A 表现最好", "recommendation": "给 门店A 加推广"}]
    restored = restore_obj(insights, scope.placeholder_map)
    assert restored[0]["text"] == "青花椒大融城店 表现最好"
    assert "门店A" not in restored[0]["recommendation"]


# ── retry 占位稳定 (A3) ──────────────────────────────────────────────────
def test_placeholder_stable_across_retries():
    # 同一 scope/allocator 复用 → 多次脱敏占位序号一致 (provider fallback retry)
    scope = RedactionScope()
    scope.register_values({"门店": ["青花椒大融城店", "青花椒春熙店"]})
    payload = {"messages": [{"role": "user", "content": "青花椒大融城店 青花椒春熙店"}]}
    out1, _ = redact_payload(payload, allocator=scope.allocator, known_values=scope.known_values)
    out2, _ = redact_payload(payload, allocator=scope.allocator, known_values=scope.known_values)
    assert out1["messages"][0]["content"] == out2["messages"][0]["content"]


# ── scope 集成 (choke point 路径) ─────────────────────────────────────────
def test_redact_payload_for_egress_with_scope():
    df = pd.DataFrame({"门店名称": ["青花椒大融城店"], "营业额": [12000]})
    with redaction_scope():
        register_df_in_scope(df)
        payload = {"messages": [{"role": "user", "content": "青花椒大融城店 营收 12000"}]}
        out, meta = redact_payload_for_egress(payload)
        sent = out["messages"][0]["content"]
        assert "青花椒大融城店" not in sent
        assert "12000" in sent
        assert meta.sanitized is True
        assert "门店" in meta.redacted_fields
        # 还原
        assert restore_in_scope([{"text": sent}])[0]["text"].count("青花椒大融城店") == 1


def test_no_scope_pii_floor():
    # 无 scope (Java 意图/chat) 仍跑 PII floor, 不还原
    payload = {"messages": [{"role": "user", "content": "我的号码 13812345678"}]}
    out, meta = redact_payload_for_egress(payload)
    assert "13812345678" not in out["messages"][0]["content"]


# ── StreamRestorer (流式占位切 chunk) ─────────────────────────────────────
def test_stream_restorer_split_placeholder():
    pmap = {"门店A": "青花椒大融城店"}
    sr = StreamRestorer(pmap, tail=8)
    # 占位被切成两段 "门店" + "A"
    emitted = ""
    for ch in ["本月 门", "店", "A 营收最高, 门店A 客流稳"]:
        emitted += sr.push(ch)
    emitted += sr.flush()
    assert "门店A" not in emitted
    assert emitted.count("青花椒大融城店") == 2


# ── 二级调用方 helpers (field_detector / llm_mapper / data_cleaner / cross_sheet) ──
def test_extract_sensitive_values_from_fields():
    fields = [
        {"fieldName": "门店名称", "sampleValues": ["青花椒大融城店", "青花椒春熙店"]},
        {"fieldName": "营业额", "sampleValues": [12000, 9000]},   # 数值字段不抽
        {"fieldName": "客户类型", "sampleValues": ["VIP", "普通"]},  # 类别列不抽
    ]
    v = extract_sensitive_values_from_fields(fields)
    assert v.get("门店") == ["青花椒大融城店", "青花椒春熙店"]
    assert "客户" not in v and "营业额" not in str(v)


def test_register_df_for_egress_creates_scope():
    # ensure_redaction_scope: 无 scope 时自建 (no-reset), 给二级调用方用
    with redaction_scope():  # 隔离, 测完自动 reset
        register_df_for_egress(pd.DataFrame({"客户名称": ["张权"], "额": [1]}))
        scope = current_redaction_scope()
        assert scope is not None and "张权" in scope.known_values


def test_register_values_for_egress_then_redact():
    with redaction_scope():
        register_values_for_egress({"分部": ["华东大区", "青花椒大融城店"]})
        payload = {"messages": [{"role": "user", "content": "华东大区 利润率 18%, 青花椒大融城店 12%"}]}
        out, meta = redact_payload_for_egress(payload)
        sent = out["messages"][0]["content"]
        assert "华东大区" not in sent and "青花椒大融城店" not in sent
        assert "18%" in sent and "12%" in sent  # 数字不动
        # 输出还原
        assert restore_in_scope([{"text": sent}])[0]["text"].count("华东大区") == 1


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
