-- 应付发票唯一性: 从「每采购订单一条」改成「每收货单一条」
--
-- 背景 (2026-08-03 prod 实证, F006 PO-20260707-0002):
--   分批到货的采购订单, 第二批<b>永远入不了库</b>。
--     7/07 第一批 3.456kg → 确认收货 → 记应付 42.65 元 ✓
--     8/03 第二批 6.544kg → 确认收货 → 想再记应付 80.75 元 → 撞唯一约束 → 整个事务回滚 → 货没入库
--   服务端日志逐条对上:
--     INFO  自动应付挂账(实收值): receivedValue=80.75   ← 代码以为自己成功了, 先打了成功日志
--     ERROR duplicate key value violates unique constraint "uk_aat_ap_invoice_per_po"   ← 下一毫秒 flush 才炸
--     WARN  数据冲突 (唯一约束) → 全局兜底成通用 409「数据已存在, 请勿重复提交」
--   界面报错完全指不到真因(应付发票唯一), 运维无法自查。
--
-- 为什么是约束错而不是代码错:
--   PurchaseServiceImpl#confirmReceive 的注释 (2026-07-02 doomed-tx 修复) 白纸黑字写着
--   「幂等键改为每张入库单 (PURCHASE_RECEIVE, receiveId): 同一入库单重复确认不重复挂账;
--     分批入库<b>各挂各的</b>实收值, 累计 = 实收总额」。
--   「各挂各的」= 一个 PO 会有多条 AP_INVOICE —— 正是旧约束禁止的。当时只改了代码没改约束,
--   所以那次修复从未真正生效, 症状只是从「抛异常」变成「insert 时炸」。
--
-- 新口径: (factory_id, source_type, source_id) 唯一 —— 与代码的幂等键完全一致。
--   · 同一张收货单重复确认 → 命中唯一键 → 仍然不会重复挂账 (幂等保持)
--   · 分批到货的不同收货单 → source_id 不同 → 各记各的 ✓
--   · source_id IS NULL 的历史行 (49 条 legacy) 不纳入本约束, 保持现状不受影响
--
-- 影响面 (prod 实查): 15 张采购单卡在「已记应付且仍有待收量」, 本迁移后第二批可正常入库;
--   全库 96 条 AP_INVOICE, 同一 PO 多条的现存 0 条 → 新约束不会撞历史数据。

-- 1) 先建新约束 —— 若历史数据违反会在此失败, 不会留下"旧的删了新的没建上"的中间态
CREATE UNIQUE INDEX IF NOT EXISTS uk_aat_ap_invoice_per_receipt
    ON ar_ap_transactions (factory_id, source_type, source_id)
    WHERE transaction_type = 'AP_INVOICE'
      AND deleted_at IS NULL
      AND source_id IS NOT NULL;

-- 2) 再删旧的「每 PO 一条」约束
DROP INDEX IF EXISTS uk_aat_ap_invoice_per_po;
