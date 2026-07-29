"""外部平台回调端点。

⚠️ 三层校验缺一不可, 且**必须独立鉴权** —— 本端点路径里没有 factoryId,
不能沿用「URL 能解析出 factoryId 才鉴权」那套 (2026-07-29 匿名访问事故根因)。

回调只是「有新数据」的触发器, 不携带业务数据: 回调丢一次由定时拉取兜底,
两条路指向同一个幂等写入。
"""
from __future__ import annotations

import asyncio
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


# 拉取循环注册进来的唤醒信号。回调验完签就 set 它, 循环立刻醒来拉一轮,
# 把延迟从「最多一个轮询周期」压到接近实时; 没有回调时循环仍按周期兜底。
#
# ⚠️ 进程内信号, 只有注册过的那个进程能被唤醒。多 worker 部署时回调可能落在
# follower 上(那里没有拉取循环, `_WAKEUP` 是 None), 这次就唤不醒 —— 兜底轮询
# 照常工作, 所以最坏退化成「和没有回调一样」, 不会丢数据。要跨进程唤醒得走
# Redis pub/sub 或 DB 通知, 不在本计划范围。
_WAKEUP: Optional["asyncio.Event"] = None


def register_wakeup(event: Optional["asyncio.Event"]) -> None:
    """由拉取循环在启动时调用(只在 leader 上)。传 None 表示注销。"""
    global _WAKEUP
    _WAKEUP = event


def _signal_wakeup() -> None:
    if _WAKEUP is not None:
        _WAKEUP.set()


class CallbackRejected(RuntimeError):
    """回调被拒。消息里不回显密钥或签名细节。"""


class CallbackMisconfigured(RuntimeError):
    """服务端自身配置缺失。是我们的错不是调用方的错 —— 回 500 不回 401。"""


_WARNED_NO_ALLOWLIST = False


def _allowed_ips() -> Set[str]:
    """来源白名单。空 = 不做来源校验 —— 这时必须把「层①失效」喊出来。

    ⚠️ 这里读的是 socket 对端地址 (request.client.host)。若服务跑在 nginx
    后面(47 上就是), 对端永远是 127.0.0.1, 填模拟器的真实公网 IP 会把每一次
    合法回调都 403 掉。要按真实来源 IP 过滤, 得先配置可信代理并解析
    X-Forwarded-For —— 本仓当前没有这套设施, 所以部署时要么填代理地址,
    要么留空并依赖验签(层②③)。
    """
    global _WARNED_NO_ALLOWLIST
    raw = os.getenv("PLATFORM_CALLBACK_ALLOWED_IPS", "")
    allowed = {ip.strip() for ip in raw.split(",") if ip.strip()}
    if not allowed and not _WARNED_NO_ALLOWLIST:
        # 只喊一次, 免得每个请求刷屏。不静默是关键: 三层校验只剩两层这件事
        # 必须在日志里留痕, 不能"没配就当没这层"。
        logger.warning("[callback] PLATFORM_CALLBACK_ALLOWED_IPS 未配置 —— "
                       "层① IP 白名单未生效, 当前仅靠验签 + 防重放")
        _WARNED_NO_ALLOWLIST = True
    return allowed


def _secret() -> str:
    value = os.getenv("PLATFORM_CALLBACK_SECRET", "").strip()
    if not value:
        # 禁降级: 没配密钥就拒绝服务, 绝不「没配就放行」
        raise CallbackMisconfigured("PLATFORM_CALLBACK_SECRET 未配置")
    return value


def verify_signature(body: bytes, timestamp: str, nonce: str,
                     signature: str, secret: str) -> bool:
    """验签。任何"根本签不出来"的输入一律判否, 不上抛。

    ⚠️ Starlette 按 latin-1 解 header, 所以外部随手塞一个 0xFF 字节就能让
    timestamp/nonce 变成非 ASCII 字符串。若不拦:
      - `.encode("ascii")` 抛 UnicodeEncodeError
      - `hmac.compare_digest` 对含非 ASCII 的 str 抛 TypeError
    本端点在公网上且默认不校验来源, 那就是一个谁都能打出来的 500 + traceback。
    ascii 编码本身不能换成 utf-8 —— 那会和模拟端 build_signature 的字节序列脱钩。
    """
    try:
        payload = timestamp.encode("ascii") + nonce.encode("ascii") + body
        expected = hmac.new(secret.encode("utf-8"), payload, hashlib.sha256).hexdigest().lower()
        return hmac.compare_digest(expected, (signature or "").lower())
    except (UnicodeEncodeError, TypeError):
        # 非 ASCII 的 timestamp/nonce/signature: 合法调用方永远不会发出这种东西。
        return False


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

    # 只当触发器: 不解析业务数据, 唤醒拉取循环让它自己去拿。
    # 回调丢了也不要紧 —— 循环的周期兜底和它指向同一个幂等写入。
    logger.info("[callback] %s 通知有新数据", platform)
    _signal_wakeup()
    return {"success": True, "message": "ok", "data": {"platform": platform}}
