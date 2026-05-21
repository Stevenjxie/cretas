-- Bug #2 硬化 (R4 2026-04-16): 防止同一 SO 多个待处理发票并发创建
-- 业务规则: 一个 SO 在任意时刻最多只能有 1 个 REQUESTED + 多个 APPROVED/ISSUED/REJECTED
-- 原服务层 pre-check 存在 TOCTOU race; DB 层 partial unique index 作为最终兜底.
--
-- 状态语义:
--   REQUESTED = 待审核 (客户端提交, 财务未审)
--   APPROVED = 审核通过 (未开具)
--   ISSUED = 已开具 (税票已出, 可多张)
--   REJECTED = 驳回 (业务拒绝, 可重提)
-- 只锁 REQUESTED — 其他状态可重复以支持分次开票/驳回重提场景.

-- Step 1: 清理现有历史 duplicate REQUESTED invoices — 只保留每 SO 最新的 1 张
-- 其他 REQUESTED 标 REJECTED + 审计备注 (软处理, 不删数据)
--
-- Guard added 2026-05-21 (PR fix: unblock recurring e2e-pr-gate Flyway failure):
-- requested_at column was historically added by Hibernate ddl-auto=update, not by
-- any Flyway migration. On long-lived prod/test DBs Hibernate supplied the column
-- long ago, so the IF EXISTS branch matches pre-guard behavior identically. On
-- fresh CI DBs (baseline-version=20260416.99 skips bootstrap V20260415_99) the
-- column is absent at this step → ELSE branch safely skips dedup (a fresh DB
-- with empty invoice_records has no duplicates to clean). Pattern mirrors
-- V20260416_05__prepayment_records_items_parent_id_to_varchar.sql:24-39.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'invoice_records'
          AND column_name  = 'requested_at'
    ) THEN
        WITH dupes AS (
            SELECT id,
                   ROW_NUMBER() OVER (
                       PARTITION BY factory_id, sales_order_id
                       ORDER BY requested_at DESC, id DESC
                   ) AS rn
            FROM invoice_records
            WHERE status = 'REQUESTED' AND deleted_at IS NULL
        )
        UPDATE invoice_records ir
        SET status = 'REJECTED',
            remark = COALESCE(remark, '') || ' [V20260419_01 auto-reject: duplicate REQUESTED before unique constraint]'
        FROM dupes
        WHERE ir.id = dupes.id AND dupes.rn > 1;
    ELSE
        RAISE NOTICE 'V20260419_01 Step 1 skipped — requested_at column not yet present (fresh DB baseline; no duplicates to clean since invoice_records is empty at this migration step)';
    END IF;
END $$;

-- Step 2: Partial unique index — 只锁 REQUESTED 防并发重复提交
--
-- Guard added 2026-05-21 (same fix iteration as Step 1): sales_order_id was
-- ALSO Hibernate-ddl-auto added (not in V20260415_99 bootstrap), so fresh CI
-- DBs lack the column at this migration step. Same evidence trail: PR #117
-- first attempt wrapped only Step 1, but CI showed:
--   ERROR: column "sales_order_id" does not exist
-- proving both columns are Hibernate-supplied. Index DDL references
-- sales_order_id directly; missing column blocks the CREATE INDEX. Guard
-- with same DO $$ IF EXISTS pattern. CREATE INDEX IF NOT EXISTS retains
-- idempotency on prod/test where the index already exists.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'invoice_records'
          AND column_name  = 'sales_order_id'
    ) THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_records_pending_per_so
            ON invoice_records (factory_id, sales_order_id)
            WHERE status = 'REQUESTED' AND deleted_at IS NULL;
    ELSE
        RAISE NOTICE 'V20260419_01 Step 2 skipped — sales_order_id column not yet present (fresh DB baseline; Hibernate ddl-auto will supply the column post-Flyway, but the partial unique index will be re-attempted on next deploy after Flyway re-validates with its current checksum — for index creation to take effect on prod, this migration would need to run a second time, which it does NOT. See follow-up audit task for proper resolution: lower baseline-version or move V20260415_99 above baseline.)';
    END IF;
END $$;

-- Note: partial unique index works in PostgreSQL. On MySQL would need trigger approach,
-- but project is now PG-only (per database-entity-sync rule).
