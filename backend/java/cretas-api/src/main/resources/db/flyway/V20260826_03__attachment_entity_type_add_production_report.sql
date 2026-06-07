-- 六扇门真实报工证据: 允许照片 / 视频挂到单条 production_reports 记录.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'chk_att_entity_type'
          AND table_name = 'attachments'
    ) THEN
        ALTER TABLE attachments DROP CONSTRAINT chk_att_entity_type;
    END IF;
END $$;

ALTER TABLE attachments
    ADD CONSTRAINT chk_att_entity_type CHECK (entity_type IN (
        'CUSTOMER',
        'CUSTOMER_TRACKING',
        'PURCHASE_ORDER',
        'PURCHASE_RECEIPT',
        'QUALITY_CHECK',
        'PRODUCTION_BATCH',
        'PAYMENT_VOUCHER',
        'INVOICE',
        'RD_SAMPLE',
        'RECEIPT',
        'RETURN_ORDER',
        'SHIPMENT',
        'WASTAGE_RECORD',
        'GROUP_LEADER_REPORT',
        'EXPENSE_REPORT',
        'LEAVE_REQUEST',
        'TIMECLOCK_PHOTO',
        'SALES_ORDER',
        'INVENTORY',
        'ECN',
        'CALL_RECORD',
        'PRODUCTION_REPORT',
        'GENERIC'
    ));

COMMENT ON COLUMN attachments.entity_type IS '关联实体类型, 白名单约束 (CHECK chk_att_entity_type) 与 Java Attachment.EntityType 枚举同步. 加 PRODUCTION_REPORT 用于单次报工照片/视频证据.';
