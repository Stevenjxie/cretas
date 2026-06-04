-- #59 Phase 2 — 营销员月度累计提成汇总表 (cretas_db)
-- 月度累计: 营销员当月复购业绩累加 (cumulative_revenue), 当 cumulative_revenue 跨过
-- CommissionRule.tierConfig 的档位 (邓总 UI 配 15万/30万/50万 → 三档费率) 时, 当前档位 (current_tier) 上移.
-- 一个营销员 / 一个月 (period_key='YYYY-MM') 唯一一行. @Version 乐观锁防并发到访事件重复结算撞车.
--
-- flyway 版本 20260925.01 (frontier 20260922; 925 free, 见 PR 前 git ls-tree 复核). 对应实体:
--   com.cretas.aims.entity.restaurant.RestaurantRepCommissionSummary.

CREATE TABLE IF NOT EXISTS restaurant_rep_commission_summaries (
    id                       VARCHAR(191)    NOT NULL PRIMARY KEY,
    factory_id               VARCHAR(100)    NOT NULL,
    -- 营销员 (FK users.id)
    rep_id                   BIGINT          NOT NULL,
    -- 计提周期 'YYYY-MM' (月度累计)
    period_key               VARCHAR(7)      NOT NULL,
    -- 当月累计复购业绩 (跨档位依据)
    cumulative_revenue       NUMERIC(15,2)   NOT NULL DEFAULT 0,
    -- 当前所处档位 index (0-based; 从 tierConfig 重算). NULL = 无 tierConfig / 未匹配.
    current_tier             INTEGER,
    -- 当月计业绩到访次数 (展示用)
    attributed_visit_count   INTEGER         NOT NULL DEFAULT 0,
    -- 乐观锁
    version                  BIGINT          NOT NULL DEFAULT 0,
    -- BaseEntity 审计字段
    created_at               TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at               TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rest_rcs_factory  ON restaurant_rep_commission_summaries (factory_id);
CREATE INDEX IF NOT EXISTS idx_rest_rcs_rep      ON restaurant_rep_commission_summaries (factory_id, rep_id);
CREATE INDEX IF NOT EXISTS idx_rest_rcs_period   ON restaurant_rep_commission_summaries (factory_id, period_key);

-- 一个营销员 / 一个月 唯一 (软删除记录不占用)
CREATE UNIQUE INDEX IF NOT EXISTS uq_rest_rcs_rep_period
    ON restaurant_rep_commission_summaries (factory_id, rep_id, period_key)
    WHERE deleted_at IS NULL;
