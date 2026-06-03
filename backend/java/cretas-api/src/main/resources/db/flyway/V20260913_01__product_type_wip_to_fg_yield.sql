-- 备货看板: 在产半成品折成品的下游出率系数 (null = 按 1.0 估算)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'product_types' AND column_name = 'wip_to_fg_yield'
    ) THEN
        ALTER TABLE product_types ADD COLUMN wip_to_fg_yield DECIMAL(5,4);
        COMMENT ON COLUMN product_types.wip_to_fg_yield IS '在产半成品折成品下游出率系数(备货看板WIP估算, null=按1.0)';
    END IF;
END $$;
