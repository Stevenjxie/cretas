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
    _validate_basis_shape(estimation_basis)


#: basis 会被套进「用{basis}估算，…」，所以它必须是**名词短语**。
#: ⛔ 约束放在**消费端**(这里)而不是每个产出端 —— basis 已经有多个产出点
#:    (成本卡 / 折扣 / 将来还会有), 约束它比约束每个产出点便宜, 也不会漏。
_BASIS_MAX_CHARS = 24
_BASIS_FORBIDDEN = ("。", "；", ";", "——", "\n")


def _validate_basis_shape(estimation_basis: str) -> None:
    """basis 必须是名词短语。

    🔴 同一个错犯过**两次**: 成本卡那条塞了整句, 折扣那条又塞了整句。
       第二次在 prod 上打出:
         「用这里没扣折扣 —— 折扣是整单的…会比合计高估算，这部分是估出来的」
    ⛔ 「犯过、记过、又犯」说明**记在 memory 里挡不住** ——
       写 basis 的时候不会去翻 memory。所以改成结构性约束: 当场炸。

    ⚠️ 判据是形状不是内容: 带句号/分号/破折号 = 它是句子; 太长 = 它是句子。
       ⛔ 不检查语义 —— 那会变成一张越来越长的词表(形态 E)。
    """
    b = (estimation_basis or "").strip()
    if not b:
        return
    for mark in _BASIS_FORBIDDEN:
        if mark in b:
            raise ProvenanceError(
                f"estimation_basis 里出现 {mark!r} —— 它会被套进「用{{basis}}估算，…」，"
                f"塞一句完整的话进去读不通。请写成**名词短语**，"
                f"解释放到模板外面。当前值: {b!r}"
            )
    if len(b) > _BASIS_MAX_CHARS:
        raise ProvenanceError(
            f"estimation_basis 有 {len(b)} 字，超过 {_BASIS_MAX_CHARS} —— "
            f"这么长基本就是句子了。套进「用{{basis}}估算，…」会读不通。"
            f"当前值: {b!r}"
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
        # 🔴 owner 2026-08-13 定稿, 三处改动:
        #
        # ① 「不是账上的数」和「不能当实际毛利用」**是同一件事说两遍**, 留一个。
        # ② **正反都说**。只说否定面(「45% 没有配方成本」)会让店长觉得这数没用;
        #    只说正面(「55% 能算准」)会淡化风险。两个都给, 他自己判断这 55%
        #    够不够他做决定。
        # ③ basis 里那个括号「(实际用了多少要等盘点)」去掉 —— 第三层嵌套,
        #    而且**对店长不产生行动**: 他不会因为这句去改盘点周期。
        #    真要改, 第三段的开价会告诉他。
        if coverage_ratio is not None and coverage_ratio < _FULL_COVERAGE:
            return (
                f"> 其中 {coverage_ratio * 100:.1f}% 的营收能算准，"
                f"另外 {(1 - coverage_ratio) * 100:.1f}% 没有配方成本、"
                f"是按{estimation_basis}估的 —— 估出来的部分不能当实际毛利用。"
            )
        return f"> 用{estimation_basis}估算，估出来的部分不能当实际毛利用。"

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
