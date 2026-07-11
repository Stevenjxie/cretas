-- Silver tables — 会员储值 + 画像 (member stored-value + demographic profile).
--
-- New restaurant analytics dimension: NOT full RFM (no recency/frequency —
-- this data source has no per-member consumption event, only card-issuance
-- snapshot + recharge transactions). See gold/queries.py member_profile()
-- for the explicit "not full RFM" caveat surfaced to the frontend.
--
-- Source A: 二维火 "卡详情一览" export — one row per member card, SNAPSHOT
-- grain (current tier/balance, not a daily fact). Real columns (verified
-- against a 青花椒 customer export): 卡号/卡类型/领用状态/状态/本金/赠送金/
-- 余额(元)/会员/剩余积分/等级/性别/手机/生日/押金/售卡人/发卡日期/
-- 卡所属门店/发卡门店.
--
-- 🔒 PII MINIMIZATION (not just masking-on-read): 卡号 (card no), 会员
-- (member nickname), 手机 (phone) are NEVER persisted here — not even
-- hashed. 生日 (birthdate) is reduced to birth_month (1-12) at ingest time;
-- day and year are discarded before the row ever reaches this table. This
-- table holds ZERO fields that identify an individual — the aggregation
-- (agg_member_tier / agg_member_birth_month in V20261006_02) reads from it,
-- but even if this Silver table were exposed directly, no PII would leak.
--
-- Natural key: 发卡日期 in the real export is a full timestamp (e.g.
-- "2025-01-01 09:20:44", not just a date) — issued one member at a time in
-- practice, so (factory_id, source_type, issue_store_id, issue_at) is a
-- solid idempotency key without needing card_no (which we don't store).
--
-- Source B: 二维火 "卡充值统计" export — no card number column at all (this
-- report is pre-aggregated by the POS to store+date+channel grain), so it
-- carries no PII to begin with. Columns: 充值门店/日期/充值笔数/本金/
-- 赠送金额/合计/其它/操作渠道/手续费/实收金额.
CREATE TABLE IF NOT EXISTS dim_member (
    id                 BIGSERIAL PRIMARY KEY,
    factory_id         VARCHAR(50) NOT NULL,
    upload_id          BIGINT,               -- loose link to upload tracking
    source_type        VARCHAR(20) NOT NULL DEFAULT 'excel',
    issue_store_id     BIGINT NOT NULL,       -- 发卡门店
    tier               VARCHAR(50) NOT NULL DEFAULT '未知',  -- 等级
    gender             VARCHAR(10) NOT NULL DEFAULT '未知',  -- 性别 ('未知' already appears in real data)
    birth_month        SMALLINT,              -- 1-12; NULL = 生日 missing/blank in source. Month ONLY — day/year discarded (PII minimization, see header note)
    balance            NUMERIC(12,2) NOT NULL DEFAULT 0,  -- 余额(元)
    issue_at           TIMESTAMP NOT NULL,     -- 发卡日期 (full timestamp; doubles as natural-key component)
    created_at         TIMESTAMP DEFAULT NOW(),
    updated_at         TIMESTAMP DEFAULT NOW(),
    CONSTRAINT chk_dim_member_birth_month CHECK (birth_month IS NULL OR (birth_month BETWEEN 1 AND 12)),
    CONSTRAINT uq_dim_member UNIQUE (factory_id, source_type, issue_store_id, issue_at),
    CONSTRAINT fk_dim_member_store FOREIGN KEY (issue_store_id)
        REFERENCES dim_store (store_id) ON DELETE RESTRICT
);
ALTER TABLE dim_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE dim_member FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON dim_member;
CREATE POLICY tenant_isolation ON dim_member FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
DROP TRIGGER IF EXISTS trg_dim_member_touch ON dim_member;
CREATE TRIGGER trg_dim_member_touch BEFORE UPDATE ON dim_member
    FOR EACH ROW EXECUTE FUNCTION silver_touch_updated_at();
-- Hot path A: 等级分布 aggregator scan (agg_member_tier materializer).
CREATE INDEX IF NOT EXISTS idx_dim_member_factory_store_tier
    ON dim_member (factory_id, issue_store_id, tier);
-- Hot path B: 生日月份分布 aggregator scan (agg_member_birth_month materializer).
CREATE INDEX IF NOT EXISTS idx_dim_member_factory_birth_month
    ON dim_member (factory_id, birth_month)
    WHERE birth_month IS NOT NULL;
-- Hot path C: upload rollback ("delete everything from upload 1234").
CREATE INDEX IF NOT EXISTS idx_dim_member_upload
    ON dim_member (upload_id)
    WHERE upload_id IS NOT NULL;


-- Silver fact table — 充值 (recharge) event grain. Source report is already
-- pre-aggregated by the POS to (store, date, channel) — no per-member/
-- per-card detail, hence no PII concern here at all.
CREATE TABLE IF NOT EXISTS fact_member_recharge (
    id                 BIGSERIAL PRIMARY KEY,
    factory_id         VARCHAR(50) NOT NULL,
    upload_id          BIGINT,
    source_type        VARCHAR(20) NOT NULL DEFAULT 'excel',
    store_id           BIGINT NOT NULL,        -- 充值门店
    recharge_date      DATE NOT NULL,          -- 日期
    -- channel is NOT NULL DEFAULT '' (part of the UNIQUE key) — a NULL never
    -- conflicts with anything in a Postgres UNIQUE index, so a blank-渠道 row
    -- would silently duplicate on every rerun (same reasoning as
    -- fact_pos_void.table_no, V20261005_01).
    channel            VARCHAR(100) NOT NULL DEFAULT '',  -- 操作渠道
    recharge_count     INT NOT NULL DEFAULT 0,  -- 充值笔数
    principal          NUMERIC(12,2) NOT NULL DEFAULT 0,  -- 本金
    bonus              NUMERIC(12,2) NOT NULL DEFAULT 0,  -- 赠送金额
    other_amount       NUMERIC(12,2),           -- 其它 (source semantics unclear beyond the label; kept honest as a nullable extra, not folded into principal/bonus)
    fee                NUMERIC(12,2),            -- 手续费
    actual_received    NUMERIC(12,2),            -- 实收金额
    created_at         TIMESTAMP DEFAULT NOW(),
    updated_at         TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_fact_member_recharge UNIQUE (factory_id, source_type, store_id, recharge_date, channel),
    CONSTRAINT fk_fact_member_recharge_store FOREIGN KEY (store_id)
        REFERENCES dim_store (store_id) ON DELETE RESTRICT
);
ALTER TABLE fact_member_recharge ENABLE ROW LEVEL SECURITY;
ALTER TABLE fact_member_recharge FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fact_member_recharge;
CREATE POLICY tenant_isolation ON fact_member_recharge FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
DROP TRIGGER IF EXISTS trg_fact_member_recharge_touch ON fact_member_recharge;
CREATE TRIGGER trg_fact_member_recharge_touch BEFORE UPDATE ON fact_member_recharge
    FOR EACH ROW EXECUTE FUNCTION silver_touch_updated_at();
-- Hot path: 充值趋势 (monthly principal/bonus) aggregator scan.
CREATE INDEX IF NOT EXISTS idx_fact_member_recharge_factory_date_store
    ON fact_member_recharge (factory_id, recharge_date, store_id);
-- Hot path: upload rollback.
CREATE INDEX IF NOT EXISTS idx_fact_member_recharge_upload
    ON fact_member_recharge (upload_id)
    WHERE upload_id IS NOT NULL;
