-- 🔴 数据订正: F006 关单退料漏判 DEPLETED → 库存永久冻结。
--
-- 背景: FactoryMaterialRequisitionServiceImpl.restoreBatchUsedQuantity 关单退料恢复
--   usedQuantity 后, 只在 status==USED_UP 时把状态翻回 AVAILABLE, 漏了 DEPLETED (两者同为
--   "耗尽终态", 见 ReportReversalServiceImpl.restoreMaterialBatchConsumption /
--   MaterialBatchServiceImpl.releaseBatchReservation 的同 pattern, 均判两个状态)。
--
--   代码修复见本 PR 对 restoreBatchUsedQuantity 的改动 (加 DEPLETED 判断)。
--   本迁移订正代码修复前已产生的历史遗留数据: 批次曾被打满 DEPLETED 后又退料, currentQuantity
--   (receipt−used−reserved) 已 > 0 但 status 仍卡在 DEPLETED — 所有下游 FEFO/FIFO 查询硬编码
--   status='AVAILABLE', 捞不到这些批次 → 库存永久冻结不可用。
--
-- 只读核实 (2026-07-06, cretas_prod_db): 全表扫描 status='DEPLETED' AND
--   (receipt_quantity-used_quantity-COALESCE(reserved_quantity,0))>0, 命中 10 行, 全部
--   factory_id='F006' (0 行属于 LIUSHANMEN 或其他租户), 残余合计 67.40 (kg)。
--
-- 修复范围: 严格 id 硬编码 (10 个) + factory_id='F006' 双重限定 + 仅当仍满足
--   (status='DEPLETED' AND residual>0) 才翻转 → 幂等, 绝不误碰其他状态 / 其他工厂 /
--   LIUSHANMEN 真实客户数据。不改动 usedQuantity/receiptQuantity/reservedQuantity 任何数值,
--   只翻转 status 字段 (数量本身已经是对的, 错的只是状态没跟着现存量走)。
--
-- Flyway 先于 Hibernate DDL 跑 (fresh CI DB), material_batches 可能尚未建表 → to_regclass 守卫跳过。

DO $$
BEGIN
    IF to_regclass('public.material_batches') IS NULL THEN
        RAISE NOTICE 'V20261027_43 skipped: material_batches not present before Hibernate DDL';
        RETURN;
    END IF;

    UPDATE material_batches mb
       SET status = 'AVAILABLE',
           updated_at = now()
     WHERE mb.id IN (
             '2915ad61-a0ee-42c4-b061-7584543f5931',
             '0d4c986c-6bfb-4228-b8fa-77364f4b461e',
             '8e6ff467-472f-4b05-8e7c-9de261db8af2',
             '5def31c3-9650-47e1-9d0f-563b2a992049',
             '2ac6b560-4f2c-4e67-bf9a-609773d4ca49',
             '593713c5-d07b-4025-8d12-f588731bfa50',
             '177a3d6f-e06f-4323-91b2-e5804d9c797d',
             '1a954ae3-1afc-47bb-8471-0eb323c8e705',
             '2223788b-7186-4e9b-872b-17bfe91928df',
             '22f08e8e-ff1e-4054-b38b-5c02a522a694')
       AND mb.factory_id = 'F006'
       AND mb.status = 'DEPLETED'
       AND (mb.receipt_quantity - COALESCE(mb.used_quantity, 0) - COALESCE(mb.reserved_quantity, 0)) > 0;
END $$;
