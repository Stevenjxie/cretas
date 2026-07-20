ALTER TABLE unit_of_measurements
    ADD COLUMN IF NOT EXISTS usage_scopes_json jsonb,
    ADD COLUMN IF NOT EXISTS conversion_family varchar(50);

UPDATE unit_of_measurements
SET usage_scopes_json = CASE upper(coalesce(category, ''))
        WHEN 'MASS' THEN '["INVENTORY_QUANTITY","PURCHASE_QUANTITY","BOM_QUANTITY","SPECIFICATION"]'::jsonb
        WHEN 'WEIGHT' THEN '["INVENTORY_QUANTITY","PURCHASE_QUANTITY","BOM_QUANTITY","SPECIFICATION"]'::jsonb
        WHEN 'VOLUME' THEN '["INVENTORY_QUANTITY","PURCHASE_QUANTITY","BOM_QUANTITY","SPECIFICATION"]'::jsonb
        WHEN 'COUNT' THEN '["INVENTORY_QUANTITY","PURCHASE_QUANTITY","BOM_QUANTITY","SPECIFICATION"]'::jsonb
        WHEN 'PACKAGE' THEN '["INVENTORY_QUANTITY","PURCHASE_QUANTITY","BOM_QUANTITY","SPECIFICATION"]'::jsonb
        WHEN 'PACKAGING' THEN '["INVENTORY_QUANTITY","PURCHASE_QUANTITY","BOM_QUANTITY","SPECIFICATION"]'::jsonb
        WHEN 'LENGTH' THEN '["SPECIFICATION"]'::jsonb
        WHEN 'AREA' THEN '["SPECIFICATION"]'::jsonb
        WHEN 'TIME' THEN '["PROCESS_DURATION"]'::jsonb
        WHEN 'TEMPERATURE' THEN '["STORAGE_TEMPERATURE"]'::jsonb
        WHEN 'RATIO' THEN '["YIELD_RATE"]'::jsonb
        ELSE '[]'::jsonb
    END
WHERE usage_scopes_json IS NULL;

UPDATE unit_of_measurements
SET conversion_family = upper(coalesce(nullif(category, ''), unit_code))
WHERE conversion_family IS NULL;

CREATE INDEX IF NOT EXISTS idx_uom_usage_scopes_gin
    ON unit_of_measurements USING gin (usage_scopes_json);
