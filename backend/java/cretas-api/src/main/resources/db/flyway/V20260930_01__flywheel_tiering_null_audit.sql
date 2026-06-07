-- V20260930_01: 飞轮质量治理 — 置信度分层 + is_active 列补全 + NULL 存量审计
--
-- 背景:
--   V20260415_99 bootstrap 创建 ai_learned_expressions 时未含 is_active 列。
--   Hibernate ddl-auto=update 后续加列但不回填，导致存量 ~2588 行 is_active=NULL。
--   本 migration 做两件事：
--     Piece 1: 确保 is_active 列存在（幂等 IF NOT EXISTS），并修复 DEFAULT。
--     Piece 2: NULL 存量 Deactivate 候选 —— ⛔ WHERE 条件待 Opus 审批后确认。
--
-- ⛔ IMPORTANT: Piece 2 的 UPDATE 语句目前注释掉。
-- 请 Opus 审阅下方「NULL 审计样本」(由 ExpressionLearningService.auditNullIsActive() 输出)
-- 确认 WHERE 条件范围后，取消注释并重新部署。
-- ----------------------------------------------------------------

-- ================================================================
-- Piece 1: 确保 is_active 列存在（prod/test 已由 Hibernate 加列，CI 幂等补全）
-- ================================================================
ALTER TABLE ai_learned_expressions
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN DEFAULT TRUE;

-- 修复 DEFAULT（Hibernate 不设 DEFAULT，导致新列无默认值约束）
ALTER TABLE ai_learned_expressions
    ALTER COLUMN is_active SET DEFAULT TRUE;

-- 确保索引存在（Hibernate 会加但 CI 需要幂等保证）
CREATE INDEX IF NOT EXISTS idx_ale_is_active ON ai_learned_expressions(is_active);

-- ================================================================
-- Piece 2: NULL 存量 Deactivate 候选（⛔ 待 Opus 审批）
-- ================================================================
--
-- 审计依据 (2026-06-07 prod 快照):
--   SELECT COUNT(*), source_type
--   FROM ai_learned_expressions
--   WHERE is_active IS NULL
--   GROUP BY source_type;
--
-- 建议 WHERE 条件（基于 Guard B + Guard C 追溯逻辑）:
--   1) tool_name=NULL 投毒行: ai_intent_configs 里 tool_name IS NULL 的意图所有 NULL 行
--   2) 业态不兼容行: 工厂类型与意图 business_type 不匹配的 NULL 行
--
-- ⛔ 以下 UPDATE 语句注释掉，等待 Opus gate 审批后确认 WHERE scope：
--
-- UPDATE ai_learned_expressions ale
-- SET    is_active  = false,
--        updated_at = NOW()
-- WHERE  ale.is_active IS NULL
--   AND  (
--          -- 条件 A: 绑定的意图 tool_name=NULL（Guard B 追溯）
--          EXISTS (
--              SELECT 1 FROM ai_intent_configs aic
--              WHERE  aic.intent_code = ale.intent_code
--                AND  (aic.tool_name IS NULL OR aic.tool_name = '')
--          )
--          OR
--          -- 条件 B: 业态不兼容（Guard C 追溯）——
--          -- 餐饮工厂 (factory_id LIKE 'RES_%' OR 'QHJ%') 的 FACTORY 意图 NULL 行
--          -- ⛔ 需 Opus 确认 factory_id 命名规律是否可靠
--          (
--            ale.intent_code IN (
--                SELECT intent_code FROM ai_intent_configs
--                WHERE business_type = 'FACTORY'
--            )
--            AND ale.factory_id IN (
--                SELECT id FROM factories WHERE business_type = 'RESTAURANT'
--            )
--          )
--        );
--
-- 剩余 NULL 行（不符合条件 A/B 的）保留为 NULL，
-- 待后续 Piece 1 的 DEFAULT TRUE 约束让新行自动填充，
-- 存量 NULL 由单独 backfill 决策（见下方 Piece 3 optional）。

-- ================================================================
-- Piece 3 (optional, 可选): 将剩余良性 NULL 回填为 true
-- ================================================================
-- 如果 Opus 确认剩余 NULL 行均为良性（非投毒），可执行：
-- UPDATE ai_learned_expressions
-- SET    is_active  = true,
--        updated_at = NOW()
-- WHERE  is_active IS NULL;
--
-- ⛔ 不自动执行，防止将投毒行 NULL→true 激活。
