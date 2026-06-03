-- SKU_GROSS_MARGIN / REVENUE_REPORT_GENERATE 是工厂概念误挂 COMMON → 餐饮业态过滤放行 → 垃圾路由源。
-- 重标 'FACTORY' (系统 canonical 工厂业态值, resolveBusinessDomain 只返 'RESTAURANT'/'FACTORY'):
--   · BusinessTypeScope.isCompatible('FACTORY','RESTAURANT')=false → 餐饮工厂候选过滤排除 ✓
--   · BusinessTypeScope.isCompatible('FACTORY','FACTORY')=true → 工厂租户仍放行 ✓
--   · BusinessTypeGate: 工厂租户 intentBiz='FACTORY' == factoryDomain='FACTORY' → 放行 ✓ (零回归)
--     餐饮租户 'FACTORY' != 'RESTAURANT' → 诚实空状态 (而非制造垃圾) ✓
-- ⚠️ 不可用 'MANUFACTURING': resolveBusinessDomain 从不返回该值, BusinessTypeGate 会对工厂租户
--   误判 'MANUFACTURING'!='FACTORY' → 把这俩意图在真实工厂租户上拦成"本店为餐饮业态" (回归)。
UPDATE ai_intent_configs SET business_type = 'FACTORY', updated_at = NOW()
 WHERE intent_code IN ('SKU_GROSS_MARGIN', 'REVENUE_REPORT_GENERATE')
   AND business_type = 'COMMON';
