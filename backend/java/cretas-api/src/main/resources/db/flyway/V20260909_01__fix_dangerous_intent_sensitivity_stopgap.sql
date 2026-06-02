-- Stop-gap (2026-06-02): correct mislabeled destructive intents whose sensitivity / approval
-- flags let them slip the write gates.
--
-- Gate semantics (audit-verified): needsApproval() == (requires_approval AND sensitivity='CRITICAL');
-- requiresConfirmation is set during recognition for sensitivity in {HIGH, CRITICAL}.
--
-- Ground-truth from prod ai_intent_configs (510 active intents): 72 destructive-suffix intents,
-- only 5 CRITICAL; several irreversible / security-critical ops were mislabeled LOW or
-- HIGH-without-approval, so they engaged neither gate. This migration fixes the labels.
--
-- Idempotent (WHERE intent_code IN / sensitivity='CRITICAL'); safe to re-run.
-- NOTE: behavioral enforcement engages on intent-config cache reload (next deploy). The FULL
-- server-side write-guard (a single orchestrator choke point that also closes the
-- forceExecute / multi-intent / dynamic-planner / skill bypass, driven off tool action-type
-- not this hand-maintained column) is W0 of the classifier redesign — this is a label stop-gap only.

-- Group 1: irreversible / security-critical -> CRITICAL + approval (engages the needsApproval hard gate)
UPDATE ai_intent_configs
SET sensitivity_level = 'CRITICAL', requires_approval = true
WHERE intent_code IN (
  'MONTHLY_FINANCIAL_CLOSE',  -- 月度财务结账, irreversible, was LOW
  'SYSTEM_PASSWORD_RESET',    -- 系统密码重置, security, was LOW
  'PERIOD_CONFIRM_CLOSE',     -- 结账锁, irreversible, was HIGH/no-approval
  'PERIOD_REOPEN',            -- 反结账, irreversible, was HIGH/no-approval
  'HR_EMPLOYEE_DELETE'        -- 删除员工, was HIGH/no-approval/OPEN-roles
);

-- Group 2: destructive but recoverable -> HIGH + approval (engages requiresConfirmation + sets approval flag)
UPDATE ai_intent_configs
SET sensitivity_level = 'HIGH', requires_approval = true
WHERE intent_code IN (
  'RESTAURANT_DISH_DELETE',   -- 删除菜品, was NULL sensitivity (untyped)
  'INVENTORY_FREEZE',         -- 冻结库存, was HIGH/no-approval
  'FACTORY_MR_CLOSE',         -- was MEDIUM/no-approval
  'PERIOD_REQUEST_CLOSE'      -- was MEDIUM/no-approval
);

-- Group 3 (principle): every CRITICAL intent must engage the approval gate.
UPDATE ai_intent_configs
SET requires_approval = true
WHERE sensitivity_level = 'CRITICAL'
  AND (requires_approval IS NULL OR requires_approval = false);
