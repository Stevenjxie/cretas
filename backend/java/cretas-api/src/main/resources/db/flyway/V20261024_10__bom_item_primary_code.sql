-- F006 strict 16-code: persist BOM item primary code for grouping and validation.
ALTER TABLE bom_recipe_items
    ADD COLUMN IF NOT EXISTS primary_code VARCHAR(3);

COMMENT ON COLUMN bom_recipe_items.primary_code IS
    'SP8: BOM item material primary code copied from raw_material_types.primary_code; nullable for historical rows';

CREATE INDEX IF NOT EXISTS idx_bri_primary_code_v2
    ON bom_recipe_items (factory_id, primary_code)
    WHERE primary_code IS NOT NULL;

-- F006 sample segment dictionary entries. Full taxonomy remains an admin data-entry task.
INSERT INTO material_code_segments (factory_id, level, segment_code, segment_label, parent_code, sort_order)
SELECT f.id, v.level, v.segment_code, v.segment_label, v.parent_code, v.sort_order
FROM factories f
CROSS JOIN (
    VALUES
        (2::SMALLINT, '001001', U&'\725B\8089\90E8\4F4D', '001', 101),
        (2::SMALLINT, '001002', U&'\732A\8089\90E8\4F4D', '001', 102),
        (2::SMALLINT, '001003', U&'\79BD\7C7B\90E8\4F4D', '001', 103),
        (3::SMALLINT, '0010010001', U&'\725B\8171', '001001', 1001),
        (3::SMALLINT, '0010010002', U&'\725B\8138', '001001', 1002),
        (3::SMALLINT, '0010020001', U&'\732A\820C', '001002', 2001),
        (3::SMALLINT, '0010030001', U&'\638C\4E2D\5B9D', '001003', 3001)
) AS v(level, segment_code, segment_label, parent_code, sort_order)
WHERE f.id = 'F006'
ON CONFLICT (factory_id, segment_code) DO NOTHING;
