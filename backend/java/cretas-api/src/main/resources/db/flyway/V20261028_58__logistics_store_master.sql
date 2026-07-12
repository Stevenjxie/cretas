-- 一加物流 门店主数据 (store master) — 门店坐标"解析一次, 逐日复用" (客户第一诉求:
-- "录一次不用天天重复维护")。
--
-- 问题: 今天每次导入都对每一家门店重新按地址字符串 geocode (geocodeUnresolvedOrders,
-- 受 GEOCODE_ON_COMMIT_CAP 逐次限流) —— 同一批 ~200 家门店天天重新解析, 命中率被上限拖累,
-- 且调度员手工修正的坐标 (updateLocation) 只落在当次订单行, 第二天照样丢失重新变
-- UNRESOLVED。本表按 门店名称 (稳定跨天身份, 不是 store_code —— 手动录入时 store_code 是
-- 每天不同的 SM-{date}-{seq} 订单号, 不是门店身份) 落一条主数据行, 一次解析/一次人工修正
-- 之后所有后续导入直接复用, 不再消耗 geocode 预算。
--
-- 约定 (per task brief, 同 V20261028_57 精神): additive, nullable where sensible, 不加 CHECK
-- 约束 (PG CHECK runtime-only, 项目已有 2 次相关事故教训 — 见
-- .claude/rules/database-entity-sync.md)。updated_at 触发器函数 update_updated_at() 已由
-- V20261028_01 建好, 本迁移直接复用不重复定义。

CREATE TABLE IF NOT EXISTS logistics_store_master (
    id             VARCHAR(36)  PRIMARY KEY DEFAULT gen_random_uuid()::text,
    factory_id     VARCHAR(64)  NOT NULL,
    store_name     VARCHAR(256) NOT NULL,
    address        VARCHAR(512),
    area_code      VARCHAR(64),
    longitude      NUMERIC(11,7),
    latitude       NUMERIC(10,7),
    location_status VARCHAR(24) NOT NULL DEFAULT 'UNRESOLVED',
    source         VARCHAR(24)  NOT NULL DEFAULT 'GEOCODED',
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMP
);
-- 同厂+同门店名称 只允许一条主数据行 (resolve/upsert 查重键; normalize = trim + collapse
-- internal whitespace, 不 lowercase — 中文场景). 部分唯一索引跟 V20261028_57 一致的软删除语义。
CREATE UNIQUE INDEX IF NOT EXISTS uq_lsm_factory_name
    ON logistics_store_master (factory_id, store_name)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_lsm_factory_area ON logistics_store_master (factory_id, area_code);
CREATE TRIGGER trg_lsm_updated_at BEFORE UPDATE ON logistics_store_master
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
