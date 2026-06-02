-- V20260905_02__product_type_grams_per_unit.sql
--
-- 报工体 P0-2: product_types 加标准克重 (1份/盒 = X 克), 供末道"份/盒→kg"折算整批出成率
-- + 成品入库换算。spec §3.2。
--   NULL = 该产品无标准克重 (跨单位出成率/折算保持诚实 null, 不臆造)。
--
-- ⚠️ 表存在守卫 (per feedback_e2e_pr_gate_freshdb_flyway_startup_debt): product_types 是
--   Hibernate JPA entity (无 Flyway CREATE), 全新 CI DB 上 Flyway 先于 ddl-auto 跑时该表
--   不存在, 裸 ALTER 报 "relation does not exist" 阻断启动。to_regclass 守卫: 表存在才
--   ALTER; 不存在则跳过 (Hibernate 随后按 entity 建表+列, entity 已声明 grams_per_unit)。
--   prod 该表早已存在 → 守卫无行为改变; ADD COLUMN IF NOT EXISTS 幂等可重跑。
--   validate-on-migrate=false → 编辑已 apply migration 不破 prod checksum 校验。

DO $$
BEGIN
    IF to_regclass('public.product_types') IS NOT NULL THEN
        ALTER TABLE product_types
          ADD COLUMN IF NOT EXISTS grams_per_unit NUMERIC(10,2);
    END IF;
END $$;
