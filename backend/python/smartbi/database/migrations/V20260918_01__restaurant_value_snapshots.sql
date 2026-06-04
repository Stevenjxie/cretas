-- V20260918_01__restaurant_value_snapshots.sql
-- #56 价值可视化回馈回路 — 价值快照表。
-- 把诊断引擎已算出的省钱/改善金额按 (工厂, 期间, 门店) 幂等快照, 供 web 驾驶舱
-- ValueFeedbackStrip + RN 通知 + AI 问答"本月省了多少"读取。
-- 解决金毛范囊式"门店看不到价值 → 配合度崩塌"死因。
--
-- 触发 (D1 双触发, 同一 compute_and_upsert_snapshot 幂等):
--   (a) 每月1日 cron 遍历所有 RESTAURANT 工厂 upsert 上月快照 (兜底);
--   (b) 月度数据上传 hooks.py 物化尾部 fire-and-forget 即时重算当前工厂当前期间。
--
-- ⛔ 迁移 runner 以 postgres 超级用户跑 → 表归 postgres → 必须 GRANT DML + sequence
--   给 smartbi_user (per feedback_smartbi_table_grant_gap: 漏 GRANT 会导致写路径
--   permission denied 被 fail-open 静默吞, 表 0 行无人发现; 已踩过 3 次)。
-- ⛔ store_id 可空幂等: 用 COALESCE(store_id,'') 部分唯一索引, 不用裸 UNIQUE 约束
--   (PG 把 NULL 当作互不相等 → 全店汇总行 store_id IS NULL 会被插重)。

CREATE TABLE IF NOT EXISTS restaurant_value_snapshots (
    id                        BIGSERIAL PRIMARY KEY,
    factory_id                VARCHAR(50)  NOT NULL,
    period_month              VARCHAR(7)   NOT NULL,        -- 'YYYY-MM'
    store_id                  VARCHAR(100),                  -- NULL = 全店汇总
    labor_rigidity_annual_est NUMERIC(14,2),                -- 预估·年化 (人工刚性节省, NULL = 暂无数据)
    shrinkage_variance_amount NUMERIC(14,2),                -- 本月实测 (档口损溢超标金额)
    food_cost_savings_est     NUMERIC(14,2),                -- 预估 (食材成本改善空间)
    discount_savings_est      NUMERIC(14,2),                -- 预估 (折扣率改善空间)
    total_est_month           NUMERIC(14,2),                -- 月度口径合计 (D3, 与年化并存; NULL = 全 null)
    total_est_annual          NUMERIC(14,2),                -- 年化口径合计 (D3; NULL = 全 null)
    diagnosis_count           SMALLINT     NOT NULL DEFAULT 0,
    critical_count            SMALLINT     NOT NULL DEFAULT 0,
    rx_action_count           SMALLINT     NOT NULL DEFAULT 0,
    signal_sources            JSONB        NOT NULL DEFAULT '[]',  -- [{signal, label, amount, period}]
    confidence_note           TEXT,
    computed_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- store_id 可空幂等: COALESCE 部分唯一索引 (吸取 Wave1 G2 NULLS DISTINCT 教训)。
-- 全店汇总行 (store_id IS NULL) 与各门店行 (store_id = '门店X') 各自唯一。
CREATE UNIQUE INDEX IF NOT EXISTS uq_value_snapshot_factory_period_store
    ON restaurant_value_snapshots (factory_id, period_month, COALESCE(store_id, ''));
CREATE INDEX IF NOT EXISTS idx_value_snapshot_factory
    ON restaurant_value_snapshots (factory_id, period_month DESC);

-- RLS: 租户隔离 + __internal__ sentinel 旁路 (物化 hook / cron 以 SET app.factory_id
-- = 真实工厂 写, 但内部任务路径可能落 __internal__ — 与既有表 dim_store_review_alias
-- 同模式, per #590 教训)。DROP-then-CREATE 保证 re-apply 幂等。
ALTER TABLE restaurant_value_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_value_snapshots FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_value_snapshot_tenant ON restaurant_value_snapshots;
CREATE POLICY rls_value_snapshot_tenant ON restaurant_value_snapshots
    USING (current_setting('app.factory_id', true) IS NULL
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) = '__internal__'
           OR factory_id = current_setting('app.factory_id', true))
    WITH CHECK (current_setting('app.factory_id', true) IS NULL
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) = '__internal__'
           OR factory_id = current_setting('app.factory_id', true));

-- ⛔ GRANT (HARD, per feedback_smartbi_table_grant_gap): 不写则整个写路径死。
-- GRANT 幂等, 可安全重复 apply。runner 以 postgres 连 (表 owner) 故可授权。
GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_value_snapshots TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_value_snapshots_id_seq TO smartbi_user;

COMMENT ON TABLE restaurant_value_snapshots IS
  '#56 价值可视化回馈回路 — 诊断引擎已算金额按 (工厂,期间,门店) 幂等快照。'
  'labor_rigidity_annual_est=预估年化, shrinkage_variance_amount=本月实测, '
  'food_cost_savings_est/discount_savings_est=预估如达标; NULL = 暂无数据 (禁用 0 填 null)。'
  'Added 2026-06-04 per docs/superpowers/specs/2026-06-04-restaurant-value-feedback-loop-design.md';
