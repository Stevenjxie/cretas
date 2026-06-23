-- D-BUG3: 采购异常单 RETURN_OVER 决策真正创建采购退货单 (PURCHASE_RETURN ReturnOrder)。
--
-- 此前 decideException(RETURN_OVER) 的 javadoc 声称会创建退货单, 但方法体只 setDecision/
-- setStatus('RESOLVED'), 不创建任何 ReturnOrder → 误导性死路。前端 procurement/exceptions/list.vue
-- 的 "退货(拒收,退回供应商)" 选项选了变 RESOLVED 却无下游退货财审链。
--
-- 本迁移新增 return_order_id 列, 记录该异常单转出的退货单 ID:
--   1. 提供 异常单 → 退货单 的可追溯关联;
--   2. 作为幂等键 — RETURN_OVER 重复决策时, 若已有 return_order_id 则不重复创建退货单。
--
-- prod profile 用 ddl-auto=validate/none, 实体新列必须由本迁移创建 (dev=update 会自动建,
-- 故全部 IF NOT EXISTS 幂等)。

DO $$
BEGIN
    IF to_regclass('public.purchase_exceptions') IS NULL THEN
        RAISE NOTICE 'V20261027_05 skipped: purchase_exceptions not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE purchase_exceptions
        ADD COLUMN IF NOT EXISTS return_order_id VARCHAR(191);

    COMMENT ON COLUMN purchase_exceptions.return_order_id IS
        'D-BUG3: RETURN_OVER 决策生成的采购退货单 (ReturnOrder) ID; 作为幂等键防重复创建';

    CREATE INDEX IF NOT EXISTS idx_pe_return_order
        ON purchase_exceptions(return_order_id);

    -- HIGH (并发幂等): 乐观锁 version 列。两个并发 decideException(RETURN_OVER) 读同 version,
    -- 一个 save 成功 (version+1), 另一个抛 OptimisticLockingFailureException → 只生成一张退货单。
    -- 现有行 default 0 (NOT NULL), 新行由 Hibernate 维护。
    ALTER TABLE purchase_exceptions
        ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

    COMMENT ON COLUMN purchase_exceptions.version IS
        'HIGH: 乐观锁版本号, 防并发 RETURN_OVER 重复创建退货单';
END $$;
