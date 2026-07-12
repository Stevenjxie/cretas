"""Per-tenant daily LLM token budget tracking.

Table: agent_budget_daily (PK: factory_id, date).
Tier source: agent_tenant_config.tier (+ optional custom_cap_override).

Contract:
- `check_budget(factory_id)` — called before LLM call. Returns
  BudgetCheckResult(blocked, tokens_used, tokens_cap). Creates the daily
  row lazily on first call (UPSERT with tier-derived cap).
- `consume(factory_id, tokens)` — called after LLM call completes.
  UPDATEs tokens_used; `blocked` column regenerates automatically.
- `reset_daily()` NOT needed — every new UTC date gets its own row.

Why daily not monthly:
- Free-tier LLM APIs (Aliyun, Zhipu) have per-day rate limits. Daily
  budget aligns with that reset cadence.
- If a tenant bursts through cap on one day, next day is clean — no
  prorated rollover nonsense.

RLS expectation:
- Caller has already set `app.factory_id` via tenant_ctx (the pool's
  setup callback does this on every acquire). The factory_id arg is
  belt-and-suspenders — must match session's tenant.
- Cross-tenant writes are blocked by the RLS WITH CHECK clause.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date
from typing import Optional

import asyncpg

logger = logging.getLogger(__name__)


# Default caps by tier. Override per-factory via agent_tenant_config.custom_cap_override.
DEFAULT_TIER_CAPS = {
    "basic": 50_000,
    "enterprise": 200_000,
}
FALLBACK_CAP = DEFAULT_TIER_CAPS["basic"]


@dataclass(frozen=True)
class BudgetCheckResult:
    blocked: bool
    tokens_used: int
    tokens_cap: int

    @property
    def tokens_remaining(self) -> int:
        return max(0, self.tokens_cap - self.tokens_used)


class AgentBudgetTracker:
    """Daily token budget per factory.

    Stateless; every method borrows a pool connection and writes
    SQL directly. Pool setup callback (set_pg_connection_tenant) handles
    RLS context; this class never calls SET LOCAL itself.
    """

    def __init__(self, pool: asyncpg.Pool):
        self._pool = pool

    async def _resolve_cap(self, conn: asyncpg.Connection, factory_id: str) -> int:
        """Look up cap from agent_tenant_config. Unknown factory → FALLBACK."""
        row = await conn.fetchrow(
            """
            SELECT tier, custom_cap_override
              FROM agent_tenant_config
             WHERE factory_id = $1
            """,
            factory_id,
        )
        if row is None:
            logger.info("budget: factory=%s not in agent_tenant_config, using basic cap", factory_id)
            return FALLBACK_CAP
        override = row["custom_cap_override"]
        if override is not None and override > 0:
            return int(override)
        return DEFAULT_TIER_CAPS.get(row["tier"], FALLBACK_CAP)

    async def check_budget(
        self, factory_id: str, today: Optional[date] = None
    ) -> BudgetCheckResult:
        """Read-or-create today's budget row; report blocked state.

        Idempotent — safe to call before every LLM request.
        Uses INSERT...ON CONFLICT so two concurrent requests creating
        the same day's row don't race.
        """
        today = today or _utc_today()
        async with self._pool.acquire() as conn:
            cap = await self._resolve_cap(conn, factory_id)
            row = await conn.fetchrow(
                """
                INSERT INTO agent_budget_daily (factory_id, date, tokens_cap)
                VALUES ($1, $2, $3)
                ON CONFLICT (factory_id, date) DO UPDATE
                    SET updated_at = NOW(),
                        -- Refresh the cap from the freshly-resolved value so a
                        -- mid-day agent_tenant_config.custom_cap_override change
                        -- (or tier change) takes effect the same day instead of
                        -- being stuck at whatever cap the row was first created
                        -- with. Without this, ON CONFLICT only bumped updated_at
                        -- and the daily row kept its original cap until the next
                        -- UTC day rolled over a fresh row.
                        tokens_cap = EXCLUDED.tokens_cap
                RETURNING tokens_used, tokens_cap, blocked
                """,
                factory_id, today, cap,
            )
        return BudgetCheckResult(
            blocked=bool(row["blocked"]),
            tokens_used=int(row["tokens_used"]),
            tokens_cap=int(row["tokens_cap"]),
        )

    async def consume(
        self, factory_id: str, tokens: int, today: Optional[date] = None
    ) -> BudgetCheckResult:
        """Add `tokens` to today's tokens_used. Returns post-consume state.

        Negative / zero tokens are a no-op (cache-hit path logs 0 cost).
        Row is created if missing (shouldn't happen if check_budget ran
        first, but defensive).
        """
        if tokens <= 0:
            return await self.check_budget(factory_id, today)
        today = today or _utc_today()
        async with self._pool.acquire() as conn:
            cap = await self._resolve_cap(conn, factory_id)
            row = await conn.fetchrow(
                """
                INSERT INTO agent_budget_daily
                    (factory_id, date, tokens_cap, tokens_used)
                VALUES ($1, $2, $3, $4)
                ON CONFLICT (factory_id, date) DO UPDATE
                    SET tokens_used = agent_budget_daily.tokens_used + EXCLUDED.tokens_used,
                        updated_at  = NOW()
                RETURNING tokens_used, tokens_cap, blocked
                """,
                factory_id, today, cap, tokens,
            )
        return BudgetCheckResult(
            blocked=bool(row["blocked"]),
            tokens_used=int(row["tokens_used"]),
            tokens_cap=int(row["tokens_cap"]),
        )


def _utc_today() -> date:
    # Server-local date. Python service runs in CST; budget resets at
    # local midnight which aligns with restaurant operating day — using
    # UTC would reset at 08:00 CST and confuse operators.
    return date.today()
