-- ============================================================
-- Sprint 6 W2-C-1: 微信记录 (wechat_records) table.
--
-- 背景: HJ Round 11 §F.2 finding — Customer 详情 "微信记录" tab.
-- F006 业务员场景: 跟客户微信聊单进度, 手工补录到 CRM (微信 OAuth
-- 第三方限制, 手工补录最实际).
--
-- Entity: com.cretas.aims.entity.WechatRecord
-- Direction enum: INBOUND / OUTBOUND / INTERNAL (com.cretas.aims.entity.enums.WechatDirection)
--
-- 镜像 customer_tracking_records (V20260330_01) 结构. 差异:
-- - direction enum 列 (NOT NULL, CHECK 白名单)
-- - message_content NOT NULL (TEXT)
-- - 加 idx_wechat_record_time 索引 (时间范围查询)
-- ============================================================

CREATE TABLE IF NOT EXISTS wechat_records (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(191) NOT NULL,
    customer_id VARCHAR(191) NOT NULL,
    record_time TIMESTAMP NOT NULL DEFAULT NOW(),
    direction VARCHAR(20) NOT NULL,
    message_content TEXT NOT NULL,
    created_by BIGINT NOT NULL,
    created_by_name VARCHAR(100),
    contact_person VARCHAR(100),
    remark TEXT,
    created_at TIMESTAMP DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMP DEFAULT NOW() NOT NULL,
    deleted_at TIMESTAMP
);

-- direction 枚举白名单 (defense-in-depth, entity 层用 @Enumerated(EnumType.STRING))
ALTER TABLE wechat_records
    ADD CONSTRAINT chk_wechat_direction
    CHECK (direction IN ('INBOUND', 'OUTBOUND', 'INTERNAL'));

-- 索引: customer 详情主查询 + factory RLS + 时间范围
CREATE INDEX IF NOT EXISTS idx_wechat_customer
    ON wechat_records (customer_id);
CREATE INDEX IF NOT EXISTS idx_wechat_factory
    ON wechat_records (factory_id);
CREATE INDEX IF NOT EXISTS idx_wechat_record_time
    ON wechat_records (record_time DESC);

-- 复合索引: 列表 page 主查询 (factory_id + customer_id + 软删除 filter)
CREATE INDEX IF NOT EXISTS idx_wechat_factory_customer_time
    ON wechat_records (factory_id, customer_id, record_time DESC)
    WHERE deleted_at IS NULL;
