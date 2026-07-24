from pathlib import Path

import pytest

from food_kb.api import manual_chat as manual_chat_module
from food_kb.api.manual_chat import (
    _BOM_WORKFLOW_SEQUENCE_ANSWER,
    FACTORY_SYSTEM_PROMPT,
    ManualChatRequest,
    _build_scope_prompt,
    _needs_bom_workflow_sequence_guard,
    _uses_current_production_sop,
)
from food_kb.services.knowledge_retriever import KnowledgeRetriever
from food_kb.services.manual_ingester import (
    MANUAL_SOURCES,
    PROJECT_ROOT,
    parse_markdown_to_sections,
)


def test_factory_prompt_keeps_restaurant_analysis_out_of_ai_assist():
    assert "工厂操作助手" in FACTORY_SYSTEM_PROMPT
    assert "不执行创建、审批、报工、调库存或结单" in FACTORY_SYSTEM_PROMPT
    assert "餐饮经营数据分析属于独立的餐饮 AI" in FACTORY_SYSTEM_PROMPT
    assert "操作路径" in FACTORY_SYSTEM_PROMPT
    assert "验收结果" in FACTORY_SYSTEM_PROMPT
    assert "阻塞条件" in FACTORY_SYSTEM_PROMPT
    assert "不要使用“端口”这个词" in FACTORY_SYSTEM_PROMPT
    assert "Workflow 完整草稿 → 创建 BOM 时自动固定该工艺修订" in FACTORY_SYSTEM_PROMPT
    assert "普通用户不选择 Workflow 版本" in FACTORY_SYSTEM_PROMPT
    assert "投入 → 工序执行（开始/结束/人数）→ 产出 → 确认提交" in FACTORY_SYSTEM_PROMPT
    assert "ACTIVE BOM 是 Workflow 发布启用的前置门禁" in FACTORY_SYSTEM_PROMPT
    assert "禁止回答“两者无依赖”" in FACTORY_SYSTEM_PROMPT


def test_scope_prompt_distinguishes_depth_and_business_line():
    mvp_stock = _build_scope_prompt("mvp", "stock")
    full_sales = _build_scope_prompt("full", "sales")

    assert "MVP 非阻塞最小闭环" in mvp_stock
    assert "增量小结" in mvp_stock
    assert "全量数据闭环" in full_sales
    assert "开票和收款" in full_sales
    assert mvp_stock != full_sales


def test_only_production_chain_questions_force_the_current_sop_source():
    assert _uses_current_production_sop("多个原料连接到工序时怎么报工")
    assert _uses_current_production_sop("Workflow 的成品单位为什么是盒")
    assert not _uses_current_production_sop("登录页忘记密码怎么办")
    assert not _uses_current_production_sop("设备保养入口在哪里")


def test_bom_workflow_publication_questions_use_the_deterministic_guard():
    assert _needs_bom_workflow_sequence_guard(
        "BOM 激活后 Workflow 为什么还不能发布？"
    )
    assert not _needs_bom_workflow_sequence_guard("BOM 怎么添加包材？")
    assert "Workflow 完整草稿 → 创建 BOM 时系统自动固定该工艺修订" in (
        _BOM_WORKFLOW_SEQUENCE_ANSWER
    )
    assert "普通用户不需要选择 Workflow 版本" in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "工艺来源" in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "Workflow 刷新、发布并启用" in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "两者无依赖" not in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "两者无从属关系" not in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "先发布 Workflow" not in _BOM_WORKFLOW_SEQUENCE_ANSWER


def test_retriever_source_allow_list_is_bound_as_a_sql_parameter():
    retriever = KnowledgeRetriever()
    sql, params = retriever._build_vector_query(
        query_embedding=[0.1, 0.2],
        categories=["operation_manual"],
        subcategories=["factory"],
        top_k=8,
        similarity_threshold=0.4,
        include_expired=False,
        source_names=["f006-production-full-chain-sop.md"],
    )

    assert "source = ANY($4::text[])" in sql
    assert params[3] == ["f006-production-full-chain-sop.md"]
    assert params[-2:] == [0.4, 8]


def test_manual_chat_request_rejects_unknown_sop_scope():
    with pytest.raises(ValueError):
        ManualChatRequest(
            question="怎么报工",
            category="factory",
            depth="unknown",
        )


def test_latest_f006_sop_is_a_deployable_manual_source():
    source = next(
        item
        for item in MANUAL_SOURCES
        if item["source"] == "f006-production-full-chain-sop.md"
    )
    source_path = PROJECT_ROOT / source["path"]

    assert source_path == Path(PROJECT_ROOT) / "backend/python/food_kb/data/f006_production_sop.md"
    assert source_path.is_file()
    sections = parse_markdown_to_sections(source_path.read_text(encoding="utf-8"))
    titles = {section["title"] for section in sections}
    assert "7. 创建、按工序配置并激活 BOM / 配方" in titles
    assert "BOM 与 Workflow 的自动关联顺序" in titles
    assert "8. 绘制、校验并发布 Workflow" in titles
    assert "12. 逐道报工" in titles
    assert "15. 成本归集与出厂核算" in titles

    current_sop = source_path.read_text(encoding="utf-8")
    assert "ACTIVE BOM 是 Workflow 发布启用的前置门禁" in current_sop
    assert "BOM 激活本身不会自动发布 Workflow" in current_sop
    assert "系统自动固定唯一、完整且兼容的 Workflow 修订" in current_sop
    assert "普通用户不选择 Workflow 版本" in current_sop
    assert "升级到最新工艺" in current_sop
    assert "①投入物料/批次与投入数量 → ②工序执行" in current_sop
    assert "包装包材批次" in current_sop
    assert "重复提交同一创建请求只能返回同一张计划" in current_sop
    assert "小结成功后，本轮消耗必须标记已结算" in current_sop
    assert "已发布 vX" in current_sop
    assert "已启用 vX" in current_sop

    html_path = Path(PROJECT_ROOT) / "docs/manual/F006-production-full-chain-manual-test-sop.html"
    html = html_path.read_text(encoding="utf-8")
    assert "origin/main · SOP sync 2026-07-24" in html
    assert "先有完整 Workflow 草稿，再创建 BOM" in html
    assert "页面没有任意切换版本的选择器" in html
    assert "① 投入" in html
    assert "② 工序执行" in html
    assert "③ 产出明细" in html
    assert "④ 确认提交" in html
    assert "R03A · 调料按固定工序自动分配" in html
    assert "双出成率总览表头排序与筛选" in html
    assert "重复创建只得到同一计划" in html
    assert "消耗结算标记、入库状态与库存流水一致" in html


@pytest.mark.asyncio
async def test_factory_chat_passes_scope_to_llm_and_does_not_delay_for_related(
    monkeypatch,
):
    captured = {}

    class FakeDoc:
        title = "F006 生产全链路测试 SOP - 绘制、校验并发布 Workflow"
        content = "多个原料连接到同一工序时，至少选择一个投入来源。"
        source = "f006-production-full-chain-sop.md"
        similarity = 0.82

    class FakeRetriever:
        def is_ready(self):
            return True

        async def retrieve(self, **kwargs):
            captured["retrieve"] = kwargs
            return [FakeDoc()]

    async def fake_call_chain(slot, payload, timeout):
        captured["payload"] = payload
        captured["timeout"] = timeout
        return {
            "choices": [{"message": {"content": "至少选择一个来源，再填写实际投入量。"}}]
        }

    monkeypatch.setattr(
        manual_chat_module,
        "get_knowledge_retriever",
        lambda: FakeRetriever(),
    )
    monkeypatch.setattr("common.llm_router.call_chain", fake_call_chain)
    manual_chat_module._answer_cache.clear()

    response = await manual_chat_module.manual_chat(
        ManualChatRequest(
            question="多个原料怎么报工",
            category="factory",
            depth="full",
            business_line="sales",
        )
    )

    system_messages = [
        message["content"]
        for message in captured["payload"]["messages"]
        if message["role"] == "system"
    ]
    assert any("工厂操作助手" in message for message in system_messages)
    assert any("全量数据闭环" in message for message in system_messages)
    assert any("销售订单生产" in message for message in system_messages)
    assert captured["retrieve"]["subcategories"] == ["factory"]
    assert captured["retrieve"]["source_names"] == [
        "f006-production-full-chain-sop.md"
    ]
    assert response["related_questions"] == []
    assert response["sources"][0]["source"] == "f006-production-full-chain-sop.md"


@pytest.mark.asyncio
async def test_bom_workflow_publication_answer_never_calls_the_llm(monkeypatch):
    class FakeDoc:
        title = "F006 生产全链路测试 SOP - BOM 与 Workflow 的自动关联顺序"
        content = "ACTIVE BOM 是 Workflow 发布启用的前置门禁。"
        source = "f006-production-full-chain-sop.md"
        similarity = 0.91

    class FakeRetriever:
        def is_ready(self):
            return True

        async def retrieve(self, **kwargs):
            return [FakeDoc()]

    async def unexpected_call_chain(*args, **kwargs):
        raise AssertionError("deterministic BOM/Workflow answer must skip the LLM")

    monkeypatch.setattr(
        manual_chat_module,
        "get_knowledge_retriever",
        lambda: FakeRetriever(),
    )
    monkeypatch.setattr("common.llm_router.call_chain", unexpected_call_chain)
    manual_chat_module._answer_cache.clear()

    response = await manual_chat_module.manual_chat(
        ManualChatRequest(
            question="BOM 激活后 Workflow 为什么还不能发布？",
            category="factory",
        )
    )

    assert response["answer"] == _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert response["sources"][0]["source"] == "f006-production-full-chain-sop.md"


@pytest.mark.asyncio
async def test_related_questions_keep_factory_and_restaurant_ai_separate(monkeypatch):
    captured = {}

    async def fake_call_chain(slot, payload, timeout):
        captured["payload"] = payload
        return {
            "choices": [
                {"message": {"content": "下一步如何验收？\n库存不足时怎么处理？"}}
            ]
        }

    monkeypatch.setattr("common.llm_router.call_chain", fake_call_chain)

    related = await manual_chat_module._generate_related_questions(
        "生产计划如何创建？",
        "先选择目标成品，再由系统匹配 Workflow。",
    )

    system_prompt = captured["payload"]["messages"][0]["content"]
    assert "独立的「白垩纪工厂操作助手」" in system_prompt
    assert "不得扩展为餐饮经营分析问题" in system_prompt
    assert related == ["下一步如何验收？", "库存不足时怎么处理？"]
