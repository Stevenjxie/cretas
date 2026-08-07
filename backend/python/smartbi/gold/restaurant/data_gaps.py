"""「这项数据还没采集」—— G1 的 B 类归宿。

⛔ 存在的理由: 2026-08-07 prod 实测 15 个代表性问句, 归宿分布是
   **A=10 / B=0 / C=2 / D=4** —— **B 类完全为零**。系统从不说「这项数据没采集」,
   要么反问, 要么落进「天气、新闻这类外部信息不在我的数据范围内」。

   「哪个供应商报价最贵」就落在后者。那句话对用户是**误导**: 供应商报价不是天气,
   它是我们**打算支持、只是客户还没录**的东西。诚实的答案是
   「供应商报价目前是空的(agg_supplier_price 0 行), 录入后才能比价」。

判据(goal 原文): `B(诚实缺数据, **必须点名缺哪张表哪个字段**)`。
所以本模块的文案里一定带表名 —— 不带表名的「暂无数据」等于什么都没说。

🔴 **必须真查表, 不能硬编码「没数据」**。客户开始录入之后, 这里必须让路 ——
   把「数据没到」写死成常量, 就变成了另一种降级处理: 数据来了却还在说没有。
   查到**非空**时本模块返回 None, 调用方照旧走原来的出口。

⚠️ 这不是关键词路由。命中词只用来判断「用户问的是不是这件我们知道缺数据的事」,
   命中之后**仍然要查库**才决定说什么; 而且它只在**已经要走域外拒答**的那一步
   介入 —— 只可能把一句更糟的话(C)换成一句更准的话(B), 不会抢走任何能答的问题。
"""
from __future__ import annotations

import logging
from typing import Any, Dict, Optional, Tuple

logger = logging.getLogger(__name__)


class DataGap:
    """一处已知的数据缺口。"""

    __slots__ = ("terms", "table", "subject", "what_to_do")

    def __init__(self, terms: Tuple[str, ...], table: str, subject: str, what_to_do: str):
        self.terms = terms
        self.table = table
        self.subject = subject
        self.what_to_do = what_to_do


#: ⚠️ 只登记「我们打算支持、但客户还没录」的东西。
#: 天气/新闻那类**永远不在范围内**的不属于这里, 它们该继续落域外拒答。
_GAPS: Tuple[DataGap, ...] = (
    DataGap(
        terms=("供应商", "供货商", "报价", "比价", "采购价"),
        table="agg_supplier_price",
        subject="供应商报价",
        what_to_do="在「供应商进货录入」里录入各供应商的报价后，就能做比价和采购价异常分析",
    ),
    DataGap(
        terms=("实收", "收款差异", "平台抽成", "到账"),
        table="fact_pos_transaction.actual_receive",
        subject="实收金额",
        what_to_do="POS 对接时把实收金额一并回传后，才能算收款差异与平台抽成",
    ),
)


def _match(query: str) -> Optional[DataGap]:
    text = (query or "").strip()
    if not text:
        return None
    for gap in _GAPS:
        if any(term in text for term in gap.terms):
            return gap
    return None


async def _row_count(pool, factory_id: str, table: str) -> Optional[int]:
    """本租户在该表的行数; 查不动时返回 None(让调用方放弃, 不猜)。"""
    # 只取表名那一段 —— 登记表里允许写 `表.字段` 以便文案点名字段。
    relation = table.split(".", 1)[0]
    try:
        from smartbi.gold.queries import tenant_conn
        async with tenant_conn(pool, factory_id) as conn:
            # relation 来自本模块的**固定字面量**, 不接受外部输入, 故可安全内插。
            row = await conn.fetchrow(
                f"SELECT count(*)::int AS n FROM {relation} WHERE factory_id = $1",
                factory_id,
            )
        return int((row or {}).get("n") or 0)
    except Exception as exc:
        # 查不动就别说话 —— 说「没数据」可能是假话, 说「有数据」也是。
        logger.warning("[data-gaps] 无法确认 %s 是否为空, 放弃: %s", relation, exc)
        return None


async def honest_gap_answer(
    pool, factory_id: str, query: str,
) -> Optional[Dict[str, Any]]:
    """问的是已知缺口且该表**确实为空** → 返回 B 类文案; 否则 None。

    返回 None 的三种情况都必须让调用方照旧走原出口:
      · 问的不是登记在册的缺口
      · 表里**有数据**了(客户已经录) —— 这时说「没数据」就是降级处理
      · 查不动(连不上/表不存在) —— 猜任何一侧都是假话
    """
    gap = _match(query)
    if gap is None:
        return None
    count = await _row_count(pool, factory_id, gap.table)
    if count is None or count > 0:
        return None

    logger.info(
        "[data-gaps] 已知缺口命中且确实为空: subject=%s table=%s factory=%s",
        gap.subject, gap.table, factory_id,
    )
    return {
        "subject": gap.subject,
        "table": gap.table,
        # ⛔ 客户文案里**不放表名**。第一版放了 `agg_supplier_price`, prod 上渲染成
        #    「缺的是：``（本店 0 行）」—— `customer_text._INTERNAL_IDENTIFIER` 会抹掉
        #    内部标识符, 那是**刻意的**闸, 客户不该看到表名, 对店长它也毫无意义。
        #
        #    goal 要求 B 类「必须点名缺哪张表哪个字段」, 本意是**别说含糊的「暂无
        #    数据」**。所以拆成两侧: 用户看到的是**业务上那件事**(供应商报价)与
        #    具体行动; 表名进 `meta.missing_table` 给工程侧/交接看。两边都拿到了
        #    自己需要的精确度。
        "answer_text": (
            f"**{gap.subject}目前还没有数据**，所以这个问题现在算不出来。\n\n"
            f"- 现状：本店一条{gap.subject}记录都没有\n"
            f"- 怎么才能有：{gap.what_to_do}\n\n"
            "在那之前我不会用别的口径凑一个数给您。"
        ),
    }
