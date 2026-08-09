"""时段（daypart）切分 —— **唯一定义处**。

⛔ 时段边界只能在这里定义一次。`staffing_forecast.py` 的预测排班与
`restaurant_ops_router.resolve_daypart_performance` 的历史时段表现都要按同一套
边界切，否则「晚市」在两个页面上会是两段不同的时间，而用户看到的是同一个词。

📌 边界取自 `staffing_forecast.py` 原有的 CASE（2026-08-07 抽出共用时逐字保留），
不是新定的口径 —— 抽取的意义就在于**不引入第二套边界**。

⚠️ `time IS NOT NULL` 必须由调用方在 WHERE 里带上：`EXTRACT(HOUR FROM NULL)`
返回 NULL，会全部落进 ELSE 分支被算成「夜宵」—— 那不是夜宵，是没时间戳。
"""
from __future__ import annotations

#: 时段切分的 SQL 片段。调用方直接内插进自己的 SELECT。
#: 依赖列：`time`（timestamp）。
DAYPART_CASE_SQL = """CASE
             WHEN EXTRACT(HOUR FROM time) BETWEEN 10 AND 13 THEN '午市'
             WHEN EXTRACT(HOUR FROM time) BETWEEN 14 AND 16 THEN '下午茶'
             WHEN EXTRACT(HOUR FROM time) BETWEEN 17 AND 20 THEN '晚市'
             ELSE '夜宵'
           END"""

#: 展示顺序（营业时间先后），与切分边界一致。
#: 结果排序按业务量走，这个顺序只用于「没有数据的时段也要列出来」的场合。
DAYPART_ORDER = ("午市", "下午茶", "晚市", "夜宵")
