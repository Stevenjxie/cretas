-- Canvas P3 batch 2: purchase_order_approval_rules @Version + soft-delete partial-unique migration.
--
-- 背景: P3 半-Canvas-ed UI wrap of PurchaseOrderApprovalRule entity. AUD-4 P1 optimistic-lock
-- requires `version` column. Entity already extends BaseEntity (has deleted_at), but the
-- UNIQUE(factory_id, rule_name) constraint blocks re-creation after soft-delete.
--
-- Changes:
-- 1. ADD COLUMN version BIGINT NOT NULL DEFAULT 0 (JPA @Version backing column)
-- 2. Convert UNIQUE(factory_id, rule_name) → partial-unique WHERE deleted_at IS NULL
--    so soft-deleted rows don't block re-creation
--
-- Idempotent: IF NOT EXISTS / IF EXISTS guards.

-- 1. Add version column (JPA @Version backing)
ALTER TABLE purchase_order_approval_rules
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- 2. Convert UNIQUE(factory_id, rule_name) → partial-unique (WHERE deleted_at IS NULL)
-- Original table: V20260517_01 with CONSTRAINT uk_poar_factory_name UNIQUE (factory_id, rule_name)
DO $$
DECLARE
    cons_name TEXT;
BEGIN
    SELECT conname INTO cons_name
    FROM pg_constraint
    WHERE conrelid = 'purchase_order_approval_rules'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) LIKE '%(factory_id, rule_name)%';
    IF cons_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE purchase_order_approval_rules DROP CONSTRAINT ' || quote_ident(cons_name);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_poar_factory_name_active
    ON purchase_order_approval_rules(factory_id, rule_name)
    WHERE deleted_at IS NULL;
