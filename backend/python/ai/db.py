"""ai_intent_configs DB loader.

Read-only. Java owns writes via web-admin admin endpoints. Python loads
snapshot every 5min (or via /api/ai/intent/cache/invalidate POST). Filters
honor factory_id + business_type + soft-delete + is_active per Java entity
@Where(deleted_at IS NULL) + AIIntentConfig.factoryId Javadoc.

Visibility rules (mirrors Java JPA query in IntentRepository):
    factory_id == NULL  → platform-level (all factories see)
    factory_id == X     → only factory X sees

    business_type COMMON      → all business types see
    business_type FACTORY     → only FACTORY callers see
    business_type RESTAURANT  → only RESTAURANT callers see

The SQL load already filters out soft-deleted + inactive rows. The in-memory
filter is therefore primarily about scope (factory + business). It also
defensively re-checks deleted_at + is_active so callers passing a row list
from any source still get correct visibility.
"""
from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List

from ai.config import default_config
from ai.dto import AIIntentConfigDto

logger = logging.getLogger(__name__)


@dataclass
class IntentSnapshot:
    """In-memory snapshot of ai_intent_configs.

    Held as a module-level singleton; replaced atomically on each refresh.
    Callers must treat `rows` as immutable.
    """

    rows: List[Dict[str, Any]] = field(default_factory=list)
    loaded_at_unix: float = 0.0
    max_config_version: int = 0


# Module-level state (singleton snapshot)
_current_snapshot: IntentSnapshot = IntentSnapshot()
_snapshot_lock = asyncio.Lock()


SELECT_SQL = """
SELECT
    id, factory_id, business_type, intent_code, intent_name,
    intent_category, sensitivity_level, required_roles, required_permission,
    quota_cost,
    cache_ttl_minutes, requires_approval, approval_chain_id,
    keywords, negative_keywords, negative_keyword_penalty,
    regex_pattern, description, example_queries, negative_examples,
    handler_class, tool_name, max_tokens, response_template,
    is_active, priority, metadata,
    chart_type, required_entities, confidence_boost,
    config_version, previous_snapshot,
    semantic_domain, semantic_action, semantic_object, semantic_path,
    deleted_at
FROM ai_intent_configs
WHERE deleted_at IS NULL
  AND is_active = true
ORDER BY priority DESC, intent_code ASC
"""


async def load_snapshot(pool) -> IntentSnapshot:
    """Load all visible intents into snapshot. Called on startup + every
    AI_CONFIG_REFRESH_S seconds + on /api/ai/intent/cache/invalidate.

    Returns the new snapshot. Replaces global singleton atomically.
    """
    global _current_snapshot

    async with pool.acquire() as conn:
        try:
            rows_raw = await conn.fetch(SELECT_SQL)
        except Exception as exc:
            # P1 读写分块部署顺序防御 (2026-07-23): required_permission 列由
            # Java Flyway 迁移添加; Python 先于 Java 部署时列不存在 →
            # UndefinedColumnError。回落旧列集, snapshot 照常可用 (行内无
            # required_permission 键 → 读写过滤按无权限码处理), 迁移跑完后
            # 下个刷新周期自动用上新列。
            if "required_permission" not in str(exc):
                raise
            logger.warning(
                "[ai.db] required_permission 列不存在 (Java 迁移未跑?), 回落旧列集: %s", exc
            )
            rows_raw = await conn.fetch(
                SELECT_SQL.replace(" required_roles, required_permission,",
                                   " required_roles,")
            )

    rows = [dict(r) for r in rows_raw]
    snap = IntentSnapshot(
        rows=rows,
        loaded_at_unix=time.time(),
        max_config_version=max(
            (r["config_version"] for r in rows if r["config_version"] is not None),
            default=0,
        ),
    )

    async with _snapshot_lock:
        _current_snapshot = snap

    logger.info(
        "Loaded ai_intent_configs snapshot: %d rows, max_config_version=%d",
        len(rows), snap.max_config_version,
    )
    return snap


def get_current_snapshot() -> IntentSnapshot:
    """Return current snapshot (no lock needed for read in CPython, but
    callers must not mutate)."""
    return _current_snapshot


def filter_intents_for_request(
    rows: List[Dict[str, Any]],
    factoryId: str,
    businessType: str,
) -> List[Dict[str, Any]]:
    """Filter snapshot rows by factory_id + business_type per AIIntentConfig
    Javadoc:
        factory_id == null  → platform-level (all factories see)
        factory_id == X     → only X sees

        business_type COMMON  → all business types see
        business_type FACTORY → only FACTORY callers see
        business_type RESTAURANT → only RESTAURANT callers see

    Defensive: also strips soft-deleted + inactive even though SQL already
    does — fixture-driven tests may pass rows that include them.
    """
    visible = []
    for r in rows:
        # soft-delete defense (SQL also strips, but tests pass full set)
        if r.get("deleted_at") is not None:
            continue
        # is_active defense
        if r.get("is_active") is False:
            continue
        # factory scope
        row_factory = r.get("factory_id")
        if row_factory is not None and row_factory != factoryId:
            continue
        # business type scope
        bt = r.get("business_type")
        if bt != "COMMON" and bt != businessType:
            continue
        visible.append(r)
    return visible


_WRITE_SENSITIVITY = frozenset({"HIGH", "CRITICAL"})
_WRITE_PERMISSION_ACTIONS = frozenset({"write", "read_write"})


def _is_write_row(r: Dict[str, Any]) -> bool:
    """写意图判定 (镜像 Java WriteGuardService.isWriteIntent 的意图侧口径):
    sensitivity HIGH/CRITICAL, 或 required_permission 的 action 段为
    write/read_write。判定仅用于目录过滤 (UX 层); 执行期强制在 Java。"""
    if str(r.get("sensitivity_level") or "").upper() in _WRITE_SENSITIVITY:
        return True
    perm = str(r.get("required_permission") or "")
    if ":" in perm and perm.split(":", 1)[1] in _WRITE_PERMISSION_ACTIONS:
        return True
    return False


def filter_rows_by_rw_mode(
    rows: List[Dict[str, Any]],
    mode: Any,
    user_permissions: Any,
) -> List[Dict[str, Any]]:
    """P1 读写分块 (2026-07-23 spec §4.3): 识别层目录过滤。

    - mode == "READ" (咨询tab): 剔除全部写意图 — 咨询区永远匹配不到写。
    - mode == "OPERATE" 且 user_permissions 非 None: 剔除用户无权限码的
      写意图 (精确匹配; Java hasAnyPermission 的通配等高级语义不在此镜像,
      执行期 Java 仍是权威 — 这里只是让无权限用户不被错误引导)。
    - mode 为 None/其他 (老客户端): 不过滤, 行为与 P1 之前逐字节一致。

    Fail-open: 任何异常返回原 rows (过滤是 UX 优化, 不是安全边界)。
    """
    try:
        m = str(mode).upper() if mode else ""
        if m == "READ":
            return [r for r in rows if not _is_write_row(r)]
        if m == "OPERATE" and user_permissions is not None:
            allowed = {str(p) for p in user_permissions}
            out = []
            for r in rows:
                if _is_write_row(r):
                    perm = str(r.get("required_permission") or "")
                    if perm and perm not in allowed:
                        continue
                out.append(r)
            return out
        return rows
    except Exception:
        logger.warning("[ai.db] rw-mode 过滤异常, fail-open 返回未过滤目录", exc_info=True)
        return rows


def max_config_version(rows: List[Dict[str, Any]]) -> int:
    """Used for cache invalidation: Java sends its highest known
    config_version, Python compares to its snapshot max."""
    if not rows:
        return 0
    return max(r["config_version"] for r in rows)


def row_to_dto(row: Dict[str, Any]) -> AIIntentConfigDto:
    """Convert raw asyncpg row to Pydantic DTO. snake_case → camelCase.

    confidence_boost: PG numeric type returns Decimal in asyncpg; we coerce
    to float for byte-shape parity with Java BigDecimal-as-JSON-number. See
    ai.dto.AIIntentConfigDto.confidenceBoost docstring for rationale.
    """
    cb = row.get("confidence_boost", 0.0)
    if cb is None:
        cb = 0.0
    cb = float(cb)
    is_active_raw = row.get("is_active")
    return AIIntentConfigDto(
        id=row["id"],
        factoryId=row.get("factory_id"),
        businessType=row.get("business_type") or "COMMON",
        intentCode=row["intent_code"],
        intentName=row["intent_name"],
        intentCategory=row.get("intent_category"),
        sensitivityLevel=row.get("sensitivity_level") or "LOW",
        requiredRoles=row.get("required_roles"),
        quotaCost=row.get("quota_cost") or 1,
        cacheTtlMinutes=row.get("cache_ttl_minutes") or 0,
        requiresApproval=row.get("requires_approval") or False,
        approvalChainId=row.get("approval_chain_id"),
        keywords=row.get("keywords"),
        negativeKeywords=row.get("negative_keywords"),
        negativeKeywordPenalty=row.get("negative_keyword_penalty") or 15,
        regexPattern=row.get("regex_pattern"),
        description=row.get("description"),
        exampleQueries=row.get("example_queries"),
        negativeExamples=row.get("negative_examples"),
        handlerClass=row.get("handler_class"),
        toolName=row.get("tool_name"),
        maxTokens=row.get("max_tokens") or 2000,
        responseTemplate=row.get("response_template"),
        isActive=is_active_raw if is_active_raw is not None else True,
        priority=row.get("priority") or 0,
        metadata=row.get("metadata"),
        chartType=row.get("chart_type"),
        requiredEntities=row.get("required_entities"),
        confidenceBoost=cb,
        configVersion=row.get("config_version") or 1,
        previousSnapshot=row.get("previous_snapshot"),
        semanticDomain=row.get("semantic_domain"),
        semanticAction=row.get("semantic_action"),
        semanticObject=row.get("semantic_object"),
        semanticPath=row.get("semantic_path"),
    )


async def start_periodic_refresh(pool, stop_event: asyncio.Event) -> None:
    """Background task that reloads snapshot every config_refresh_s seconds.

    Cancellation-safe: stop_event.set() exits the loop cleanly without
    cancelling the in-flight load. Errors during load are logged but do not
    crash the refresher — the previous snapshot remains in effect until the
    next successful load.
    """
    while not stop_event.is_set():
        try:
            await load_snapshot(pool)
        except Exception:
            logger.exception("Failed to refresh ai_intent_configs snapshot")
        try:
            await asyncio.wait_for(
                stop_event.wait(),
                timeout=default_config.config_refresh_s,
            )
        except asyncio.TimeoutError:
            pass  # Normal: timeout means do another refresh


__all__ = [
    "IntentSnapshot",
    "load_snapshot",
    "get_current_snapshot",
    "filter_intents_for_request",
    "max_config_version",
    "row_to_dto",
    "start_periodic_refresh",
    "SELECT_SQL",
]
