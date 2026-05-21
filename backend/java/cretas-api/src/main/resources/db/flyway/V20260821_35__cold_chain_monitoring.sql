-- V20260821_35__cold_chain_monitoring.sql
--
-- Sprint 9 P2.D 食品行业 P0 — 冷链温控 + 偏离报警 (2026-05-21)
-- 法定: GB 14881 / 食品安全法 — 部分品类 (卤味 / 乳制品 / 冷冻熟食) 强制冷链监控
--
-- 业务场景:
--   - 冷链设备 (冰柜/冷藏车/陈列柜) 温度连续监控 (典型每 5 min 1 个数据点)
--   - 设定温控阈值 (e.g. 冷藏 <= 8 ℃, 冷冻 <= -18 ℃)
--   - 偏离持续超 toleranceMinutes → 即时报警 + audit log
--   - 24h 温度曲线 + 月报 (per GB 14881)
--
-- HJ 0 冷链温控 → Cretas 食品垂直 V2 差异化护城河 +1.
--
-- Pattern mirrors V20260821_30 (food_samples). BaseEntity audit fields
-- (created_at / updated_at / deleted_at) per .claude/rules/database-entity-sync.md HARD.

-- ===== 1. cold_chain_equipment 冷链设备表 =====

CREATE TABLE IF NOT EXISTS cold_chain_equipment (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    equipment_code VARCHAR(50) NOT NULL,
    equipment_name VARCHAR(200) NOT NULL,
    type VARCHAR(30) NOT NULL
        CHECK (type IN ('FREEZER','REFRIGERATOR','COLD_CHAIN_VEHICLE',
                        'COLD_DISPLAY','COLD_STORAGE')),
    target_temp_min DECIMAL(5,2) NOT NULL,
    target_temp_max DECIMAL(5,2) NOT NULL,
    unit VARCHAR(10) NOT NULL DEFAULT '℃',
    tolerance_minutes INTEGER NOT NULL DEFAULT 15,
    location VARCHAR(200),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT chk_cold_chain_temp_range CHECK (target_temp_min < target_temp_max),
    CONSTRAINT chk_cold_chain_tolerance_positive CHECK (tolerance_minutes > 0),
    CONSTRAINT uk_cold_chain_equipment_factory_code
        UNIQUE (factory_id, equipment_code)
);

CREATE INDEX IF NOT EXISTS idx_cold_chain_equipment_factory
    ON cold_chain_equipment(factory_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_cold_chain_equipment_factory_active
    ON cold_chain_equipment(factory_id, active)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE cold_chain_equipment IS '冷链温控设备 — Sprint 9 P2.D / GB 14881 / 冷藏 8℃ 冷冻 -18℃';
COMMENT ON COLUMN cold_chain_equipment.type IS 'FREEZER 冰柜 / REFRIGERATOR 冷藏柜 / COLD_CHAIN_VEHICLE 冷链车 / COLD_DISPLAY 陈列冷柜 / COLD_STORAGE 冷库';
COMMENT ON COLUMN cold_chain_equipment.tolerance_minutes IS '偏离持续时长阈值 min - 超过即触发 ColdChainAlert. 默认 15 min - 短期电压波动 / 开门不报警';

-- ===== 2. cold_chain_temp_readings 高频时序读数表 =====

CREATE TABLE IF NOT EXISTS cold_chain_temp_readings (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    equipment_id BIGINT NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    temperature DECIMAL(5,2) NOT NULL,
    is_deviation BOOLEAN NOT NULL DEFAULT FALSE,
    alert_sent BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cold_chain_readings_eq_time
    ON cold_chain_temp_readings(equipment_id, recorded_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_cold_chain_readings_factory_time
    ON cold_chain_temp_readings(factory_id, recorded_at)
    WHERE deleted_at IS NULL;

-- partial index for deviation polling — 偏离未发报的快速 scan
CREATE INDEX IF NOT EXISTS idx_cold_chain_readings_deviation
    ON cold_chain_temp_readings(equipment_id, is_deviation, alert_sent)
    WHERE is_deviation = TRUE AND alert_sent = FALSE AND deleted_at IS NULL;

COMMENT ON TABLE cold_chain_temp_readings IS '冷链温度读数 - 高频时序数据 (典型 5 min 1 点) - Sprint 9 P2.D';
COMMENT ON COLUMN cold_chain_temp_readings.is_deviation IS '是否偏离目标区间 - 写入时由 Tool / Scheduler 自动 mark';
COMMENT ON COLUMN cold_chain_temp_readings.alert_sent IS '是否已触发 ColdChainAlert - 避免重复';

-- ===== 3. cold_chain_alerts 偏离报警事件表 =====

CREATE TABLE IF NOT EXISTS cold_chain_alerts (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    equipment_id BIGINT NOT NULL,
    alert_time TIMESTAMP NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'WARNING'
        CHECK (severity IN ('WARNING','CRITICAL')),
    min_temp DECIMAL(5,2) NOT NULL,
    max_temp DECIMAL(5,2) NOT NULL,
    duration_minutes INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
    acknowledged_at TIMESTAMP,
    acknowledged_by_user_id BIGINT,
    ack_reason VARCHAR(50),
    ack_remark TEXT,
    resolved_at TIMESTAMP,
    resolved_by_user_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT chk_cold_chain_alert_duration_positive
        CHECK (duration_minutes > 0)
);

CREATE INDEX IF NOT EXISTS idx_cold_chain_alerts_factory_status
    ON cold_chain_alerts(factory_id, status, alert_time)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_cold_chain_alerts_equipment
    ON cold_chain_alerts(equipment_id, alert_time)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_cold_chain_alerts_severity
    ON cold_chain_alerts(factory_id, severity, status)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE cold_chain_alerts IS '冷链温度偏离报警事件 - Sprint 9 P2.D';
COMMENT ON COLUMN cold_chain_alerts.severity IS 'WARNING (< 30 min) / CRITICAL (>= 30 min)';
COMMENT ON COLUMN cold_chain_alerts.status IS 'OPEN (新报警) / ACKNOWLEDGED (已确认) / RESOLVED (已解决)';
COMMENT ON COLUMN cold_chain_alerts.ack_reason IS 'R3 防呆 enum: TEMPORARY_DOOR_OPEN / POWER_FLUCTUATION / MAINTENANCE / SENSOR_FAULT / SPOILED / OTHER';
