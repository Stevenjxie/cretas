-- SKU_GROSS_MARGIN 是工厂概念(生产批次毛利, SkuGrossMarginTool 走 ProductionBatchRepository)误挂 COMMON
-- → 餐饮业态过滤放行 → 餐饮问题(如"付款方式占比")按向量相似度撞它瞎编 → 垃圾路由源。
-- 重标 'FACTORY' (系统 canonical 工厂业态值, resolveBusinessDomain 只返 'RESTAURANT'/'FACTORY'):
--   · BusinessTypeScope.isCompatible('FACTORY','RESTAURANT')=false → 餐饮工厂候选过滤排除 ✓
--   · BusinessTypeScope.isCompatible('FACTORY','FACTORY')=true → 工厂租户仍放行 ✓
--   · BusinessTypeGate: 工厂租户 intentBiz='FACTORY' == factoryDomain='FACTORY' → 放行 ✓ (零回归)
--     餐饮租户 'FACTORY' != 'RESTAURANT' → 诚实空状态 (而非制造垃圾) ✓
-- ⚠️ 不可用 'MANUFACTURING': resolveBusinessDomain 从不返回该值, BusinessTypeGate 会对工厂租户
--   误判 'MANUFACTURING'!='FACTORY' → 把它在真实工厂租户上拦成"本店为餐饮业态" (回归)。
--
-- ⚠️ 只重标 SKU_GROSS_MARGIN, 不动 REVENUE_REPORT_GENERATE:
--   终审(5-agent)确认 REVENUE_REPORT_GENERATE 其实是**餐饮**功能 (RevenueReportGenerateTool 在
--   ai/tool/impl/restaurant/, intent_category=RESTAURANT, 生成青花椒餐饮收入报表 午市/晚市/堂食外卖)。
--   误重标 FACTORY 会让 BusinessTypeGate 在餐饮租户上把"拉收入报表"拦成"工厂分析不适用"(回归)。
--   它保持 COMMON; 原先"堂食外卖对比"误撞它的问题已由 Track A 确定性短语(→RESTAURANT_ORDER_STATISTICS)修复。
UPDATE ai_intent_configs SET business_type = 'FACTORY', updated_at = NOW()
 WHERE intent_code = 'SKU_GROSS_MARGIN'
   AND business_type = 'COMMON';
