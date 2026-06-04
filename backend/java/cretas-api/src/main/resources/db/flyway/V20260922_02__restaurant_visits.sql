-- #59 Phase 1 — 餐饮 CRM 到访记录表 (cretas_db)
-- 每次到访一条. rep_id 是到访时绑定营销员的快照 (换绑后不回写历史, 保证 Phase 2 业绩归属正确).
-- is_qualifying = (visit_number >= 2): 首次到访不计业绩, 第2次复购起计.
--
-- flyway 版本 20260922.02 (见 V20260922_01 头注释; 自 20260921 上移避 sister 撞车). 对应实体:
--   com.cretas.aims.entity.restaurant.RestaurantVisit.

CREATE TABLE IF NOT EXISTS restaurant_visits (
    id                  VARCHAR(191)    NOT NULL PRIMARY KEY,
    factory_id          VARCHAR(100)    NOT NULL,
    guest_id            VARCHAR(191)    NOT NULL REFERENCES restaurant_guests(id),
    -- 到访时绑定营销员快照 (FK users.id, nullable). NOT 当前 guest.rep_id.
    rep_id              BIGINT,
    visit_number        INTEGER         NOT NULL,
    visit_at            TIMESTAMP       NOT NULL,
    spend_amount        NUMERIC(15,2),
    -- 渠道: WALK_IN / RESERVATION / DELIVERY
    channel             VARCHAR(20),
    table_id            VARCHAR(50),
    -- 是否计业绩 (visit_number >= 2)
    is_qualifying       BOOLEAN         NOT NULL DEFAULT FALSE,
    notes               TEXT,
    created_by          BIGINT,
    -- BaseEntity 审计字段
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rest_visit_factory   ON restaurant_visits (factory_id);
CREATE INDEX IF NOT EXISTS idx_rest_visit_guest     ON restaurant_visits (guest_id);
CREATE INDEX IF NOT EXISTS idx_rest_visit_rep       ON restaurant_visits (factory_id, rep_id);
CREATE INDEX IF NOT EXISTS idx_rest_visit_at        ON restaurant_visits (factory_id, visit_at);

-- 防呆 Rule 4 去重: 同客户同到访时刻唯一 (软删除记录不占用)
CREATE UNIQUE INDEX IF NOT EXISTS uq_rest_visit_guest_at
    ON restaurant_visits (guest_id, visit_at)
    WHERE deleted_at IS NULL;
