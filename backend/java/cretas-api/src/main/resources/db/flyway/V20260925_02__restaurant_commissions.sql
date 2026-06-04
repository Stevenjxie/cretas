-- #59 Phase 2 — 餐饮到访提成记录表 (cretas_db)
-- 每次计业绩到访 (RestaurantVisit visit_number>=2) 经 RestaurantVisitAttributedEvent 触发结算一条.
-- 不复用 commissions 表 (其 sales_opportunity_id NOT NULL, 餐饮无商机维度).
-- tier_snapshot / rate_snapshot / cumulative_revenue_at_calc 是结算时刻的快照 (规则日后改不影响历史).
-- commission_amount = visit_revenue × tier-rate / 100 (ROUND_HALF_UP scale 2).
-- visit_id 唯一 (幂等: 同一次到访事件重复 fire 不重复建).
--
-- flyway 版本 20260925.02. 对应实体: com.cretas.aims.entity.restaurant.RestaurantCommission.

CREATE TABLE IF NOT EXISTS restaurant_commissions (
    id                          VARCHAR(191)    NOT NULL PRIMARY KEY,
    factory_id                  VARCHAR(100)    NOT NULL,
    -- 来源到访 (FK restaurant_visits.id) — 幂等键
    visit_id                    VARCHAR(191)    NOT NULL REFERENCES restaurant_visits(id),
    -- 营销员 (FK users.id, 冗余存储)
    rep_id                      BIGINT          NOT NULL,
    -- 使用的提成规则 (FK commission_rules.id). NULL = flat 兜底无规则但仍建? 不: 无规则跳过, 故 NOT NULL.
    rule_id                     VARCHAR(36)     NOT NULL,
    -- 结算时所处档位 index (0-based). NULL = flat percentage (无 tierConfig).
    tier_snapshot               INTEGER,
    -- 结算时套用的费率 (%) — 阶梯档费率 or flat percentage
    rate_snapshot               NUMERIC(5,2)    NOT NULL,
    -- 本次到访营收
    visit_revenue               NUMERIC(15,2)   NOT NULL,
    -- 本次提成金额 = visit_revenue × rate_snapshot / 100
    commission_amount           NUMERIC(14,2)   NOT NULL,
    -- 结算时营销员当月累计业绩 (审计/复核)
    cumulative_revenue_at_calc  NUMERIC(15,2)   NOT NULL,
    -- 状态 PENDING/PAID/CANCELLED
    status                      VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    paid_at                     TIMESTAMP,
    -- BaseEntity 审计字段
    created_at                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at                  TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rest_comm_factory  ON restaurant_commissions (factory_id);
CREATE INDEX IF NOT EXISTS idx_rest_comm_rep      ON restaurant_commissions (factory_id, rep_id);
CREATE INDEX IF NOT EXISTS idx_rest_comm_status   ON restaurant_commissions (factory_id, status);

-- 幂等: 一次到访仅一条提成 (软删除记录不占用)
CREATE UNIQUE INDEX IF NOT EXISTS uq_rest_comm_visit
    ON restaurant_commissions (visit_id)
    WHERE deleted_at IS NULL;
