-- SP10 fallback: 成本差异配置表 (幂等 IF NOT EXISTS — 若SP3已建则无操作)
CREATE TABLE IF NOT EXISTS cost_variance_configs (
    id              VARCHAR(191)   NOT NULL PRIMARY KEY,
    factory_id      VARCHAR(191)   NOT NULL,
    product_type_id VARCHAR(191)   NULL,
    threshold_pct   NUMERIC(5, 2)  NOT NULL DEFAULT 10.00,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cvc_factory_product UNIQUE (factory_id, product_type_id)
);

COMMENT ON COLUMN cost_variance_configs.product_type_id IS 'NULL=全局默认';
COMMENT ON COLUMN cost_variance_configs.threshold_pct IS '超支百分比阈值 默认10%';
