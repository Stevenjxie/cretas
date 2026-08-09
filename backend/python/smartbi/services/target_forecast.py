"""#58 Phase 1 — 滚动营收预测 (linear-trend, revenue-only).

从 gold agg_daily 取 trailing N 天日营收 → IQR 去离群 → numpy 线性拟合
(polyfit deg=1) → 外推 horizon 天 → 80% CI (±1.28σ residual)。

2026-08-10 起在趋势之上叠**乘性周内因子** (model_type='linear_trend_dow')。
回测(scripts/forecast_backtest.py, RES_3101_009 与 DEMO_REST 两个租户)：

    h=1/7/30 线上旧版  27.3% / 27.8% / 27.7%  ← 三个跨度全部输给「上周同日」
    h=1/7/30 加周内后  15.9% / 17.1% / 16.8%  ← 三个跨度全部胜出

原因是结构性的：餐饮日营收的主信号就是星期几(实测周六/周一 = 1.81×)，
一元线性回归里没有这一项，再怎么调窗口也抓不到。

设计取舍 (Phase 1):
  - 模型 = 线性趋势 × 周内因子；七个星期几不齐或观测不足 → 退回纯趋势
    (model_type='linear_trend')，不引入 statsmodels。
  - 离群剔除复用 smartbi.utils.outlier_stats.iqr_fence / find_outliers_iqr
    (跟 OutlierService 同一算法源，避免数字打架)。
  - < 14 个有效历史点 → 退化到「最近窗口均值」flat 预测 (model_type=
    'mean_fallback')，CI = ±均值的标准差。
  - 所有金额 float (gold 层已是 float dict)；预测下界 clamp 到 >= 0
    (营收不为负)。

Phase 2 (margin) 用 #57 已 merge 的成本/毛利 silver，扩展为多指标预测。
"""
from __future__ import annotations

import logging
from datetime import date, timedelta
from typing import Any, Dict, List, Optional, Tuple

import asyncpg

from smartbi.utils.outlier_stats import find_outliers_iqr, iqr_fence

logger = logging.getLogger(__name__)

_MIN_POINTS_FOR_TREND = 14   # 少于此 → mean_fallback
_Z_80 = 1.28155              # 80% two-sided CI z-score (±1.28σ)

# ── 周内项 (2026-08-10 加) ───────────────────────────────────────────────────
# 回测实测: 不带周内项时本实现在 h=1/7/30 全部输给「上周同日」这条一行基线
# (27.3/27.8/27.7% vs 18.9/19.5/19.7% MAPE), 稳定差 ~8 个百分点。
# 原因是结构性的 —— 餐饮日营收的主信号就是星期几 (RES_3101_009 实测
# 周六 53.1万 / 周一 29.4万 = 1.81×), 一元线性回归里没有这一项, 抓不到。
_MIN_OBS_PER_DOW = 3         # 每个星期几至少要有这么多观测, 否则不启用周内项
_DOW_FACTOR_FLOOR = 0.3      # 因子落到区间外 → 视为数据异常, 退回无周内项
_DOW_FACTOR_CEIL = 3.0


async def _fetch_daily_revenue(
    pool: asyncpg.Pool,
    factory_id: str,
    anchor: date,
    window_days: int,
    store_id: Optional[int],
) -> List[Tuple[date, float]]:
    """Trailing-window daily net_amount from gold agg_daily, ascending.

    Window is (anchor - window_days, anchor] inclusive of anchor. Missing
    days are simply absent (not zero-filled) — the trend fit operates on the
    observed days only.
    """
    start = anchor - timedelta(days=window_days)
    async with pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", factory_id
        )
        if store_id is None:
            rows = await conn.fetch(
                """
                SELECT date, SUM(net_amount)::numeric(18,2) AS rev
                  FROM agg_daily
                 WHERE factory_id = $1 AND date > $2 AND date <= $3
                 GROUP BY date
                 ORDER BY date
                """,
                factory_id, start, anchor,
            )
        else:
            rows = await conn.fetch(
                """
                SELECT date, SUM(net_amount)::numeric(18,2) AS rev
                  FROM agg_daily
                 WHERE factory_id = $1 AND date > $2 AND date <= $3
                   AND store_id = $4
                 GROUP BY date
                 ORDER BY date
                """,
                factory_id, start, anchor, store_id,
            )
    return [(r["date"], float(r["rev"])) for r in rows if r["rev"] is not None]


def _clean_outliers(
    series: List[Tuple[date, float]],
) -> List[Tuple[date, float]]:
    """Drop IQR outliers from a (date, value) series (1.5×IQR fence).

    Reuses smartbi.utils.outlier_stats (same algorithm as OutlierService).
    N<4 → fence is None → return unchanged (too few to define an IQR).
    """
    values = [v for _, v in series]
    fence = iqr_fence(values, multiplier=1.5)
    if fence is None:
        return series
    outlier_idx = {o.index for o in find_outliers_iqr(values, fence)}
    return [pt for i, pt in enumerate(series) if i not in outlier_idx]


def _dow_factors(ys, fitted, dows: List[int]) -> Optional[Dict[int, float]]:
    """按星期几的乘性因子 = mean(实际 / 趋势拟合值)。

    返回 None 表示**不该启用周内项**, 调用方原样退回一元线性趋势。三种情况:
      · 有星期几观测数不足 _MIN_OBS_PER_DOW  —— 样本太少, 因子是噪声
      · 七个星期几没凑齐                      —— 缺的那天没有因子可用
      · 任一因子落到 [floor, ceil] 之外        —— 数据异常, 别把它放大进预测

    ⚠️ 因子做归一化(除以均值), 否则拟合值本身已含平均水平, 再乘一遍会整体抬高。
    """
    import numpy as np

    by_dow: Dict[int, List[float]] = {}
    for y, f, w in zip(ys, fitted, dows):
        if f <= 0:
            continue          # 趋势线跌到 0 以下, 比值没有意义
        by_dow.setdefault(w, []).append(float(y) / float(f))

    if len(by_dow) < 7 or any(len(v) < _MIN_OBS_PER_DOW for v in by_dow.values()):
        return None

    raw = {w: float(np.mean(v)) for w, v in by_dow.items()}
    scale = float(np.mean(list(raw.values())))
    if scale <= 0:
        return None
    factors = {w: r / scale for w, r in raw.items()}

    if any(not (_DOW_FACTOR_FLOOR <= f <= _DOW_FACTOR_CEIL)
           for f in factors.values()):
        return None
    return factors


def compute_rolling_forecast(
    series: List[Tuple[date, float]],
    *,
    anchor: date,
    horizon_days: int = 30,
    window_days: int = 90,
) -> Dict[str, Any]:
    """Pure forecast core (no DB) — testable in isolation.

    1. IQR-clean the series.
    2. If >= _MIN_POINTS_FOR_TREND cleaned points → numpy linear polyfit on
       day-ordinal x; project the next horizon_days. CI = ±_Z_80 * residual
       std (population std of fit residuals).
    3. Else → flat mean fallback: point = mean(cleaned), CI = ±_Z_80 * std.
    4. lower_bound clamped to >= 0 (revenue can't be negative).

    Returns {model_type, anchor_date, window_days, points:[{date,
    forecast_amount, lower_bound, upper_bound}]}. Empty series → honest empty
    (points=[], model_type='no_data').
    """
    import numpy as np

    cleaned = _clean_outliers(series) if series else []
    base = {
        "anchor_date": anchor.isoformat(),
        "window_days": window_days,
        "horizon_days": horizon_days,
    }
    if not cleaned:
        return {**base, "model_type": "no_data", "points": []}

    # Use day ordinals relative to the earliest cleaned date as x.
    x0 = cleaned[0][0].toordinal()
    xs = np.array([d.toordinal() - x0 for d, _ in cleaned], dtype=float)
    ys = np.array([v for _, v in cleaned], dtype=float)

    if len(cleaned) >= _MIN_POINTS_FOR_TREND:
        slope, intercept = np.polyfit(xs, ys, 1)
        fitted = slope * xs + intercept
        dows = [d.weekday() for d, _ in cleaned]
        factors = _dow_factors(ys, fitted, dows)

        if factors is None:
            residuals = ys - fitted
            model_type = "linear_trend"

            def predict(x_ord_rel: float, _dow: int) -> float:
                return float(slope * x_ord_rel + intercept)
        else:
            # 乘性周内因子。残差在**乘完之后**再算 —— 否则 CI 会带着已被解释掉
            # 的周内波动, 白白宽出一截。
            adjusted = fitted * np.array([factors[w] for w in dows], dtype=float)
            residuals = ys - adjusted
            model_type = "linear_trend_dow"

            def predict(x_ord_rel: float, dow: int) -> float:
                return float((slope * x_ord_rel + intercept) * factors[dow])

        sigma = float(np.std(residuals))  # population std
    else:
        mean_v = float(np.mean(ys))
        sigma = float(np.std(ys))
        model_type = "mean_fallback"

        def predict(x_ord_rel: float, _dow: int) -> float:  # noqa: ARG001
            return mean_v

    margin = _Z_80 * sigma
    points: List[Dict[str, Any]] = []
    for h in range(1, horizon_days + 1):
        fdate = anchor + timedelta(days=h)
        x_rel = float(fdate.toordinal() - x0)
        pt = predict(x_rel, fdate.weekday())
        pt = max(pt, 0.0)  # revenue floor
        lower = max(pt - margin, 0.0)
        upper = pt + margin
        points.append({
            "date": fdate.isoformat(),
            "forecast_amount": round(pt, 2),
            "lower_bound": round(lower, 2),
            "upper_bound": round(upper, 2),
        })

    return {**base, "model_type": model_type, "points": points}


async def forecast_revenue(
    pool: asyncpg.Pool,
    factory_id: str,
    *,
    horizon_days: int = 30,
    window_days: int = 90,
    store_id: Optional[int] = None,
    anchor: Optional[date] = None,
    persist: bool = False,
) -> Dict[str, Any]:
    """Fetch gold history → compute_rolling_forecast → (optionally) persist.

    anchor defaults to the latest agg_daily date for the tenant (so forecasts
    are anchored to real data, not wall-clock today which may be ahead of the
    data window). When the tenant has no gold rows, returns honest empty.

    When persist=True, UPSERTs the forecast points into
    restaurant_target_forecast (version bumped past the current max for this
    factory/store). Fail-soft: persistence errors are logged, never raised —
    the computed forecast is still returned.
    """
    if not factory_id:
        raise ValueError("factory_id required")

    # Resolve anchor to the latest data date if not given.
    if anchor is None:
        async with pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )
            row = await conn.fetchrow(
                "SELECT MAX(date) AS mx FROM agg_daily WHERE factory_id = $1",
                factory_id,
            )
        anchor = row["mx"] if row and row["mx"] else None
    if anchor is None:
        return {
            "factory_id": factory_id,
            "model_type": "no_data",
            "anchor_date": None,
            "window_days": window_days,
            "horizon_days": horizon_days,
            "points": [],
            "message": "该工厂暂无 gold 营收数据，无法预测。",
        }

    series = await _fetch_daily_revenue(
        pool, factory_id, anchor, window_days, store_id
    )
    result = compute_rolling_forecast(
        series, anchor=anchor, horizon_days=horizon_days,
        window_days=window_days,
    )
    result["factory_id"] = factory_id
    result["store_id"] = store_id

    if persist and result["points"]:
        try:
            await _persist_forecast(pool, factory_id, store_id, result)
        except Exception as exc:  # fail-soft (never doom the request)
            logger.warning("forecast persist failed (non-fatal): %s", exc)

    return result


async def _persist_forecast(
    pool: asyncpg.Pool,
    factory_id: str,
    store_id: Optional[int],
    result: Dict[str, Any],
) -> None:
    """UPSERT forecast points with a freshly bumped version (REQUIRES_NEW-ish).

    Uses its own connection/transaction so a persistence failure cannot poison
    a surrounding caller transaction (cross-service isolation per HARD RULE).
    """
    from datetime import date as _date

    anchor = _date.fromisoformat(result["anchor_date"])
    async with pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", factory_id
        )
        async with conn.transaction():
            if store_id is None:
                maxv = await conn.fetchval(
                    """
                    SELECT COALESCE(MAX(version), 0)
                      FROM restaurant_target_forecast
                     WHERE factory_id = $1 AND store_id IS NULL
                    """,
                    factory_id,
                )
            else:
                maxv = await conn.fetchval(
                    """
                    SELECT COALESCE(MAX(version), 0)
                      FROM restaurant_target_forecast
                     WHERE factory_id = $1 AND store_id = $2
                    """,
                    factory_id, store_id,
                )
            version = int(maxv) + 1
            for p in result["points"]:
                await conn.execute(
                    """
                    INSERT INTO restaurant_target_forecast
                        (factory_id, store_id, forecast_date, forecast_amount,
                         lower_bound, upper_bound, model_type, anchor_date,
                         window_days, version)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
                    """,
                    factory_id, store_id,
                    date.fromisoformat(p["date"]),
                    p["forecast_amount"], p["lower_bound"], p["upper_bound"],
                    result["model_type"], anchor,
                    result["window_days"], version,
                )
