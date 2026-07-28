"""顶层编排: 执行计划 → 渲染文件字节。

调用方（HTTP 端点 / cron 任务）只需要这一个函数。它返回**文件字节 + 元信息**，
不落盘、不上传 —— 存储策略由调用方决定，报告层不做副作用。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Dict, Optional, Tuple

from .errors import ReportGenerationError
from .exporters import render_pdf, render_xlsx
from .model import DataFreshness, MonthlyReport
from .runner import build_monthly_report
from .template import ReportTemplate, get_template

REPORT_FORMATS: Tuple[str, ...] = ("xlsx", "pdf")

_CONTENT_TYPES = {
    "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pdf": "application/pdf",
}

_FILENAME_SAFE = re.compile(r"[^0-9A-Za-z一-鿿._-]+")


@dataclass(frozen=True)
class RenderedReport:
    filename: str
    content_type: str
    content: bytes
    report: MonthlyReport

    def meta(self) -> Dict[str, Any]:
        return {
            "filename": self.filename,
            "content_type": self.content_type,
            "size_bytes": len(self.content),
            "as_of_date": self.report.freshness.as_of_date,
            "generated_at": self.report.freshness.generated_at,
            "period": self.report.meta.get("period"),
            "template_code": self.report.template_code,
            "sections": [
                {
                    "key": s.key,
                    "heading": s.heading,
                    "query": s.query,
                    "plan_hash": s.plan_hash,
                    "executed_resolvers": list(s.executed_resolvers),
                }
                for s in self.report.sections
            ],
        }


def _filename(report: MonthlyReport, fmt: str) -> str:
    stem = f"{report.factory_id}_{report.meta.get('period', '')}_月度经营报告"
    return f"{_FILENAME_SAFE.sub('_', stem)}.{fmt}"


async def generate_monthly_report_file(
    pool,
    factory_id: str,
    role: Optional[str] = None,
    *,
    fmt: str = "xlsx",
    period: Optional[str] = None,
    template_code: Optional[str] = None,
    template: Optional[ReportTemplate] = None,
    answer_fn=None,
    freshness: Optional[DataFreshness] = None,
) -> RenderedReport:
    """生成一份月度报告文件。

    任何一节拿不到可信数据 → 抛 :class:`~.errors.ReportGenerationError`，
    **不产生任何文件**。调用方应把它翻译成 ``{success: false, message}``。
    """
    fmt = (fmt or "xlsx").lower().strip()
    if fmt not in REPORT_FORMATS:
        raise ReportGenerationError(
            f"不支持的报告格式 {fmt!r}，可选：{', '.join(REPORT_FORMATS)}。",
            code="REPORT_BAD_FORMAT",
        )
    if template is None:
        try:
            tpl = get_template(template_code)
        except KeyError:
            raise ReportGenerationError(
                f"未知报告模板 {template_code!r}。",
                code="REPORT_UNKNOWN_TEMPLATE",
            ) from None
    else:
        tpl = template

    report = await build_monthly_report(
        pool, factory_id, role,
        template=tpl, period=period, answer_fn=answer_fn, freshness=freshness,
    )
    content = render_xlsx(report) if fmt == "xlsx" else render_pdf(report)
    return RenderedReport(
        filename=_filename(report, fmt),
        content_type=_CONTENT_TYPES[fmt],
        content=content,
        report=report,
    )
