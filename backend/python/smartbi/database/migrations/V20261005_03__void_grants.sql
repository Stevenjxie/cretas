-- Grant smartbi_user write privileges on the 撤单 (void) analytics tables.
-- V20261005_01/02 created fact_pos_void + agg_daily_void owned by postgres (the
-- migration runner); without this grant the app role smartbi_user gets
-- "permission denied for table" on INSERT — hit at deploy time by both the
-- demo-data loader (fact_pos_void) and materialize_daily_void (agg_daily_void).
-- The runner does NOT auto-grant; this mirrors V20260428_03__b_silver_grants.sql
-- and V20260711_02__grant_config_tables_to_app.sql. Idempotent (GRANT).

GRANT SELECT, INSERT, UPDATE, DELETE ON fact_pos_void  TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_daily_void TO smartbi_user;

-- fact_pos_void has a BIGSERIAL id; agg_daily_void has a composite PK (no seq).
GRANT USAGE, SELECT ON SEQUENCE fact_pos_void_id_seq TO smartbi_user;
