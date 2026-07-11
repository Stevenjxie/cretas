-- Grant smartbi_user write privileges on the 区域坪效 (zone-efficiency)
-- analytics tables. V20261006_01/02 created fact_zone_sales + agg_daily_zone
-- owned by postgres (the migration runner); without this grant the app role
-- smartbi_user gets "permission denied for table" on INSERT — hit at deploy
-- time by both the demo-data loader (fact_zone_sales) and
-- materialize_daily_zone (agg_daily_zone). The runner does NOT auto-grant;
-- this mirrors V20261005_03__void_grants.sql. Idempotent (GRANT).

GRANT SELECT, INSERT, UPDATE, DELETE ON fact_zone_sales  TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_daily_zone TO smartbi_user;

-- fact_zone_sales has a BIGSERIAL id; agg_daily_zone has a composite PK (no seq).
GRANT USAGE, SELECT ON SEQUENCE fact_zone_sales_id_seq TO smartbi_user;
