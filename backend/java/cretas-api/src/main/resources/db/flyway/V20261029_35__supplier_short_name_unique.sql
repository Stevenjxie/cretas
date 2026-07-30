-- 客户走查确认：供应商简称必须在同一工厂内不区分大小写唯一。
-- 不自动改名或清空存量业务数据；存在冲突时明确列出冲突组并中止迁移，
-- 由业务负责人确认正确简称后再重跑，避免静默篡改客户数据。
DO $$
DECLARE
    duplicate_groups TEXT;
BEGIN
    IF to_regclass('public.suppliers') IS NULL THEN
        RETURN;
    END IF;

    SELECT string_agg(
               format('%s / %s (%s 条)', factory_id, normalized_short_name, duplicate_count),
               '; ' ORDER BY factory_id, normalized_short_name)
      INTO duplicate_groups
      FROM (
          SELECT factory_id,
                 lower(short_name) AS normalized_short_name,
                 count(*) AS duplicate_count
            FROM suppliers
           WHERE deleted_at IS NULL
             AND short_name IS NOT NULL
           GROUP BY factory_id, lower(short_name)
          HAVING count(*) > 1
      ) duplicates;

    IF duplicate_groups IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = 'unique_violation',
            MESSAGE = '无法启用供应商简称唯一约束：存在重复简称',
            DETAIL = duplicate_groups,
            HINT = '请先由业务负责人确认并修改重复供应商简称，再重新执行迁移';
    END IF;

    CREATE UNIQUE INDEX IF NOT EXISTS uq_suppliers_short_name
        ON suppliers(factory_id, lower(short_name))
        WHERE deleted_at IS NULL AND short_name IS NOT NULL;

    -- 唯一索引已覆盖 V34 的普通查询索引能力，避免维护两份相同索引。
    DROP INDEX IF EXISTS idx_suppliers_short_name;
END $$;
