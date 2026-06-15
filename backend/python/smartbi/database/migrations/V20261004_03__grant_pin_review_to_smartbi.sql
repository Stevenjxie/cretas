-- Grant the Phase 0 pin + review-queue tables to the runtime role (smartbi_user).
-- V20261004_02 created them owned by the migration runner; without these grants the
-- Python service hits "permission denied" on every pin/review query (the mapper is
-- fail-open so it degrades to legacy behaviour instead of crashing, but Phase 0's
-- new functionality silently does nothing). Idempotent (GRANT re-applies cleanly).
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_bi_pin_mappings TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_bi_mapping_review_queue TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE smart_bi_pin_mappings_id_seq TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE smart_bi_mapping_review_queue_id_seq TO smartbi_user;
