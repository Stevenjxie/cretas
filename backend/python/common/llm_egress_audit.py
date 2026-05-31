from __future__ import annotations
"""
LLM 出境审计 (P0 — 数据主权可证明).

记录"哪些字段 / 多少敏感值, 经哪个 provider/model, 在什么时间, 发给了外部 LLM",
让"客户数据不喂公有 AI / 已脱敏出境"从口号变成**可审计、可导出、可演示**的事实。

- 只存脱敏后 prompt 的 sha256 (prompt_sha256), **不存明文** —— 审计库不当 PII 副本。
- queue + 后台批量 flush, 请求路径非阻塞 (镜像 common/llm_metrics)。
- bg flush 连接的 GUC app.factory_id 永远是 '__internal__' (asyncpg pool setup),
  审计表 RLS 已含 __internal__ 三分支 (V20260531_01, 对齐 V20260515_01 防 Issue #590)。

写入点: common/llm_router.call_chain / call_chain_stream 每次实际 POST 后 record_egress。
"""
import asyncio
import hashlib
import logging
import time
from dataclasses import dataclass, field
from typing import List, Optional

logger = logging.getLogger(__name__)

_audit_queue: asyncio.Queue = asyncio.Queue(maxsize=20000)
_flush_task: Optional[asyncio.Task] = None
_enabled: bool = False


@dataclass
class EgressAuditRecord:
    call_site: str                      # llm_caller (semantic_mapper / insight_generator / ...)
    provider: str                       # aliyun_c / aliyun_b / zhipu / ...
    model: str
    factory_id: Optional[str] = None
    slot: Optional[str] = None
    sanitized: bool = False             # 是否对本次出境做了脱敏
    redacted_count: int = 0             # 替换掉的敏感值次数
    redacted_fields: List[str] = field(default_factory=list)  # 脱敏类型 (门店/客户/菜品...)
    sent_field_keys: List[str] = field(default_factory=list)  # 预留: 业务字段名
    data_window: Optional[str] = None   # 数据时间窗 (可选上下文)
    prompt_chars: int = 0               # 脱敏后 prompt 字符数
    prompt_sha256: Optional[str] = None  # 脱敏后 prompt 的 sha256 (不存明文)
    status_code: Optional[int] = None   # 出境真实响应码 (200 / 403 / None=异常)


def sha256_of(text: str) -> str:
    return hashlib.sha256((text or "").encode("utf-8")).hexdigest()


def record_egress(rec: EgressAuditRecord) -> None:
    """非阻塞入队。审计未启用 / 队列满 → 静默丢弃 (审计不能拖垮主路径)。"""
    if not _enabled:
        return
    try:
        _audit_queue.put_nowait(rec)
    except asyncio.QueueFull:
        logger.warning("[egress-audit] queue full, dropping record for %s", rec.call_site)


async def _flush_loop() -> None:
    """后台批量写: 每 5s 或攒满 100 条写一次 smart_bi_llm_egress_audit。"""
    from smartbi.config import get_pg_pool

    while True:
        try:
            first = await _audit_queue.get()
            batch = [first]
            deadline = time.time() + 5.0
            while len(batch) < 100 and time.time() < deadline:
                try:
                    nxt = await asyncio.wait_for(
                        _audit_queue.get(), timeout=max(0.01, deadline - time.time())
                    )
                    batch.append(nxt)
                except asyncio.TimeoutError:
                    break

            pool = await get_pg_pool()
            if pool is None:
                logger.warning("[egress-audit] no pg_pool, dropping %d records", len(batch))
                continue

            async with pool.acquire() as conn:
                await conn.executemany(
                    """INSERT INTO smart_bi_llm_egress_audit
                       (call_site, slot, provider, model, factory_id, sanitized,
                        redacted_count, redacted_fields, sent_field_keys, data_window,
                        prompt_chars, prompt_sha256, status_code)
                       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)""",
                    [
                        (r.call_site, r.slot, r.provider, r.model, r.factory_id, r.sanitized,
                         r.redacted_count, r.redacted_fields, r.sent_field_keys, r.data_window,
                         r.prompt_chars, r.prompt_sha256, r.status_code)
                        for r in batch
                    ],
                )
        except asyncio.CancelledError:
            raise
        except Exception as e:
            logger.warning("[egress-audit] flush failed: %s", e)
            await asyncio.sleep(1.0)


def enable_egress_audit() -> None:
    """app 启动 (lifespan) 中调用, pg pool 就绪后。"""
    global _enabled, _flush_task
    if _enabled:
        return
    _enabled = True
    _flush_task = asyncio.create_task(_flush_loop(), name="llm_egress_audit_flusher")
    logger.info("[egress-audit] enabled (queue + async DB flush)")


async def disable_egress_audit() -> None:
    global _enabled, _flush_task
    _enabled = False
    if _flush_task is not None:
        _flush_task.cancel()
        try:
            await _flush_task
        except (asyncio.CancelledError, Exception):
            pass
        _flush_task = None
