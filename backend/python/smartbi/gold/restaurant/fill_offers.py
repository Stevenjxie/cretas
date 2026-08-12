"""T2 补数据开价 —— 「补 X，能算出 / 能把 Y 从估变实」。

## 为什么它必须是 registry 反查，而不是写文案

设计卡（`docs/decisions/2026-08-12-下钻回路设计卡.md`）原文：

> **为什么这条能自动产出而不用写文案**：只要反查做出来，
> 「补了能到什么程度」是**算出来的**，不是人编的。这也是它必须做成反查的全部理由。

手写文案的坏法是确定的：新登记一个指标 / 改一条 `requires`，那句开价不会跟着变，
而它**不报错** —— 店长看到的是一句过期的承诺。

## 反查怎么做

`Metric.requires` 是 `("表.列", ...)`。反转成 `列 → [metric_key]` 即可回答
「补这一列能解锁哪些指标」。

🔴 **必须传递闭包到 `DERIVED`**：`Derived` 没有 `requires`，它用 `left`/`right`
指向别的指标键（`gross_margin` → `gross_profit` → `revenue` / `food_cost`）。
不做闭包，最有价值的那句「补成本卡能算**毛利率**」就说不出来 ——
而毛利率恰恰是店长嘴里的那个词，`food_cost` 不是。

## 两类文案对应两种影响

- **完全算不出**（`missing_columns` 非空）→ 「补 {列的人话名}，能算出 {指标 label}」
- **精度降级**（`provenance == ESTIMATED`）→ 「补 {basis 缺的那样}，{指标} 从估变实」

⛔ 「列的人话名」取自 `metric_registry.COLUMN_LABELS`，**不在本模块里写映射表** ——
与 `category` 同一条判据，手写映射一旦落地，新登记的列会悄悄落在表外而不报错。
"""
from __future__ import annotations

from typing import Dict, Iterable, List, Sequence, Tuple

# ⛔ 用**模块引用**而不是 `from ... import METRICS` —— from-import 会把名字绑死在
#    本模块上, `monkeypatch.setattr(reg, "METRICS", ...)` 够不着它。
#    实测: 变异「改一条 requires」当场不生效, 断言纹丝不动 ——
#    那是「变异没生效」而不是「守卫没覆盖」, 两者长得一样但成因相反。
from smartbi.gold.restaurant import metric_registry as _reg
from smartbi.gold.restaurant.provenance import ESTIMATED, MEASURED


def _derived_dependencies() -> Dict[str, Tuple[str, ...]]:
    """派生指标直接依赖的指标键。"""
    return {k: (d.left, d.right) for k, d in _reg.DERIVED.items()}


def unlocked_by_column() -> Dict[str, Tuple[str, ...]]:
    """`列 → 它能解锁的全部指标键`（含派生指标的**传递闭包**）。

    ⛔ 不缓存 —— 登记表在测试里会被 monkeypatch，缓存会让变异对照失效
       （那正是「闸红不了」的一种长相）。这张表只有几十项，重算不值一提。
    """
    direct: Dict[str, set] = {}
    for key, metric in _reg.METRICS.items():
        for column in metric.requires:
            direct.setdefault(column, set()).add(key)

    deps = _derived_dependencies()
    for column, keys in direct.items():
        # 不动点迭代: 某个派生指标的两个输入都在集合里, 它本身也就解锁了。
        # ⚠️ 必须迭代到不动点 —— gross_margin 依赖 gross_profit, 而后者也是派生的。
        #    只做一层的话「补成本卡能算毛利率」这句永远说不出来。
        changed = True
        while changed:
            changed = False
            for dkey, (left, right) in deps.items():
                if dkey in keys:
                    continue
                # 🔴 `or` 不是 `and` —— 开价问的是「**假设别的都有**, 补这一列
                #    能算出什么」。`gross_profit = revenue - food_cost`:
                #    补成本卡时 revenue 本来就在库里, 所以补 food_cost 就解锁毛利。
                # ⛔ 第一版写的是 `and`, 实测 food_cost 只解锁 ('food_cost',) ——
                #    店长嘴里的「毛利率」永远开不了价, 而那正是最有价值的那一句。
                if left in keys or right in keys:
                    keys.add(dkey)
                    changed = True

    return {c: tuple(sorted(k)) for c, k in direct.items()}


def _labels_of(metric_keys: Iterable[str]) -> List[str]:
    out = []
    for key in metric_keys:
        entry = _reg.METRICS.get(key) or _reg.DERIVED.get(key)
        if entry is not None and getattr(entry, "label", ""):
            out.append(entry.label)
    return out


def column_label(column: str) -> str:
    """列的人话名。⛔ 只从 registry 取 —— 缺了让 `assert_registry_self_consistent`
    去红，不在这里编一个。"""
    return _reg.COLUMN_LABELS.get(column, "")


def offers_for_missing_columns(missing_columns: Sequence[str]) -> List[Dict[str, object]]:
    """完全算不出 → 「补 {列}，能算出 {指标}」。"""
    reverse = unlocked_by_column()
    offers: List[Dict[str, object]] = []
    for column in missing_columns:
        label = column_label(column)
        unlocked = _labels_of(reverse.get(column, ()))
        if not label or not unlocked:
            # ⛔ 说不清楚就不说 —— 一句「补 fact_pos_transaction.tax_amount」
            #    或者「补了能算出（空）」比不开价更糟。
            #    这不会静默: 缺人话名由 registry 那道断言当场红。
            continue
        offers.append({
            "kind": "fill",
            "column": column,
            "text": f"补{label}，能算出{'、'.join(unlocked)}",
            "unlocks": tuple(reverse.get(column, ())),
        })
    return offers


def offers_for_estimated(
    provenance: str,
    estimation_basis: str,
    metric_labels: Sequence[str],
) -> List[Dict[str, object]]:
    """精度降级 → 「补 {basis 缺的那样}，{指标} 从估变实」。

    ⚠️ `estimation_basis` 说的是**用什么估的**（例：行业默认成本率 32%）。
       开价要说的是**缺什么**，所以措辞是「这些菜还没有成本卡」而不是复述 basis。
       basis 原样带出去，让店长能对上他刚在正文里看到的那句限定语。
    """
    if provenance != ESTIMATED:
        return []
    if not metric_labels:
        return []
    return [{
        "kind": "upgrade",
        "text": (f"这些数字里有一部分是按{estimation_basis}估的；"
                 f"补齐对应的成本卡，{'、'.join(metric_labels)}就能从估变实"),
        "basis": estimation_basis,
    }]


def build_fill_offers(
    *,
    missing_columns: Sequence[str] = (),
    provenance: str = MEASURED,
    estimation_basis: str = "",
    estimated_metric_labels: Sequence[str] = (),
) -> List[Dict[str, object]]:
    """T2 的唯一入口。两个触发条件都在这里：
    `missing_columns` 非空，或 `provenance != MEASURED`。
    """
    return (offers_for_missing_columns(missing_columns)
            + offers_for_estimated(provenance, estimation_basis,
                                   estimated_metric_labels))
