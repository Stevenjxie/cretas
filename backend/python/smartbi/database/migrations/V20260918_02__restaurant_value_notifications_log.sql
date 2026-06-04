-- V20260918_02__restaurant_value_notifications_log.sql
-- #56 价值可视化回馈回路 — 月度通知防重日志表。
-- value_notifier.maybe_notify_monthly 调 Java 通知前先查本表; 通知成功后写一行。
-- 防止 cron + upload 双触发 (D1) 对同一 (工厂, 期间, 角色) 重复推送。
--
-- 防重键: UNIQUE(factory_id, period_month, recipient_role)。三者都 NOT NULL
-- (无可空列) → 普通 UNIQUE 约束即可 (无 NULLS DISTINCT 陷阱, 不需 COALESCE 部分索引)。
--
-- ⛔ GRANT DML + sequence (HARD, per feedback_smartbi_table_grant_gap)。
-- ⛔ RLS 租户隔离 + __internal__ 旁路 (cron 内部任务路径写)。

CREATE TABLE IF NOT EXISTS restaurant_value_notifications_log (
    id              BIGSERIAL PRIMARY KEY,
    factory_id      VARCHAR(50)  NOT NULL,
    period_month    VARCHAR(7)   NOT NULL,        -- 'YYYY-MM'
    recipient_role  VARCHAR(64)  NOT NULL,        -- e.g. restaurant_manager / factory_super_admin
    snapshot_id     BIGINT,                        -- 关联 restaurant_value_snapshots.id (审计用, 可空)
    notified_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- 防重: 每 (工厂, 期间, 角色) 只推一次
    CONSTRAINT uq_value_notif_factory_period_role
        UNIQUE (factory_id, period_month, recipient_role)
);

CREATE INDEX IF NOT EXISTS idx_value_notif_factory
    ON restaurant_value_notifications_log (factory_id, period_month);

ALTER TABLE restaurant_value_notifications_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_value_notifications_log FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_value_notif_tenant ON restaurant_value_notifications_log;
CREATE POLICY rls_value_notif_tenant ON restaurant_value_notifications_log
    USING (current_setting('app.factory_id', true) IS NULL
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) = '__internal__'
           OR factory_id = current_setting('app.factory_id', true))
    WITH CHECK (current_setting('app.factory_id', true) IS NULL
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) = '__internal__'
           OR factory_id = current_setting('app.factory_id', true));

-- ⛔ GRANT (HARD, per feedback_smartbi_table_grant_gap)。
GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_value_notifications_log TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_value_notifications_log_id_seq TO smartbi_user;

COMMENT ON TABLE restaurant_value_notifications_log IS
  '#56 价值可视化回馈回路 — 月度通知防重日志。UNIQUE(factory_id,period_month,recipient_role) '
  '防止 cron + upload 双触发对同一角色重复推送。'
  'Added 2026-06-04 per docs/superpowers/specs/2026-06-04-restaurant-value-feedback-loop-design.md';
