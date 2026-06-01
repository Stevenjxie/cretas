"""扫候选 → gate → 候选清单; --apply 把通过项写进 promoted_field_aliases.json (人审后)。

用法:
  python scripts/promote_field_mappings.py            # 只看候选清单 (dry-run)
  python scripts/promote_field_mappings.py --apply    # 人审后毕业通过项
绝不静默自动: --apply 是人手动跑。需在能连 smartbi 库的环境跑 (本地无库则在服务器跑)。
"""
import argparse
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend" / "python"))

from smartbi.services.field_promotion import (        # noqa: E402
    is_promotable, PROMOTED_FILE, _load_promoted,
)


async def _fetch_candidates(pool):
    rows = await pool.fetch(
        """SELECT column_name, standard_field,
                  MAX(confidence) AS max_confidence,
                  COUNT(DISTINCT factory_id) AS factory_count,
                  SUM(occurrences) AS occurrences
           FROM smart_bi_field_mapping_candidates
           GROUP BY column_name, standard_field
           ORDER BY factory_count DESC, max_confidence DESC""")
    return [dict(r) for r in rows]


async def main(apply: bool):
    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        print("ERROR: no smartbi DB pool (postgres 未配置/连不上)。在能连库的环境跑。")
        return
    promoted = _load_promoted()
    cands = await _fetch_candidates(pool)
    to_apply = {}
    print(f"{'列名':<22}{'标准字段':<24}{'置信':<7}{'工厂':<6}判定")
    print("-" * 70)
    for c in cands:
        # v1: curated={} (项目无列级 curated 别名字典); promoted 当去重 baseline
        ok, reason = is_promotable(c, curated={}, promoted=promoted)
        mark = "[毕业]" if ok else f"-- {reason}"
        print(f"{str(c['column_name']):<22}{str(c['standard_field']):<24}"
              f"{float(c['max_confidence']):<7.2f}{int(c['factory_count']):<6}{mark}")
        if ok:
            to_apply[c["column_name"]] = c["standard_field"]
    print("-" * 70)
    if apply and to_apply:
        merged = {**promoted, **to_apply}
        PROMOTED_FILE.write_text(json.dumps(merged, ensure_ascii=False, indent=2),
                                 encoding="utf-8")
        print(f"已毕业 {len(to_apply)} 条 → {PROMOTED_FILE} (commit 它使之生效)")
    elif apply:
        print("无可毕业候选")
    else:
        print(f"(dry-run) {len(to_apply)} 条达标; 加 --apply 写入")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    asyncio.run(main(ap.parse_args().apply))
