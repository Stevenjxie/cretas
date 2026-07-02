-- 盘点批量导入模式列 (张权客户需求 2026-07-02: 月度整仓盘点 + 期初建账导入)
-- additive-only: 逐项 UI 发起的盘点 import_mode = NULL (历史行为不变, apply 不过账损益凭证)
--   NORMAL  — 批量导入常规盘点: apply 过账 盘盈(借1403原材料/贷6301营业外收入) / 盘亏(借6602管理费用/贷1403)
--   OPENING — 批量导入期初建账: apply 过账 借1403原材料/贷4001实收资本 (不进6301, 避免虚增当期损益)
-- 注: 原料盘点历史上不过账凭证 (仅半成品盘点过账); 本列驱动的过账仅对批量导入创建的任务生效。
ALTER TABLE factory_stocktakes
    ADD COLUMN IF NOT EXISTS import_mode VARCHAR(20);

COMMENT ON COLUMN factory_stocktakes.import_mode IS
    '批量导入模式: NORMAL(常规盘点过账盘盈盘亏) / OPENING(期初建账过账实收资本); NULL=逐项UI盘点(不过账)';
