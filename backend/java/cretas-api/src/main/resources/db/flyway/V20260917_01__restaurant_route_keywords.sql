-- Track A 关键词双保险: 让 KEYWORD 层也命中, 减少落动态路径。短语层(IntentKnowledgeBase
-- restaurantPhraseMapping)已是主路由, 此为冗余加固。
--
-- ⚠️ 覆盖说明: 短语层(主路由)才是确定性命中点, keywords 仅二级加固。下面的数组是
--   **真超集** = 上游已写关键词(ORDER_STATISTICS←V20260902_01:44, STORE_REVENUE_RANK←V20260910_01:25)
--   的并集 + 本次新增的 客单价/人均/堂食外卖 等变体, 故 ON UPDATE 覆盖不丢任何既有 KEYWORD-层命中。
--
-- keywords ::jsonb cast 与 V20260902/03/04/07/10 一致 (跨环境 keywords 列类型不一, 须显式 cast)。
-- 仅 UPDATE 既有意图, 不新建 (RESTAURANT_ORDER_STATISTICS / RESTAURANT_STORE_REVENUE_RANK 均已存在)。

-- 上游 V20260902_01:44 = ["外卖占比","外卖占比多少","堂食外卖对比","堂食外卖","外卖比例","外卖生意","堂食占比"]
-- 并集 + 新增: 堂食 / 内用外卖 / 服务模式
UPDATE ai_intent_configs
   SET keywords = '["外卖占比","外卖占比多少","堂食外卖对比","堂食外卖","外卖比例","外卖生意","堂食占比","堂食","内用外卖","服务模式"]'::jsonb,
       updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_ORDER_STATISTICS';

-- 上游 V20260910_01:25 = 12 个门店营收/业绩词; 并集 + 新增: 门店营收 / 门店排行 / 营收对比 / 客单价 / 人均消费 / 笔单价 / 哪家店
UPDATE ai_intent_configs
   SET keywords = '["哪家店业绩最好","门店营收对比","门店营收排行","门店营收排名","门店业绩排名","哪家店最赚钱","哪家店营收最高","各门店营收对比","哪家店生意最好","门店销售排行","哪个店营收最高","门店收入排名","门店营收","门店排行","营收对比","客单价","人均消费","笔单价","哪家店"]'::jsonb,
       updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_STORE_REVENUE_RANK';
