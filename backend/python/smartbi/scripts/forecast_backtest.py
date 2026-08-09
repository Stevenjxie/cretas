#!/usr/bin/env python3
"""预测回测闸 —— 先证明数据里有可失败的东西, 再谈算法准不准。

## ⛔ 这个闸的第一职责

不是「算法准不准」, 而是**拒绝在没有信号的序列上发绿灯**。

2026-08-10 审计实测: 若把本闸跑在 `MOCK_REST`(餐饮回归电池的默认租户)上 ——

    42 天 | 单量恒定 1986~2000/天 | CV 1.60%
    周末 71.97万  vs  工作日 71.91万   (差 0.08%)

在这种序列上「预测 = 历史均值」就有 ~1.3% MAPE, **任何算法都是满分**。
闸会绿得很漂亮, 而它什么都没证明。这是「闸不可能变红」的第三种形状:
不是闸没跑, 不是左右同源(恒真式), 而是**被测数据里没有可失败的东西**。

判据: **验证数据集的选择本身就是闸的一部分, 要和断言一起论证。**
朴素基线的误差 = 这个闸的天花板; 天花板 1.3% 的闸没有信息量。

## 执行顺序 (顺序即判据)

  ① 量信号   拿不出「朴素基线会错多少」就不许往下走 → REFUSE(**不是 PASS**)
  ② 查断层   灌数据留下的台阶会让训练窗跨越两个体制, 跨断层预测必然是垃圾
  ③ 朴素基线 seasonal-naive(上周同日) / mean —— 它们定义本闸的天花板
  ④ 算法对照 必须显著优于**最好的**朴素基线才算通过

## 用法

    SMARTBI_DB_PASSWORD=... python -m smartbi.scripts.forecast_backtest \\
        --factory-id RES_3101_009 --start 2026-01-01 --end 2026-04-30 --horizon 7

退出码: 0=PASS  1=FAIL(算法赢不了朴素基线)  2=REFUSE(数据不配当验证集)
"""
from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import os
import statistics
import sys
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import asyncpg

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))

from smartbi.services.forecast_service import ForecastService  # noqa: E402

# ── 信号下限 ────────────────────────────────────────────────────────────────
# 低于这两条线的序列**不配当验证集** —— 在它上面无论算法多蠢都会及格。
MIN_CV = 0.05           # 日营收变异系数; MOCK_REST 实测 0.016 → 会被拒
MIN_DOW_RATIO = 1.15    # 最忙星期 / 最闲星期; MOCK_REST 实测 ~1.00 → 会被拒

# ── 断层判据 ────────────────────────────────────────────────────────────────
# 相邻 7 日均值跨越这个倍数, 视为灌数据留下的体制切换而非业务波动。
# RES_3101_009 实测 2026-05 整月塌到 2.2万/天(CV 0.2%), 而 4 月是 37.0万。
BREAK_RATIO = 2.0

MIN_TRAIN_DAYS = 28     # 训练窗下限: 至少 4 个完整周, 否则谈不上周内规律
BEAT_MARGIN = 0.10      # 算法 MAPE 必须比最好的朴素基线低 10%(相对) 才算赢

# 目标测算线上那条路的实现, 和候选算法一起摆进同一张表比 —— 免得只能"按同一
# 模型类推断"。它参与评比(是算法的一种), 但单独打标, 因为它已经在给用户看数了。
PRODUCTION_ROW = "target_forecast(线上目标测算)"
CANDIDATE_ROW = "target_forecast(改动候选)"

ALGORITHMS = (
    "moving_average",
    "linear_trend",
    "exponential_smoothing",
    "seasonal_decomposition",
    "auto",
)


# ══ 取数 ════════════════════════════════════════════════════════════════════

async def pull_series(
    conn, factory_id: str, start: dt.date, end: dt.date
) -> List[Tuple[dt.date, float]]:
    """日营收序列。RLS 是连接级的, GUC 由调用方在**同一条连接**上设好。"""
    rows = await conn.fetch(
        """
        SELECT date, sum(net_amount)::float8 AS v
        FROM fact_pos_transaction
        WHERE factory_id = $1 AND date BETWEEN $2 AND $3
        GROUP BY date ORDER BY date
        """,
        factory_id, start, end,
    )
    return [(r["date"], float(r["v"])) for r in rows]


# ══ ① 信号 ══════════════════════════════════════════════════════════════════

class SignalReport:
    def __init__(self, series: Sequence[Tuple[dt.date, float]]):
        self.n = len(series)
        vals = [v for _, v in series]
        self.mean = statistics.fmean(vals) if vals else 0.0
        self.stdev = statistics.stdev(vals) if len(vals) > 1 else 0.0
        self.cv = (self.stdev / self.mean) if self.mean else 0.0

        by_dow: Dict[int, List[float]] = {}
        for d, v in series:
            by_dow.setdefault(d.weekday(), []).append(v)
        self.dow_mean = {k: statistics.fmean(v) for k, v in sorted(by_dow.items())}
        if len(self.dow_mean) >= 7 and min(self.dow_mean.values()) > 0:
            self.dow_ratio = max(self.dow_mean.values()) / min(self.dow_mean.values())
        else:
            self.dow_ratio = 1.0

    @property
    def usable(self) -> bool:
        return (
            self.n >= MIN_TRAIN_DAYS + 7
            and self.cv >= MIN_CV
            and self.dow_ratio >= MIN_DOW_RATIO
        )

    def reasons(self) -> List[str]:
        out = []
        if self.n < MIN_TRAIN_DAYS + 7:
            out.append(f"样本 {self.n} 天 < 下限 {MIN_TRAIN_DAYS + 7} 天")
        if self.cv < MIN_CV:
            out.append(
                f"变异系数 {self.cv:.2%} < 下限 {MIN_CV:.0%} —— "
                f"序列太平, 「预测=均值」就能拿满分")
        if self.dow_ratio < MIN_DOW_RATIO:
            out.append(
                f"最忙/最闲星期 = {self.dow_ratio:.2f}× < 下限 {MIN_DOW_RATIO}× —— "
                f"没有周内规律可供预测")
        return out


# ══ ② 断层 ══════════════════════════════════════════════════════════════════

def find_breaks(series: Sequence[Tuple[dt.date, float]]) -> List[Tuple[dt.date, float]]:
    """相邻 7 日窗均值突变点。返回 [(断层日, 倍数)]。"""
    vals = [v for _, v in series]
    breaks: List[Tuple[dt.date, float]] = []
    for i in range(7, len(vals) - 6):
        prev = statistics.fmean(vals[i - 7:i])
        nxt = statistics.fmean(vals[i:i + 7])
        if prev <= 0 or nxt <= 0:
            continue
        ratio = max(prev, nxt) / min(prev, nxt)
        if ratio >= BREAK_RATIO:
            breaks.append((series[i][0], ratio))
    # 同一台阶会连续命中若干天, 只留每段的第一天
    deduped: List[Tuple[dt.date, float]] = []
    for d, r in breaks:
        if not deduped or (d - deduped[-1][0]).days > 7:
            deduped.append((d, r))
    return deduped


# ══ ③ 朴素基线 ══════════════════════════════════════════════════════════════

def naive_mean(train: Sequence[float], horizon: int) -> List[float]:
    return [statistics.fmean(train)] * horizon


def naive_seasonal(train: Sequence[float], horizon: int) -> List[float]:
    """上周同日 —— 餐饮最该被打败的那条基线。"""
    if len(train) < 7:
        return naive_mean(train, horizon)
    return [train[-7 + (j % 7)] for j in range(horizon)]


BASELINES: Dict[str, Callable[[Sequence[float], int], List[float]]] = {
    "naive_mean(均值)": naive_mean,
    "naive_seasonal(上周同日)": naive_seasonal,
}


# ══ ④ 滚动回测 ══════════════════════════════════════════════════════════════

def mape(actual: Sequence[float], pred: Sequence[float]) -> Optional[float]:
    pairs = [(a, p) for a, p in zip(actual, pred) if a]
    if not pairs:
        return None
    return statistics.fmean(abs(a - p) / abs(a) for a, p in pairs)


Series = Sequence[Tuple[dt.date, float]]
# 预测器统一签名: 拿到**带日期的**训练序列, 吐 horizon 个点。
# ⚠️ 必须带日期 —— 生产实现 `compute_rolling_forecast` 要 anchor, 只传 float
#    就没法把它原样接进来测, 只能"按同一模型类推断", 而推断正是今天翻车两次的地方。
Forecaster = Callable[[Series, int], Optional[List[float]]]


def all_origins(series: Series, horizon: int) -> List[int]:
    return list(range(MIN_TRAIN_DAYS, len(series) - horizon + 1))


def rolling_backtest(
    series: Series, forecaster: Forecaster, horizon: int,
    origins: Optional[List[int]] = None,
) -> Tuple[Optional[float], int]:
    """滚动起点回测。返回 (平均 MAPE, 起点数)。

    ⚠️ 起点必须**逐日**推进而不是抽样 —— 抽样会让「碰巧好的那几天」主导读数。
       只有一种例外: 接了 LLM 时按次数收敛(每个起点一次真实调用), 此时**所有**
       方法都跑同一批 `origins`, 否则比的不是同一件事。
    """
    errs: List[float] = []
    for i in (origins if origins is not None
              else range(MIN_TRAIN_DAYS, len(series) - horizon + 1)):
        train = series[:i]
        actual = [v for _, v in series[i:i + horizon]]
        try:
            pred = forecaster(train, horizon)
        except Exception:
            pred = None
        if not pred or len(pred) < horizon:
            continue
        e = mape(actual, pred[:horizon])
        if e is not None:
            errs.append(e)
    if not errs:
        return None, 0
    return statistics.fmean(errs), len(errs)


def values_only(fn: Callable[[Sequence[float], int], List[float]]) -> Forecaster:
    return lambda ts, h: fn([v for _, v in ts], h)


def make_algo_forecaster(svc: ForecastService, algorithm: str) -> Forecaster:
    def _f(train: Series, horizon: int) -> Optional[List[float]]:
        res = svc.forecast(
            data=[v for _, v in train], algorithm=algorithm,
            periods=horizon, seasonality=7)
        if not res.get("success"):
            return None
        return res.get("predictions")
    return _f


def _make_target_forecaster(compute) -> Forecaster:
    def _f(train: Series, horizon: int) -> Optional[List[float]]:
        res = compute(
            list(train), anchor=train[-1][0],
            horizon_days=horizon, window_days=90)
        pts = res.get("points") or []
        if not pts:
            return None
        return [float(p["forecast_amount"]) for p in pts]
    return _f


LLM_ROW = "LLM(qwen/glm 链)"
_LLM_MAX_HISTORY = 42        # 塞给模型的历史天数上限, 再多只是烧 token


def _llm_prompt(train: Series, horizon: int) -> str:
    names = "一二三四五六日"
    tail = train[-_LLM_MAX_HISTORY:]
    lines = [f"{d.isoformat()} 周{names[d.weekday()]} {v / 10000:.2f}"
             for d, v in tail]
    nxt = [tail[-1][0] + dt.timedelta(days=k + 1) for k in range(horizon)]
    want = [f"{d.isoformat()} 周{names[d.weekday()]}" for d in nxt]
    return (
        "你是餐饮经营分析师。下面是某门店每日营收(单位: 万元), 按日期升序:\n"
        + "\n".join(lines)
        + "\n\n请预测接下来这几天的日营收(单位: 万元):\n"
        + "\n".join(want)
        + "\n\n只输出一个 JSON 数组, 元素是数字, 长度必须正好 "
        + f"{horizon}, 顺序与上面一致。不要任何解释、单位或代码块标记。"
    )


async def llm_predictions(
    series: Series, horizon: int, origins: List[int]
) -> Dict[int, List[float]]:
    """给每个起点各问一次 LLM。返回 {len(train): [预测值...]}(万元还原成元)。

    ⚠️ 失败(超时/额度/格式不符)一律**不记入**, 而不是填一个数 —— 填数会让 LLM
       的 MAPE 被无关的东西拉高或拉低, 量出来的就不是它的能力。
    """
    import json
    import re

    from common.llm_router import SLOT, call_chain

    out: Dict[int, List[float]] = {}
    for i in origins:
        train = series[:i]
        try:
            resp = await call_chain(
                SLOT.REVIEW,
                {"messages": [{"role": "user",
                               "content": _llm_prompt(train, horizon)}],
                 "temperature": 0,
                 "max_tokens": 512},
                timeout=40.0, total_timeout=90.0,
            )
        except Exception as exc:                      # noqa: BLE001
            logger_warn(f"origin={i} 调用失败: {type(exc).__name__}")
            continue
        # ⚠️ 返回是 OpenAI 形状。第一版我写成 resp["content"], 恒为空字符串, 于是
        #    12/12 全「失败」—— 看起来像一个关于 LLM 能力的发现, 其实是我读错了
        #    字段。判据: **探针拿到全 0 / 全空时, 先怀疑探针**。
        try:
            text = (resp["choices"][0]["message"]["content"] or "").strip()
        except (KeyError, IndexError, TypeError):
            logger_warn(f"origin={i} 返回形状不对: {str(resp)[:80]!r}")
            continue
        m = re.search(r"\[[^\[\]]*\]", text, re.S)
        if not m:
            logger_warn(f"origin={i} 没吐出数组: {text[:60]!r}")
            continue
        try:
            arr = json.loads(m.group(0))
        except Exception:                             # noqa: BLE001
            logger_warn(f"origin={i} 数组解析失败: {m.group(0)[:60]!r}")
            continue
        if not isinstance(arr, list) or len(arr) != horizon:
            logger_warn(f"origin={i} 长度不符: 要 {horizon} 得 {len(arr) if isinstance(arr, list) else '非数组'}")
            continue
        try:
            out[i] = [float(x) * 10000.0 for x in arr]
        except Exception:                             # noqa: BLE001
            logger_warn(f"origin={i} 元素非数字")
    return out


def logger_warn(msg: str) -> None:
    print(f"    [llm] {msg}")


def load_production_target_compute():
    """**目标测算线上正在用的那个实现**, 原样接进来测。

    `GET /restaurant-targets/forecast?horizon_days=30` → `forecast_revenue`
    → `compute_rolling_forecast` (90 日窗 + IQR 去异常 + 一元线性回归)。
    """
    from smartbi.services.target_forecast import compute_rolling_forecast
    return compute_rolling_forecast


def load_candidate_target_compute(path: str):
    """从**任意路径**加载一份改过的 target_forecast, 和线上版摆进同一张表比。

    ⛔ 存在的理由: 服务器上 `smartbi/services/target_forecast.py` 是**运行中的
       prod 代码**, 不能为了跑一次回测就覆盖它。改动放 /tmp, 由这里按文件路径
       载入 —— prod 一个字节都不动。
    """
    import importlib.util
    spec = importlib.util.spec_from_file_location("_candidate_target_forecast", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"载不进来: {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.compute_rolling_forecast


# ══ 主流程 ══════════════════════════════════════════════════════════════════

async def run(factory_id: str, start: dt.date, end: dt.date, horizon: int,
              candidate_path: Optional[str] = None, llm_origins: int = 0) -> int:
    pool = await asyncpg.create_pool(
        host=os.getenv("SMARTBI_DB_HOST", "localhost"),
        user=os.getenv("SMARTBI_DB_USER", "smartbi_user"),
        database=os.getenv("SMARTBI_DB_NAME", "smartbi_prod_db"),
        password=os.environ["SMARTBI_DB_PASSWORD"], min_size=1, max_size=2)
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id)
            series = await pull_series(conn, factory_id, start, end)
    finally:
        await pool.close()

    print(f"租户 {factory_id}   窗口 {start} ~ {end}   预测跨度 {horizon} 天")
    print("=" * 72)

    if not series:
        print("BACKTEST REFUSE  取不到数据 —— 检查租户/窗口, 以及 RLS GUC 是否设在同一条连接上")
        return 2

    # ── ① 信号 ──
    sig = SignalReport(series)
    print(f"① 信号  {sig.n} 天 | 均值 {sig.mean / 10000:.2f}万 | "
          f"CV {sig.cv:.2%} | 最忙/最闲星期 {sig.dow_ratio:.2f}×")
    if sig.dow_mean:
        names = "一二三四五六日"
        print("        周内: " + "  ".join(
            f"周{names[k]} {v / 10000:.1f}万" for k, v in sig.dow_mean.items()))
    if not sig.usable:
        for r in sig.reasons():
            print(f"        ⛔ {r}")
        print()
        print("BACKTEST REFUSE  这段数据不配当验证集 —— 在它上面通过不构成证据")
        return 2

    # ── ② 断层 ──
    breaks = find_breaks(series)
    if breaks:
        for d, r in breaks:
            print(f"② 断层  ⛔ {d} 前后 7 日均值突变 {r:.1f}× —— 疑似灌数据体制切换")
        print()
        print("BACKTEST REFUSE  窗口内有断层, 训练窗会跨越两个体制 —— 请改用平稳窗口")
        return 2
    print("② 断层  无")

    # ── ③④ 基线与算法 ──
    svc = ForecastService()

    origins = all_origins(series, horizon)
    llm_by_origin: Dict[int, List[float]] = {}
    if llm_origins:
        # ⚠️ 每个起点一次真实 LLM 调用, 所以起点要收敛。收敛后**所有方法**都跑
        #    这同一批起点 —— 只给 LLM 换一套起点就成了两张不同的考卷。
        #    取**最近**的 N 个: 近期表现比远期更能代表现在。
        origins = origins[-llm_origins:]
        print(f"③ LLM  {len(origins)} 个起点 × 1 次调用 (所有方法同批起点)")
        llm_by_origin = await llm_predictions(series, horizon, origins)
        print(f"       成功 {len(llm_by_origin)}/{len(origins)} 次")

    rows: List[Tuple[str, Optional[float], int]] = []
    for name, fn in BASELINES.items():
        m, n = rolling_backtest(series, values_only(fn), horizon, origins)
        rows.append((name, m, n))
    baseline_best = min(
        (m for _, m, _ in rows if m is not None), default=None)

    for algo in ALGORITHMS:
        m, n = rolling_backtest(
            series, make_algo_forecaster(svc, algo), horizon, origins)
        rows.append((algo, m, n))

    if llm_origins:
        def _llm(train: Series, _h: int) -> Optional[List[float]]:
            return llm_by_origin.get(len(train))
        m, n = rolling_backtest(series, _llm, horizon, origins)
        rows.append((LLM_ROW, m, n))

    m, n = rolling_backtest(
        series, _make_target_forecaster(load_production_target_compute()), horizon)
    rows.append((PRODUCTION_ROW, m, n))

    if candidate_path:
        m, n = rolling_backtest(
            series,
            _make_target_forecaster(load_candidate_target_compute(candidate_path)),
            horizon)
        rows.append((CANDIDATE_ROW, m, n))

    print()
    print(f"{'方法':28s} {'MAPE':>8s} {'起点数':>7s}   判定")
    print("-" * 72)
    algo_best: Optional[float] = None
    algo_best_name = ""
    for name, m, n in rows:
        if m is None:
            print(f"{name:28s} {'—':>8s} {n:>7d}   算不出")
            continue
        is_baseline = name in BASELINES
        tag = "基线(天花板)" if is_baseline else ""
        if name == PRODUCTION_ROW:
            tag = "⚠️ 已在给用户看数"
        if not is_baseline:
            if algo_best is None or m < algo_best:
                algo_best, algo_best_name = m, name
        print(f"{name:28s} {m:>7.2%} {n:>7d}   {tag}")

    print()
    if baseline_best is None or algo_best is None:
        print("BACKTEST FAIL  基线或算法一侧算不出来, 无法比较")
        return 1

    need = baseline_best * (1 - BEAT_MARGIN)
    print(f"最好的朴素基线 {baseline_best:.2%}  →  要通过须 ≤ {need:.2%}"
          f"(领先 {BEAT_MARGIN:.0%})")
    print(f"最好的算法     {algo_best:.2%}  ({algo_best_name})")
    print()
    if algo_best <= need:
        print(f"BACKTEST OK  {algo_best_name} 以 {algo_best:.2%} 击败朴素基线 "
              f"{baseline_best:.2%}")
        return 0
    print(f"BACKTEST FAIL  最好的算法 {algo_best:.2%} 赢不了朴素基线 "
          f"{baseline_best:.2%} —— 预测入口不该开")
    return 1


def main() -> int:
    p = argparse.ArgumentParser(description="预测回测闸")
    p.add_argument("--factory-id", required=True)
    p.add_argument("--start", required=True)
    p.add_argument("--end", required=True)
    p.add_argument("--horizon", type=int, default=7)
    p.add_argument(
        "--llm-origins", type=int, default=0, metavar="N",
        help="把 LLM 也放进同一张表比: 取最近 N 个起点, 每个起点一次真实调用"
             "(所有方法都改跑这批起点, 保证同一张考卷)")
    p.add_argument(
        "--candidate", default=None, metavar="PATH",
        help="额外测一份改过的 target_forecast.py(按文件路径载入, 不碰部署目录)")
    a = p.parse_args()
    return asyncio.run(run(
        a.factory_id,
        dt.date.fromisoformat(a.start),
        dt.date.fromisoformat(a.end),
        a.horizon,
        a.candidate,
        a.llm_origins,
    ))


if __name__ == "__main__":
    raise SystemExit(main())
