-- V20261029_77: 下架 LEGACY 老路 —— 生产只认画布工艺 (Steve 2026-08-09 拍板)
--
-- 批次的工艺/BOM 权威由本触发器在 INSERT 时写死。原先有两条分支会把批次判成 LEGACY
-- (工艺字段全 NULL, 报工回落到 product_work_processes 工序模板):
--   (1) 计划自己就是 LEGACY;
--   (2) 批次不挂计划, 且该产品没有启用中的画布工艺。
-- Java 侧的两级回落已在同批改动中删除; 这里堵住数据库这一侧, 免得应用改了而直接写库
-- 或别的入口仍在源源不断造出新的 LEGACY 行 (prod 实测 08-02 至 08-09 造了 17 个)。
--
-- 有意的收紧: 拍板时已知 509 个产品里只有 8 个配了画布工艺, 其余产品必须先建工艺才能
-- 生产。不要因为"挡住了很多产品"把这里改回去。
--
-- 本迁移不动已有的 17 行 LEGACY 批次 -- 它们底下挂着 10 条报工 + 28 条领料消耗, 清理要
-- 单独做(整行备份进独立 schema 后再删), 且必须排在删除 Java 枚举值之前。
--
-- 幂等: CREATE OR REPLACE FUNCTION, 重复执行安全。

CREATE OR REPLACE FUNCTION public.pin_production_batch_workflow_selection()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
  plan_mode VARCHAR(16);
  plan_workflow_id BIGINT;
  plan_workflow_version INTEGER;
  plan_workflow_revision_id BIGINT;
  plan_workflow_revision_hash VARCHAR(64);
  plan_bom_family_id VARCHAR(64);
  plan_bom_recipe_ids JSONB;
  plan_bom_versions JSONB;
  plan_output_units JSONB;
  plan_target_finished_good_ids JSONB;
  plan_found BOOLEAN := FALSE;
  governed_workflow_exists BOOLEAN := FALSE;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(
    NEW.factory_id || E'\\x1f' || NEW.product_type_id,
    0
  ));

  IF NEW.production_plan_id IS NOT NULL THEN
    SELECT workflow_selection_mode,
           selected_workflow_id,
           selected_workflow_version,
           selected_workflow_revision_id,
           selected_workflow_revision_hash,
           selected_bom_family_id,
           selected_bom_recipe_ids_by_product,
           selected_bom_versions_by_product,
           workflow_output_units_by_product,
           target_finished_good_ids
      INTO plan_mode,
           plan_workflow_id,
           plan_workflow_version,
           plan_workflow_revision_id,
           plan_workflow_revision_hash,
           plan_bom_family_id,
           plan_bom_recipe_ids,
           plan_bom_versions,
           plan_output_units,
           plan_target_finished_good_ids
      FROM production_plans
     WHERE id = NEW.production_plan_id
       AND factory_id = NEW.factory_id;
    plan_found := FOUND;
    IF NOT plan_found THEN
      RAISE EXCEPTION
        'production batch plan % does not exist in factory %',
        NEW.production_plan_id, NEW.factory_id
        USING ERRCODE = '23503';
    END IF;
  END IF;

  IF plan_mode = 'WORKFLOW' THEN
    -- Expand-phase compatibility: while the old blue/green slot can still
    -- write during migration startup, copy its legacy Workflow selection
    -- without rejecting the batch. The new service always writes the complete
    -- authority below and fails incomplete plans closed at runtime. A later
    -- contract migration may enforce NOT NULL only after every writer has
    -- crossed this deployment boundary.
    NEW.workflow_selection_mode := 'WORKFLOW';
    NEW.selected_workflow_id := plan_workflow_id;
    NEW.selected_workflow_version := plan_workflow_version;
    NEW.selected_workflow_revision_id := plan_workflow_revision_id;
    NEW.selected_workflow_revision_hash := plan_workflow_revision_hash;
    NEW.selected_bom_family_id := plan_bom_family_id;
    NEW.selected_bom_recipe_ids_by_product := COALESCE(plan_bom_recipe_ids, '{}'::jsonb);
    NEW.selected_bom_versions_by_product := COALESCE(plan_bom_versions, '{}'::jsonb);
    NEW.workflow_output_units_by_product := COALESCE(plan_output_units, '{}'::jsonb);
    NEW.target_finished_good_ids := COALESCE(plan_target_finished_good_ids, '[]'::jsonb);
  ELSIF plan_mode = 'LEGACY' THEN
    RAISE EXCEPTION
      'production plan % is pinned to the retired LEGACY mode; only canvas-governed workflows can produce',
      NEW.production_plan_id
      USING ERRCODE = '23514';
  ELSIF NEW.production_plan_id IS NOT NULL THEN
    RAISE EXCEPTION
      'production plan % lacks an explicit workflow selection snapshot',
      NEW.production_plan_id
      USING ERRCODE = '23514';
  ELSE
    SELECT EXISTS (
      SELECT 1
        FROM product_process_workflow_activations activation
       WHERE activation.factory_id = NEW.factory_id
         AND activation.product_type_id = NEW.product_type_id
         AND activation.enabled = TRUE
         AND activation.deleted_at IS NULL
    )
      INTO governed_workflow_exists;
    IF governed_workflow_exists THEN
      RAISE EXCEPTION
        'product % in factory % is Workflow-governed; create the batch through a production plan',
        NEW.product_type_id, NEW.factory_id
        USING ERRCODE = '23514';
    END IF;
    RAISE EXCEPTION
      'product % in factory % has no published/enabled canvas workflow; LEGACY production was retired 2026-08-09',
      NEW.product_type_id, NEW.factory_id
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$function$;
