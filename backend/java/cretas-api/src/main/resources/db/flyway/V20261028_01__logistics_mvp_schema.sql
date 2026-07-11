-- 一加物流排线 MVP 后端闭环 — 领域 schema (logistics bounded context)
-- Spec: docs/superpowers/specs/2026-07-11-logistics-mvp-backend-closed-loop-spec.md
-- Handoff: docs/dispatch/2026-07-11-logistics-mvp-closed-loop-handoff.md §7
--
-- 版本说明: 真实 Flyway location = classpath:db/flyway, out-of-order=false,
-- 当前最高版本 V20261027_54 → 本迁移用 V20261028_01 (严格高于最高版本, 且与
-- V20261027_NN sentinel 序列分离, 降低并发 session 撞号概率).
--
-- 约定 (spec §2): 所有表带 created_at/updated_at/deleted_at 审计列 + version 乐观锁;
-- 所有 status/enum 列 CHECK 含全部枚举值 (加值时 additive DROP+ADD 含全值);
-- id/factory_id VARCHAR (对齐现有 Vehicle.id String); snake_case 列名.

-- 共享 updated_at 触发器函数 (若已存在则 REPLACE, 幂等)
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- 1. logistics_order_batches — 每日订单导入批次
-- ============================================================
CREATE TABLE IF NOT EXISTS logistics_order_batches (
    id                VARCHAR(36)  PRIMARY KEY DEFAULT gen_random_uuid()::text,
    factory_id        VARCHAR(64)  NOT NULL,
    business_date     DATE         NOT NULL,
    batch_number      VARCHAR(64)  NOT NULL,
    source_filename   VARCHAR(512),
    source_fingerprint VARCHAR(128) NOT NULL,
    status            VARCHAR(32)  NOT NULL DEFAULT 'PREVIEWED'
        CONSTRAINT ck_lob_status CHECK (status IN ('PREVIEWED','COMMITTED','PLANNED','CANCELLED')),
    total_rows        INTEGER      NOT NULL DEFAULT 0,
    valid_rows        INTEGER      NOT NULL DEFAULT 0,
    error_rows        INTEGER      NOT NULL DEFAULT 0,
    created_by        VARCHAR(64),
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMP
);
-- 幂等键: 同厂+同业务日+同文件指纹 只允许一批 (重复上传返回既有批次, 不静默双写)
CREATE UNIQUE INDEX IF NOT EXISTS uq_lob_idempotent
    ON logistics_order_batches (factory_id, business_date, source_fingerprint)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_lob_factory_date ON logistics_order_batches (factory_id, business_date);
CREATE TRIGGER trg_lob_updated_at BEFORE UPDATE ON logistics_order_batches
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 2. logistics_delivery_orders — 门店配送订单 (一家门店一行)
-- ============================================================
CREATE TABLE IF NOT EXISTS logistics_delivery_orders (
    id                    VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    factory_id            VARCHAR(64) NOT NULL,
    batch_id              VARCHAR(36) NOT NULL REFERENCES logistics_order_batches(id),
    store_code            VARCHAR(64) NOT NULL,
    store_name            VARCHAR(256) NOT NULL,
    address               VARCHAR(512),
    area_code             VARCHAR(64),
    pieces                INTEGER     NOT NULL DEFAULT 0 CONSTRAINT ck_ldo_pieces CHECK (pieces >= 0),
    boxes                 INTEGER     NOT NULL DEFAULT 0 CONSTRAINT ck_ldo_boxes CHECK (boxes >= 0),
    weight_kg             NUMERIC(12,3) NOT NULL DEFAULT 0 CONSTRAINT ck_ldo_weight CHECK (weight_kg >= 0),
    volume_cbm            NUMERIC(12,3) NOT NULL DEFAULT 0 CONSTRAINT ck_ldo_volume CHECK (volume_cbm >= 0),
    delivery_window_start VARCHAR(8),
    delivery_window_end   VARCHAR(8),
    longitude             NUMERIC(11,7),
    latitude              NUMERIC(10,7),
    location_status       VARCHAR(24) NOT NULL DEFAULT 'UNRESOLVED'
        CONSTRAINT ck_ldo_loc_status CHECK (location_status IN ('RESOLVED','UNRESOLVED','OUT_OF_BOUNDS')),
    status                VARCHAR(24) NOT NULL DEFAULT 'IMPORTED'
        CONSTRAINT ck_ldo_status CHECK (status IN ('IMPORTED','PLANNED','CONFIRMED','CANCELLED')),
    source_row_number     INTEGER,
    version               BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ldo_batch ON logistics_delivery_orders (batch_id);
CREATE INDEX IF NOT EXISTS idx_ldo_factory ON logistics_delivery_orders (factory_id);
-- 同批内门店编码唯一 (导入去重)
CREATE UNIQUE INDEX IF NOT EXISTS uq_ldo_batch_store
    ON logistics_delivery_orders (batch_id, store_code)
    WHERE deleted_at IS NULL;
CREATE TRIGGER trg_ldo_updated_at BEFORE UPDATE ON logistics_delivery_orders
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 3. logistics_vehicle_profiles — 物流车属性 (与现有 vehicles.id 1:1, 不改 Vehicle.capacity kg 语义)
-- ============================================================
CREATE TABLE IF NOT EXISTS logistics_vehicle_profiles (
    id               VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    vehicle_id       VARCHAR(36) NOT NULL,
    factory_id       VARCHAR(64) NOT NULL,
    capacity_cbm     NUMERIC(12,3) NOT NULL DEFAULT 0 CONSTRAINT ck_lvp_cap CHECK (capacity_cbm >= 0),
    max_weight_kg    NUMERIC(12,3) NOT NULL DEFAULT 0 CONSTRAINT ck_lvp_weight CHECK (max_weight_kg >= 0),
    source           VARCHAR(16) NOT NULL DEFAULT 'OWNED'
        CONSTRAINT ck_lvp_source CHECK (source IN ('OWNED','OUTSOURCED')),
    body_type        VARCHAR(64),
    temperature_mode VARCHAR(24) NOT NULL DEFAULT 'DUAL_TEMP'
        CONSTRAINT ck_lvp_temp CHECK (temperature_mode IN ('DUAL_TEMP','FROZEN','AMBIENT')),
    service_areas    VARCHAR(512),
    available_from   VARCHAR(8),
    available_to     VARCHAR(8),
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted_at       TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_lvp_vehicle
    ON logistics_vehicle_profiles (vehicle_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_lvp_factory ON logistics_vehicle_profiles (factory_id);
CREATE TRIGGER trg_lvp_updated_at BEFORE UPDATE ON logistics_vehicle_profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 4. logistics_drivers — 独立司机资料
-- ============================================================
CREATE TABLE IF NOT EXISTS logistics_drivers (
    id              VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    factory_id      VARCHAR(64) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    phone           VARCHAR(32),
    employment_type VARCHAR(16) NOT NULL DEFAULT 'OWNED'
        CONSTRAINT ck_ld_emp CHECK (employment_type IN ('OWNED','OUTSOURCED')),
    service_areas   VARCHAR(512),
    available_from  VARCHAR(8),
    available_to    VARCHAR(8),
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ld_factory ON logistics_drivers (factory_id);
CREATE TRIGGER trg_ld_updated_at BEFORE UPDATE ON logistics_drivers
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 5. logistics_vehicle_drivers — 车辆-司机绑定 (一车 2-3 司机, 白班/晚班)
-- ============================================================
CREATE TABLE IF NOT EXISTS logistics_vehicle_drivers (
    id            VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    factory_id    VARCHAR(64) NOT NULL,
    vehicle_id    VARCHAR(36) NOT NULL,
    driver_id     VARCHAR(36) NOT NULL REFERENCES logistics_drivers(id),
    role          VARCHAR(16) NOT NULL DEFAULT 'PRIMARY'
        CONSTRAINT ck_lvd_role CHECK (role IN ('PRIMARY','BACKUP')),
    shift_start   VARCHAR(8),
    shift_end     VARCHAR(8),
    priority      INTEGER     NOT NULL DEFAULT 0,
    effective_from DATE,
    effective_to   DATE,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lvd_vehicle ON logistics_vehicle_drivers (vehicle_id);
CREATE INDEX IF NOT EXISTS idx_lvd_driver ON logistics_vehicle_drivers (driver_id);
-- 同车同司机同班次同角色不重复
CREATE UNIQUE INDEX IF NOT EXISTS uq_lvd_binding
    ON logistics_vehicle_drivers (vehicle_id, driver_id, role, shift_start)
    WHERE deleted_at IS NULL;
CREATE TRIGGER trg_lvd_updated_at BEFORE UPDATE ON logistics_vehicle_drivers
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 6. logistics_plans — 排线计划 (一批订单 → 一计划)
-- ============================================================
CREATE TABLE IF NOT EXISTS logistics_plans (
    id                VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    factory_id        VARCHAR(64) NOT NULL,
    order_batch_id    VARCHAR(36) NOT NULL REFERENCES logistics_order_batches(id),
    plan_date         DATE        NOT NULL,
    plan_number       VARCHAR(64) NOT NULL,
    target_load_pct   NUMERIC(5,2) NOT NULL DEFAULT 88
        CONSTRAINT ck_lp_target CHECK (target_load_pct > 0 AND target_load_pct <= 100),
    status            VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT ck_lp_status CHECK (status IN ('DRAFT','NEEDS_ACTION','CONFIRMED','EXPORTED','CANCELLED')),
    distance_source   VARCHAR(24) NOT NULL DEFAULT 'MAINTAINED_MATRIX'
        CONSTRAINT ck_lp_dist_src CHECK (distance_source IN ('MAINTAINED_MATRIX','MAP_PROVIDER','DEMO_FIXTURE')),
    total_stores      INTEGER     NOT NULL DEFAULT 0,
    total_trips       INTEGER     NOT NULL DEFAULT 0,
    total_distance_km NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_by        VARCHAR(64),
    confirmed_by      VARCHAR(64),
    confirmed_at      TIMESTAMP,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lp_factory_date ON logistics_plans (factory_id, plan_date);
CREATE INDEX IF NOT EXISTS idx_lp_batch ON logistics_plans (order_batch_id);
CREATE TRIGGER trg_lp_updated_at BEFORE UPDATE ON logistics_plans
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 7. logistics_trips — 车次 (一计划多车次)
-- ============================================================
CREATE TABLE IF NOT EXISTS logistics_trips (
    id                VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    factory_id        VARCHAR(64) NOT NULL,
    plan_id           VARCHAR(36) NOT NULL REFERENCES logistics_plans(id),
    trip_no           INTEGER     NOT NULL,
    vehicle_id        VARCHAR(36),
    driver_id         VARCHAR(36),
    status            VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT ck_lt_status CHECK (status IN ('DRAFT','NEEDS_VEHICLE','NEEDS_DRIVER','NEEDS_ROUTE_DATA','CONFIRMED')),
    total_volume_cbm  NUMERIC(12,3) NOT NULL DEFAULT 0,
    total_weight_kg   NUMERIC(12,3) NOT NULL DEFAULT 0,
    load_rate         NUMERIC(6,4) NOT NULL DEFAULT 0,
    weight_load_rate  NUMERIC(6,4) NOT NULL DEFAULT 0,
    total_distance_km NUMERIC(12,2) NOT NULL DEFAULT 0,
    geometry          JSONB,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lt_plan ON logistics_trips (plan_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_lt_plan_tripno
    ON logistics_trips (plan_id, trip_no) WHERE deleted_at IS NULL;
CREATE TRIGGER trg_lt_updated_at BEFORE UPDATE ON logistics_trips
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 8. logistics_stops — 停靠点 (车次内门店顺序; 一订单只属一条有效车次)
-- ============================================================
CREATE TABLE IF NOT EXISTS logistics_stops (
    id                   VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    factory_id           VARCHAR(64) NOT NULL,
    trip_id              VARCHAR(36) NOT NULL REFERENCES logistics_trips(id),
    delivery_order_id    VARCHAR(36) NOT NULL REFERENCES logistics_delivery_orders(id),
    sequence_no          INTEGER     NOT NULL,
    leg_distance_km      NUMERIC(12,2) NOT NULL DEFAULT 0,
    arrival_window_start VARCHAR(8),
    arrival_window_end   VARCHAR(8),
    geometry             JSONB,
    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ls_trip ON logistics_stops (trip_id);
-- 车次内顺序唯一
CREATE UNIQUE INDEX IF NOT EXISTS uq_ls_trip_seq
    ON logistics_stops (trip_id, sequence_no) WHERE deleted_at IS NULL;
-- 一个订单只能属于一条有效车次 (防重复分配/漏排的 DB 保障, 配合 service 校验)
CREATE UNIQUE INDEX IF NOT EXISTS uq_ls_order_single_trip
    ON logistics_stops (delivery_order_id) WHERE deleted_at IS NULL;
CREATE TRIGGER trg_ls_updated_at BEFORE UPDATE ON logistics_stops
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 9. logistics_distance_edges — 距离边 (公里数不伪造; 缺边 → NEEDS_ROUTE_DATA)
-- ============================================================
CREATE TABLE IF NOT EXISTS logistics_distance_edges (
    id            VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    factory_id    VARCHAR(64) NOT NULL,
    from_point_id VARCHAR(64) NOT NULL,
    to_point_id   VARCHAR(64) NOT NULL,
    distance_km   NUMERIC(12,2) NOT NULL CONSTRAINT ck_lde_dist CHECK (distance_km >= 0),
    geometry      JSONB,
    source        VARCHAR(24) NOT NULL DEFAULT 'DEMO_FIXTURE'
        CONSTRAINT ck_lde_source CHECK (source IN ('CUSTOMER_MAINTAINED','MAP_PROVIDER','DEMO_FIXTURE')),
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMP
);
-- 同厂内有向边唯一 (DEPOT->store, store->store)
CREATE UNIQUE INDEX IF NOT EXISTS uq_lde_edge
    ON logistics_distance_edges (factory_id, from_point_id, to_point_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_lde_factory ON logistics_distance_edges (factory_id);
CREATE TRIGGER trg_lde_updated_at BEFORE UPDATE ON logistics_distance_edges
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
