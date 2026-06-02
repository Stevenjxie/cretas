-- 餐厅评价深度分析 8 意图 → P1 对话深度层 within-review 跨维 + 更多评价问题 (qhj, 2026-06-02)
--
-- 背景: P1 在已 LIVE 的评价 8 问 (PR #387/#391) 之上补深度:
--   跨维 3 工具 (VIP×口味 / 时段×评价 / 服务-环境标签×评分) +
--   评价问题 4 工具 (好评高频词 / 平台对比 / 评价趋势 / 回复率)。
--   score_tags 一个工具覆盖 服务标签 + 环境标签 两意图 (dim 自动判定)。
--   全部读 smart_bi_dynamic_data 评价数据 (按 评价ID 去重), 不 fallthrough LLM。
--
-- flyway 版本 20260904.01 > prod 已应用 max 20260903.03 (out-of-order=false, 必须更大)。
-- 幂等: 全部 ON CONFLICT (intent_code) DO UPDATE, 可重复执行。
-- priority 115 (= 评价基础问), business_type='RESTAURANT' 让业态门控放行。
-- keywords ::jsonb cast 与 V20260903_01 一致 (keywords 列跨环境 text/json/jsonb 不一致,
--   jsonb 入参必须显式 cast, 否则 fresh-CI DB 报类型错误)。

-- (1) VIP×口味
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_VIP_TAGS', 'VIP口味偏好', 'SMARTBI', 'restaurant_review_vip_tags', 'LOW',
        '["vip喜欢什么","vip喜欢什么口味","vip好评点","vip差评点","会员口味偏好","vip在意什么","vip顾客喜欢","会员喜欢什么菜","vip对什么满意","vip吐槽什么"]'::jsonb,
        'VIP vs 非VIP 高频好评/差评口味标签对比 (口味/品质标签, 非菜名)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_vip_tags', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (2) 时段×评价
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_TIME_PERIOD', '时段评价分布', 'SMARTBI', 'restaurant_review_time_period', 'LOW',
        '["哪个时段评价好","时段评价","时段评价分布","什么时间段口碑差","什么时段评价高","各时段评价","时段口碑","哪个时间段差评多","时段评分对比","早午晚评价"]'::jsonb,
        '各时段(早/午/下午/晚/夜)评价量与平均星级 (评价时间 ~73% 有值)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_time_period', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (3a) 服务标签×评分
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_SERVICE_TAGS', '服务评价标签', 'SMARTBI', 'restaurant_review_score_tags', 'LOW',
        '["服务标签","服务评价标签","顾客怎么评价服务","服务评价词","顾客对服务的评价","服务好评词","服务相关评价","服务评价关键词","服务口碑标签"]'::jsonb,
        '服务评价标签高频词 + 平均服务分 (服务标签)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_score_tags', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (3b) 环境标签×评分 (同工具, dim=env 由 userInput 含"环境"判定)
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_ENV_TAGS', '环境评价标签', 'SMARTBI', 'restaurant_review_score_tags', 'LOW',
        '["环境标签","环境评价标签","顾客怎么评价环境","环境评价词","顾客对环境的评价","环境好评词","环境相关评价","环境评价关键词","环境口碑标签"]'::jsonb,
        '环境评价标签高频词 + 平均环境分 (环境标签)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_score_tags', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (4) 好评高频词
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_GOOD_TAGS', '好评高频词', 'SMARTBI', 'restaurant_review_good_tags', 'LOW',
        '["好评最多提到什么","好评高频词","顾客最满意什么","好评关键词","好评里说什么","好评提到的","顾客最认可什么","好评热词","好评最多说什么","好评里最常提到"]'::jsonb,
        '好评(>=4.5星)高频口味/品质标签 (非菜名)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_good_tags', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (5) 平台对比
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_PLATFORM', '平台评价对比', 'SMARTBI', 'restaurant_review_platform', 'LOW',
        '["各平台评价对比","平台评价","点评和美团哪个评分高","平台口碑","各渠道评价","美团点评对比","哪个平台评分高","平台评分对比","渠道口碑对比","不同平台评价"]'::jsonb,
        '各平台(点评/美团)评价量与平均星级对比', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_platform', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (6) 评价趋势
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_TREND', '评价趋势', 'SMARTBI', 'restaurant_review_trend', 'LOW',
        '["评价趋势","口碑变化","评分走势","最近评价好转还是变差","评价趋势怎么样","口碑趋势","评分趋势","评价变化趋势","近期口碑","评价走势图"]'::jsonb,
        '按月聚合评价量与平均星级走势 (评价趋势)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_trend', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

-- (7) 回复率
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_REPLY_RATE', '评价回复率', 'SMARTBI', 'restaurant_review_reply_rate', 'LOW',
        '["评价回复率","有多少评价没回复","回复及时吗","商家回复率","回复情况","未回复评价","评价回复情况","多少评价回复了","回复率怎么样","差评回复了吗"]'::jsonb,
        '商家评价回复率 (已/未回复 + 未回复差评数)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_reply_rate', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();
