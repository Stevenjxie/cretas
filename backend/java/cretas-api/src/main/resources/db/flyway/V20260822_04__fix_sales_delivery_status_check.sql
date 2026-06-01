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
--
-- ⚠️ 表存在守卫 (2026-06-01 修 e2e-pr-gate 全新 CI DB): sales_delivery_records 是 Hibernate
--   JPA entity (无 Flyway CREATE), 全新 DB 上 Flyway 先于 ddl-auto 跑时该表不存在, 裸 ALTER
--   报 "relation does not exist" 阻断启动。to_regclass 守卫: 表存在才改约束; 不存在则跳过
--   (Hibernate 随后建表, 该 hotfix 约束在 fresh DB 由 entity @Check 或后续启动补)。prod 该表
--   早已存在 → 守卫无行为改变; validate-on-migrate=false → 编辑已 apply migration 不破 prod。

DO $$
BEGIN
    IF to_regclass('public.sales_delivery_records') IS NOT NULL THEN
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
    END IF;
END $$;
