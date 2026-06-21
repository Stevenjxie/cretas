-- F-BUG-2: 报废审批状态机 — 区分 PENDING / APPROVED / REJECTED。
--
-- 此前 disposal_records 只有 is_approved 布尔, 无法持久化 "已拒绝" 状态;
-- 前端 list.vue 读 row.status (PENDING/APPROVED/REJECTED) 决定按钮显示与状态标签,
-- 后端从不序列化 status → 永远 undefined。新增显式 approval_status 列 + reject_reason。
--
-- prod profile 用 ddl-auto=validate/none, 实体新列必须由本迁移创建 (dev=update 会自动建,
-- 故全部 IF NOT EXISTS 幂等)。

DO $$
BEGIN
    IF to_regclass('public.disposal_records') IS NULL THEN
        RAISE NOTICE 'V20261026_05 skipped: disposal_records not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE disposal_records
        ADD COLUMN IF NOT EXISTS approval_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';

    ALTER TABLE disposal_records
        ADD COLUMN IF NOT EXISTS reject_reason TEXT;

    -- 历史行回填: 已审批的标 APPROVED, 其余保持 PENDING (DEFAULT 已处理新行)。
    UPDATE disposal_records
        SET approval_status = 'APPROVED'
        WHERE is_approved = true AND (approval_status IS NULL OR approval_status = 'PENDING');

    COMMENT ON COLUMN disposal_records.approval_status IS
        'F-BUG-2: 审批状态 PENDING/APPROVED/REJECTED';
    COMMENT ON COLUMN disposal_records.reject_reason IS
        'F-BUG-2: 拒绝原因 (仅 REJECTED 时有值)';

    CREATE INDEX IF NOT EXISTS idx_disposal_approval_status
        ON disposal_records(approval_status);
END $$;
