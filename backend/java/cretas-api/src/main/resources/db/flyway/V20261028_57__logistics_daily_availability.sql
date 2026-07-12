-- 一加物流排线 — 按日可用性覆盖 (司机/车辆 当天请假/维修 或 临时班次调整)
-- 无记录的资源当天按 logistics_drivers / logistics_vehicle_profiles (V20261028_01) 的固定
-- 资料默认可用 — 引入本表前既有排线行为完全不变 (诚实默认, additive)。
--
-- 约定 (per task brief): additive, nullable, 不加 CHECK 约束 (PG CHECK runtime-only, 项目已有
-- 2 次相关事故教训 — 见 .claude/rules/database-entity-sync.md)。updated_at 触发器函数
-- update_updated_at() 已由 V20261028_01 建好, 本迁移直接复用不重复定义。

CREATE TABLE IF NOT EXISTS logistics_daily_availability (
    id            VARCHAR(36)  PRIMARY KEY DEFAULT gen_random_uuid()::text,
    factory_id    VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(16)  NOT NULL,
    resource_id   VARCHAR(36)  NOT NULL,
    avail_date    DATE         NOT NULL,
    available     BOOLEAN      NOT NULL DEFAULT TRUE,
    shift_start   VARCHAR(8),
    shift_end     VARCHAR(8),
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMP
);
-- 同厂+同资源类型+同资源+同日期 只允许一条覆盖记录 (upsert 查重键)
CREATE UNIQUE INDEX IF NOT EXISTS uq_lda_resource_date
    ON logistics_daily_availability (factory_id, resource_type, resource_id, avail_date)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_lda_factory_date ON logistics_daily_availability (factory_id, avail_date);
CREATE TRIGGER trg_lda_updated_at BEFORE UPDATE ON logistics_daily_availability
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
