"""Gold 增量刷新 — 给 60s 常驻拉取循环用的「按成本分档」刷新。

背景 (2026-07-29 实测)
----------------------
connector 每 60s 把 POS 订单拉进 Silver (`fact_pos_transaction` /
`fact_pos_item`), 但问答链路读的是 Gold (`agg_daily` / `agg_channel` /
`agg_product` …)。Gold 过去只由回填脚本或 03:30 夜间 ETL 刷新, 于是线上出现:
AI 答「本月营收 ¥20,610,511.37 (57,020 单)」而 Silver 实际是
¥20,669,324.83 (57,190 单) —— AI 没算错, 它读的 Gold 本身就是旧快照。

为什么要分档 (这是本模块存在的唯一理由)
--------------------------------------
- `materialize_daily` / `materialize_channel` 传的是日期区间, 收窄到「今天」
  就只扫今天那点行 —— 秒级, 每轮都跑得起。
- `materialize_product` 的 grain 是**月**: 一次调用重算整月, 不是只算今天。
  线上实测: 表统计新鲜时是秒级, **统计陈旧时超过 20 分钟**(planner 估 1 行,
  实际 243k 行, 选了 nested loop)。每 60s 跑一次 = 自己给自己造故障。
  所以它走慢档 (默认 600s), 且周期可用环境变量调。

租户上下文
----------
`GoldMaterializer` 自己**不**设 `app.factory_id`, 它依赖连接池的 setup 回调
(`smartbi.tenant_ctx.set_pg_connection_tenant`) 在每次 acquire 时按 ContextVar
写 GUC。所以这里沿用 `scripts/backfill_gold_for_chains.py` 的做法:
调 materializer 之前 `set_factory_id(factory_id)`。
**不要**在这里改成「显式事务 + set_config(..., true)」—— 那是 writer /
cursor_store 那条路径的做法(它们自己开事务), 两套混用只会让 RLS 更难推理。
用完 `reset_factory_id` 还原, 免得把租户粘在常驻任务的 ContextVar 上。
"""
from __future__ import annotations

import logging
import os
import time
from datetime import date
from typing import Mapping, Optional, Tuple

from smartbi.gold.materializer import GoldMaterializer

logger = logging.getLogger(__name__)

# 慢档默认周期。600s 是「够新鲜」与「即使退化成 20 分钟也不至于每轮叠加」之间的取值。
DEFAULT_PRODUCT_INTERVAL_SECONDS = 600

# agg_product 超过这个秒数就 warning: 它本该是秒级, 一旦逼近分钟级几乎必然是
# 表统计陈旧导致 planner 选错计划 (ANALYZE fact_pos_item / fact_pos_transaction)。
PRODUCT_SLOW_WARN_SECONDS = 60.0

_TRUTHY = ("1", "true", "yes")


def parse_interval_seconds(raw: Optional[str], default: int, *, var_name: str) -> int:
    """把环境变量解析成正整数秒; 非法值记 error 并回退 default。

    与 main.py 里 PLATFORM_SYNC_INTERVAL_SECONDS 的解析口径一致: 只接受正整数,
    `0` / 负数 / 小数 / 非数字一律算非法。禁降级的边界在这里是「回退到一个安全的
    慢周期并把非法值喊出来」, 而不是静默按 0 秒跑。
    """
    try:
        value = int(raw)  # type: ignore[arg-type]  # None/非数字走 except
    except (TypeError, ValueError):
        logger.error("[platform-sync] %s=%r 非法, 回退 %ds", var_name, raw, default)
        return default
    if value <= 0:
        logger.error("[platform-sync] %s=%r 非法, 回退 %ds", var_name, raw, default)
        return default
    return value


def gold_refresh_enabled(env: Optional[Mapping[str, str]] = None) -> bool:
    """PLATFORM_GOLD_REFRESH_ENABLED —— 默认**开**。

    默认开是刻意的: 外层已经有 PLATFORM_SYNC_ENABLED 这道闸, 能走到这里说明
    运维已经明确要拉数据; 拉了却不刷 Gold 正是本模块要消灭的那个故障。
    这个变量的用途是出事时单独关掉 Gold 刷新而**保住拉取**。
    """
    env = os.environ if env is None else env
    return env.get("PLATFORM_GOLD_REFRESH_ENABLED", "1").strip().lower() in _TRUTHY


def product_interval_from_env(env: Optional[Mapping[str, str]] = None) -> int:
    """PLATFORM_GOLD_PRODUCT_INTERVAL_SECONDS, 默认 600。"""
    env = os.environ if env is None else env
    return parse_interval_seconds(
        env.get("PLATFORM_GOLD_PRODUCT_INTERVAL_SECONDS"),
        DEFAULT_PRODUCT_INTERVAL_SECONDS,
        var_name="PLATFORM_GOLD_PRODUCT_INTERVAL_SECONDS",
    )


class SlowCadence:
    """「贵活儿现在该跑了吗」的判定。

    - 首次一定 due: 进程刚起来时 Gold 可能已经陈旧几小时, 不该再等一个周期。
    - `mark()` 由调用方在**开跑之前**调用, 于是失败也照样消耗一个周期。
      反过来「成功才记账」会让一个持续失败(或持续 20 分钟超时)的 agg_product
      每 60s 重试一次 —— 正是慢档要避免的那种雪崩。
    - 时钟用 `time.monotonic()`, 不受系统时间跳变影响; `now` 可注入便于测试。
    """

    def __init__(self, interval_seconds: float):
        if interval_seconds <= 0:
            raise ValueError(f"interval_seconds must be > 0, got {interval_seconds}")
        self.interval_seconds = float(interval_seconds)
        self._last_run: Optional[float] = None

    @property
    def last_run(self) -> Optional[float]:
        return self._last_run

    def due(self, now: float) -> bool:
        if self._last_run is None:
            return True
        return (now - self._last_run) >= self.interval_seconds

    def mark(self, now: float) -> None:
        self._last_run = now


def daily_refresh_range(today: date) -> Tuple[date, date]:
    """每轮刷新的日期区间 —— 只有今天。

    Python 服务跑在 CST(见 smartbi/agent/budget_tracker.py 的同款假设), 与
    `fact_pos_transaction.date` 的业务日同一时区, 所以 `date.today()` 就是业务日。
    ⚠️ 已知边界: 跨零点后, 昨天最后几分钟的迟到账单只会进 Silver, 昨天的 Gold
    要等 03:30 夜间 ETL 才补上(最长约 3.5h 陈旧)。见本模块 PR 说明。
    """
    return (today, today)


def product_refresh_month(today: date) -> date:
    """agg_product 的 grain 是月, 归一到当月 1 号。"""
    return today.replace(day=1)


async def refresh_gold_incremental(
    pool,
    factory_id: str,
    *,
    cadence: SlowCadence,
    today: Optional[date] = None,
    now: Optional[float] = None,
) -> dict:
    """刷新 Gold: 便宜的每轮跑, 贵的按 `cadence` 跑。

    返回一个适合直接进日志的小 dict。任何失败都原样抛给调用方 —— 常驻循环负责
    「记日志 + 继续下一轮」, 这里不吞异常(吞了就成了又一处静默陈旧)。
    """
    today = date.today() if today is None else today
    now = time.monotonic() if now is None else now

    from smartbi.tenant_ctx import reset_factory_id, set_factory_id

    token = set_factory_id(factory_id)
    try:
        m = GoldMaterializer(pool, factory_id)
        start, end = daily_refresh_range(today)

        t0 = time.monotonic()
        daily = await m.materialize_daily((start, end))
        channel = await m.materialize_channel((start, end))
        out = {
            "date": str(today),
            "agg_daily": daily.rows_upserted,
            "agg_channel": channel.rows_upserted,
            "cheap_seconds": round(time.monotonic() - t0, 2),
        }

        if not cadence.due(now):
            out["agg_product"] = "skipped"
            return out

        # 先记账再开跑: 失败/超慢也要等满一个周期, 不能 60s 一次地重试。
        cadence.mark(now)
        month = product_refresh_month(today)
        p0 = time.monotonic()
        product = await m.materialize_product(month)
        elapsed = time.monotonic() - p0
        out["agg_product"] = product.rows_upserted
        out["product_month"] = str(month)
        out["product_seconds"] = round(elapsed, 2)
        if elapsed >= PRODUCT_SLOW_WARN_SECONDS:
            logger.warning(
                "[platform-sync] agg_product 耗时 %.1fs (factory=%s month=%s) —— "
                "本该是秒级, 高度怀疑 fact_pos_item / fact_pos_transaction "
                "表统计陈旧导致 planner 选错计划, 去跑 ANALYZE",
                elapsed, factory_id, month,
            )
        return out
    finally:
        # 常驻任务的 ContextVar 会一直活着, 不还原等于把租户永久粘上去。
        reset_factory_id(token)
