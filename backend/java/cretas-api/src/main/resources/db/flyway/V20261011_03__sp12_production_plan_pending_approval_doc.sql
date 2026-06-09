-- SP12 T3: ProductionPlanStatus.PENDING_APPROVAL 文档迁移
-- 枚举值由 Java 代码管理 (ProductionPlanStatus.java)，无需 DDL
-- 此迁移仅作记录，标记 T3 workflow 接入的 DB-level 变更锚点

-- 文档注释: PENDING_APPROVAL 用于生产计划撤回/取消审批流程
-- 状态机: COMPLETED → PENDING_APPROVAL (生产撤回申请) → CANCELLED (审批通过)
--                                                       → COMPLETED (审批拒绝，恢复原状)
-- 由 ProductionPlanServiceImpl.requestCancelWithApproval() 触发
-- 由 WorkflowEngineService.startWorkflow("PRODUCTION_REVERSAL") 驱动

SELECT 1; -- no-op DDL, Flyway needs at least one statement
