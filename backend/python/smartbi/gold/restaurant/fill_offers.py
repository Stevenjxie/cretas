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

from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

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


def unlocked_split_by_column() -> Dict[str, Dict[str, Tuple[str, ...]]]:
    """同一份反查，但把**直接命中**和**闭包命中**分开。

    🔴 为什么要分：不分就会说出「补每道菜的食材成本，能算出**食材成本**、毛利率、毛利」
       —— 字面正确，读起来像绕口令。那一列直接对应的 metric 是**显然的**，
       闭包解锁的派生指标才是开价的真正卖点（「毛利率」才是店长嘴里那个词）。

    ⛔ 这个区分**不是手写排除表** —— 反查算法本来就知道：
       直接命中 = 列在 `Metric.requires` 里；闭包命中 = 经 `left`/`right` 传递到的。
    """
    direct: Dict[str, set] = {}
    for key, metric in _reg.METRICS.items():
        for column in metric.requires:
            direct.setdefault(column, set()).add(key)

    full = unlocked_by_column()
    out: Dict[str, Dict[str, Tuple[str, ...]]] = {}
    for column, keys in direct.items():
        out[column] = {
            "direct": tuple(sorted(keys)),
            "closure": tuple(sorted(set(full.get(column, ())) - keys)),
        }
    return out


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
    """完全算不出 → 「补 {列}，还能算出 {闭包解锁的指标}」。"""
    reverse = unlocked_by_column()
    reverse_split = unlocked_split_by_column()
    offers: List[Dict[str, object]] = []
    for column in missing_columns:
        label = column_label(column)
        split = reverse_split.get(column, {})
        closure_labels = _labels_of(split.get("closure", ()))
        direct_labels = _labels_of(split.get("direct", ()))
        # 🔴 闭包解锁的才是卖点。直接命中的那个 metric 与列本身同义,
        #    说出来是「补每道菜的食材成本, 能算出食材成本」—— 绕口令。
        #    「还能」两个字把「显然的」和「意外之喜」分开。
        # ⛔ 闭包为空时退回直接命中 —— 否则那一列就完全开不出价了,
        #    而「开不出价」和「没有缺口」在下游长得一样。
        unlocked = closure_labels or direct_labels
        lead = "还能算出" if closure_labels else "能算出"
        if not label or not unlocked:
            # ⛔ 说不清楚就不说 —— 一句「补 fact_pos_transaction.tax_amount」
            #    或者「补了能算出（空）」比不开价更糟。
            #    这不会静默: 缺人话名由 registry 那道断言当场红。
            continue
        offers.append({
            "kind": "fill",
            "column": column,
            "text": f"补{label}，{lead}{'、'.join(unlocked)}",
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
    # ⚠️ 2026-08-13: **不再复述 basis**。开价紧跟在限定语后面, 而限定语已经把
    #    「按什么估的」说过一遍了。开价要回答的是**下一句**:「那我该做什么」。
    # ⛔ `basis` 仍然原样带在结构化字段里 —— 前端/下游要对上时拿得到, 只是不进正文。
    #
    # 🔴 2026-08-14 订正: 原文是
    #      「补齐对应的成本卡，毛利就能从估变实 —— 那时这个数就是账上的了」
    #    **那是一句系统结构上做不到的承诺。** `_provenance_of` 是**指标键的纯函数**:
    #    只要依赖里有成本卡那一列, 就恒为 ESTIMATED —— 把全店的卡补齐, 它还是
    #    ESTIMATED, 因为 basis 是「成本卡的**理论**用量」, 而实际耗用要**盘点**才知道。
    #    补卡能提高的是**覆盖率**, 不是出处。两件事, 原文混成了一件。
    #    ⚠️ 这正是 owner 2026-08-14 收窄的那条: ⛔「补上就准了」。
    return [{
        "kind": "upgrade",
        "text": (f"{'、'.join(metric_labels)}用的是成本卡上的**理论**用量 —— "
                 f"实际用了多少要盘一次库才知道"),
        "basis": estimation_basis,
    }]


#: 一次开价最多点名几道。⚠️ 不是审美 —— 「先补这几道」超过 3 条就不再是
#: 优先级, 是另一张清单; 店长照着做的成功率随长度掉。
_TOP_N_GAPS = 3


def offers_for_cost_gaps(
    cost_gaps: Sequence[Dict[str, Any]],
    coverage_ratio: float,
    coverage_denominator: float,
    top_n: int = _TOP_N_GAPS,
) -> List[Dict[str, object]]:
    """T2 前两层：**哪几道菜没卡**（第一层）+ **补了覆盖率能到多少**（第二层）。

    🔴 owner 2026-08-14 放行前两层, 第三层「成本率可疑」**挂账**
       （触发条件还没定, 平阈值会误伤天然低成本项）。

    🔴 措辞收窄, 这条承重:
         ✅ 「补这 3 道，能算进毛利的营收从 42% 提到约 50%」
         ⛔ 「补上就准了」
       覆盖率**不依赖卡录得全不全**, 所以覆盖率这句站得住;
       而我们已经实测到「卡是对的但只录了主料」这种形状 ——
       **「补上就准了」是一句我们知道可能不成立的话。**

    ⚠️ 分母用调用方传进来的 `coverage_denominator`(算 `coverage_ratio` 用的
       那一个), ⛔ 不在这里另取一次数 —— 两个分母就是两个覆盖率。
    """
    if not cost_gaps or not coverage_denominator or coverage_denominator <= 0:
        return []
    picked = list(cost_gaps)[:max(1, top_n)]
    gained = sum(float(g.get("revenue") or 0) for g in picked)
    if gained <= 0:
        return []
    after = min(1.0, coverage_ratio + gained / coverage_denominator)
    # ⛔ 提升不到 0.1 个百分点就不开价 —— 「从 42.2% 提到 42.2%」读起来像坏了。
    if (after - coverage_ratio) * 100 < 0.1:
        return []
    names = "、".join(str(g.get("name") or "") for g in picked)
    return [{
        "kind": "fill_dishes",
        # 结构化字段原样带出去, 前端要自己排版时不用再解析正文
        "dishes": tuple({"name": g.get("name"),
                         "revenue": float(g.get("revenue") or 0)} for g in picked),
        "coverage_before": coverage_ratio,
        "coverage_after": after,
        "gap_total": len(cost_gaps),
        # ⚠️ 「约」不是谦虚 —— 补卡之后那道菜可能被判成异常卡而重新排除,
        #    所以这是上界不是承诺。⛔ 但**不许**因此就不给数。
        "text": (f"先补这 {len(picked)} 道的成本卡（{names}）——"
                 f"能算进毛利的营收会从 {coverage_ratio * 100:.1f}% "
                 f"提到约 {after * 100:.1f}%"),
    }]


def build_fill_offers(
    *,
    missing_columns: Sequence[str] = (),
    provenance: str = MEASURED,
    estimation_basis: str = "",
    estimated_metric_labels: Sequence[str] = (),
    cost_gaps: Sequence[Dict[str, Any]] = (),
    coverage_ratio: Optional[float] = None,
    coverage_denominator: Optional[float] = None,
) -> List[Dict[str, object]]:
    """T2 的唯一入口。触发条件：`missing_columns` 非空，或 `provenance != MEASURED`。

    🔴 2026-08-14 起多一层：知道**哪几道菜没卡**时，开价点名 + 给覆盖率增量，
       ⛔ 不再只说一句笼统的「补齐对应的成本卡」。
    """
    dish_offers = (
        offers_for_cost_gaps(cost_gaps, coverage_ratio, coverage_denominator)
        if (cost_gaps and coverage_ratio is not None
            and coverage_denominator is not None) else []
    )
    return (offers_for_missing_columns(missing_columns)
            + dish_offers
            + offers_for_estimated(provenance, estimation_basis,
                                   estimated_metric_labels))
