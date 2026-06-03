-- W1a shadow router harness: columns for champion-vs-challenger agreement logging on
-- intent_match_records. These columns are LIVE on prod already (hand-added before W1a) and are
-- referenced by IntentMatchRecordRepository.updateShadowResult / markZpdBoundary native SQL, but
-- they were never under Flyway -> fresh / test / CI databases lack them and the native UPDATE fails.
-- This brings them under version control. Idempotent (ADD COLUMN IF NOT EXISTS) -> no-op on prod.
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS shadow_intent_code   VARCHAR(64);
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS shadow_confidence    NUMERIC(6,4);
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS shadow_agreed        BOOLEAN;
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS classifier_entropy   NUMERIC(8,4);
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS classifier_margin    NUMERIC(8,4);
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS classifier_max_logit NUMERIC(10,6);
ALTER TABLE intent_match_records ADD COLUMN IF NOT EXISTS zpd_boundary         BOOLEAN DEFAULT FALSE;
