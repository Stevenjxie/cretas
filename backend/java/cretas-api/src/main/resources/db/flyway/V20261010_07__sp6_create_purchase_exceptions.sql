-- SP6 采购到付款 — 采购异常单表
-- 超收(OVER_RECEIVE) / 少收(UNDER_RECEIVE) 自动在 confirmReceive 时创建
-- 决策(ACCEPT_OVER / RETURN_OVER / ACCEPT_UNDER / REORDER)由采购员事后操作

CREATE TABLE IF NOT EXISTS purchase_exceptions (
    id                 VARCHAR(191)   NOT NULL PRIMARY KEY,
    factory_id         VARCHAR(191)   NOT NULL,
    exception_number   VARCHAR(50)    NOT NULL,
    receive_record_id  VARCHAR(191)   NOT NULL,
    purchase_order_id  VARCHAR(191)   NOT NULL,
    supplier_id        VARCHAR(191),
    material_type_id   VARCHAR(191),
    material_name      VARCHAR(200),

    -- 异常类型: OVER_RECEIVE / UNDER_RECEIVE
    exception_type     VARCHAR(32)    NOT NULL,

    -- 数量
    po_quantity        NUMERIC(15,4)  NOT NULL,
    received_quantity  NUMERIC(15,4)  NOT NULL,
    exception_qty      NUMERIC(15,4)  NOT NULL,
    unit               VARCHAR(20),

    -- 决策字段
    decision           VARCHAR(32),                  -- ACCEPT_OVER / RETURN_OVER / ACCEPT_UNDER / REORDER
    decision_by        BIGINT,
    decision_at        TIMESTAMP,
    decision_notes     TEXT,

    -- 状态: PENDING / RESOLVED
    status             VARCHAR(32)    NOT NULL DEFAULT 'PENDING',

    -- 创建人
    created_by         BIGINT         NOT NULL,

    -- BaseEntity audit 字段
    created_at         TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at         TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_purchase_exceptions_number
    ON purchase_exceptions (factory_id, exception_number)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_purchase_exceptions_factory_status
    ON purchase_exceptions (factory_id, status)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_purchase_exceptions_receive_record
    ON purchase_exceptions (receive_record_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE  purchase_exceptions                    IS 'SP6: 采购超收/少收异常单';
COMMENT ON COLUMN purchase_exceptions.exception_type     IS 'OVER_RECEIVE=超收, UNDER_RECEIVE=少收';
COMMENT ON COLUMN purchase_exceptions.decision           IS 'ACCEPT_OVER/RETURN_OVER/ACCEPT_UNDER/REORDER';
COMMENT ON COLUMN purchase_exceptions.status             IS 'PENDING=待处理, RESOLVED=已处理';
