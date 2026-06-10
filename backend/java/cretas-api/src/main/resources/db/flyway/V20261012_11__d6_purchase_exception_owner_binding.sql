-- D-6 入库异常责任绑定：给 purchase_exceptions 表加责任人字段
-- owner_user_id  — 责任采购人的 userId（来自 PurchaseOrder.created_by）
-- owner_name     — 责任人用户名（冗余展示用，避免联表查询）
ALTER TABLE purchase_exceptions
    ADD COLUMN IF NOT EXISTS owner_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS owner_name    VARCHAR(100);

-- 索引：便于按责任人查询待处理异常单
CREATE INDEX IF NOT EXISTS idx_pe_owner ON purchase_exceptions (owner_user_id)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN purchase_exceptions.owner_user_id IS 'D-6 责任绑定：源采购单创建人 userId，决策须为本人或主管角色';
COMMENT ON COLUMN purchase_exceptions.owner_name    IS 'D-6 责任人姓名（冗余存储）';
