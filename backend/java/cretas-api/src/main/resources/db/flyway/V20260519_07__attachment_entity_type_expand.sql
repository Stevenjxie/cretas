-- V20260519_07: Sprint 6 W3-A — 扩 attachments.entity_type 加 'SALES_ORDER' 和 'INVENTORY' 值
--
-- 业务背景 (per dispatch §W3-A + spec §3.4):
-- 4 list (sales/PO/inventory/voucher) inline link counter `文件(N) 图片(N) 合同(N)`
-- 3-chip 显示. 现有 EntityType whitelist 已含 PURCHASE_ORDER / INVOICE / PAYMENT_VOUCHER
-- 但缺 SALES_ORDER 和 INVENTORY.
--
-- SALES_ORDER: 销售订单挂载合同 / 发货证明 / 客户签字
-- INVENTORY: 库存批次挂载入库单据 / 抄码单 / 质检证明
--
-- 这条 migration 仅扩 enum + CHECK constraint, 不改 schema 结构.
--
-- 关联 Java 改动 (本 PR 同步): entity/Attachment.java EntityType enum 加
-- SALES_ORDER 和 INVENTORY.
--
-- Sprint 5 H-1 spec §3.4 checklist:
--   - [x] (本 spec) 确保 SALES_ORDER / PURCHASE_ORDER / INVENTORY / VOUCHER 都在白名单
--   - [x] (本 spec) 加 migration 扩 chk_att_entity_type CHECK + Java EntityType enum
--   - [ ] (本 PR) 后端 SalesOrderLinkCountsDTO 加 3-chip 字段 + grouped count repository
--   - [ ] (本 PR) Frontend 4 list 3-chip 行内显示

ALTER TABLE attachments
    DROP CONSTRAINT chk_att_entity_type;

ALTER TABLE attachments
    ADD CONSTRAINT chk_att_entity_type CHECK (entity_type IN (
        'CUSTOMER', 'CUSTOMER_TRACKING', 'PURCHASE_ORDER', 'PURCHASE_RECEIPT',
        'QUALITY_CHECK', 'PRODUCTION_BATCH', 'PAYMENT_VOUCHER', 'INVOICE',
        'RD_SAMPLE', 'RECEIPT', 'RETURN_ORDER', 'SHIPMENT',
        'WASTAGE_RECORD', 'GROUP_LEADER_REPORT', 'EXPENSE_REPORT',
        'LEAVE_REQUEST', 'TIMECLOCK_PHOTO',
        'SALES_ORDER', 'INVENTORY',
        'GENERIC'
    ));

COMMENT ON COLUMN attachments.entity_type IS '关联实体类型, 白名单约束 (CHECK chk_att_entity_type) 与 Java Attachment.EntityType 枚举同步. Sprint 6 W3-A (2026-05-19) 加 SALES_ORDER 和 INVENTORY 用于 inline link counter 4 list 3-chip 显示.';
