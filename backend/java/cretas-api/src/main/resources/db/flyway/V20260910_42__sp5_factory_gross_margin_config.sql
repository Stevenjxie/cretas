-- SP5: 创建工厂毛利红线配置表
-- 每个工厂可配置全局默认目标毛利率，也可按产品类型细化覆盖
CREATE TABLE IF NOT EXISTS factory_gross_margin_configs (
    id                  VARCHAR(191) NOT NULL PRIMARY KEY,
    factory_id          VARCHAR(50) NOT NULL,
    product_type_id     VARCHAR(191) NULL,       -- NULL = 工厂全局默认
    target_gross_margin NUMERIC(6,4) NOT NULL,   -- 目标毛利率 (0-1, e.g. 0.30 = 30%)
    description         TEXT NULL,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_by          BIGINT NOT NULL,
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW(),
    deleted_at          TIMESTAMP NULL,
    UNIQUE (factory_id, product_type_id)         -- 每工厂每产品类型唯一配置
);

CREATE INDEX IF NOT EXISTS idx_fgmc_factory ON factory_gross_margin_configs (factory_id);
CREATE INDEX IF NOT EXISTS idx_fgmc_factory_product ON factory_gross_margin_configs (factory_id, product_type_id);

COMMENT ON TABLE  factory_gross_margin_configs                IS 'SP5 工厂毛利红线配置';
COMMENT ON COLUMN factory_gross_margin_configs.product_type_id IS 'NULL = 工厂全局默认毛利率';
COMMENT ON COLUMN factory_gross_margin_configs.target_gross_margin IS '目标毛利率 0-1 (e.g. 0.30 = 30%)';
