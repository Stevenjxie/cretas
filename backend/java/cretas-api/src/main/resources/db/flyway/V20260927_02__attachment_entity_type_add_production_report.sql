-- Add PRODUCTION_REPORT so RN process reports can store on-site photo/video evidence.
-- This migration also reconciles older entity type additions that share one CHECK constraint.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE table_name = 'attachments'
          AND constraint_name = 'chk_att_entity_type'
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

COMMENT ON COLUMN attachments.entity_type IS 'Attachment entity type whitelist synchronized with Java Attachment.EntityType. Adds PRODUCTION_REPORT for process report photo/video evidence.';
