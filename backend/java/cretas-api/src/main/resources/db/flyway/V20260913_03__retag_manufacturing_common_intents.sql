-- SKU_GROSS_MARGIN / REVENUE_REPORT_GENERATE 是制造业概念误挂 COMMON → 餐饮业态过滤放行 → 垃圾路由源。
-- 重标 MANUFACTURING: 餐饮工厂 isCompatible=false 排除; 非餐饮工厂(只排 RESTAURANT)仍放行, 零回归。
UPDATE ai_intent_configs SET business_type = 'MANUFACTURING', updated_at = NOW()
 WHERE intent_code IN ('SKU_GROSS_MARGIN', 'REVENUE_REPORT_GENERATE')
   AND business_type = 'COMMON';
