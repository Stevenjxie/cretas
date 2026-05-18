-- V20260519_01: 加 partial unique index — approval_workflow_instances 同时只允许 1 个 RUNNING 实例
--
-- 来源: PR review (pr-review-toolkit:code-reviewer) issue #11 / Phase 1 P1 follow-up
-- 触发场景: 同一 (factory_id, module_code, business_entity_id) 反复 cancel + 重新审批
--           → 多行 status='RUNNING' 残留 → ApprovalWorkflowInstanceRepository
--             .findByFactoryIdAndModuleCodeAndBusinessEntityId 返 Optional<> 假设 0-1 行
--           → 实际命中 2+ 行抛 IncorrectResultSizeDataAccessException.
--
-- 设计:
--   * Partial unique — 只约束 RUNNING (status='RUNNING' AND deleted_at IS NULL)
--   * 终态历史 (APPROVED/REJECTED/CANCELLED/TIMEOUT) 不受影响, 同一 entity 可有多行历史
--   * Cancel + 再 start: 旧实例 status=CANCELLED 后退出唯一约束, 新实例可以 INSERT
--
-- 与 idx_aw_instances_business_entity 关系:
--   原 idx 是 NON-unique (查询用), 本 idx 是 unique (约束用), 不冲突, 两个都保留.
--
-- 注意: 本 migration 不应 fail on existing RUNNING dup rows (production 至 2026-05-19 暂无 dup
-- 报告). 若 deploy 时实际命中 dup → flyway 报错, 手动清理后重跑.
--
-- 设计文档: PR #5 review summary (Phase 1 latent bugs)

CREATE UNIQUE INDEX IF NOT EXISTS uq_aw_instances_active
    ON approval_workflow_instances (factory_id, module_code, business_entity_id)
    WHERE status = 'RUNNING' AND deleted_at IS NULL;

COMMENT ON INDEX uq_aw_instances_active IS
    'Partial unique — 同一 (factory, module, business entity) 只允许 1 个 RUNNING 实例. '
    || '防御 cancel+resubmit 残留 dup 行触发 IncorrectResultSizeDataAccessException. '
    || '终态实例 (APPROVED/REJECTED/CANCELLED/TIMEOUT) 不受约束, 可有多行历史.';
