-- Canvas-P3 Module 3 — HR Insurance UI wrap (2026-05-22).
--
-- 背景: hr_insurance_configs 表已存 (V20260606_22__hr_insurance_configs.sql).
-- HrInsuranceConfig entity 已存 (entity/hr/HrInsuranceConfig.java), 无 @Version.
-- 任务: 加 JPA @Version 乐观锁字段供 Canvas controller 使用.
--
-- API: /api/mobile/{factoryId}/canvas-hr-insurance
-- Vue: web-admin/src/views/platform/hr-insurance-editor/index.vue

-- 增加 JPA @Version 乐观锁字段
ALTER TABLE hr_insurance_configs
    ADD COLUMN IF NOT EXISTS opt_lock_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN hr_insurance_configs.opt_lock_version IS
    'Canvas-P3: JPA @Version 乐观锁字段 (per AUD-4 P1)';
