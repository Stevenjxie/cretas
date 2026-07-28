-- 2026-07-29 上下文两层记忆 — 第二层「跨轮滚动会话状态摘要」
-- (spec: docs/superpowers/specs/2026-07-28-restaurant-ai-flywheel-reconnect-plan.md
--  §持续项 "上下文两层记忆")
--
-- WHAT THIS IS NOT: it is not a replacement for the 20-turn verbatim history.
--   `CHAT_SESSION_HISTORY_LIMIT = 20` (smartbi/services/chat_session_service.py)
--   is a product decision and stays exactly as it is.  `turns_history` remains
--   the verbatim record.  This column is a SECOND, much smaller layer.
--
-- WHAT IT HOLDS: a <=300-char Chinese state line answering "what is this
--   session about" — which stores / which dishes / which metrics ("口径") /
--   which window / what has already been concluded.  20 verbatim turns are
--   ~10k chars of prompt; this line is the compressed standing state the
--   planner needs even when a turn falls out of the 20-turn window, and it is
--   cheap enough to inject on every T3 call.
--
-- HOW IT IS PRODUCED: DETERMINISTICALLY, in Python
--   (`build_session_state_summary`), from the already-whitelisted structured
--   facts in `turns_history[*].context` (the same allowlist
--   `compact_structured_context` enforces) plus the tail of the latest answer
--   summary.  NO extra LLM call — an LLM-written summary would add one paid
--   call per turn, which is the exact opposite of the flywheel goal ("越用越
--   便宜").  The 300-char cap is enforced by code, not by asking a model to
--   "be brief".
--
-- INJECTION DEFENSE: the text is derived in part from LLM output, and it is
--   injected back into later turns' prompts, so it goes through the SAME
--   `sanitize_for_storage` / `_PROMPT_INJECTION_PATTERNS` scrub that
--   `parent_answer_summary` has used since Apr 26 2026.  Never store a value
--   in this column that has not been through that scrub.
--
-- TENANCY: unchanged.  The row is still keyed by (factory_id, user_id,
--   session_id); this migration introduces no new key shape and no new policy.
--
-- DEPLOY ORDER (additive column, expand phase only):
--   apply this migration BEFORE rolling out the Python that reads the column —
--   `ChatSessionService.lookup` selects it by name.  Old Python never mentions
--   the column, so applying it early is safe.  Same shape as
--   V20260427_01__chat_session_v3_history.sql (turns_history).
--
-- Version collision check against origin/main frontier:
--   git ls-tree origin/main backend/python/smartbi/database/migrations/ \
--     | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | tail -1
--   -> V20261030_01, so V20261031_01 is above the frontier.
--   (The V-series is a monotonic counter, not the wall-clock date — see the
--    same note in V20261030_01__ai_promoted_routes.sql.)
--
-- Idempotent: ADD COLUMN IF NOT EXISTS only.  No backfill: an existing live
-- session simply gets its summary on its next turn, and a NULL column is
-- treated by the reader as "no state yet" (identical to pre-feature behavior).

ALTER TABLE smart_bi_chat_session
    ADD COLUMN IF NOT EXISTS session_state_summary TEXT;

COMMENT ON COLUMN smart_bi_chat_session.session_state_summary IS
    'Layer 2 of conversation memory: deterministic <=300-char rolling session state (stores / dishes / metrics / window / latest conclusion). Derived in Python from turns_history, injection-scrubbed before write, injected into the T3 prompt DYNAMIC zone only. Layer 1 (turns_history, 20 verbatim turns) is unchanged.';

-- Verification (run after apply):
--   \d smart_bi_chat_session
--   -- expect column session_state_summary | text |
--   SELECT session_id, length(session_state_summary) AS chars, session_state_summary
--     FROM smart_bi_chat_session
--    WHERE session_state_summary IS NOT NULL
--    ORDER BY updated_at DESC LIMIT 5;
--   -- expect chars <= 300 on every row
--
-- Rollback:
--   ALTER TABLE smart_bi_chat_session DROP COLUMN IF EXISTS session_state_summary;
--   (the reader treats a missing summary as "no state yet"; the T3 prompt then
--    falls back to exactly the pre-feature dynamic zone)
