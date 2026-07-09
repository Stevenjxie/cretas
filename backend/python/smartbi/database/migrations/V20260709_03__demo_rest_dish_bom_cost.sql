-- DEMO_REST dish-level BOM/cost seed for mobile AI synthesis.
--
-- Scope:
--   - demo tenant only (DEMO_REST)
--   - additive/idempotent
--   - no real-customer rows touched
--   - RLS session context set before writes

SELECT set_config('app.factory_id', 'DEMO_REST', false);

INSERT INTO restaurant_sku_forms (
    factory_id,
    store_id,
    sku_name,
    category,
    total_cogs_amount,
    selling_price,
    monthly_sales_quantity,
    ingredients,
    uploaded_by,
    notes
)
SELECT
    'DEMO_REST',
    NULL,
    v.sku_name,
    v.category,
    v.total_cogs_amount,
    v.selling_price,
    v.monthly_sales_quantity,
    v.ingredients::jsonb,
    'migration',
    'DEMO_REST mobile AI dish BOM seed; derived from demo recipe pricing assumptions'
FROM (
    VALUES
        (
            '招牌青花椒鱼',
            '招牌主菜',
            28.50::numeric,
            88.00::numeric,
            320::numeric,
            '[{"name":"黑鱼片","cost":18.00,"weight_g":320,"unit_price_per_kg":56.25},{"name":"青花椒","cost":4.50,"weight_g":30,"unit_price_per_kg":150.00},{"name":"酸菜","cost":3.20,"weight_g":120,"unit_price_per_kg":26.67},{"name":"汤底料","cost":2.80,"weight_g":80,"unit_price_per_kg":35.00}]'
        ),
        (
            '藤椒牛肉煲',
            '招牌主菜',
            34.80::numeric,
            98.00::numeric,
            210::numeric,
            '[{"name":"牛肉片","cost":24.00,"weight_g":260,"unit_price_per_kg":92.31},{"name":"藤椒油","cost":3.80,"weight_g":25,"unit_price_per_kg":152.00},{"name":"金针菇","cost":2.70,"weight_g":150,"unit_price_per_kg":18.00},{"name":"汤底料","cost":4.30,"weight_g":100,"unit_price_per_kg":43.00}]'
        ),
        (
            '脆皮乳鸽',
            '招牌主菜',
            42.00::numeric,
            128.00::numeric,
            96::numeric,
            '[{"name":"乳鸽","cost":34.00,"weight_g":420,"unit_price_per_kg":80.95},{"name":"脆皮水","cost":2.20,"weight_g":40,"unit_price_per_kg":55.00},{"name":"卤水香料","cost":3.60,"weight_g":30,"unit_price_per_kg":120.00},{"name":"配菜","cost":2.20,"weight_g":80,"unit_price_per_kg":27.50}]'
        ),
        (
            '手工虾滑',
            '小吃',
            16.00::numeric,
            38.00::numeric,
            500::numeric,
            '[{"name":"虾仁","cost":12.00,"weight_g":150,"unit_price_per_kg":80.00},{"name":"蛋清","cost":0.80,"weight_g":30,"unit_price_per_kg":26.67},{"name":"淀粉","cost":0.50,"weight_g":20,"unit_price_per_kg":25.00},{"name":"调味料","cost":2.70,"weight_g":20,"unit_price_per_kg":135.00}]'
        ),
        (
            '老坛酸菜鱼小份',
            '主菜',
            22.60::numeric,
            58.00::numeric,
            420::numeric,
            '[{"name":"黑鱼片","cost":14.50,"weight_g":260,"unit_price_per_kg":55.77},{"name":"老坛酸菜","cost":3.40,"weight_g":150,"unit_price_per_kg":22.67},{"name":"汤底料","cost":2.90,"weight_g":80,"unit_price_per_kg":36.25},{"name":"粉丝","cost":1.80,"weight_g":100,"unit_price_per_kg":18.00}]'
        ),
        (
            '酸辣土豆丝',
            '素菜',
            5.20::numeric,
            18.00::numeric,
            760::numeric,
            '[{"name":"土豆","cost":2.40,"weight_g":300,"unit_price_per_kg":8.00},{"name":"青红椒","cost":1.10,"weight_g":40,"unit_price_per_kg":27.50},{"name":"醋","cost":0.40,"weight_g":20,"unit_price_per_kg":20.00},{"name":"调味料","cost":1.30,"weight_g":20,"unit_price_per_kg":65.00}]'
        ),
        (
            '招牌凉拌牛肉',
            '凉菜',
            24.30::numeric,
            48.00::numeric,
            260::numeric,
            '[{"name":"卤牛腱","cost":19.50,"weight_g":160,"unit_price_per_kg":121.88},{"name":"香菜","cost":0.80,"weight_g":20,"unit_price_per_kg":40.00},{"name":"辣椒油","cost":1.60,"weight_g":25,"unit_price_per_kg":64.00},{"name":"调味汁","cost":2.40,"weight_g":50,"unit_price_per_kg":48.00}]'
        ),
        (
            '米饭',
            '主食',
            1.10::numeric,
            3.00::numeric,
            1800::numeric,
            '[{"name":"东北大米","cost":0.85,"weight_g":120,"unit_price_per_kg":7.08},{"name":"蒸煮耗材","cost":0.25,"weight_g":1,"unit_price_per_kg":250.00}]'
        )
) AS v(sku_name, category, total_cogs_amount, selling_price, monthly_sales_quantity, ingredients)
WHERE NOT EXISTS (
    SELECT 1
      FROM restaurant_sku_forms r
     WHERE r.factory_id = 'DEMO_REST'
       AND r.store_id IS NULL
       AND r.sku_name = v.sku_name
);
