-- V20260625_01: Sprint 5 Track A — personal view query indexes
--
-- 添加 (factory_id, initiated_by) 复合索引, 给 "我创建的工作流" personal view
-- 查询 (findByFactoryIdAndInitiatedByOrderByInitiatedAtDesc) 提供命中.
-- 不加 (initiated_at DESC) 因为 PG B-tree 反向扫描跟正向同效率.
--
-- "我参与的工作流" 通过 ApprovalHistory join — 已有索引
-- idx_approval_history_factory_action_time (factory_id, action, created_at)
-- 覆盖范围尚可. 若 actor_id 高基数性能不够, 后续 phase 加
-- (factory_id, actor_id, created_at) 复合索引.
--
-- 设计文档: docs/superpowers/plans/2026-05-19-sprint-5-dispatch.md §A

CREATE INDEX IF NOT EXISTS idx_aw_instances_initiated_by
    ON approval_workflow_instances (factory_id, initiated_by)
    WHERE deleted_at IS NULL AND initiated_by IS NOT NULL;

COMMENT ON INDEX idx_aw_instances_initiated_by IS
    'Sprint 5 Track A personal view — speeds up findCreatedBy queries (我创建的工作流).';
