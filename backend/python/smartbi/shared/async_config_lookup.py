"""`business_config_overrides` 的**唯一异步承载**。

## 为什么新建这个模块（而不是新建一张表，也不是再抄一遍 SQL）

优先级链的第 1 层「会话配置」缺的不是存储 —— `business_config_overrides` 早就有了
（`factory_id` + `store_id` + `domain` + `config_key/value` + 生效区间 + 软删），
`shared/dynamic_config_resolver.DynamicConfigResolver` 也早就实现了 4 层优先级：

    session 覆盖 > 门店级 > 工厂级 > 系统默认

⛔ 但那个 resolver 要一个**同步 SQLAlchemy Session**，而餐饮问答链路只有 asyncpg 池。
`services/restaurant/health_check_metrics.py` 因此**把工厂级那段 SQL 又抄了一遍**，
并在注释里写明了原因：

> that resolver requires a sync SQLAlchemy ``Session`` while this async builder
> only holds an asyncpg pool, so the factory-level query is **re-implemented
> here directly** against the same table/columns

⇒ 同一份查找逻辑已经有**两处**实现。餐饮问答再抄一遍就是第三处 ——
本模块的存在就是为了**不出现第三处**：异步侧只此一份，
`health_check_metrics` 的那份后续收编到这里（本轮不动它，避免把体检指标卷进来）。

## 与同步 resolver 的关系

**优先级语义逐字对齐**（门店级 > 工厂级 > 调用方给的默认），
生效区间与软删条件也逐字对齐。差别只有一个：**没有 session 级覆盖** ——
那是「单次试算」用的内存层，问答链路里没有对应概念，硬塞进来只会多一个没人写的分支。

## ⛔ 不做的事

- 不缓存。配置是低频读、且改了要立刻生效；加缓存就要再解决失效，
  而问答链路每轮只查一两个 key，不值得。
- 不 fail-closed。查不到 / 查不动 → 返回调用方给的默认值，
  **配置服务挂了不该让问答挂**。
"""
from __future__ import annotations

import logging
from typing import Any, Optional

logger = logging.getLogger(__name__)

#: 与 `DynamicConfigResolver` 同一条：key 必须带 domain 前缀，防止跨域取值。
_ALLOWED_DOMAINS = ("restaurant", "factory")


async def resolve_config(
    pool,
    factory_id: str,
    config_key: str,
    *,
    domain: str = "restaurant",
    store_id: Optional[str] = None,
    default: Any = None,
) -> Any:
    """按「门店级 > 工厂级 > default」取一个配置值。

    :param store_id: 给了就先查门店级；查不到再落工厂级。
    :param default:  查不到 / 查不动时返回它。**不抛异常** —— 见模块 docstring。

    ⚠️ `config_key` 必须以 `{domain}.` 开头，与同步 resolver 同一条校验。
       不满足直接 `ValueError`：这是**编码错误**，不是运行期状况，
       应该在开发时就炸出来，而不是悄悄返回默认值。
    """
    if domain not in _ALLOWED_DOMAINS:
        raise ValueError(f"domain 必须是 {_ALLOWED_DOMAINS} 之一, 收到 {domain!r}")
    if not config_key.startswith(f"{domain}."):
        raise ValueError(
            f"config_key 必须以 {domain!r} 前缀开头(防跨域取值), 收到 {config_key!r}"
        )
    if not factory_id:
        return default

    # 生效区间与软删条件逐字对齐 `DynamicConfigResolver` 与
    # `health_check_metrics` 里那份重实现 —— 三处必须同口径。
    sql = """
        SELECT config_value
          FROM business_config_overrides
         WHERE factory_id = $1
           AND domain = $2
           AND config_key = $3
           AND deleted_at IS NULL
           AND (effective_from IS NULL OR effective_from <= CURRENT_DATE)
           AND (effective_to   IS NULL OR effective_to   >= CURRENT_DATE)
           AND store_id IS NOT DISTINCT FROM $4
         ORDER BY updated_at DESC
         LIMIT 1
    """
    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                # RLS: 与本仓其它租户读一致, 事务内设 GUC(is_local=True 不会漏回池)。
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)", factory_id
                )
                for scope in ([store_id] if store_id else []) + [None]:
                    row = await conn.fetchrow(sql, factory_id, domain, config_key, scope)
                    if row is not None and row["config_value"] is not None:
                        return row["config_value"]
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "[config] 取 %s 失败(fail-open, 用默认值): %s", config_key, exc
        )
        return default
    return default
