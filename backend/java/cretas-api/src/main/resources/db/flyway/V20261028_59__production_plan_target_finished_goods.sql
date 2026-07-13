-- raw-centric 多成品计划: 记录本计划要产的终端成品 productTypeId 列表 (展示/校验用, 不裁剪 workflow 图)。
-- null = 非多成品计划 (走既有单产品/legacy 路径)。计划仍锚定原料 owner (product_type_id=raw)。
ALTER TABLE production_plans ADD COLUMN target_finished_good_ids jsonb;

COMMENT ON COLUMN production_plans.target_finished_good_ids IS
  'raw-centric 多成品计划: 本次要产的终端成品 productTypeId 列表(展示/校验用, 不裁剪 workflow 图); null=非多成品计划';
