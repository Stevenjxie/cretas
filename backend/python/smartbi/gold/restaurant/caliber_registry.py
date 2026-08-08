"""同一能力的**唯一权威口径**登记表 + 对账。

## 为什么需要它（2026-08-08 实测事故）

同一个租户、同一个 30 天窗口，两个查询给出的折扣总额：

    gold.queries.discount_breakdown  ->  ¥0
    gold.queries.discount_summary    ->  ¥3,543,242  (占营收 4.5%)

前者只读 `agg_discount`（MOCK_REST 是 0 行）；后者以 `agg_daily.discount_amount`
为准，再把 `agg_discount` 的构成**按比例缩放到该总额**——它的 docstring 写明这正是
为了修「月粒度构成 ÷ 日粒度营收」那次事故（C1）。

⇒ 老板从 Java 工具面问会听到「没有折扣」，从问答链问会听到「让了 354 万」。
**同一个问题、两个入口、两个答案。**

## 这个模块解决什么，不解决什么

⛔ 它**不合并实现**。合并是逐个能力的工程，且有些从属实现还有别的调用方。
✅ 它做两件事：
   1. **登记**：哪个能力有多份实现、哪一份是权威、为什么。
   2. **对账**：给定租户和窗口，跑两侧、比数字，不一致就报出来。

📌 判据：口径唯一**不能靠纪律**。我在同一天里反复强调「一个指标一处定义」，
   然后自己在 Python 侧新建了折扣意图 —— 说明纪律不管用，只有机制管用。

⚠️ 对账**不能自动修正**。发现不一致时正确的动作是让人看，
   而不是挑一个数字当真 —— 挑哪个正是需要判断的东西。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Caliber:
    """一个能力的口径登记。

    :param capability:   能力名（人话，给报告用）
    :param authority:    权威实现的函数名，**唯一**
    :param subordinates: 同能力的其它实现；它们不是错的，但不得作为口径来源
    :param total_key:    对账用的总量字段（在两侧结果里取同名/同义的量）
    :param why:          为什么权威方是权威 —— **必须写**，否则下次有人会改回去
    """

    capability: str
    authority: str
    subordinates: Tuple[str, ...]
    total_key: str
    why: str
    extract: Dict[str, Callable[[Dict[str, Any]], Optional[float]]] = field(
        default_factory=dict
    )


def _sum_items(payload: Dict[str, Any]) -> Optional[float]:
    items = payload.get("discounts") or payload.get("items") or []
    if not items and payload.get("total_amount") is not None:
        return float(payload["total_amount"])
    return float(sum(float(x.get("amount") or 0) for x in items))


def _summary_total(payload: Dict[str, Any]) -> Optional[float]:
    v = payload.get("total_discount_amount")
    return None if v is None else float(v)


#: ⛔ 新增一个与既有能力重叠的查询时，**必须**在这里登记，
#:    否则 `test_caliber_registry.py` 的覆盖闸会红。
REGISTRY: Tuple[Caliber, ...] = (
    Caliber(
        capability="折扣总额",
        authority="discount_summary",
        subordinates=("discount_breakdown",),
        total_key="折扣总额(元)",
        why=(
            "discount_summary 以 agg_daily 的日粒度窗口为准，再把 agg_discount 的"
            "月粒度构成按比例缩放到该总额，分项加总恒等于总额；"
            "discount_breakdown 只读 agg_discount，租户没接构成数据时直接报 0，"
            "而那不代表这段时间没有折扣。C1 事故修的正是这个错配。"
        ),
        extract={"discount_summary": _summary_total,
                 "discount_breakdown": _sum_items},
    ),
)


@dataclass(frozen=True)
class Reconciliation:
    capability: str
    authority: str
    authority_value: Optional[float]
    others: Dict[str, Optional[float]]

    @property
    def diverged(self) -> bool:
        """任一从属实现与权威不一致即为分叉。

        ⛔ 用**绝对相等**判，不设容差：这里比的是同一口径应当给出的同一个数，
           不是两个近似算法。有容差就会把「0 vs 354 万」之外的小分叉放过去。
        """
        return any(v != self.authority_value for v in self.others.values())

    def render(self) -> str:
        lines = [f"[{self.capability}] 权威 {self.authority} = {self.authority_value}"]
        for name, v in self.others.items():
            mark = "  ⚠️ 不一致" if v != self.authority_value else "  一致"
            lines.append(f"    {name} = {v}{mark}")
        return "\n".join(lines)


async def reconcile(
    pool,
    factory_id: str,
    date_range: Tuple[Any, Any],
    *,
    registry: Sequence[Caliber] = REGISTRY,
    call: Optional[Callable] = None,
) -> List[Reconciliation]:
    """跑登记表里每个能力的两侧实现，比总量。**只报告，不修正。**

    :param call: 注入点（测试用）。默认按函数名从 `gold.queries` 取。
    """
    async def _default_call(name, *a, **kw):
        import smartbi.gold.queries as Q
        return await getattr(Q, name)(*a, **kw)

    invoke = call or _default_call

    out: List[Reconciliation] = []
    for cal in registry:
        async def _value(fn_name):
            try:
                payload = await invoke(fn_name, pool, factory_id, date_range)
            except Exception as exc:  # noqa: BLE001
                logger.warning("[caliber] %s 跑不动: %s", fn_name, exc)
                return None
            picker = cal.extract.get(fn_name)
            return picker(payload) if picker else None

        auth = await _value(cal.authority)
        others = {name: await _value(name) for name in cal.subordinates}
        rec = Reconciliation(cal.capability, cal.authority, auth, others)
        if rec.diverged:
            logger.warning("[caliber] 口径分叉:\n%s", rec.render())
        out.append(rec)
    return out


def authority_of(query_name: str) -> Optional[str]:
    """这个查询是不是某个能力的从属实现？是就返回权威方的名字。

    给 resolver 用：**从属实现不得作为口径来源**。
    """
    for cal in REGISTRY:
        if query_name in cal.subordinates:
            return cal.authority
    return None
