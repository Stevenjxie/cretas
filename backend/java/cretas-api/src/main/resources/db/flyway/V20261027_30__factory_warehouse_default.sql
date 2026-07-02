-- 工厂默认仓路由配置表 (factory_warehouse_default)
-- 目的: 把 WarehouseResolver 里硬编码的仓库 code (WH-LOG / WH-WKS / WH-RD) 变成 per-factory 可配置。
--
-- 背景: WarehouseResolver.resolveLogisticsId / resolveWorkshopId / resolveRdId 原本固定解析
--   WarehouseCodes.WH_LOG / WH_WKS / WH_RD。客户 LIUSHANMEN 想自定义仓库结构 (外仓/主仓/工厂仓/研发库),
--   需要让每个 purpose (物流默认/车间默认/研发默认) 路由到该工厂自选的 factory_warehouses 记录。
--
-- 向后兼容 (核心): 本表为 additive。未配置的工厂 (无对应 purpose 行) → resolver 回退到原硬编码 code
--   查询, 行为与现状 100% 一致。仅当工厂显式写入一行配置时才改变路由目标。
--
-- purpose 取值 (与 WarehouseDefaultPurpose 枚举一致):
--   LOGISTICS_DEFAULT — resolveLogisticsId (采购入库/销售/原料默认/调拨/退货/领料)
--   WORKSHOP_DEFAULT  — resolveWorkshopId  (生产成品/报工消耗/结算)
--   RD_DEFAULT        — resolveRdId        (试制批次产出)
--
-- warehouse_id 指向 factory_warehouses.id (UUID/varchar)。resolver 使用时会校验该 id 仍解析到
--   同工厂未软删的 factory_warehouses 记录; 若指向已删除/不存在的仓库 (悬空配置) → 回退硬编码, 不报错。
--
-- 唯一约束: 一个工厂每个 purpose 至多一行有效配置 (partial unique on deleted_at IS NULL),
--   镜像 V20261027_27 的 partial-unique pattern。PG-only 特性; test profile 走 H2 ddl-auto 不经此 DDL。

CREATE TABLE IF NOT EXISTS factory_warehouse_default (
    id           VARCHAR(64) NOT NULL,
    factory_id   VARCHAR(64) NOT NULL,
    purpose      VARCHAR(32) NOT NULL,   -- LOGISTICS_DEFAULT / WORKSHOP_DEFAULT / RD_DEFAULT
    warehouse_id VARCHAR(64) NOT NULL,   -- → factory_warehouses.id
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMP,
    CONSTRAINT pk_factory_warehouse_default PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_fwd_factory
    ON factory_warehouse_default (factory_id);

-- 一厂一 purpose 至多一行有效配置 (软删排除), 保证 upsert 幂等 + resolver lookup 单值。
CREATE UNIQUE INDEX IF NOT EXISTS uk_fwd_factory_purpose_active
    ON factory_warehouse_default (factory_id, purpose)
    WHERE deleted_at IS NULL;
