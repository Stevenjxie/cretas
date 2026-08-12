from pathlib import Path

import pytest

from food_kb.api import manual_chat as manual_chat_module
from food_kb.api.manual_chat import (
    _BOM_WORKFLOW_SEQUENCE_ANSWER,
    FACTORY_SYSTEM_PROMPT,
    ManualChatRequest,
    SYSTEM_PROMPT,
    _FACTORY_BYPRODUCT_LIFECYCLE_ANSWER,
    _FACTORY_BOM_CANVAS_COST_ANSWER,
    _FACTORY_CURRENT_GATES_ANSWER,
    _FACTORY_ACCOUNTING_PERIOD_OA_ANSWER,
    _FACTORY_PRODUCTION_EXECUTION_ANSWER,
    _FACTORY_REPORTING_RUNTIME_ANSWER,
    _FACTORY_REPORTING_SOURCE_SHORTAGE_ANSWER,
    _FACTORY_RN_READONLY_ANSWER,
    _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER,
    _MATERIAL_PACKAGING_ANSWER,
    _LABEL_QC_REVIEW_ANSWER,
    _MULTI_OUTPUT_WAREHOUSE_RECEIPT_ANSWER,
    _MULTI_OUTPUT_LABEL_QC_ANSWER,
    _REPORTING_UNIT_YIELD_ANSWER,
    _RESTAURANT_CONTEXT_SCOPE_ANSWER,
    _RESTAURANT_COST_CATEGORY_ANSWER,
    _RESTAURANT_DISCOUNT_GUIDE_ANSWER,
    _RESTAURANT_DATA_AVAILABILITY_ANSWER,
    _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER,
    _RESTAURANT_FLYWHEEL_GOVERNANCE_ANSWER,
    _RESTAURANT_GUIDE_BOUNDARY_ANSWER,
    _RESTAURANT_LIVE_COMMAND_ANSWER,
    _RESTAURANT_METRIC_ENTITY_ANSWER,
    _RESTAURANT_MONTHLY_REPORT_ANSWER,
    _RESTAURANT_PLAN_ALERT_ANSWER,
    _RESTAURANT_PROACTIVE_FINDINGS_ANSWER,
    _RESTAURANT_PLATFORM_SYNC_ANSWER,
    _RESTAURANT_OUTPUT_CLARIFICATION_ANSWER,
    _RESTAURANT_QUERY_CONTRACT_ANSWER,
    _RESTAURANT_READ_WRITE_BOUNDARY_ANSWER,
    _RESTAURANT_SCOPE_ACTION_ANSWER,
    _RESTAURANT_SINGLE_DISH_MARGIN_ANSWER,
    _RESTAURANT_STAFFING_SCOPE_ANSWER,
    _WORKFLOW_ACTUAL_IO_ANSWER,
    _build_scope_prompt,
    _needs_bom_workflow_sequence_guard,
    _needs_factory_byproduct_lifecycle_guard,
    _needs_factory_bom_canvas_cost_guard,
    _needs_factory_current_gates_guard,
    _needs_factory_accounting_period_oa_guard,
    _needs_factory_production_execution_guard,
    _needs_factory_reporting_runtime_guard,
    _needs_factory_reporting_source_shortage_guard,
    _needs_factory_rn_readonly_guard,
    _needs_factory_workflow_output_directory_guard,
    _needs_material_packaging_guard,
    _needs_label_qc_review_guard,
    _needs_multi_output_label_qc_guard,
    _needs_multi_output_warehouse_receipt_guard,
    _needs_reporting_unit_yield_guard,
    _needs_restaurant_context_scope_guard,
    _needs_restaurant_cost_category_guard,
    _needs_restaurant_discount_guide_guard,
    _needs_restaurant_data_availability_guard,
    _needs_restaurant_department_stocktake_guard,
    _needs_restaurant_flywheel_governance_guard,
    _needs_restaurant_guide_boundary_guard,
    _needs_restaurant_live_command_guard,
    _needs_restaurant_metric_entity_guard,
    _needs_restaurant_monthly_report_guard,
    _needs_restaurant_plan_alert_guard,
    _needs_restaurant_proactive_findings_guard,
    _needs_restaurant_platform_sync_guard,
    _needs_restaurant_output_clarification_guard,
    _needs_restaurant_query_contract_guard,
    _needs_restaurant_read_write_boundary_guard,
    _needs_restaurant_scope_action_guard,
    _needs_restaurant_single_dish_margin_guard,
    _needs_restaurant_staffing_scope_guard,
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
    assert "画布首次保存 BOM 配置时自动固定该工艺修订" in FACTORY_SYSTEM_PROMPT
    assert "普通用户不选择 Workflow 版本" in FACTORY_SYSTEM_PROMPT
    assert "投入 → 工序执行（开始/结束/人数）→ 产出 → 确认提交" in FACTORY_SYSTEM_PROMPT
    assert "ACTIVE BOM 是 Workflow 发布启用的前置门禁" in FACTORY_SYSTEM_PROMPT
    assert "使用“自动同步并发布”" in FACTORY_SYSTEM_PROMPT
    assert "每行实收必须大于 0 且等于该行报工数量" in FACTORY_SYSTEM_PROMPT
    assert "禁止回答“两者无依赖”" in FACTORY_SYSTEM_PROMPT
    assert "原料包装换算在“原料类型字典”" in FACTORY_SYSTEM_PROMPT
    assert "画布中的 BOM 副产配置只声明稳定的副产 SKU 与预计量" in FACTORY_SYSTEM_PROMPT
    assert "副产抵扣只按盘点实际数量 × 盘点时人工确认单价" in FACTORY_SYSTEM_PROMPT
    assert "BOM 至少需要一项主原料" in FACTORY_SYSTEM_PROMPT
    assert "没有辅料或包材本身不阻止激活" in FACTORY_SYSTEM_PROMPT
    assert "当前 BOM 成本摘要只汇总包材" in FACTORY_SYSTEM_PROMPT
    assert "AI 候选无论 0 处还是多处都进入人工审核" in FACTORY_SYSTEM_PROMPT
    assert "盒子、白标、彩标三层参考框" in FACTORY_SYSTEM_PROMPT
    assert "“归属对象”只表示存放位置" in FACTORY_SYSTEM_PROMPT
    assert "“本图产出”按画布终端生产节点计算" in FACTORY_SYSTEM_PROMPT
    assert "原料分流才把整条版本谱系自动重锚" in FACTORY_SYSTEM_PROMPT
    assert "每个工序至少保留一个产出 Cell" in FACTORY_SYSTEM_PROMPT
    assert "生效 BOM 为空配方只能说“还没配辅料/包材”" in FACTORY_SYSTEM_PROMPT
    assert "中间 WIP 批次继续携带同一生产计划身份" in FACTORY_SYSTEM_PROMPT
    assert "跨量纲成品率必须先补充每单位重量" in FACTORY_SYSTEM_PROMPT
    assert "RN App 的业务入口只面向仓库主管和仓库操作员" in FACTORY_SYSTEM_PROMPT
    assert "`allowMultipleUpstreamSources`" in FACTORY_SYSTEM_PROMPT
    assert "会计期间关账申请只创建 OA" in FACTORY_SYSTEM_PROMPT


def test_restaurant_prompt_keeps_session_scope_and_evidence_honest():
    assert "跨页面筛选不保证自动带入" in SYSTEM_PROMPT
    assert "【预测排班】" in SYSTEM_PROMPT
    assert "确定性预测 FactBook" in SYSTEM_PROMPT
    assert "默认查询全部门店并在答案显式披露" in SYSTEM_PROMPT
    assert "固定为 21 个维度" in SYSTEM_PROMPT
    assert "真实、代理、模拟或缺失证据" in SYSTEM_PROMPT
    assert "不得拿演示值冒充真实租户事实" in SYSTEM_PROMPT
    assert "AI 飞轮运营台只对平台管理员开放" in SYSTEM_PROMPT
    assert "只有 confirmed 映射影响解析" in SYSTEM_PROMPT
    assert "禁止模拟数据或假成功" in SYSTEM_PROMPT
    assert "明确起止日期按自然日闭区间" in SYSTEM_PROMPT
    assert "当前繁体支持不得扩大成全句转换" in SYSTEM_PROMPT
    assert "只有呈现层实际返回对应表格" in SYSTEM_PROMPT
    assert "当前月报固定 9 节" in SYSTEM_PROMPT
    assert "计划预警是同一 sealed QuerySpec" in SYSTEM_PROMPT
    assert "客如云风格 connector" in SYSTEM_PROMPT
    assert "损耗金额按 `wastage_cost` 排序" in SYSTEM_PROMPT
    assert "盘点亏损默认按同一窗口的 `shortage_cost` 回答金额" in SYSTEM_PROMPT
    assert "五部门与金额权限" in SYSTEM_PROMPT
    assert "主动发现与行动建议" in SYSTEM_PROMPT
    assert "POS 流水或后厨事实任一类即可进入餐饮问答" in SYSTEM_PROMPT
    assert "导览助手不替用户计算毛利、损耗" in SYSTEM_PROMPT
    assert "统一使用 GFM Markdown 表格" in SYSTEM_PROMPT
    assert "门店简称匹配多家时返回最多 3 个真实候选按钮" in SYSTEM_PROMPT
    assert "简称或片段唯一匹配时也先给真实门店确认按钮" in SYSTEM_PROMPT
    assert "问题对象/分析范围/语义规划/查询计划/计划版本" in SYSTEM_PROMPT
    assert "【餐饮读写意图边界】" in SYSTEM_PROMPT
    assert "即使出现下架、调整、删除等写动词也保持只读分析" in SYSTEM_PROMPT
    assert "本次不执行任何操作，也不替用户猜答案" in SYSTEM_PROMPT
    assert "食材类只认“食材、原材料、食品、饮料、酒水、菜品”" in SYSTEM_PROMPT


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
    assert _uses_current_production_sop(
        "工厂没有分段字典时，物料类别和新建料号分别怎么保存？"
    )
    assert _uses_current_production_sop(
        "没有 L1/L2/L3 字典时，新建物料的编码由谁填写？"
    )
    assert _uses_current_production_sop(
        "无分类码的类别应该怎样显示？编辑已有物料能不能改料号？"
    )
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
    assert "Workflow 完整草稿 → 首次保存 BOM 配置时系统自动固定该工艺修订" in (
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


def test_factory_bom_canvas_and_cost_boundary_is_deterministic():
    equivalent_questions = (
        "BOM 现在为什么在 Workflow 画布里配置，人工和均摊去哪了？",
        "辅料包材 cell 怎么生效，BOM 成本能不能和实际成本比？",
        "旧 BOM 菜单不见了，怎么从零建首版配方并发布？",
    )
    assert all(_needs_factory_bom_canvas_cost_guard(q) for q in equivalent_questions)
    assert not _needs_factory_bom_canvas_cost_guard(
        "BOM 激活后 Workflow 为什么还不能发布？"
    )
    assert "产品-工序配置" in _FACTORY_BOM_CANVAS_COST_ANSWER
    assert "不是新的拓扑节点" in _FACTORY_BOM_CANVAS_COST_ANSWER
    assert "首次保存会创建首版草稿" in _FACTORY_BOM_CANVAS_COST_ANSWER
    assert "没有包材或副产本身不阻止激活" in _FACTORY_BOM_CANVAS_COST_ANSWER
    assert "只汇总包材" in _FACTORY_BOM_CANVAS_COST_ANSWER
    assert "包材 Cell 位于成品上方并连向所属成品" in (
        _FACTORY_BOM_CANVAS_COST_ANSWER
    )
    assert "有生效 BOM 但明细为空只能说“还没配辅料/包材”" in (
        _FACTORY_BOM_CANVAS_COST_ANSWER
    )
    assert "旧启用版本不能把重发布自身误拦" in (
        _FACTORY_BOM_CANVAS_COST_ANSWER
    )
    assert "人工与制造费用也不在 BOM 中配置" in _FACTORY_BOM_CANVAS_COST_ANSWER
    assert "不能直接与正式报工、结算形成的实际完整成本比较" in (
        _FACTORY_BOM_CANVAS_COST_ANSWER
    )


def test_factory_workflow_storage_and_output_directory_is_deterministic():
    equivalent_questions = (
        "Workflow 的存放位置为什么和本图产出不一样？",
        "工艺图归属对象不是实际产出时怎样按产出反查？",
        "存放在原料目录的 Workflow 怎样找到实际产出的成品路线？",
    )
    assert all(
        _needs_factory_workflow_output_directory_guard(q)
        for q in equivalent_questions
    )
    assert not _needs_factory_workflow_output_directory_guard(
        "Workflow 和 BOM 的发布顺序是什么？"
    )
    assert "只表示工艺图的存放位置" in _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER
    assert "“本图产出”才是画布计算出的真实产出" in (
        _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER
    )
    assert "BOM 副产不计入" in _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER
    assert "不能冒充“没有工艺图”" in _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER
    assert "完全匹配或最小超集" in _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER
    assert "整条 Workflow 版本谱系自动重锚到该原料" in (
        _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER
    )
    assert "多原料多成品的联产没有唯一归属" in (
        _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER
    )
    assert "每个工序 Cell 至少保留一个产出 Cell" in (
        _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER
    )
    assert "有生效 BOM 但暂未配辅料或包材" in (
        _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER
    )


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


def test_reporting_unit_and_cross_unit_yield_use_the_reviewed_contract():
    equivalent_questions = (
        "报工投入是 kg、产出是盒时成品率怎么计算？",
        "袋和盒这些报工单位能自动换算吗？",
        "跨单位出成率为什么要填写每单位重量？",
    )
    assert all(_needs_reporting_unit_yield_guard(q) for q in equivalent_questions)
    assert not _needs_reporting_unit_yield_guard("Workflow 怎么发布？")
    assert "计数/包装单位按字面量匹配" in _REPORTING_UNIT_YIELD_ANSWER
    assert "手工批次和已有库存批次都遵守同一单位合同" in (
        _REPORTING_UNIT_YIELD_ANSWER
    )
    assert "需补充每单位成品重量" in _REPORTING_UNIT_YIELD_ANSWER
    assert "不得给出伪造百分比" in _REPORTING_UNIT_YIELD_ANSWER


def test_label_qc_workbench_uses_three_reference_layers_and_human_tools():
    equivalent_questions = (
        "标签质检复核台的盒子白标彩标参考框怎么用？",
        "标签 AI 漏检时用什么画笔补框？",
        "彩标审核能不能让 AI 自动给结论？",
    )
    assert all(_needs_label_qc_review_guard(q) for q in equivalent_questions)
    assert not _needs_label_qc_review_guard("普通批次质检在哪里？")
    assert "盒子 → 白标 → 彩标" in _LABEL_QC_REVIEW_ANSWER
    assert "白标画笔或彩标画笔" in _LABEL_QC_REVIEW_ANSWER
    assert "AI 为 0 候选也必须人工检查" in _LABEL_QC_REVIEW_ANSWER
    assert "不会自动训练或发布模型" in _LABEL_QC_REVIEW_ANSWER


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


def test_factory_rn_readonly_questions_use_current_mobile_boundaries():
    equivalent_questions = (
        "在手机端，仓库角色的盘点记录和智能入库各能做什么？",
        "RN 仓库角色能看盘点记录吗，生产角色为什么去电脑端？",
        "手机里的智能入库、盘点记录和电脑端物料需求单边界是什么？",
    )
    assert all(_needs_factory_rn_readonly_guard(q) for q in equivalent_questions)
    assert not _needs_factory_rn_readonly_guard("Web 后台怎么创建生产计划？")
    assert "未完成任务可以从记录列表继续进入盘点录入" in (
        _FACTORY_RN_READONLY_ANSWER
    )
    assert "业务入口只面向仓库主管和仓库操作员" in _FACTORY_RN_READONLY_ANSWER
    assert "缺少规格明确显示“未设规格”" in _FACTORY_RN_READONLY_ANSWER
    assert "物料需求单和生产工作台属于电脑 Web" in _FACTORY_RN_READONLY_ANSWER
    assert "不会替用户提交、审批或增加库存" in _FACTORY_RN_READONLY_ANSWER


def test_factory_accounting_period_close_uses_oa_state_machine():
    equivalent_questions = (
        "会计期间发起关账后 OA 审批怎么流转？",
        "账期关账被 REJECT 会重开已经关闭的期间吗？",
        "期间关账 APPROVE 后要验收哪些结果？",
    )
    assert all(_needs_factory_accounting_period_oa_guard(q) for q in equivalent_questions)
    assert not _needs_factory_accounting_period_oa_guard("采购付款审批怎么做？")
    assert "申请成功不等于已经关账" in _FACTORY_ACCOUNTING_PERIOD_OA_ANSWER
    assert "APPROVE" in _FACTORY_ACCOUNTING_PERIOD_OA_ANSWER
    assert "CLOSED" in _FACTORY_ACCOUNTING_PERIOD_OA_ANSWER
    assert "20 天调整窗口" in _FACTORY_ACCOUNTING_PERIOD_OA_ANSWER
    assert "REJECT 只驳回本次申请" in _FACTORY_ACCOUNTING_PERIOD_OA_ANSWER


def test_factory_reporting_runtime_uses_frozen_workflow_and_terminal_bom():
    equivalent_questions = (
        "逐道报工什么时候允许同一物料选择多个批次？",
        "工序报工能不能使用过期库存批次？",
        "中间工序的调料配方应该从哪个 BOM 读取？",
    )
    assert all(_needs_factory_reporting_runtime_guard(q) for q in equivalent_questions)
    assert not _needs_factory_reporting_runtime_guard("仓库库存怎么盘点？")
    assert "`allowMultipleUpstreamSources`" in _FACTORY_REPORTING_RUNTIME_ANSWER
    assert "过期库存单独展示为不可用" in _FACTORY_REPORTING_RUNTIME_ANSWER
    assert "终端成品 SKU 的 BOM" in _FACTORY_REPORTING_RUNTIME_ANSWER
    assert "未绑定工序的辅料与包材" in _FACTORY_REPORTING_RUNTIME_ANSWER


def test_factory_reporting_source_shortage_uses_server_authoritative_contract():
    equivalent_questions = (
        "逐道报工投入来源怎样选，服务端发现物料短缺怎么办？",
        "报工选多个批次来源时库存不足会显示什么？",
        "投入来源一行一批次，缺料时能不能部分报工？",
    )
    assert all(
        _needs_factory_reporting_source_shortage_guard(q)
        for q in equivalent_questions
    )
    assert not _needs_factory_reporting_source_shortage_guard("库存不足怎么办？")
    assert "可以选一个、多个或全部" in _FACTORY_REPORTING_SOURCE_SHORTAGE_ANSWER
    assert "一个来源一行" in _FACTORY_REPORTING_SOURCE_SHORTAGE_ANSWER
    assert "需多少、可用多少、缺多少" in _FACTORY_REPORTING_SOURCE_SHORTAGE_ANSWER
    assert "不能扣一部分库存或让其它投入部分成功" in (
        _FACTORY_REPORTING_SOURCE_SHORTAGE_ANSWER
    )


def test_factory_start_batch_reporting_and_receipt_stay_on_one_execution_chain():
    equivalent_questions = (
        "开工后生产批次和逐道报工怎么衔接？",
        "逐道报工完成后什么时候能仓库确认入库？",
        "生产批次、报工和完工入库是不是同一条链？",
        "中间 WIP 批次和下一道工序如何保持同一生产计划身份？",
    )
    assert all(_needs_factory_production_execution_guard(q) for q in equivalent_questions)
    assert not _needs_factory_production_execution_guard("仓库库存怎么盘点？")
    assert "开工创建批次 → 逐道报工复用该批次 → 完工后仓库确认入库" in (
        _FACTORY_PRODUCTION_EXECUTION_ANSWER
    )
    assert "[{batchNo, qty}]" in _FACTORY_PRODUCTION_EXECUTION_ANSWER
    assert "`batchRows`" in _FACTORY_PRODUCTION_EXECUTION_ANSWER
    assert "不指定则交给服务端按 FEFO 自动分配" in _FACTORY_PRODUCTION_EXECUTION_ANSWER
    assert "PENDING_WAREHOUSE_RECEIPT" in _FACTORY_PRODUCTION_EXECUTION_ANSWER
    assert "中间 WIP 批次必须保留同一生产计划身份" in (
        _FACTORY_PRODUCTION_EXECUTION_ANSWER
    )


def test_restaurant_scope_default_and_followup_questions_use_the_reviewed_contract():
    equivalent_questions = (
        "首轮没说门店时是默认全部门店还是一定先反问？",
        "最近30天总营收没写门店时会怎么处理？",
        "点名不存在的门店、澄清延续轮和缺时间时分别怎么处理？",
        "门店简称唯一匹配时会直接查还是先确认按钮？",
        "全店答案后只说门店片段，怎样保留原问题并收窄范围？",
    )
    assert all(
        _needs_restaurant_context_scope_guard(q) for q in equivalent_questions
    )
    assert "同一连续会话中已确认且与新问题兼容" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "“全部门店”始终是聚合范围" in _RESTAURANT_CONTEXT_SCOPE_ANSWER
    assert "另一个页面或模块的筛选不保证自动带入" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "系统默认查询全部门店" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "范围：全部门店合计" in _RESTAURANT_CONTEXT_SCOPE_ANSWER
    assert "不存在、改名或停用的门店时必须澄清" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "属于对上一问的范围收窄" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "也必须先显示“你是想看 X 店吗？”确认按钮" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "把新门店范围拼回原问题后再规划" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "问题对象、分析范围、语义规划、查询计划、计划版本" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "planner 结构化判定唯一缺项是 `store_scope`" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "最近30天加权毛利率是多少" in _RESTAURANT_CONTEXT_SCOPE_ANSWER
    assert "最近30天哪个时段生意最好" in _RESTAURANT_CONTEXT_SCOPE_ANSWER
    assert "最近30天食材成本占营收多少" in _RESTAURANT_CONTEXT_SCOPE_ANSWER
    assert "最近30天折扣力度多大" in _RESTAURANT_CONTEXT_SCOPE_ANSWER
    assert "历史时段表现 resolver" in _RESTAURANT_CONTEXT_SCOPE_ANSWER
    assert "无时间戳记录不会硬塞进夜宵" in _RESTAURANT_CONTEXT_SCOPE_ANSWER
    assert "泛指“食材”不再被误标成某个具体食材维度" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "按折扣金额、折扣率和优惠构成作描述性汇总" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "不把同期变化写成折扣导致营收变化" in (
        _RESTAURANT_CONTEXT_SCOPE_ANSWER
    )
    assert "折扣力度问法仍未闭环" not in _RESTAURANT_CONTEXT_SCOPE_ANSWER
    assert "仍缺历史时段表现 resolver" not in _RESTAURANT_CONTEXT_SCOPE_ANSWER


def test_restaurant_deliberative_write_words_stay_read_only_and_fail_closed():
    equivalent_questions = (
        "这个菜要不要下架？",
        "如果要调整菜单应该怎样预览和确认？",
        "读写歧义时写操作安全门怎么处理？",
    )
    assert all(
        _needs_restaurant_read_write_boundary_guard(q)
        for q in equivalent_questions
    )
    assert not _needs_restaurant_read_write_boundary_guard("最近30天菜品销量排名")
    assert "征询或假设保持只读" in _RESTAURANT_READ_WRITE_BOUNDARY_ANSWER
    assert "这个菜要不要下架" in _RESTAURANT_READ_WRITE_BOUNDARY_ANSWER
    assert "先返回预览" in _RESTAURANT_READ_WRITE_BOUNDARY_ANSWER
    assert "判定矛盾时 fail closed" in _RESTAURANT_READ_WRITE_BOUNDARY_ANSWER
    assert "本次不执行任何操作" in _RESTAURANT_READ_WRITE_BOUNDARY_ANSWER


def test_restaurant_discount_questions_never_fabricate_current_business_data():
    equivalent_questions = (
        "最近30天折扣力度多大？",
        "过去30天优惠折扣情况怎么样？",
        "近一个月折扣率、折扣金额和优惠构成分别是多少？",
    )
    assert all(
        _needs_restaurant_discount_guide_guard(q) for q in equivalent_questions
    )
    assert not _needs_restaurant_discount_guide_guard("折扣权限在哪里配置？")
    assert "不能读取、计算或分析当前门店的折扣数据" in (
        _RESTAURANT_DISCOUNT_GUIDE_ANSWER
    )
    assert "不会编造折扣金额、占比或健康判断" in (
        _RESTAURANT_DISCOUNT_GUIDE_ANSWER
    )
    assert "折扣金额 ÷ 同期间营收" in _RESTAURANT_DISCOUNT_GUIDE_ANSWER
    assert "行业示例、模拟占比或 0" in _RESTAURANT_DISCOUNT_GUIDE_ANSWER
    assert "不得声称折扣导致营收变化" in _RESTAURANT_DISCOUNT_GUIDE_ANSWER


def test_restaurant_metric_and_entity_questions_use_current_axes():
    equivalent_questions = (
        "损耗金额最高的食材按什么排序，金额轴没有时怎么办？",
        "领料花了多少钱应该走菜品成本还是 requisition_cost？",
        "菜单目录如何避免把业务词和疑问片段当成菜名？",
    )
    assert all(_needs_restaurant_metric_entity_guard(q) for q in equivalent_questions)
    assert not _needs_restaurant_metric_entity_guard("损耗怎么管理？")
    assert "按食材 `wastage_cost` 排序" in _RESTAURANT_METRIC_ENTITY_ANSWER
    assert "不能拿数量顺序冒充金额排名" in _RESTAURANT_METRIC_ENTITY_ANSWER
    assert "走后厨领料成本 `requisition_cost`" in _RESTAURANT_METRIC_ENTITY_ANSWER
    assert "当前租户菜单目录内核对" in _RESTAURANT_METRIC_ENTITY_ANSWER


def test_restaurant_data_availability_never_turns_missing_into_zero():
    equivalent_questions = (
        "只有 POS 流水，没有领料损耗盘点时能回答什么，会按 0 算吗？",
        "仅有 POS 时后厨事实缺失是不是都返回 0？",
        "只有后厨事实没有 POS 时能编造营收和渠道吗？",
        "哪个供应商报价最贵，没数据时应该怎么回答？",
        "平台抽成为什么没有数据，缺哪张表？",
    )
    assert all(
        _needs_restaurant_data_availability_guard(q)
        for q in equivalent_questions
    )
    assert "可以回答营收、订单、菜品销售、门店" in (
        _RESTAURANT_DATA_AVAILABILITY_ANSWER
    )
    assert "不能按 0 计算" in _RESTAURANT_DATA_AVAILABILITY_ANSWER
    assert "0 只代表真实查询得到的 0" in _RESTAURANT_DATA_AVAILABILITY_ANSWER
    assert "供应商报价" in _RESTAURANT_DATA_AVAILABILITY_ANSWER
    assert "实收金额" in _RESTAURANT_DATA_AVAILABILITY_ANSWER
    assert "数据库表/字段只进入工程侧缺口元数据" in (
        _RESTAURANT_DATA_AVAILABILITY_ANSWER
    )
    assert "agg_supplier_price" not in _RESTAURANT_DATA_AVAILABILITY_ANSWER
    assert "actual_receive" not in _RESTAURANT_DATA_AVAILABILITY_ANSWER
    assert "有数据或查表失败时不得硬编码成缺失" in (
        _RESTAURANT_DATA_AVAILABILITY_ANSWER
    )
    assert "不替用户执行比价" in _RESTAURANT_DATA_AVAILABILITY_ANSWER


def test_restaurant_guide_never_calculates_business_data():
    equivalent_questions = (
        "餐饮导览助手能替我计算门店毛利和损耗吗？",
        "操作助手可以分析我上传的业务数据吗？",
        "导览助手不代算时应把真实分析带到哪里？",
    )
    assert all(_needs_restaurant_guide_boundary_guard(q) for q in equivalent_questions)
    assert "不能替用户计算门店毛利、损耗" in _RESTAURANT_GUIDE_BOUNDARY_ANSWER
    assert "进入 SmartBI 餐饮 AI" in _RESTAURANT_GUIDE_BOUNDARY_ANSWER
    assert "不会返回或代算该门店的真实经营结果" in (
        _RESTAURANT_GUIDE_BOUNDARY_ANSWER
    )


def test_restaurant_single_dish_margin_uses_the_fixed_capability_boundary():
    equivalent_questions = (
        "中餐能不能自动精确算出每一道菜的真实毛利？",
        "每道菜的毛利能自动实时算准吗？",
        "单菜毛利是不是系统精确算出的真实值？",
    )
    assert all(
        _needs_restaurant_single_dish_margin_guard(question)
        for question in equivalent_questions
    )
    assert not _needs_restaurant_single_dish_margin_guard("期间总毛利率怎么看？")
    assert "单菜精确毛利算不准" in _RESTAURANT_SINGLE_DISH_MARGIN_ANSWER
    assert "不能自动或实时算出每一道菜的真实毛利" in (
        _RESTAURANT_SINGLE_DISH_MARGIN_ANSWER
    )
    assert "理论参考" in _RESTAURANT_SINGLE_DISH_MARGIN_ANSWER
    assert "配方覆盖率与采购价新鲜度" in _RESTAURANT_SINGLE_DISH_MARGIN_ANSWER
    assert "只汇总配方中已经登记的食材" in _RESTAURANT_SINGLE_DISH_MARGIN_ANSWER
    assert "不超过 3 项时提示覆盖不足" in _RESTAURANT_SINGLE_DISH_MARGIN_ANSWER
    assert "可信第一口径是期间总毛利率" in (
        _RESTAURANT_SINGLE_DISH_MARGIN_ANSWER
    )
    assert "期初库存 + 本期采购 − 期末库存" in (
        _RESTAURANT_SINGLE_DISH_MARGIN_ANSWER
    )
    assert "不替用户计算或分析真实经营数据" in (
        _RESTAURANT_SINGLE_DISH_MARGIN_ANSWER
    )


def test_restaurant_scope_actions_require_executable_store_questions():
    equivalent_questions = (
        "什么时候给看全部门店或只看某店的按钮？",
        "损耗问题也会显示换范围按钮吗？",
        "resolver 不支持门店维度时还能切换范围吗？",
    )
    assert all(_needs_restaurant_scope_action_guard(q) for q in equivalent_questions)
    assert "当前 resolver 真正支持门店维度" in _RESTAURANT_SCOPE_ACTION_ANSWER
    assert "损耗等当前不能按门店拆分" in _RESTAURANT_SCOPE_ACTION_ANSWER
    assert "完整、可独立执行的问句" in _RESTAURANT_SCOPE_ACTION_ANSWER


def test_restaurant_staffing_advice_describes_forecast_scope_without_executing_it():
    equivalent_questions = (
        "餐饮预测排班会根据什么数据，支持哪些时间范围和门店范围？",
        "下周的排班建议会按门店和午市晚市生成吗？",
        "预测排班的一键调整怎样确认，旧计划还能提交吗？",
    )
    assert all(_needs_restaurant_staffing_scope_guard(q) for q in equivalent_questions)
    assert not _needs_restaurant_staffing_scope_guard("今天各门店营业额是多少？")
    assert "支持“明天”“下周”“下个月”三个未来范围" in _RESTAURANT_STAFFING_SCOPE_ANSWER
    assert "按门店、午市/下午茶/晚市/夜宵展示" in _RESTAURANT_STAFFING_SCOPE_ANSWER
    assert "大模型只负责理解、综合和解释" in _RESTAURANT_STAFFING_SCOPE_ANSWER
    assert "相同计划指纹精确确认" in _RESTAURANT_STAFFING_SCOPE_ANSWER
    assert "拒绝过期确认" in _RESTAURANT_STAFFING_SCOPE_ANSWER
    assert "不替用户计算真实经营数据" in _RESTAURANT_STAFFING_SCOPE_ANSWER


def test_current_factory_gates_have_three_equivalent_routes_and_keep_fail_closed():
    equivalent_questions = [
        "只、个、件和自定义单位怎么保存，SKU 大类为什么创建时必填？",
        "没有收货凭证能确认收货入库吗，其他仓库过期批次算可用量吗？",
        "标签生产日期和 ZIP 有什么规则，Workflow 缺 skuId 怎么处理？",
    ]
    assert all(_needs_factory_current_gates_guard(q) for q in equivalent_questions)
    assert _needs_factory_current_gates_guard(
        "clerk 逐道录入的 RawInput 为什么按批次库存单位填写数量？"
    )
    assert not _needs_factory_current_gates_guard("BOM 怎么激活？")
    assert "不计入当前生产仓可用量" in _FACTORY_CURRENT_GATES_ANSWER
    assert "WORKFLOW_MATERIAL_SKU_MISSING" in _FACTORY_CURRENT_GATES_ANSWER
    assert "PRODUCT_CATEGORY_REQUIRED" in _FACTORY_CURRENT_GATES_ANSWER
    assert "确认按钮禁用" in _FACTORY_CURRENT_GATES_ANSWER
    assert "clerk" in _FACTORY_CURRENT_GATES_ANSWER
    assert "不二次做 g↔kg 换算" in _FACTORY_CURRENT_GATES_ANSWER
    assert "整批失败" in _FACTORY_CURRENT_GATES_ANSWER


def test_restaurant_live_command_has_three_equivalent_routes_and_source_boundaries():
    equivalent_questions = [
        "餐饮 AI 实时经营指挥屏的传输状态和来源怎么读？",
        "经营指挥屏里的模拟预订、FactBook 和大模型分别做什么？",
        "近15分钟客流在实时指挥里是谁提供的，排班能直接改吗？",
    ]
    assert all(_needs_restaurant_live_command_guard(q) for q in equivalent_questions)
    assert not _needs_restaurant_live_command_guard("工厂今天生产多少？")
    assert "Java 经营汇总" in _RESTAURANT_LIVE_COMMAND_ANSWER
    assert "Python 预测 FactBook" in _RESTAURANT_LIVE_COMMAND_ANSWER
    assert "不能冒充平台实单" in _RESTAURANT_LIVE_COMMAND_ANSWER
    assert "不替用户计算或分析真实经营数据" in _RESTAURANT_LIVE_COMMAND_ANSWER


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


def test_restaurant_table_and_ambiguity_clarification_use_current_contract():
    equivalent_questions = (
        "Markdown 表格排行里门店名匹配到多家时怎么澄清？",
        "排行表格为什么用 GFM，门店简称有多个候选怎么办？",
        "构成表格的追问怎样保留原指标并显示门店候选？",
    )
    assert all(
        _needs_restaurant_output_clarification_guard(q)
        for q in equivalent_questions
    )
    assert not _needs_restaurant_output_clarification_guard("门店营收是多少？")
    assert "GFM Markdown 表格" in _RESTAURANT_OUTPUT_CLARIFICATION_ANSWER
    assert "缺失值显示“—”" in _RESTAURANT_OUTPUT_CLARIFICATION_ANSWER
    assert "没有金额权限时，整列省略金额" in (
        _RESTAURANT_OUTPUT_CLARIFICATION_ANSWER
    )
    assert "最多 3 个真实候选" in _RESTAURANT_OUTPUT_CLARIFICATION_ANSWER
    assert "片段只推断出一家时也先显示该真实门店的确认按钮" in (
        _RESTAURANT_OUTPUT_CLARIFICATION_ANSWER
    )
    assert "只有用户完整写出有效门店全名时才直接使用" in (
        _RESTAURANT_OUTPUT_CLARIFICATION_ANSWER
    )
    assert "仍然查询销量" in _RESTAURANT_OUTPUT_CLARIFICATION_ANSWER
    assert "不替用户查询、计算或分析真实经营数据" in (
        _RESTAURANT_OUTPUT_CLARIFICATION_ANSWER
    )


def test_restaurant_cost_category_uses_java_authoritative_vocabulary():
    equivalent_questions = (
        "食材成本和人工成本用哪些费用名称分类？",
        "费用名写原材料或工资会归哪类，采购算食材吗？",
        "Java 和 Python 的食材成本分类词表为什么必须一致？",
    )
    assert all(_needs_restaurant_cost_category_guard(q) for q in equivalent_questions)
    assert not _needs_restaurant_cost_category_guard("本月食材成本是多少？")
    assert "食材、原材料、食品、饮料、酒水、菜品" in (
        _RESTAURANT_COST_CATEGORY_ANSWER
    )
    assert "人工、工资、薪、员工、劳务" in _RESTAURANT_COST_CATEGORY_ANSWER
    assert "“采购”“原料”“人力”等未列入当前权威词表" in (
        _RESTAURANT_COST_CATEGORY_ANSWER
    )
    assert "期初库存 + 本期采购 − 期末库存" in _RESTAURANT_COST_CATEGORY_ANSWER
    assert "不读取或代算当前门店的真实费用" in _RESTAURANT_COST_CATEGORY_ANSWER


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


def test_restaurant_monthly_report_matches_the_current_nine_section_template():
    assert "当前固定为 9 节" in _RESTAURANT_MONTHLY_REPORT_ANSWER
    assert "每一节都显式使用“全部门店”范围" in _RESTAURANT_MONTHLY_REPORT_ANSWER
    assert "损耗、领料和盘点 resolver 现在都按月报请求的明确时间窗取数" in (
        _RESTAURANT_MONTHLY_REPORT_ANSWER
    )
    assert "损耗排行、领料用量、盘点差异" in _RESTAURANT_MONTHLY_REPORT_ANSWER


def test_factory_byproduct_questions_use_one_reviewed_lifecycle():
    equivalent_questions = (
        "副产怎样建 SKU、在 BOM 声明、报工落生产仓并由盘点确认抵扣？",
        "肥油怎么从物料建档经过配方和报工进入生产仓？",
        "碎骨副产物料的盘点单价和成本抵扣为什么还没确认？",
    )
    assert all(_needs_factory_byproduct_lifecycle_guard(q) for q in equivalent_questions)
    assert not _needs_factory_byproduct_lifecycle_guard("副产是什么？")
    assert "原料类型字典建立带“副产”标记的物料 SKU" in (
        _FACTORY_BYPRODUCT_LIFECYCLE_ANSWER
    )
    assert "盘点实际数量 × 盘点时人工确认单价" in (
        _FACTORY_BYPRODUCT_LIFECYCLE_ANSWER
    )


def test_restaurant_department_and_stocktake_questions_share_current_contract():
    equivalent_questions = (
        "五部门驾驶舱分别看什么，盘点亏损金额怎样切换时间范围？",
        "老板、店长和采购对运营市场财务人事采购有哪些权限？",
        "盘亏多少钱，能切本月和最近7天吗？",
        "AI 工作台和 AI 价值汇总从哪里进入？",
        "营销员提成和复购提成从哪里进入？",
    )
    assert all(
        _needs_restaurant_department_stocktake_guard(q)
        for q in equivalent_questions
    )
    assert not _needs_restaurant_department_stocktake_guard("今天营业额多少？")
    assert "五部门驾驶舱" in _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER
    assert "厨师长角色已经退役" in _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER
    assert "餐饮老板是全局读写角色" in _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER
    assert "不能把 kg、L 等不同单位相加" in (
        _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER
    )
    assert "中央角色/模块/金额权限闸" in _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER
    assert "AI 工作台" in _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER
    assert "/dashboard/ai-value" in _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER
    assert "/restaurant/commission" in _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER


def test_restaurant_proactive_findings_keep_three_states_and_grounded_actions():
    equivalent_questions = (
        "店长不提问能看到什么，餐饮现在有几个部门？",
        "今日营运台的主动发现和行动建议怎么理解？",
        "谜题菜、损耗提示、几点最忙分别怎么理解？",
    )
    assert all(
        _needs_restaurant_proactive_findings_guard(q) for q in equivalent_questions
    )
    assert not _needs_restaurant_proactive_findings_guard("今天营业额多少？")
    assert "已检查且没有发现" in _RESTAURANT_PROACTIVE_FINDINGS_ANSWER
    assert "数据不足或不可比属于“跳过”" in _RESTAURANT_PROACTIVE_FINDINGS_ANSWER
    assert "规则查询失败属于“失败”" in _RESTAURANT_PROACTIVE_FINDINGS_ANSWER
    assert "高单位贡献毛利、低销量" in _RESTAURANT_PROACTIVE_FINDINGS_ANSWER
    assert "不能生成事实中没有的" in _RESTAURANT_PROACTIVE_FINDINGS_ANSWER
    assert "每条已渲染事实彼此独立" in _RESTAURANT_PROACTIVE_FINDINGS_ANSWER
    assert "不能把谜题菜和损耗等无关发现揉成一件事" in (
        _RESTAURANT_PROACTIVE_FINDINGS_ANSWER
    )
    assert "跨发现错归因" in _RESTAURANT_PROACTIVE_FINDINGS_ANSWER
    assert "当前页面可见的是“今日营运台”发现卡" in (
        _RESTAURANT_PROACTIVE_FINDINGS_ANSWER
    )
    assert "运营、市场、财务、人事、采购" in _RESTAURANT_PROACTIVE_FINDINGS_ANSWER
    assert "厨师长已退役" in _RESTAURANT_PROACTIVE_FINDINGS_ANSWER


def test_restaurant_plan_alert_questions_use_queryspec_fail_closed_contract():
    equivalent_questions = (
        "餐饮计划预警怎样复用 QuerySpec 和阈值？",
        "经营告警定时执行失败会自动关闭吗？",
        "环比预警查询计划没有数据时怎样判定？",
    )
    assert all(_needs_restaurant_plan_alert_guard(q) for q in equivalent_questions)
    assert not _needs_restaurant_plan_alert_guard("今天有告警吗？")
    assert "同一种 sealed QuerySpec" in _RESTAURANT_PLAN_ALERT_ANSWER
    assert "当前 P1 只支持餐饮销售汇总的环比预警" in (
        _RESTAURANT_PLAN_ALERT_ANSWER
    )
    assert "本次无法判定" in _RESTAURANT_PLAN_ALERT_ANSWER
    assert "不能自动关闭既有 OPEN 告警" in _RESTAURANT_PLAN_ALERT_ANSWER


def test_restaurant_platform_sync_questions_keep_runtime_and_mock_boundaries():
    equivalent_questions = (
        "客如云 POS 怎么接入并自动同步？",
        "平台同步和 Gold 刷新是实时的吗？",
        "POS 模拟平台的数据能当真实门店数据吗？",
    )
    assert all(_needs_restaurant_platform_sync_guard(q) for q in equivalent_questions)
    assert not _needs_restaurant_platform_sync_guard("外卖毛利怎么算？")
    assert "不是已经交付给门店用户的“设置 → POS 对接”自助页面" in (
        _RESTAURANT_PLATFORM_SYNC_ANSWER
    )
    assert "默认每 60 秒拉取一次" in _RESTAURANT_PLATFORM_SYNC_ANSWER
    assert "菜品月聚合默认每 600 秒刷新" in _RESTAURANT_PLATFORM_SYNC_ANSWER
    assert "模拟平台只用于测试和演示" in _RESTAURANT_PLATFORM_SYNC_ANSWER
    assert "默认每 6 小时刷新" in _RESTAURANT_PLATFORM_SYNC_ANSWER
    assert "不做部分写入" in _RESTAURANT_PLATFORM_SYNC_ANSWER
    assert "不终断订单增量同步" not in _RESTAURANT_PLATFORM_SYNC_ANSWER
    assert "不中断订单增量同步" in _RESTAURANT_PLATFORM_SYNC_ANSWER


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
    assert "7. 在 Workflow 画布配置并激活 BOM / 配方" in titles
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
    assert "配置格是 BOM 叠层，不是新的 Workflow 拓扑节点" in current_sop
    assert "①投入物料/批次与投入数量 → ②工序执行" in current_sop
    assert "包装包材批次" in current_sop
    assert "重复提交同一创建请求只能返回同一张计划" in current_sop
    assert "小结成功后，本轮消耗必须标记已结算" in current_sop
    assert "已发布并启用" in current_sop
    assert "采购包装单位 → 库存基本单位" in current_sop
    assert "多产出实际报工与成本分摊合同" in current_sop
    assert "多产出完工入库逐行合同" in current_sop
    assert "BOM“副产”页签只声明可回收副产 SKU 与预计量" in current_sop
    assert "不进投入成本池" in current_sop
    assert "没有辅料或包材本身不是阻塞" in current_sop
    assert "只在本次报工表单要求填写各实际产出的分摊比例" in current_sop
    assert "所有照片都必须进入人工审核" in current_sop
    assert "盒子、白标和彩标三层参考框" in current_sop
    assert "kg/g 等质量单位只在同量纲内科学换算" in current_sop
    assert "每单位成品重量" in current_sop
    assert "当前 OA 节点明确只授权 `factory_super_admin`" in current_sop
    assert "RN 仓库角色入口与 Web 业务边界" in current_sop
    assert "一个来源一行" in current_sop
    assert "可用库存和短缺数量以服务端校验为准" in current_sop
    assert "供应商可维护简称，以及多联系人、多地址和银行账户" in current_sop
    assert "单据追踪" in current_sop
    assert "BOM_PRODUCT_MISSING" in current_sop
    assert "[{batchNo, qty}]" in current_sop
    assert "点击“开工”时" in current_sop
    assert "PROGRESS" in current_sop
    assert "PENDING_WAREHOUSE_RECEIPT" in current_sop
    assert "基本类型是必填业务属性" in current_sop
    assert "L1/L2/L3 分类是可选辅助信息" in current_sop
    assert "不再生成或要求 16 位分类业务编码" in current_sop
    assert "已删除料号也不能回收复用" in current_sop
    assert "翻页过程中已见类别只增不减" in current_sop
    assert "生产批次只允许 `WORKFLOW` 路线" in current_sop
    assert "申请、审批和仓库实收是三个独立动作" in current_sop
    assert "客户来料必须选择有效客户" in current_sop
    assert "汇总量严格等于批次数量之和" in current_sop
    assert "`BY_STOCK` 小结形成的成品已经是有效、可用库存批次" in current_sop
    assert "调拨凭证只在收货确认后生成" in current_sop
    assert "归属读取失败时保持缺失并阻止" in current_sop
    assert "整条 Workflow 版本谱系自动重锚到该共享原料" in current_sop
    assert "每个工序至少保留一个产出 Cell" in current_sop
    assert "有生效 BOM 但明细为空时只能说“还没配辅料/包材”" in current_sop
    assert "包材 Cell 位于成品上方并连向所属成品" in current_sop
    assert "旧启用版本不能把“自动同步并发布”自身误拦" in current_sop

    html_path = Path(PROJECT_ROOT) / "docs/manual/F006-production-full-chain-manual-test-sop.html"
    html = html_path.read_text(encoding="utf-8")
    assert required_sequence in html
    assert "origin/main · SOP sync 2026-08-12" in html
    assert "存放位置不等于“本图产出”" in html
    assert "中间 WIP 批次沿用同一生产计划身份" in html
    assert "先有完整 Workflow 草稿，再在同一画布配置 BOM" in html
    assert "配置格不改变 Workflow 节点或连线" in html
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
    assert "至少配置一项主原料" in html
    assert "没有可选包材或副产不阻止激活" in html
    assert "自动同步并发布" in html
    assert "多产出逐行确认" in html
    assert "所有照片都会进入人工审核" in html
    assert "盒子、白标、彩标三层参考框" in html
    assert "跨单位成品率" in html
    assert "单总监死锁" in html
    assert "RN 仓储页与 Web 生产页不要混说" in html
    assert "一个来源一行" in current_sop
    assert "需多少、可用多少、缺多少" in html
    assert "RN 查看今日/历史盘点并续录" in html
    assert "BOM_PRODUCT_MISSING" in html
    assert "旧“BOM/配方维护”菜单已退出日常入口" in html
    assert "当前只汇总包材" in html
    assert "实际完整成本仍从正式报工与结算形成" in html
    assert "配置格是 BOM 叠层，不是新的 Workflow 拓扑节点" in current_sop
    assert "[{batchNo, qty}]" in html
    assert "开工”必须同步建立生产批次" in html
    assert "PENDING_WAREHOUSE_RECEIPT" in html
    assert "使用短料号并保持不可复用" in html
    assert "L1 → L2 → L3 分类为可选辅助信息" in html
    assert "不再生成或要求 16 位分类业务编码" in html
    assert "已删除料号也不回收复用" in html
    assert "无采购订单入库与仓库任务工作台" in html
    assert "申请、审批和仓库实收是三个独立动作" in html
    assert "每个工序至少保留一个产出 Cell" in html
    assert "生效 BOM 明细为空只显示“还没配辅料/包材”" in html
    assert "包材 Cell 位于成品上方并连向所属成品" in html
    assert "旧版本不能把重发布自身误拦" in html
    assert "原料分流才把整条版本谱系自动重锚到该原料" in html


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
        "财务经理不显示出纳专属“付款”页",
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
            "默认查询全部门店",
            "范围：全部门店合计",
            "最近30天加权毛利率是多少",
            "最近30天哪个时段生意最好",
            "历史时段表现 resolver",
            "无时间戳记录不硬塞入夜宵",
            "全店比率处理",
            "折扣金额、折扣率与优惠构成",
            "数据库表/字段只进入工程侧缺口元数据",
            "指定区间、繁体范围词与输出偏好",
            "月度经营报告：预览、导出与数据截至时间",
            "AI 飞轮、菜品别名与人审边界",
            "计划预警：同一 QuerySpec 定时回放",
            "平台 connector、Gold 刷新与模拟数据边界",
            "指标选择、菜单目录与路由防漂移",
            "数据可用门槛与叙事接地",
            "永久坏单据写入租户隔离的死信记录",
            "默认每 6 小时刷新",
            "预测排班：门店、时段与未来范围",
            "盘点只认已完成",
            "今日营运台主动发现、峰值时段与行动建议",
            "五部门驾驶舱",
            "AI 工作台",
            "营销员提成",
            "当前页面只确认发现卡可见",
            "登记表是通用查询的单一真值",
            "是否答到所问只影响学习",
            "营收预测的历史回测误差",
            "只写简称或片段且唯一推断到一家时",
            "把新门店范围拼回原问题后再规划",
            "征询不是写操作",
        ),
        "restaurant-product-manual.html": (
            "当前 21 维综合分析目录",
            "全部门店是聚合范围",
            "默认查询全部门店",
            "范围：全部门店合计",
            "最近30天加权毛利率是多少",
            "最近30天食材成本占营收多少",
            "历史时段表现 resolver",
            "泛指“食材”不是具体食材维度",
            "折扣金额、折扣率与优惠构成",
            "数据库表/字段只进入工程侧元数据",
            "精确日期、范围词与输出偏好",
            "月度经营报告",
            "AI 飞轮运营台与菜品别名治理",
            "计划预警",
            "当前代码已验证的是客如云风格 connector",
            "当前指标与实体合同",
            "达到卡死阈值的永久坏单据进入租户隔离死信",
            "不是门店管理员在页面自助填写密钥",
            "不超过 3 项时提示覆盖不足",
            "默认每 6 小时刷新",
            "餐饮预测排班与调整边界",
            "主动发现与行动建议",
            "餐饮五部门",
            "AI 工作台",
            "营销员提成",
            "当前前端只确认发现卡可见",
            "登记表驱动的通用问答",
            "营收预测与历史回测误差",
            "简称或片段唯一推断到一家时先显示真实门店确认按钮",
            "不能丢成裸店名",
            "写判定与只读意图矛盾时就地 fail closed",
        ),
        "restaurant-metrics-glossary.html": (
            "21 维综合分析证据目录",
            "REAL / PROXY / SIMULATED / MISSING",
            "当前餐饮问答的指标与接地合同",
            "最近30天加权毛利率是多少",
            "最近30天折扣力度多大",
            "无时间戳记录不归入夜宵",
            "全店食材成本率",
            "折扣金额、折扣率和优惠构成",
            "数据库表/字段只进入工程侧元数据",
            "wastage_cost",
            "requisition_cost",
            "不能称为精确真实成本模型",
            "预测 FactBook",
            "只统计状态为 <code>COMPLETED</code>",
            "销量 × 单位贡献毛利",
            "今日营运台主动发现三态与接地行动建议",
            "AI 工作台",
            "营销员月度累计复购阶梯提成",
            "客户端未接入动作前不得宣称页面已能生成策划案",
            "历史回测误差约 ±X%",
            "经营断点",
            "简称或片段唯一推断到一家时也先给真实门店确认按钮",
            "读写意图边界",
            "本次 fail closed，不执行操作、不猜答案",
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
    assert "三层参考框与标签人工复核" in ai_assist
    assert "报工单位与跨单位成品率" in ai_assist
    assert "指定区间、全部門店与输出形态" in ai_assist
    assert "月报预览、导出与截至时间" in ai_assist
    assert "AI 飞轮与菜品别名怎么治理？" in ai_assist
    assert "计划预警与平台同步边界" in ai_assist
    assert "RN 仓库角色与 Web 业务边界" in ai_assist
    assert "投入来源、缺料与单据追踪" in ai_assist
    assert "指标口径与菜单目录裁决" in ai_assist
    assert "默认全店与当前限制" in ai_assist
    assert "范围：全部门店合计" in ai_assist
    assert "最近30天加权毛利率是多少" in ai_assist
    assert "最近30天哪个时段生意最好" in ai_assist
    assert "历史时段表现 resolver" in ai_assist
    assert "食材成本占营收多少" in ai_assist
    assert "折扣金额、折扣率与优惠构成" in ai_assist
    assert "折扣力度多大」仍未闭环" not in ai_assist
    assert "仍缺历史时段表现 resolver" not in ai_assist
    assert "数据库表/字段只进入工程侧元数据" in ai_assist
    assert "agg_supplier_price" not in ai_assist
    assert "actual_receive" not in ai_assist
    assert "7 节小课 · 约 12 分钟" in ai_assist
    assert "飞轮与人审边界" in ai_assist
    assert "不做计算" in ai_assist
    assert "开工、批次与完工入库链路" in ai_assist
    assert "预测排班的范围与调整边界" in ai_assist
    assert "默认每 6 小时刷新" in ai_assist
    assert "画布 BOM 与成本边界" in ai_assist
    assert "五部门与角色权限边界" in ai_assist
    assert "主动发现与接地行动建议" in ai_assist
    assert "<strong>五部门驾驶舱：</strong>" in ai_assist
    assert "AI 工作台" in ai_assist
    assert "营销员提成" in ai_assist
    assert "基本类型、可选分类与短料号" in ai_assist
    assert "无订单入库与库存归属" in ai_assist
    assert "登记表与通用问答边界" in ai_assist
    assert "营收预测与历史回测误差" in ai_assist
    assert "存放位置、本图产出与自动重锚" in ai_assist
    assert "产出门禁、版本状态与包材连线" in ai_assist
    assert "标准表格与门店匹配确认" in ai_assist
    assert "征询分析与写操作安全门" in ai_assist
    assert "把新门店范围拼回原问题后再规划" in ai_assist
    assert "简称或片段唯一匹配时也先显示真实门店确认按钮" in ai_assist
    assert "本次不执行操作也不猜答案" in ai_assist
    assert "<strong>四部门驾驶舱：</strong>" not in ai_assist


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
async def test_factory_current_sop_miss_does_not_fall_back_to_legacy_sources(
    monkeypatch,
):
    captured_calls = []

    class FakeRetriever:
        def is_ready(self):
            return True

        async def retrieve(self, **kwargs):
            captured_calls.append(kwargs)
            return []

    async def fake_call_chain(slot, payload, timeout):
        return {"choices": [{"message": {"content": "当前手册未检索到相关内容。"}}]}

    monkeypatch.setattr(
        manual_chat_module,
        "get_knowledge_retriever",
        lambda: FakeRetriever(),
    )
    monkeypatch.setattr("common.llm_router.call_chain", fake_call_chain)
    manual_chat_module._answer_cache.clear()

    response = await manual_chat_module.manual_chat(
        ManualChatRequest(
            question="标签质检复核台的三层参考框怎么用？",
            category="factory",
        )
    )

    assert len(captured_calls) == 1
    assert captured_calls[0]["source_names"] == [
        "f006-production-full-chain-sop.md"
    ]
    assert response["sources"] == []


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
            "报工投入是 kg、产出是盒时成品率怎么计算？",
            "factory",
            _REPORTING_UNIT_YIELD_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "标签质检复核台的盒子白标彩标参考框怎么用？",
            "factory",
            _LABEL_QC_REVIEW_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "手机里的仓库角色与电脑端业务角色边界是什么？",
            "factory",
            _FACTORY_RN_READONLY_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "会计期间发起关账后 OA APPROVE 或 REJECT 会怎样？",
            "factory",
            _FACTORY_ACCOUNTING_PERIOD_OA_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "逐道报工什么时候能选多个批次，过期库存和调料配方怎么处理？",
            "factory",
            _FACTORY_REPORTING_RUNTIME_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "逐道报工投入来源怎样选，服务端发现物料短缺怎么办？",
            "factory",
            _FACTORY_REPORTING_SOURCE_SHORTAGE_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "开工后生产批次、逐道报工和仓库确认入库怎样衔接？",
            "factory",
            _FACTORY_PRODUCTION_EXECUTION_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "副产怎样建 SKU、在 BOM 声明、报工落生产仓并由盘点确认抵扣？",
            "factory",
            _FACTORY_BYPRODUCT_LIFECYCLE_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "只、个、件和自定义单位怎么保存，SKU 大类为什么创建时必填？",
            "factory",
            _FACTORY_CURRENT_GATES_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "旧 BOM 菜单不见了，怎么从零建首版配方并发布？",
            "factory",
            _FACTORY_BOM_CANVAS_COST_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "Workflow 画布的 BOM 版本状态和包材 Cell 连线怎样验收？",
            "factory",
            _FACTORY_BOM_CANVAS_COST_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "Workflow 的存放位置为什么和本图产出不一样，怎样按产出反查？",
            "factory",
            _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "原料分流发布后 Workflow 存放位置和本图产出怎样自动重锚？",
            "factory",
            _FACTORY_WORKFLOW_OUTPUT_DIRECTORY_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "Workflow 缺 skuId、其它仓库过期批次和标签 ZIP 下载分别怎么处理？",
            "factory",
            _FACTORY_CURRENT_GATES_ANSWER,
            "f006-production-full-chain-sop.md",
        ),
        (
            "门店菜品不同月份继续追问时怎么保持时间范围？",
            "restaurant",
            _RESTAURANT_CONTEXT_SCOPE_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "门店简称唯一匹配时会直接查还是先确认按钮？",
            "restaurant",
            _RESTAURANT_CONTEXT_SCOPE_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "这个菜要不要下架，读写歧义时怎样处理？",
            "restaurant",
            _RESTAURANT_READ_WRITE_BOUNDARY_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "中餐能不能自动精确算出每一道菜的真实毛利？",
            "restaurant",
            _RESTAURANT_SINGLE_DISH_MARGIN_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "看 2026年7月1日到7月15日全部門店营收，给我表格",
            "restaurant",
            _RESTAURANT_QUERY_CONTRACT_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "餐饮排行为什么用 Markdown 表格，门店名匹配到多家会怎样？",
            "restaurant",
            _RESTAURANT_OUTPUT_CLARIFICATION_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "门店简称有歧义时，排行表格和候选按钮应如何展示？",
            "restaurant",
            _RESTAURANT_OUTPUT_CLARIFICATION_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "食材成本和人工成本用哪些费用名称分类？",
            "restaurant",
            _RESTAURANT_COST_CATEGORY_ANSWER,
            "restaurant-metrics-glossary.html",
        ),
        (
            "餐饮月报怎样预览和导出 XLSX 或 PDF，数据截至时间是什么？",
            "restaurant",
            _RESTAURANT_MONTHLY_REPORT_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "AI 飞轮运营台谁能使用，菜品别名候选可以自动生效吗？",
            "restaurant",
            _RESTAURANT_FLYWHEEL_GOVERNANCE_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "餐饮计划预警怎样复用 QuerySpec 和阈值？",
            "restaurant",
            _RESTAURANT_PLAN_ALERT_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "客如云 POS 怎么接入并自动同步？",
            "restaurant",
            _RESTAURANT_PLATFORM_SYNC_ANSWER,
            "restaurant-product-manual.html",
        ),
        (
            "损耗金额最高的食材按什么排序，金额轴没有时怎么办？",
            "restaurant",
            _RESTAURANT_METRIC_ENTITY_ANSWER,
            "restaurant-metrics-glossary.html",
        ),
        (
            "只有 POS 流水，没有领料损耗盘点时能回答什么，会按 0 算吗？",
            "restaurant",
            _RESTAURANT_DATA_AVAILABILITY_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "餐饮导览助手能替我计算门店毛利和损耗吗？",
            "restaurant",
            _RESTAURANT_GUIDE_BOUNDARY_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "损耗问题也会显示换范围按钮吗？",
            "restaurant",
            _RESTAURANT_SCOPE_ACTION_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "餐饮预测排班会根据什么数据，支持哪些时间范围和门店范围？",
            "restaurant",
            _RESTAURANT_STAFFING_SCOPE_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "餐饮 AI 实时经营指挥屏的传输状态和来源怎么读？",
            "restaurant",
            _RESTAURANT_LIVE_COMMAND_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "五部门驾驶舱分别看什么，盘点亏损金额怎样切换时间范围？",
            "restaurant",
            _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "AI 工作台和 AI 价值汇总从哪里进入？",
            "restaurant",
            _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "营销员提成和复购提成从哪里进入？",
            "restaurant",
            _RESTAURANT_DEPARTMENT_STOCKTAKE_ANSWER,
            "restaurant-full-chain-sop.html",
        ),
        (
            "谜题菜、损耗提示、几点最忙分别怎么理解？",
            "restaurant",
            _RESTAURANT_PROACTIVE_FINDINGS_ANSWER,
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
