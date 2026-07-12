-- Gold aggregates — 会员 RFM 分析 (full RFM: Recency/Frequency/Monetary). CRM P0.
--
-- Source: fact_member_consumption (V20261008_01). Materialized by
-- smartbi/services/materialized_analytics/member_rfm.py. Read by
-- smartbi.gold.queries.member_rfm() via GET /api/smartbi/gold/member-rfm.
--
-- 🔒 AGGREGATE-ONLY BY CONSTRUCTION: none of these three tables carry
-- card_no / member_name (fact_member_consumption's only PII-adjacent join
-- keys stay in Silver, never propagate to Gold). Safe to read row-by-row
-- from the API without additional stripping BEYOND the query layer's
-- k-anonymity (k=5) suppression — see gold/queries.py member_rfm()'s
-- docstring. RBAC money-strip (gold_reads._apply_rbac_strip) additionally
-- nulls total_cum_spend / avg_cum_spend / total_balance for non-price-view
-- roles (avg_spend_interval is a day-count, not money, and stays visible).
--
-- Three tables, three different consumers:
--   agg_member_rfm_segment  — raw (r,f,m) quintile-bucket grid (up to 125
--                             combos). Feeds the RFM 3D scatter chart.
--   agg_member_rfm_tier     — (r,f,m) mapped to 7 business-friendly labels
--                             (Champions/Loyal/Potential/New/At Risk/
--                             Hibernating/Lost) — SAME classification rule
--                             as the existing in-memory analyzer
--                             (smartbi/services/restaurant/member_rfm.py
--                             MemberRfmAnalyzer._classify_segment), reused
--                             here as a SQL CASE for naming consistency
--                             across the two independent RFM
--                             implementations in this codebase.
--   agg_member_lifecycle    — simpler recency-only bucketing (新客/活跃/
--                             沉睡>90天/流失>180天/未消费) for a plainer
--                             "who do we need to win back" view.
--
-- All three are recomputed from fact_member_consumption.last_spend_date at
-- MATERIALIZATION TIME (CURRENT_DATE - last_spend_date), NOT from the
-- source's stale 消费间隔(天) column — see V20261008_01's header note.
-- Re-running the materializer periodically (even with no new Silver rows)
-- will correctly age members from 活跃 -> 沉睡 -> 流失 as time passes.

CREATE TABLE IF NOT EXISTS agg_member_rfm_segment (
    factory_id      VARCHAR(50) NOT NULL,
    r_score         SMALLINT NOT NULL,   -- 1-5 (5 = most recent)
    f_score         SMALLINT NOT NULL,   -- 1-5 (5 = most frequent)
    m_score         SMALLINT NOT NULL,   -- 1-5 (5 = highest cumulative spend)
    member_count    INT NOT NULL DEFAULT 0,
    avg_cum_spend   NUMERIC(14,2) NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 1,
    computed_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, r_score, f_score, m_score),
    CONSTRAINT chk_agg_member_rfm_segment_r CHECK (r_score BETWEEN 1 AND 5),
    CONSTRAINT chk_agg_member_rfm_segment_f CHECK (f_score BETWEEN 1 AND 5),
    CONSTRAINT chk_agg_member_rfm_segment_m CHECK (m_score BETWEEN 1 AND 5)
);
ALTER TABLE agg_member_rfm_segment ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_member_rfm_segment FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_member_rfm_segment;
CREATE POLICY tenant_isolation ON agg_member_rfm_segment FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
-- Hot path: RFM 3D scatter chart read (member_rfm()).
CREATE INDEX IF NOT EXISTS idx_agg_member_rfm_segment_factory
    ON agg_member_rfm_segment (factory_id);


CREATE TABLE IF NOT EXISTS agg_member_rfm_tier (
    factory_id          VARCHAR(50) NOT NULL,
    -- 7-label business tier — mirrors restaurant/member_rfm.py's Segment
    -- Literal exactly (English labels; frontend translates/localizes for
    -- display). NOT an enum column (keep Postgres schema-flexible if a
    -- future re-classification adds/renames a tier without a migration).
    rfm_tier            VARCHAR(30) NOT NULL,
    member_count         INT NOT NULL DEFAULT 0,
    total_cum_spend      NUMERIC(14,2) NOT NULL DEFAULT 0,
    -- Average days since last consumption for members in this tier, AS OF
    -- computed_at (recomputed every materialization run — see header note).
    avg_spend_interval   NUMERIC(8,2),
    version              BIGINT NOT NULL DEFAULT 1,
    computed_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, rfm_tier)
);
ALTER TABLE agg_member_rfm_tier ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_member_rfm_tier FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_member_rfm_tier;
CREATE POLICY tenant_isolation ON agg_member_rfm_tier FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
CREATE INDEX IF NOT EXISTS idx_agg_member_rfm_tier_factory
    ON agg_member_rfm_tier (factory_id);


CREATE TABLE IF NOT EXISTS agg_member_lifecycle (
    factory_id       VARCHAR(50) NOT NULL,
    -- 新客 (issued <=30d ago) / 活跃 (<=90d since last spend) /
    -- 沉睡 (90-180d) / 流失 (>180d) / 未消费 (spend_count = 0, defensive —
    -- 卡消费排行 is a "consumption ranking" report so this bucket is
    -- expected to be empty in practice, but classified honestly rather
    -- than assumed impossible).
    lifecycle_stage  VARCHAR(20) NOT NULL,
    member_count     INT NOT NULL DEFAULT 0,
    total_balance    NUMERIC(14,2) NOT NULL DEFAULT 0,
    version          BIGINT NOT NULL DEFAULT 1,
    computed_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, lifecycle_stage)
);
ALTER TABLE agg_member_lifecycle ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_member_lifecycle FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_member_lifecycle;
CREATE POLICY tenant_isolation ON agg_member_lifecycle FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
CREATE INDEX IF NOT EXISTS idx_agg_member_lifecycle_factory
    ON agg_member_lifecycle (factory_id);
