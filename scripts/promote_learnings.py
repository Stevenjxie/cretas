"""扫多域学习候选 → 两级 gate → 候选清单; --apply 把通过项写进毕业文件(人审后)。

用法:
  python scripts/promote_learnings.py                  # 看候选清单 (dry-run, 全 learning_type)
  python scripts/promote_learnings.py --type field_mapping   # 只看某域
  python scripts/promote_learnings.py --apply          # 人审后毕业: 行业分支 + 全局主干
绝不静默自动: --apply 是人手动跑。需在能连 smartbi 库的环境跑(本地无库则在服务器跑)。

两级:
  行业分支(per business_type): 行业内 ≥2 工厂 conf≥0.9, 或 纠正+再1工厂 → 写 promoted_learnings_by_industry.json
  全局主干(cross-industry): 同映射跨 ≥2 行业分支一致 → 写 promoted_learnings.json
"""
import argparse
import asyncio
import json
import sys
from pathlib import Path

_BACKEND_PY = Path(__file__).resolve().parents[1] / "backend" / "python"
sys.path.insert(0, str(_BACKEND_PY))
# smartbi/services/__init__.py eagerly imports excel_parser → bare `from services...`,
# only resolvable with backend/python/smartbi on sys.path (how the live app resolves it).
sys.path.insert(0, str(_BACKEND_PY / "smartbi"))

from smartbi.services.learning_promotion import (        # noqa: E402
    is_branch_promotable, is_trunk_promotable,
    TRUNK_FILE, BRANCH_FILE, _load,
)


async def _fetch_candidates(pool):
    rows = await pool.fetch(
        """SELECT learning_type, source_key, target_value, business_type,
                  MAX(confidence) AS max_confidence,
                  COUNT(DISTINCT factory_id) AS factory_count,
                  bool_or(method = 'user_correction' AND confidence >= 0.99) AS has_correction,
                  SUM(occurrences) AS occurrences
           FROM smart_bi_learning_candidates
           GROUP BY learning_type, source_key, target_value, business_type
           ORDER BY learning_type, factory_count DESC, max_confidence DESC""")
    return [dict(r) for r in rows]


def _ensure(d, *keys):
    for k in keys:
        d = d.setdefault(k, {})
    return d


async def main(apply: bool, type_filter):
    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        print("ERROR: no smartbi DB pool (postgres 未配置/连不上)。在能连库的环境跑。")
        return

    trunk = _load(TRUNK_FILE)
    branch = _load(BRANCH_FILE)
    cands = await _fetch_candidates(pool)
    if type_filter:
        cands = [c for c in cands if c["learning_type"] == type_filter]

    # ---- 行业分支 gate ----
    to_branch = {}  # lt -> bt -> {src: tgt}
    print("=== 行业分支候选 ===")
    print(f"{'域':<14}{'业态':<10}{'源':<18}{'目标':<18}{'置信':<6}{'工厂':<5}判定")
    print("-" * 84)
    for c in cands:
        ok, reason = is_branch_promotable(c, branch, trunk)
        mark = "[升分支]" if ok else f"-- {reason}"
        print(f"{c['learning_type']:<14}{c['business_type']:<10}{str(c['source_key']):<18}"
              f"{str(c['target_value']):<18}{float(c['max_confidence']):<6.2f}"
              f"{int(c['factory_count']):<5}{mark}")
        if ok:
            _ensure(to_branch, c["learning_type"], c["business_type"])[c["source_key"]] = c["target_value"]

    # ---- 全局主干 gate (用 已有分支 + 本轮新升分支 的合并态) ----
    merged_branch = json.loads(json.dumps(branch))
    for lt, bts in to_branch.items():
        for bt, m in bts.items():
            _ensure(merged_branch, lt, bt).update(m)

    to_trunk = {}  # lt -> {src: tgt}
    seen = set()
    print("\n=== 全局主干候选 ===")
    print(f"{'域':<14}{'源':<18}{'目标':<18}判定")
    print("-" * 60)
    for c in cands:
        key = (c["learning_type"], c["source_key"], c["target_value"])
        if key in seen:
            continue
        seen.add(key)
        if trunk.get(c["learning_type"], {}).get(c["source_key"]) == c["target_value"]:
            continue  # 已在主干
        ok, reason = is_trunk_promotable(c["learning_type"], c["source_key"],
                                         c["target_value"], merged_branch)
        if ok:
            print(f"{c['learning_type']:<14}{str(c['source_key']):<18}{str(c['target_value']):<18}[升主干]")
            _ensure(to_trunk, c["learning_type"])[c["source_key"]] = c["target_value"]

    n_branch = sum(len(m) for bts in to_branch.values() for m in bts.values())
    n_trunk = sum(len(m) for m in to_trunk.values())
    print("-" * 60)

    if apply and (n_branch or n_trunk):
        for lt, bts in to_branch.items():
            for bt, m in bts.items():
                _ensure(branch, lt, bt).update(m)
        for lt, m in to_trunk.items():
            _ensure(trunk, lt).update(m)
        BRANCH_FILE.write_text(json.dumps(branch, ensure_ascii=False, indent=2), encoding="utf-8")
        TRUNK_FILE.write_text(json.dumps(trunk, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"已毕业 行业分支 {n_branch} 条 + 全局主干 {n_trunk} 条 (commit 这两个 JSON 使之生效)")
    elif apply:
        print("无可毕业候选")
    else:
        print(f"(dry-run) 行业分支 {n_branch} 条 + 全局主干 {n_trunk} 条达标; 加 --apply 写入")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--type", dest="type_filter", default=None,
                    help="只处理某 learning_type (field_mapping/classification/data_cleaning)")
    args = ap.parse_args()
    asyncio.run(main(args.apply, args.type_filter))
