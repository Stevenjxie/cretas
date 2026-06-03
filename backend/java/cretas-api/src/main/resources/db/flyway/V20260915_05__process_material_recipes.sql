-- V20260915_05: 工序材料配方表 (调料 BOM + 包材)
-- 两表:
--   process_material_recipes  — 汇总行, 存每道工序的 unit_cost (供成本计算)
--   process_material_recipe_items — 明细行, 供审计 (自由文本 material_name, 无 FK 到原材料主档)
-- NOTE: work_process_id 是 VARCHAR(50) 字符串, 故意不加 FK 到 work_processes (entity-only 表),
--       避免 Flyway 对 entity-only 表做 FK 约束校验失败。

CREATE TABLE process_material_recipes (
    id                  BIGSERIAL PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    work_process_id     VARCHAR(50)  NOT NULL,
    recipe_type         VARCHAR(20)  NOT NULL CHECK (recipe_type IN ('SEASONING', 'PACKAGING')),
    -- unit_cost:
    --   SEASONING  → 元/kg 投入量 (按本道 inputQuantity 摊)
    --   PACKAGING  → 元/盒 产出量 (按本道 outputQuantity 摊)
    unit_cost           NUMERIC(14,4) NOT NULL,
    cost_unit           VARCHAR(20)  NOT NULL,   -- e.g. '元/kg投入', '元/盒产出'
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    remark              TEXT,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

COMMENT ON TABLE  process_material_recipes                IS '工序材料配方 (调料BOM / 包材) — unit_cost 供逐道成本计算';
COMMENT ON COLUMN process_material_recipes.recipe_type    IS 'SEASONING=调料(元/kg投入), PACKAGING=包材(元/盒产出)';
COMMENT ON COLUMN process_material_recipes.unit_cost      IS '已摊摊分单位成本; SEASONING=元/kg投入; PACKAGING=元/盒产出';
COMMENT ON COLUMN process_material_recipes.cost_unit      IS '人读单位说明, 如 元/kg投入 / 元/盒产出';

-- 明细行: 每一条配方原材料 (自由文本, 审计用)
CREATE TABLE process_material_recipe_items (
    id              BIGSERIAL PRIMARY KEY,
    recipe_id       BIGINT       NOT NULL REFERENCES process_material_recipes(id) ON DELETE CASCADE,
    material_name   VARCHAR(100) NOT NULL,   -- 自由文本, 不 FK 到原材料主档
    unit_price      NUMERIC(14,4),           -- 元/kg (采购价)
    quantity        NUMERIC(14,4),           -- 用量 g (或其他单位)
    quantity_unit   VARCHAR(20),             -- g / kg / 个 / 张
    line_cost       NUMERIC(14,4),           -- unit_price × quantity (折算 kg 后) 元
    remark          VARCHAR(200),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  process_material_recipe_items              IS '工序配方明细行 (供审计, 不参与成本计算)';
COMMENT ON COLUMN process_material_recipe_items.unit_price   IS '采购单价 元/kg (价格敏感字段, 服务层 @PriceSensitive 管控)';
COMMENT ON COLUMN process_material_recipe_items.line_cost    IS '行成本 元 (价格敏感字段)';

-- 自动更新 updated_at
CREATE OR REPLACE FUNCTION update_process_material_recipes_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_process_material_recipes_updated_at
BEFORE UPDATE ON process_material_recipes
FOR EACH ROW EXECUTE FUNCTION update_process_material_recipes_updated_at();
