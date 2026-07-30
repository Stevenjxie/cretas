-- R7 Issue 3 (F): sales_order_prepayment_records_items.parent_id type alignment.
--
-- The sub-table was auto-generated before DDLExecutor.resolveParentIdSqlType
-- (R4 fix, commit 7b23217b0) taught new sub-tables to inherit parent_id type
-- from their parent. Parent sales_orders.id is VARCHAR but this sub-table
-- had parent_id UUID — type-aware cast in DynamicTableService would fail new
-- writes with "invalid input syntax for type uuid" and verifyParentOwnership
-- could not link rows.
--
-- Already applied directly on cretas_prod_db 2026-04-16 03:34 CST via psql
-- (2 rows preserved — UUID values stringify to VARCHAR cleanly, still linking
-- to parent sales_order id=cb4e687d-... via text match).
--
-- Originally staged as V20260416_02 in /db/migration/ but relocated here as
-- V20260416_05 after parallel session took the earlier version slots in
-- /db/flyway/ (V20260416_02 trigger-chain-obs, V20260416_03 po-status-expand,
-- V20260416_04 scheduler-obs).
--
-- Idempotent: DO $$ block checks column type first.
--   - Fresh env without this sub-table → skip (table doesn't exist)
--   - Env with column already VARCHAR → skip (RAISE NOTICE, no ALTER)
--   - Env with column still UUID → convert

-- sales_order_prepayment_records_items 只由 Hibernate 实体建表, Flyway 目录里没有 CREATE TABLE。Spring Boot 先跑
-- Flyway 后跑 ddl-auto, 所以全新库跑到这里时该表还不存在, 裸语句会让整个应用起不来
-- (老库上表早已存在, 因此这个缺陷只在 CI 的全新库上出现)。守卫写法同 V20261027_41。
DO $$
BEGIN
    IF to_regclass('public.sales_order_prepayment_records_items') IS NULL THEN
        RAISE NOTICE 'V20260416_05 skipped: sales_order_prepayment_records_items not present before Hibernate DDL';
        RETURN;
    END IF;

    DO $$
    BEGIN
      IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'sales_order_prepayment_records_items'
          AND column_name = 'parent_id'
          AND data_type = 'uuid'
      ) THEN
        ALTER TABLE sales_order_prepayment_records_items
          ALTER COLUMN parent_id TYPE varchar(100) USING parent_id::text;
        RAISE NOTICE 'Migrated sales_order_prepayment_records_items.parent_id UUID -> VARCHAR(100)';
      ELSE
        RAISE NOTICE 'sales_order_prepayment_records_items.parent_id already VARCHAR or table missing — skipping';
      END IF;
    END $$;
END $$;
