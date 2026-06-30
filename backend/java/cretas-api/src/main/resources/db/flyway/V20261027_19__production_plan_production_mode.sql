-- V20261027_19: 生产业态字段 (BY_ORDER 销售订单生产 / BY_STOCK 库存永续生产)
-- Task 1 of inv-prod-phase2: 纯 additive; 默认 BY_ORDER 向后兼容。

ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS production_mode VARCHAR(30) NOT NULL DEFAULT 'BY_ORDER';

COMMENT ON COLUMN production_plans.production_mode
    IS '生产业态: BY_ORDER 销售订单生产 / BY_STOCK 库存(永续)生产';
