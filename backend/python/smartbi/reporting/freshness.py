"""「数据截至时间」的唯一来源 —— 直接问物化表要真实的最大业务日期。

spec 持续项要求「维度分级加数据截至时间，回答与预警都要明示」。报告是这条
要求最硬的落点: 报告会被存档、转发、在会上引用，读者没法像聊天那样追问一句
「这是到哪天的数」。

⛔ 这里**唯一允许失败的方式是抛异常**。查不到 = 这个租户没有物化好的经营
数据，此时任何报告都是编的。不回落 ``date.today()``、不回落「上次成功的
日期」、不返回 ``None`` 让上层"自己看着办"。
"""
from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

from .errors import ReportDataUnavailableError
from .model import DataFreshness, SourceFreshness

logger = logging.getLogger(__name__)

# (表名, 中文标签) —— 餐饮报告实际读到的两类 Gold 物化表。
# agg_daily: 营收/订单/客单价/渠道/门店维度 (queries.py 全家)
# agg_restaurant_daily_totals: 领料/损耗/盘点 (restaurant_ops_router 全家)
FRESHNESS_SOURCES: Tuple[Tuple[str, str], ...] = (
    ("agg_daily", "营收与订单"),
    ("agg_restaurant_daily_totals", "领料损耗盘点"),
)

_SPAN_SQL_TEMPLATE = """
SELECT MIN(date) AS min_date,
       MAX(date) AS max_date,
       COUNT(DISTINCT date) AS day_count
  FROM {table}
 WHERE factory_id = $1
"""


def _iso(value: Any) -> Optional[str]:
    if value is None:
        return None
    isoformat = getattr(value, "isoformat", None)
    if callable(isoformat):
        return isoformat()
    return str(value)


async def probe_sources(pool, factory_id: str) -> Tuple[SourceFreshness, ...]:
    """逐表探测真实日期跨度。

    表不存在 / 无权限 时该源记为「无数据」(``max_date=None``) 而不是让整个
    探测崩掉 —— 但注意这**不是**降级: 无数据的源不会贡献任何数字，且
    :func:`resolve_freshness` 在**所有**源都无数据时仍然 fail-closed。
    """
    out: List[SourceFreshness] = []
    async with pool.acquire() as conn:
        for table, label in FRESHNESS_SOURCES:
            try:
                row = await conn.fetchrow(
                    _SPAN_SQL_TEMPLATE.format(table=table), factory_id,
                )
            except Exception as exc:  # 表缺失 / 权限不足
                logger.warning(
                    "[monthly-report] freshness probe failed on %s: %s", table, exc,
                )
                row = None
            out.append(SourceFreshness(
                source=table,
                label=label,
                min_date=_iso(row["min_date"]) if row else None,
                max_date=_iso(row["max_date"]) if row else None,
                day_count=int(row["day_count"]) if row and row["day_count"] else 0,
            ))
    return tuple(out)


def combine(
    sources: Tuple[SourceFreshness, ...],
    *,
    generated_at: Optional[str] = None,
) -> DataFreshness:
    """把逐源结果折成报告页眉那一行；全部无数据则 fail-closed。"""
    populated = [s for s in sources if s.max_date]
    if not populated:
        raise ReportDataUnavailableError(
            "无法确定数据截至时间：本租户在 "
            + "、".join(s.source for s in sources)
            + " 中没有任何已物化的经营数据。为避免生成一份无法说明数据口径的"
            "报告，本次不生成任何文件。请先确认数据同步/ETL 是否已跑过。",
        )
    as_of = min(s.max_date for s in populated)  # 木桶原则, 见 DataFreshness 文档
    earliest_candidates = [s.min_date for s in populated if s.min_date]
    earliest = min(earliest_candidates) if earliest_candidates else None
    return DataFreshness(
        as_of_date=as_of,
        earliest_date=earliest,
        day_count=max(s.day_count for s in populated),
        generated_at=(generated_at or datetime.now().strftime("%Y-%m-%d %H:%M:%S")),
        sources=tuple(sources),
    )


async def resolve_freshness(
    pool,
    factory_id: str,
    *,
    generated_at: Optional[str] = None,
) -> DataFreshness:
    """报告层唯一的 freshness 入口。"""
    if pool is None:
        raise ReportDataUnavailableError(
            "数据库连接不可用，无法确认数据截至时间；本次不生成报告文件。",
        )
    return combine(await probe_sources(pool, factory_id), generated_at=generated_at)


def freshness_meta(freshness: DataFreshness) -> Dict[str, Any]:
    return freshness.to_dict()
