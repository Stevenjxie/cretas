-- Demo restaurant sales gold seed.
-- Purpose: make /demo restaurant AI report prompts answer with usable sales/order
-- context instead of "review-only data". Scope is limited to DEMO_REST.

BEGIN;

WITH store_seed AS (
    INSERT INTO dim_store (factory_id, name, brand, city, province, region, created_at, updated_at)
    VALUES
        ('DEMO_REST', '青花椒上海示范店', '青花椒', '上海', '上海', '静安商圈', NOW(), NOW()),
        ('DEMO_REST', '青花椒大融城店', '青花椒', '上海', '上海', '大融城商圈', NOW(), NOW()),
        ('DEMO_REST', '青花椒陆家嘴店', '青花椒', '上海', '上海', '陆家嘴商圈', NOW(), NOW())
    ON CONFLICT (factory_id, name) DO UPDATE SET
        brand = EXCLUDED.brand,
        city = EXCLUDED.city,
        province = EXCLUDED.province,
        region = EXCLUDED.region,
        updated_at = NOW()
    RETURNING store_id, name
),
all_stores AS (
    SELECT store_id, name
      FROM store_seed
    UNION
    SELECT store_id, name
      FROM dim_store
     WHERE factory_id = 'DEMO_REST'
       AND name IN ('青花椒上海示范店', '青花椒大融城店', '青花椒陆家嘴店')
),
day_seed AS (
    SELECT d::int AS day_offset
      FROM generate_series(0, 13) AS d
),
daily_values AS (
    SELECT
        'DEMO_REST'::varchar AS factory_id,
        (CURRENT_DATE - ds.day_offset)::date AS date,
        s.store_id,
        CASE s.name
            WHEN '青花椒上海示范店' THEN 28500 - ds.day_offset * 520 + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 7800 ELSE 0 END
            WHEN '青花椒大融城店' THEN 22600 - ds.day_offset * 390 + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 5400 ELSE 0 END
            ELSE 19800 - ds.day_offset * 260 + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 4200 ELSE 0 END
        END::numeric(18,2) AS gross_amount,
        CASE s.name
            WHEN '青花椒上海示范店' THEN 1120 + ds.day_offset * 18
            WHEN '青花椒大融城店' THEN 1480 + ds.day_offset * 22
            ELSE 980 + ds.day_offset * 15
        END::numeric(18,2) AS discount_amount,
        CASE s.name
            WHEN '青花椒上海示范店' THEN 27380 - ds.day_offset * 538 + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 7800 ELSE 0 END
            WHEN '青花椒大融城店' THEN 21120 - ds.day_offset * 412 + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 5400 ELSE 0 END
            ELSE 18820 - ds.day_offset * 275 + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 4200 ELSE 0 END
        END::numeric(18,2) AS net_amount,
        CASE s.name
            WHEN '青花椒上海示范店' THEN 96 - ds.day_offset + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 24 ELSE 0 END
            WHEN '青花椒大融城店' THEN 82 - ds.day_offset + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 18 ELSE 0 END
            ELSE 76 - ds.day_offset + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 15 ELSE 0 END
        END::int AS bill_count,
        CASE s.name
            WHEN '青花椒上海示范店' THEN 218 - ds.day_offset * 2 + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 52 ELSE 0 END
            WHEN '青花椒大融城店' THEN 185 - ds.day_offset * 2 + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 40 ELSE 0 END
            ELSE 164 - ds.day_offset * 2 + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE - ds.day_offset) IN (6, 7) THEN 34 ELSE 0 END
        END::int AS customer_count
    FROM all_stores s
    CROSS JOIN day_seed ds
)
INSERT INTO agg_daily (
    factory_id, date, store_id, gross_amount, discount_amount, net_amount,
    actual_receive, bill_count, customer_count, item_count, version, computed_at
)
SELECT
    factory_id,
    date,
    store_id,
    gross_amount,
    discount_amount,
    net_amount,
    net_amount AS actual_receive,
    GREATEST(bill_count, 1),
    GREATEST(customer_count, 1),
    GREATEST(bill_count * 3, 1) AS item_count,
    1,
    NOW()
FROM daily_values
ON CONFLICT (factory_id, date, store_id) DO UPDATE SET
    gross_amount = EXCLUDED.gross_amount,
    discount_amount = EXCLUDED.discount_amount,
    net_amount = EXCLUDED.net_amount,
    actual_receive = EXCLUDED.actual_receive,
    bill_count = EXCLUDED.bill_count,
    customer_count = EXCLUDED.customer_count,
    item_count = EXCLUDED.item_count,
    computed_at = NOW();

COMMIT;
