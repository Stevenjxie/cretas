-- 撤销小结申请登记进统一 WorkflowEngine 审批中心列表 (镜像半成品盘点 semi_finished_stocktakes.workflow_instance_id)
--
-- 背景: 撤销小结 (interim_settle_reversal_request, V20261027_26 建) 之前只在独立"撤销审批"弹窗里审批,
--   不出现在统一 /workflow/instances (审批中心) 列表。本迁移加可空 workflow_instance_id 列, 让撤销申请
--   在创建时登记一个 INVENTORY_ADJUSTMENT workflow 实例 (与半成品盘点完全一致的做法), 从而与盘点等审批
--   同列展示; 审批/驳回时驱动该实例到终态使其离开待审列表。
--
-- additive + nullable: 无 active INVENTORY_ADJUSTMENT workflow 配置时该列保持 NULL (graceful degradation,
--   申请仍正常创建/审批)。
-- prod ddl-auto=validate/none → 实体新列必须由本迁移创建, 否则启动校验失败。
-- ADD COLUMN IF NOT EXISTS: 防 fresh-DB 上 Flyway 先于/晚于 Hibernate ddl-auto 的竞态 (幂等)。
ALTER TABLE interim_settle_reversal_request
    ADD COLUMN IF NOT EXISTS workflow_instance_id VARCHAR(191);

COMMENT ON COLUMN interim_settle_reversal_request.workflow_instance_id IS
    '复用 INVENTORY_ADJUSTMENT workflow 实例 ID — 撤销申请登记进统一审批中心列表 (可空: 无 active workflow 时不登记)';
