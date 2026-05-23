-- Canvas-P3 Module 1 — Supplier Admission UI wrap (2026-05-22).
--
-- 背景: P3 半-Canvas-ed — Supplier entity 已存, 仅做 UI wrap.
-- "供应商准入" 概念在 Cretas 不是独立实体 — 它是 Supplier 行的几个字段:
--   - is_active        BOOLEAN  (准入主开关; false = 暂停准入)
--   - rating           INTEGER  (等级 1-5, 1=最高)
--   - rating_notes     TEXT     (准入备注, 多行)
--   - quality_certificates TEXT (资质证书摘要)
--   - business_license VARCHAR  (营业执照号)
--   - credit_level     VARCHAR  (信用等级 A/B/C)
--
-- 本迁移作用:
--   1. Supplier 已经有 @Version (BIGINT NOT NULL, see Supplier.java line 113-115) — 不动
--   2. 加入 "admission_status" / "admission_reviewed_at" / "admission_reviewer_id" 准入 metadata
--   3. 为 admission_status 加 CHECK 约束 (PENDING/APPROVED/REJECTED/SUSPENDED)
--
-- API: /api/mobile/{factoryId}/canvas-supplier-admission
-- Vue: web-admin/src/views/platform/supplier-admission-editor/index.vue

-- 准入审核状态 (PENDING=待审 / APPROVED=已准入 / REJECTED=拒绝 / SUSPENDED=暂停)
ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS admission_status VARCHAR(20) DEFAULT 'APPROVED';

-- 准入审核时间
ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS admission_reviewed_at TIMESTAMP;

-- 准入审核人 (User.id)
ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS admission_reviewer_id BIGINT;

-- 约束: admission_status 只能是 4 个允许值之一
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_supplier_admission_status'
    ) THEN
        ALTER TABLE suppliers
            ADD CONSTRAINT chk_supplier_admission_status
            CHECK (admission_status IN ('PENDING','APPROVED','REJECTED','SUSPENDED'));
    END IF;
END
$$;

-- Index: 按 (factory_id, admission_status) 快速过滤
CREATE INDEX IF NOT EXISTS idx_supplier_admission_status
    ON suppliers (factory_id, admission_status)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN suppliers.admission_status IS
    'Canvas-P3: 准入审核状态 PENDING/APPROVED/REJECTED/SUSPENDED (默认 APPROVED 兼容历史数据)';
COMMENT ON COLUMN suppliers.admission_reviewed_at IS
    'Canvas-P3: 准入审核时间 (最近一次 admission_status 变更时刻)';
COMMENT ON COLUMN suppliers.admission_reviewer_id IS
    'Canvas-P3: 准入审核人 user_id';

-- 历史行 backfill: 全部置 APPROVED (兼容原始 isActive=true 行)
UPDATE suppliers
   SET admission_status = COALESCE(admission_status, 'APPROVED')
 WHERE admission_status IS NULL;
