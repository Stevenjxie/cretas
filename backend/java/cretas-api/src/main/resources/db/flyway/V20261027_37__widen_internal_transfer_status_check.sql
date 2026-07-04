-- 撤销小结 (interim-settle reversal) after 同厂调拨 500 根因:
-- internal_transfers 的 ck_it_status CHECK 只允许 8 类
-- ('DRAFT','REQUESTED','APPROVED','REJECTED','SHIPPED','RECEIVED','CONFIRMED','CANCELLED')
-- (来源: legacy database/p3_transfer_pricelist_pg.sql, 非 Flyway 迁移, 手工建于 prod)。
-- #1218 给 TransferStatus 补了 REVERSED 枚举 (仅同厂调拨 source==target 的 CONFIRMED 调拨,
-- 在其 TRF-child 成品批次被撤销小结整批退回归零时置入), 并在
-- FinishedGoodsFeedServiceImpl.reverseInterimCreate 里 setStatus(TransferStatus.REVERSED),
-- 但 DB CHECK 未同步加宽 → 该 setStatus 触发 ck_it_status 违反 → 因撤销小结是 @Transactional,
-- 整个撤销事务 (含 SFI/FG 逆转 + 消耗恢复) 回滚 → 500 → RE-BRICKS #1214 刚解封的撤销小结路径,
-- 但仅当 FG 曾被同厂调拨部分搬走时才触发。
-- ⚠️ TransferStatus.java 注释曾误称 status 列 "无 DB CHECK 约束 (纯 VARCHAR(32))" — 事实错误,
-- prod 确有具名约束 ck_it_status (已在 prod PG 确认, 2026-07-04)。这正是本 repo 第 4 次
-- "枚举加了但 DB CHECK 未加宽" 模式 (同 V20261027_15/29/34/35 的教训: voucher / ck_it_type / ck_aat_type)。
-- 单测 mock repo / H2 均照不到该 PG-only 具名约束, 仅 prod PG 才暴。
--
-- 修法: DROP + 重建 ck_it_status, 含 TransferStatus 枚举全部 9 个值。幂等 (DROP IF EXISTS + ADD)。
-- 顺带 drop 可能存在的 Hibernate 自动命名约束 (防某些环境用了默认名)。additive, 不改数据。

ALTER TABLE internal_transfers DROP CONSTRAINT IF EXISTS ck_it_status;
ALTER TABLE internal_transfers DROP CONSTRAINT IF EXISTS internal_transfers_status_check;

ALTER TABLE internal_transfers
    ADD CONSTRAINT ck_it_status CHECK (status IN (
        'DRAFT',
        'REQUESTED',
        'APPROVED',
        'REJECTED',
        'SHIPPED',
        'RECEIVED',
        'CONFIRMED',
        'CANCELLED',
        'REVERSED'
    ));
