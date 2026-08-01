"""MOCK_REST 人效配置 —— 让「哪个时段人手不够 / 上个月人效怎么样」答得出来。

## 为什么是「配置」而不是「导入」

`fact_staffing_daypart` 三个字段来源不同, 不能一视同仁:

    avg_orders               ← **事实**, 从真实 POS 算出来, 本脚本不编
    staff_on_duty            ← **配置**, 餐厅自己排的班
    target_orders_per_staff  ← **配置**, 餐厅自己定的目标人效

resolver 缺数时的提示就写着「请先在**人效配置**里维护各时段的目标值」—— 后两个本来
就是等人填的。模拟端(139)没有人员/排班实体, 也不该有: 排班是餐厅内部管理, 不是 POS
平台会下发的东西。所以走配置, 不走 connector。

⛔ 但 `avg_orders` **必须是真的**。编一个日均订单数, 算出来的人效比就是假的,
而「建议加 2 人」这种话看起来完全正常 —— 那比答不出来更糟。

## 时段只配真实存在的两个

MOCK_REST 近 30 天按小时的实测分布:

    11-13 点  30 天都有   → 午市
    14-16 点  **只有 2-3 天有**  → 不是常规时段, 是个别异常
    17-20 点  29-30 天都有 → 晚市
    21 点以后 **一单都没有** → 没有夜宵

所以只配午市与晚市。给不存在的时段编日均, 会产出关于不存在时段的排班建议。
(对比 DEMO_REST 配了 4 个时段 —— 那个租户的生成器确实产出下午茶与夜宵。)

## 幂等

`UNIQUE(factory_id, store_id, daypart, weekday_type)` 里 store_id 为 NULL 时
**PostgreSQL 认为每行都不同**, `ON CONFLICT` 不会命中 → 重复跑会堆重复行。
所以用「先按租户删干净再插」的全量替换 —— 这也正是配置该有的语义。

## 用法

    cd /www/wwwroot/cretas/code/backend/python
    PYTHONPATH=.:smartbi venv-current/bin/python -m smartbi.scripts.seed_mock_rest_staffing \\
        --apply --confirm MOCK_REST

不带 `--apply` 是干跑(照常算真实日均并打印将写入的内容, 但事务回滚)。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from pathlib import Path
from typing import Dict, Tuple

_PYTHON_ROOT = Path(__file__).resolve().parent.parent.parent
for _p in (str(_PYTHON_ROOT), str(_PYTHON_ROOT / "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("seed_mock_rest_staffing")

FACTORY_ID = "MOCK_REST"

# 时段 → 小时区间(闭区间)。与实测分布对齐, 见模块 docstring。
_DAYPART_HOURS: Dict[str, Tuple[int, int]] = {
    "午市": (11, 13),
    "晚市": (17, 20),
}

# ── 配置部分 (daypart, weekday_type) → (在岗人数, 目标人效) ──────────────
#
# 10 家店的**连锁汇总**(store_id 为 NULL, 与 DEMO_REST 的先例一致), 所以人数是全链
# 口径: 95 人 ÷ 10 店 ≈ 每店 9-10 人在岗, 是休闲正餐单时段的正常配置。
# 目标 15 单/人/时段 比 DEMO_REST 的 25 低 —— 那个租户单店口径、菜单更简单。
#
# 周末晚市刻意配得比工作日晚市**更多人**(125 vs 110): 连锁默认「周末晚上更忙」而
# 加人, 但这个租户的真实数据是周末晚市反而更清闲(日均 1270 vs 2120)。于是诊断会
# 给出「周末晚市人效偏低, 可减人」—— 这是从真实数据里读出来的真结论, 不是摆设。
_STAFF_CONFIG: Dict[Tuple[str, str], Tuple[int, float]] = {
    ("午市", "weekday"): (95, 15.0),
    ("晚市", "weekday"): (110, 15.0),
    ("午市", "weekend"): (100, 15.0),
    ("晚市", "weekend"): (125, 15.0),
}

_AVG_ORDERS_SQL = """
SELECT CASE
         WHEN EXTRACT(HOUR FROM t.time) BETWEEN $2 AND $3 THEN 'lunch'
         WHEN EXTRACT(HOUR FROM t.time) BETWEEN $4 AND $5 THEN 'dinner'
       END AS bucket,
       CASE WHEN EXTRACT(ISODOW FROM t.date) >= 6 THEN 'weekend' ELSE 'weekday' END AS wd,
       count(DISTINCT t.date)::int AS days,
       count(*)::int              AS orders
  FROM fact_pos_transaction t
 WHERE t.factory_id = $1
   AND t.date >= CURRENT_DATE - 30
   AND (EXTRACT(HOUR FROM t.time) BETWEEN $2 AND $3
        OR EXTRACT(HOUR FROM t.time) BETWEEN $4 AND $5)
 GROUP BY 1, 2
"""


async def _measure(conn) -> Dict[Tuple[str, str], float]:
    """从真实 POS 算各(时段, 工作日/周末)的**日均订单**。"""
    lunch, dinner = _DAYPART_HOURS["午市"], _DAYPART_HOURS["晚市"]
    rows = await conn.fetch(
        _AVG_ORDERS_SQL, FACTORY_ID, lunch[0], lunch[1], dinner[0], dinner[1])
    label = {"lunch": "午市", "dinner": "晚市"}
    out: Dict[Tuple[str, str], float] = {}
    for r in rows:
        if not r["bucket"] or not r["days"]:
            continue
        out[(label[r["bucket"]], r["wd"])] = round(r["orders"] / r["days"], 2)
    return out


async def _seed(conn) -> Dict[str, int]:
    await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY_ID)
    measured = await _measure(conn)

    missing = [k for k in _STAFF_CONFIG if k not in measured]
    if missing:
        # 禁降级: 算不出真实日均就别写。写个 0 进去, resolver 会一本正经地
        # 建议「减人」, 而那个建议完全建立在编造的分母上。
        raise RuntimeError(
            f"这些(时段,工作日类型)在近 30 天 POS 里没有数据, 拒绝写入: {missing}")

    # 全量替换: store_id 为 NULL 时 ON CONFLICT 不命中(NULL 在唯一索引里互不相等),
    # 只能先删后插 —— 配置本来也该是全量替换而不是只增不改。
    deleted = await conn.execute(
        "DELETE FROM fact_staffing_daypart WHERE factory_id = $1 AND store_id IS NULL",
        FACTORY_ID,
    )
    inserted = 0
    for (daypart, wd), (staff, target) in sorted(_STAFF_CONFIG.items()):
        await conn.execute(
            "INSERT INTO fact_staffing_daypart"
            " (factory_id, store_id, daypart, weekday_type,"
            "  avg_orders, staff_on_duty, target_orders_per_staff)"
            " VALUES ($1, NULL, $2, $3, $4, $5, $6)",
            FACTORY_ID, daypart, wd, measured[(daypart, wd)], staff, target,
        )
        inserted += 1
    return {
        "deleted": int(deleted.split()[-1]) if deleted else 0,
        "inserted": inserted,
    }


async def _run(apply: bool) -> int:
    from smartbi.config import get_pg_pool

    pool = await get_pg_pool()
    async with pool.acquire() as conn:
        tx = conn.transaction()
        await tx.start()
        try:
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY_ID)
            measured = await _measure(conn)
            logger.info("从近 30 天真实 POS 算出的日均订单(连锁汇总, 10 家店):")
            logger.info("  %-6s %-9s %10s %8s %10s %8s  %s",
                        "时段", "类型", "日均订单", "在岗", "人效/人", "目标", "诊断")
            for (dp, wd), (staff, target) in sorted(_STAFF_CONFIG.items()):
                avg = measured.get((dp, wd))
                if avg is None:
                    logger.info("  %-6s %-9s %10s  ← 无真实数据", dp, wd, "—")
                    continue
                per = avg / staff
                if per > target * 1.15:
                    verdict = f"人效偏高 → 建议加 {max(1, round(avg / target) - staff)} 人"
                elif per < target * 0.7:
                    verdict = f"人效偏低 → 可减 {staff - max(1, round(avg / target))} 人"
                else:
                    verdict = "人效均衡"
                logger.info("  %-6s %-9s %10.1f %8d %10.1f %8.1f  %s",
                            dp, wd, avg, staff, per, target, verdict)

            stats = await _seed(conn)
            if not apply:
                await tx.rollback()
                logger.info("干跑完成(已回滚): %s", stats)
                logger.info("加 --apply --confirm MOCK_REST 才会真正写入。")
                return 0
            await tx.commit()
        except Exception:
            await tx.rollback()
            raise
    logger.info("写入完成: %s", stats)

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY_ID)
            n = await conn.fetchval(
                "SELECT count(*) FROM fact_staffing_daypart WHERE factory_id = $1",
                FACTORY_ID,
            )
    logger.info("fact_staffing_daypart 现有 %s 行", n)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(
        description="MOCK_REST 人效配置(日均订单取自真实 POS, 人数/目标为配置)")
    ap.add_argument("--apply", action="store_true", help="真正写入; 不加则干跑并回滚")
    ap.add_argument("--confirm", default="", help="写入时必须显式传 MOCK_REST")
    args = ap.parse_args()
    if args.apply and args.confirm != FACTORY_ID:
        logger.error("拒绝执行: --apply 必须配 --confirm %s", FACTORY_ID)
        return 2
    return asyncio.run(_run(args.apply))


if __name__ == "__main__":
    raise SystemExit(main())
