"""时间词语料的读写 —— 纯数据层。

## 为什么有这张表

确定性层 `_resolve_sales_date_range` 认不出「最近」这类说法时, LLM
double-check 会认出来并给出规范短语。今天这个知识用完就扔 —— 同一个词
每次都要重新花一次 LLM 调用。记下来, 攒够了由**人工**晋升进确定性规则。

见 migration `V20261101_15__ai_time_phrase_corpus.sql` 的表/列注释。

## 本模块的边界 (⛔ 不含判别逻辑)

「这句话该不该记」的判据是 `spec.window_from_llm_phrase` —— 产品**已经
算好**了 (`restaurant_intent.py:2266`)。本模块只管写/读表, 不重算、不判断。
接线 (调用 `record_time_phrase` 的时机) 是 Task 2 的事; CLI/跑批读
`list_unpromoted` / `mark_promoted` / `corpus_counts` 是 Task 3 的事。

## `hit_count` 的口径 (承重, ⛔ 不要当频次读)

`chat.py` 会先 peek 一次 `parse_restaurant_query`, 随后
`_try_tiered_restaurant_intent` 还会再解析一次 —— **一次用户提问可能
触发 2+ 次 `record_time_phrase` 调用**。`hit_count` 只是人工晋升排优先级
的信号, ⛔ 不是「用户问了几次」的去重频次指标。
"""
from __future__ import annotations

import json
import logging
from typing import Any, Dict, List

from smartbi.gold.restaurant.restaurant_intent import _normalize_exact_phrase

logger = logging.getLogger(__name__)

_UPSERT_SQL = (
    "INSERT INTO ai_time_phrase_corpus"
    " (domain, normalized_phrase, factory_id, raw_query, llm_phrase,"
    "  llm_time_range, hit_count, created_at, last_seen_at)"
    " VALUES ($1, $2, $3, $4, $5, $6::jsonb, 1, now(), now())"
    " ON CONFLICT (domain, normalized_phrase) DO UPDATE"
    "    SET hit_count      = ai_time_phrase_corpus.hit_count + 1,"
    "        last_seen_at   = now(),"
    "        raw_query      = EXCLUDED.raw_query,"
    "        llm_phrase     = EXCLUDED.llm_phrase,"
    "        llm_time_range = EXCLUDED.llm_time_range"
)

_UNPROMOTED_SQL = (
    "SELECT domain, normalized_phrase, factory_id, raw_query, llm_phrase,"
    "       llm_time_range, hit_count, created_at, last_seen_at"
    "  FROM ai_time_phrase_corpus"
    " WHERE domain = $1 AND promoted_at IS NULL"
    " ORDER BY last_seen_at DESC"
    " LIMIT $2"
)

_MARK_PROMOTED_SQL = (
    "UPDATE ai_time_phrase_corpus"
    "    SET promoted_at   = now(),"
    "        reviewed_by   = $3,"
    "        promoted_note = $4"
    "  WHERE domain = $1 AND normalized_phrase = $2"
)

_COUNTS_SQL = (
    "SELECT count(*) AS total,"
    "       count(*) FILTER (WHERE promoted_at IS NULL) AS unpromoted"
    "  FROM ai_time_phrase_corpus"
    " WHERE domain = $1"
)


async def record_time_phrase(
    pool, *, domain: str, factory_id: str, raw_query: str,
    llm_phrase: str, llm_time_range: Any,
) -> bool:
    """记一条「规则没认出、LLM 认出了」的时间说法。返回是否真的写了。

    ⚠️ **fail-open 但要出声**: 记语料失败绝不能让一次问答失败, 但静默吞掉
    会让「语料一直是空的」和「没有这类问句」长得一模一样 —— 而这张表
    就是我们唯一的仪器。所以任何异常在这里全部吞掉, 只留一条 WARNING。
    """
    try:
        normalized = _normalize_exact_phrase(raw_query)
        if not normalized or not llm_phrase:
            return False
        async with pool.acquire() as conn:
            await conn.execute(
                _UPSERT_SQL, domain, normalized, factory_id, raw_query,
                llm_phrase, json.dumps(llm_time_range or {}, ensure_ascii=False),
            )
        return True
    except Exception:  # noqa: BLE001 — fail-open by design, see docstring
        logger.warning(
            "时间词语料(time_phrase_corpus)写入失败(不影响本次问答): "
            "domain=%s factory=%s query=%r",
            domain, factory_id, raw_query, exc_info=True,
        )
        return False


async def list_unpromoted(
    pool, *, domain: str = "restaurant", limit: int = 100,
) -> List[Dict[str, Any]]:
    """还没人工晋升的语料, 最近命中的排前面。供 CLI/跑批读, 不 fail-open ——
    一次读失败就该让调用方(人工审阅工具)知道, 而不是安静地返回空列表。
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(_UNPROMOTED_SQL, domain, limit)
    return [dict(r) for r in rows or ()]


async def mark_promoted(
    pool, *, domain: str, normalized_phrase: str, reviewed_by: str, note: str,
) -> bool:
    """登记一条语料已经人工晋升。⛔ 不许只写时间戳 —— `reviewed_by` 和
    `note` 一起写, 登记是留痕, 不是打勾。

    返回是否真的更新了一行(该 `normalized_phrase` 在这个 `domain` 下存在)。
    """
    async with pool.acquire() as conn:
        status = await conn.execute(
            _MARK_PROMOTED_SQL, domain, normalized_phrase, reviewed_by, note,
        )
    # asyncpg execute() 返回形如 "UPDATE 1" 的状态串。
    try:
        return int(str(status).rsplit(" ", 1)[-1]) > 0
    except (ValueError, IndexError):
        return False


async def corpus_counts(pool, *, domain: str = "restaurant") -> Dict[str, int]:
    """`{"total": N, "unpromoted": M}` —— 两个数**必须分开**返回, 跑批靠它们
    区分「饱和」(unpromoted 接近 0 但 total 在涨)和「写入路径没跑」(total
    不涨)。
    """
    async with pool.acquire() as conn:
        row = await conn.fetchrow(_COUNTS_SQL, domain)
    if not row:
        return {"total": 0, "unpromoted": 0}
    return {"total": int(row["total"]), "unpromoted": int(row["unpromoted"])}
