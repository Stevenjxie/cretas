-- #1214 静默漂移缺口修复: 撤销小结退回一个同厂调拨(TRF-child)成品批次至归零(REVERSED)时,
-- 现在连带把对应 internal_transfers 记录置 REVERSED + 写操作提示(见 FinishedGoodsFeedServiceImpl
-- #reconcileTransferForRetiredChild)。审批执行结果(reverseInterimSettle result map 的
-- transferReconcileHints)持久化快照到申请记录, 供审批中心/操作员事后核实"哪些调拨记录被连带冲销,
-- 物理货物是否已实际归位"(fool-proof Rule 2/5: 操作提示不能只落日志, 必须留痕可查)。
--
-- 新增列可空 (向后兼容, 无连带冲销的历史/新申请此列为 NULL); 无 CHECK 约束 (纯文本快照, 同
-- affected_batch_numbers 前例)。

ALTER TABLE interim_settle_reversal_request
    ADD COLUMN IF NOT EXISTS transfer_reconcile_hints TEXT NULL;

COMMENT ON COLUMN interim_settle_reversal_request.transfer_reconcile_hints IS
    '执行时快照: 撤销小结连带冲销的同厂调拨记录操作提示(分号分隔), 提示物理货物需人工核实/退回; 无连带冲销为 NULL';
