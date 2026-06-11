-- V20261018_02__factory_skip_process_reporting_default.sql
--
-- 工厂级"免工序报工默认值" (Fable 审计修复 — 多租户安全红线)。
--
-- 背景 (Fable 对抗审计 2026-06-11):
--   #718 报工模型重设计把 createProductionPlan 的 skipProcessReporting null → 全系统默认 true。
--   后果: 其他工厂 (逐道报工是其溯源/成本/出成率/自学习/人效价值) 从 RN/AI 新建计划
--   时该字段恒 null → 静默变两点 spawn → 逐道能力静默失效, 零提示。
--   转录只六扇门 (F006) 一家要两点, 不支持全系统默认两点。
--
-- 修复设计 (Steve 拍板倾向 + Fable 推荐: 工厂级配置):
--   factory_settings 加 skip_process_reporting_default (BOOLEAN NOT NULL DEFAULT FALSE)。
--   createProductionPlan 的 null → 解析为"该工厂的默认值":
--     • F006 (六扇门) = TRUE  → RN/AI/web 新建仍默认两点 (他们 want)。
--     • 其他工厂      = FALSE → 默认逐道报工 (不误伤溯源/成本/出成率/自学习/人效)。
--
-- 多租户安全保证:
--   1. 列 DEFAULT FALSE → 任何未显式 seed 的工厂 (现有 + 未来新建) 默认逐道, 零回归。
--   2. 只 F006 显式 seed TRUE (转录唯一要两点的客户)。
--   3. createProductionPlan 解析 default 时带 factoryId (租户隔离), 一个工厂的配置不影响另一个。
--   4. production_plans.skip_process_reporting 列本身仍 DEFAULT FALSE (V20261017_01),
--      现有计划行不变 (向后兼容铁律)。本迁移只改"新建计划 null 的解析口径"。
--
-- 幂等: ADD COLUMN IF NOT EXISTS + DEFAULT FALSE; F006 seed 用条件 UPDATE (无行则 no-op)。

ALTER TABLE factory_settings
    ADD COLUMN IF NOT EXISTS skip_process_reporting_default BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN factory_settings.skip_process_reporting_default IS
    '工厂级免工序报工默认值 (默认 false = 新建计划默认逐道报工). true = 新建计划默认两点报工 (领料入+产出出). createProductionPlan 对 skipProcessReporting=null 的计划取此值. 仅六扇门类需两点报工的工厂置 true; 其他工厂保持 false 以保留逐道溯源/成本/出成率/自学习/人效.';

-- 六扇门 (F006) 是转录中唯一要两点报工的客户: seed default = true。
-- 若 F006 尚无 factory_settings 行, 此 UPDATE no-op; createProductionPlan 解析逻辑对
-- "无 settings 行 / settings 行该列为 null" 兜底为 false (即逐道), 故需确保 F006 有行并置 true。
UPDATE factory_settings
   SET skip_process_reporting_default = TRUE
 WHERE factory_id = 'F006';

-- 兜底: F006 若无 settings 行则插入一行 (仅含必要列 + 该 default), 其余列走表 DEFAULT。
-- factory_id 唯一约束 → ON CONFLICT DO UPDATE 保证幂等。
INSERT INTO factory_settings (factory_id, skip_process_reporting_default)
VALUES ('F006', TRUE)
ON CONFLICT (factory_id) DO UPDATE
    SET skip_process_reporting_default = TRUE;
