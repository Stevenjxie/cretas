-- T126 Phase 1: 成品库存调整日志表
-- 记录 /finished-goods/{id}/adjust 的每次数量调整，提供可审计的变更历史
-- Idempotent: CREATE TABLE IF NOT EXISTS

CREATE TABLE IF NOT EXISTS finished_goods_adjustment_log (
    id               BIGSERIAL PRIMARY KEY,
    factory_id       VARCHAR(191)   NOT NULL,
    batch_id         VARCHAR(191)   NOT NULL,
    adjustment_quantity NUMERIC(15, 4) NOT NULL,
    before_produced  NUMERIC(15, 4) NOT NULL,
    after_produced   NUMERIC(15, 4) NOT NULL,
    reason           TEXT,
    reference_type   VARCHAR(50),
    operator_id      BIGINT,
    created_at       TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fgal_factory_batch ON finished_goods_adjustment_log (factory_id, batch_id);
CREATE INDEX IF NOT EXISTS idx_fgal_created_at    ON finished_goods_adjustment_log (created_at);
