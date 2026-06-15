-- V20261024_17: 销售订单改价记录表 (warn-not-block 模式)
-- Spec: 销售订单行价格调整 + 历史留痕 + 超阈值审计标记 (不阻塞)
-- fool-proof Rule 3: 原因类型用 enum (dropdown), OTHER 时才填明细
-- warn-not-block: 改价立即生效，超阈值 (降价>10%/涨价>20%) 设 flagged=true 供审计

CREATE TABLE sales_price_adjustment_records (
    id                       VARCHAR(191) PRIMARY KEY,
    sales_order_line_id      BIGINT       NOT NULL,
    sales_order_id           VARCHAR(191) NOT NULL,
    factory_id               VARCHAR(191) NOT NULL,
    old_unit_price           NUMERIC(19,4) NOT NULL,
    new_unit_price           NUMERIC(19,4) NOT NULL,
    adjustment_reason_type   VARCHAR(64)  NOT NULL,
    adjustment_reason_detail TEXT,
    adjusted_by              BIGINT       NOT NULL,
    adjusted_by_name         VARCHAR(200),
    flagged                  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at               TIMESTAMP    NULL
);

CREATE INDEX idx_spa_factory_order
    ON sales_price_adjustment_records (factory_id, sales_order_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_spa_line
    ON sales_price_adjustment_records (sales_order_line_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE sales_price_adjustment_records IS '销售订单行改价记录 — 留痕审计，warn-not-block 模式';
COMMENT ON COLUMN sales_price_adjustment_records.adjustment_reason_type IS 'CUSTOMER_REQUEST|MARKET_CHANGE|NEGOTIATION|PROMOTION|ERROR_CORRECTION|OTHER';
COMMENT ON COLUMN sales_price_adjustment_records.flagged IS '超阈值审计标记: 降价>10% 或 涨价>20% 时为 true，改价仍立即生效';
