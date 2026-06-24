-- 结转损益自动凭证 (P1): accounting_periods 加 closing_posted_at。
-- 标记该期结转凭证是否已过账 (方案A: 期间 LOCKED 后由 finalize scheduler 过账)。
-- NULL = 未结转; finalize 只处理 NULL 的 LOCKED 期间 (幂等)。反结账时清回 NULL。
-- 幂等 (IF NOT EXISTS)。

ALTER TABLE accounting_periods ADD COLUMN IF NOT EXISTS closing_posted_at TIMESTAMP;
