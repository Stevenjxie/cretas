"""回填 fact_pos_item.product_id —— 给「写了 Silver 但没解析菜品维度」的租户。

背景 (2026-07-29): 平台 connector 的 writer 早期只把菜名写进
`source_item_raw`，`product_id` 一直是 NULL。后果不是少一列而已 ——
`agg_product` 的物化 SQL 带 `WHERE product_id IS NOT NULL`（见
`gold/materializer.py`），于是菜品维度整个物化出 0 行，餐饮 AI 的语义
规划器直接弃权（tiered-answer 返回 `delegate:false`）。菜品是餐饮分析的
主轴，缺了它问答层等于不可用。

⚠️ `agg_discount` **不在**此列：它读 `fact_pos_discount`，与 product_id 无关，
   而平台 writer 根本不写那张表 —— 修完这里 `agg_discount` 仍是 0 行，
   那是另一回事，别顺着这条线找。

writer 已修好，新订单会带 product_id；这个脚本补历史行。

做法：按 `source_item_raw` 去重 → `dim_product` UPSERT（沿用
`canonical/dim_resolver.py` 的 SQL 形状，归一化用 `normalize_for_dim`）
→ 批量 UPDATE 回填。

⚠️ 关于归一化口径：`normalize_for_dim` 是 migration
   `2026_04_28_silver_dimensions.sql` 给 `normalized_name` 写明的**意图**，
   但**当前另一条通道并没有照做** —— `canonical/normalizer.py` 调的是
   `resolve_product(item.name, item.name)`，把原名原样当归一化名（那里留着
   TODO）。所以同一租户若同时被两条通道喂数据，`水煮·牛肉` 会裂成两行
   dim_product，菜品排行里对半分。MOCK_REST 目前只有这一条通道、且 10 个
   种子菜名不含标点，两个函数结果相同，所以是潜在问题不是现网问题。
   收敛应该往 `normalize_for_dim` 这边走（那是 migration 的原意），列为后续。

**category 留空**：历史行只存了菜名，分类无从得知。不猜也不填「未分类」
（禁降级）。后续新订单流过来时 writer 的
`category = COALESCE(EXCLUDED.category, dim_product.category)` 会把分类补上。

⚠️ 但这个自愈**有前提**：分类是模拟端 `_paging.py` 新加的 `dishCategory` 字段
   带过来的。**139 上的模拟器没重新部署的话**，报文里就没有这个字段，
   writer 每次都送 `category=NULL`，`COALESCE` 是空操作，
   `dim_product.category` 会永远是 NULL —— 不是会自愈，是永远空。

**部署顺序（有先后依赖，别颠倒）**：
    1. 先部 139 模拟器（`scripts/deploy/deploy-mock-platform.sh`）—— 让报文带上 dishCategory
    2. 再部 47 Python（`deploy-smartbi-python.sh`）—— 让 writer 开始写 product_id
    3. 最后跑本脚本回填历史
   顺序反了的话：先跑回填，旧 writer 还在每 60s 往表里追加 product_id IS NULL
   的新行，脚本报一个漂亮的 rows_updated 而表几分钟后又脏了。

用法：
    cd backend/python
    PYTHONPATH=$PWD/smartbi python -m scripts.backfill_pos_item_product_id \\
        --factory-ids MOCK_REST [--dry-run]

    ⚠️ PYTHONPATH 必须指到 `backend/python/smartbi` —— `main.py` 会把这个目录
       塞进 sys.path，独立脚本不会，缺了就 `No module named 'services'`。
    ⚠️ POSTGRES_DB/USER/HOST/PORT 来自 systemd unit 的 `Environment=`，**不在**
       `.env.prod` 里；直接 `set -a; . .env.prod` 会拿到残缺值。

幂等：只碰 `product_id IS NULL` 的行，重复跑第二次影响 0 行。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from typing import Dict, List

logger = logging.getLogger(__name__)

# 与 writer / canonical dim_resolver 同形。
_PRODUCT_UPSERT_SQL = """
INSERT INTO dim_product (factory_id, name, normalized_name, category)
VALUES ($1, $2, $3, NULL)
ON CONFLICT (factory_id, normalized_name)
  DO UPDATE SET updated_at = NOW()
RETURNING product_id
"""


def plan_backfill(raw_names):
    """原始菜名列表 → (raw → normalized 映射, 归一化后为空的那些)。

    纯函数，不碰库 —— 去重口径是这个脚本唯一容易写错的地方，单独拎出来测。
    多个原始名可能归一化到同一个菜（全角空格 / 标点差异），这正是要的效果。
    """
    from smartbi.canonical.entity_resolution.agents.deterministic import normalize_for_dim

    mapping = {}
    skipped = []
    for raw in raw_names:
        normalized = normalize_for_dim((raw or "").strip())
        if not normalized:
            # 纯标点之类，归一化后什么都不剩 —— 不猜，留 NULL 并报出来。
            skipped.append(raw)
            continue
        mapping[raw] = normalized
    return mapping, skipped


async def backfill_one_factory(pool, factory_id: str, dry_run: bool) -> dict:
    async with pool.acquire() as conn:
        # RLS: app.factory_id 是**事务级** GUC，asyncpg 上不开显式事务从不生效
        # （dim_product / fact_pos_item 的 tenant_isolation 没有 __internal__
        # 逃生门，靠连接池残留会碰运气）。整个回填包在一个事务里。
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, true)", factory_id)

            rows = await conn.fetch(
                # ORDER BY: 多个原始拼写归一化到同一个菜时, 谁先来谁的写法就成了
                # dim_product.name(展示名)。不排序的话每次跑可能落到不同变体上。
                "SELECT DISTINCT source_item_raw FROM fact_pos_item "
                "WHERE factory_id = $1 AND product_id IS NULL "
                "  AND source_item_raw IS NOT NULL AND btrim(source_item_raw) <> '' "
                "ORDER BY 1",
                factory_id,
            )
            raw_names: List[str] = [r["source_item_raw"] for r in rows]
            if not raw_names:
                return {"factory_id": factory_id, "distinct_dishes": 0, "rows_updated": 0}

            mapping, skipped = plan_backfill(raw_names)
            name_to_pid: Dict[str, int] = {}
            normalized_cache: Dict[str, int] = {}
            for raw, normalized in mapping.items():
                pid = normalized_cache.get(normalized)
                if pid is None:
                    if dry_run:
                        pid = -1
                    else:
                        pid = await conn.fetchval(
                            _PRODUCT_UPSERT_SQL, factory_id, raw.strip(), normalized)
                        if pid is None:
                            # RETURNING 没给出行 —— 真发生了就说明 UPSERT 语义
                            # 不是我们以为的那样。放行的话会把 NULL 写回
                            # product_id, 正好复现本次要修的 bug。
                            raise RuntimeError(
                                f"dim_product UPSERT 没有返回 product_id: "
                                f"factory={factory_id} name={raw!r}")
                    normalized_cache[normalized] = pid
                name_to_pid[raw] = pid

            if dry_run:
                logger.info("[dry-run] %s: %d 个原始菜名 → %d 个去重菜品，跳过 %d",
                            factory_id, len(raw_names), len(normalized_cache), len(skipped))
                return {"factory_id": factory_id, "distinct_dishes": len(normalized_cache),
                        "rows_updated": 0, "skipped": len(skipped), "dry_run": True}

            # 一条 UPDATE ... FROM (VALUES ...) 搞定，不要逐菜发一条 UPDATE：
            # MOCK_REST 实测 24 万行明细，逐条往返会把几秒的事拖成几分钟。
            pairs = list(name_to_pid.items())
            updated = await conn.fetchval(
                """
                WITH m(raw, pid) AS (
                    SELECT * FROM UNNEST($2::text[], $3::bigint[])
                ), upd AS (
                    UPDATE fact_pos_item f
                       SET product_id = m.pid
                      FROM m
                     WHERE f.factory_id = $1
                       AND f.product_id IS NULL
                       AND f.source_item_raw = m.raw
                    RETURNING 1
                )
                SELECT count(*) FROM upd
                """,
                factory_id, [p[0] for p in pairs], [p[1] for p in pairs],
            )

            # 后置校验: 只报"我写了多少行"是不够的 —— 那正是本次要修的 bug
            # 的同一类毛病(报告做了什么, 不报告目标有没有达成)。这里直接问
            # "还剩多少行没有 product_id", 把 SELECT 阶段就被过滤掉的
            # (source_item_raw 为 NULL / 空白) 也一并算进来。
            remaining = await conn.fetchval(
                "SELECT count(*) FROM fact_pos_item "
                "WHERE factory_id = $1 AND product_id IS NULL",
                factory_id,
            )

    if skipped:
        logger.warning("%s: %d 个菜名归一化后为空，未回填（需人工看）：%s",
                       factory_id, len(skipped), skipped[:5])
    logger.info("%s: %d 个去重菜品，回填 %d 行，剩余未解析 %d 行",
                factory_id, len(normalized_cache), updated, remaining)
    if remaining:
        # 不当成成功。要么是归一化后为空的那些，要么是回填期间拉取循环又写了
        # 新行（那说明部署顺序错了：writer 还没更新就先跑了回填）。
        logger.warning("%s: 仍有 %d 行 product_id IS NULL —— 检查部署顺序"
                       "（先 139 模拟器 → 再 47 Python → 最后回填）", factory_id, remaining)
    return {"factory_id": factory_id, "distinct_dishes": len(normalized_cache),
            "rows_updated": updated, "skipped": len(skipped), "remaining_null": remaining}


async def _main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--factory-ids", required=True,
                        help="逗号分隔的 factory_id")
    parser.add_argument("--dry-run", action="store_true",
                        help="只统计去重后的菜品数，不写库")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    factory_ids = [f.strip() for f in args.factory_ids.split(",") if f.strip()]
    if not factory_ids:
        print("no factory ids", file=sys.stderr)
        sys.exit(2)

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()

    results = []
    for fid in factory_ids:
        # 失败隔离：一个租户炸掉不该让其余的不做。
        try:
            results.append(await backfill_one_factory(pool, fid, args.dry_run))
        except Exception as exc:  # noqa: BLE001
            logger.exception("%s 回填失败", fid)
            results.append({"factory_id": fid, "error": str(exc)})

    print()
    for r in results:
        print("  " + ", ".join(f"{k}: {v}" for k, v in r.items()))
    if any("error" in r for r in results):
        sys.exit(1)


if __name__ == "__main__":
    os.environ.setdefault("POSTGRES_ENABLED", "true")
    asyncio.run(_main())
