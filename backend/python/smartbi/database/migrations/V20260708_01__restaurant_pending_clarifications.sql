-- 2026-07-08 clarification-loop v1 fix: pending-clarification storage moves
-- from a per-process OrderedDict to Postgres.
--
-- Root cause (prod verified): cretas-python runs `uvicorn --workers 2`, so
-- the in-process `_PENDING_CLARIFICATIONS` store registered a pending entry
-- in worker A while the user's follow-up answer landed on worker B --
-- continuation became a coin flip. Multi-turn conversations MUST share
-- state across workers; the smartbi Postgres pool (the same pool
-- `parse_restaurant_query` already receives, home of agg_restaurant_daily_*)
-- is the shared store.
--
-- Semantics preserved from the in-process version (smartbi/gold/
-- restaurant_intent.py):
--   * one pending entry per (factory_id, session_key) -- PK; a newer
--     clarification for the same session UPSERTs over the older one
--   * consume-once: pop = atomic single-statement DELETE ... RETURNING
--   * TTL (~5 min) judged Python-side on created_at; expired rows are also
--     opportunistically swept (created_at < now() - 1 hour) on pop so the
--     table cannot bloat

CREATE TABLE IF NOT EXISTS restaurant_pending_clarifications (
    factory_id  TEXT NOT NULL,
    session_key TEXT NOT NULL,
    original_query TEXT NOT NULL,
    clarification_question TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (factory_id, session_key)
);
