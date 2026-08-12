"""日结 P0 —— 打烊那一屏：今天赚多少。

## ⛔ 承重约束：**写死的只有 spec，算法一行不新写**

日结的「毛利」如果另写一套算法，它会和问答的「毛利」漂 —— 形态 D，
而且是最贵的那种：**两个数字都对外，店长会问「为什么不一样」**。

所以这里只做一件事：**把一个固定的 spec 喂给现有的
`generic_executor` + `metric_registry` 执行链**。

    写死的：metric = 毛利 / 营收 · grain = 全店（`dimension="all"`）· time = 当日
    没写的：取数、口径、格式化、限定语、开价 —— 全部来自现有链路

## 为什么它不经过 planner

「今天赚多少」走通用问答会被 `_execution_mismatch` 判成「不确定你要看的是哪一层」
（粒度没有默认机制，已挂账进设计卡）。而日结是**固定形态**：粒度写死全店、
时间写死当日，**根本不需要 planner**，也就不会撞那道一致性校验。

⛔ 这不是绕过校验 —— 校验守的是「不许在执行期重新解释一个不可变的计划」，
   而这里压根没有 planner 产出的计划要守。

## 三段从哪来

| 段 | 来源 | 谁负责 |
|---|---|---|
| ① 数字 | `execute_cell` | 现有执行链 |
| ② 限定语 | `CellResult.provenance` → `generic_answer.render()` | 出处字段生成，不手写 |
| ③ 开价 | `fill_offers` | registry 反查算出来的 |

⚠️ ② 之所以会出现，是因为**毛利依赖成本卡那一列**（`_provenance_of` 递归展开
派生量得到的），不是因为这里手工标了「日结是估的」。
"""
from __future__ import annotations

import logging
from datetime import date
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

from smartbi.gold.restaurant.generic_answer import render
from smartbi.gold.restaurant.generic_executor import execute_cell

#: 日结那一屏要哪几个格子。**这就是「写死的 spec」的全部**。
#:
#: ⛔ 顺序有意义：店长打烊先看「今天卖了多少」，再看「赚了多少」。
#: ⚠️ 每个格子的 `(metric, dimension, aggregation)` 都必须是 registry 上登记过的
#:    组合 —— 不成立时 `execute_cell` 会抛 `UnsupportedCell`，那是**对的**：
#:    日结不该有自己的一套「万一算不出就凑一个」。
#: ⚠️ 聚合形态是 `summary` —— 它是 `AGGREGATIONS` 里**唯一** `needs_dimension=False`
#:    的那个, 也就是唯一能配 `dimension="all"`(全店合计)的。
#:    第一版我按直觉写了 `"total"`, 登记表里根本没有这个键, `execute_cell`
#:    当场 `UnsupportedCell` —— 而那是**对的**: 日结不该有自己的一套聚合名。
DAILY_CLOSE_CELLS: Tuple[Tuple[str, str, str], ...] = (
    ("revenue", "all", "summary"),
    ("orders", "all", "summary"),
    ("gross_profit", "all", "summary"),
)

#: 打烊那一屏的标题。⛔ 不说「日结」——那是我们的词，店长说「今天怎么样」。
DAILY_CLOSE_TITLE = "今天怎么样"


def daily_close_window(today: Optional[date] = None) -> Tuple[date, date]:
    """当日 —— 起止同一天。

    ⚠️ 不用「最近 1 天」：那会把昨天算进来。打烊看的是**今天**。
    """
    day = today or date.today()
    return (day, day)


async def build_daily_close(
    conn,
    *,
    factory_id: str,
    today: Optional[date] = None,
) -> Dict[str, Any]:
    """跑那个写死的 spec，返回打烊一屏。

    ⛔ 不做任何取数/口径/格式化 —— 全部交给 `execute_cell` 和 `render`。
       本函数的全部职责是「决定问哪几个格子」和「把结果拼成一屏」。
    """
    date_range = daily_close_window(today)
    sections: List[Dict[str, Any]] = []
    cells = []
    for metric_key, dimension_key, aggregation_key in DAILY_CLOSE_CELLS:
        cell = await execute_cell(
            conn,
            factory_id=factory_id,
            metric_key=metric_key,
            dimension_key=dimension_key,
            aggregation_key=aggregation_key,
            date_range=date_range,
        )
        cells.append(cell)
        sections.append({
            "metric_key": metric_key,
            "text": render(cell, "今天"),
            # ⚠️ 带出 unit 是为了**推送时按 RBAC 过滤**: 非金额角色不许看 money 段。
            #    ⛔ 这个判断只能来自 registry —— 在推送侧手写一张「哪些是金额」
            #       的名单, 新登记一个金额指标就会悄悄漏出去(而且不报错)。
            "unit": cell.unit,
            # ⚠️ 出处一起带出去 —— 前端要打灰 tag 时不用再猜。
            #    ⛔ 但正文里的限定语**不依赖**它: 限定语已经在 text 里了。
            "provenance": cell.provenance,
            "estimation_basis": cell.estimation_basis,
            "missing_columns": list(cell.missing_columns),
        })

    return {
        "title": DAILY_CLOSE_TITLE,
        "date": date_range[0].isoformat(),
        "factory_id": factory_id,
        "sections": sections,
        "answer_text": "\n\n".join(s["text"] for s in sections),
        # 整屏的出处 = 只要有一段是估的, 这一屏就不能被当成账上的数。
        # ⛔ 取「最保守」的那个, 不取多数 —— 一段估的就足以让店长误判。
        "provenance": ("ESTIMATED"
                       if any(s["provenance"] == "ESTIMATED" for s in sections)
                       else "MEASURED"),
    }


async def push_daily_close(
    pool,
    *,
    factory_id: str,
    today: Optional[date] = None,
    java_notify=None,
    roles: Optional[List[str]] = None,
) -> Dict[str, Any]:
    """打烊触发 —— **接现有通知链, 不新建**。

    复用 `value_notifier` 的三样: 角色路由 / Java 通道 / 幂等防重。
    唯一的改动是**周期键从月换成日**(`2026-08-13` 而不是 `2026-08`),
    对应迁移 `V20261101_13`(把那一列从 varchar(7) 加宽到 16)。

    ⛔ 不新建一张日粒度通知表 —— 同一件事两份存储会漂, 而漂的表现是
       **店长每天收到两遍**。

    ⚠️ 幂等由 `(factory_id, 周期键, 角色)` 保证: 同一天重复跑只发一次。
       cron 重试、手工补跑都不会重复推送。
    """
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
    from smartbi.services.restaurant.value_notifier import maybe_notify

    async with pool.acquire() as conn:
        screen = await build_daily_close(conn, factory_id=factory_id, today=today)

    def _render_for(role: str) -> Optional[Tuple[str, str]]:
        """按角色裁剪那一屏。

        🔴 **非金额角色不许看到 ¥**。`NOTIFY_ROLES` 里的 `factory_admin` 不在
           `PRICE_VIEW_ROLES` 里 —— 把整屏原样推给所有人, 就是把一道 RBAC 边界
           推平了。第一版我正是这么写的。

        ⛔ 「哪些段是金额」只问 registry 的 `unit`, 不在这里写名单。
        ⚠️ 裁到一段不剩 → 返回 None(不推空通知), 而不是编一句话顶上。
        """
        visible = [s for s in screen["sections"]
                   if role in PRICE_VIEW_ROLES or s["unit"] != "money"]
        if not visible:
            return None
        title = f"{screen['date']} {screen['title']}"
        return title, "\n\n".join(s["text"] for s in visible)

    result = await maybe_notify(
        pool,
        factory_id,
        screen["date"],          # ← 周期键: 日粒度(对应迁移 V20261101_13)
        render=_render_for,
        roles=roles,
        java_notify=java_notify,
        log_tag="daily-close",
    )
    logger.info(
        "[daily-close] 打烊推送: factory=%s date=%s provenance=%s "
        "notified=%s skipped=%s failed=%s",
        factory_id, screen["date"], screen["provenance"],
        result.get("notified"), result.get("skipped"), result.get("failed"),
    )
    return {"screen": screen, "notify": result}
