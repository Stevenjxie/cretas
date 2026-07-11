-- Gold aggregates — 会员储值 + 画像 (member stored-value + demographic profile).
--
-- Sources: dim_member (V20261007_01, snapshot grain) + fact_member_recharge
-- (V20261007_01, daily event grain). Materialized by
-- smartbi/services/materialized_analytics/member_profile.py. Read by
-- smartbi.gold.queries.member_profile() via
-- GET /api/smartbi/gold/member-profile.
--
-- 🔒 AGGREGATE-ONLY BY CONSTRUCTION: none of these four tables carry a
-- member-identifying column (no card_no, no name, no phone, no full
-- birthdate — see V20261007_01's header note). They are safe to read
-- row-by-row from the API without any additional RBAC/PII stripping.
-- The query layer (member_profile()) additionally applies k-anonymity
-- (k=5) suppression so no cohort smaller than 5 exposes a balance / count.
--
-- Snapshot vs time-series: agg_member_tier / agg_member_birth_month /
-- agg_member_gender have NO date dimension (dim_member is a current-state
-- snapshot per card, not a daily fact — mirrors how the source 卡详情一览
-- report has no event date to bucket by). agg_member_recharge_daily DOES
-- have a date dimension (mirrors agg_daily_void's daily grain) since
-- fact_member_recharge is a true daily event stream.

CREATE TABLE IF NOT EXISTS agg_member_tier (
    factory_id     VARCHAR(50) NOT NULL,
    store_id       BIGINT NOT NULL,
    tier           VARCHAR(50) NOT NULL,
    member_count   INT NOT NULL DEFAULT 0,
    total_balance  NUMERIC(14,2) NOT NULL DEFAULT 0,
    version        BIGINT NOT NULL DEFAULT 1,
    computed_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, store_id, tier),
    CONSTRAINT fk_agg_member_tier_store FOREIGN KEY (store_id)
        REFERENCES dim_store (store_id) ON DELETE CASCADE
);
ALTER TABLE agg_member_tier ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_member_tier FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_member_tier;
CREATE POLICY tenant_isolation ON agg_member_tier FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
-- Hot path: "member count + balance totals" (SUM grouped by nothing/tier).
CREATE INDEX IF NOT EXISTS idx_agg_member_tier_factory
    ON agg_member_tier (factory_id);


CREATE TABLE IF NOT EXISTS agg_member_gender (
    factory_id     VARCHAR(50) NOT NULL,
    -- gender is a low-cardinality demographic bucket (男/女/未知 in the real
    -- 二维火 export). '未知' already appears verbatim in the source data, so
    -- no synthetic sentinel is needed. Storing it satisfies "don't
    -- store-and-never-use" — dim_member.gender feeds this 性别画像 aggregate.
    gender         VARCHAR(10) NOT NULL,
    member_count   INT NOT NULL DEFAULT 0,
    version        BIGINT NOT NULL DEFAULT 1,
    computed_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, gender)
);
ALTER TABLE agg_member_gender ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_member_gender FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_member_gender;
CREATE POLICY tenant_isolation ON agg_member_gender FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
CREATE INDEX IF NOT EXISTS idx_agg_member_gender_factory
    ON agg_member_gender (factory_id);


CREATE TABLE IF NOT EXISTS agg_member_birth_month (
    factory_id     VARCHAR(50) NOT NULL,
    -- birth_month sentinel: PRIMARY KEY columns cannot be NULL. 0 = "生日
    -- missing/blank in source" (honest "unknown" bucket, not fabricated —
    -- mirrors agg_daily_void's 0/未标注 sentinel pattern, V20261005_02).
    birth_month    SMALLINT NOT NULL,
    member_count   INT NOT NULL DEFAULT 0,
    version        BIGINT NOT NULL DEFAULT 1,
    computed_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, birth_month),
    CONSTRAINT chk_agg_member_birth_month CHECK (birth_month BETWEEN 0 AND 12)
);
ALTER TABLE agg_member_birth_month ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_member_birth_month FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_member_birth_month;
CREATE POLICY tenant_isolation ON agg_member_birth_month FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
CREATE INDEX IF NOT EXISTS idx_agg_member_birth_month_factory
    ON agg_member_birth_month (factory_id);


CREATE TABLE IF NOT EXISTS agg_member_recharge_daily (
    factory_id       VARCHAR(50) NOT NULL,
    date             DATE NOT NULL,
    store_id         BIGINT NOT NULL,
    -- channel NOT NULL DEFAULT '未标注' — mirrors agg_daily_void.void_reason
    -- sentinel (V20261005_02): PK columns can't be NULL, and a blank source
    -- 操作渠道 should not silently collapse into every other blank row from
    -- a DIFFERENT actual channel if the source ever starts populating it.
    channel          VARCHAR(100) NOT NULL DEFAULT '未标注',
    recharge_count   INT NOT NULL DEFAULT 0,
    principal        NUMERIC(14,2) NOT NULL DEFAULT 0,
    bonus            NUMERIC(14,2) NOT NULL DEFAULT 0,
    version          BIGINT NOT NULL DEFAULT 1,
    computed_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, date, store_id, channel),
    CONSTRAINT fk_agg_member_recharge_daily_store FOREIGN KEY (store_id)
        REFERENCES dim_store (store_id) ON DELETE CASCADE
);
ALTER TABLE agg_member_recharge_daily ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_member_recharge_daily FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_member_recharge_daily;
CREATE POLICY tenant_isolation ON agg_member_recharge_daily FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
-- Hot path: "充值趋势 over date range" (SUM principal/bonus grouped by month).
CREATE INDEX IF NOT EXISTS idx_agg_member_recharge_daily_factory_date
    ON agg_member_recharge_daily (factory_id, date);
