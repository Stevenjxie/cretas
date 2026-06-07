-- COMMON-overload fix for clarification candidate padding
--
-- 背景: MATERIAL_BATCH_QUERY ("查询原料库存") 和 PROCESSING_BATCH_LIST ("查询生产批次") 是
-- 制造业专属意图, 但 business_type='COMMON', 导致:
--   1. isCandidateCompatible('COMMON','RESTAURANT')=true → 它们作为 top 候选出现在餐饮租户
--      (RES_3101_009 qhj) 的 NEED_CLARIFICATION 列表里 — 餐厅老板困惑 "查询原料库存" 是什么。
--   2. 是 #494 C1 default-swap 修了 ensureMinChoices/buildDefaultSuggestions 两个补位池,
--      但未修候选列表 (buildCandidateActions 仍通过 COMMON → isCompatible=true 放行).
--
-- 修复: 重标 FACTORY (系统 canonical 工厂业态值, resolveBusinessDomain 只返 'RESTAURANT'/'FACTORY'):
--   · BusinessTypeScope.isCompatible('FACTORY','RESTAURANT')=false
--     → buildCandidateActions 过滤; 餐饮租户澄清列表再不出现制造业意图 ✓
--   · BusinessTypeScope.isCompatible('FACTORY','FACTORY')=true
--     → 工厂租户 (F006, F001 等) 仍放行, 零回归 ✓
--   · BusinessTypeGate: 工厂租户 intentBiz='FACTORY' == factoryDomain='FACTORY' → 放行 ✓
--     餐饮租户 'FACTORY' != 'RESTAURANT' → 诚实空状态 ✓
--
-- ⚠️ 不可用 'MANUFACTURING': resolveBusinessDomain 从不返回该值 —— BusinessTypeGate
--    会对工厂租户误判为"本店为餐饮业态"(回归). 见 V20260913_03 注释.
--
-- 幂等: 改后再跑影响 0 行 (WHERE business_type='COMMON' 不再匹配).

UPDATE ai_intent_configs
SET    business_type = 'FACTORY',
       updated_at    = NOW()
WHERE  intent_code IN ('MATERIAL_BATCH_QUERY', 'PROCESSING_BATCH_LIST')
  AND  business_type = 'COMMON';
