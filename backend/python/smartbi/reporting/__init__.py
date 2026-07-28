"""餐饮月度报告 —— 计划批量执行 + 模板渲染 + 文件导出 (spec §3.2)。

统一原则 R1: **「计划」是系统通用货币**。交互问答 / 缓存 / 晋升 / 预警 / 报告
执行的是同一种 sealed QuerySpec。所以这个包里**没有任何取数逻辑、没有任何
resolver、没有一句 SQL 业务查询** —— 报告的每一节都是把模板里的自然语言问句
交给 :func:`smartbi.gold.restaurant.restaurant_intent_service.tiered_answer`，
拿它已经算好的 ``answer_text`` / ``charts`` / ``kpis`` 来排版。

也就是说这个包**不是新引擎**，它只做三件事：

1. :mod:`.runner` —— 按模板批量执行计划 (调 ``tiered_answer``)，并取数据截至时间。
2. :mod:`.tabular` —— 把 ECharts 形状的 ``charts`` 折成表格 (无需新数据通道)。
3. :mod:`.exporters` —— 把结果渲染成 xlsx / pdf 文件。

⛔ 禁止降级处理 (项目核心原则 1): 任何一节拿不到数据、契约不通过、或者数据
截至时间未知，整份报告 **不生成**，抛 :class:`~.errors.ReportGenerationError`
并说清哪一节因为什么失败。绝不用 0 / "-" / 上期数 / 模拟数把报告填满 ——
报告是要发给老板看的，一个假数就废掉整个产品的可信度。
"""
from __future__ import annotations

from .errors import (
    ReportDataUnavailableError,
    ReportGenerationError,
    SectionFailure,
)
from .preference import (
    OUTPUT_FORM_REPORT_FILE,
    wants_report_file,
)
from .model import (
    DataFreshness,
    KpiBlock,
    MonthlyReport,
    ReportSection,
    TableBlock,
)
from .runner import build_monthly_report
from .service import (
    REPORT_FORMATS,
    generate_monthly_report_file,
)
from .template import (
    DEFAULT_MONTHLY_TEMPLATE,
    ReportTemplate,
    SectionTemplate,
    get_template,
    list_templates,
)

__all__ = [
    "DEFAULT_MONTHLY_TEMPLATE",
    "OUTPUT_FORM_REPORT_FILE",
    "DataFreshness",
    "KpiBlock",
    "MonthlyReport",
    "REPORT_FORMATS",
    "ReportDataUnavailableError",
    "ReportGenerationError",
    "ReportSection",
    "ReportTemplate",
    "SectionFailure",
    "SectionTemplate",
    "TableBlock",
    "build_monthly_report",
    "generate_monthly_report_file",
    "get_template",
    "list_templates",
    "wants_report_file",
]
