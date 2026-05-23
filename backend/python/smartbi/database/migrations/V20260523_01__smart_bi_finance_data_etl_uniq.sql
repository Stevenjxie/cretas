-- V20260523_01__smart_bi_finance_data_etl_uniq.sql
-- Sprint 11.5 Phase D — add UNIQUE INDEX to smart_bi_finance_data for ETL upsert.
--
-- Background: smart_bi_finance_data currently has 0 unique constraints (per
-- spec §3.6). The Phase D Python ETL (restaurant_finance_etl.py) needs
-- ON CONFLICT (factory_id, upload_id, record_date, record_type, category)
-- DO UPDATE to be idempotent on re-run (one-time backfill + nightly cron
-- on rolling 30-day window both re-process the same rows).
--
-- This migration adds a partial UNIQUE INDEX (excludes soft-deleted rows) so:
--   1. ETL upserts are idempotent — re-run on same (factory, date) overwrites
--      instead of double-inserting.
--   2. Excel upload path remains unaffected because each upload generates a
--      unique upload_id (BIGSERIAL on smart_bi_excel_uploads), so the
--      (upload_id, ...) tuple is always distinct between uploads.
--   3. ETL-generated rows reserve upload_id = 0 sentinel (per spec §3.1).
--      Operator Excel uploads (upload_id >= 1) win in the RestaurantFinancialMetricsFetcher
--      latest-upload filter (max upload_id wins).
--
-- Existing prod data: 0 rows (verified 2026-05-23 across all factories).
-- No constraint-violation risk on apply.
--
-- Spec: docs/superpowers/specs/2026-05-23-sprint11.5-etl-design.md §3.6
-- Phase: D (ETL implementation)

CREATE UNIQUE INDEX IF NOT EXISTS uq_smart_bi_finance_data_etl
    ON smart_bi_finance_data (factory_id, upload_id, record_date, record_type, category)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX uq_smart_bi_finance_data_etl IS
    'Sprint 11.5 Phase D — supports ETL ON CONFLICT upsert. Partial index excludes soft-deleted rows.';

-- Rollback:
--   DROP INDEX IF EXISTS uq_smart_bi_finance_data_etl;
