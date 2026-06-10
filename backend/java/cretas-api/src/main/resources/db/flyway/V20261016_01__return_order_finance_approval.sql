-- V20261016_01__return_order_finance_approval.sql
--
-- 六扇门 Tier0 #16 (requirements-catalog 行2399-2416): 退货财务审批门。
--
-- 客户原话 (六扇门第N次会议, contradiction 现场纠正):
--   "先说不用审批直接给仓库，后纠正：退货跟钱有关要先财务审批确认；
--    跟钱有关的东西都要审批" / "退货单发财务审批，审批后给仓管把实物拿走(出货管理)"。
--
-- 设计:
--   退货状态机插入财务审批节点: APPROVED → FINANCE_APPROVED → COMPLETED。
--   - finance_approved_by / finance_approved_at: 记录财务审批人与时间 (nullable, 兼容历史)。
--   - completeReturnOrder 前置状态从 APPROVED 收紧为 FINANCE_APPROVED (service 层把关)。
--   - 仅 finance:read_write 角色可调用 finance-approve 端点。
--
-- 向后兼容铁律:
--   - 两列 nullable, 不回溯历史已完成 (COMPLETED) 退货单。
--   - 历史处于 APPROVED 的在途单: 新流程要求其先走一次 finance-approve 才能完成
--     (不自动 backfill, 避免把未经财务确认的资金影响静默放行 — 与"跟钱有关都要审批"一致)。
--   - FINANCE_APPROVED 是 ReturnOrderStatus enum 新增值, status 列为 VARCHAR(32) 足够容纳。

ALTER TABLE return_orders
    ADD COLUMN IF NOT EXISTS finance_approved_by BIGINT NULL,
    ADD COLUMN IF NOT EXISTS finance_approved_at TIMESTAMP NULL;

COMMENT ON COLUMN return_orders.finance_approved_by IS '六扇门#16 退货财务审批人用户ID (null=未财务审批)';
COMMENT ON COLUMN return_orders.finance_approved_at IS '六扇门#16 退货财务审批时间 (null=未财务审批)';
