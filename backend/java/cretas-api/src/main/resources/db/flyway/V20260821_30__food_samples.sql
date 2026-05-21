-- V20260821_30__food_samples.sql
--
-- Sprint 9 P2.A 食品行业法定 P0 — 留样追踪 (2026-05-21)
-- 法定: GB 31654-2021 餐饮服务从业人员食品安全管理规范
--
-- 留样要求:
--   - 餐饮供应链每批产品出货时, 留样 >= 125g (sample_quantity)
--   - 留样 48 小时冷藏保存 (storage_temperature_max <= 8.0 ℃)
--   - 超时未消化 → 标准化销毁 + audit log
--   - 客户投诉时回溯 → 留样可用作检测
--
-- HJ 0 留样能力 → Cretas 食品垂直 V2 差异化护城河 +1.
--
-- Pattern mirrors V20260820_03 (haccp_checkpoints). BaseEntity audit fields
-- (created_at / updated_at / deleted_at) per .claude/rules/database-entity-sync.md HARD.

-- ===== food_samples 留样表 =====

CREATE TABLE IF NOT EXISTS food_samples (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    batch_number VARCHAR(100) NOT NULL,
    material_batch_id VARCHAR(191) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    sample_quantity DECIMAL(10,2) NOT NULL,
    unit VARCHAR(10) NOT NULL DEFAULT 'g',
    sample_time TIMESTAMP NOT NULL,
    expire_time TIMESTAMP NOT NULL,
    storage_temperature_max DECIMAL(5,2) NOT NULL DEFAULT 8.0,
    storage_location VARCHAR(100) NOT NULL,
    retained_by_user_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'RETAINED'
        CHECK (status IN ('RETAINED','DISPOSED','USED_FOR_INSPECTION')),
    disposed_at TIMESTAMP,
    disposed_by_user_id BIGINT,
    dispose_reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_food_samples_factory_batch
    ON food_samples(factory_id, batch_number)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_food_samples_expire
    ON food_samples(factory_id, status, expire_time)
    WHERE status = 'RETAINED' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_food_samples_status
    ON food_samples(factory_id, status)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE food_samples IS '食品留样表 — Sprint 9 P2.A / GB 31654-2021 / 48h 冷藏 >= 125g';
COMMENT ON COLUMN food_samples.expire_time IS 'sample_time + 48h per GB 31654-2021';
COMMENT ON COLUMN food_samples.storage_temperature_max IS '冷藏温度上限 (℃, 法规 <= 8.0)';
COMMENT ON COLUMN food_samples.status IS 'RETAINED (留样中) / DISPOSED (已销毁) / USED_FOR_INSPECTION (用于检测)';
