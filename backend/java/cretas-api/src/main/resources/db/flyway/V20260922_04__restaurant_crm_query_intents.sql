-- #59 Phase 1 — 餐饮 CRM 查询意图 → 工具绑定 (cretas_db)
--   1) RESTAURANT_VIP_GUEST_QUERY     → restaurant_vip_guest_query     (重点客户/VIP)
--   2) RESTAURANT_AT_RISK_GUEST_QUERY  → restaurant_at_risk_guest_query  (即将流失/好久没来)
--
-- flyway 版本 20260922.04 (自 20260921 上移避 sister 撞车; Phase 1 块 _01/_02/_04; _03/_05/_06 留 Phase 2).
-- 幂等: ON CONFLICT (intent_code) DO UPDATE, 可重复执行.
-- business_type='RESTAURANT' 让业态门控放行 (制造业态工厂不进此意图).
-- keywords ::jsonb cast 与既有迁移一致 (keywords 列跨环境 text/json/jsonb 不一致).
-- priority 115 与同期餐饮意图对齐.
-- sensitivity_level=LOW: 读路径不写库; 手机号在工具内默认脱敏, 仅管理角色见完整号.
-- ⚠️ 关键词避开"怎么样"形式 (已知歧义查询局限).

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_VIP_GUEST_QUERY', '重点客户查询', 'SMARTBI', 'restaurant_vip_guest_query', 'LOW',
        '["重点客户","VIP客户","VIP名单","常客名单","重要客户","老客户名单","重点客户名单","常来的客户","谁是重点客户","哪些是VIP"]'::jsonb,
        '重点客户(VIP)查询: 到访 3 次及以上的客户, 返回客户名、到访次数、最近到访, 并标注是否应安排包厢(VIP 必须进包厢)。手机号默认脱敏, 仅管理角色见完整号。适用 "重点客户 / VIP客户 / 常客名单" 类问题。',
        115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'restaurant_vip_guest_query',
    intent_category = 'SMARTBI',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 115,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_AT_RISK_GUEST_QUERY', '即将流失客户查询', 'SMARTBI', 'restaurant_at_risk_guest_query', 'LOW',
        '["即将流失","好久没来","快流失的客户","流失预警","好久没来的客户","客户召回","哪些客户要流失","快流失了","好久不来的","流失客户名单"]'::jsonb,
        '即将流失客户查询: 超过阈值天数(默认 30 天)未到访的客户, 返回客户名、距上次到访天数、绑定营销员, 供门店主动召回。手机号默认脱敏, 仅管理角色见完整号。适用 "即将流失 / 好久没来 / 客户召回 / 流失预警" 类问题。',
        115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'restaurant_at_risk_guest_query',
    intent_category = 'SMARTBI',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 115,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();
