"""回调推送：只发「有新数据」的信号，不发数据本身。

真实平台的回调带数据，这里刻意牺牲一点仿真度：回调丢一次就永久少一笔，
改成触发器后回调丢失由 connector 的定时拉取兜底，两条路指向同一个幂等写入。
"""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import secrets
import time

logger = logging.getLogger(__name__)


def build_signature(body: bytes, timestamp: str, nonce: str, secret: str) -> str:
    """HMAC-SHA256(secret, timestamp + nonce + body)，小写 hex。"""
    payload = timestamp.encode("ascii") + nonce.encode("ascii") + body
    return hmac.new(secret.encode("utf-8"), payload, hashlib.sha256).hexdigest().lower()


async def notify(client, url: str, secret: str, *, max_seq: int) -> bool:
    """推一次「新数据到 max_seq」。失败只记日志——拉取会兜底，不阻塞生成。"""
    if not url:
        return False
    body = json.dumps({"platform": "keruyun", "maxSeq": max_seq},
                      separators=(",", ":")).encode("utf-8")
    timestamp = str(int(time.time()))
    nonce = secrets.token_hex(8)
    headers = {
        "Content-Type": "application/json",
        "X-Mock-Timestamp": timestamp,
        "X-Mock-Nonce": nonce,
        "X-Mock-Signature": build_signature(body, timestamp, nonce, secret),
    }
    try:
        resp = await client.post(url, content=body, headers=headers, timeout=5.0)
        if resp.status_code != 200:
            logger.warning("[callback] 非 200: %s %s", resp.status_code, resp.text[:200])
            return False
        return True
    except Exception as exc:  # noqa: BLE001 — 回调失败不该拖垮生成器
        logger.warning("[callback] 推送失败: %s", exc)
        return False
