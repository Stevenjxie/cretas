"""追问按钮 —— T2 补数据 / T1 下钻。**结构化**产出，与正文共用同一批 offers。

## 三条边界（owner 2026-08-14）

1. ⛔ **按钮不许说正文没说的话。** 按钮是入口，不是新内容。
   ⇒ 每个 action 带一个 `anchor`：它指向的**正文里那句话**。
     `assert_actions_anchored()` 逐条核 anchor 确实在正文里。
2. ⛔ **系统故障时不出按钮。** 抑制路径必须有**阳性对照**证明它真的会抑制 ——
   否则「故障时没有按钮」在「任何时候都没有按钮」时同样成立（恒真式）。
3. 上限 4 个是**前端** `.slice(0, 4)` 截的。⇒ **后端按优先级排好再送**，
   ⛔ 不能指望前端恰好切掉该切的。

## 优先级

卡上是 `T2 > T3 > T1 > T4`。⚠️ 那是**产品优先级**（哪个对店长更有用），
不是建造顺序。今天没有 T3/T4，所以实际是「补数据」在前、「下钻」在后。

## T1 的维度从哪来

**registry 反查**：`Metric.dimensions` 是这个指标能按哪些维度分组，
减去这次回答**已经用过的**那些，剩下的就是可下钻的。

⚠️ 这里有一处**不能推**的输入：「这次回答已经渲染了哪些维度」是那段回答代码
自己的性质，登记表不知道。⇒ 由调用方声明 `used_dimensions`，
**紧挨着渲染它的代码**写。候选集合仍然是反查出来的 —— ⛔ 那一半不许手写。
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Sequence, Tuple

from smartbi.gold.restaurant import metric_registry as _reg

logger = logging.getLogger(__name__)

#: 类型优先级。数字小的排前面。⛔ 前端只负责截断，排序在后端。
TYPE_PRIORITY: Dict[str, int] = {"T2": 0, "T3": 1, "T1": 2, "T4": 3}

#: 前端 `.slice(0, 4)` 的那个 4。⚠️ 后端也按它截 —— 送 8 个再让前端切 4 个,
#: 等于「优先级由切的位置决定」, 而那个位置在另一个仓里。
MAX_ACTIONS = 4

#: meta 里出现任何一个就**不出按钮**。⛔ 故障/拒答时给按钮 = 请他继续点一个
#: 本来就答不出的东西。
_SUPPRESS_META_KEYS = ("no_data", "rbac_masked", "unavailable", "clarification")


def suppressed(meta: Optional[Dict[str, Any]]) -> bool:
    """这次回答该不该出按钮。

    ⚠️ 阳性对照在测试里: 先证明**正常时出得来**, 再证明故障时不出。
       否则「故障时没按钮」在「永远没按钮」时同样成立。
    """
    if not meta:
        return False
    return any(bool(meta.get(k)) for k in _SUPPRESS_META_KEYS)


def _drillable_dimensions(metric_key: str,
                          used: Sequence[str]) -> Tuple[str, ...]:
    """还没用过的维度 —— **registry 反查**, ⛔ 不手写。"""
    entry = _reg.METRICS.get(metric_key)
    if entry is None:
        derived = _reg.DERIVED.get(metric_key)
        if derived is None:
            return ()
        # 派生量取两个输入的**交集** —— 只在两边都能分组的维度上下钻,
        # ⛔ 取并集会给出一个算不出来的组合(点下去就是拒答)。
        left = set(_drillable_dimensions(derived.left, ()))
        right = set(_drillable_dimensions(derived.right, ()))
        cand = left & right
    else:
        cand = set(entry.dimensions)
    # `all` 是「不分组」, 它不是可下钻的对象
    skip = set(used) | {"all"}
    return tuple(d for d in sorted(cand - skip) if d in _reg.DIMENSIONS)


def build_actions(
    *,
    metric_key: str,
    used_dimensions: Sequence[str],
    offers: Sequence[Dict[str, Any]],
    answer_text: str,
    meta: Optional[Dict[str, Any]] = None,
) -> Tuple[Dict[str, Any], ...]:
    """这次回答该给哪些按钮。**排好序、截好断**再送。

    :param offers: `fill_offers` 的产出 —— **与正文共用同一批**,
        ⛔ 不为按钮再拼一份文案(那就是同一句话两个来源)。
    """
    if suppressed(meta):
        return ()

    actions: List[Dict[str, Any]] = []

    # T2 —— 补数据。label 取 offer 正文的**前半句**(破折号之前),
    #        ⇒ 它必然是正文那句话的子串, 边界 1 由构造成立。
    for offer in offers or ():
        text = str(offer.get("text") or "")
        if not text:
            continue
        label = text.split("——")[0].strip().rstrip("，,")
        if not label:
            continue
        actions.append({
            "type": "T2", "label": label,
            "anchor": text,                     # 正文里那句话, 逐字
            "payload": {"kind": offer.get("kind"),
                        "dishes": offer.get("dishes", ())},
        })

    # T1 —— 下钻。anchor 是**那个数字所在的那一段**(正文第一段),
    #        因为下钻就是「进入这个数」。
    head = (answer_text or "").split("\n\n")[0]
    entry = _reg.METRICS.get(metric_key) or _reg.DERIVED.get(metric_key)
    metric_label = getattr(entry, "label", metric_key) if entry else metric_key
    for dim_key in _drillable_dimensions(metric_key, used_dimensions):
        dim = _reg.DIMENSIONS[dim_key]
        actions.append({
            "type": "T1", "label": f"按{dim.label}看{metric_label}",
            "anchor": head,
            "payload": {"metric": metric_key, "dimension": dim_key},
        })

    # ⛔ 排序在后端。前端只截断 —— 送一堆让它 slice, 等于优先级由别的仓决定。
    actions.sort(key=lambda a: TYPE_PRIORITY.get(a["type"], 99))
    return tuple(actions[:MAX_ACTIONS])


def assert_actions_anchored(actions, answer_text: str) -> None:
    """边界 1 的可执行形式: 每个按钮都指向正文里真实存在的一句话。

    ⛔ 按钮不许说正文没说的话 —— 它是入口, 不是新内容。
    """
    for action in actions or ():
        anchor = str(action.get("anchor") or "")
        assert anchor and anchor in (answer_text or ""), (
            f"按钮 {action.get('label')!r} 的 anchor 不在正文里 —— "
            f"它在说正文没说的话")
