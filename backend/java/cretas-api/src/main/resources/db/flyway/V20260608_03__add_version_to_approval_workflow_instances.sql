-- V20260519_02: 加 version 列 — JPA @Version 乐观锁
--
-- 来源: PR review (pr-review-toolkit:code-reviewer) issue #12 / Phase 1 P1 follow-up
-- 触发场景: 2 个 reviewer 同时点 APPROVE 同一 instance:
--   T1: reviewer A load instance (version=5)
--   T2: reviewer B load instance (version=5)
--   T3: A 写 history + advance workflow + UPDATE instance (version 5→6)
--   T4: B 写 history + advance workflow + UPDATE instance (version 5→? — 实际 stale)
--   结果: 两个 reviewer 都写了 history + workflow 双次推进 → 状态错乱.
--
-- 修复: 加 @Version 列, Hibernate 自动 WHERE version=? + UPDATE SET version=version+1.
--   T4 时 stale version → 0 rows updated → ObjectOptimisticLockingFailureException.
--   Service 层 (WorkflowEngineServiceImpl.transitionNode / cancel) catch 后抛
--   BusinessException 409 "并发审批冲突, 请刷新后重试".
--
-- 现有行: DEFAULT 0 兼容 (Hibernate 第一次 UPDATE 把 NULL/0 转 1 不会问题).
--
-- 设计文档: PR #5 review summary (Phase 1 latent bugs)

ALTER TABLE approval_workflow_instances
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN approval_workflow_instances.version IS 'JPA @Version 乐观锁 — 并发 APPROVE/CANCEL 冲突时抛 ObjectOptimisticLockingFailureException, Service 转 BusinessException 409. issue #12 修复.';
