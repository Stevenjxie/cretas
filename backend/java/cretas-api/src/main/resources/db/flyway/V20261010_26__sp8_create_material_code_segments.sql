-- SP8: 16位分段编码字典表
-- level: 1=类型(3位), 2=部位/品类(6位), 3=品名(10位)
-- segment_code 为累积编码: L1=3位(001), L2=6位(001001), L3=10位(0010010001)
CREATE TABLE IF NOT EXISTS material_code_segments (
    id              BIGSERIAL       PRIMARY KEY,
    factory_id      VARCHAR(50)     NOT NULL,
    level           SMALLINT        NOT NULL CHECK (level IN (1, 2, 3)),
    segment_code    VARCHAR(10)     NOT NULL,
    segment_label   VARCHAR(100)    NOT NULL,
    parent_code     VARCHAR(10),
    sort_order      INT             NOT NULL DEFAULT 0,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    CONSTRAINT uk_mcs_factory_segment UNIQUE (factory_id, segment_code),
    CONSTRAINT fk_mcs_factory FOREIGN KEY (factory_id) REFERENCES factories(id)
);

CREATE INDEX IF NOT EXISTS idx_mcs_factory_level ON material_code_segments (factory_id, level);
CREATE INDEX IF NOT EXISTS idx_mcs_parent        ON material_code_segments (factory_id, parent_code);

COMMENT ON TABLE  material_code_segments IS 'SP8: 物料16位分段编码字典 (类型/部位/品名三级)';
COMMENT ON COLUMN material_code_segments.segment_code IS '累积段编码: L1=3位(001), L2=6位(001001), L3=10位(0010010001)';
COMMENT ON COLUMN material_code_segments.level IS '层级: 1=类型(3位), 2=部位/品类(6位), 3=品名(10位)';

-- 种子数据: 六扇门通用类型 (F006)
INSERT INTO material_code_segments (factory_id, level, segment_code, segment_label, parent_code, sort_order)
SELECT f.id, 1, '001', '原料', NULL, 1 FROM factories f WHERE f.id = 'F006'
ON CONFLICT (factory_id, segment_code) DO NOTHING;

INSERT INTO material_code_segments (factory_id, level, segment_code, segment_label, parent_code, sort_order)
SELECT f.id, 1, '002', '包材', NULL, 2 FROM factories f WHERE f.id = 'F006'
ON CONFLICT (factory_id, segment_code) DO NOTHING;

INSERT INTO material_code_segments (factory_id, level, segment_code, segment_label, parent_code, sort_order)
SELECT f.id, 1, '003', '辅料', NULL, 3 FROM factories f WHERE f.id = 'F006'
ON CONFLICT (factory_id, segment_code) DO NOTHING;
