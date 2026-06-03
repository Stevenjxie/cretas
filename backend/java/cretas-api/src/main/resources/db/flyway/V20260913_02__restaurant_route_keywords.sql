-- Track A 关键词双保险: 让 KEYWORD 层也命中, 减少落动态路径。短语层(IntentKnowledgeBase
-- restaurantPhraseMapping)已是主路由, 此为冗余加固。
--
-- ⚠️ 覆盖说明: 此 worktree 无法读 prod 当前 keywords 值, 故这里写完整期望集合(含常见既有词,
--   即上游 V20260902_01 / V20260910_01 已写过的词的超集), 用 ON UPDATE 直接覆盖。因为短语层(主路由)
--   才是确定性命中点, keywords 仅二级加固, 覆盖为超集是安全的。
--
-- keywords ::jsonb cast 与 V20260902/03/04/07/10 一致 (跨环境 keywords 列类型不一, 须显式 cast)。
-- 仅 UPDATE 既有意图, 不新建 (RESTAURANT_ORDER_STATISTICS / RESTAURANT_STORE_REVENUE_RANK 均已存在)。

UPDATE ai_intent_configs
   SET keywords = '["堂食外卖","堂食","外卖占比","内用外卖","服务模式","外卖生意","堂食外卖对比"]'::jsonb,
       updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_ORDER_STATISTICS';

UPDATE ai_intent_configs
   SET keywords = '["门店营收","门店排行","营收对比","客单价","人均消费","笔单价","哪家店","门店营收对比"]'::jsonb,
       updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_STORE_REVENUE_RANK';
