-- Feature #7 (R12): 已过账凭证红字冲销 (金蝶规范).
-- POSTED 凭证不可直接 VOID, 应生成红字冲销凭证 (借贷互换) 关联原凭证,
-- 原凭证标记 REVERSED (不物理删), 账簿可追溯。
--
-- 新增两个自关联字段:
--   reversal_voucher_id  — 原凭证指向其红字冲销凭证 (一对一)
--   original_voucher_id  — 红字冲销凭证指回原凭证
--
-- 幂等 (prod=validate 但本地/test=migrate): IF NOT EXISTS 防重复 apply。

ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS reversal_voucher_id VARCHAR(191);
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS original_voucher_id VARCHAR(191);

-- 红字冲销凭证按原凭证反查的索引 (审计 / 防重复冲销时用)
CREATE INDEX IF NOT EXISTS idx_v_original_voucher ON vouchers (original_voucher_id);
