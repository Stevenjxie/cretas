-- V20261029_82: 放行「逐道报工的中间半成品批次」—— 修 V20261029_77 的误伤
--
-- ⛔ 症状: 任何「原料 → 工序A → 半成品 → 工序B → 成品」的画布, 逐道报工在**第一道工序就 500**:
--     ERROR: product <半成品> in factory <F> has no published/enabled canvas workflow;
--            LEGACY production was retired 2026-08-09
--     at ClerkProcessEntryServiceImpl.createProductionBatch(:622)
--   2026-08-11 在 prod F006 用真实画布 wf=163 实撞 (traceId E528806C)。
--
-- 🔍 根因 —— 两条各自合理的规则打架:
--
--   (1) Java 侧 ClerkProcessEntryServiceImpl:563-570 **故意**不给 WIP 批次挂计划:
--         // Only link FINISHED batches to the plan so OrderCostBreakdownService.compute()
--         // doesn't double-count WIP batch raw costs (WIP costs are already traced via
--         // traceCost() when the finished batch's consumption is followed upstream).
--       —— 「不挂计划」是 WIP 批次的**设计特征**, 不是历史脏数据。
--
--   (2) V20261029_77 的 ELSE 分支把「不挂计划」当成 LEGACY 老路的**唯一判据**:
--         ELSE  -- production_plan_id IS NULL
--           ... RAISE '... has no published/enabled canvas workflow; LEGACY production was retired'
--
--   合起来: 中间半成品批次**既不许挂 planId(成本会重复计), 又因为没挂 planId 被判 LEGACY 拒绝**。
--
-- 📉 附带损失 (已查证, 非推测): V20261029_78 以 workflow_selection_mode='LEGACY' 为判据清存量,
--   注释写「prod 实测存量: 17 个批次 …(全部不挂计划)」。实际备份表里躺着的是:
--     legacy_retired.production_batches_20260809 → workflow_selection_mode=LEGACY
--                                                  batch_type=CLERK_WIP  20 条  plan_null=20 条
--                                                  2026-08-02 19:52 → 2026-08-09 16:38:26
--   即**被当作「LEGACY 老路遗留」删掉的 20 条, 100% 是正常逐道报工产生的中间半成品批次**
--   (连同它们的 production_reports / material_consumptions)。整行备份在, 可追溯/回灌。
--   本迁移**不做回灌** —— 回灌会改动历史生产与成本记录, 需业务侧单独拍板。
--
-- ⏱ 为什么两天没人发现: 最后一条成功的逐道报工批次 CLK-B-20260809-18347 建于 16:38:38,
--   V20261029_77 落地于 16:40:45 —— 差 2 分钟。此后无人跑过含中间半成品的多工序画布。
--
-- ✅ 本迁移的改动 (最小、可逆、恢复 08-09 之前跑了几个月的行为):
--   ELSE 分支在 RAISE 之前先放行 batch_type='CLERK_WIP'。
--   这些批次继续沿用列默认 workflow_selection_mode='LEGACY'(NOT NULL DEFAULT 'LEGACY'),
--   满足 ck_production_batch_workflow_selection 的 (LEGACY AND selected_workflow_id IS NULL) 分支。
--
-- ⚠️ 有意**不做**的事:
--   - 不给 workflow_selection_mode 加第三个取值 (如 'CLERK_WIP'): check 约束只认 LEGACY/WORKFLOW,
--     且 Java 枚举 ProductionBatch.WorkflowSelectionMode 只有这两个 —— 加第三值会让读取侧抛异常。
--   - 不改 Java 让 WIP 挂 planId (结构上更正确的方案 B): 那要同时动成本核算
--     (OrderCostBreakdownService)、findWorkflowRuntime 的批次筛选、小结/撤销,
--     blast radius 大, 且本仓 `mvn test` 基线长期 130 红, 拿不到可信回归证据。留作后续。
--
-- 📌 判据 (留给下一个人):
--   闸按「缺少某个字段」判定「属于旧世界」时, 先问: **这个字段为空还有没有别的合法含义**。
--   这里 production_plan_id IS NULL 有两个来源 —— LEGACY 老路 **和** 设计上不挂计划的 WIP 批次,
--   V20261029_77/78 只认了前一个。
--
-- 幂等: CREATE OR REPLACE FUNCTION, 重复执行安全。触发器本身不重建。

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
    NEW.factory_id || E'\x1f' || NEW.product_type_id,
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
    -- ✅ V20261029_82: 逐道报工的中间半成品批次 (CLK-W- 前缀) 按设计就不挂计划 ——
    --    见 ClerkProcessEntryServiceImpl:563-570。它不是 LEGACY 老路, 是画布工艺内部的
    --    一道中间工件, 其工艺权威在同计划的成品批次上。放行, 不要求它自己有画布工艺。
    --    ⛔ 后续任何「清理 LEGACY 存量」的迁移, 判据必须带上 batch_type <> 'CLERK_WIP',
    --       否则会像 V20261029_78 那样把正常生产数据当老路删掉。
    IF NEW.batch_type = 'CLERK_WIP' THEN
      RETURN NEW;
    END IF;

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

COMMENT ON FUNCTION public.pin_production_batch_workflow_selection() IS
  'INSERT 时把生产计划的工艺/BOM 选择钉进批次。'
  '不挂计划的批次一律拒绝, 唯一例外是 batch_type=''CLERK_WIP'' 的逐道报工中间半成品批次 '
  '(V20261029_82) —— 它按设计不挂计划(避免成本重复计), 工艺权威在同计划的成品批次上。';

COMMENT ON COLUMN public.production_batches.workflow_selection_mode IS
  'LEGACY | WORKFLOW。⚠️ workflow_selection_mode=''LEGACY'' 且 batch_type=''CLERK_WIP'' 是**合法组合** '
  '(逐道报工的中间半成品批次), 不是待清理的老路数据 —— V20261029_78 就是漏了这条把 20 条正常批次删了。';
