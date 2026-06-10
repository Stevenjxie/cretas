-- P0-1: Backfill quality column for existing smart_bi_distillation_samples rows.
--
-- Context (spec §1, 2026-06-11):
--   The `quality` column was added to the schema but NEVER written at any
--   capture site — every row currently has quality = NULL.  The export filter
--   `quality IS NULL OR quality >= N` was therefore a no-op (all rows pass).
--
-- Backfill rules (per spec §4 quality tiers):
--   source='chart_insight'        → quality=5  (claims-pinning verified)
--   source='materialization'      → quality=4  (structural-verified + served)
--   source='agent_insight'        → quality=4  (structural-verified + served)
--   source='chat_qa' (old rows)   → quality=NULL (leave as-is — bare-query
--                                                   rows without data context
--                                                   are polluted; must NOT be
--                                                   marked ≥4 and must not reach
--                                                   training until re-captured
--                                                   with context by P1-1 sweep)
--
-- After this migration the export filter `quality >= N` (fixed in P0-1) will
-- correctly exclude all chat_qa legacy rows and all future NULL rows.

UPDATE smart_bi_distillation_samples
SET quality = 5
WHERE source = 'chart_insight'
  AND quality IS NULL;

UPDATE smart_bi_distillation_samples
SET quality = 4
WHERE source IN ('materialization', 'agent_insight')
  AND quality IS NULL;

-- chat_qa rows: intentionally left as NULL.
-- They are bare-query pairs without data context — teaching a model on them
-- would teach hallucination. They will be excluded from export by the
-- `quality >= N` filter and re-captured with proper data context by the P1-1
-- chat_qa replay sweep.
