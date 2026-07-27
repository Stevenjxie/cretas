-- 2026-07-28 restaurant AI flywheel reconnect (card 2): the human-reviewed
-- whole-sentence promotion registry moves from a Python module constant into
-- Postgres, so the flywheel has a durable exit that does not require a code
-- deploy for every approved phrase.
--
-- WHY A TABLE AND NOT THE MODULE DICT:
--   `_APPROVED_EXACT_ROUTES` (smartbi/gold/restaurant_intent.py) held 3
--   reviewed phrases mapped to a bare resolver code.  It was only consulted
--   by the LEGACY parse branch; production restaurant chat runs
--   `parse_restaurant_query(semantic_first=True)`, which never reached it, so
--   every repeat of a reviewed question still paid a full REVIEW-tier LLM
--   call (5-12s).  This table is the storage the semantic-first branch reads.
--
-- WHY `ai_promoted_routes` AND NOT `restaurant_promoted_routes`:
--   platform-level asset.  `domain` discriminates the business line; the
--   first and (for now) only value is 'restaurant'.
--
-- WHAT `plan_json` HOLDS (load-bearing, read before editing a row):
--   the RAW planner output contract (the same JSON shape `_t3_llm_parse`
--   returns and `_semantic_spec_from_t3` compiles), NOT a sealed QuerySpec.
--   Time MUST stay a structured/relative description (`time_range: null`, or
--   a relative descriptor) — never a concrete date.  A hit recompiles the
--   plan against TODAY, which is what keeps a promoted route from serving a
--   stale window after midnight.  Storing resolved dates here would be the
--   exact bug the in-process route cache's docstring warns about.
--
-- PK IS (domain, normalized_phrase): one reviewed plan per phrase per domain.
--   `scope` therefore selects WHO may see that single row ('global', or one
--   factory_id) rather than allowing a per-tenant override of a global
--   phrase.  A tenant-specific promotion must use a phrase no global row
--   claims.
--
-- Version collision check against origin/main frontier (V20261029_02):
--   git ls-tree origin/main backend/python/smartbi/database/migrations/ \
--     | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | tail -1
--   -> V20261029_02, so V20261030_01 is above the frontier.
--   (The V-series is a monotonic counter, not the wall-clock date: the
--   V20261029_* pair was committed 2026-07-26.)
--
-- Idempotent: CREATE ... IF NOT EXISTS / DROP POLICY IF EXISTS / ON CONFLICT.

CREATE TABLE IF NOT EXISTS ai_promoted_routes (
    domain            VARCHAR(32)  NOT NULL,
    normalized_phrase TEXT         NOT NULL,
    plan_json         JSONB        NOT NULL,
    plan_version      VARCHAR(64)  NOT NULL DEFAULT 'restaurant-query-plan-v2',
    source            VARCHAR(32)  NOT NULL DEFAULT 'manual_seed',
    scope             VARCHAR(64)  NOT NULL DEFAULT 'global',
    reviewed_by       VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    hit_count         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ai_promoted_routes_pkey
        PRIMARY KEY (domain, normalized_phrase),
    CONSTRAINT chk_ai_promoted_routes_source
        CHECK (source IN ('flywheel', 'manual_seed')),
    CONSTRAINT chk_ai_promoted_routes_scope
        CHECK (scope <> ''),
    CONSTRAINT chk_ai_promoted_routes_phrase
        CHECK (normalized_phrase <> '' AND normalized_phrase = btrim(normalized_phrase)),
    CONSTRAINT chk_ai_promoted_routes_plan_object
        CHECK (jsonb_typeof(plan_json) = 'object')
);

-- Hot read path: "every route this tenant may replay, for one domain and one
-- plan contract version".  Phrase equality is matched in Python (the same
-- conservative whole-sentence normalization the reviewed registry always
-- used), so the index serves the catalogue load, not a per-phrase lookup.
CREATE INDEX IF NOT EXISTS idx_ai_promoted_routes_domain_scope
    ON ai_promoted_routes (domain, plan_version, scope);

-- RLS: mirrors external_benchmark_observation (V20261004_05), the existing
-- "global rows + tenant rows in one table" precedent.  The asymmetry is the
-- whole point: everyone READS the globally reviewed dictionary, but no tenant
-- session may CREATE or MODIFY a global row — otherwise one tenant could
-- poison the deterministic answer of every other tenant.  Only the seeding /
-- promotion path, which pins app.factory_id to the '__internal__' sentinel,
-- writes global rows.
ALTER TABLE ai_promoted_routes ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_promoted_routes FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS ai_promoted_routes_read ON ai_promoted_routes;
CREATE POLICY ai_promoted_routes_read ON ai_promoted_routes
    FOR SELECT
    USING (
        scope = 'global'
        OR scope = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS ai_promoted_routes_insert ON ai_promoted_routes;
CREATE POLICY ai_promoted_routes_insert ON ai_promoted_routes
    FOR INSERT
    WITH CHECK (
        scope = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS ai_promoted_routes_update ON ai_promoted_routes;
CREATE POLICY ai_promoted_routes_update ON ai_promoted_routes
    FOR UPDATE
    USING (
        scope = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    )
    WITH CHECK (
        scope = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

-- GRANT DML (recurring grant-gap — the runner applies DDL as the postgres
-- superuser and does NOT auto-grant; a new smartbi table without this line
-- fails open with "permission denied for table" on every read, which for
-- this table would silently disable the zero-token path.  Mirrors
-- V20260708_02__grant_pending_clarifications.sql / V20260428_03.)
-- No DELETE: retiring a promotion is a reviewed operation, not something the
-- app role should be able to do.  No sequence grant: the PK is natural.
GRANT SELECT, INSERT, UPDATE ON ai_promoted_routes TO smartbi_user;

COMMENT ON TABLE ai_promoted_routes IS
    'Human-reviewed whole-sentence query promotions replayed without an LLM call. plan_json is a raw planner-contract plan with RELATIVE time only; it is recompiled against today on every hit.';
COMMENT ON COLUMN ai_promoted_routes.plan_json IS
    'Raw planner output contract (_t3_llm_parse shape). Never a sealed spec, never a concrete date range.';
COMMENT ON COLUMN ai_promoted_routes.scope IS
    'global = visible to every tenant (write requires the __internal__ sentinel); otherwise a single factory_id.';
COMMENT ON COLUMN ai_promoted_routes.hit_count IS
    'Maintained by the promotion tooling, not by the runtime read path (the chat hot path stays read-only on this table).';

-- ── Seed: the 3 existing reviewed phrases from _APPROVED_EXACT_ROUTES ──────
-- These are the ONLY rows seeded.  No new phrase is auto-promoted; adding one
-- stays a human review gate (scripts/restaurant-intent-promote.py --apply).
--
-- The plan below was verified to compile byte-identically to the legacy
-- `_build_spec("RESTAURANT_OPS_GROSS_MARGIN", q, tier="exact",
--  planner_authority="promoted_exact", require_explicit_time=True)` output for
-- all four approved sentence shapes (bare / +time / +all-stores / +both):
-- zero field diffs outside plan_hash/source_tier/planner_authority.
--
-- time_range is null on purpose: the bare phrase carries no window, so the
-- deterministic time gate still asks for one — identical to today's behavior.
-- Transaction-local (the runner already wraps this file in BEGIN/COMMIT), so
-- the sentinel cannot leak past this migration.  The runner connects as the
-- postgres superuser, which bypasses RLS anyway; this makes the intent
-- explicit and keeps the seed valid if the runner role is ever narrowed.
SELECT set_config('app.factory_id', '__internal__', true);

INSERT INTO ai_promoted_routes
    (domain, normalized_phrase, plan_json, plan_version, source, scope, reviewed_by)
VALUES
    ('restaurant', '哪个菜卖得好',
     '{"intent":"RESTAURANT_OPS_GROSS_MARGIN","time_range":null,"wants_margin":false,"asks_profitability":false,"requested_metrics":["sales_volume"],"analysis_action":"lookup","dimensions":["dish"],"dish":null,"store":null,"stores":[],"store_scope":null,"confidence":1.0,"clarification_needed":false,"missing_fields":[],"clarification_question":null,"clarification_options":[]}'::jsonb,
     'restaurant-query-plan-v2', 'manual_seed', 'global', 'migration-seed'),
    ('restaurant', '哪个菜卖得最好',
     '{"intent":"RESTAURANT_OPS_GROSS_MARGIN","time_range":null,"wants_margin":false,"asks_profitability":false,"requested_metrics":["sales_volume"],"analysis_action":"lookup","dimensions":["dish"],"dish":null,"store":null,"stores":[],"store_scope":null,"confidence":1.0,"clarification_needed":false,"missing_fields":[],"clarification_question":null,"clarification_options":[]}'::jsonb,
     'restaurant-query-plan-v2', 'manual_seed', 'global', 'migration-seed'),
    ('restaurant', '哪个菜最好卖',
     '{"intent":"RESTAURANT_OPS_GROSS_MARGIN","time_range":null,"wants_margin":false,"asks_profitability":false,"requested_metrics":["sales_volume"],"analysis_action":"lookup","dimensions":["dish"],"dish":null,"store":null,"stores":[],"store_scope":null,"confidence":1.0,"clarification_needed":false,"missing_fields":[],"clarification_question":null,"clarification_options":[]}'::jsonb,
     'restaurant-query-plan-v2', 'manual_seed', 'global', 'migration-seed')
ON CONFLICT (domain, normalized_phrase) DO NOTHING;

-- Verification (run after apply):
--   SELECT domain, normalized_phrase, scope, source, plan_json->>'intent'
--     FROM ai_promoted_routes ORDER BY normalized_phrase;
--   -- expect 3 rows, all scope=global, intent=RESTAURANT_OPS_GROSS_MARGIN
--   SET ROLE smartbi_user;
--   SELECT set_config('app.factory_id', 'DEMO_REST', false);
--   SELECT count(*) FROM ai_promoted_routes;   -- expect 3 (global rows visible)
--   RESET ROLE;
--
-- Rollback:
--   DROP TABLE IF EXISTS ai_promoted_routes;
--   (the runtime fails open to the LLM planner when the table is missing)
