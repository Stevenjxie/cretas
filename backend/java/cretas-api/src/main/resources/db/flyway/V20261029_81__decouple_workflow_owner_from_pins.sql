-- 把 product_type_id 从剩下三条复合 FK 里摘掉, 改引用不含它的工艺图身份键。
--
-- 范式来自 V20261028_83(production_batches), 文件头原话:
--   "Those output SKUs need not equal the Workflow owner SKU, so the batch FK must protect
--    Workflow tenant/version identity without incorrectly coupling it to the output batch product."
-- 同一个坑当时只修了 batches 这一条链, activations / instances / plans 三条留着 ——
-- 于是「一张工艺图的产出必须等于它的存储锚点(owner SKU)」这条隐含约束仍然由 FK 强制着。
-- 本迁移把同一个形状套到剩下三条链上: FK 仍然保护租户(factory_id)与版本(definition_version)身份,
-- 但不再要求引用方的 product_type_id 等于工艺图的 owner SKU。
--
-- 引用目标:
--   uk_ppw_id_factory_version (id, factory_id, definition_version)  已存在(V20261028_83 建的)
--   uk_ppw_id_factory         (id, factory_id)                      不存在 —— 本迁移新建,
--     供 fk_pwi_workflow_owner 使用(那条 FK 只有 3 列且不含 definition_version)。

ALTER TABLE product_process_workflows
  ADD CONSTRAINT uk_ppw_id_factory
    UNIQUE (id, factory_id);

ALTER TABLE product_process_workflow_activations
  DROP CONSTRAINT fk_ppwa_active_workflow_owner,
  ADD CONSTRAINT fk_ppwa_active_workflow_owner
    FOREIGN KEY (
      active_workflow_id,
      factory_id,
      active_definition_version
    )
    REFERENCES product_process_workflows(
      id,
      factory_id,
      definition_version
    );

ALTER TABLE production_plans
  DROP CONSTRAINT fk_production_plan_selected_workflow,
  ADD CONSTRAINT fk_production_plan_selected_workflow
    FOREIGN KEY (
      selected_workflow_id,
      factory_id,
      selected_workflow_version
    )
    REFERENCES product_process_workflows(
      id,
      factory_id,
      definition_version
    );

-- ⚠️ 表名是 production_workflow_instances(不是 product_workflow_instances)。
ALTER TABLE production_workflow_instances
  DROP CONSTRAINT fk_pwi_workflow_owner,
  ADD CONSTRAINT fk_pwi_workflow_owner
    FOREIGN KEY (
      workflow_id,
      factory_id
    )
    REFERENCES product_process_workflows(
      id,
      factory_id
    );
