-- Restaurant reservation -> demand forecast -> staffing suggestion closed loop.
--
-- All simulated rows are explicitly labelled.  They must never be presented as
-- a real booking-platform feed.  RLS follows the strict tenant policy used by
-- Silver restaurant facts: every application transaction sets app.factory_id.

BEGIN;

CREATE TABLE IF NOT EXISTS fact_restaurant_reservation (
    id                  BIGSERIAL PRIMARY KEY,
    factory_id          VARCHAR(50) NOT NULL,
    source              VARCHAR(80) NOT NULL,
    external_ref        VARCHAR(160) NOT NULL,
    store_id            BIGINT NOT NULL REFERENCES dim_store(store_id) ON DELETE RESTRICT,
    reservation_date    DATE NOT NULL,
    daypart             VARCHAR(20) NOT NULL,
    table_count         INT NOT NULL CHECK (table_count >= 0),
    guest_count         INT NOT NULL CHECK (guest_count >= 0),
    status              VARCHAR(20) NOT NULL CHECK (
        status IN ('PENDING', 'CONFIRMED', 'SEATED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')
    ),
    is_simulated        BOOLEAN NOT NULL DEFAULT FALSE,
    source_updated_at   TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_restaurant_reservation_source
        UNIQUE (factory_id, source, external_ref),
    CONSTRAINT ck_restaurant_reservation_daypart
        CHECK (daypart IN ('午市', '下午茶', '晚市', '夜宵'))
);

ALTER TABLE fact_restaurant_reservation ENABLE ROW LEVEL SECURITY;
ALTER TABLE fact_restaurant_reservation FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fact_restaurant_reservation;
CREATE POLICY tenant_isolation ON fact_restaurant_reservation FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

CREATE INDEX IF NOT EXISTS idx_restaurant_reservation_window
    ON fact_restaurant_reservation(factory_id, reservation_date, store_id, daypart);
CREATE INDEX IF NOT EXISTS idx_restaurant_reservation_source_updated
    ON fact_restaurant_reservation(factory_id, source, source_updated_at DESC);

CREATE TABLE IF NOT EXISTS restaurant_staffing_policy (
    id                              BIGSERIAL PRIMARY KEY,
    factory_id                      VARCHAR(50) NOT NULL,
    store_id                        BIGINT NOT NULL REFERENCES dim_store(store_id) ON DELETE CASCADE,
    daypart                         VARCHAR(20) NOT NULL,
    role_code                       VARCHAR(40) NOT NULL,
    role_name                       VARCHAR(60) NOT NULL,
    required_skill                  VARCHAR(80) NOT NULL,
    shift_hours                     NUMERIC(5,2) NOT NULL CHECK (shift_hours > 0),
    target_guests_per_labor_hour    NUMERIC(8,2) NOT NULL CHECK (target_guests_per_labor_hour > 0),
    minimum_staff                   INT NOT NULL CHECK (minimum_staff >= 0),
    current_staff                   INT NOT NULL CHECK (current_staff >= 0),
    available_skilled_staff         INT NOT NULL CHECK (available_skilled_staff >= 0),
    max_hours_per_person_week       NUMERIC(6,2) NOT NULL CHECK (max_hours_per_person_week > 0),
    expected_reservation_share      NUMERIC(5,4) NOT NULL DEFAULT 0.35
        CHECK (expected_reservation_share > 0 AND expected_reservation_share <= 1),
    source                          VARCHAR(80) NOT NULL,
    is_simulated                    BOOLEAN NOT NULL DEFAULT FALSE,
    version                         INT NOT NULL DEFAULT 1,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_restaurant_staffing_policy
        UNIQUE(factory_id, store_id, daypart, role_code),
    CONSTRAINT ck_restaurant_staffing_policy_daypart
        CHECK (daypart IN ('午市', '下午茶', '晚市', '夜宵'))
);

ALTER TABLE restaurant_staffing_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_staffing_policy FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON restaurant_staffing_policy;
CREATE POLICY tenant_isolation ON restaurant_staffing_policy FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

CREATE INDEX IF NOT EXISTS idx_restaurant_staffing_policy_lookup
    ON restaurant_staffing_policy(factory_id, store_id, daypart);

CREATE TABLE IF NOT EXISTS restaurant_staffing_adjustment (
    id                  BIGSERIAL PRIMARY KEY,
    factory_id          VARCHAR(50) NOT NULL,
    store_id            BIGINT NOT NULL REFERENCES dim_store(store_id) ON DELETE RESTRICT,
    target_date         DATE NOT NULL,
    daypart             VARCHAR(20) NOT NULL,
    role_code           VARCHAR(40) NOT NULL,
    predicted_guests    INT NOT NULL CHECK (predicted_guests >= 0),
    policy_version      INT NOT NULL CHECK (policy_version > 0),
    prior_staff         INT NOT NULL CHECK (prior_staff >= 0),
    recommended_staff   INT NOT NULL CHECK (recommended_staff >= 0),
    adjusted_staff      INT NOT NULL CHECK (adjusted_staff >= 0),
    plan_fingerprint    VARCHAR(64) NOT NULL,
    reason              VARCHAR(500) NOT NULL,
    actor_user_id       VARCHAR(80) NOT NULL,
    actor_role          VARCHAR(80) NOT NULL,
    idempotency_key     VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_restaurant_staffing_adjustment_idempotency
        UNIQUE(factory_id, idempotency_key),
    CONSTRAINT ck_restaurant_staffing_adjustment_daypart
        CHECK (daypart IN ('午市', '下午茶', '晚市', '夜宵'))
);

ALTER TABLE restaurant_staffing_adjustment ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_staffing_adjustment FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON restaurant_staffing_adjustment;
CREATE POLICY tenant_isolation ON restaurant_staffing_adjustment FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

CREATE INDEX IF NOT EXISTS idx_restaurant_staffing_adjustment_shift
    ON restaurant_staffing_adjustment(factory_id, target_date, store_id, daypart);

CREATE TABLE IF NOT EXISTS restaurant_reservation_roll_audit (
    id              BIGSERIAL PRIMARY KEY,
    factory_id      VARCHAR(50) NOT NULL,
    run_date        DATE NOT NULL,
    window_start    DATE NOT NULL,
    window_end      DATE NOT NULL,
    inserted_rows   INT NOT NULL DEFAULT 0,
    updated_rows    INT NOT NULL DEFAULT 0,
    deleted_rows    INT NOT NULL DEFAULT 0,
    policy_rows     INT NOT NULL DEFAULT 0,
    source          VARCHAR(80) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_restaurant_reservation_roll_daily
        UNIQUE(factory_id, run_date, source)
);

ALTER TABLE restaurant_reservation_roll_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_reservation_roll_audit FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON restaurant_reservation_roll_audit;
CREATE POLICY tenant_isolation ON restaurant_reservation_roll_audit FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- Seed auditable simulated staffing policies only for the two explicitly
-- authorized restaurant tenants.  New stores are filled by the daily roller.
WITH dayparts(daypart, shift_hours, current_factor) AS (
    VALUES
        ('午市', 4.0::numeric, 1.00::numeric),
        ('下午茶', 3.0::numeric, 0.70::numeric),
        ('晚市', 4.0::numeric, 1.15::numeric),
        ('夜宵', 3.0::numeric, 0.65::numeric)
), roles(role_code, role_name, skill, target, minimum_staff, base_current, max_week_hours) AS (
    VALUES
        ('host', '迎宾', '预订接待与排队分流', 16.0::numeric, 1, 1, 40.0::numeric),
        ('service', '服务员', '堂食服务与翻台', 8.0::numeric, 2, 3, 40.0::numeric),
        ('kitchen', '后厨', '备餐与出餐', 7.0::numeric, 2, 3, 40.0::numeric),
        ('cashier', '收银', '收银与订单核对', 20.0::numeric, 1, 1, 40.0::numeric)
)
INSERT INTO restaurant_staffing_policy(
    factory_id, store_id, daypart, role_code, role_name, required_skill,
    shift_hours, target_guests_per_labor_hour, minimum_staff, current_staff,
    available_skilled_staff, max_hours_per_person_week,
    expected_reservation_share, source, is_simulated
)
SELECT s.factory_id, s.store_id, d.daypart, r.role_code, r.role_name, r.skill,
       d.shift_hours, r.target, r.minimum_staff,
       GREATEST(r.minimum_staff, ROUND(r.base_current * d.current_factor)::int),
       GREATEST(r.minimum_staff + 1, ROUND(r.base_current * d.current_factor)::int + 1),
       r.max_week_hours, 0.35, 'cretas_daily_simulator', TRUE
  FROM dim_store s
 CROSS JOIN dayparts d
 CROSS JOIN roles r
 WHERE s.factory_id IN ('MOCK_REST', 'RES_3101_009')
ON CONFLICT(factory_id, store_id, daypart, role_code) DO NOTHING;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    fact_restaurant_reservation,
    restaurant_staffing_policy,
    restaurant_staffing_adjustment,
    restaurant_reservation_roll_audit
TO smartbi_user;

GRANT USAGE, SELECT ON SEQUENCE
    fact_restaurant_reservation_id_seq,
    restaurant_staffing_policy_id_seq,
    restaurant_staffing_adjustment_id_seq,
    restaurant_reservation_roll_audit_id_seq
TO smartbi_user;

COMMENT ON TABLE fact_restaurant_reservation IS
    'Tenant-scoped reservation platform facts. is_simulated/source must remain visible to consumers.';
COMMENT ON TABLE restaurant_staffing_policy IS
    'Role skill/hour/productivity constraints; historical actual productivity is evidence only.';
COMMENT ON TABLE restaurant_staffing_adjustment IS
    'Audited user-confirmed staffing adjustments; GET/forecast paths never write here.';

COMMIT;
