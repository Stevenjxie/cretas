-- Flywheel Tiering v2: add proposal_count + last_proposed_at to ai_learned_expressions
-- These columns enable the "consensus re-proposal" promote mechanism:
--   staged (is_active=false) rows accumulate proposals from successive LLM sessions;
--   when proposal_count reaches the threshold (3), the row is promoted to active.
--
-- Design notes:
--   - ADD COLUMN IF NOT EXISTS: idempotent (safe to re-run, no error if already exists).
--   - DEFAULT 1: existing rows start at count=1 (the original write counts as proposal #1).
--   - No mass UPDATE of is_active — NULL (dormant) rows stay dormant; the re-proposal
--     mechanism will organically promote them if they deserve it.

ALTER TABLE ai_learned_expressions
    ADD COLUMN IF NOT EXISTS proposal_count     INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS last_proposed_at   TIMESTAMP   NULL;

COMMENT ON COLUMN ai_learned_expressions.proposal_count    IS 'Flywheel v2: number of times this expression has been proposed (including initial write). Staged rows are promoted to active when count >= 3 and confidence >= 0.70.';
COMMENT ON COLUMN ai_learned_expressions.last_proposed_at  IS 'Flywheel v2: timestamp of the most recent re-proposal (dedup hit on a staged/dormant row).';
