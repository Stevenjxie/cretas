"""归因基线 —— 给任意窗口挑一个**不重叠**的对照期。

⛔ 本模块不取数、不算指标。它只回答一个问题：
**「跟什么比」**，以及**「那个『什么』叫什么名字」**。

## 为什么需要它

原来只有一条规则：把窗口整体前挪 7 天。对**单日**窗口没问题，
窗口一长基线就与主窗口**重叠**（实测）：

    今天(1 天)      重叠  0 天  ✅
    本周(7 天)      重叠  0 天  ✅
    本月至今(16 天) 重叠  9 天  🔴
    上个月(31 天)   重叠 24 天  🔴
    上季度(91 天)   重叠 84 天  🔴
    上半年(181 天)  重叠 174 天 🔴

▎重叠的「对比」两边几乎是同一批数据 —— 拆出来的主因是噪音假装成洞察，
▎**而且带着一个精确的数字**。⇒ 比不归因更糟。

## 裁定：默认**环比**（上一个同类日历窗口），⛔ 不默认同比

- 老板的心智模型是「这个月比上个月」，不是「比去年同月」
- 同比（去年同期）控住了季节性，但把一年的经营变化也算了进来
- ⚠️ 两者都不是「对的」——**所以基线必须写进文案**，让他自己判断
  （`decompose` 的调用方拿 `label` 去拼那句「跟 X 比」）

⛔ 不做「自动选更合适的基线」：那需要一个我们没有的判据，
   而选错方向的归因比不归因更糟。

## 规则（按窗口形状，⛔ 不按天数一刀切）

| 窗口 | 基线 | 名字 |
|---|---|---|
| 单日 | 上周同一天 | 上周同一天 |
| ≤7 天 | 前移同样天数 | 上一个 N 天 |
| 完整日历月 | 上一个完整月 | 上个月 |
| 月初至今 | 上月同一批日号 | 上月同期 |
| 完整季度 | 上一季度 | 上一季度 |
| 完整半年 | 上一个半年 | 上一个半年 |
| 其他 | 前移同样天数 | 上一个 N 天 |

🔴 **每一条出口都必须满足「与主窗口不重叠」** —— 那是本模块的承重墙，
由 `pick_baseline` 自己断言，⛔ 不指望调用方记得检查。
"""
import calendar
import datetime
from typing import Optional, Tuple

Date = datetime.date
Window = Tuple[Date, Date]


def _month_end(year: int, month: int) -> int:
    return calendar.monthrange(year, month)[1]


def _shift_months(d: Date, months: int) -> Date:
    """把日期前后挪若干个月，日号溢出时钳到月末（1-31 挪到 2 月 → 28/29）。"""
    total = (d.year * 12 + d.month - 1) + months
    year, month = divmod(total, 12)
    month += 1
    return Date(year, month, min(d.day, _month_end(year, month)))


def _is_full_month(start: Date, end: Date) -> bool:
    return (start.day == 1
            and end.day == _month_end(end.year, end.month)
            and start.year == end.year and start.month == end.month)


def _is_month_to_date(start: Date, end: Date) -> bool:
    return (start.day == 1
            and start.year == end.year and start.month == end.month
            and not _is_full_month(start, end))


def _is_full_quarter(start: Date, end: Date) -> bool:
    if start.day != 1 or start.month not in (1, 4, 7, 10):
        return False
    q_end_month = start.month + 2
    return (end.year == start.year and end.month == q_end_month
            and end.day == _month_end(end.year, q_end_month))


def _is_full_half(start: Date, end: Date) -> bool:
    if start.day != 1 or start.month not in (1, 7):
        return False
    end_month = start.month + 5
    return (end.year == start.year and end.month == end_month
            and end.day == _month_end(end.year, end_month))


def pick_baseline(start: Date, end: Date) -> Tuple[Optional[Window], str]:
    """给 ``[start, end]`` 挑一个**不重叠**的对照期。

    返回 ``((基线起, 基线止), 名字)``；挑不出来时返回 ``(None, 原因)``。

    🔴 出口前**自己断言不重叠** —— 那是这个模块存在的全部理由，
    ⛔ 不指望调用方记得检查（原来那条 -7 天规则就是没人检查才活下来的）。
    """
    if start > end:
        return None, "窗口起止颠倒"

    span = (end - start).days + 1

    if span == 1:
        base = (start - datetime.timedelta(days=7),) * 2
        label = "上周同一天"
    elif span <= 7:
        delta = datetime.timedelta(days=span)
        base = (start - delta, end - delta)
        label = f"上一个 {span} 天"
    elif _is_full_month(start, end):
        b_start = _shift_months(start, -1)
        base = (b_start, Date(b_start.year, b_start.month,
                              _month_end(b_start.year, b_start.month)))
        label = "上个月"
    elif _is_month_to_date(start, end):
        # ⚠️ 月初至今 → **上月同一批日号**，⛔ 不是「上月整月」：
        #    拿 16 天跟 31 天比，营收当然低一半，那不是经营变化。
        b_start = _shift_months(start, -1)
        b_end = _shift_months(end, -1)
        base = (b_start, b_end)
        label = "上月同期"
    elif _is_full_quarter(start, end):
        b_start = _shift_months(start, -3)
        b_end = _shift_months(start, -1)
        base = (b_start, Date(b_end.year, b_end.month,
                              _month_end(b_end.year, b_end.month)))
        label = "上一季度"
    elif _is_full_half(start, end):
        b_start = _shift_months(start, -6)
        b_end = _shift_months(start, -1)
        base = (b_start, Date(b_end.year, b_end.month,
                              _month_end(b_end.year, b_end.month)))
        label = "上一个半年"
    else:
        delta = datetime.timedelta(days=span)
        base = (start - delta, end - delta)
        label = f"上一个 {span} 天"

    b_start, b_end = base
    # 🔴 承重墙：不重叠。⛔ 不是「差不多不重叠」——重叠一天都说明规则错了。
    if b_end >= start:
        return None, (
            f"挑出的基线 {b_start}~{b_end} 与主窗口 {start}~{end} 重叠 "
            f"{(min(end, b_end) - max(start, b_start)).days + 1} 天 —— 规则有误")
    return base, label
