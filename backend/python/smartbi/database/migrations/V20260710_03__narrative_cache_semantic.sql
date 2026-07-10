-- Semantic fallback layer for narrative_cache (2026-07-10).
--
-- narrative_cache today is exact-SHA256-hash only (question + date_range +
-- factory_id) -- a natural paraphrase ("外卖堂食哪个赚钱" vs "外送跟到店哪个
-- 强") misses even when the underlying FactBook/plan would be identical.
--
-- This migration adds the columns needed for a semantic lookup ON TOP of
-- the existing exact-hash path (that path is unchanged and still tried
-- first -- cheapest + zero false-positive risk). New columns are all
-- nullable so existing rows keep working as exact-hash-only entries; only
-- rows written after this migration ships carry an embedding.
--
--   question_embedding -- gte-base-zh 768-dim vector of the raw question
--                          text (same model/dim as smart_bi_template_embeddings).
--   window_key          -- "{start_iso}|{end_iso}" -- the resolved date window.
--                          A semantic hit MUST come from the same window; two
--                          questions phrased alike but about different date
--                          ranges must never cross-serve (grounding guard).
--   plan_key            -- comma-joined sorted set of plan_dimensions() True
--                          keys (e.g. "channel,finance"). A semantic hit MUST
--                          also come from the same dimension set -- "堂食赚钱吗"
--                          (finance+channel) must never serve for "堂食忙吗"
--                          (channel only, no revenue dimension).
--
-- Both window_key and plan_key are hard SQL WHERE filters in
-- NarrativeCacheService.get_semantic (not just part of the similarity score)
-- -- see narrative_cache.py docstring for the grounding rationale.

ALTER TABLE narrative_cache
    ADD COLUMN IF NOT EXISTS question_embedding vector(768),
    ADD COLUMN IF NOT EXISTS window_key text,
    ADD COLUMN IF NOT EXISTS plan_key text;

-- Btree covering index for the semantic lookup's hard filter
-- (factory_id, window_key, plan_key) before the ORDER BY ... <=> ... scan.
-- Partial (WHERE question_embedding IS NOT NULL) keeps old exact-hash-only
-- rows out of this index entirely -- they can never satisfy a semantic query.
CREATE INDEX IF NOT EXISTS idx_narrative_cache_semantic
    ON narrative_cache (factory_id, window_key, plan_key)
    WHERE question_embedding IS NOT NULL;

-- Note: narrative_cache already has FORCE ROW LEVEL SECURITY +
-- tenant_isolation policy (2026_05_27_agent_layer.sql) scoped on
-- factory_id = current_setting('app.factory_id', true). These new
-- columns don't change that policy -- NarrativeCacheService.get_semantic
-- still SETs app.factory_id per-connection like get()/put() do, and the
-- policy's factory_id predicate already covers them (no per-column RLS
-- needed). No backfill: old rows simply have NULL embedding/window_key/
-- plan_key and are invisible to the semantic path (excluded by the partial
-- index's WHERE clause and by "question_embedding IS NOT NULL" in the query),
-- so they remain valid exact-hash-only entries until they expire naturally.

-- Rollback:
--   DROP INDEX IF EXISTS idx_narrative_cache_semantic;
--   ALTER TABLE narrative_cache
--       DROP COLUMN IF EXISTS question_embedding,
--       DROP COLUMN IF EXISTS window_key,
--       DROP COLUMN IF EXISTS plan_key;
