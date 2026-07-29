"""pdf 渲染 (reportlab)。

中文字体注册沿用 ``printing/services/pdf_renderer.py`` 已验证的候选路径列表
（Linux wqy/Noto、macOS PingFang、Windows msyh/simhei）。

版式: 标题 → **数据截至时间横幅**（正文第一块，翻开就看得到）→ 逐源明细 →
每节（标题 / 结论 / KPI / 表格）→ 每页页脚重复一次数据截至时间。

⛔ 表格里的 ``None`` 渲染成 ``—``（破折号，视觉上明确的"无"），不是 ``0``。
"""
from __future__ import annotations

import logging
from io import BytesIO
from typing import Any, List, Optional

from ..model import MonthlyReport

logger = logging.getLogger(__name__)

_CHINESE_FONT: Optional[str] = None

_FONT_CANDIDATES = (
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    "/System/Library/Fonts/PingFang.ttc",
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simhei.ttf",
)


def _register_chinese_font() -> str:
    """注册中文字体；与 printing/services/pdf_renderer.py 同一套候选路径。"""
    global _CHINESE_FONT
    if _CHINESE_FONT is not None:
        return _CHINESE_FONT
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont

    for path in _FONT_CANDIDATES:
        try:
            pdfmetrics.registerFont(TTFont("CretasReportCJK", path))
            _CHINESE_FONT = "CretasReportCJK"
            return _CHINESE_FONT
        except Exception:
            continue
    logger.warning(
        "[monthly-report] 无中文字体可用，PDF 中文会显示为 □ —— "
        "部署机器请安装 wqy-zenhei 或 Noto-CJK",
    )
    _CHINESE_FONT = "Helvetica"
    return _CHINESE_FONT


def _cell_text(value: Any) -> str:
    """``None`` → ``—``。刻意不是 ``0``：读者必须能分辨「无数据」和「零」。"""
    if value is None:
        return "—"
    if isinstance(value, float):
        return f"{value:,.2f}"
    return str(value)


def _styles():
    from reportlab.lib.colors import HexColor
    from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet

    font = _register_chinese_font()
    base = getSampleStyleSheet()
    return font, {
        "title": ParagraphStyle(
            "RptTitle", parent=base["Title"], fontName=font, fontSize=20,
            textColor=HexColor("#111827"), spaceAfter=10, alignment=1,
        ),
        "asof": ParagraphStyle(
            "RptAsOf", parent=base["Normal"], fontName=font, fontSize=11,
            textColor=HexColor("#b45309"), leading=16, alignment=1, spaceAfter=10,
        ),
        "h2": ParagraphStyle(
            "RptH2", parent=base["Heading2"], fontName=font, fontSize=13,
            textColor=HexColor("#111827"), spaceBefore=12, spaceAfter=6,
        ),
        "body": ParagraphStyle(
            "RptBody", parent=base["Normal"], fontName=font, fontSize=10,
            textColor=HexColor("#1f2937"), leading=15,
        ),
        "small": ParagraphStyle(
            "RptSmall", parent=base["Normal"], fontName=font, fontSize=8,
            textColor=HexColor("#6b7280"), leading=11,
        ),
    }


def _escape(text: str) -> str:
    return (
        str(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def _table(font, columns, rows, col_width_hint: float):
    from reportlab.lib import colors
    from reportlab.platypus import Table, TableStyle

    data = [[_cell_text(c) for c in columns]]
    data.extend([[_cell_text(v) for v in row] for row in rows])
    table = Table(data, repeatRows=1, colWidths=[col_width_hint] * len(columns))
    table.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), font),
        ("FONTSIZE", (0, 0), (-1, -1), 8),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#f3f4f6")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.HexColor("#111827")),
        ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#d1d5db")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("RIGHTPADDING", (0, 0), (-1, -1), 4),
    ]))
    return table


def render_pdf(report: MonthlyReport) -> bytes:
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.units import cm
    from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer

    font, st = _styles()
    buf = BytesIO()
    doc = SimpleDocTemplate(
        buf, pagesize=A4,
        leftMargin=1.6 * cm, rightMargin=1.6 * cm,
        topMargin=1.6 * cm, bottomMargin=1.6 * cm,
        title=report.title,
    )
    usable = A4[0] - 3.2 * cm

    footer_line = f"数据截至时间：{report.freshness.as_of_date}｜{report.title}"

    def _footer(canvas, _doc):
        canvas.saveState()
        canvas.setFont(font, 7.5)
        canvas.setFillColorRGB(0.42, 0.45, 0.5)
        canvas.drawString(1.6 * cm, 1.0 * cm, footer_line)
        canvas.drawRightString(A4[0] - 1.6 * cm, 1.0 * cm, f"第 {canvas.getPageNumber()} 页")
        canvas.restoreState()

    flow: List[Any] = [
        Paragraph(_escape(report.title), st["title"]),
        # ⬇⬇ 数据截至时间: PDF 第 1 页标题正下方的橙色横幅
        Paragraph(_escape(report.freshness.as_line()), st["asof"]),
    ]
    for line in report.freshness.source_lines():
        flow.append(Paragraph("· " + _escape(line), st["small"]))
    flow.append(Spacer(1, 0.3 * cm))
    flow.append(Paragraph(
        _escape(
            f"周期区间 {report.meta.get('period_span', '')}｜租户 {report.factory_id}"
            f"｜模板 {report.template_code}"
        ),
        st["small"],
    ))
    flow.append(Spacer(1, 0.4 * cm))
    flow.append(Paragraph("报告目录", st["h2"]))
    flow.append(_table(
        font,
        ("节", "标题", "原始问句"),
        [(s.key, s.heading, s.query) for s in report.sections],
        usable / 3,
    ))

    for section in report.sections:
        flow.append(PageBreak())
        flow.append(Paragraph(_escape(section.heading), st["h2"]))
        flow.append(Paragraph(
            _escape(f"原始问句：{section.query}"), st["small"],
        ))
        if section.plan_hash:
            flow.append(Paragraph(
                _escape(f"计划指纹：{section.plan_hash}"), st["small"],
            ))
        flow.append(Spacer(1, 0.25 * cm))
        for para in str(section.answer_text).split("\n"):
            if para.strip():
                flow.append(Paragraph(_escape(para), st["body"]))
            else:
                flow.append(Spacer(1, 0.15 * cm))
        if section.kpis:
            flow.append(Spacer(1, 0.3 * cm))
            flow.append(_table(
                font,
                tuple(k.title for k in section.kpis),
                [tuple(k.value for k in section.kpis)],
                usable / max(len(section.kpis), 1),
            ))
        for table in section.tables:
            flow.append(Spacer(1, 0.35 * cm))
            flow.append(Paragraph(_escape(table.title), st["body"]))
            flow.append(Spacer(1, 0.15 * cm))
            flow.append(_table(
                font, table.columns, table.rows,
                usable / max(len(table.columns), 1),
            ))

    doc.build(flow, onFirstPage=_footer, onLaterPages=_footer)
    return buf.getvalue()
