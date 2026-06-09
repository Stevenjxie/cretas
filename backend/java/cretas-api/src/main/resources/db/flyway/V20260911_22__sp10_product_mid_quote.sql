-- SP10: 新建 product_mid_quotes 表 — 中报价实体
CREATE TABLE IF NOT EXISTS product_mid_quotes (
    id                   VARCHAR(191)   NOT NULL PRIMARY KEY,
    factory_id           VARCHAR(191)   NOT NULL,
    sample_id            VARCHAR(191)   NOT NULL,
    quotation_task_id    VARCHAR(191)   NULL,
    trial_batch_id       VARCHAR(191)   NOT NULL,

    -- 中试实际数据
    trial_quantity_kg    NUMERIC(12, 4) NOT NULL,
    trial_output_kg      NUMERIC(12, 4) NULL,
    actual_yield_rate    NUMERIC(6, 4)  NULL,

    -- 成本汇算 (SP3依赖: 移动均价+报工人工)
    material_cost_per_kg NUMERIC(12, 4) NULL,
    labor_cost_per_kg    NUMERIC(12, 4) NULL,
    overhead_cost_per_kg NUMERIC(12, 4) NULL,
    total_cost_per_kg    NUMERIC(12, 4) NULL,

    -- 与预报价对比
    pre_quote_labor_per_kg  NUMERIC(12, 4) NULL,
    pre_quote_total_per_kg  NUMERIC(12, 4) NULL,
    cost_variance_pct       NUMERIC(6, 2)  NULL,
    variance_alert          BOOLEAN        NOT NULL DEFAULT FALSE,

    -- 状态
    status        VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    calculated_at TIMESTAMP    NULL,
    confirmed_by  BIGINT       NULL,
    confirmed_at  TIMESTAMP    NULL,
    remark        TEXT         NULL,

    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMP    NULL,

    CONSTRAINT uq_mid_quote_batch UNIQUE (factory_id, trial_batch_id)
);

COMMENT ON COLUMN product_mid_quotes.sample_id IS 'FK → product_samples.id';
COMMENT ON COLUMN product_mid_quotes.quotation_task_id IS 'FK → quotation_tasks.id';
COMMENT ON COLUMN product_mid_quotes.trial_batch_id IS 'FK → production_batches.id (is_trial=true)';
COMMENT ON COLUMN product_mid_quotes.material_cost_per_kg IS '材料成本 元/kg成品 (移动均价自动汇算, SP3)';
COMMENT ON COLUMN product_mid_quotes.labor_cost_per_kg IS '人工成本 元/kg成品 (报工段 人数×工时×工价 / 产出kg)';
COMMENT ON COLUMN product_mid_quotes.overhead_cost_per_kg IS '制造费用 元/kg成品 (按SP3配置摊)';
COMMENT ON COLUMN product_mid_quotes.total_cost_per_kg IS '中试成本价 元/kg成品 (三项合计)';
COMMENT ON COLUMN product_mid_quotes.cost_variance_pct IS '超支百分比=(中试成本-预报价)/预报价×100';
COMMENT ON COLUMN product_mid_quotes.status IS 'DRAFT|CALCULATED|CONFIRMED';

CREATE INDEX IF NOT EXISTS idx_pmq_factory_sample ON product_mid_quotes (factory_id, sample_id);
CREATE INDEX IF NOT EXISTS idx_pmq_quotation_task ON product_mid_quotes (quotation_task_id);
