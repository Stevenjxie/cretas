-- ============================================================
-- Sprint 6 W2-C-2: 通话记录 (call_records) table + Attachment.EntityType extension.
--
-- 背景: HJ Round 11 §F.2 finding — Customer 详情 "通话记录" tab.
-- F006 卤制品销售场景: 电话沟通后上传录音 → Whisper 自动转写关键信息.
--
-- Entity: com.cretas.aims.entity.CallRecord
-- CallType enum: INCOMING / OUTGOING / MISSED (com.cretas.aims.entity.enums.CallType)
--
-- 镜像 wechat_records (V20260520_01) 结构. 差异:
-- - call_type enum + CHECK 白名单
-- - audio_oss_url / audio_filename / audio_size_bytes / audio_mime_type (录音元数据)
-- - transcript_text TEXT + transcript_status (PENDING / SUCCESS / FAILED)
-- - duration_sec 通话时长 (秒)
-- - contact_name / contact_phone 客户联系人
--
-- 同时扩展 Attachment.EntityType CHECK 加入 CALL_RECORD (录音附件可挂载).
-- ============================================================

CREATE TABLE IF NOT EXISTS call_records (
    id                 BIGSERIAL PRIMARY KEY,
    factory_id         VARCHAR(191) NOT NULL,
    customer_id        VARCHAR(191) NOT NULL,
    call_time          TIMESTAMP NOT NULL DEFAULT NOW(),
    duration_sec       INTEGER,
    call_type          VARCHAR(16) NOT NULL,
    contact_name       VARCHAR(100),
    contact_phone      VARCHAR(32),
    audio_oss_url      VARCHAR(1000),
    audio_filename     VARCHAR(255),
    audio_size_bytes   BIGINT,
    audio_mime_type    VARCHAR(64),
    transcript_text    TEXT,
    transcript_status  VARCHAR(16),
    remark             TEXT,
    created_by         BIGINT NOT NULL,
    created_by_name    VARCHAR(100),
    created_at         TIMESTAMP DEFAULT NOW() NOT NULL,
    updated_at         TIMESTAMP DEFAULT NOW() NOT NULL,
    deleted_at         TIMESTAMP
);

-- call_type 白名单 (defense-in-depth, entity 层用 @Enumerated(EnumType.STRING))
ALTER TABLE call_records
    ADD CONSTRAINT chk_call_record_type
    CHECK (call_type IN ('INCOMING', 'OUTGOING', 'MISSED'));

-- transcript_status 白名单 (允许 NULL = 未触发转写)
ALTER TABLE call_records
    ADD CONSTRAINT chk_call_record_transcript_status
    CHECK (transcript_status IS NULL OR transcript_status IN ('PENDING', 'SUCCESS', 'FAILED'));

-- 主索引 (customer 详情查询 + factory RLS + 时间范围)
CREATE INDEX IF NOT EXISTS idx_call_record_customer ON call_records (customer_id);
CREATE INDEX IF NOT EXISTS idx_call_record_factory ON call_records (factory_id);
CREATE INDEX IF NOT EXISTS idx_call_record_call_time ON call_records (call_time DESC);

-- 复合索引: 列表主查询 (factory_id + customer_id + 软删过滤)
CREATE INDEX IF NOT EXISTS idx_call_record_factory_customer_time
    ON call_records (factory_id, customer_id, call_time DESC)
    WHERE deleted_at IS NULL;

-- 转写状态部分索引 — 用于 cron / batch retry FAILED 转写
CREATE INDEX IF NOT EXISTS idx_call_record_pending_transcript
    ON call_records (transcript_status)
    WHERE transcript_status = 'PENDING' AND deleted_at IS NULL;

-- ============================================================
-- Attachment.EntityType extension — 加 CALL_RECORD
--
-- 历史 chk_att_entity_type constraint 由 V20260515_01 等 migration 创建.
-- 多次 PR 已加 SALES_ORDER / INVENTORY (Sprint 6 W3-A, EntityType.java line 134-137).
-- 本次加 CALL_RECORD 跟随同模式: DROP + RECREATE constraint with extended whitelist.
--
-- 注意: 如果 W4-C-bom-batch-ecn 也同期加 ECN 到 EntityType, 后 merge 的 PR 需要 reconcile
-- 这个 constraint (V20260520_xx 命名空间不冲突, 但 chk_att_entity_type 是单个 constraint).
-- 解决方案: 后 merge 的 PR 在自己的 migration 里 ALTER DROP + ADD 加 ECN 即可.
-- ============================================================

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
    ADD CONSTRAINT chk_att_entity_type
    CHECK (entity_type IN (
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
        'CALL_RECORD',
        'GENERIC'
    ));
