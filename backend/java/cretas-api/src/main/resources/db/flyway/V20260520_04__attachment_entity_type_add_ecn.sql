-- V20260520_04: Sprint 6 W4-C — 扩 attachments.entity_type 加 'ECN' 值
--
-- 业务背景 (per dispatch §W4-C):
-- ECN (Engineering Change Notice) 需要挂载附件 — 变更说明 / 影响分析报告 / 客户确认书 / OSS
-- 录音 (electronic signature 备份). 现有 chk_att_entity_type 白名单缺 ECN 值.
--
-- 这条 migration 仅扩 enum + CHECK constraint, 不改 schema 结构.
--
-- 关联 Java 改动 (本 PR 同步): entity/Attachment.java EntityType enum 加 ECN.
--
-- Spec §W4-C Day 2 ECN paginated list + impact report:
--   - [x] (本 spec) 确保 ECN 在白名单
--   - [x] (本 spec) 加 migration 扩 chk_att_entity_type CHECK + Java EntityType enum
--   - [ ] (本 PR) Backend ECN paginated list endpoint
--   - [ ] (本 PR) Frontend EcnList.vue paginated + impact report dialog

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
        'ECN',
        'GENERIC'
    ));

COMMENT ON COLUMN attachments.entity_type IS '关联实体类型, 白名单约束 (CHECK chk_att_entity_type) 与 Java Attachment.EntityType 枚举同步. Sprint 6 W4-C (2026-05-20) 加 ECN 用于 ECN 工程变更通知附件.';
