"""报告的中间数据结构 —— 渲染器 (xlsx/pdf) 的唯一输入。

刻意做成 frozen dataclass 且**只装已经算好的值**: 报告层不持有 pool、不持有
spec、不能再去补一次数。这样「渲染阶段偷偷补个数」在结构上就不可能发生。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, Optional, Tuple


@dataclass(frozen=True)
class SourceFreshness:
    """单个 Gold 物化表的真实日期跨度。"""

    source: str
    label: str
    min_date: Optional[str]
    max_date: Optional[str]
    day_count: int

    def to_dict(self) -> Dict[str, Any]:
        return {
            "source": self.source,
            "label": self.label,
            "min_date": self.min_date,
            "max_date": self.max_date,
            "day_count": self.day_count,
        }


@dataclass(frozen=True)
class DataFreshness:
    """数据截至时间 —— 报告的诚实底线 (spec 持续项)。

    ``as_of_date`` 是**业务数据**的最后一天，不是报告生成时间；两者都必须
    出现在报告里，用户要能区分「数据只到 6/28」和「报告是 7/29 生成的」。

    多数据源时 ``as_of_date`` 取各源最大日期中的**最小值**（木桶原则）:
    营收表更新到 6/30 而损耗表只到 6/25 时，声称"数据截至 6/30"会让读者以为
    损耗那一节也覆盖到 6/30。逐源明细放在 ``sources`` 里一并印进报告，不藏。
    """

    as_of_date: str
    earliest_date: Optional[str]
    day_count: int
    generated_at: str
    sources: Tuple[SourceFreshness, ...] = ()

    def as_line(self) -> str:
        """报告页眉/页脚上的一行中文说明。"""
        return (
            f"数据截至时间：{self.as_of_date}"
            f"（覆盖 {self.day_count} 天，最早 {self.earliest_date or '—'}）"
            f"；报告生成时间：{self.generated_at}"
        )

    def source_lines(self) -> Tuple[str, ...]:
        return tuple(
            f"{s.label}（{s.source}）数据截至 {s.max_date or '无数据'}"
            f"，共 {s.day_count} 天"
            for s in self.sources
        )

    def to_dict(self) -> Dict[str, Any]:
        return {
            "as_of_date": self.as_of_date,
            "earliest_date": self.earliest_date,
            "day_count": self.day_count,
            "generated_at": self.generated_at,
            "sources": [s.to_dict() for s in self.sources],
        }


@dataclass(frozen=True)
class TableBlock:
    """一张可直接落到 xlsx sheet / pdf Table 的二维表。"""

    title: str
    columns: Tuple[str, ...]
    rows: Tuple[Tuple[Any, ...], ...]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "title": self.title,
            "columns": list(self.columns),
            "rows": [list(r) for r in self.rows],
        }


@dataclass(frozen=True)
class KpiBlock:
    """执行链返回的 kpi 卡片，原样透传 (不重算、不补齐)。"""

    title: str
    value: str

    def to_dict(self) -> Dict[str, Any]:
        return {"title": self.title, "value": self.value}


@dataclass(frozen=True)
class ReportSection:
    """一节 = 一个已执行的 sealed 计划的呈现结果。

    ``query`` / ``plan_hash`` / ``executed_resolvers`` 一起构成可追溯性:
    看到报告里某个数字有疑问，可以拿 ``query`` 原样再问一次机器人，得到的
    应该是同一个计划 (同一个 ``plan_hash``) 的同一个答案。
    """

    key: str
    heading: str
    query: str
    answer_text: str
    kpis: Tuple[KpiBlock, ...] = ()
    tables: Tuple[TableBlock, ...] = ()
    plan_hash: Optional[str] = None
    executed_resolvers: Tuple[str, ...] = ()
    code: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "key": self.key,
            "heading": self.heading,
            "query": self.query,
            "answer_text": self.answer_text,
            "kpis": [k.to_dict() for k in self.kpis],
            "tables": [t.to_dict() for t in self.tables],
            "plan_hash": self.plan_hash,
            "executed_resolvers": list(self.executed_resolvers),
            "code": self.code,
        }


@dataclass(frozen=True)
class MonthlyReport:
    """一份完整的、每一节都拿到了真实数据的月度报告。

    构造出这个对象本身就是「全部成功」的证明 —— :mod:`.runner` 只有在零失败
    时才会返回它，否则抛异常。渲染器因此不需要处理「某节缺数」的分支。
    """

    factory_id: str
    template_code: str
    title: str
    period_label: str
    freshness: DataFreshness
    sections: Tuple[ReportSection, ...]
    meta: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "factory_id": self.factory_id,
            "template_code": self.template_code,
            "title": self.title,
            "period_label": self.period_label,
            "freshness": self.freshness.to_dict(),
            "sections": [s.to_dict() for s in self.sections],
            "meta": dict(self.meta),
        }
