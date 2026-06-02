-- V20260906_01__backfill_production_batch_product_name.sql
--
-- P0-1 产品名绑定 (F006 报工审计修复) — 回填历史占位行。
--
-- Background:
--   ProcessingServiceImpl.createBatch 历史逻辑: 创建批次时若 productName 缺省, 直接落
--   占位符 "待设置产品名称", 从不从 productTypeId 解析真实品名。结果生产批次/报工界面
--   显示占位符 (如 F006 应显示 "叮咚好食光轻卤门腔（猪舌）120g" 却显示 "待设置产品名称")。
--   代码侧已修 (createBatch 现按 productTypeId 解析品名 + 防呆快速失败)。
--   本迁移回填存量数据: 把历史占位行的 product_name 用 product_types.name 重写。
--   spec: P0-1 产品名绑定 task 1.2
--
-- 幂等 + 安全:
--   - to_regclass 守卫: 两表 (production_batches / product_types) 都存在才执行
--     (防 fresh-DB / Hibernate-ddl-auto 先于 Flyway 时表尚未建 → 裸 UPDATE 报错阻断启动)。
--   - 纯 DML, WHERE 限定只改占位符行 (product_name = '待设置产品名称'),
--     不动任何已有真实品名的行。重复执行: 第二次跑时占位行已被改成真实名 → WHERE 不命中
--     → no-op。
--   - JOIN product_types 同工厂 (factory_id 一致) + id 匹配; 解析不到 name 的行 (productTypeId
--     在 product_types 无对应 / 跨工厂) 自然不被 UPDATE 命中, 留占位符不动 (不硬改成 NULL)。
--   - product_types.name NOT NULL, 所以命中的行回填的一定是非空真实品名。

DO $$
BEGIN
    IF to_regclass('public.production_batches') IS NOT NULL
       AND to_regclass('public.product_types') IS NOT NULL THEN

        UPDATE production_batches pb
        SET product_name = pt.name,
            updated_at = NOW()
        FROM product_types pt
        WHERE pb.product_type_id = pt.id
          AND pb.factory_id = pt.factory_id
          AND pb.product_name = '待设置产品名称'
          AND pt.name IS NOT NULL
          AND pt.name <> '';

        RAISE NOTICE 'V20260906_01: production_batches placeholder product_name backfilled from product_types';
    ELSE
        RAISE NOTICE 'V20260906_01 skipped: production_batches or product_types table absent (fresh/minimal DB), non-fatal';
    END IF;
END $$;
