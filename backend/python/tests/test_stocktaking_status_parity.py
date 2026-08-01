"""盘点口径: 同一个 SQL 常量里读同一张表的**每一处**都要带同样的 status 过滤。

2026-08-01 实测缺陷: `_AGG_DAILY_TOTALS_SQL` 里盘点相关读 `fact_restaurant_stocktaking`
有**两处** —— 日期清单与聚合子查询。#2131 只给日期清单加了 `status = 'COMPLETED'`,
聚合那处没加。

后果: 日期清单只能剔掉「当天一张 COMPLETED 都没有」的日期(R_XMX_CHAIN 正是这种,
所以那轮 prod 验证过了)。**混合日期**(当天既有已完成又有未完成)会通过日期清单,
然后在聚合里把未完成单一起算进去 —— 而同 PR 的 migration
`V20261101_07` 用的是 `WHERE status = 'COMPLETED'`。

于是两边对同一天算出不同的数, **正是那个 migration 注释自己警告的表现:
「跑完 ETL 数字自己变了」**。

prod 实证(2026-08-01): F002 在 2026-03-04 与 2026-04-21 各有 1 张 COMPLETED +
1 张非 COMPLETED。金额恰好都是 0 所以看不出来, 但 `stocktaking_count` 立刻分叉:
migration 写 1, ETL 写 2。F002 现有 COMPLETED 7 / IN_PROGRESS 2 / CANCELLED 1,
以后只要有一张未完成单带负差异, 金额也会跟着分叉。

⛔ 判据按**承载点计数**而不是「有没有出现过 status」—— 后者只要一处有就绿, 而
「一个闸多处承载、只改一处」正是本仓反复踩的形状。
"""
from __future__ import annotations

import re

from smartbi.gold.restaurant import restaurant_ops_etl as etl

_TABLE = "fact_restaurant_stocktaking"


def _read_sites(sql: str):
    """返回 SQL 里每一处 `FROM fact_restaurant_stocktaking ...` 之后到子句结束的片段。"""
    out = []
    for m in re.finditer(rf"FROM\s+{_TABLE}\b", sql):
        # 取到下一个 GROUP BY / 右括号 / 换行后的 UNION 为止, 足够看到 WHERE 子句
        tail = sql[m.end():m.end() + 400]
        stop = re.search(r"\bGROUP\s+BY\b|\n\s*\)|\bUNION\b", tail)
        out.append(tail[:stop.start()] if stop else tail)
    return out


def test_每日总量SQL里读盘点表的每一处都过滤COMPLETED():
    sites = _read_sites(etl._AGG_DAILY_TOTALS_SQL)
    assert len(sites) >= 2, (
        f"预期 _AGG_DAILY_TOTALS_SQL 至少有 2 处读 {_TABLE}(日期清单 + 聚合), "
        f"实际 {len(sites)} —— SQL 结构变了, 请重新确认本判据还守得住"
    )
    missing = [i for i, s in enumerate(sites) if "COMPLETED" not in s]
    assert not missing, (
        f"第 {missing} 处(0 基)读 {_TABLE} 时没有 status 过滤 —— "
        "与 migration V20261101_07 的 `WHERE status = 'COMPLETED'` 口径不一致, "
        "混合状态的日期上两边会算出不同的数(表现是「跑完 ETL 数字自己变了」)。\n"
        + "\n".join(f"  [{i}] {s.strip()[:160]}" for i, s in enumerate(sites))
    )


def test_按食材的两条聚合同样过滤COMPLETED():
    """反向对照: 这两条一直是对的, 本判据不该把它们判红。"""
    for name in ("_AGG_STOCK_SHORTAGE_SQL", "_AGG_STOCK_SHORTAGE_COST_SQL"):
        sql = getattr(etl, name)
        for i, site in enumerate(_read_sites(sql)):
            assert "COMPLETED" in site, f"{name} 第 {i} 处丢了 status 过滤"
