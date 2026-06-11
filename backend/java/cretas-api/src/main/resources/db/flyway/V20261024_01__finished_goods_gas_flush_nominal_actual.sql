-- 气调货入库 标称数 vs 实收数 (六扇门转录 13:01-13:48 "一托36放37, 对方划单")
-- finished_goods_batches 新增 3 列, 全 nullable, 不破坏现有入库路径:
--   nominal_quantity   = 标称数 (一托标 36). null = 非气调/无差异入库, 用 produced_quantity 即可
--   counterparty_confirmed = 对方划单确认 (boolean). 气调货差异需对方签字确认
--   inbound_remark     = 入库差异/划单备注 (留痕)
-- 库存仍按 produced_quantity (实收数 37) 计量 — 不改变现有库存事务语义.
-- 差异 = produced_quantity - nominal_quantity, 业务层派生, 不持久化避免冗余.

ALTER TABLE finished_goods_batches
    ADD COLUMN IF NOT EXISTS nominal_quantity NUMERIC(15, 4),
    ADD COLUMN IF NOT EXISTS counterparty_confirmed BOOLEAN,
    ADD COLUMN IF NOT EXISTS inbound_remark TEXT;

COMMENT ON COLUMN finished_goods_batches.nominal_quantity
    IS '气调货标称数 (一托标36). null=非气调/无差异. 库存按 produced_quantity(实收)计量';
COMMENT ON COLUMN finished_goods_batches.counterparty_confirmed
    IS '对方划单确认 (气调差异需对方签字). null=不适用';
COMMENT ON COLUMN finished_goods_batches.inbound_remark
    IS '入库差异/划单备注 (留痕)';
