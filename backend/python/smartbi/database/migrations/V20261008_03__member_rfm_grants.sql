-- Grant smartbi_user write privileges on the 会员 RFM (CRM P0) analytics
-- tables. V20261008_01/02 created fact_member_consumption,
-- agg_member_rfm_segment, agg_member_rfm_tier, agg_member_lifecycle — all
-- owned by postgres (the migration runner); without this grant the app role
-- smartbi_user gets "permission denied for table" on INSERT — hit at deploy
-- time by both the demo-data loader (fact_member_consumption) and
-- materialize_member_rfm (agg_member_rfm_segment / agg_member_rfm_tier /
-- agg_member_lifecycle). The runner does NOT auto-grant; this mirrors
-- V20261007_03__member_profile_grants.sql (itself mirroring
-- V20261005_03__void_grants.sql). Idempotent (GRANT).

GRANT SELECT, INSERT, UPDATE, DELETE ON fact_member_consumption  TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_member_rfm_segment   TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_member_rfm_tier      TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_member_lifecycle     TO smartbi_user;

-- fact_member_consumption has a BIGSERIAL id; the three agg_* Gold tables
-- all have composite PKs (no sequence).
GRANT USAGE, SELECT ON SEQUENCE fact_member_consumption_id_seq TO smartbi_user;
