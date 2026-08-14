"""T3「标事件」—— 归因走到**无痕层**时，问他一句，把回答存成主观数据。

## 什么是无痕层（触发判据，owner 2026-08-14）

归因一层层往下走：全店 → 门店 → 菜 → …。走到某一层，**下一层在登记表里
没有对应的 metric/dimension** —— 库里再也没有任何东西能继续解释这个波动。
那一刻我们只有两个选择：

  · 停在那里说「查不出来」  ← 今天的行为
  · **问他一句**            ← T3

⇒ 判据是「候选下钻维度为空 **且** 这个波动本身还没被解释」，
  ⛔ 不是「答案行数少」（那是被挂账的样本量阈值，不许混进来）。

## 🔴🔴 承重约束：它永远不进实测那一侧

owner 原话：**事件标注是主观数据，有自己的 provenance，绝不和实测混算。**

三道结构性保障，⛔ 不靠自觉：

  1. `REPORTED_BY_USER` **不在** `provenance.VALID_PROVENANCE` 里
     ⇒ 拿它构造 `CellResult` 会被 `validate()` 当场炸
  2. 库里 `CHECK (provenance = 'REPORTED_BY_USER')`
     ⇒ 这张表里一行都不可能标成 MEASURED
  3. 本模块**不导出任何返回数值的函数** —— 它只产出「问句」和「一段话」

⚠️ 它进正文的方式是**引述**：「你上次说这天在做抖音团购」——
   而不是「因为做了活动所以营收 +30%」。前者是转述，后者是把它当因果用了。

## 为什么不复用既有表

`fact_pos_*` 是实测流水。把主观标注混进去 = 下一次归因把「老板说做了活动」
当成事实去推。⇒ 独立表 `fact_restaurant_event_annotation`，
migration `V20261101_14__restaurant_event_annotation.sql`（走 runner）。
"""
from __future__ import annotations

import logging
from datetime import date
from typing import Any, Dict, Optional, Sequence

from smartbi.gold.restaurant.provenance import REPORTED_BY_USER

logger = logging.getLogger(__name__)

#: 问句模板。⚠️ **两个都问**：「做活动」是我们干的，「有人在推」是外部的 ——
#: 只问前者会把外部原因（达人探店、隔壁装修）逼成「不知道」。
#: ⚠️ 用他的话：⛔ 不出现「归因」「维度」「波动」这些词。
_ASK_TEMPLATE = "{when}{subject}{phenomenon}，这天有没有做什么活动 / 是不是有人在推？"

#: 按钮上印的字。⚠️ 必须逐字出现在正文里 —— 与 T1/T2 同一条纪律。
T3_LABEL = "说说那天的情况"

SUBJECT_KINDS = ("dish", "store", "all")


def is_trace_exhausted(drillable_dimensions: Sequence[str]) -> bool:
    """归因是不是走到无痕层了。

    ⚠️ 判据**只有一条**：候选下钻维度为空。
    ⛔ 不掺「这组只有 N 单」—— 样本量阈值是挂账项，掺进来的话 T3 会在
       **还能继续往下查**的时候冒出来，等于用一个问句顶替一次查询。
    """
    return not tuple(drillable_dimensions)


def build_question(*, when: str, subject: str, phenomenon: str) -> str:
    """问他的那句话。⚠️ 三段都由调用方给**具体值**，⛔ 不留占位符。"""
    for name, value in (("when", when), ("phenomenon", phenomenon)):
        if not (value or "").strip():
            raise ValueError(
                f"T3 问句缺 {name} —— 「这天有没有做什么活动」不带具体时间和现象, "
                f"他不知道你在问哪一天的哪件事")
    return _ASK_TEMPLATE.format(
        when=when.strip(), subject=(subject or "").strip(),
        phenomenon=phenomenon.strip())


def build_action(*, when: str, subject: str, phenomenon: str,
                 anchor: str) -> Dict[str, Any]:
    """T3 按钮。落回 `{label, question}` 契约，与 T1/T2 同一份。

    ⚠️ `question` 就是问他的那句话 —— 点下去等于他在回答，
       ⛔ 不是「再查一次」。这是 T3 与 T1/T2 的根本区别：
       T1/T2 点下去是**我们**再算一次，T3 点下去是**他**说一句。
    """
    return {
        "type": "T3",
        "label": T3_LABEL,
        "question": build_question(when=when, subject=subject,
                                   phenomenon=phenomenon),
        "anchor": anchor,
    }


_UPSERT_SQL = (
    "INSERT INTO fact_restaurant_event_annotation"
    " (factory_id, event_date, subject_kind, subject_name,"
    "  asked_question, answer_text, answered_by_role, provenance)"
    " VALUES ($1::varchar, $2::date, $3::varchar, $4::varchar,"
    "         $5::text, $6::text, $7::varchar, $8::varchar)"
    " ON CONFLICT (factory_id, event_date, subject_kind, subject_name)"
    " DO UPDATE SET asked_question = EXCLUDED.asked_question,"
    "               answer_text    = EXCLUDED.answer_text,"
    "               answered_by_role = EXCLUDED.answered_by_role,"
    "               updated_at     = NOW()"
    " RETURNING id"
)


async def record_answer(
    conn,
    *,
    factory_id: str,
    event_date: date,
    subject_kind: str,
    subject_name: str,
    asked_question: str,
    answer_text: str,
    answered_by_role: str = "",
) -> Optional[int]:
    """把他的回答存下来。返回行 id；答的是空话就不存（返回 None）。

    ⛔ `provenance` **不接受入参** —— 写死 `REPORTED_BY_USER`。
       留成参数就等于留了一条「标成 MEASURED」的路, 而库里那条 CHECK
       会在运行时才炸(那时已经在 prod 上了)。
    ⚠️ 存**原话**, 不抽结构 —— 抽出来的「活动类型」是我们猜的,
       而这张表的全部价值就在于它是他说的。
    """
    if subject_kind not in SUBJECT_KINDS:
        raise ValueError(f"未登记的对象类型 {subject_kind!r}，"
                         f"只有 {SUBJECT_KINDS}")
    text = (answer_text or "").strip()
    if not text:
        # 他没答（或答了空）不算标注 —— 存一条空的等于把「没解释」
        # 记成「已解释」，下次就不会再问了。
        logger.info("[event-annotation] 回答为空, 不入库 factory=%s date=%s",
                    factory_id, event_date)
        return None
    row = await conn.fetchrow(
        _UPSERT_SQL, factory_id, event_date, subject_kind,
        (subject_name or "").strip()[:120],
        (asked_question or "").strip(), text,
        (answered_by_role or "").strip()[:64],
        REPORTED_BY_USER,
    )
    return int(row["id"]) if row else None


async def known_annotations(
    conn, factory_id: str, event_date: date,
) -> Sequence[Dict[str, Any]]:
    """这一天已经有过的解释。**用于不再问第二遍**。

    ⚠️ 返回的是**一段话**, 不是任何数值 —— 本模块不导出返回数值的函数,
       那是「绝不和实测混算」的第三道结构性保障。
    """
    rows = await conn.fetch(
        "SELECT subject_kind, subject_name, asked_question, answer_text,"
        "       provenance"
        "  FROM fact_restaurant_event_annotation"
        " WHERE factory_id = $1 AND event_date = $2"
        " ORDER BY updated_at DESC",
        factory_id, event_date)
    return [dict(r) for r in rows or ()]


def quote(annotation: Dict[str, Any]) -> str:
    """把标注**引述**进正文。

    ⚠️ 引述, ⛔ 不是当因果用:
        ✅ 「你上次说这天在做抖音团购」
        ⛔ 「因为做了活动所以营收涨了 30%」
       后者把一句主观的话变成了一条推理的前提。
    """
    said = str(annotation.get("answer_text") or "").strip()
    if not said:
        return ""
    return f"你上次说：{said}"
