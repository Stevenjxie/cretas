-- Preserve gram-level seasoning consumption when the stock master unit is kg.
-- Widening NUMERIC precision/scale is lossless for all existing 2-decimal rows.
ALTER TABLE material_consumptions
    ALTER COLUMN quantity TYPE NUMERIC(18, 6) USING quantity::NUMERIC(18, 6),
    ALTER COLUMN planned_quantity TYPE NUMERIC(18, 6) USING planned_quantity::NUMERIC(18, 6);

ALTER TABLE material_batches
    ALTER COLUMN receipt_quantity TYPE NUMERIC(18, 6) USING receipt_quantity::NUMERIC(18, 6),
    ALTER COLUMN used_quantity TYPE NUMERIC(18, 6) USING used_quantity::NUMERIC(18, 6),
    ALTER COLUMN reserved_quantity TYPE NUMERIC(18, 6) USING reserved_quantity::NUMERIC(18, 6);
