"""数字的出处，以及由出处**生成**的限定语。

## 为什么要有这个模块（2026-08-12 架构收口 C）

同一个店长问「毛利怎么样」，今天会拿到两个不同的数字，而系统不告诉他为什么：

| 路 | 位置 | 对没有配方成本的菜怎么办 |
|---|---|---|
| 经营看板 | `dish_margin.py` | 用**行业默认成本率**估一个毛利算进总数，前端标灰 |
| AI 问答 | `restaurant_ops_router.py` | **排除**在结论外，正文写「成本覆盖率 X%」 |

两条都不算错 —— 一个要「全店有个数」，一个要「说出口的数必须站得住」。
错的是**它们都只给数字，不给出处**：店长看到 32% 和 41%，无从判断哪个能用。

MOCK_REST 的成本覆盖率是 100%，所以今天不发作。换一个真实租户就发作。

## 裁定（不改任何现有数字，只加表达）

数字一个都不动 —— 改口径要重新对账，那是另一件事。这里只做一件事:
**让每个数字带上它的出处**，并且限定语**由出处字段生成**，不许手写。

⛔ 只做 `MEASURED` 与 `ESTIMATED` 两种。曾经想加的 `ALLOCATED_BY_CYCLE`
   （按盘点周期摊销）没有落地的读法，加进来就是个永远填不上的枚举值。

## 为什么限定语不许手写

手写的限定语会漂移：`restaurant_ops_router.py` 里那句「成本覆盖率 X%；未覆盖
成本的菜品无法判断盈亏，不在结论内」是**字面量拼在正文里**的，同一份口径在
`dish_margin.py` 那边压根没有对应的句子（只有一个前端灰色 tag）。
两处各写各的，就是「同一个口径两种说法」的来源。

判据：**限定语出现与否，只能由 provenance 字段决定**。
把 `provenance` 改成 `MEASURED` 而限定语还在，说明它是手写的，不是生成的。
"""
from __future__ import annotations

from typing import Optional

#: 账上真有的数 —— 来自实际成本/实际销售，没有任何推算成分。
MEASURED = "MEASURED"

#: 估出来的数 —— 缺少某个输入，用一个**可说明的依据**替代。
#: ⛔ 用它就必须同时给 `estimation_basis`，否则店长看到的是一个没有出处的数。
ESTIMATED = "ESTIMATED"

VALID_PROVENANCE = (MEASURED, ESTIMATED)

#: 覆盖率低于这个值才需要说明。1.0 == 全覆盖，说了反而是噪音。
_FULL_COVERAGE = 1.0


class ProvenanceError(ValueError):
    """出处填得不合法 —— 一律炸，不静默降级成 MEASURED。

    ⚠️ 静默降级正是这套东西要防的病：一个估出来的数被当成账上的数端出去，
       比不给数字更糟。
    """


def validate(provenance: str, estimation_basis: str = "") -> None:
    """出处字段自洽性。ESTIMATED 必须带依据。"""
    if provenance not in VALID_PROVENANCE:
        raise ProvenanceError(
            f"未登记的出处 {provenance!r}，只有 {VALID_PROVENANCE}。"
            f"（想加 ALLOCATED_BY_CYCLE 的话先回答「盘点周期从哪读」——"
            f"没有读数来源的枚举值加进来永远填不上）"
        )
    if provenance == ESTIMATED and not (estimation_basis or "").strip():
        raise ProvenanceError(
            "provenance=ESTIMATED 却没写 estimation_basis —— "
            "一个估出来的数没有出处，店长无从判断能不能用它做决定。"
        )


def qualifier(
    provenance: str,
    estimation_basis: str = "",
    *,
    coverage_ratio: Optional[float] = None,
) -> str:
    """由出处**生成**限定语。空字符串 = 这个数不需要限定语。

    ⛔ 调用方不许再手写一份。所有「这个数有什么前提」的话都从这里出。

    `coverage_ratio` = 有成本的营收 ÷ 全部营收。`None` 表示这条路不按覆盖率
    表达（例如整块数据都是估的，没有「覆盖了一部分」这个概念）。
    """
    validate(provenance, estimation_basis)

    if provenance == ESTIMATED:
        head = ""
        if coverage_ratio is not None and coverage_ratio < _FULL_COVERAGE:
            head = f"其中 {(1 - coverage_ratio) * 100:.1f}% 的营收没有配方成本，"
        return (
            f"> {head}用{estimation_basis}估算，"
            f"这部分是估出来的，不是账上的数，不能当实际毛利用。"
        )

    # MEASURED
    if coverage_ratio is None or coverage_ratio >= _FULL_COVERAGE:
        return ""
    return (
        f"> 成本覆盖率 {coverage_ratio * 100:.1f}%；"
        f"未覆盖成本的菜品无法判断盈亏，不在结论内。"
    )


def coverage_ratio(revenue_with_cost: float, revenue_total: float) -> float:
    """覆盖率的**唯一**定义。两条路都从这里取，免得一边算分子一边算分母。"""
    if not revenue_total:
        return _FULL_COVERAGE
    return max(0.0, min(_FULL_COVERAGE, revenue_with_cost / revenue_total))
