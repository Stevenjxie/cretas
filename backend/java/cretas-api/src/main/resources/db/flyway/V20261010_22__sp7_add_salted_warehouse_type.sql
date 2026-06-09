-- SP7: 添加盐化仓库类型
-- 蓝图 §2.5: 多仓体系 SALTED 盐化仓，独立出量记录
-- 注意: PostgreSQL ALTER TYPE ADD VALUE 不能在事务块内执行（Flyway 默认会包装在事务中）
-- 需要使用 outOfOrder 或配置，但标准做法是单独迁移脚本

-- 检查枚举值是否已存在，避免重复添加（幂等）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_enum
        WHERE enumlabel = 'SALTED'
          AND enumtypid = (
              SELECT oid FROM pg_type WHERE typname = 'factory_warehouse_type'
          )
    ) THEN
        ALTER TYPE factory_warehouse_type ADD VALUE 'SALTED';
    END IF;
EXCEPTION
    WHEN undefined_object THEN
        -- enum type doesn't exist as a PG native type (stored as VARCHAR in JPA @Enumerated(STRING))
        -- No DDL needed; JPA will accept the new string value transparently
        NULL;
END
$$;
