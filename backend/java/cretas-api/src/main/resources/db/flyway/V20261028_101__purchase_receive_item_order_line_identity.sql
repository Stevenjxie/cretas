-- purchase_receive_items 只由 Hibernate 实体建表, Flyway 目录里没有 CREATE TABLE。Spring Boot 先跑
-- Flyway 后跑 ddl-auto, 所以全新库跑到这里时该表还不存在, 裸语句会让整个应用起不来
-- (老库上表早已存在, 因此这个缺陷只在 CI 的全新库上出现)。守卫写法同 V20261027_41。
DO $$
BEGIN
    IF to_regclass('public.purchase_receive_items') IS NULL THEN
        RAISE NOTICE 'V20261028_101 skipped: purchase_receive_items not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE purchase_receive_items
        ADD COLUMN IF NOT EXISTS purchase_order_item_id BIGINT;

    CREATE INDEX IF NOT EXISTS idx_pri_order_item
        ON purchase_receive_items (purchase_order_item_id);

    COMMENT ON COLUMN purchase_receive_items.purchase_order_item_id IS
        'Stable PO line identity for receipt allocation; historical rows may be null';
END $$;
