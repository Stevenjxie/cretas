-- G7 取数自动化 Tier B — 语音录入领料单意图 → restaurant_voice_requisition 工具
--
-- 决策 D3 (Steve 拍板): 新建独立 RestaurantVoiceRequisitionTool + 意图 RESTAURANT_VOICE_REQUISITION,
--   与 AI 问答分开 UX (Tool-Skill 架构, 禁止 IntentHandler)。语音转文字后 NLP slot-fill 提取
--   食材/数量/单位, 返回草稿供人工二段式确认 (Rule 2 / Rule 4), 不直接落库提交。
--
-- 表名 ai_intent_configs (复数) — 与 V20260903/04/07/10 一致。keywords ::jsonb cast 跨环境列类型一致。
-- business_type='RESTAURANT' 业态门控; sensitivity_level='LOW' (仅返回草稿, 不自动写最终单);
-- priority 110 (= 数据录入操作)。幂等 ON CONFLICT (intent_code) DO UPDATE。
--
-- flyway 版本 20260916.02 (origin/main 最高 V20260915_07, out-of-order=false, 故 V20260916_01/02 安全)。

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_VOICE_REQUISITION', '语音录入领料单', 'DATA_OPERATION', 'restaurant_voice_requisition', 'LOW',
        '["领料","要料","拿料","进料","用料","备料","语音领料","报领料"]'::jsonb,
        '通过语音识别文本创建领料单草稿, 提取食材名称/数量/单位 (如"要五斤猪肉"), 返回草稿供人工确认后再提交。不直接落库。',
        110, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'restaurant_voice_requisition',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 110,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();
