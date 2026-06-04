from __future__ import annotations

"""#56 价值可视化回馈回路 — Python → Java 内部通知 client。

调 Java 内部端点 POST /api/internal/notifications/role 创建角色定向站内通知
(复用 DbNotificationServiceImpl.notifyRole)。带 X-Internal-Key 内部密钥认证,
由 JwtAuthInterceptor 对 /api/internal/** 校验 X-Internal-Key == INTERNAL_API_SECRET
(fail-closed: 密钥未设或不匹配一律 403)。

失败返回 False (不抛) → 上层 value_notifier 不写防重日志, 下次可重试。
"""

import logging
import os

import httpx

logger = logging.getLogger(__name__)

# 与 restaurant_etl_admin 一致的 Java base url 约定。
_JAVA_API_BASE_URL_ENV = "JAVA_API_BASE_URL"
_DEFAULT_JAVA_API_BASE_URL = "http://localhost:10010"
# Java JwtAuthInterceptor 对 /api/internal/** 用 X-Internal-Key 对比 INTERNAL_API_SECRET
# 环境变量 (与 Python auth_middleware 同一密钥)。这里读同一 env, 保证密钥一致。
_INTERNAL_KEY_ENV = "INTERNAL_API_SECRET"


async def notify_role_via_java(
    *,
    factory_id: str,
    role: str,
    title: str,
    body: str,
    action_url: str = "",
) -> bool:
    """调 Java 创建角色定向通知。成功 True, 任何失败 False (不抛)。"""
    base_url = os.environ.get(_JAVA_API_BASE_URL_ENV, _DEFAULT_JAVA_API_BASE_URL)
    internal_key = os.environ.get(_INTERNAL_KEY_ENV, "")
    if not internal_key:
        logger.warning(
            "[value-notify-client] %s not set — cannot authenticate Java notify; "
            "skipping (factory=%s role=%s)", _INTERNAL_KEY_ENV, factory_id, role,
        )
        return False

    url = f"{base_url}/api/internal/notifications/role"
    payload = {
        "factoryId": factory_id,
        "role": role,
        "title": title,
        "body": body,
        "actionUrl": action_url,
        "source": "VALUE_FEEDBACK",
    }
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(
                url, json=payload, headers={"X-Internal-Key": internal_key}
            )
        if resp.status_code == 200:
            return True
        logger.warning(
            "[value-notify-client] Java notify non-200 (%s) factory=%s role=%s: %s",
            resp.status_code, factory_id, role, resp.text[:200],
        )
        return False
    except Exception as e:  # noqa: BLE001
        logger.warning(
            "[value-notify-client] Java notify call failed factory=%s role=%s: %s",
            factory_id, role, e,
        )
        return False
