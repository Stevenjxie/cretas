"""平台鉴权算法。

客如云走 token + sign 两段式。这里实现 sign 部分：与美团/抖音刻意不同，
目的是让 connector 侧被迫处理异构鉴权——这正是接真实平台时的实际情况。
"""
from __future__ import annotations

import hashlib
import hmac


def keruyun_sign(params: dict[str, str], app_secret: str) -> str:
    """参数按名字典序拼成 key=value&，用 app_secret 做 HMAC-SHA256，取小写 hex。

    参与签名的参数排除 sign 本身与空值。
    """
    items = sorted(
        (k, str(v)) for k, v in params.items()
        if k != "sign" and v is not None and str(v) != ""
    )
    payload = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(
        app_secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256
    ).hexdigest().lower()
