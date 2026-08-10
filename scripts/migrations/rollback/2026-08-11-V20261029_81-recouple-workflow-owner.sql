-- 回滚 V20261029_81__decouple_workflow_owner_from_pins.sql
--
-- ⛔ 本文件【不能】放进 backend/java/cretas-api/src/main/resources/db/flyway/ ——
--    那个目录按 glob 自动执行, 把回滚脚本放进去等于给自己写了一条撤销自己的迁移
--    (2026-08-08 真事故)。放在 scripts/migrations/rollback/ 下, 只在人工决定回滚时手工执行。
--
-- 用法(先在克隆库上跑往返验证, 再考虑 prod):
--   su - postgres -c "psql -v ON_ERROR_STOP=1 -d <db> -f 2026-08-11-V20261029_81-recouple-workflow-owner.sql"
--   之后还需手工删除 flyway_schema_history 里 version='20261029.81' 那一行, 否则 Flyway 不会重跑正向。
--
-- ⚠️ 前置条件: 回滚会重新强制「引用方 product_type_id == 工艺图 owner SKU」。
--    若解耦窗口期内已经写入了锚点与产出不一致的 activation / plan / instance,
--    下面的 ADD CONSTRAINT 会失败并列出违规行 —— 这是预期行为, 不要绕过, 先修数据。

ALTER TABLE production_workflow_instances
  DROP CONSTRAINT fk_pwi_workflow_owner,
  ADD CONSTRAINT fk_pwi_workflow_owner
    FOREIGN KEY (
      workflow_id,
      factory_id,
      product_type_id
    )
    REFERENCES product_process_workflows(
      id,
      factory_id,
      product_type_id
    );

ALTER TABLE production_plans
  DROP CONSTRAINT fk_production_plan_selected_workflow,
  ADD CONSTRAINT fk_production_plan_selected_workflow
    FOREIGN KEY (
      selected_workflow_id,
      factory_id,
      product_type_id,
      selected_workflow_version
    )
    REFERENCES product_process_workflows(
      id,
      factory_id,
      product_type_id,
      definition_version
    );

ALTER TABLE product_process_workflow_activations
  DROP CONSTRAINT fk_ppwa_active_workflow_owner,
  ADD CONSTRAINT fk_ppwa_active_workflow_owner
    FOREIGN KEY (
      active_workflow_id,
      factory_id,
      product_type_id,
      active_definition_version
    )
    REFERENCES product_process_workflows(
      id,
      factory_id,
      product_type_id,
      definition_version
    );

-- uk_ppw_id_factory 是 V20261029_81 新建的, 回滚时一并撤掉。
-- 必须排在三条 FK 都改回去之后 —— 否则 fk_pwi_workflow_owner 还引用着它时无法 DROP。
ALTER TABLE product_process_workflows
  DROP CONSTRAINT uk_ppw_id_factory;
