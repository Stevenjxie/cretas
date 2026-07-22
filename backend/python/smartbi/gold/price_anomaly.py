"""Wave2 价格异常威慑引擎 — 供应商进价异常检测 + 解释/确认。

邓总差异化护城河 (洗洁精 110→150、青菜 2.5→2): 对同类物料**相邻采购单价**做异常检测,
让乱报价的供应商被持续盯住 ("连续三次跟我这样玩他就不敢乱来")。

核心逻辑
--------
对每个 material (normalized_name × supplier):
- 取最近一次采购单价 (latest) vs 该物料 trailing N 次 (latest 之前) 的均价 (trailing_avg)。
- |delta_pct| > ε (默认 5%, 可配) → 异常。delta_pct = (latest - trailing_avg)/trailing_avg*100。
- direction = UP / DOWN; 连续同向异常累计 (来自 price_anomaly_ack 的历史确认) → risk_level。
  - 这是第 3 次 (含本次) 同向异常 → HIGH (邓总: 连续三次)。否则 MEDIUM。

威慑非处罚 (防呆)
----------------
检测只产报警, 不自动改价/不处罚。管理者对每条异常要求供应商录入"解释"
(reason_code dropdown + 备注), 写入 price_anomaly_ack。OTHER 必填备注 (Rule 3)。
幂等: 同一 (factory, food, supplier, 异常送货日) 只确认一次 (Rule 4)。

数据来源
--------
``agg_supplier_price`` (G7 OCR/manual 进价历史) — 检测无需新表, 只扫历史。
``price_anomaly_ack`` (本 Wave2 新表) — 记 ack/解释 + 累计高风险。

所有读/写都设 RLS 租户上下文 (set_config app.factory_id)。Rule 6: 拒绝 None
factory_id 静默零结果。Java↔Python parity: 金额/率 → float (JSON-safe)。
"""
from __future__ import annotations

import logging
from datetime import timedelta
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, List, Optional

logger = logging.getLogger(__name__)

# 标准异常原因 (Rule 3: 自由文本改约束选择)。OTHER 必填补充备注。
VALID_REASON_CODES: frozenset[str] = frozenset({
    "SEASONAL",      # 季节性
    "MARKET_RISE",   # 市场涨价
    "SPEC_CHANGE",   # 规格变化
    "OTHER",         # 其他 (必填 reason_note)
})

# 连续同向异常达到此次数 (含本次) → HIGH 风险供应商 (邓总: 连续三次)。
_HIGH_RISK_THRESHOLD = 3

# 默认配置。
DEFAULT_TRAILING_N = 3
DEFAULT_EPSILON_PCT = 5.0

# 基准窗口模式。
#   "count" — 取 latest 之前最多 trailing_n 次的均价 (legacy, 默认; web-admin 板 + #53 原行为)。
#   "days"  — 取 latest 之前 window_days 天内全部点的均价 (邓总锁定: "自身 90 天移动均价")。
BASELINE_MODE_COUNT = "count"
BASELINE_MODE_DAYS = "days"
DEFAULT_WINDOW_DAYS = 90


def _to_float(v: Any) -> Optional[float]:
    """Decimal/None → float|None (JSON-safe, mirrors gold read convention)."""
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _classify_risk(prior_consecutive: int) -> str:
    """连续同向异常 risk level。

    ``prior_consecutive`` = 本次之前已记录的同向连续异常确认数。
    本次是第 ``prior_consecutive + 1`` 次。第 3 次 (含本次) 起 → HIGH。
    """
    this_count = prior_consecutive + 1
    if this_count >= _HIGH_RISK_THRESHOLD:
        return "HIGH"
    return "MEDIUM"


def _avg(values: List[Decimal]) -> Optional[Decimal]:
    if not values:
        return None
    return sum(values, Decimal("0")) / Decimal(len(values))


async def detect_price_anomalies(
    pool,
    factory_id: str,
    trailing_n: int = DEFAULT_TRAILING_N,
    epsilon_pct: float = DEFAULT_EPSILON_PCT,
    baseline_mode: str = BASELINE_MODE_COUNT,
    window_days: int = DEFAULT_WINDOW_DAYS,
) -> List[dict]:
    """检测同类物料相邻采购单价异常。

    返回 list of dict (camelCase), 每条 = 一个 (food, supplier) 的最近异常:
        normalizedName, ingredientName, supplierId, supplierName, unit,
        anomalyDeliveryDate (ISO), oldPrice, newPrice, trailingAvg, deltaPct,
        direction (UP|DOWN), consecutiveAnomalyCount, riskLevel (MEDIUM|HIGH)

    算法 (per (food, supplier) group):
        latest        = 最近一次单价
        trailing_avg  = 基准均价, 取决于 baseline_mode:
            "count" — latest 之前最多 trailing_n 次的均价 (legacy, web-admin 板默认)
            "days"  — latest 之前 window_days 天内全部点的均价
                      (邓总锁定: "自身 90 天移动均价"; 跨同类物料相邻采购单价比较)
        delta_pct     = (latest - trailing_avg) / trailing_avg * 100  (Rule 10: Decimal)
        anomaly       当 |delta_pct| > epsilon_pct

    两种 mode 共享同一 SQL (按 (food, supplier) date-DESC 全量拉取), 仅基准点的
    选取方式不同 — 不分叉成两个 detector (邓总护城河复用 #53 检测内核 + ack/累计高风险)。

    Rule 6: 拒绝 None factory_id。
    """
    if factory_id is None or factory_id == "":
        raise ValueError(
            f"detect_price_anomalies: factory_id required (got {factory_id!r})"
        )
    if trailing_n < 1:
        trailing_n = 1
    if window_days < 1:
        window_days = 1
    mode = baseline_mode if baseline_mode in (BASELINE_MODE_COUNT, BASELINE_MODE_DAYS) \
        else BASELINE_MODE_COUNT
    eps = Decimal(str(epsilon_pct))

    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        # All price points per (food, supplier), newest first. We fetch the
        # whole recent window (trailing_n + 1) per group so we can compute the
        # latest-vs-trailing comparison in Python (clearer than a window-fn SQL).
        rows = await conn.fetch(
            """
            SELECT normalized_name, ingredient_name, supplier_id, supplier_name,
                   unit, delivery_date, unit_price
              FROM agg_supplier_price
             WHERE factory_id = $1
               AND unit_price IS NOT NULL
             ORDER BY normalized_name, supplier_id, delivery_date DESC
            """,
            factory_id,
        )

        # Group by (normalized_name, supplier_id), preserving date-DESC order.
        groups: dict[tuple, List[dict]] = {}
        for r in rows:
            key = (r["normalized_name"], r["supplier_id"])
            groups.setdefault(key, []).append(r)

        anomalies: List[dict] = []
        ack_lookups: List[tuple] = []  # (normalized_name, supplier_id, direction)
        for (nname, sid), pts in groups.items():
            if len(pts) < 2:
                continue  # need at least latest + 1 baseline point
            latest = pts[0]
            latest_price = latest["unit_price"]
            if latest_price is None:
                continue
            latest_price = Decimal(str(latest_price))
            # Baseline-point selection — pts[1:] are the prior points (date-DESC).
            #   count mode: the immediately-prior `trailing_n` points.
            #   days  mode: every prior point whose delivery_date is within
            #              [latest_date - window_days, latest_date) — i.e. the
            #              moving-average window 邓总 locked at 90 days.
            if mode == BASELINE_MODE_DAYS:
                latest_date = latest["delivery_date"]
                cutoff = (latest_date - timedelta(days=window_days)
                          if latest_date is not None else None)
                baseline_pts = [
                    p for p in pts[1:]
                    if p["delivery_date"] is not None
                    and cutoff is not None
                    and cutoff <= p["delivery_date"] < latest_date
                ]
            else:
                baseline_pts = pts[1: 1 + trailing_n]
            baseline_prices = [
                Decimal(str(p["unit_price"]))
                for p in baseline_pts
                if p["unit_price"] is not None
            ]
            trailing_avg = _avg(baseline_prices)
            if trailing_avg is None or trailing_avg == 0:
                continue
            delta = latest_price - trailing_avg
            delta_pct = (delta / trailing_avg * Decimal("100")).quantize(
                Decimal("0.0001"), rounding=ROUND_HALF_UP
            )
            if abs(delta_pct) <= eps:
                continue  # within tolerance — not an anomaly
            direction = "UP" if delta_pct > 0 else "DOWN"
            old_price = baseline_prices[0] if baseline_prices else None  # immediately-prior
            anomalies.append({
                "_nname": nname,
                "_sid": sid,
                "normalizedName": nname,
                "ingredientName": latest["ingredient_name"],
                "supplierId": sid,
                "supplierName": latest["supplier_name"],
                "unit": latest["unit"],
                "anomalyDeliveryDate": latest["delivery_date"].isoformat()
                if latest["delivery_date"] is not None else None,
                "oldPrice": _to_float(old_price),
                "newPrice": _to_float(latest_price),
                "trailingAvg": _to_float(
                    trailing_avg.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
                ),
                "deltaPct": _to_float(delta_pct),
                "direction": direction,
            })
            ack_lookups.append((nname, sid, direction))

        # Compute consecutive same-direction prior ack counts in one query.
        prior_counts: dict[tuple, int] = {}
        if ack_lookups:
            # Build arrays for UNNEST join. supplier_id may be None.
            nnames = [t[0] for t in ack_lookups]
            sids = [t[1] for t in ack_lookups]
            dirs = [t[2] for t in ack_lookups]
            count_rows = await conn.fetch(
                """
                SELECT a.normalized_name, a.supplier_id, COUNT(*) AS prior_count
                  FROM price_anomaly_ack a
                  JOIN UNNEST($2::text[], $3::text[], $4::text[])
                       AS t(nname, sid, dir)
                    ON a.normalized_name = t.nname
                   AND COALESCE(a.supplier_id, '') = COALESCE(t.sid, '')
                   -- same direction: ack's recorded new>old (UP) matches probe dir
                   AND ( (t.dir = 'UP'  AND a.new_price > COALESCE(a.old_price, a.new_price))
                      OR (t.dir = 'DOWN' AND a.new_price < COALESCE(a.old_price, a.new_price)) )
                 WHERE a.factory_id = $1
                 GROUP BY a.normalized_name, a.supplier_id
                """,
                factory_id, nnames, sids, dirs,
            )
            for cr in count_rows:
                prior_counts[(cr["normalized_name"], cr["supplier_id"])] = int(
                    cr["prior_count"]
                )

        # Attach consecutive count + risk level, drop private keys.
        out: List[dict] = []
        for a in anomalies:
            key = (a.pop("_nname"), a.pop("_sid"))
            prior = prior_counts.get(key, 0)
            a["consecutiveAnomalyCount"] = prior + 1
            a["riskLevel"] = _classify_risk(prior)
            out.append(a)

    # Stable ordering: HIGH risk first, then larger |delta| first.
    out.sort(key=lambda a: (a["riskLevel"] != "HIGH", -abs(a.get("deltaPct") or 0)))
    logger.info(
        "[price-anomaly] detected %d anomalies for factory=%s "
        "(mode=%s trailing_n=%d window_days=%d eps=%s%%)",
        len(out), factory_id, mode, trailing_n, window_days, epsilon_pct,
    )
    return out


async def record_anomaly_ack(
    pool,
    factory_id: str,
    *,
    normalized_name: str,
    ingredient_name: Optional[str],
    supplier_id: Optional[str],
    supplier_name: Optional[str],
    anomaly_delivery_date,
    old_price: Optional[Decimal],
    new_price: Decimal,
    delta_pct: Optional[Decimal],
    reason_code: str,
    reason_note: Optional[str],
    acknowledged_by: Optional[str],
) -> int:
    """记录供应商对一条价格异常的解释/确认 (威慑非处罚)。

    幂等: 同一 (factory, food, supplier, 异常送货日) 重复确认 → 更新解释 (ON CONFLICT)。
    返回 ack 行 id。

    Rule 3 (防呆): reason_code 必须在 VALID_REASON_CODES; OTHER 必填 reason_note。
    Rule 6: 拒绝 None factory_id。
    """
    if factory_id is None or factory_id == "":
        raise ValueError(
            f"record_anomaly_ack: factory_id required (got {factory_id!r})"
        )
    if reason_code not in VALID_REASON_CODES:
        raise ValueError(
            f"record_anomaly_ack: invalid reason_code {reason_code!r}; "
            f"must be one of {sorted(VALID_REASON_CODES)}"
        )
    if reason_code == "OTHER" and (reason_note is None or not str(reason_note).strip()):
        raise ValueError(
            "record_anomaly_ack: reason_note required when reason_code='OTHER'"
        )
    if not normalized_name:
        raise ValueError("record_anomaly_ack: normalized_name required")
    if anomaly_delivery_date is None:
        raise ValueError("record_anomaly_ack: anomaly_delivery_date required")
    if new_price is None:
        raise ValueError("record_anomaly_ack: new_price required")

    note = reason_note.strip() if isinstance(reason_note, str) else reason_note

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )
            row = await conn.fetchrow(
                """
                INSERT INTO price_anomaly_ack (
                    factory_id, normalized_name, ingredient_name,
                    supplier_id, supplier_name, anomaly_delivery_date,
                    old_price, new_price, delta_pct,
                    reason_code, reason_note, acknowledged_by
                )
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
                ON CONFLICT (factory_id, normalized_name, anomaly_delivery_date, COALESCE(supplier_id, ''))
                DO UPDATE SET
                    reason_code = EXCLUDED.reason_code,
                    reason_note = EXCLUDED.reason_note,
                    acknowledged_by = EXCLUDED.acknowledged_by,
                    old_price = EXCLUDED.old_price,
                    new_price = EXCLUDED.new_price,
                    delta_pct = EXCLUDED.delta_pct,
                    created_at = NOW()
                RETURNING id
                """,
                factory_id, normalized_name, ingredient_name,
                supplier_id, supplier_name, anomaly_delivery_date,
                old_price, new_price, delta_pct,
                reason_code, note, acknowledged_by,
            )
    ack_id = int(row["id"]) if row is not None else 0
    logger.info(
        "[price-anomaly] ack id=%d factory=%s food=%s supplier=%s reason=%s by=%s",
        ack_id, factory_id, normalized_name, supplier_id, reason_code, acknowledged_by,
    )
    return ack_id


async def list_anomaly_acks(
    pool,
    factory_id: str,
    normalized_name: Optional[str] = None,
) -> List[dict]:
    """已确认异常列表 (报警去重 + 留痕展示)。

    可选按 normalized_name 过滤。返回 camelCase dicts ordered by date DESC.
    Rule 6: 拒绝 None factory_id。
    """
    if factory_id is None or factory_id == "":
        raise ValueError(
            f"list_anomaly_acks: factory_id required (got {factory_id!r})"
        )
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        if normalized_name:
            rows = await conn.fetch(
                """
                SELECT id, normalized_name, ingredient_name, supplier_id,
                       supplier_name, anomaly_delivery_date, old_price, new_price,
                       delta_pct, reason_code, reason_note, acknowledged_by, created_at
                  FROM price_anomaly_ack
                 WHERE factory_id = $1 AND normalized_name = $2
                 ORDER BY anomaly_delivery_date DESC, id DESC
                """,
                factory_id, normalized_name,
            )
        else:
            rows = await conn.fetch(
                """
                SELECT id, normalized_name, ingredient_name, supplier_id,
                       supplier_name, anomaly_delivery_date, old_price, new_price,
                       delta_pct, reason_code, reason_note, acknowledged_by, created_at
                  FROM price_anomaly_ack
                 WHERE factory_id = $1
                 ORDER BY anomaly_delivery_date DESC, id DESC
                """,
                factory_id,
            )
    return [
        {
            "id": int(r["id"]),
            "normalizedName": r["normalized_name"],
            "ingredientName": r["ingredient_name"],
            "supplierId": r["supplier_id"],
            "supplierName": r["supplier_name"],
            "anomalyDeliveryDate": r["anomaly_delivery_date"].isoformat()
            if r["anomaly_delivery_date"] is not None else None,
            "oldPrice": _to_float(r["old_price"]),
            "newPrice": _to_float(r["new_price"]),
            "deltaPct": _to_float(r["delta_pct"]),
            "reasonCode": r["reason_code"],
            "reasonNote": r["reason_note"],
            "acknowledgedBy": r["acknowledged_by"],
            "createdAt": r["created_at"].isoformat()
            if r["created_at"] is not None else None,
        }
        for r in rows
    ]
