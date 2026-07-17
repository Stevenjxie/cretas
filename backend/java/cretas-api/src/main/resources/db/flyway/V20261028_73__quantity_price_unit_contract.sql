ALTER TABLE purchase_order_items
    ADD COLUMN IF NOT EXISTS price_unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS quantity_to_price_factor NUMERIC(24, 12);

UPDATE purchase_order_items
SET price_unit = unit
WHERE price_unit IS NULL;

UPDATE purchase_order_items
SET quantity_to_price_factor = 1
WHERE quantity_to_price_factor IS NULL;

ALTER TABLE purchase_order_items
    ALTER COLUMN price_unit SET NOT NULL,
    ALTER COLUMN quantity_to_price_factor SET NOT NULL,
    ALTER COLUMN quantity_to_price_factor SET DEFAULT 1;

ALTER TABLE purchase_receive_items
    ADD COLUMN IF NOT EXISTS price_unit VARCHAR(20);

UPDATE purchase_receive_items
SET price_unit = unit
WHERE price_unit IS NULL;

ALTER TABLE purchase_receive_items
    ALTER COLUMN price_unit SET NOT NULL;

ALTER TABLE bom_items
    ADD COLUMN IF NOT EXISTS price_unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS quantity_to_price_factor NUMERIC(24, 12);

UPDATE bom_items
SET price_unit = unit
WHERE price_unit IS NULL AND unit_price IS NOT NULL;

UPDATE bom_items
SET quantity_to_price_factor = 1
WHERE quantity_to_price_factor IS NULL;

ALTER TABLE bom_items
    ALTER COLUMN quantity_to_price_factor SET NOT NULL,
    ALTER COLUMN quantity_to_price_factor SET DEFAULT 1;

ALTER TABLE bom_recipe_items
    ADD COLUMN IF NOT EXISTS price_unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS quantity_to_price_factor NUMERIC(24, 12);

UPDATE bom_recipe_items
SET price_unit = unit
WHERE price_unit IS NULL AND unit_price IS NOT NULL;

UPDATE bom_recipe_items
SET quantity_to_price_factor = 1
WHERE quantity_to_price_factor IS NULL;

ALTER TABLE bom_recipe_items
    ALTER COLUMN quantity_to_price_factor SET NOT NULL,
    ALTER COLUMN quantity_to_price_factor SET DEFAULT 1;
