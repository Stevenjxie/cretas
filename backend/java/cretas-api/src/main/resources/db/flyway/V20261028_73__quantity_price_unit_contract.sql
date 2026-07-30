ALTER TABLE purchase_order_items
    ADD COLUMN IF NOT EXISTS price_unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS quantity_to_price_factor NUMERIC(24, 12);

UPDATE purchase_order_items
SET price_unit = unit
WHERE price_unit IS NULL;

UPDATE purchase_order_items
SET quantity_to_price_factor = 1
WHERE quantity_to_price_factor IS NULL;

ALTER TABLE purchase_order_items
    ALTER COLUMN price_unit SET NOT NULL,
    ALTER COLUMN quantity_to_price_factor SET NOT NULL,
    ALTER COLUMN quantity_to_price_factor SET DEFAULT 1;

-- purchase_receive_items 只由 Hibernate 实体建表, Flyway 目录里没有任何 CREATE TABLE。
-- 而 Spring Boot 的启动顺序是 Flyway 先跑、Hibernate ddl-auto 后跑, 所以在【全新库】上
-- 跑到这里时这张表还不存在, 裸 ALTER 会抛错 -> flywayInitializer 失败 -> 整个应用起不来。
-- 老库(prod/test/任何跑过一次的开发库)因为表早已由 Hibernate 建出而一直正常, 所以这个
-- 缺陷从 2026-07-17 本迁移进 main 起潜伏了两周: e2e-pr 门禁是 workflow_dispatch 专用,
-- 上一次成功运行是 07-16, 正好是它进来的前一天, 之后没人跑过。
--
-- 守卫写法与同目录 V20261027_41 一致(那里的注释写着 "entity-only in fresh CI DBs:
-- Flyway runs before Hibernate DDL")。老库上 to_regclass 非空, 行为与改动前完全一致;
-- 全新库上跳过, 表随后由 Hibernate 建出并自带 price_unit 列。
DO $$
BEGIN
    IF to_regclass('public.purchase_receive_items') IS NULL THEN
        RAISE NOTICE 'V20261028_73 skipped purchase_receive_items: not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE purchase_receive_items
        ADD COLUMN IF NOT EXISTS price_unit VARCHAR(20);

    UPDATE purchase_receive_items
    SET price_unit = unit
    WHERE price_unit IS NULL;

    ALTER TABLE purchase_receive_items
        ALTER COLUMN price_unit SET NOT NULL;
END $$;

ALTER TABLE bom_items
    ADD COLUMN IF NOT EXISTS price_unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS quantity_to_price_factor NUMERIC(24, 12);

UPDATE bom_items
SET price_unit = unit
WHERE price_unit IS NULL AND unit_price IS NOT NULL;

UPDATE bom_items
SET quantity_to_price_factor = 1
WHERE quantity_to_price_factor IS NULL;

ALTER TABLE bom_items
    ALTER COLUMN quantity_to_price_factor SET NOT NULL,
    ALTER COLUMN quantity_to_price_factor SET DEFAULT 1;

ALTER TABLE bom_recipe_items
    ADD COLUMN IF NOT EXISTS price_unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS quantity_to_price_factor NUMERIC(24, 12);

UPDATE bom_recipe_items
SET price_unit = unit
WHERE price_unit IS NULL AND unit_price IS NOT NULL;

UPDATE bom_recipe_items
SET quantity_to_price_factor = 1
WHERE quantity_to_price_factor IS NULL;

ALTER TABLE bom_recipe_items
    ALTER COLUMN quantity_to_price_factor SET NOT NULL,
    ALTER COLUMN quantity_to_price_factor SET DEFAULT 1;
