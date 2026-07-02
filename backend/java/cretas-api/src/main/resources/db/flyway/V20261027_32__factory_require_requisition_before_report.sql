-- V20261027_32__factory_require_requisition_before_report.sql
--
-- ② Part B 生产领料单 Gate — 工厂级"报工前必须领料确认"开关 (opt-in, 默认 OFF)。
--
-- 背景 (LIUSHANMEN 六扇门 目标料流):
--   采购 → 原料仓(主仓) → 生成领料单 → 仓管拣货确认 → 调拨到生产仓 → 报工消耗。
--   客户张权(F006 仓管场景)诉求: "仓管没确认领料，生产不能报工" (防呆 = 卡在报工前, 强制先领料)。
--
-- 为什么 opt-in 默认 OFF (向后兼容铁律):
--   LIUSHANMEN 等现有工厂当前料流是报工直接从原料仓/物流仓消耗 (② Part A 宽松 ensureRawMaterialWarehouse),
--   立刻强制领料 gate 会 BLOCK 正在用的真客户。故本列 DEFAULT FALSE:
--     • FALSE (默认, 所有工厂) → 报工照旧, ZERO 行为变化。
--     • TRUE  (未来 per-factory 培训后手动开启) → 报工前该计划必须有仓管已确认的领料单 (TRANSFERRED/ISSUED/IN_USE)
--       且覆盖被消耗物料, 否则 BLOCKING + 明确指引 ("请先在该计划生成领料单并由仓管确认领料到生产仓")。
--
-- 多租户安全: 列 DEFAULT FALSE → 任何未显式配置的工厂 (现有 + 未来) 默认关闭, 零回归。
--   本迁移不 seed 任何工厂为 TRUE (含 F006) —— 需人工在「工厂配置」页逐工厂开启, 培训到位后再开。
--
-- 幂等: ADD COLUMN IF NOT EXISTS + DEFAULT FALSE。

ALTER TABLE factory_settings
    ADD COLUMN IF NOT EXISTS require_requisition_before_report BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN factory_settings.require_requisition_before_report IS
    '工厂级"报工前必须领料确认"开关 (默认 false = 报工照旧, 从物料所在原料仓/物流仓消耗). true = 报工前该生产计划必须有仓管已确认(拣货+调拨)的领料单覆盖被消耗物料, 否则 BLOCKING. 需人工在工厂配置页 per-factory 开启, 用于强制"仓管没确认领料，生产不能报工"料流.';
