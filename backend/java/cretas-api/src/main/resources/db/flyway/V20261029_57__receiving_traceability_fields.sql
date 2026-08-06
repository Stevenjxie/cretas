-- =============================================================================
-- V20261029_57: 收货可追溯字段 —— 合同号 / 供应商批次号 / 件数
--
-- 背景 (客户 2026-08-06)
--   六膳门用 Excel 台账记来料, 列是:
--     来料日期 | 料号 | 原料名称 | 原料状态 | 合同号 | 批次号 | 厂号 | 件数(件/箱) | 初期重量KG
--   客户原话「收货里面要能填写这些信息」。
--
--   系统里已有: 厂号(factory_number) / 产地(origin_place) —— 收货 DTO 与批次表都有,
--   只是收货弹窗没渲染; 件数在 material_batches 有 box_count 但收货链路没有。
--   真正缺列的是 **合同号** 与 **供应商批次号**。
--
-- ⛔ 为什么合同号必须挂在**行/批次**上, 不能只用采购单上那个
--   采购单已有 purchase_orders.contract_number(订单级)。但客户实测数据里,
--   同一张 PO-20260806-0001 下的两行来料是**两个不同合同号**:
--     2026-08-02  SAN-16572
--     2026-08-04  SAN-16562
--   订单级装不下, 所以按收货行/批次记。
--
-- ⛔ 为什么不复用 material_batches.batch_number 记客户的「批次号」
--   那一列是**系统生成**的批次号(MT-20260806-9583 这种), 是库存主键语义。
--   供应商/客户给的批次号(20251029)是外部标识, 占用会让两个语义打架。
--
-- 影响
--   纯加列, 全部可空, 不改任何存量行。加完之前的收货记录这三列为 NULL。
--
-- 回滚
--   db/manual-rollback/V20261029_57__receiving_traceability_fields_rollback.sql
-- =============================================================================

ALTER TABLE purchase_receive_items
    ADD COLUMN IF NOT EXISTS contract_number        varchar(100),
    ADD COLUMN IF NOT EXISTS supplier_batch_number  varchar(100),
    ADD COLUMN IF NOT EXISTS box_count              integer;

ALTER TABLE material_batches
    ADD COLUMN IF NOT EXISTS contract_number        varchar(100),
    ADD COLUMN IF NOT EXISTS supplier_batch_number  varchar(100);

COMMENT ON COLUMN purchase_receive_items.contract_number IS
    '合同号(收货行级) —— 同一张采购单的不同来料批可以是不同合同号, 订单级的 purchase_orders.contract_number 装不下';
COMMENT ON COLUMN purchase_receive_items.supplier_batch_number IS
    '供应商/客户给的批次号 —— 与系统生成的 material_batches.batch_number 是两件事, 不可互相占用';
COMMENT ON COLUMN purchase_receive_items.box_count IS
    '件数(件/箱) —— 客户台账列, 与重量并存: 抄码来料件数固定而重量不定';
COMMENT ON COLUMN material_batches.contract_number IS
    '合同号 —— 从收货行带入, 供批次追溯';
COMMENT ON COLUMN material_batches.supplier_batch_number IS
    '供应商/客户批次号 —— 从收货行带入; batch_number 仍是系统生成的库存批次号';

-- 便于按合同号/供应商批次号追溯 (客户按这两个查来料是常态)
CREATE INDEX IF NOT EXISTS idx_mb_contract_number
    ON material_batches (factory_id, contract_number)
    WHERE contract_number IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_mb_supplier_batch_number
    ON material_batches (factory_id, supplier_batch_number)
    WHERE supplier_batch_number IS NOT NULL;
