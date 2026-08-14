"""「上下文串了」的可判定形式 —— 带 history 与不带 history 编译同一个问句，
比 spec 的槽位差，**解释不了的差异 = 串**。

## 客户原话（2026-08-12 转录 [40:28]，他用别家大模型的实感）

> 它的上下文很容易搞混……我问的问题很多，他有时候会搞混……
> **你回到这个问题的时候，他还在扯你前面提到的一些，把它汇总在里面，
> 压根跟我问的这些问题没有任何关系。这就是类似幻觉的东西。**

⚠️ 他抱怨的**不是「AI 忘了」，是「AI 记太多、还掺进来」**。

## 为什么现有测试守不住

现有的上下文测试**全是正向**（`test_entity_resolution_contextual` /
`test_restaurant_store_scope_session_carry`）—— 守的是「能不能带过去」，
**没有一条守「会不会串错」**。

⇒ 这正是反复立的那条判据：**「X 不该出现」必须先证明 X 在别的路径上出得来。**
   而 T1 下钻按钮刚上线，回路从一轮变多轮，串的机会成倍增加。

## ⛔ 为什么不看正文

正文是模板拼的 —— 「串」发生在**问句编译成 spec 那一层**，
到正文时已经被模板洗过一遍。看正文会同时漏报（模板没印出来）和误报
（模板本来就会提一句范围）。

## 判据

    差异集合 = 带 history 的槽位 − 不带 history 的槽位
    差异集合里的每一项，都必须能在「问句省略了什么」里找到解释
    解释不了的差异 = 串

「省略」指指代/省略词：`呢 / 那 / 它 / 同期 / 还有呢 / 这个 / 上面`……
以及**槽位本身为空**（不带 history 时压根没解析出来 ⇒ 问句确实没说，
继承是合理的）。

⚠️ 反过来：**问句自己明确说了的槽位，继承值不许覆盖它** —— 那是「覆盖式」串，
   比「凭空多出来」更隐蔽，因为两边都有值、只是值不同。
"""
from __future__ import annotations

import logging
import re
from typing import Any, Dict, List, Optional, Sequence, Tuple

logger = logging.getLogger(__name__)

#: 会让人合理省略的指代/省略词。⚠️ 出现其中之一 ⇒ 这一问**依赖上文**，
#: 继承槽位是对的。⛔ 没有任何一个 ⇒ 这一问是自足的，不该继承任何东西。
ANAPHORA = (
    "呢", "它", "刚才", "上面", "同期", "环比", "同比",
    "还有", "再看", "接着", "继续", "换成", "换个", "分别",
)

#: ⚠️ 这几个词**看起来像指代, 其实不是** —— 它们是时间/普通表达的一部分。
#: 实测踩到: 「这个月毛利多少」里的「这个」被当成指代, 于是一个**自足**的问句
#: 被判成「依赖上文」, 而它正是客户抱怨的那个原形。
#: ⇒ 命中这些的片段先剔掉, 再看剩下的有没有指代。
NOT_ANAPHORA = ("这个月", "这个星期", "这个季度", "这个年", "这周", "这个月份",
                "那个月", "那一天", "这天", "那天")

#: 参与比较的槽位。⛔ 只列**会被继承**的那些 —— `plan_hash` / `confidence`
#: 这种派生字段两边必然不同，放进来会让差异集合永远非空（那就成了噪音）。
TRACKED_SLOTS: Tuple[str, ...] = (
    "store_scope", "store_slots", "store_slot", "dish_slot",
    "dimensions", "metrics", "requested_metrics",
    "date_range", "window_label", "comparison", "comparison_label",
    "excluded_entities", "ranking_direction", "analysis_action",
    "compare_stores",
)

#: 问句里**明确提到了范围**的信号。⚠️ 命中其一 ⇒ 这一问自己定了范围，
#: 继承来的 `store_scope` / `store_slots` **不许覆盖它**。
EXPLICIT_SCOPE = ("全部门店", "所有门店", "全店", "整体", "各门店", "每家店",
                  "全部店", "所有店")


def _norm(value: Any) -> Any:
    """把槽位归一成可比较的形态。⚠️ 元组/列表排序后比 —— 顺序不是「串」。"""
    if isinstance(value, (tuple, list)):
        return tuple(sorted(str(v) for v in value))
    return value


def slot_diff(with_history, without_history,
              slots: Sequence[str] = TRACKED_SLOTS) -> Dict[str, Tuple[Any, Any]]:
    """`带 history` 与 `不带 history` 的槽位差。

    返回 `{槽位: (不带, 带)}`。空字典 = 这一问不受上文影响。
    """
    out: Dict[str, Tuple[Any, Any]] = {}
    for name in slots:
        a = _norm(getattr(without_history, name, None))
        b = _norm(getattr(with_history, name, None))
        if a != b:
            out[name] = (a, b)
    return out


def question_is_self_contained(query: str) -> bool:
    """这一问自己说全了吗（没有指代/省略）。

    ⚠️ 自足的问句**不该继承任何东西** —— 转录里那个原形正是这样：
       他问了一个说全了的问题，而 AI「还在扯前面提到的一些」。
    """
    q = query or ""
    # 先把「这个月」这类**假指代**剔掉, 再看剩下的。
    for fake in NOT_ANAPHORA:
        q = q.replace(fake, "")
    return not any(token in q for token in ANAPHORA)


def question_sets_scope_explicitly(query: str) -> bool:
    """这一问自己定了范围吗（「全部门店」这类）。"""
    q = query or ""
    return any(token in q for token in EXPLICIT_SCOPE)


def explain(query: str, slot: str, before: Any, after: Any) -> Optional[str]:
    """这一项差异**解释得通吗**。解释得通返回理由，解释不通返回 None。

    三条合法解释，⛔ 除此之外都算串：

      1. 问句里有指代/省略词 ⇒ 它本来就依赖上文
      2. 不带 history 时这个槽位**是空的** ⇒ 问句确实没说，继承是补全
      3. 继承之后**值没变**（只是从 None 变成同一个值的另一种写法）

    ⚠️ 第 2 条有一个例外：问句**自己定了范围**时，范围类槽位即使原本为空
       也不许被继承覆盖 —— 那是「覆盖式」串。
    """
    empty = (None, "", (), [], False)
    scope_slots = ("store_scope", "store_slots", "store_slot", "compare_stores")

    if slot in scope_slots and question_sets_scope_explicitly(query):
        return None            # ← 覆盖式串：问句说了范围，还被继承改掉

    # 🔴 顺序要紧：**自足的问句什么都不该继承**，「槽位原本为空」对它不成立。
    #    判据原文是「差异必须能在**问句省略了什么**里找到解释」——
    #    自足的问句什么都没省略 ⇒ 任何继承都解释不通。
    #    ⚠️ 第一版把「槽位为空」放在前面, 于是
    #       「这个月毛利多少」继承了 history 的门店/菜也被判成合法 ——
    #       **那正是客户抱怨的那个形状**, 而我的闸放过了它。
    #       是形状一的变异当场抓住的。
    if question_is_self_contained(query):
        return None

    if before in empty:
        return "不带 history 时这个槽位是空的 —— 问句确实没说，继承是补全"

    return (f"问句含指代/省略（"
            f"{'、'.join(t for t in ANAPHORA if t in query)}）")


def detect(query: str, with_history, without_history,
           slots: Sequence[str] = TRACKED_SLOTS) -> Dict[str, Any]:
    """一次判定。

    返回 `{"diff": {...}, "explained": {...}, "bleed": {...}}`；
    `bleed` 非空 = **串了**。
    """
    diff = slot_diff(with_history, without_history, slots)
    explained: Dict[str, str] = {}
    bleed: Dict[str, Tuple[Any, Any]] = {}
    for slot, (before, after) in diff.items():
        why = explain(query, slot, before, after)
        if why:
            explained[slot] = why
        else:
            bleed[slot] = (before, after)
    return {"diff": diff, "explained": explained, "bleed": bleed}


def render(query: str, result: Dict[str, Any]) -> str:
    """给人读的一段。⚠️ 贴**差异集合原文**，⛔ 不转述。"""
    lines = [f"问句: {query!r}",
             f"  自足(无指代): {question_is_self_contained(query)}",
             f"  明确定了范围: {question_sets_scope_explicitly(query)}"]
    if not result["diff"]:
        lines.append("  差异集合: {} —— 这一问不受上文影响")
        return "\n".join(lines)
    lines.append("  差异集合 (槽位: 不带 → 带):")
    for slot, (a, b) in result["diff"].items():
        tag = "🔴 串" if slot in result["bleed"] else "✅ " + result["explained"][slot]
        lines.append(f"    {slot:<20} {a!r} → {b!r}   {tag}")
    return "\n".join(lines)
