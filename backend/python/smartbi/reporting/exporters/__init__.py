"""报告文件渲染器 —— xlsx / pdf。

渲染器的输入只有 :class:`~smartbi.reporting.model.MonthlyReport`，拿不到
pool 也拿不到 spec，**结构上不可能在渲染阶段补一个数**。
"""
from __future__ import annotations

from .pdf import render_pdf
from .xlsx import render_xlsx

__all__ = ["render_pdf", "render_xlsx"]
