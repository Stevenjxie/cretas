"""Controlled canonical field dictionary — single source of truth (Phase 0).

每个业务域的 canonical ``standard_name`` 白名单 + 同义词。SemanticMapper 的目标空间
就是这里:规则层用 ``synonyms`` 匹配,LLM 兜底输出**必须**落在 ``ALL_CANONICAL_FIELDS``
内,否则进复核队列(不再自由生成 ``数量金额_N`` 垃圾)。

迁移自原 ``semantic_mapper.py:STANDARD_FIELDS``(财务向,忠实保留 synonyms),按域重组并
补 生产/销售/采购/库存。**这是唯一事实源** —— ``semantic_mapper.py`` 从此 import
``STANDARD_FIELDS``(= 全域合并),不再各自维护一份(避免漂移)。

结构: ``{standard_name: {"synonyms": [...], "category": "...", "description": "..."}}``
category ∈ {amount, rate, quantity, category, time, id}
"""
from __future__ import annotations

# ── 共享维度(各域通用,合并时只定义一次)──────────────────────────────
COMMON_FIELDS: dict[str, dict] = {
    "period": {
        "synonyms": ["期间", "Period", "月份", "日期", "时间"],
        "category": "time", "description": "Time period",
    },
    "category": {
        "synonyms": ["项目", "科目", "Category", "类别", "分类", "项目名称"],
        "category": "category", "description": "Category or line item name",
    },
    "department": {
        "synonyms": ["部门", "Department", "组织", "事业部", "中心"],
        "category": "category", "description": "Department or organization unit",
    },
    "product": {
        "synonyms": ["产品", "Product", "商品", "产品名称", "品类"],
        "category": "category", "description": "Product name",
    },
    "region": {
        "synonyms": ["区域", "Region", "地区", "区域名称", "销售区域"],
        "category": "category", "description": "Region name",
    },
}

# ── 财务(忠实迁移自原 STANDARD_FIELDS,synonyms 一字不改)────────────────
FINANCE_FIELDS: dict[str, dict] = {
    "budget_amount": {"synonyms": ["预算数", "预算金额", "预算", "Budget", "budget_amount", "预算值"], "category": "amount", "description": "Budget amount"},
    "actual_amount": {"synonyms": ["本月实际", "实际数", "实际金额", "实际", "Actual", "actual_amount", "实际值", "当月实际"], "category": "amount", "description": "Actual amount"},
    "variance": {"synonyms": ["预实差异", "差异", "Variance", "variance", "差额", "预算差异"], "category": "amount", "description": "Variance between budget and actual"},
    "last_year_actual": {"synonyms": ["去年同期", "同期实际", "Last Year", "YoY", "去年实际", "上年同期"], "category": "amount", "description": "Last year's actual amount"},
    "ytd_actual": {"synonyms": ["累计实际", "YTD Actual", "累计", "本年累计", "年度累计"], "category": "amount", "description": "Year-to-date actual"},
    "ytd_budget": {"synonyms": ["累计预算", "YTD Budget", "本年累计预算", "年度累计预算"], "category": "amount", "description": "Year-to-date budget"},
    "achievement_rate": {"synonyms": ["达成率", "完成率", "Achievement Rate", "达成比例", "完成比例", "预算达成率"], "category": "rate", "description": "Achievement/completion rate"},
    "yoy_rate": {"synonyms": ["同比增长", "YoY Rate", "同比", "同比变化", "同比增幅"], "category": "rate", "description": "Year-over-year growth rate"},
    "mom_rate": {"synonyms": ["环比增长", "MoM Rate", "环比", "环比变化", "月环比"], "category": "rate", "description": "Month-over-month growth rate"},
    "gross_margin_rate": {"synonyms": ["毛利率", "Gross Margin", "销售毛利率"], "category": "rate", "description": "Gross margin rate"},
    "net_margin_rate": {"synonyms": ["净利率", "Net Margin", "销售净利率", "净利润率"], "category": "rate", "description": "Net margin rate"},
    "revenue": {"synonyms": ["销售收入", "收入", "Revenue", "营业收入", "主营业务收入"], "category": "amount", "description": "Revenue/Sales income"},
    "cost": {"synonyms": ["成本", "Cost", "销售成本", "营业成本", "主营业务成本"], "category": "amount", "description": "Cost"},
    "gross_profit": {"synonyms": ["毛利", "毛利润", "Gross Profit", "销售毛利"], "category": "amount", "description": "Gross profit"},
    "expense": {"synonyms": ["费用", "Expense", "营业费用", "销售费用", "管理费用"], "category": "amount", "description": "Expense"},
    "net_profit": {"synonyms": ["净利润", "Net Profit", "利润", "净利", "本期利润"], "category": "amount", "description": "Net profit"},
}

# ── 生产(evidence: field_definitions 实测 output_quantity/good_quantity/defect_rate/yield_rate)──
PRODUCTION_FIELDS: dict[str, dict] = {
    "output_quantity": {"synonyms": ["产出数量", "产量", "产出", "产出量", "完成数量"], "category": "quantity", "description": "Output quantity"},
    "good_quantity": {"synonyms": ["合格数量", "良品数", "合格数", "良品数量", "合格品数量"], "category": "quantity", "description": "Good/qualified quantity"},
    "defect_quantity": {"synonyms": ["不良数量", "缺陷数", "不合格数", "废品数量", "次品数量"], "category": "quantity", "description": "Defect quantity"},
    "defect_rate": {"synonyms": ["不良率", "缺陷率", "不合格率", "废品率"], "category": "rate", "description": "Defect rate"},
    "yield_rate": {"synonyms": ["出成率", "良率", "出品率", "成品率", "良品率"], "category": "rate", "description": "Yield rate"},
    "input_quantity": {"synonyms": ["投入数量", "投入量", "投料量", "原料投入"], "category": "quantity", "description": "Input quantity"},
    "batch_number": {"synonyms": ["批次号", "批号", "批次", "生产批次"], "category": "id", "description": "Batch number"},
    "work_order": {"synonyms": ["工单号", "生产工单", "工单", "工单编号"], "category": "id", "description": "Work order number"},
    "process_name": {"synonyms": ["工序", "工序名称", "工艺", "工艺名称", "加工工序"], "category": "category", "description": "Process name"},
    "labor_hours": {"synonyms": ["工时", "工时数", "人工工时", "工时(分钟)", "工作时长"], "category": "quantity", "description": "Labor hours"},
    "production_date": {"synonyms": ["生产日期", "报工日期", "加工日期", "生产时间"], "category": "time", "description": "Production date"},
}

# ── 销售 ──────────────────────────────────────────────────────────
SALES_FIELDS: dict[str, dict] = {
    "sales_amount": {"synonyms": ["销售额", "销售金额", "营业额", "销售收入额"], "category": "amount", "description": "Sales amount"},
    "sales_quantity": {"synonyms": ["销售量", "销量", "销售数量", "出货数量"], "category": "quantity", "description": "Sales quantity"},
    "unit_price": {"synonyms": ["单价", "销售单价", "售价", "单位价格"], "category": "amount", "description": "Unit price"},
    "customer_name": {"synonyms": ["客户", "客户名称", "客户名", "购货方"], "category": "category", "description": "Customer name"},
    "order_number": {"synonyms": ["订单号", "销售单号", "订单编号", "销售订单号"], "category": "id", "description": "Order number"},
    "sales_date": {"synonyms": ["销售日期", "开单日期", "下单日期", "成交日期"], "category": "time", "description": "Sales date"},
    "discount_amount": {"synonyms": ["折扣", "优惠", "折让金额", "优惠金额", "折扣金额"], "category": "amount", "description": "Discount amount"},
}

# ── 采购 ──────────────────────────────────────────────────────────
PURCHASE_FIELDS: dict[str, dict] = {
    "purchase_amount": {"synonyms": ["采购金额", "采购额", "进货金额"], "category": "amount", "description": "Purchase amount"},
    "purchase_quantity": {"synonyms": ["采购量", "采购数量", "进货数量", "采购数"], "category": "quantity", "description": "Purchase quantity"},
    "purchase_price": {"synonyms": ["采购单价", "进价", "采购价", "进货单价"], "category": "amount", "description": "Purchase unit price"},
    "supplier_name": {"synonyms": ["供应商", "供应商名称", "供方", "供货商", "供应商名"], "category": "category", "description": "Supplier name"},
    "purchase_date": {"synonyms": ["采购日期", "进货日期", "送货日期", "采购时间"], "category": "time", "description": "Purchase date"},
    "po_number": {"synonyms": ["采购单号", "PO号", "采购订单号", "采购单编号"], "category": "id", "description": "Purchase order number"},
}

# ── 库存 ──────────────────────────────────────────────────────────
INVENTORY_FIELDS: dict[str, dict] = {
    "stock_quantity": {"synonyms": ["库存数量", "库存量", "结存数量", "现存量", "库存数"], "category": "quantity", "description": "Stock quantity"},
    "stock_amount": {"synonyms": ["库存金额", "结存金额", "存货金额"], "category": "amount", "description": "Stock amount"},
    "material_name": {"synonyms": ["物料", "物料名称", "原料", "原材料", "材料名称"], "category": "category", "description": "Material name"},
    "warehouse": {"synonyms": ["仓库", "仓位", "库位", "仓库名称", "存放位置"], "category": "category", "description": "Warehouse / location"},
    "turnover_rate": {"synonyms": ["周转率", "库存周转率", "周转次数"], "category": "rate", "description": "Inventory turnover rate"},
    "safety_stock": {"synonyms": ["安全库存", "最低库存", "安全存量", "再订购点"], "category": "quantity", "description": "Safety stock"},
    "expiry_date": {"synonyms": ["到期日期", "有效期", "保质期", "失效日期"], "category": "time", "description": "Expiry date"},
}

# ── 域 → 受控字典(每域 = 共享维度 + 域专属)────────────────────────────
DOMAIN_FIELDS: dict[str, dict] = {
    "finance": {**COMMON_FIELDS, **FINANCE_FIELDS},
    "production": {**COMMON_FIELDS, **PRODUCTION_FIELDS},
    "sales": {**COMMON_FIELDS, **SALES_FIELDS},
    "purchase": {**COMMON_FIELDS, **PURCHASE_FIELDS},
    "inventory": {**COMMON_FIELDS, **INVENTORY_FIELDS},
}

# ── 全域合并(单一事实源;semantic_mapper.py import 这个替代旧 STANDARD_FIELDS)──
STANDARD_FIELDS: dict[str, dict] = {
    **COMMON_FIELDS,
    **FINANCE_FIELDS,
    **PRODUCTION_FIELDS,
    **SALES_FIELDS,
    **PURCHASE_FIELDS,
    **INVENTORY_FIELDS,
}

# LLM 兜底输出验证白名单(不在此集合 → 进复核队列,不写库)
ALL_CANONICAL_FIELDS: frozenset[str] = frozenset(STANDARD_FIELDS.keys())


def get_standard_fields(domain: str | None = None) -> dict[str, dict]:
    """返回指定域的受控字典,或全域合并(domain=None,等价旧 STANDARD_FIELDS)。"""
    if domain and domain in DOMAIN_FIELDS:
        return DOMAIN_FIELDS[domain]
    return STANDARD_FIELDS
