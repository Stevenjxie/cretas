-- Grant smartbi_user write privileges on the 会员储值+画像 (member profile)
-- analytics tables. V20261006_01/02 created dim_member, fact_member_recharge,
-- agg_member_tier, agg_member_birth_month, agg_member_recharge_daily — all
-- owned by postgres (the migration runner); without this grant the app role
-- smartbi_user gets "permission denied for table" on INSERT — hit at deploy
-- time by both the demo-data loader (dim_member / fact_member_recharge) and
-- materialize_member_* (agg_member_tier / agg_member_birth_month /
-- agg_member_recharge_daily). The runner does NOT auto-grant; this mirrors
-- V20261005_03__void_grants.sql (which itself mirrors
-- V20260428_03__b_silver_grants.sql and
-- V20260711_02__grant_config_tables_to_app.sql). Idempotent (GRANT).

GRANT SELECT, INSERT, UPDATE, DELETE ON dim_member                  TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON fact_member_recharge        TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_member_tier             TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_member_birth_month      TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_member_recharge_daily   TO smartbi_user;

-- dim_member / fact_member_recharge have BIGSERIAL ids; the three agg_* Gold
-- tables all have composite PKs (no sequence).
GRANT USAGE, SELECT ON SEQUENCE dim_member_id_seq           TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE fact_member_recharge_id_seq TO smartbi_user;
