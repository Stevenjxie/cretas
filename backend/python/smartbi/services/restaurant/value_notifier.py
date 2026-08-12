from __future__ import annotations

"""#56 价值可视化回馈回路 — 月度通知器 (D2 角色路由 + 幂等防重)。

maybe_notify_monthly: 月度快照算完后, 给 D2 指定角色推送站内通知。
  - D2: 仅 restaurant_manager + factory_super_admin/factory_admin 收推送。
    restaurant_operations 可在 web 驾驶舱看 ValueFeedbackStrip, 但不推送 (避噪音)。
  - 幂等: 推送前查 restaurant_value_notifications_log; 已推过的 (factory,period,role)
    跳过。推送成功才写日志 (失败不写, 下次可重试)。
  - 文案 (R2 + RBAC): 金额角色 (PRICE_VIEW_ROLES) 看金额; 非金额角色只给 count。
  - 无价值 (无金额且无 critical) → 不推送 (避免发空通知)。

调 Java: 通过 value_notifier_client.notify_role (Python → Java 内部通知端点)。
maybe_notify_monthly 接受可注入的 java_notify (默认走真实 client), 便于测试。
"""

import logging
from typing import Any, Awaitable, Callable, Optional

from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

logger = logging.getLogger(__name__)


# D2: 收推送的角色 (店长 + 老板/工厂管理员)。restaurant_operations 不推 (web 可看)。
NOTIFY_ROLES: tuple[str, ...] = (
    "restaurant_manager",
    "factory_super_admin",
    "factory_admin",
)


# actionUrl: 点击跳驾驶舱 value tab (D5)。
_ACTION_URL = "/smart-bi/dashboard?tab=value"


def _role_sees_amount(role: str) -> bool:
    """金额角色 (PRICE_VIEW_ROLES) → 文案含金额; 否则只给 count (R2 + RBAC)。"""
    return bool(role) and role in PRICE_VIEW_ROLES


def _fmt_amount(v: Optional[float]) -> str:
    """金额千分位格式化。None → '暂无数据' (禁降级填 0)。"""
    if v is None:
        return "暂无数据"
    try:
        return f"{float(v):,.0f}"
    except (TypeError, ValueError):
        return "暂无数据"


def build_message(role: str, summary: dict[str, Any]) -> str:
    """构造通知正文 (R2: 带期间 + 金额/count + 责任提示)。

    金额角色: "{期间} 本月可优化空间约 ¥{金额}, {N} 项指标需关注..."。
    非金额角色: "{期间} 有 {N} 项经营指标需关注, 详情请联系店长/查看驾驶舱"。
    """
    period = summary.get("periodMonth") or "本月"
    critical = int(summary.get("criticalCount") or 0)
    rx_count = int(summary.get("rxActionCount") or 0)
    total_month = (summary.get("month") or {}).get("total")

    if _role_sees_amount(role):
        amount_txt = _fmt_amount(total_month)
        if total_month is not None:
            return (
                f"{period} 经营诊断: 本月可优化/节省空间约 ¥{amount_txt}, "
                f"含 {critical} 项严重指标 + {rx_count} 条改善处方。"
                f"点击查看本月价值回馈明细。"
            )
        # 无金额但有 critical: 诚实, 不编金额
        return (
            f"{period} 经营诊断: 检测到 {critical} 项严重指标 + {rx_count} 条改善处方 "
            f"(金额暂无数据)。点击查看明细。"
        )
    # 非金额角色 (R2): 只给 count, 不泄露金额
    return (
        f"{period} 经营诊断: 有 {critical} 项经营指标需关注。"
        f"详情请查看经营驾驶舱或联系店长。"
    )


def build_title(summary: dict[str, Any]) -> str:
    period = summary.get("periodMonth") or "本月"
    return f"{period} 价值回馈月报"


def _has_value(summary: dict[str, Any]) -> bool:
    """快照是否有可报的价值 (有金额 或 有 critical)。无 → 不推送 (避空通知)。"""
    total_month = (summary.get("month") or {}).get("total")
    total_annual = (summary.get("annual") or {}).get("total")
    critical = int(summary.get("criticalCount") or 0)
    return total_month is not None or total_annual is not None or critical > 0


# JavaNotify = async (factory_id, role, title, body, action_url) -> bool
JavaNotifyFn = Callable[..., Awaitable[bool]]


async def _default_java_notify(
    *, factory_id: str, role: str, title: str, body: str, action_url: str
) -> bool:
    """默认走真实 Python → Java 内部通知 client。"""
    from smartbi.services.restaurant.value_notifier_client import notify_role_via_java

    return await notify_role_via_java(
        factory_id=factory_id, role=role, title=title, body=body, action_url=action_url
    )


_ALREADY_NOTIFIED_SQL = """
SELECT id FROM restaurant_value_notifications_log
 WHERE factory_id = $1 AND period_month = $2 AND recipient_role = $3
 LIMIT 1
"""

_WRITE_LOG_SQL = """
INSERT INTO restaurant_value_notifications_log
    (factory_id, period_month, recipient_role, snapshot_id, notified_at, created_at)
VALUES ($1, $2, $3, $4, NOW(), NOW())
ON CONFLICT (factory_id, period_month, recipient_role) DO NOTHING
"""


async def maybe_notify(
    pool: Any,
    factory_id: str,
    period_key: str,
    *,
    render: Callable[[str], Optional[tuple[str, str]]],
    roles: Optional[list[str]] = None,
    action_url: str = _ACTION_URL,
    java_notify: Optional[JavaNotifyFn] = None,
    snapshot_id: Optional[int] = None,
    log_tag: str = "value-notify",
) -> dict[str, Any]:
    """通用推送 —— **幂等防重 + D2 角色路由 + Java 通道**, 文案由调用方给。

    2026-08-13 从 `maybe_notify_monthly` 里原样提出来的, 一行行为都没改:
    月报把自己的 `build_title`/`build_message` 作为 `render` 传进来, 结果逐字相同。

    ## 为什么要提这一层

    打烊日结要的正是这三样(角色路由/防重/通道), **唯独文案不一样**。
    原来的 `maybe_notify_monthly` 把文案写死在里面(`build_message` 完全不看
    调用方给的正文), 直接复用会把「今天赚多少」推成「2026-08-13 价值回馈月报」——
    机制接上了, 推出去的却是别人的话。

    ⛔ 另一条路是日结自己写一遍推送+防重 —— 那是同一件事两份实现, 而漂的表现是
       **店长一天收到两遍**。

    Args:
        period_key: 幂等周期键。月报写 `YYYY-MM`, 日结写 `YYYY-MM-DD`。
        render: `role -> (title, body)`; 返回 `None` 表示**这个角色没有可报的东西**
            → 跳过不推。⚠️ 按角色而不是全局判断, 是因为 RBAC 下
            非金额角色可能确实没内容可看(见 `_role_sees_amount`)。

    Returns:
        {notified:[...], skipped:[...], failed:[...]}。永不抛。
    """
    notify_fn = java_notify or _default_java_notify
    target_roles = roles if roles is not None else list(NOTIFY_ROLES)

    notified: list[str] = []
    skipped: list[str] = []
    failed: list[str] = []

    for role in target_roles:
        try:
            rendered = render(role)
            if rendered is None:
                # 这个角色没有可报的内容 —— 不推空通知。
                skipped.append(role)
                continue

            async with pool.acquire() as conn:
                existing = await conn.fetchrow(
                    _ALREADY_NOTIFIED_SQL, factory_id, period_key, role
                )
            if existing is not None:
                skipped.append(role)
                continue

            title, body = rendered
            ok = await notify_fn(
                factory_id=factory_id, role=role, title=title, body=body,
                action_url=action_url,
            )
            if not ok:
                failed.append(role)
                logger.warning(
                    "[%s] Java notify failed factory=%s role=%s — not logging "
                    "(retry next time)", log_tag, factory_id, role,
                )
                continue

            # 成功才写防重日志
            async with pool.acquire() as conn:
                await conn.execute(
                    _WRITE_LOG_SQL, factory_id, period_key, role, snapshot_id
                )
            notified.append(role)
        except Exception as e:  # noqa: BLE001 — fire-and-forget per role
            failed.append(role)
            logger.error(
                "[%s] role=%s factory=%s failed: %s",
                log_tag, role, factory_id, e, exc_info=True,
            )

    logger.info(
        "[%s] factory=%s period=%s notified=%s skipped=%s failed=%s",
        log_tag, factory_id, period_key, notified, skipped, failed,
    )
    return {"notified": notified, "skipped": skipped, "failed": failed}


async def maybe_notify_monthly(
    pool: Any,
    factory_id: str,
    period_month: str,
    summary: dict[str, Any],
    java_notify: Optional[JavaNotifyFn] = None,
    roles: Optional[list[str]] = None,
    snapshot_id: Optional[int] = None,
) -> dict[str, Any]:
    """月度通知 (幂等防重 + D2 角色路由)。

    ⚠️ 2026-08-13 起是 `maybe_notify` 的薄包装 —— **对外行为一字未改**:
    `_has_value` 的短路、`reason: "no_value"`、文案、`action_url` 全在原位。

    Args:
        summary: get_value_summary 的返回 (含 month/annual/criticalCount...)。
        java_notify: 可注入的 Java 通知函数 (测试用); None → 真实 client。
        roles: 收件角色; None → D2 默认 NOTIFY_ROLES。

    Returns:
        {notified:[...], skipped:[...], failed:[...], reason?}。永不抛。
    """
    if not _has_value(summary):
        logger.info(
            "[value-notify] factory=%s period=%s no value → skip", factory_id, period_month
        )
        return {"notified": [], "skipped": [], "failed": [], "reason": "no_value"}

    return await maybe_notify(
        pool,
        factory_id,
        period_month,
        render=lambda role: (build_title(summary), build_message(role, summary)),
        roles=roles,
        java_notify=java_notify,
        snapshot_id=snapshot_id,
    )
