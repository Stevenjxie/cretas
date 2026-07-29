"""外部平台回调端点。

⚠️ 三层校验缺一不可, 且**必须独立鉴权** —— 本端点路径里没有 factoryId,
不能沿用「URL 能解析出 factoryId 才鉴权」那套 (2026-07-29 匿名访问事故根因)。

回调只是「有新数据」的触发器, 不携带业务数据: 回调丢一次由定时拉取兜底,
两条路指向同一个幂等写入。
"""
from __future__ import annotations

import hashlib
import hmac
import logging
import os
import time
from typing import Optional, Set

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/platform-callback", tags=["Platform Callback"])

TIMESTAMP_WINDOW_SECONDS = 300
SUPPORTED_PLATFORMS = {"keruyun"}

# 进程内 nonce 去重。单 leader 拉取 + 短窗口, 内存集合足够;
# 多副本部署时换 Redis SETEX(nonce, 300)。
#
# 条目形如 "<timestamp>:<nonce>" 而非裸 nonce, 这样 prune_nonces 能把
# 超出时间窗的条目清出去 —— 否则常驻进程里这个集合只增不减 (每分钟一次
# 回调 = 一年 50 万条)。用复合键不削弱防重放: 签名覆盖 timestamp, 换个
# ts 重放就得重新签名, 拿不到密钥就签不出来。
_SEEN_NONCES: Set[str] = set()


class CallbackRejected(RuntimeError):
    """回调被拒。消息里不回显密钥或签名细节。"""


class CallbackMisconfigured(RuntimeError):
    """服务端自身配置缺失。是我们的错不是调用方的错 —— 回 500 不回 401。"""


def _allowed_ips() -> Set[str]:
    raw = os.getenv("PLATFORM_CALLBACK_ALLOWED_IPS", "")
    return {ip.strip() for ip in raw.split(",") if ip.strip()}


def _secret() -> str:
    value = os.getenv("PLATFORM_CALLBACK_SECRET", "").strip()
    if not value:
        # 禁降级: 没配密钥就拒绝服务, 绝不「没配就放行」
        raise CallbackMisconfigured("PLATFORM_CALLBACK_SECRET 未配置")
    return value


def verify_signature(body: bytes, timestamp: str, nonce: str,
                     signature: str, secret: str) -> bool:
    payload = timestamp.encode("ascii") + nonce.encode("ascii") + body
    expected = hmac.new(secret.encode("utf-8"), payload, hashlib.sha256).hexdigest().lower()
    return hmac.compare_digest(expected, (signature or "").lower())


def prune_nonces(pool: Optional[Set[str]] = None, *, now: Optional[int] = None) -> int:
    """清掉时间窗外的 nonce 条目, 返回清掉的条数。

    窗口外的条目再也不可能命中重放判定 (check_replay 会先在 timestamp 那关
    拒掉), 留着纯属内存泄漏。
    """
    target = _SEEN_NONCES if pool is None else pool
    current = int(time.time()) if now is None else now
    stale = set()
    for entry in target:
        head, _, _rest = entry.partition(":")
        try:
            ts = int(head)
        except ValueError:
            # 形状不认识的条目 (理论上进不来) 一律当过期清掉, 免得永久驻留。
            stale.add(entry)
            continue
        if abs(current - ts) > TIMESTAMP_WINDOW_SECONDS:
            stale.add(entry)
    target -= stale
    return len(stale)


def check_replay(timestamp: str, nonce: str, seen: Optional[Set[str]] = None) -> None:
    """时间窗 + nonce 去重。不通过就抛 CallbackRejected。

    ⚠️ 调用方必须先验签再调它 —— 记 nonce 是「消费」动作, 只有已鉴权的
    请求才有资格消费, 否则外部流量能抢先烧掉 nonce 并污染内存池。
    """
    pool = _SEEN_NONCES if seen is None else seen
    try:
        ts = int(timestamp)
    except (TypeError, ValueError):
        raise CallbackRejected("timestamp 非法") from None
    if abs(int(time.time()) - ts) > TIMESTAMP_WINDOW_SECONDS:
        raise CallbackRejected("timestamp 超出允许窗口")
    if not nonce:
        raise CallbackRejected("nonce 缺失")
    entry = f"{ts}:{nonce}"
    if entry in pool:
        raise CallbackRejected("nonce 已使用 (重放)")
    pool.add(entry)


def _reject(status: int, message: str) -> JSONResponse:
    return JSONResponse(status_code=status,
                        content={"success": False, "message": message, "data": None})


@router.post("/{platform}")
async def receive_callback(platform: str, request: Request):
    if platform not in SUPPORTED_PLATFORMS:
        return _reject(404, "未知平台")

    # ① IP 白名单
    client_ip = request.client.host if request.client else ""
    allowed = _allowed_ips()
    if allowed and client_ip not in allowed:
        logger.warning("[callback] 拒绝非白名单来源 %s", client_ip)
        return _reject(403, "来源不被允许")

    try:
        secret = _secret()
    except CallbackMisconfigured as exc:
        # 细节只进日志: 回显「哪个环境变量没配」等于给外部探测服务端状态。
        logger.error("[callback] 服务端配置缺失: %s", exc)
        return _reject(500, "回调服务未就绪")

    body = await request.body()
    timestamp = request.headers.get("X-Mock-Timestamp", "")
    nonce = request.headers.get("X-Mock-Nonce", "")

    # ② HMAC 验签 —— 必须排在防重放前面, 见 check_replay 的注释。
    if not verify_signature(body, timestamp, nonce,
                            request.headers.get("X-Mock-Signature", ""), secret):
        logger.warning("[callback] 验签失败, 来源 %s", client_ip)
        return _reject(401, "验签失败")

    # ③ 时间窗 + nonce 防重放
    prune_nonces()
    try:
        check_replay(timestamp, nonce)
    except CallbackRejected as exc:
        logger.warning("[callback] 拒绝: %s", exc)
        return _reject(401, str(exc))

    # 只当触发器: 不解析业务数据, 交给拉取循环去拿。
    logger.info("[callback] %s 通知有新数据", platform)
    return {"success": True, "message": "ok", "data": {"platform": platform}}
