-- R12 (2026-06-23): 成品报损完整扣库存 —— DisposalRecord 加 finished_goods_batch_id 列。
--
-- 此前成品报损只有 production_batch_id (生产批次的产出额定值, 非可售库存) → approveDisposal
-- 走 422 安全阻止 ("成品报损暂不支持自动扣减库存"), 因无法从单个生产批次可靠定位唯一 FG 批次。
--
-- 本迁移新增 finished_goods_batch_id 列, 关联可售成品库存批次 (FinishedGoodsBatch.id):
--   1. 报损审批通过时真扣其可用量 (减 produced_quantity + 写 FinishedGoodsAdjustmentLog SCRAP 留痕);
--   2. 旧数据仅有 production_batch_id 无 finished_goods_batch_id → 仍走 422 安全阻止 (向后兼容)。
--
-- prod profile 用 ddl-auto=validate/none, 实体新列必须由本迁移创建 (dev=update 会自动建,
-- 故全部 IF NOT EXISTS 幂等)。

DO $$
BEGIN
    IF to_regclass('public.disposal_records') IS NULL THEN
        RAISE NOTICE 'V20261027_06 skipped: disposal_records not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE disposal_records
        ADD COLUMN IF NOT EXISTS finished_goods_batch_id VARCHAR(191);

    COMMENT ON COLUMN disposal_records.finished_goods_batch_id IS
        'R12: 成品报损关联的可售成品库存批次 (FinishedGoodsBatch) ID; 审批通过时扣减其可用量';

    CREATE INDEX IF NOT EXISTS idx_disposal_finished_goods_batch
        ON disposal_records(finished_goods_batch_id);
END $$;
