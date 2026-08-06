-- =============================================================================
-- 回滚 V20261029_61 —— 删掉收货可追溯的三列
--
-- 手动执行, Flyway 不跑本目录。
--
-- ⚠️ 删列会**丢掉已经填进去的合同号/供应商批次号/件数**, 不可逆。
--    执行前先看一眼有没有数据:
--      SELECT count(*) FROM purchase_receive_items
--       WHERE contract_number IS NOT NULL OR supplier_batch_number IS NOT NULL OR box_count IS NOT NULL;
--    有数据就先导出, 或者干脆别回滚 —— 这三列可空, 留着不影响任何既有逻辑。
-- =============================================================================

DO $$
DECLARE
    v_filled integer;
BEGIN
    SELECT count(*) INTO v_filled
      FROM purchase_receive_items
     WHERE contract_number IS NOT NULL
        OR supplier_batch_number IS NOT NULL
        OR box_count IS NOT NULL;
    IF v_filled > 0 THEN
        RAISE NOTICE '⚠️ 有 % 行收货明细已填过这三列, 删列会丢数据。确认要继续请注释掉这段 RAISE EXCEPTION。', v_filled;
        RAISE EXCEPTION '回滚中止: 存在已填数据';
    END IF;
END $$;

DROP INDEX IF EXISTS idx_mb_supplier_batch_number;
DROP INDEX IF EXISTS idx_mb_contract_number;

ALTER TABLE material_batches
    DROP COLUMN IF EXISTS supplier_batch_number,
    DROP COLUMN IF EXISTS contract_number;

ALTER TABLE purchase_receive_items
    DROP COLUMN IF EXISTS box_count,
    DROP COLUMN IF EXISTS supplier_batch_number,
    DROP COLUMN IF EXISTS contract_number;
