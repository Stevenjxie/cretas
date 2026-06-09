-- SP9-M1: 研发预估人工成本(元/kg成品)
-- Quoted labor cost per kg of finished goods product (RD estimate).
-- @PriceSensitive — stripped from sales role responses.
-- null = not configured; front-end must show "-" not "0".
ALTER TABLE product_types
    ADD COLUMN IF NOT EXISTS quoted_labor_cost_per_kg NUMERIC(12, 4) NULL;

COMMENT ON COLUMN product_types.quoted_labor_cost_per_kg
    IS 'SP9-M1: 研发预估人工成本(元/kg成品). @PriceSensitive. null=未填';
