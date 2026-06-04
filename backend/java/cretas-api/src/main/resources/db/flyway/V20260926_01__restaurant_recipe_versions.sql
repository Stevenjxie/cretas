-- #60 Phase 2 — 餐饮配方版本化 recipe_versions (cretas_db)
--
-- 一道菜 (product_type_id) 的完整配方 = 多条 recipes 行 (扁平, 无版本概念).
-- recipe_versions 把这组行在审批时刻冻结为一份 snapshot_json, 独立 row-per-approval +
-- 状态机 (DRAFT → PENDING_APPROVAL → APPROVED → OBSOLETE; REJECTED 终态).
-- 对应实体: com.cretas.aims.entity.restaurant.RecipeVersion.
--
-- 借 bom.bom_versions 模式 (独立 row + snapshot + 状态机 + 显式 supersede), 但 restaurant 包
-- 本地化 — enum 本地复制避免 bom↔restaurant 包耦合, 表/索引/约束独立.
--
-- Flyway 版本 V20260926_01: origin/main db/flyway max = V20260922_04 (frontier 922),
--   923-925 free. 取 926 留 buffer 并已 collision-check (V20260926 free in db/flyway).
--
-- 幂等: CREATE TABLE / INDEX IF NOT EXISTS — 重跑安全.

CREATE TABLE IF NOT EXISTS recipe_versions (
    id                  VARCHAR(191)    NOT NULL PRIMARY KEY,
    factory_id          VARCHAR(100)    NOT NULL,
    -- 逻辑 FK → product_types.id (该菜品). 无硬 DB FK (跨表/并发迁移协调一致性).
    product_type_id     VARCHAR(191)    NOT NULL,
    -- 1,2,3... 每 (factory_id, product_type_id) 顺序递增 (createDraft = max+1)
    version_number      INTEGER         NOT NULL,
    -- 审批时刻冻结的配方快照 (含食材用量 + 成本字段 → @PriceSensitive, 响应层 RBAC strip)
    snapshot_json       JSONB           NOT NULL,
    -- 状态机
    status              VARCHAR(32)     NOT NULL DEFAULT 'DRAFT'
                                        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED',
                                                          'OBSOLETE', 'REJECTED')),
    effective_from      DATE,
    effective_to        DATE,
    created_by          BIGINT,
    approved_by         BIGINT,
    approved_at         TIMESTAMP,
    rejection_reason    TEXT,
    -- BaseEntity 审计字段
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- 同 (factory_id, product_type_id, version_number) 唯一 (软删不占用)
CREATE UNIQUE INDEX IF NOT EXISTS idx_rv_dish_version
    ON recipe_versions (factory_id, product_type_id, version_number)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_rv_dish_status
    ON recipe_versions (product_type_id, status)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_rv_factory_status
    ON recipe_versions (factory_id, status)
    WHERE deleted_at IS NULL;

-- Partial unique: 同一道菜任一时刻只能有 1 条 APPROVED + effective_to IS NULL 的版本.
-- DB 层保证 "single active recipe version" 不变量 (service approve() 显式 supersede 是
-- defense-in-depth, 与 bom.uq_bv_one_current_per_recipe 同理).
CREATE UNIQUE INDEX IF NOT EXISTS uq_rv_one_approved_per_dish
    ON recipe_versions (factory_id, product_type_id)
    WHERE status = 'APPROVED' AND effective_to IS NULL AND deleted_at IS NULL;

-- BaseEntity updated_at 触发器 (per database-entity-sync.md PG pattern).
-- JPA @PreUpdate 已维护 updated_at, 触发器是 direct-SQL / batch 路径的兜底.
CREATE OR REPLACE FUNCTION trg_rv_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_rv_updated_at ON recipe_versions;
CREATE TRIGGER trigger_rv_updated_at
    BEFORE UPDATE ON recipe_versions
    FOR EACH ROW EXECUTE FUNCTION trg_rv_set_updated_at();

COMMENT ON TABLE recipe_versions IS
    '#60 Phase 2 餐饮配方版本化. 独立 row-per-approval + 完整 snapshot + 状态机. 一道菜 = 多 recipes 行的冻结快照.';
COMMENT ON COLUMN recipe_versions.snapshot_json IS
    '审批时刻该菜全部 recipes 行快照. @PriceSensitive — 含食材单价/成本字段.';
COMMENT ON COLUMN recipe_versions.effective_to IS
    'NULL = 当前生效. Partial unique uq_rv_one_approved_per_dish 保证同菜单一生效版本.';

-- Rollback:
--   DROP TRIGGER IF EXISTS trigger_rv_updated_at ON recipe_versions;
--   DROP FUNCTION IF EXISTS trg_rv_set_updated_at();
--   DROP TABLE IF EXISTS recipe_versions;
