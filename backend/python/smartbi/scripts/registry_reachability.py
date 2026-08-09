"""可达性闸 —— 「登记表有多少格子，规划器能指到几个」。

🔴 为什么需要它（2026-08-09 实测）：
   执行侧登记了 **3168 个格子**，穷举规划器**所有可能的输出组合**，
   只能到达 **147 个（4%）**。登记表的 96% 是死的 —— 造好了、验过了、
   用不上。而这件事**从任何现有的闸上都看不出来**：
   单元测试绿、prod 全量实跑 2074 个组合通过、回归电池 80/85，
   全都不会因为「模型指不到它们」而变红。

⛔ 判据：这个数只许变大。它变小 = 某次改动把规划器能表达的东西缩窄了，
   而那种回归在别的地方一点症状都没有。

用法：
    python -m smartbi.scripts.registry_reachability            # 报总数
    python -m smartbi.scripts.registry_reachability --detail   # 列出够不到的元素
"""
from __future__ import annotations

import argparse
import datetime
import itertools
import sys
from dataclasses import dataclass, field
from typing import Any, Optional, Sequence, Set, Tuple

from smartbi.gold.restaurant.generic_answer import spec_to_cell
from smartbi.gold.restaurant.metric_registry import (
    AGGREGATIONS,
    canonical_dimensions,
    canonical_metrics,
    DERIVED,
    DIMENSIONS,
    METRICS,
)
from smartbi.gold.restaurant.restaurant_intent import (
    _SEMANTIC_DIMENSIONS,
    _SEMANTIC_METRICS,
)

#: 规格里 `analysis_action` 的**真实**取值域。
#: ⛔ 从 `RestaurantQuerySpec.analysis_action` 的注释逐字抄来，不是我猜的 ——
#:    2026-08-09 有个缺陷就是按 "rank"/"top" 判排名，而规划器一次都不产出那两个值。
_ACTIONS = ("lookup", "compare", "diagnose", "optimize")
_DIRECTIONS: Tuple[Optional[str], ...] = (None, "best", "worst")


@dataclass
class _ProbeSpec:
    """穷举用的规格替身。

    ⚠️ 字段必须与 `RestaurantQuerySpec` 逐字对齐 —— 夹具与真实规格不一致时，
       量出来的是夹具的可达性，不是系统的。
    """
    requested_metrics: Sequence[str] = ()
    dimensions: Sequence[str] = ()
    analysis_action: str = "lookup"
    ranking_direction: Optional[str] = None
    ranking_limit: int = 5
    aggregation: Optional[str] = None
    date_range: Tuple[datetime.date, datetime.date] = (
        datetime.date(2026, 8, 1), datetime.date(2026, 8, 9))


def planner_vocabulary() -> dict:
    """规划器**真正能产出**的取值域。取自它自己的定义，⛔ 不另抄一份。"""
    return {
        # ⚠️ 用 `_SEMANTIC_METRICS`(LLM **输出**的取值域), 不是
        #    `_REQUEST_METRIC_RULES`(确定性关键词编译表)。批 3 扩的是前者;
        #    量错了域, 这个闸会报「一点没变」而实际上变了。
        "metrics": sorted(_SEMANTIC_METRICS),
        "dimensions": sorted(_SEMANTIC_DIMENSIONS),
        "actions": list(_ACTIONS),
        "directions": list(_DIRECTIONS),
        # 批 1 之后规划器才会产出这个槽；没有时穷举里它恒为 None，
        # 可达集合与批 1 之前逐字相同。
        "aggregations": [None] + sorted(AGGREGATIONS),
    }


def reachable_cells() -> Set[Tuple[str, str, str]]:
    """穷举规划器所有可能的输出，收集能到达的格子。"""
    v = planner_vocabulary()
    out: Set[Tuple[str, str, str]] = set()
    for metric, dim, action, direction, agg in itertools.product(
        v["metrics"], list(v["dimensions"]) + [None],
        v["actions"], v["directions"], v["aggregations"],
    ):
        # ⛔ 必须**跟真实管线一样先归一**: 规格入口会把 product→dish、date→time。
        #    不归一就是在量一个系统实际不做的动作 —— 这个闸就成了自说自话。
        cell = spec_to_cell(_ProbeSpec(
            requested_metrics=canonical_metrics((metric,)),
            dimensions=canonical_dimensions((dim,)) if dim else (),
            analysis_action=action,
            ranking_direction=direction,
            aggregation=agg,
        ))
        if cell is not None:
            out.add(cell)
    return out


def total_cells() -> int:
    return (len(METRICS) + len(DERIVED)) * len(DIMENSIONS) * len(AGGREGATIONS)


def report(detail: bool = False) -> int:
    cells = reachable_cells()
    total = total_cells()
    pct = len(cells) * 100 // total if total else 0
    print(f"REACHABLE {len(cells)}/{total}  ({pct}%)")
    got_m = {c[0] for c in cells}
    got_d = {c[1] for c in cells}
    got_a = {c[2] for c in cells}
    all_m = set(METRICS) | set(DERIVED)
    print(f"  指标 {len(got_m)}/{len(all_m)}   维度 {len(got_d)}/{len(DIMENSIONS)}   "
          f"聚合 {len(got_a)}/{len(AGGREGATIONS)}")
    if detail:
        for label, missing in (("指标", all_m - got_m),
                               ("维度", set(DIMENSIONS) - got_d),
                               ("聚合", set(AGGREGATIONS) - got_a)):
            if missing:
                print(f"  ⛔ 规划器指不到的{label}: {sorted(missing)}")
    return len(cells)


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--detail", action="store_true")
    ap.add_argument("--min", type=int, default=None,
                    help="低于这个数就退非零 —— 给 CI 当闸用")
    args = ap.parse_args(argv)
    n = report(args.detail)
    if args.min is not None and n < args.min:
        print(f"FAIL 可达格子 {n} < 要求的 {args.min} —— 有改动缩窄了规划器能表达的东西")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
