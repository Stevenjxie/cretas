-- 餐厅评价分析 8 问答 → gold-backed review Tool 意图绑定 (qhj 重点客户, 2026-06-02)
--
-- 背景: 经营驾驶舱"评价类"8 问 (客户评价/差评门店/哪些菜差评/VIP评价/投诉集中/
--   城市评价最低/服务分排名/环境分对比) 全坏:
--   (A) 部分被销售 gold 意图抢路由 (差评门店→营收排行 / 哪些菜差评→慢销 /
--       服务分→员工排行), 答非所问;
--   (B) 部分误路由到制造业意图或 LLM 编造。
--   评价数据不在 gold 物化表 (dim_review_summary/fact_review_event 对 qhj 为空),
--   而在 smart_bi_dynamic_data.row_data (大众点评'评价下载' JSONB)。
--
-- 修法:
--   (1) 停用 慢销 语义孤儿 RESTAURANT_SLOW_SELLER_QUERY (active/空keywords/无tool) —
--       它在 FUSION/语义层抢"慢销菜品", 让位给 RESTAURANT_DISH_SLOW(gold)。
--       与历史 RESTAURANT_DISH_LIST 同根 (priority 调整无效, 必须停用)。
--   (2) 停用 评价语义孤儿 RESTAURANT_REVIEW_COMPETITIVE + RESTAURANT_PERFORMANCE_EVAL
--       (priority 85/空keywords/仅语义命中/对 qhj 无 grounded 数据) — 它们会在 FUSION
--       层抢"客户评价/服务/口碑"问题。新建的 grounded 评价工具取而代之。
--   (3) 去掉 RESTAURANT_OPS_STORE_MARGIN 过于宽泛的 门店/分店/店铺 关键词 —
--       它们会让"差评最多的门店"误命中营收排行 (保留"哪家店业绩最好"等强短语)。
--   (4) 新建 8 个 grounded 评价意图, priority 115 (> DISH_SLOW 110 > 默认 85/90),
--       business_type='RESTAURANT' 让业态门控对餐厅放行, 强关键词命中 KEYWORD 层。
--       全部读 smart_bi_dynamic_data 评价数据 (按 评价ID 去重), 不 fallthrough LLM。
--
-- flyway 版本 20260903.01 > prod 已应用 max 20260902.03 (out-of-order=false, 必须更大)。
-- 幂等: 1-3 用 WHERE intent_code; 4 用 ON CONFLICT (intent_code) DO UPDATE, 可重复执行。

-- ============ (1) 停用 慢销 语义孤儿 ============
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_SLOW_SELLER_QUERY';

-- ============ (2) 停用 评价/绩效 语义孤儿 (空 keywords, 仅语义命中) ============
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW()
 WHERE intent_code IN ('RESTAURANT_REVIEW_COMPETITIVE', 'RESTAURANT_PERFORMANCE_EVAL');

-- ============ (3) 收窄 OPS_STORE_MARGIN 过宽关键词 (去 门店/分店/店铺) ============
-- keywords 列在 bootstrap (V20260415_99) 建为 TEXT, 在 prod 是 json/jsonb
-- (ddl-auto columnDefinition="JSON") → 跨环境类型不一致。jsonb_array_elements 需 jsonb
-- 入参, 必须 keywords::jsonb cast (text→jsonb 解析 / json|jsonb→jsonb 等价), 否则
-- fresh-CI DB 报 "function jsonb_array_elements(text) does not exist" 阻断后端启动。
-- (V_05 / V_36 / V20260522_50/51 历史教训, 本迁移当初漏 cast → e2e-pr-gate 全红)
UPDATE ai_intent_configs
   SET keywords = COALESCE((
         SELECT jsonb_agg(e)
           FROM jsonb_array_elements(keywords::jsonb) e
          WHERE e NOT IN ('"门店"'::jsonb, '"分店"'::jsonb, '"店铺"'::jsonb)
       ), '[]'::jsonb),
       updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_OPS_STORE_MARGIN';

-- ============ (4) 新建 8 个 grounded 评价分析意图 (priority 115) ============

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_SUMMARY', '客户评价总览', 'SMARTBI', 'restaurant_review_summary', 'LOW',
        '["客户评价怎么样","客户评价","评价怎么样","口碑怎么样","整体评价","顾客评价","点评情况","评分情况","总体口碑","用户评价","大众点评评分","评价好不好"]'::jsonb,
        '客户评价总览: 平均星级/服务/环境/口味分、好评差评数、VIP评价、门店城市覆盖 (评价下载数据)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_summary', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_BAD_STORE', '差评最多门店', 'SMARTBI', 'restaurant_review_store_rank', 'LOW',
        '["差评最多的门店","差评最多门店","差评最多的店","哪家店差评多","哪个门店差评最多","差评门店","评价最差的门店","哪家店口碑差","差评集中的门店","差评最多哪家"]'::jsonb,
        '差评(≤3星)最多的门店排行 (含差评率与平均星级)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_store_rank', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_DISH_ISSUE', '差评菜品标签', 'SMARTBI', 'restaurant_review_dish_issue', 'LOW',
        '["哪些菜品差评多","哪些菜差评多","哪些菜差评","差评菜品","菜品差评多","哪些菜被吐槽","被吐槽的菜","菜品口碑差","差评涉及的菜","哪些菜评价差"]'::jsonb,
        '差评(≤3星)中高频的口味/品质标签 (大众点评标签为口味描述, 非具体菜名)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_dish_issue', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_VIP', 'VIP评价情况', 'SMARTBI', 'restaurant_review_vip', 'LOW',
        '["vip评价情况","vip评价","会员评价","vip顾客评价","会员顾客评价","vip评分","会员评分","vip打分","会员打分","vip口碑","vip顾客满意度"]'::jsonb,
        'VIP vs 非VIP 评价对比 (星级/服务/环境分)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_vip', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_COMPLAINT', '投诉差评集中点', 'SMARTBI', 'restaurant_review_complaint', 'LOW',
        '["投诉最集中的问题","投诉最集中","投诉最多","主要投诉","投诉类型","投诉问题","客诉集中","主要客诉","投诉原因","集中投诉","投诉点"]'::jsonb,
        '差评/投诉集中点: 低星评价数 + 商家评价申诉类型分布', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_complaint', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_CITY', '城市评价对比', 'SMARTBI', 'restaurant_review_city', 'LOW',
        '["哪个城市评价最低","城市评价","城市口碑","哪个城市口碑差","各城市评价","城市评分对比","哪个城市评分最低","城市评价对比","哪个城市差评多","城市维度评价"]'::jsonb,
        '各城市评价对比 (平均星级, 评分最低在前)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_city', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_SERVICE_SCORE', '门店服务分排名', 'SMARTBI', 'restaurant_review_service_score', 'LOW',
        '["服务分排名","服务分","服务评分排名","服务态度排名","门店服务分","服务分对比","哪家店服务好","服务评分","服务分最高","服务分最低"]'::jsonb,
        '门店服务分排名 (大众点评服务分, 由高到低)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_service_score', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVIEW_ENV_SCORE', '门店环境分对比', 'SMARTBI', 'restaurant_review_env_score', 'LOW',
        '["环境分对比","环境分","环境评分","门店环境分","环境评分对比","哪家店环境好","环境分排名","环境评分排名","环境分最高","环境分最低"]'::jsonb,
        '门店环境分对比 (大众点评环境分, 由高到低)', 115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_review_env_score', keywords = EXCLUDED.keywords, description = EXCLUDED.description, priority = 115, is_active = true, business_type = 'RESTAURANT', updated_at = NOW();
