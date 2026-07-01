-- 半成品盘点 review 修复 (Fix 1 + Fix 3)
--
-- Fix 1 (禁止降级, 保住凭证+成本不变式):
--   半成品盘点生效不得改 produced_quantity / consumed_quantity / accumulated_cost / unit_cost
--   (它们喂 VoucherExportServiceImpl 半成品入库/发出凭证 + unit_cost 分母)。
--   available_quantity 在本系统是"派生-on-mutation"字段 (每次 IN/OUT 由 produced−consumed 重算),
--   直接改会被后续报工/领用重算覆盖。故引入 adjustment_quantity 独立修正列:
--     available_quantity = produced_quantity − consumed_quantity + adjustment_quantity
--   盘点差异只动 adjustment_quantity, produced/consumed/成本 全部不动 → 凭证+成本不变式保住。
--   所有 available_quantity 重算站点 (WIP moving-average / yield / reversal rebuild) 纳入本列。
--   default 0 → 存量行 + 非盘点行行为字节不变。
ALTER TABLE semi_finished_inventory
    ADD COLUMN IF NOT EXISTS adjustment_quantity NUMERIC(12, 2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN semi_finished_inventory.adjustment_quantity IS
    '盘点净修正量 (可正可负); available = produced − consumed + adjustment; 盘盈/盘亏差异存于此, 不动 produced/consumed/成本';

-- Fix 3 (@Version 防重复过账):
--   两个并发 /apply (双击 或 REST 与 workflow callback 竞争) 都过 APPLIED 检查 → 重复 ADJUST 流水。
--   给盘点单加乐观锁: 第二个事务 save 时 version 冲突 → 回滚 (含其 ADJUST 流水)。
ALTER TABLE semi_finished_stocktakes
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
