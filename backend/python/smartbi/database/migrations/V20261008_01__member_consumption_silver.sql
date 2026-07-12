-- Silver table — 会员卡消费排行 (per-member RFM snapshot). CRM P0.
--
-- New restaurant analytics dimension: FULL RFM (unlike V20261007_01's
-- member_profile dimension, which explicitly is NOT RFM because its source
-- has no per-member consumption event). This table's source — 二维火 "卡消费
-- 排行" export — IS per-card, already aggregated by the POS to give exactly
-- the three RFM primitives directly:
--   R (Recency)   <- 最近消费日期 (last_spend_date)
--   F (Frequency) <- 累计卡消费次数 (spend_count)
--   M (Monetary)  <- 累计卡消费金额 (cum_spend)
--
-- Cohort: 有滋有味 connect chain (NOT 青花椒 — that chain's export has no
-- per-card consumption ranking report, only card-snapshot + recharge, which
-- is why V20261007_01's dimension is honestly NOT-RFM). See
-- gold/queries.py member_rfm() for the full caveat surfaced to the frontend.
--
-- Real columns (verified against a 有滋有味 customer export, 7481 rows):
--   卡号,会员,手机号,卡类型,当前本金余额(元),当前赠送余额(元),当前余额(元),
--   累计本金消费,累计赠送金消费,累计卡消费金额,累计卡消费次数,最近消费日期,
--   消费间隔(天),发卡日期,卡所属门店,发卡门店
--
-- 🔒 PII: 卡号 (card_no) and 会员 (member_name) ARE persisted here (needed as
-- the natural/idempotency key + join key for future per-member drill-down),
-- but per .claude/rules —手机号/卡号只作 join key, 绝不进任何 API 输出或 agg
-- 表 — this table is NEVER read directly by any API; only the aggregate
-- Gold tables (V20261008_02) are, and none of THOSE tables carry card_no or
-- member_name. 手机号 (phone) is not persisted at all — not needed for any
-- planned join, so it is simply never stored (stricter than "join key only").
--
-- Snapshot semantics: 卡消费排行 is a live cumulative snapshot as of export
-- time (like V20261007_01's dim_member), NOT an appendable event log. Re-
-- running the loader against a refreshed export should UPDATE a card's
-- numbers (spend grows, last_spend_date moves forward), not silently skip —
-- hence ON CONFLICT ... DO UPDATE in the loader, unlike dim_member's
-- ON CONFLICT DO NOTHING (whose natural key already includes a timestamp
-- component making every row distinct-by-construction).
--
-- ⚠️ spend_interval_days (消费间隔(天)) is the SOURCE's own "days since last
-- consumption" computed by the POS AS OF EXPORT TIME — it goes stale the
-- moment "today" moves on (this export was pulled ~2026-04-22; DEMO_REST's
-- "today" is 2026-07-11, ~80 days later). It is kept here as a raw,
-- unmodified fact for transparency/debugging ONLY. Recency/lifecycle
-- classification in the Gold layer (V20261008_02/materializer) does NOT use
-- this column — it recomputes (CURRENT_DATE - last_spend_date) at
-- materialization time so "沉睡/流失" buckets stay accurate as "today" moves
-- forward. See member_rfm.py's module docstring for the full rationale.
--
-- Date decision (no +1yr shift): the source window (发卡日期 as early as
-- 2018, 最近消费日期 up to 2026-04-21) is used AS-IS, unlike
-- extract_shift_member_2026.py's 青花椒 pipeline (which shifted 2025 -> 2026
-- because that chain's data pre-dated DEMO_REST's live window). Here the
-- real dates already sit 3-8 months before DEMO_REST's "today" (2026-07-11),
-- which is exactly what makes 沉睡/流失 lifecycle buckets non-trivial and
-- realistic without any fabrication. See extract_member_rfm.py.
CREATE TABLE IF NOT EXISTS fact_member_consumption (
    id                    BIGSERIAL PRIMARY KEY,
    factory_id            VARCHAR(50) NOT NULL,
    upload_id             BIGINT,                -- loose link to upload tracking
    source_type           VARCHAR(20) NOT NULL DEFAULT 'excel',
    card_no               VARCHAR(50) NOT NULL,   -- 卡号 — join key ONLY, never returned by any API
    member_name           VARCHAR(100),           -- 会员 — join key ONLY, never returned by any API
    tier                  VARCHAR(50) NOT NULL DEFAULT '未知',  -- 卡类型
    store_id              BIGINT NOT NULL,        -- 卡所属门店
    issue_store_id        BIGINT NOT NULL,        -- 发卡门店
    principal_balance     NUMERIC(12,2) NOT NULL DEFAULT 0,  -- 当前本金余额(元)
    bonus_balance         NUMERIC(12,2) NOT NULL DEFAULT 0,  -- 当前赠送余额(元)
    total_balance         NUMERIC(12,2) NOT NULL DEFAULT 0,  -- 当前余额(元)
    cum_principal_spend   NUMERIC(14,2) NOT NULL DEFAULT 0,  -- 累计本金消费
    cum_bonus_spend       NUMERIC(14,2) NOT NULL DEFAULT 0,  -- 累计赠送金消费
    cum_spend             NUMERIC(14,2) NOT NULL DEFAULT 0,  -- 累计卡消费金额 (M)
    spend_count           INT NOT NULL DEFAULT 0,            -- 累计卡消费次数 (F)
    last_spend_date       TIMESTAMP NOT NULL,                -- 最近消费日期 (R, source basis)
    spend_interval_days   INT,                                -- 消费间隔(天) — RAW source fact only, see header note; NOT used for lifecycle scoring
    issue_date            DATE NOT NULL,                     -- 发卡日期 (bare date in this export, unlike V20261007_01's full-timestamp 发卡日期)
    created_at            TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_fact_member_consumption UNIQUE (factory_id, card_no),
    CONSTRAINT fk_fact_member_consumption_store FOREIGN KEY (store_id)
        REFERENCES dim_store (store_id) ON DELETE RESTRICT,
    CONSTRAINT fk_fact_member_consumption_issue_store FOREIGN KEY (issue_store_id)
        REFERENCES dim_store (store_id) ON DELETE RESTRICT
);
ALTER TABLE fact_member_consumption ENABLE ROW LEVEL SECURITY;
ALTER TABLE fact_member_consumption FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fact_member_consumption;
CREATE POLICY tenant_isolation ON fact_member_consumption FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
-- Hot path: RFM NTILE scoring scan (materialize_member_rfm) + recency reads.
CREATE INDEX IF NOT EXISTS idx_fact_member_consumption_factory_recency
    ON fact_member_consumption (factory_id, last_spend_date);
-- Hot path: upload rollback ("delete everything from upload 1234").
CREATE INDEX IF NOT EXISTS idx_fact_member_consumption_upload
    ON fact_member_consumption (upload_id)
    WHERE upload_id IS NOT NULL;
