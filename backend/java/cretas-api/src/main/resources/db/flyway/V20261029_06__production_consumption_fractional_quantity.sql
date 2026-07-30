-- Preserve gram-level seasoning consumption when the stock master unit is kg.
-- Widening NUMERIC precision/scale is lossless for all existing 2-decimal rows.
-- material_consumptions 只由 Hibernate 实体建表, Flyway 目录里没有 CREATE TABLE。Spring Boot 先跑 Flyway
-- 后跑 ddl-auto, 全新库跑到这里时该表还不存在, 裸语句会让整个应用起不来。守卫同 V20261027_41。
DO $$
BEGIN
    IF to_regclass('public.material_consumptions') IS NULL THEN
        RAISE NOTICE 'V20261029_06 skipped material_consumptions: not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE material_consumptions
        ALTER COLUMN quantity TYPE NUMERIC(18, 6) USING quantity::NUMERIC(18, 6),
        ALTER COLUMN planned_quantity TYPE NUMERIC(18, 6) USING planned_quantity::NUMERIC(18, 6);
END $$;


ALTER TABLE material_batches
    ALTER COLUMN receipt_quantity TYPE NUMERIC(18, 6) USING receipt_quantity::NUMERIC(18, 6),
    ALTER COLUMN used_quantity TYPE NUMERIC(18, 6) USING used_quantity::NUMERIC(18, 6),
    ALTER COLUMN reserved_quantity TYPE NUMERIC(18, 6) USING reserved_quantity::NUMERIC(18, 6);
