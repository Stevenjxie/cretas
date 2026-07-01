-- 半成品盘点「一厂一进行中」DB 层唯一约束 (partial unique index)
-- 根治 SemiFinishedStocktakeServiceImpl.initiate 的 check-then-act TOCTOU:
--   原逻辑 = countActiveStocktake(factoryId) 读到 0 → 再 INSERT, 非原子。
--   两个并发 initiate 可同时读到 0 → 都创建一个进行中盘点 → 一厂两活跃 (UX 去重被击穿)。
-- 财务正确性本已由 @Version + uk_voucher_source_business 保护 (apply 阶段), 这里是 UX 去重硬化。
--
-- 语义 (与服务层 countActiveStocktake + 实体 @Where 完全一致):
--   活跃(非终态) = status NOT IN ('APPLIED','REJECTED')
--   终态 APPLIED / REJECTED 排除 → 上一个盘点生效/驳回后即可再发起 (周复盘节奏)
--   deleted_at IS NULL 谓词必须带 —— 实体 SemiFinishedStocktake 有 @Where(deleted_at IS NULL),
--     Hibernate 给 countActiveStocktake 自动追加 deleted_at IS NULL; 索引不带会比预检更严
--     (软删的活跃行仍占位), 二者必须同谓词才一致。
--
-- 索引作用: 竞态输家 INSERT 命中此唯一索引 → DataIntegrityViolationException,
--   服务层 catch 后转成与预检同一句友好 409 (见 initiate() 的 saveAndFlush try/catch)。
--
-- PG-only: partial(filtered) unique index 是 PostgreSQL 特性 (镜像 V20260419_01
--   invoice_records_unique_pending 同 pattern)。项目已 PG-only (database-entity-sync rule);
--   test profile 禁用 Flyway 走 H2 ddl-auto=create-drop (application-test.properties),
--   此 DDL 不经过 H2 路径 —— 前一实现者对 H2 partial-index 支持的疑虑因此不成立。
--
-- 上线安全: prod cretas_prod_db 已核实 semi_finished_stocktakes 为空 (0 行, 2026-07-01
--   read-only 核查), 无任一工厂已有 2+ 活跃盘点 → CREATE UNIQUE INDEX 不会失败。
--   CREATE ... IF NOT EXISTS 保证重跑幂等。表 + 列均由 V20261027_23 建 (Flyway 顺序 23 < 27)。

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_stocktake_one_active
    ON semi_finished_stocktakes (factory_id)
    WHERE status NOT IN ('APPLIED', 'REJECTED') AND deleted_at IS NULL;
