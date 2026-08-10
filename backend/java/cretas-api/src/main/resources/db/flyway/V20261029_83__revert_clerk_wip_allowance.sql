-- V20261029_83: 撤回 V20261029_82 —— 那个修复是错的, 会写出 Java 读不回来的行
--
-- 🔴 V20261029_82 让 batch_type='CLERK_WIP' 的无计划批次通过触发器。INSERT 确实成功了,
--    但这些行会拿到列默认 workflow_selection_mode='LEGACY', 而 Java 侧
--    ProductionBatch.WorkflowSelectionMode 枚举**只剩 WORKFLOW 一个值** ——
--    LEGACY 已在下架 LEGACY 老路时删除, 类注释还专门写了:
--      "不要把 LEGACY 加回来。枚举里存在这个值, 就意味着有代码路径能产出它,
--       而那条路径正是「两处口径打架」的来源。"
--    实测 (prod F006, 2026-08-11 05:38, traceId 72808A09):
--      IllegalArgumentException: No enum constant ...WorkflowSelectionMode.LEGACY
--    报工仍然失败, 只是从「触发器拒绝」变成了「插进去以后读不回来」。
--
-- ⛔ 为什么必须撤而不是留着: 本次失败发生在同一事务内(读回时抛), 所以整笔回滚、没留脏行。
--    但只要有**任何**路径插入 CLERK_WIP 批次后不在同事务读回, 就会落下一条
--    workflow_selection_mode='LEGACY' 的行, 之后**每一次**读到它的查询都会抛。
--    这是把一个干脆的 500 换成了一颗定时炸弹, 净负面。
--
-- ✅ 正确的修法 (方案 B, 未实施): 让 WIP 批次也挂 production_plan_id, 走触发器的
--    WORKFLOW 分支拿到完整 pins; 成本/汇总/打印/小结/运行时解析这 6 个按 planId
--    查批次的调用点补 batch_type <> 'CLERK_WIP' 排除
--    (这个排除在 ProductionBatchRepository 已有 8+ 处同样写法, 是既有惯例)。
--    合计 11 处改动, 需要可信回归证据后单独做。
--
-- 📌 判据: 放行一类数据之前, 先问「放行后它会被打上什么值, 读取侧认不认这个值」。
--    我只验了写入侧(触发器放行 = 干跑 A 通过), 没验读取侧 —— 干跑里插入后
--    那条 SELECT count(*) 走的是 psql 不是 Hibernate, 照不出枚举映射失败。
--
-- 幂等: CREATE OR REPLACE FUNCTION, 重复执行安全。
--
-- ⚠️ 本文件的函数体是从 V20261029_77 **逐字复制**的, 不是手抄 ——
--    diff 时发现 V82 里我把 advisory lock 的分隔符从 E'\x1f' 写成了 E''
--    (前者是 4 个字符 , 后者是单个 U+001F), 属于没注意到的改动。
--    还原必须是逐字还原, 否则「撤回」自己又引入新差异。

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
