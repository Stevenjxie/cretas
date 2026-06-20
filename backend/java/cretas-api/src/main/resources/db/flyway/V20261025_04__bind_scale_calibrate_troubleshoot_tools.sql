-- 工厂侧 E2E 第6轮 (Cluster B) 发现: 两个电子秤运维意图 tool_name 为 NULL
-- → 识别成功但无绑定执行器 → 落 ToolRouter 动态兜底 (向量检索 + LLM 选择)
-- → 可能误选到不相关的 WRITE 工具 (审计报告示例: material_mark_abaca 标记剑麻, 危险)。
-- 工具类已注册 (ScaleCalibrateTool / ScaleTroubleshootTool), 仅 DB 未绑定。
--
-- SCALE_CALIBRATE   (电子秤校准)   → scale_calibrate
-- SCALE_TROUBLESHOOT (电子秤故障排查) → scale_troubleshoot
--
-- 绑定后 = 确定性路由到正确工具, 消除动态兜底误选 write 工具的风险。
-- 幂等 (仅绑 tool_name 仍空的行)。
-- ⚠️ 部署后必须调 POST /api/mobile/smartbi-config/intents/reload 清 Redis 意图缓存
--    (allIntents 缓存 Redis-backed, JVM 重启不清; 见 feedback_ai_intent_config_redis_cache_reload)。
--
-- 注: 25 个其余 NULL-tool FACTORY 意图含破坏性 (USER_DELETE/EQUIPMENT_DELETE/
--    DATA_BATCH_DELETE/INVENTORY_CLEAR) 故意不在此绑定 —— 给删除意图绑删除工具 =
--    启用破坏, 安全姿态是保持 unbound (不可执行) 或后续加二次确认门, 逐个判定。

UPDATE ai_intent_configs
SET tool_name = 'scale_calibrate', updated_at = NOW()
WHERE intent_code = 'SCALE_CALIBRATE'
  AND (tool_name IS NULL OR tool_name = '');

UPDATE ai_intent_configs
SET tool_name = 'scale_troubleshoot', updated_at = NOW()
WHERE intent_code = 'SCALE_TROUBLESHOOT'
  AND (tool_name IS NULL OR tool_name = '');
