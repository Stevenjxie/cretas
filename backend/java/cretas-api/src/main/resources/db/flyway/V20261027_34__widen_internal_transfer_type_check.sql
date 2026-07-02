-- 领料→生产仓调拨 400/409 根因: internal_transfers 的 ck_it_type CHECK 只允许 3 类
-- ('HQ_TO_BRANCH','BRANCH_TO_BRANCH','BRANCH_TO_HQ') (来源: legacy database/p3_transfer_pricelist_pg.sql
-- line 35, 非 Flyway 迁移, 手工建于 prod)。#1171 给 TransferType 补了 WAREHOUSE_TO_WAREHOUSE 枚举
-- (Java 合法), 但 DB CHECK 未同步加宽 → FactoryMaterialRequisitionServiceImpl.transferToFactory 里
-- transferService.createTransfer(..) 插 InternalTransfer 审计凭证时触发 ck_it_type 违反 →
-- 因 transferToFactory 是 @Transactional, 整事务 (含已完成的 WH-LOG→WH-WKS 物理移库) 回滚 →
-- 零库存移动 + 409。同 V20261027_15/V20261027_29 (枚举加了但 DB CHECK 未加宽) 的教训。
-- 单测 mock repo / H2 均照不到该 PG-only 具名约束, 仅 prod PG 才暴。
--
-- 修法: DROP + 重建 ck_it_type, 含全部 4 个合法值。幂等 (DROP IF EXISTS + ADD)。
-- 顺带 drop 可能存在的 Hibernate 自动命名约束 (防某些环境用了默认名)。

ALTER TABLE internal_transfers DROP CONSTRAINT IF EXISTS ck_it_type;
ALTER TABLE internal_transfers DROP CONSTRAINT IF EXISTS internal_transfers_transfer_type_check;

ALTER TABLE internal_transfers
    ADD CONSTRAINT ck_it_type CHECK (transfer_type IN (
        'HQ_TO_BRANCH',
        'BRANCH_TO_BRANCH',
        'BRANCH_TO_HQ',
        'WAREHOUSE_TO_WAREHOUSE'
    ));
