-- SP3 Three-Price Cost Engine: add labor_cost_per_kg to bom_labor_cost_configs
-- This per-kg rate is used as the standard labor cost basis when the BOM
-- does not have an explicit process-level unit price configured.
ALTER TABLE bom_labor_cost_configs
    ADD COLUMN IF NOT EXISTS labor_cost_per_kg DECIMAL(15,4) NULL;

COMMENT ON COLUMN bom_labor_cost_configs.labor_cost_per_kg
    IS 'SP3: Standard labor cost per kg for BOM standard cost calculations. NULL = not configured (honest gap).';
