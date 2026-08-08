#!/usr/bin/env python3
"""给 MOCK_REST 建 Java 侧的库存底账（仓库 / 物料类型 / 批次）。

🔴 存在的理由（2026-08-09 实测）：MOCK_REST 在 `cretas_prod_db` 里
   `raw_material_types` 和 `material_batches` **都是 0 行**。于是
   `LowStockFindingProvider` 对这个租户永远产出 0 条 ——
   「问任何问题时顺带提示库存异常」这条能力，对演示租户是**死的**。
   不是规则坏了，是它读的两张表在这个租户下从来没建过。

⛔ 阈值从**真实领用量**推导，不凭空编：
   `min_stock` = 该食材近 14 天的日均领用 × 3 天。这样库存底账与
   经营流水是同一套事实的两个面 —— 领用多的食材安全库存自然高。
   随手写个 100 的话，「库存偏低」会跟实际消耗完全脱节，
   演示时一被追问「为什么这个要 100」就穿帮。

⛔ 大部分物料必须**高于**阈值：
   `getLowStockWarnings` 对「有 min_stock 但一条批次都没有」的物料算
   currentStock=0 → **CRITICAL**。25 种食材全部报红不是「发现能力强」，
   是噪音，而且会把真正紧张的那几样淹掉。这里刻意只让 4 种低于阈值
   （其中 1 种为 0 → CRITICAL），其余留 1.5–4 倍余量。

⚠️ 幂等：所有 id 由 (factory, 名字) 确定性生成，重跑更新同一批行，
   不会每次都堆一份新的。删除只删本脚本自己前缀的行。

用法（47 服务器）：
  sudo -u postgres /www/wwwroot/cretas/code/backend/python/venv38/bin/python \
    smartbi/scripts/seed_mock_rest_inventory.py --apply
  不加 --apply 是干跑：只打印将要写入什么，一行都不落库。
"""
from __future__ import annotations

import argparse
import hashlib
import logging
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP

import psycopg2
from psycopg2.extras import RealDictCursor, execute_values

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger(__name__)

FACTORY = "MOCK_REST"
WAREHOUSE_ID = "MR_fw1"
WAREHOUSE_NAME = "中央厨房仓"

#: min_stock = 日均领用 × 这么多天
SAFETY_DAYS = Decimal("3")
#: 推导日均用最近这么多天的领用
LOOKBACK_DAYS = 14

#: 刻意压到阈值以下的食材（按名字，确定性）。
#: ⛔ 不能随机选：演示时「哪几样紧张」必须每次都一样，
#:    否则同一个问题连问两次会得到不同的答案。
BELOW_THRESHOLD = {
    "罗氏虾": Decimal("0.00"),    # 断货 → CRITICAL
    "鲈鱼": Decimal("0.35"),      # 剩 35% 阈值
    "黑木耳": Decimal("0.55"),
    "牛肉": Decimal("0.80"),
}
#: 其余食材的余量倍数（1.5–4 倍之间，按名字哈希确定性取）
COMFORTABLE = (Decimal("1.5"), Decimal("4.0"))


def _det_id(prefix: str, *parts: str) -> str:
    """确定性 id：同样的输入永远同一个 id，重跑即更新。"""
    h = hashlib.sha1("|".join(parts).encode("utf-8")).hexdigest()[:16]
    return f"{prefix}{h}"


def _qty(v: Decimal) -> Decimal:
    return v.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)


def _comfort_factor(name: str) -> Decimal:
    lo, hi = COMFORTABLE
    h = int(hashlib.sha1(name.encode("utf-8")).hexdigest()[:8], 16)
    span = hi - lo
    return lo + span * Decimal(h % 1000) / Decimal(1000)


def load_ingredients(smartbi_dsn: str) -> list[dict]:
    """从 smartbi 侧取食材主数据 + 近 14 天日均领用。

    ⛔ 用 smartbi 的 dim_ingredient 而不是另建一套名字 —— 两边对不上的话，
       「问库存」和「问损耗」会指向两个不同的「罗氏虾」。
    """
    with psycopg2.connect(smartbi_dsn) as conn, \
            conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute("SELECT set_config('app.factory_id', %s, false)", (FACTORY,))
        cur.execute(
            """
            WITH span AS (
              SELECT COALESCE(MAX(date), CURRENT_DATE) AS last_day
              FROM fact_restaurant_requisition WHERE factory_id = %(f)s
            ),
            usage AS (
              SELECT r.ingredient_id,
                     -- 实发量优先; 没有实发就用申领量(单据还没审)。
                     -- ⛔ 不能只取 actual_qty: 未审单会被当成「不消耗」,
                     --    安全库存会被系统性低估。
                     SUM(COALESCE(r.actual_qty, r.requested_qty, 0))
                       / %(days)s AS daily_qty
              FROM fact_restaurant_requisition r, span
              WHERE r.factory_id = %(f)s
                AND r.date > span.last_day - %(days)s::int
              GROUP BY r.ingredient_id
            )
            SELECT i.ingredient_id, i.name, i.category, i.unit,
                   i.unit_price, i.shelf_life_days, i.storage_type,
                   COALESCE(u.daily_qty, 0) AS daily_qty
            FROM dim_ingredient i
            LEFT JOIN usage u ON u.ingredient_id = i.ingredient_id
            WHERE i.factory_id = %(f)s AND i.is_active
            ORDER BY i.name
            """,
            {"f": FACTORY, "days": LOOKBACK_DAYS},
        )
        rows = [dict(r) for r in cur.fetchall()]
    if not rows:
        # 禁降级: 没有食材主数据就建不出库存底账, 说清楚而不是种 0 行报成功。
        raise SystemExit(
            f"{FACTORY} 在 smartbi 侧没有 dim_ingredient —— "
            "先让平台同步把菜单主数据拉进来"
        )
    return rows


def plan(rows: list[dict]) -> list[dict]:
    """算出每种食材的 min_stock 与期初库存。纯函数，便于干跑核对。"""
    out = []
    for r in rows:
        daily = Decimal(str(r["daily_qty"] or 0))
        if daily <= 0:
            # 近 14 天没领用过 —— 不设安全库存。
            # ⛔ 硬给一个阈值会让「从来不用的东西」天天报缺货。
            min_stock = Decimal("0")
            on_hand = _qty(Decimal("5"))
        else:
            min_stock = _qty(daily * SAFETY_DAYS)
            factor = BELOW_THRESHOLD.get(r["name"], _comfort_factor(r["name"]))
            on_hand = _qty(min_stock * factor)
        out.append({
            **r,
            "min_stock": min_stock,
            "max_stock": _qty(min_stock * 8) if min_stock > 0 else None,
            "on_hand": on_hand,
            "below": min_stock > 0 and on_hand < min_stock,
        })
    return out


def apply(cretas_dsn: str, planned: list[dict], today: date) -> None:
    with psycopg2.connect(cretas_dsn) as conn, conn.cursor() as cur:
        now = datetime.now()
        # code / type / is_active 都是非空 —— 照 DEMO_REST 的 DR_fw1 同形填。
        cur.execute(
            "INSERT INTO factory_warehouses "
            "(id, factory_id, code, name, type, is_active, created_at, updated_at) "
            "VALUES (%s,%s,%s,%s,%s,TRUE,%s,%s) "
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, "
            "  code = EXCLUDED.code, type = EXCLUDED.type, "
            "  updated_at = EXCLUDED.updated_at",
            # type 有 check 约束, 合法值只有 LOGISTICS/WORKSHOP/OTHER/RAW/WIP/
            # FINISHED/LINESIDE/RETURNS/SCRAP/TEMP/QC/OUTSOURCE/TRANSFER/SALTED/RD。
            # 食材原料仓 = RAW。
            (WAREHOUSE_ID, FACTORY, "WH-KITCHEN", WAREHOUSE_NAME, "RAW", now, now),
        )
        # 建档人: 用该租户任意一个真实用户, 不编一个不存在的 id。
        cur.execute(
            "SELECT id FROM users WHERE factory_id = %s ORDER BY id LIMIT 1", (FACTORY,))
        row = cur.fetchone()
        if row is None:
            raise SystemExit(f"{FACTORY} 没有任何用户 —— created_by 非空, 建不出来")
        created_by = row[0]

        mt_rows, batch_rows = [], []
        for p in planned:
            mt_id = _det_id("mrmt_", FACTORY, p["name"])
            mt_rows.append((
                mt_id, now, now, p["category"], _det_id("MRM", p["name"])[:32],
                created_by, FACTORY, True,
                p["max_stock"], p["min_stock"], p["name"], None,
                p["shelf_life_days"], p["storage_type"], p["unit"], p["unit_price"],
                p["unit_price"], False, None, None, None,
            ))
            if p["on_hand"] <= 0:
                continue          # 断货就是一条批次都没有 —— 那正是 CRITICAL 的成因
            shelf = p["shelf_life_days"] or 30
            batch_rows.append((
                _det_id("mrmb_", FACTORY, p["name"], "b1"), now, None, now,
                f"MR{today:%Y%m%d}{_det_id('', p['name'])[:6].upper()}",
                created_by, today + timedelta(days=shelf), FACTORY, None, mt_id,
                None, today - timedelta(days=1), today - timedelta(days=1), None,
                p["unit"], today, p["on_hand"], Decimal("0"), "AVAILABLE",
                WAREHOUSE_NAME, None, p["unit_price"], Decimal("0"), None,
                WAREHOUSE_ID,
            ))

        execute_values(cur, """
            INSERT INTO raw_material_types
              (id, created_at, updated_at, category, code, created_by, factory_id,
               is_active, max_stock, min_stock, name, notes, shelf_life_days,
               storage_type, unit, unit_price, moving_avg_price, is_abaca_packaging,
               abaca_unit_per_box, abaca_default_unit, tax_rate)
            VALUES %s
            ON CONFLICT (id) DO UPDATE SET
              min_stock = EXCLUDED.min_stock, max_stock = EXCLUDED.max_stock,
              unit_price = EXCLUDED.unit_price, updated_at = EXCLUDED.updated_at
        """, mt_rows)

        execute_values(cur, """
            INSERT INTO material_batches
              (id, created_at, deleted_at, updated_at, batch_number, created_by,
               expire_date, factory_id, last_used_at, material_type_id, notes,
               production_date, purchase_date, quality_certificate, quantity_unit,
               inbound_date, receipt_quantity, reserved_quantity, status,
               storage_location, supplier_id, unit_price, used_quantity,
               weight_per_unit, warehouse_id)
            VALUES %s
            ON CONFLICT (id) DO UPDATE SET
              receipt_quantity = EXCLUDED.receipt_quantity,
              used_quantity = EXCLUDED.used_quantity,
              status = EXCLUDED.status, updated_at = EXCLUDED.updated_at
        """, batch_rows)
        conn.commit()
    logger.info("已写入 %d 种物料 / %d 条批次", len(mt_rows), len(batch_rows))


def main() -> None:
    ap = argparse.ArgumentParser(description=f"给 {FACTORY} 建库存底账")
    ap.add_argument("--smartbi-dsn", default="dbname=smartbi_prod_db")
    ap.add_argument("--cretas-dsn", default="dbname=cretas_prod_db")
    ap.add_argument("--apply", action="store_true", help="真写库；不加则只干跑")
    args = ap.parse_args()

    planned = plan(load_ingredients(args.smartbi_dsn))
    low = [p for p in planned if p["below"]]
    logger.info("食材 %d 种，其中会触发低库存告警的 %d 种：", len(planned), len(low))
    for p in planned:
        mark = "⚠️ 低于阈值" if p["below"] else ""
        logger.info("  %-8s 日均 %8.3f  安全 %9.3f  在库 %9.3f %s",
                    p["name"], float(p["daily_qty"]), float(p["min_stock"]),
                    float(p["on_hand"]), mark)
    # ⛔ 配置里写的名字必须真的存在。
    #    2026-08-09 干跑时「牛腩」写错了(这套食材里叫「牛肉」), 那条配置**静默无效** ——
    #    打算压低 4 种, 实际只压了 3 种, 而脚本一声不吭照常报成功。
    #    判据: 按名字索引的配置, 必须校验名字命中, 漏配和配错长得一模一样。
    known = {p["name"] for p in planned}
    missing = sorted(set(BELOW_THRESHOLD) - known)
    if missing:
        raise SystemExit(
            f"BELOW_THRESHOLD 里这些食材不存在: {missing}；"
            f"实际食材: {sorted(known)}")
    if not low:
        # 一条都不触发, 说明这份底账演示不了「顺带提示库存异常」—— 明确报错。
        raise SystemExit("没有任何食材低于阈值 —— 低库存发现将永远产出 0 条，检查 BELOW_THRESHOLD")
    if len(low) != len(BELOW_THRESHOLD):
        raise SystemExit(
            f"打算压低 {len(BELOW_THRESHOLD)} 种, 实际只有 {len(low)} 种低于阈值 —— "
            "多半是某种食材近 14 天没领用(min_stock=0, 不参与告警)")
    if not args.apply:
        logger.info("（干跑，未写库。加 --apply 才落库）")
        return
    apply(args.cretas_dsn, planned, date.today())


if __name__ == "__main__":
    main()
