-- SP3 Three-Price Cost Engine: ProductCostVarianceConfig table
-- Stores per-product (or factory-global) cost variance alarm thresholds.
-- Priority: product-specific row (factory_id + product_type_id NOT NULL)
--           > factory-global row  (factory_id + product_type_id IS NULL)
--           > system default 10%  (resolved in Java, no DB row needed)

CREATE TABLE product_cost_variance_configs (
    id                  BIGSERIAL       PRIMARY KEY,
    factory_id          VARCHAR(64)     NOT NULL,
    -- NULL means factory-wide default; NOT NULL means product-specific
    product_type_id     VARCHAR(64),
    -- variance threshold in percent (e.g. 10.00 means 10%). DECIMAL(6,2) supports up to 9999.99%
    variance_threshold  DECIMAL(6,2)    NOT NULL DEFAULT 10.00,
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    remark              VARCHAR(500),
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP       NULL,
    CONSTRAINT uk_variance_config_factory_product
        UNIQUE (factory_id, product_type_id)
);

CREATE INDEX idx_variance_config_factory ON product_cost_variance_configs(factory_id);
CREATE INDEX idx_variance_config_factory_product ON product_cost_variance_configs(factory_id, product_type_id);

-- Auto-update updated_at trigger (reuse existing function if already created)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'update_updated_at') THEN
        CREATE FUNCTION update_updated_at()
        RETURNS TRIGGER AS $func$
        BEGIN
            NEW.updated_at = NOW();
            RETURN NEW;
        END;
        $func$ LANGUAGE plpgsql;
    END IF;
END;
$$;

CREATE TRIGGER trg_variance_config_updated_at
    BEFORE UPDATE ON product_cost_variance_configs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
