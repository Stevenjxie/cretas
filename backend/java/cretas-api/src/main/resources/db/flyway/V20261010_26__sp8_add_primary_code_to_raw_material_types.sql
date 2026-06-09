-- SP8: raw_material_types 加前三位主编码冗余列
-- 供 BOM 关联和快速搜索; 历史数据可空
ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS primary_code VARCHAR(3);

COMMENT ON COLUMN raw_material_types.primary_code IS
    'SP8: 16位编码前3位(类型段), 如 001=原料/002=包材/003=辅料; 历史数据可空';

CREATE INDEX IF NOT EXISTS idx_rmt_primary_code
    ON raw_material_types (factory_id, primary_code)
    WHERE primary_code IS NOT NULL;
