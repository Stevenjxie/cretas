-- #59 Phase 1 — 餐饮 CRM 散客表 (cretas_db)
-- 邓总模型: 散客首次登记不计业绩 → 营销员维护 → 第二次复购才计业绩 → 重点客户(来3次+)必须进包厢.
--
-- flyway 版本 20260921.01: origin/main 已应用 max 20260920.01 (out-of-order=false, 必须更大).
--   部署前已 collision-check (无 V20260921 占用). _01/_02/_04 是 Phase 1 预分配块,
--   _03/_05/_06 保留给 Phase 2 (阶梯提成 rep_summary/commissions).
-- 对应实体: com.cretas.aims.entity.restaurant.RestaurantGuest.

CREATE TABLE IF NOT EXISTS restaurant_guests (
    id                  VARCHAR(191)    NOT NULL PRIMARY KEY,
    factory_id          VARCHAR(100)    NOT NULL,
    name                VARCHAR(100),
    phone               VARCHAR(30),
    -- 营销员归属 (FK users.id, nullable — 散客可能尚无营销员维护)
    rep_id              BIGINT,
    rep_bound_at        TIMESTAMP,
    -- 到访统计 (首次到访不计业绩, 第2次起计)
    visit_count         INTEGER         NOT NULL DEFAULT 0,
    first_visit_at      TIMESTAMP,
    last_visit_at       TIMESTAMP,
    -- 营销员权限配置 JSONB: {"boxRoom":true,"discountPct":90,"fruitPlate":true,"beerBottles":2}
    perk_config         JSONB,
    -- 生命周期: NEW / ACTIVE / RECURRING / VIP / AT_RISK / CHURNED
    lifecycle_stage     VARCHAR(20)     NOT NULL DEFAULT 'NEW',
    notes               TEXT,
    created_by          BIGINT,
    -- 乐观锁
    version             BIGINT          NOT NULL DEFAULT 0,
    -- BaseEntity 审计字段
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rest_guest_factory      ON restaurant_guests (factory_id);
CREATE INDEX IF NOT EXISTS idx_rest_guest_rep          ON restaurant_guests (factory_id, rep_id);
CREATE INDEX IF NOT EXISTS idx_rest_guest_stage        ON restaurant_guests (factory_id, lifecycle_stage);
CREATE INDEX IF NOT EXISTS idx_rest_guest_last_visit   ON restaurant_guests (factory_id, last_visit_at);

-- 防呆 Rule 4 幂等: 同工厂同手机号唯一 (软删除记录不占用 → partial unique WHERE deleted_at IS NULL)
CREATE UNIQUE INDEX IF NOT EXISTS uq_rest_guest_factory_phone
    ON restaurant_guests (factory_id, phone)
    WHERE deleted_at IS NULL AND phone IS NOT NULL;
