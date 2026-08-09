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

# ── 断层 (2026-08-10 加) ─────────────────────────────────────────────────────
# 实际水位偏离「按目前势头外推出的水位」达到这个倍数, 视为体制切换(灌数据 / 门店
# 重开 / 口径变更), 断层之前的历史不能与之后的混在一起拟合。
# 缘起: prod 实测 RES_3101_009 的 90 日窗内有 3.0× 与 5.9× 两处突变, DEMO_REST
# 有 2.1× —— 而加这道判据之前的代码照算不误, 给出一个像模像样的数。
# ⚠️ 阈值与 scripts/forecast_backtest.py 的闸两边都硬编 2.0, 是刻意的: 生产不该
#    依赖 scripts/, 而让脚本 import 生产会让一致性断言变成恒真式。由
#    test_break_threshold_matches_the_gate 逐字比对两处字面量, 漂移即红。
_BREAK_RATIO = 2.0
_BREAK_WINDOW = 7            # 比较用的滑动窗天数
_MIN_POINTS_FOR_CLEAN = 4    # 少于此 → 连均值都不给, 如实说没有


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


def _trim_to_current_regime(
    series: List[Tuple[date, float]],
) -> Tuple[List[Tuple[date, float]], Optional[date]]:
    """截到**最后一个**体制断层之后的那一段。返回 (截后序列, 断层日期或 None)。

    判据: 拿前两个 _BREAK_WINDOW 日窗的均值差外推出「按目前势头下一窗该在哪」,
    实际值与它相差 >= _BREAK_RATIO 倍即为断层。**不是**「相邻两窗之比 >= 2×」——
    那个写法会把平稳高增长误判成断层, 理由见下方实现处的长注释。

    为什么取「最后一个」而不是第一个: 窗口里可能有多次灌数据(prod 实测
    RES_3101_009 的 90 日窗内就有两处, 3.0× 和 5.9×)。只有最后一段才是当前体制。

    ⚠️ 这里**不做**「太短就退回全量」的兜底 —— 那等于用一个更长但跨体制的序列
    去换一个好看的数字, 正是本函数要拦的东西。截完太短由调用方如实拒答。
    """
    n = len(series)
    if n < _BREAK_WINDOW * 2:
        return series, None

    vals = [v for _, v in series]
    w = _BREAK_WINDOW

    # ⚠️ 判据不能是「相邻两窗的比值 >= 2×」。那样会把**平稳的高增长**误判成断层:
    #    一条从 100 线性涨到 6000 的干净序列, 头两周就是 400 → 1100(2.75×), 于是
    #    真实门店只要长得够快就被告知「数据有断层」。2026-08-10 实装时当场踩到。
    #
    #    断层的本质是**跳出趋势**, 不是陡峭。所以拿前一窗自己的斜率外推出一个
    #    「按目前势头该是多少」, 再看实际值偏离它多少倍:
    #        平稳爬坡 → 外推值≈实际值, 偏离 ≈1.0×  → 不是断层
    #        灌数据台阶 → 前窗斜率≈0, 外推≈旧水位, 实际是新水位 → 偏离就是台阶高度
    #    势头由**前两个窗的均值之差**给出, ⛔ 不能用窗内最小二乘斜率 —— 窗长正好
    #    是 7 天, 与周内周期相等, 「周一起头」的窗内部天然是上坡(工作日在前、周末
    #    在后), 拿它外推会凭空多出一截, 把干净的周内规律判成断层。而窗**均值**里
    #    每个星期几各一次, 对周内效应免疫。
    if n < w * 3:
        return series, None
    breaks: List[int] = []
    for i in range(w * 2, n - w + 1):
        m0 = sum(vals[i - w * 2:i - w]) / w
        m1 = sum(vals[i - w:i]) / w
        m2 = sum(vals[i:i + w]) / w
        expected = m1 + (m1 - m0)          # 按目前势头, 下一窗该在哪
        if expected <= 0 or m2 <= 0:
            continue
        if max(expected, m2) / min(expected, m2) >= _BREAK_RATIO:
            breaks.append(i)
    if not breaks:
        return series, None

    # 一个台阶会让连续若干个 i 都命中(检测窗正跨在台阶上)。取最后那一组, 然后在
    # 它附近**精定位到台阶那一天** —— 检测是「下一窗里混进了新体制」才触发的, 比
    # 台阶本身早几天; 直接用触发点会把几天旧体制的数据留在里面继续污染拟合。
    start = breaks[-1]
    for j in range(len(breaks) - 1, 0, -1):
        if breaks[j] - breaks[j - 1] > 1:
            break
        start = breaks[j - 1]

    # ⚠️ 精定位的右界必须留够 w 天, 否则它会把断层点推到序列末尾。实测: 当天数据
    #    不完整(取到一半), 看起来像一次暴跌, 精定位一眼相中这个「最大跳变」, 于是
    #    RES_3101_009 被判成「断层在昨天, 截后只剩 1 天」—— 这条路会让**每个租户
    #    每天**都被告知数据有断层。当天不完整不是体制切换。
    hi = min(start + w, n - w)
    best, best_jump = start, -1.0
    for k in range(start + 1, hi + 1):
        base = max(abs(vals[k - 1]), 1e-9)
        jump = abs(vals[k] - vals[k - 1]) / base
        if jump > best_jump:
            best, best_jump = k, jump

    # 断层之后不足一个窗 → 不成其为「一个体制」, 当没发生(宁可不截, 也不要拿几天
    # 数据当全部依据)。截完由调用方按点数决定给不给预测。
    if n - best < w:
        return series, None
    return series[best:], series[best][0]


_BACKTEST_MIN_TRAIN = 14     # 训练窗下限, 与 _MIN_POINTS_FOR_TREND 对齐
_BACKTEST_MAX_ORIGINS = 60   # 起点上限, 防止长窗口把答题时延拉长


def _fit(cleaned: List[Tuple[date, float]]):
    """拟合。返回 (predict(x_rel, dow)->float, model_type, sigma, x0)。

    ⛔ 出数和回测**必须走这一个函数** —— 否则报出去的「历史误差 ±X%」描述的是
       另一个模型, 那比不报还糟: 用户会拿一个漂亮的误差去信一个没被量过的数。
    """
    import numpy as np

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

            def predict(x_rel: float, _dow: int) -> float:
                return float(slope * x_rel + intercept)
        else:
            # 乘性周内因子。残差在**乘完之后**再算 —— 否则 CI 会带着已被解释掉的
            # 周内波动, 白白宽出一截。
            adjusted = fitted * np.array([factors[w] for w in dows], dtype=float)
            residuals = ys - adjusted
            model_type = "linear_trend_dow"

            def predict(x_rel: float, dow: int) -> float:
                return float((slope * x_rel + intercept) * factors[dow])

        return predict, model_type, float(np.std(residuals)), x0

    mean_v = float(np.mean(ys))

    def predict_flat(_x_rel: float, _dow: int) -> float:
        return mean_v

    return predict_flat, "mean_fallback", float(np.std(ys)), x0


def _fit_and_project(
    train: List[Tuple[date, float]], target_dates: List[date]
) -> Optional[List[float]]:
    """回测用: 拿 train 拟合, 吐出 target_dates 上的点估计。与出数同源(见 _fit)。"""
    cleaned = _clean_outliers(train)
    if len(cleaned) < _MIN_POINTS_FOR_CLEAN:
        return None
    try:
        predict, _mt, _sigma, x0 = _fit(cleaned)
    except Exception:
        return None
    return [max(predict(float(d.toordinal() - x0), d.weekday()), 0.0)
            for d in target_dates]


def _self_backtest_mape(
    series: List[Tuple[date, float]], horizon_days: int
) -> Optional[float]:
    """拿这个租户自己的历史滚动回测本模型, 返回平均 MAPE(0~1), 算不出返回 None。

    **这是要给用户看的数**, 不是内部调参用的: 「预测 42.3 万(历史回测误差 ±17%)」
    比一个光秃秃的 42.3 万诚实得多, 而误差大到离谱时这个数字会自己把自己否掉。

    ⚠️ 起点逐日推进而非抽样 —— 抽样会让「碰巧准的那几天」主导读数。超过
       _BACKTEST_MAX_ORIGINS 时从**最近**的一段取, 近期表现比远期更能代表现在。
    ⚠️ 每个起点只用该起点**之前**的数据训练, 不得看未来。
    """
    n = len(series)
    if n < _BACKTEST_MIN_TRAIN + horizon_days:
        return None
    starts = list(range(_BACKTEST_MIN_TRAIN, n - horizon_days + 1))
    if len(starts) > _BACKTEST_MAX_ORIGINS:
        starts = starts[-_BACKTEST_MAX_ORIGINS:]

    errs: List[float] = []
    for i in starts:
        train, actual = series[:i], series[i:i + horizon_days]
        pred = _fit_and_project(train, [d for d, _ in actual])
        if pred is None:
            continue
        pairs = [(a, p) for (_, a), p in zip(actual, pred) if a]
        if not pairs:
            continue
        errs.append(sum(abs(a - p) / abs(a) for a, p in pairs) / len(pairs))
    if not errs:
        return None
    return sum(errs) / len(errs)


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

    base = {
        "anchor_date": anchor.isoformat(),
        "window_days": window_days,
        "horizon_days": horizon_days,
    }

    # ── 断层前置判据 (2026-08-10) ────────────────────────────────────────────
    # 窗口跨越体制切换时, 拿断层前的数据拟合出来的趋势是**没有意义**的。
    # 实测 prod: RES_3101_009 的 90 日窗内有 3.0× 与 5.9× 两处突变(灌数据留下的),
    # DEMO_REST 有 2.1× —— 而改动前后的代码都照算不误, 给出一个像模像样的数。
    # 处理: 截到最后一个断层之后的那一段(那才是当前体制); 截完太短就如实说没有,
    # 绝不拿跨体制的数据硬拟合。对齐 CLAUDE.md 原则 1「禁止降级处理」。
    # ⛔ 截不截, **不由阈值拍板, 由这个租户自己的历史回测拍板**。
    #
    #    2026-08-10 的教训: 一开始想用「相邻窗均值突变 >= 2×」这条阈值判断层然后
    #    截掉。半小时内它冒出四个假阳性 —— 平稳高增长 / 周内规律(窗长恰等于周期)
    #    / 当天数据不完整 / V 形凹陷。每堵一个又冒一个新的, 那是打补丁的形状。
    #    而「这段数据能不能用」本来就是可以**量**的, 不必猜: 两种窗口各自滚动回测
    #    一次, 谁的误差小用谁, 并把误差原样报给用户。
    cleaned_all = _clean_outliers(series) if series else []
    trimmed, break_at = _trim_to_current_regime(cleaned_all)

    cleaned = cleaned_all
    if break_at is not None and len(trimmed) >= _MIN_POINTS_FOR_CLEAN:
        err_full = _self_backtest_mape(cleaned_all, horizon_days)
        err_trim = _self_backtest_mape(trimmed, horizon_days)
        base["data_break_at"] = break_at.isoformat()
        base["points_after_break"] = len(trimmed)
        if err_trim is not None and (err_full is None or err_trim < err_full):
            cleaned = trimmed
            base["window_used"] = "after_break"
        else:
            base["window_used"] = "full"
        base["backtest_mape_full"] = err_full
        base["backtest_mape_after_break"] = err_trim

    # 无论截没截, 都把**实际使用的那个窗口**的回测误差报出来 —— 用户据此判断这个
    # 数字值不值得信。误差算不出来(样本太少)就是 None, 绝不填一个好看的默认值。
    base["backtest_mape"] = _self_backtest_mape(cleaned, horizon_days)
    if len(cleaned) < _MIN_POINTS_FOR_CLEAN:
        return {
            **base,
            "model_type": "data_break" if break_at is not None else "no_data",
            "points": [],
        }

    # ⛔ 出数走 _fit, 回测(_self_backtest_mape → _fit_and_project)也走 _fit。
    #    同源是硬要求 —— 否则报出去的「历史误差 ±X%」量的是另一个模型。
    predict, model_type, sigma, x0 = _fit(cleaned)

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
