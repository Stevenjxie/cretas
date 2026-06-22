-- SP-B1: 文员逐道录入幂等表 (V20261027_03)
-- 按 (factory_id, plan_id, idempotency_key) 唯一索引防重复写入
CREATE TABLE IF NOT EXISTS process_entry_idempotency (
    id          BIGSERIAL PRIMARY KEY,
    factory_id  VARCHAR(50)  NOT NULL,
    plan_id     VARCHAR(191),
    idempotency_key VARCHAR(255) NOT NULL,
    result_json JSONB        NOT NULL,
    created_at  TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uq_process_entry_idempotency
        UNIQUE (factory_id, plan_id, idempotency_key)
);

CREATE INDEX idx_pei_factory_plan
    ON process_entry_idempotency (factory_id, plan_id);
