-- 餐饮 food_cost 诚实标注成本来源 (Fable: 别谎称真进价 = 伪天花板)。
--
-- 背景: fact_restaurant_recipe_line.ingredient_id 此前 100% NULL (root cause:
--       restaurant_ops_etl._get_ingredient_pk_map RLS transaction-local GUC bug,
--       已在 V20261003_01 同批代码修复) → line_cost UPDATE join 不上 → food_cost
--       从未用真实配料价。修复 ingredient_id 后 food_cost 用 dim_ingredient.unit_price。
--
-- dim_ingredient.unit_price 来源择优:
--   1. agg_supplier_price 最新真实进价 (delivery-note / material-batch ingest) — 'real_purchase'
--   2. raw_material_types.moving_avg_price (实际消耗移动均价) — 'moving_avg'
--   3. raw_material_types.unit_price (列表价/预设) — 'list_price'
--   4. 无任何价 — 'none'
--
-- cost_source 让前端/洞察诚实区分 "真进价成本" vs "估算成本", 不谎称真实。
-- 当前 prod 实测 RES_3101_009: 53 配料中仅 5 个有 agg_supplier_price 真进价覆盖,
-- 其余 48 用 moving_avg (真实消耗成本, 仍是 estimate 性质非 spot 进价) — 故诚实标注必要。
--
-- 版本: V20261003_01 之后, V20261003_02 严格 > frontier 安全。

ALTER TABLE dim_ingredient
    ADD COLUMN IF NOT EXISTS cost_source VARCHAR(20);

COMMENT ON COLUMN dim_ingredient.cost_source IS
  'unit_price 成本来源: real_purchase (agg_supplier_price 真进价) | moving_avg '
  '(实际消耗移动均价) | list_price (预设列表价) | none (无价). 防 food_cost 谎称真实成本. '
  'Added 2026-06-11 (food_cost honesty).';
