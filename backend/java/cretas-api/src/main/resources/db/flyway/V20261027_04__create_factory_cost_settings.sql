-- SP-C: 工厂成本参数表 (工时单价等), 替 SP-B1 ¥26 硬编码。Spec §5.3.
CREATE TABLE IF NOT EXISTS factory_cost_settings (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    labor_hourly_rate NUMERIC(12,2),       -- ¥/工时; null=未配置
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT uq_factory_cost_settings_factory UNIQUE (factory_id)
);
