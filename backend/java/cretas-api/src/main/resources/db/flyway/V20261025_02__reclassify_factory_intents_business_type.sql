-- AI 业态泄漏系统性修复 (#993 之后的真修复)。
--
-- 背景: 全 395 意图 business_type 分布 COMMON=190 / FACTORY=126 / RESTAURANT=79,
--   0 个 NULL → V20261025_01 的 `WHERE business_type IS NULL` 是 no-op, 没真修。
--   真因: 大量**制造业专属意图被错标 COMMON**, 而 BusinessTypeGate 允许 COMMON 在
--   所有业态运行 → 餐饮租户也能路由到生产/工序/在制/电子秤等制造业意图 (实测:
--   餐饮租户 RES_3101_009 能触发 PRODUCTION_BATCH_CREATE / QUALITY_DEFECT_QUERY /
--   BOM 等, 返回工厂空数据或引导创建生产批次)。
--
-- 修复: 把**纯制造业执行类**意图从 COMMON 重标 FACTORY (餐饮用 RESTAURANT_* 意图,
--   无这些制造概念)。范围严格限制在 生产/工序/工单/在制品/批次消耗/络麻称重/电子秤/
--   BOM配方/制造质检(缺陷+退货)/物料处置/放行 —— 餐饮 100% 无对应业务。
--
-- 刻意**保留 COMMON** (业态共享, 不动): 食安合规(HACCP/SSOP/ADDITIVE/FOOD_SAMPLE,
--   工厂+餐饮均需)、物流(收货/冷链/调拨/库存/仓储/缺货)、供应商/客户/CRM、
--   财务/HR/账务/结账/税务、系统/帮助/设置/相机/打印/审批/工作流、销售/商机、
--   通用溯源/血缘、周转容器、微信、指标。
--
-- 幂等: 仅改当前仍为 COMMON 的行 (AND business_type='COMMON'); 不覆盖已 FACTORY/
--   RESTAURANT 的行; 重复执行无副作用。

UPDATE ai_intent_configs
SET business_type = 'FACTORY', updated_at = NOW()
WHERE business_type = 'COMMON'
  AND intent_code IN (
    -- 生产计划 / 批次 / 进度
    'PRODUCTION_BATCH_CREATE',
    'PRODUCTION_PLAN_CREATE',
    'PRODUCTION_PROGRESS_DASHBOARD',
    'PRODUCTION_DEMAND_ANALYSIS',
    'PRODUCTION_DEMAND_QUERY',
    'PRODUCTION_DELIVERY_WARN_QUERY',
    'PLAN_FROM_SALES_ORDER_ITEM',
    'PROCESSING_CAPACITY_TODAY',
    -- 工序任务 / 工单
    'PROCESS_TASK_ANALYSIS',
    'PROCESS_TASK_CREATE',
    'PROCESS_TASK_QUERY',
    'PROCESS_TASK_SUMMARY',
    'WORK_PROCESS_TASK_ASSIGN',
    'WORK_PROCESS_TASK_COMPLETE',
    'WORK_PROCESS_TASK_SPAWN',
    'WORK_PROCESS_TASK_START',
    'WORK_PROCESS_CONFIG_UPDATE',
    'WORK_REPORT_MODE_QUERY',
    -- 在制品 / 批次消耗 / 物料处置
    'MATERIAL_BATCH_WIP_QUERY',
    'BATCH_CONSUMPTION_ADJUST',
    'BATCH_CONSUMPTION_QUERY',
    'MATERIAL_DISPOSAL_RECOMMENDATION',
    -- 络麻称重 / 电子秤 (制造现场设备)
    'MATERIAL_MARK_ABACA',
    'ABACA_WEIGHT_LOG',
    'ABACA_WEIGHT_SUMMARY',
    'SCALE_CALIBRATE',
    'SCALE_TROUBLESHOOT',
    -- BOM 配方 (制造产品配方; 餐饮用 RESTAURANT_DISH_COST_*)
    'BOM_RECIPE_ACTIVATE',
    'BOM_RECIPE_CLONE_WITH_MODIFY',
    'BOM_RECIPE_COST_CALCULATE',
    'BOM_RECIPE_CREATE_FROM_SAMPLE',
    'BOM_RECIPE_CREATE_FROM_TEXT',
    'BOM_RECIPE_QUERY',
    'BOM_CATEGORY_FILTER',
    'SAMPLE_TO_BOM',
    -- 制造质检 (批次缺陷 / 退货 / 放行)
    'QUALITY_DEFECT_QUERY',
    'QUALITY_DEFECT_RECORD',
    'QUALITY_RETURN_CREATE',
    'QUALITY_RETURN_QUERY',
    'QUALITY_CHECK_SUMMARY',
    'QUALITY_CHIEF_WORKDESK',
    'RELEASE_DECISION',
    -- 工厂物料申领 (FACTORY_ 前缀; 餐饮用 RESTAURANT_VOICE_REQUISITION)
    'FACTORY_MR_CLOSE',
    'FACTORY_MR_GENERATE',
    'FACTORY_MR_QUERY_PENDING'
  );
