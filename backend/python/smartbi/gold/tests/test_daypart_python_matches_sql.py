"""时段切分的 Python 副本必须与 SQL 那唯一一处定义**逐时一致**。

`daypart.py` 的文件头写着「时段边界只能在这里定义一次」, 理由是同一个「晚市」
不能在两个页面上是两段不同的时间。生成器
(`ingestion/platforms/writer.py::_daypart_of`) 在 INSERT 时要落 `meal_period`,
它跑在 Python 里、拿不到那段 SQL —— 于是不可避免地有了第二份实现。

⇒ 这道闸的存在就是为了让那两份**不能悄悄分叉**: 从 `DAYPART_CASE_SQL` 里把边界
  解析出来, 拿 24 个小时逐个对。改了任何一侧而不改另一侧, 这里当场红。

⚠️ 顺带钉住 `daypart.py` 自己警告过的那个坑: 没有时间戳时 SQL 的
   `EXTRACT(HOUR FROM NULL)` 是 NULL, 会落进 ELSE 被算成「夜宵」。
   Python 侧必须返回 None, **不能重演同一个坑**。
"""
import re
from datetime import datetime

from smartbi.gold.restaurant.daypart import DAYPART_CASE_SQL, DAYPART_ORDER
from smartbi.ingestion.platforms.writer import _daypart_of


def _boundaries_from_sql():
    """从 SQL CASE 里解析出 (lo, hi, label) 与 ELSE 的 label。"""
    ranges = [
        (int(lo), int(hi), label)
        for lo, hi, label in re.findall(
            r"BETWEEN\s+(\d+)\s+AND\s+(\d+)\s+THEN\s+'([^']+)'", DAYPART_CASE_SQL
        )
    ]
    fallback = re.search(r"ELSE\s+'([^']+)'", DAYPART_CASE_SQL)
    assert ranges and fallback, "解析不出边界 —— SQL 改了写法, 这道闸要跟着改"
    return ranges, fallback.group(1)


def _sql_daypart(hour: int) -> str:
    ranges, fallback = _boundaries_from_sql()
    for lo, hi, label in ranges:
        if lo <= hour <= hi:
            return label
    return fallback


def test_every_hour_agrees_with_the_sql_definition():
    """24 小时逐个对 —— 只对几个样本点会漏掉边界那一小时。"""
    mismatched = [
        (h, _sql_daypart(h), _daypart_of(datetime(2026, 8, 8, h, 30)))
        for h in range(24)
        if _sql_daypart(h) != _daypart_of(datetime(2026, 8, 8, h, 30))
    ]
    assert not mismatched, f"Python 与 SQL 时段切分不一致 (小时, SQL, Python): {mismatched}"


def test_no_timestamp_is_not_midnight_supper():
    """🔴 没有时间戳 -> None, ⛔ 不是「夜宵」。

    SQL 侧靠调用方在 WHERE 里带 `time IS NOT NULL` 躲这个坑(见 daypart.py 文件头)。
    Python 侧没有那层保护, 只能自己返回 None。
    """
    assert _daypart_of(None) is None


def test_labels_are_exactly_the_declared_four():
    """产出的标签必须落在既有的四值闭集里, 不许冒出第五个词。"""
    produced = {_daypart_of(datetime(2026, 8, 8, h, 0)) for h in range(24)}
    assert produced == set(DAYPART_ORDER), produced
