-- V20260822_04__fix_sales_delivery_status_check.sql
--
-- Hotfix: ck_sdr_status check constraint missing 5 enum values
-- (drift since PR #757 / commit 67aea49cc which added PENDING_WAREHOUSE_CONFIRM).
--
-- Symptom: ANY delivery creation via SalesServiceImpl.createDeliveryRecord
-- with salesOrderId fails with:
--   ERROR: new row for relation "sales_delivery_records" violates check constraint "ck_sdr_status"
-- because status defaults to PENDING_WAREHOUSE_CONFIRM but DB constraint only
-- accepts {DRAFT, PICKED, SHIPPED, DELIVERED, RETURNED}.
--
-- Found by: Sprint 10 Loop 1 prod smoke test 2026-05-21 (PR #165 ShipmentConfirmCreateTool).
-- Affects: ALL delivery creation paths in prod, not just Sprint 10 (latent P0).
--
-- SalesDeliveryStatus enum (entity/enums/SalesDeliveryStatus.java) declares:
--   DRAFT / PENDING_WAREHOUSE_CONFIRM / PICKED / SHIPPED / DELIVERED / RETURNED

ALTER TABLE sales_delivery_records DROP CONSTRAINT IF EXISTS ck_sdr_status;

ALTER TABLE sales_delivery_records ADD CONSTRAINT ck_sdr_status
    CHECK (status::text = ANY (ARRAY[
        'DRAFT'::text,
        'PENDING_WAREHOUSE_CONFIRM'::text,
        'PICKED'::text,
        'SHIPPED'::text,
        'DELIVERED'::text,
        'RETURNED'::text
    ]));

COMMENT ON CONSTRAINT ck_sdr_status ON sales_delivery_records
    IS 'Sprint 10 Loop 1 hotfix: extended to include PENDING_WAREHOUSE_CONFIRM (PR #757 drift).';
