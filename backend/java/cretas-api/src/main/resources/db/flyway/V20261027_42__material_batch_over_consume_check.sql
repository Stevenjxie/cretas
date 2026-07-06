-- 🔒🔒 库存完整性: material_batches 禁止超扣 (used_quantity + reserved_quantity ≤ receipt_quantity)。
--
-- 背景: 逐工序报工的原料消耗采用「延迟扣减」—— 报工时只写 material_consumptions 行 (无 used 扣减),
--   真正扣减 usedQuantity 延到小结 (InterimSettleServiceImpl)。该扣减的负库存守卫 (BATCH_INSUFFICIENT,
--   receipt−used−reserved≥0, 悲观锁) 在 #1204 (2026-07-03) 才补上; 此前的小结窗口里 3 个 F006 批次被
--   累计超扣 (单条消耗即超收货量), 落库 used>receipt 且无 material_batch_adjustments 轨迹。
--
-- 本迁移做两件事 (原子):
--   1. 数据订正: 把 3 个已知 F006 违规行的 used_quantity clamp 回 (receipt − reserved), 并补审计行。
--      id 硬编码 + factory_id='F006' 双重限定 + 仅当仍违规才动 → 幂等, 绝不碰 LIUSHANMEN / 其他租户。
--   2. 加 CHECK 约束 ck_material_batch_no_overconsume 作最硬兜底: 任何漏接的写入点 / 未来回归
--      都无法把批次扣成负库存 (loud-fail, 防呆 Rule 1)。应用层 MaterialBatch.assertConsumptionInvariant()
--      给友好文案在先, GlobalExceptionHandler 把本约束违规映射为 409 BATCH_OVER_CONSUMED。
--
-- 全表扫描 (2026-07-06, cretas_prod_db): 仅这 3 个 F006 行违规, 含软删/其他租户/其他 factory 均 0,
--   receipt_quantity NULL 行 0 → NUMERIC 精确无浮点误伤, clamp 后加约束安全。
--
-- Flyway 先于 Hibernate DDL 跑 (fresh CI DB), material_batches 可能尚未建表 → to_regclass 守卫跳过。

DO $$
BEGIN
    IF to_regclass('public.material_batches') IS NULL THEN
        RAISE NOTICE 'V20261027_42 skipped: material_batches not present before Hibernate DDL';
        RETURN;
    END IF;

    -- ── 1. 数据订正: 3 个已知 F006 超扣行 (幂等: 仅当仍违规) ──
    IF to_regclass('public.material_batch_adjustments') IS NOT NULL THEN
        INSERT INTO material_batch_adjustments
            (id, material_batch_id, adjustment_type, adjustment_quantity,
             quantity_before, quantity_after, reason, notes, adjusted_by, adjustment_time,
             created_at, updated_at)
        SELECT gen_random_uuid()::text,
               mb.id,
               'DECREASE',
               (mb.receipt_quantity - COALESCE(mb.reserved_quantity, 0)) - mb.used_quantity,  -- 负数
               mb.used_quantity,
               mb.receipt_quantity - COALESCE(mb.reserved_quantity, 0),
               '历史超扣校正 (#1204 前小结窗口延迟扣减遗留)',
               'V20261027_42: clamp used_quantity 回 receipt−reserved, 恢复 used+reserved≤receipt 不变式',
               1309,  -- f006_admin (本批次实际操作人)
               now(), now(), now()
        FROM material_batches mb
        WHERE mb.id IN (
                '1f1e2976-af8d-4731-8733-b503bad90ef9',
                '868fce32-fb0f-4dfe-a717-22fae6c2c5f2',
                'a2fe43ab-ac1d-42e4-ba0f-c12f7d21b4e5')
          AND mb.factory_id = 'F006'
          AND (COALESCE(mb.used_quantity, 0) + COALESCE(mb.reserved_quantity, 0)) > mb.receipt_quantity;
    END IF;

    UPDATE material_batches mb
       SET used_quantity = mb.receipt_quantity - COALESCE(mb.reserved_quantity, 0),
           updated_at = now()
     WHERE mb.id IN (
             '1f1e2976-af8d-4731-8733-b503bad90ef9',
             '868fce32-fb0f-4dfe-a717-22fae6c2c5f2',
             'a2fe43ab-ac1d-42e4-ba0f-c12f7d21b4e5')
       AND mb.factory_id = 'F006'
       AND (COALESCE(mb.used_quantity, 0) + COALESCE(mb.reserved_quantity, 0)) > mb.receipt_quantity;

    -- ── 2. 加 CHECK 约束 (最硬兜底) ──
    -- 防御: 若仍有任何残余违规行 (理论上前一步已清), 先 loud-fail 阻止加约束 (给出清晰错误而非裸 fail)。
    IF EXISTS (
        SELECT 1 FROM material_batches
        WHERE (COALESCE(used_quantity, 0) + COALESCE(reserved_quantity, 0)) > receipt_quantity
    ) THEN
        RAISE EXCEPTION 'V20261027_42 abort: 仍存在 used+reserved>receipt 违规行, 无法加约束 — 请先人工核查';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_material_batch_no_overconsume'
          AND conrelid = 'public.material_batches'::regclass
    ) THEN
        ALTER TABLE material_batches
            ADD CONSTRAINT ck_material_batch_no_overconsume
            CHECK (COALESCE(used_quantity, 0) + COALESCE(reserved_quantity, 0) <= receipt_quantity);
    END IF;
END $$;
