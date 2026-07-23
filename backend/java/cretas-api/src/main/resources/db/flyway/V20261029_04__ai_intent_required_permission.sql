-- AI 读写分块 P1 (2026-07-23 spec): 意图级权限并轨到 module:action 权限矩阵
ALTER TABLE ai_intent_configs ADD COLUMN IF NOT EXISTS required_permission VARCHAR(64) NULL;
COMMENT ON COLUMN ai_intent_configs.required_permission IS '权限码 module:action; 非空优先于 required_roles; 空回落旧逻辑';
