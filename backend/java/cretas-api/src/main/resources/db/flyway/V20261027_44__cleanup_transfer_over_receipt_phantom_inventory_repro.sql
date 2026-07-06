-- 🔴 数据清理: 调拨签收超收上限守卫 bug 复现测试造出的幽灵库存 (F006 测试租户)。
--
-- 背景: receiveTransfer 对 itemActualQuantities 逐行填入 receivedQuantity 时旧代码无任何
--   上限校验; confirmTransfer → createTargetInventory 直接按 receivedQuantity 建目标批次。
--   2026-07-06 复现验证: 调拨单 TRF-20260706-7944 (F006 → F006 同厂调拨) 发运 5kg,
--   签收时传 itemActualQuantities=500 (100 倍) → 200 接受, 目标仓凭空建 500kg 批次,
--   源仓只按发运量扣减 5kg → 净空造 495kg (¥4950 幽灵资产), 且 TransferDiffServiceImpl
--   旧逻辑只检测"少收"方向, 零差异单, 完全静默。
--
--   代码修复见本 PR 对 TransferServiceImpl.receiveTransfer 的改动 (新增
--   validateReceivedNotExceedingShipped 守卫, 签收时逐行校验 receivedQuantity ≤
--   shipped × (1+2%容忍), 超出直接 409 拒绝, 不建立超量状态)。
--
-- 只读核实 (2026-07-06, cretas_prod_db):
--   material_batches id=612f9592-784e-4f1f-8844-9f0fb7303825: factory_id=F006,
--     material_type_id=RMT_1777690082465, receipt_quantity=500.00, used_quantity=0.00,
--     reserved_quantity=0.00, status=AVAILABLE, deleted_at IS NULL (未被下游消耗/预留过,
--     可安全软删)。
--   internal_transfers id=98788f75-3496-4c3f-8cbd-75f3d7e16bf1 (transfer_number
--     TRF-20260706-7944): source_factory_id=F006, target_factory_id=F006, status=CONFIRMED,
--     deleted_at IS NULL。
--   internal_transfer_items id=113: transfer_id 同上, quantity(发运)=5.0000,
--     received_quantity=500.0000, source_batch_id=177a3d6f-e06f-4323-91b2-e5804d9c797d
--     (调出批次, 已 DEPLETED, 本迁移不动), target_batch_id=612f9592-...
--   transfer_diff_records: 该 transfer_id 下 0 行 (旧逻辑对多收方向不生成差异单, 与预期一致)。
--
-- 清理方式: 软删除 (deleted_at=NOW(), 与 MaterialBatch/InternalTransfer/InternalTransferItem
--   实体 @Where(clause="deleted_at IS NULL") 惯例一致, 不 hard DELETE 破外键/审计留痕)。
--   三张表严格 id 硬编码 + factory_id='F006' 双重限定 + 仅当仍处于本次复现造成的具体状态时
--   才软删 (幂等, 重跑无副作用, 绝不误碰 LIUSHANMEN 或其他真实客户数据)。
--
-- Flyway 先于 Hibernate DDL 跑 (fresh CI DB), 三张表可能尚未建表 → to_regclass 守卫跳过。

DO $$
BEGIN
    IF to_regclass('public.material_batches') IS NULL
        OR to_regclass('public.internal_transfers') IS NULL
        OR to_regclass('public.internal_transfer_items') IS NULL THEN
        RAISE NOTICE 'V20261027_44 skipped: transfer/material_batches tables not present before Hibernate DDL';
        RETURN;
    END IF;

    -- 1) 目标仓幽灵批次 (500kg, 应为 5kg)
    UPDATE material_batches
       SET deleted_at = now(),
           updated_at = now()
     WHERE id = '612f9592-784e-4f1f-8844-9f0fb7303825'
       AND factory_id = 'F006'
       AND receipt_quantity = 500.00
       AND used_quantity = 0.00
       AND COALESCE(reserved_quantity, 0) = 0.00
       AND deleted_at IS NULL;

    -- 2) 调拨行项目 (发运5→实收500 的复现行)
    UPDATE internal_transfer_items
       SET deleted_at = now(),
           updated_at = now()
     WHERE id = 113
       AND transfer_id = '98788f75-3496-4c3f-8cbd-75f3d7e16bf1'
       AND quantity = 5.0000
       AND received_quantity = 500.0000
       AND target_batch_id = '612f9592-784e-4f1f-8844-9f0fb7303825'
       AND deleted_at IS NULL;

    -- 3) 调拨单本身 (TRF-20260706-7944)
    UPDATE internal_transfers
       SET deleted_at = now(),
           updated_at = now()
     WHERE id = '98788f75-3496-4c3f-8cbd-75f3d7e16bf1'
       AND transfer_number = 'TRF-20260706-7944'
       AND source_factory_id = 'F006'
       AND target_factory_id = 'F006'
       AND status = 'CONFIRMED'
       AND deleted_at IS NULL;
END $$;
