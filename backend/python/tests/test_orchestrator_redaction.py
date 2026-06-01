"""P0 数据主权: agent orchestrator 出境脱敏 wiring 测试.

orchestrator 是 executive dashboard /insights/custom 的唯一入口, 占 prod LLM
出境的大头 (~79%). 它把门店/商品/折扣真名塞进 prompt 发给公有 LLM (DashScope)。
本测试验证 orchestrator 现在的流程:
    register 真名 → choke point (redact_payload_for_egress) 出境占位 → 输出还原
真名不出境, 数字/分析 0 损失, 用户仍看真名。

回归背景: PR #335 只 wrap 了 insights generator, 漏了 orchestrator —— prod egress
审计显示 110/110 调用 sanitized=false (真店名直发 DashScope)。本 fix 补上。
"""
from smartbi.agent.orchestrator import _collect_sensitive_names
from common.llm_redactor import (
    redaction_scope,
    register_values_for_egress,
    restore_in_scope,
    redact_payload_for_egress,
)


def test_collect_sensitive_names_extracts_store_product_discount():
    data = {
        "finance": {"top_stores": [
            {"store_name": "青花椒新世界新丸中心店", "revenue": 3435300},
            {"store_name": "鲜行者打浦桥日月光店", "revenue": 7908100},
        ]},
        "top_products": [
            {"product_name": "蟹小青龙", "revenue": 120000},
            {"name": "松叶蟹", "revenue": 90000},
        ],
        "discount_breakdown": [
            {"discount_name": "抖音松叶蟹268代851", "total_amount": 35000},
        ],
    }
    names = _collect_sensitive_names(data)
    assert "青花椒新世界新丸中心店" in names["门店"]
    assert "鲜行者打浦桥日月光店" in names["门店"]
    assert "蟹小青龙" in names["商品"]
    assert "松叶蟹" in names["商品"]
    assert "抖音松叶蟹268代851" in names["活动"]


def test_collect_sensitive_names_empty_data_is_safe():
    assert _collect_sensitive_names({}) == {}
    assert _collect_sensitive_names({"finance": {}, "top_products": [], "discount_breakdown": []}) == {}


def test_orchestrator_egress_redacts_then_restores():
    """端到端镜像 orchestrator 现在的非流式流程: 真名出境占位, 输出还原。"""
    data = {
        "finance": {"top_stores": [{"store_name": "青花椒新世界新丸中心店", "revenue": 3435300}]},
        "top_products": [{"product_name": "蟹小青龙"}],
        "discount_breakdown": [{"discount_name": "抖音畅享套餐A"}],
    }
    user_prompt = (
        "## Top 门店\n1. 青花椒新世界新丸中心店：¥3,435,300\n"
        "## Top 商品\n1. 蟹小青龙\n## 折扣\n- 抖音畅享套餐A：¥35,000"
    )
    payload = {"messages": [
        {"role": "system", "content": "你是餐饮数据分析师"},
        {"role": "user", "content": user_prompt},
    ]}

    with redaction_scope():
        register_values_for_egress(_collect_sensitive_names(data))

        # choke point — llm_router.call_chain 出境前对 payload 调用这个
        redacted_payload, meta = redact_payload_for_egress(payload)
        egress_text = redacted_payload["messages"][1]["content"]

        # 真名不出境
        assert "青花椒新世界新丸中心店" not in egress_text
        assert "蟹小青龙" not in egress_text
        assert "抖音畅享套餐A" not in egress_text
        assert meta.sanitized is True
        assert meta.redacted_count >= 3
        assert "门店A" in egress_text  # 占位替换确实发生

        # LLM 用占位作答 → restore 还原成真名展示给用户
        llm_answer = "建议门店A主推商品A，暂停活动A，预计提升营业额 5%[按营业额]。"
        restored = restore_in_scope(llm_answer)
        assert "青花椒新世界新丸中心店" in restored
        assert "蟹小青龙" in restored
        assert "抖音畅享套餐A" in restored
        assert "门店A" not in restored
