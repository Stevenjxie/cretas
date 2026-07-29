from pathlib import Path

import pytest

from food_kb.api import manual_chat as manual_chat_module
from food_kb.api.manual_chat import (
    _BOM_WORKFLOW_SEQUENCE_ANSWER,
    FACTORY_SYSTEM_PROMPT,
    ManualChatRequest,
    SYSTEM_PROMPT,
    _MATERIAL_PACKAGING_ANSWER,
    _MULTI_OUTPUT_WAREHOUSE_RECEIPT_ANSWER,
    _MULTI_OUTPUT_LABEL_QC_ANSWER,
    _RESTAURANT_CONTEXT_SCOPE_ANSWER,
    _RESTAURANT_FLYWHEEL_GOVERNANCE_ANSWER,
    _RESTAURANT_QUERY_CONTRACT_ANSWER,
    _WORKFLOW_ACTUAL_IO_ANSWER,
    _build_scope_prompt,
    _needs_bom_workflow_sequence_guard,
    _needs_material_packaging_guard,
    _needs_multi_output_label_qc_guard,
    _needs_multi_output_warehouse_receipt_guard,
    _needs_restaurant_context_scope_guard,
    _needs_restaurant_flywheel_governance_guard,
    _needs_restaurant_query_contract_guard,
    _needs_workflow_actual_io_guard,
    _uses_current_production_sop,
)
from food_kb.services.knowledge_retriever import KnowledgeRetriever
from food_kb.services.manual_ingester import (
    MANUAL_SOURCES,
    PROJECT_ROOT,
    parse_html_to_sections,
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
    assert (
        "Workflow 完整草稿 → BOM 绑定工序辅料并激活"
        in FACTORY_SYSTEM_PROMPT
    )
    assert "Workflow 完整草稿 → 创建 BOM 时自动固定该工艺修订" in FACTORY_SYSTEM_PROMPT
    assert "普通用户不选择 Workflow 版本" in FACTORY_SYSTEM_PROMPT
    assert "投入 → 工序执行（开始/结束/人数）→ 产出 → 确认提交" in FACTORY_SYSTEM_PROMPT
    assert "ACTIVE BOM 是 Workflow 发布启用的前置门禁" in FACTORY_SYSTEM_PROMPT
    assert "使用“自动同步并发布”" in FACTORY_SYSTEM_PROMPT
    assert "每行实收必须大于 0 且等于该行报工数量" in FACTORY_SYSTEM_PROMPT
    assert "禁止回答“两者无依赖”" in FACTORY_SYSTEM_PROMPT
    assert "原料包装换算在“原料类型字典”" in FACTORY_SYSTEM_PROMPT
    assert "都不配置主产出/联产品/副产品角色" in FACTORY_SYSTEM_PROMPT
    assert "BOM 至少需要一项主原料" in FACTORY_SYSTEM_PROMPT
    assert "没有辅料或包材本身不阻止激活" in FACTORY_SYSTEM_PROMPT
    assert "AI 候选无论 0 处还是多处都进入人工审核" in FACTORY_SYSTEM_PROMPT


def test_restaurant_prompt_keeps_session_scope_and_evidence_honest():
    assert "另一个页面或模块的筛选不保证自动带入" in SYSTEM_PROMPT
    assert "固定为 21 个维度" in SYSTEM_PROMPT
    assert "真实、代理、模拟或缺失证据" in SYSTEM_PROMPT
    assert "不得拿演示值冒充真实租户事实" in SYSTEM_PROMPT
    assert "AI 飞轮运营台只对平台管理员开放" in SYSTEM_PROMPT
    assert "只有 confirmed 映射影响解析" in SYSTEM_PROMPT
    assert "禁止模拟数据或假成功" in SYSTEM_PROMPT
    assert "明确起止日期按自然日闭区间" in SYSTEM_PROMPT
    assert "当前繁体支持不得扩大成全句转换" in SYSTEM_PROMPT
    assert "只有呈现层实际返回对应表格" in SYSTEM_PROMPT


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
    assert (
        "Workflow 完整草稿 → BOM 绑定工序辅料并激活"
        " → Workflow 刷新、发布并启用"
        in _BOM_WORKFLOW_SEQUENCE_ANSWER
    )
    assert "Workflow 完整草稿 → 创建 BOM 时系统自动固定该工艺修订" in (
        _BOM_WORKFLOW_SEQUENCE_ANSWER
    )
    assert "普通用户不需要选择 Workflow 版本" in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "工艺来源" in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "Workflow 刷新、发布并启用" in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "“自动同步并发布”" in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "READY" in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "AUTO_MIGRATABLE" in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "USER_INPUT_REQUIRED" in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "既有生产计划继续使用创建时快照" in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "两者无依赖" not in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "两者无从属关系" not in _BOM_WORKFLOW_SEQUENCE_ANSWER
    assert "先发布 Workflow" not in _BOM_WORKFLOW_SEQUENCE_ANSWER


def test_material_packaging_questions_use_the_reviewed_factory_contract():
    equivalent_questions = (
        "原料按箱采购、按千克入库时，包装换算在哪里配置？",
        "物料一袋 2.5kg，采购和收货怎样按袋录入？",
        "仓库收货原料箱数后，库存为什么只显示 kg？",
    )
    assert all(_needs_material_packaging_guard(q) for q in equivalent_questions)
    assert not _needs_material_packaging_guard("成品 1 箱等于多少盒？")
    assert "不在成品 SKU 管理里配置" in _MATERIAL_PACKAGING_ANSWER
    assert "采购、收货和跨仓调拨可以按箱或袋录入" in _MATERIAL_PACKAGING_ANSWER
    assert "库存批次、库存余额、BOM 可用量和生产领料只使用 kg" in (
        _MATERIAL_PACKAGING_ANSWER
    )


def test_multi_output_label_qc_questions_keep_two_contracts_separate():
    equivalent_questions = (
        "一条 Workflow 有多个产出并且标签需要人工质检时怎么做？",
        "多产出工序的比例和包装标签人工审核分别在哪里完成？",
        "多个产出怎样分成本，标签 AI 没框到是否还要人工复核？",
    )
    assert all(
        _needs_multi_output_label_qc_guard(q) for q in equivalent_questions
    )
    assert "不配置主产出、联产品、副产品" in _MULTI_OUTPUT_LABEL_QC_ANSWER
    assert "共享投入成本 100% 归该产出" in _MULTI_OUTPUT_LABEL_QC_ANSWER
    assert "合计必须为 100%" in _MULTI_OUTPUT_LABEL_QC_ANSWER
    assert "所有照片都进入“待我审核”" in _MULTI_OUTPUT_LABEL_QC_ANSWER
    assert "不会自动训练或发布模型" in _MULTI_OUTPUT_LABEL_QC_ANSWER


def test_workflow_actual_io_questions_use_the_reviewed_factory_contract():
    equivalent_questions = (
        "Workflow 和 BOM 不预设本次投入产出时，正式报工怎么选实际行？",
        "多产出 Workflow 报工时只有一个实际产出，成本怎么分摊？",
        "配方固定后，联产实际数量和本次成本比例应该在哪里填写？",
    )
    assert all(_needs_workflow_actual_io_guard(q) for q in equivalent_questions)
    assert not _needs_workflow_actual_io_guard("Workflow 应该怎么发布？")
    assert "至少提交一项数量大于 0 的实际投入" in _WORKFLOW_ACTUAL_IO_ANSWER
    assert "未发生项留空" in _WORKFLOW_ACTUAL_IO_ANSWER
    assert "共享投入成本 100% 归该产出" in _WORKFLOW_ACTUAL_IO_ANSWER
    assert "合计必须为 100%" in _WORKFLOW_ACTUAL_IO_ANSWER
    assert "没有辅料或包材本身不阻止激活" in _WORKFLOW_ACTUAL_IO_ANSWER


def test_multi_output_warehouse_receipt_uses_line_based_atomic_contract():
    equivalent_questions = (
        "多产出完工后仓库应该怎样确认入库？",
        "多个成品和不同单位的报工，仓库收货能不能合成一个总数？",
        "多产出少收一行时，其他 SKU 能先入库吗？",
    )
    assert all(
        _needs_multi_output_warehouse_receipt_guard(q)
        for q in equivalent_questions
    )
    assert not _needs_multi_output_warehouse_receipt_guard("单个成品怎么入库？")
    assert "按产出行逐项确认" in _MULTI_OUTPUT_WAREHOUSE_RECEIPT_ANSWER
    assert "混合单位保持各自单位" in _MULTI_OUTPUT_WAREHOUSE_RECEIPT_ANSWER
    assert "与该行正式报工数量完全一致" in _MULTI_OUTPUT_WAREHOUSE_RECEIPT_ANSWER
    assert "不允许部分 SKU 已入库" in _MULTI_OUTPUT_WAREHOUSE_RECEIPT_ANSWER


def test_restaurant_followup_scope_questions_use_the_reviewed_contract():
    equivalent_questions = (
        "门店菜品不同月份继续追问时怎么保持时间范围？",
        "换一家店再看这道菜时，上下文会沿用哪个周期？",
        "全部门店的菜品分析追问会保持原来的时间范围吗？",
    )
    assert all(
        _needs_restaurant_context_scope_guard(q) for q in equivalent_questions
    )
    assert "只在当前连续会话里保留" in _RESTAURANT_CONTEXT_SCOPE_ANSWER
    assert "“全部门店”保持聚合范围" in _RESTAURANT_CONTEXT_SCOPE_ANSWER
    assert "另一个页面或模块上的筛选不保证自动带入" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )


def test_restaurant_exact_range_scope_and_output_use_current_contract():
    equivalent_questions = (
        "看 2026年7月1日到7月15日全部門店营收，给我表格",
        "指定日期区间查看全部门店，输出报告文件时系统怎么理解？",
        "全部門店的一个期间营收请画图，繁体范围和输出偏好怎么处理？",
    )
    assert all(
        _needs_restaurant_query_contract_guard(q)
        for q in equivalent_questions
    )
    assert not _needs_restaurant_query_contract_guard("菜品毛利怎么看？")
    assert not _needs_restaurant_query_contract_guard("我想到全部门店看看")
    assert "标记为“指定区间”" in _RESTAURANT_QUERY_CONTRACT_ANSWER
    assert "“全部门店”和繁体“全部門店”" in _RESTAURANT_QUERY_CONTRACT_ANSWER
    assert "不代表整句繁体中文" in _RESTAURANT_QUERY_CONTRACT_ANSWER
    assert "当前全局默认是文字 + 表格" in _RESTAURANT_QUERY_CONTRACT_ANSWER
    assert "不能宣称“表格/报告文件已生成”" in _RESTAURANT_QUERY_CONTRACT_ANSWER
    assert "不替用户查询、计算或分析真实经营数据" in (
        _RESTAURANT_QUERY_CONTRACT_ANSWER
    )


def test_restaurant_flywheel_questions_use_the_reviewed_governance_contract():
    equivalent_questions = (
        "AI 飞轮运营台谁能使用，候选可以自动晋升吗？",
        "菜品别名映射的 pending、confirmed、rejected 怎么审核？",
        "飞轮的五个治理页面做什么，工厂现在能用吗？",
    )
    assert all(
        _needs_restaurant_flywheel_governance_guard(q)
        for q in equivalent_questions
    )
    assert not _needs_restaurant_flywheel_governance_guard("菜品毛利怎么看？")
    assert "只对平台管理员开放" in _RESTAURANT_FLYWHEEL_GOVERNANCE_ANSWER
    assert "“工厂（待接入）”不可选择" in _RESTAURANT_FLYWHEEL_GOVERNANCE_ANSWER
    assert "不能自动晋升" in _RESTAURANT_FLYWHEEL_GOVERNANCE_ANSWER
    assert "只有 `confirmed` 映射影响线上解析" in (
        _RESTAURANT_FLYWHEEL_GOVERNANCE_ANSWER
    )
    assert "不能以模拟数据或假成功兜底" in (
        _RESTAURANT_FLYWHEEL_GOVERNANCE_ANSWER
    )
    assert "不会替用户计算或分析业务数据" in (
        _RESTAURANT_FLYWHEEL_GOVERNANCE_ANSWER
    )


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
    required_sequence = (
        "Workflow 完整草稿 → BOM 绑定工序辅料并激活"
        " → Workflow 刷新、发布并启用"
    )
    assert required_sequence in current_sop
    assert "ACTIVE BOM 是 Workflow 发布启用的前置门禁" in current_sop
    assert "BOM 激活本身不会自动发布 Workflow" in current_sop
    assert "自动同步并发布" in current_sop
    assert "READY" in current_sop
    assert "AUTO_MIGRATABLE" in current_sop
    assert "系统自动固定唯一、完整且兼容的 Workflow 修订" in current_sop
    assert "普通用户不选择 Workflow 版本" in current_sop
    assert "升级到最新工艺" in current_sop
    assert "①投入物料/批次与投入数量 → ②工序执行" in current_sop
    assert "包装包材批次" in current_sop
    assert "重复提交同一创建请求只能返回同一张计划" in current_sop
    assert "小结成功后，本轮消耗必须标记已结算" in current_sop
    assert "已发布并启用" in current_sop
    assert "采购包装单位 → 库存基本单位" in current_sop
    assert "多产出实际报工与成本分摊合同" in current_sop
    assert "多产出完工入库逐行合同" in current_sop
    assert "工序 Cell、Workflow 和 BOM 都不配置主产出" in current_sop
    assert "没有辅料或包材本身不是阻塞" in current_sop
    assert "只在本次报工表单要求填写各实际产出的分摊比例" in current_sop
    assert "所有照片都必须进入人工审核" in current_sop

    html_path = Path(PROJECT_ROOT) / "docs/manual/F006-production-full-chain-manual-test-sop.html"
    html = html_path.read_text(encoding="utf-8")
    assert required_sequence in html
    assert "origin/main · SOP sync 2026-07-29" in html
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
    assert "原料多包装与基本单位库存" in html
    assert "Workflow 只声明可能投入/产出，本次事实留到报工" in html
    assert "BOM 至少配置一项主原料" in html
    assert "没有辅料或包材本身不阻止激活" in html
    assert "自动同步并发布" in html
    assert "多产出逐行确认" in html
    assert "所有照片都会进入人工审核" in html


def test_factory_role_knowledge_covers_the_12_account_operating_boundaries():
    source_path = (
        Path(PROJECT_ROOT) / "backend/python/food_kb/data/f006_production_sop.md"
    )
    current_sop = source_path.read_text(encoding="utf-8")
    html_path = (
        Path(PROJECT_ROOT)
        / "docs/manual/F006-production-full-chain-manual-test-sop.html"
    )
    html = html_path.read_text(encoding="utf-8")

    role_accounts = {
        "f006_admin": "factory_super_admin",
        "f006_hr_admin": "hr_admin",
        "f006_finance_mgr": "finance_manager",
        "f006_cashier": "cashier",
        "f006_sales_mgr": "sales_manager",
        "f006_procurement_mgr": "procurement_manager",
        "f006_dispatcher": "dispatcher",
        "f006_quality_insp": "quality_inspector",
        "f006_warehouse_mgr": "warehouse_manager",
        "f006_production_mgr": "production_manager",
        "f006_workshop": "workshop_supervisor",
        "f006_worker1": "operator",
    }
    for account, role_code in role_accounts.items():
        assert account in current_sop
        assert role_code in current_sop
        assert account in html
        assert role_code in html

    required_boundaries = (
        "财务经理 RN 不显示出纳专属“付款”页",
        "采购确认供应商交付不等于库存入账",
        "QC 结论不能由采购、仓储或生产岗位代填",
        "生产经理的宽权限不能替代车间主管确认班组执行",
        "保存草稿不扣库存",
        "不能只因为老板可见全部菜单就建议用 `f006_admin` 代替真实岗位",
    )
    for marker in required_boundaries:
        assert marker in current_sop

    assert 'id="roles"' in html
    assert "销售需求 → 财审 → 调度/生产计划" in html
    assert "财务经理批准 → 出纳执行" in html
    assert "任何岗位都不能用老板账号代做" in html


def test_restaurant_registered_sources_match_current_product_contract():
    restaurant_sources = {
        item["source"]: item
        for item in MANUAL_SOURCES
        if item.get("subcategory") == "restaurant"
    }
    assert set(restaurant_sources) == {
        "restaurant-full-chain-sop.html",
        "restaurant-product-manual.html",
        "restaurant-metrics-glossary.html",
    }

    expected_markers = {
        "restaurant-full-chain-sop.html": (
            "21 个综合分析维度",
            "跨页面或跨模块不会自动继承筛选",
            "指定区间、繁体范围词与输出偏好",
            "AI 飞轮、菜品别名与人审边界",
        ),
        "restaurant-product-manual.html": (
            "当前 21 维综合分析目录",
            "全部门店是聚合范围",
            "精确日期、范围词与输出偏好",
            "AI 飞轮运营台与菜品别名治理",
        ),
        "restaurant-metrics-glossary.html": (
            "21 维综合分析证据目录",
            "REAL / PROXY / SIMULATED / MISSING",
        ),
    }
    for source_name, markers in expected_markers.items():
        source_path = PROJECT_ROOT / restaurant_sources[source_name]["path"]
        assert source_path.is_file()
        content = source_path.read_text(encoding="utf-8")
        for marker in markers:
            assert marker in content

    ai_assist = (
        PROJECT_ROOT / "web-admin/public/aiassist.html"
    ).read_text(encoding="utf-8")
    assert "原料包装换算、标签人工审核或多产出成本怎么做？" in ai_assist
    assert "实际投入产出与多产出成本怎么报？" in ai_assist
    assert "指定区间、全部門店与输出形态" in ai_assist
    assert "AI 飞轮与菜品别名怎么治理？" in ai_assist
    assert "7 节小课 · 约 12 分钟" in ai_assist
    assert "飞轮与人审边界" in ai_assist
    assert "不做计算" in ai_assist


def test_restaurant_registered_html_sources_parse_in_a_clean_runtime():
    for source_info in MANUAL_SOURCES:
        if source_info.get("subcategory") != "restaurant":
            continue
        source_path = PROJECT_ROOT / source_info["path"]
        sections = parse_html_to_sections(source_path.read_text(encoding="utf-8"))
        assert sections, source_info["source"]


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


@pytest.mark.parametrize(
    ("question", "category", "expected_answer", "source"),
    [
        (
            "原料按箱采购、按千克入库时，包装换算在哪里配置？",
            "factory",
            _MATERIAL_PACKAGING_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "一条 Workflow 有多个产出并且标签需要人工质检时怎么做？",
            "factory",
            _MULTI_OUTPUT_LABEL_QC_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "Workflow 和 BOM 不预设本次投入产出时，正式报工怎么选实际行？",
            "factory",
            _WORKFLOW_ACTUAL_IO_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "多产出完工后仓库应该怎样确认入库？",
            "factory",
            _MULTI_OUTPUT_WAREHOUSE_RECEIPT_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "门店菜品不同月份继续追问时怎么保持时间范围？",
            "restaurant",
            _RESTAURANT_CONTEXT_SCOPE_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "看 2026年7月1日到7月15日全部門店营收，给我表格",
            "restaurant",
            _RESTAURANT_QUERY_CONTRACT_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "AI 飞轮运营台谁能使用，菜品别名候选可以自动生效吗？",
            "restaurant",
            _RESTAURANT_FLYWHEEL_GOVERNANCE_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
    ],
)
@pytest.mark.asyncio
async def test_reviewed_scope_answers_never_call_the_llm(
    monkeypatch,
    question,
    category,
    expected_answer,
    source,
):
    class FakeDoc:
        title = "当前已审查 SOP"
        content = "当前已审查业务口径。"
        similarity = 0.91

        def __init__(self, source_name):
            self.source = source_name

    class FakeRetriever:
        def is_ready(self):
            return True

        async def retrieve(self, **kwargs):
            return [FakeDoc(source)]

    async def unexpected_call_chain(*args, **kwargs):
        raise AssertionError("reviewed deterministic answer must skip the LLM")

    monkeypatch.setattr(
        manual_chat_module,
        "get_knowledge_retriever",
        lambda: FakeRetriever(),
    )
    monkeypatch.setattr("common.llm_router.call_chain", unexpected_call_chain)
    manual_chat_module._answer_cache.clear()

    response = await manual_chat_module.manual_chat(
        ManualChatRequest(
            question=question,
            category=category,
            depth="full",
        )
    )

    assert response["answer"] == expected_answer
    assert response["sources"][0]["source"] == source


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
