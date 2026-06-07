-- 餐饮专属角色测试账号 (qhj factory RES_3101_009)
-- 密码: 123456 (hash 从现有 qhj_warehouse_mgr 复用, bcrypt rounds=10)
-- ⛔ 仅提供给 Opus organizer 审核后在 prod 执行 — 本脚本不得自动运行
-- 使用方式:
--   PGPASSWORD=cretas123 psql -U cretas_user -d cretas_prod_db -h 127.0.0.1 -f scripts/seed-restaurant-test-accounts.sql

BEGIN;

INSERT INTO users (
    factory_id,
    username,
    password_hash,
    full_name,
    role_code,
    is_active,
    level,
    platform_type,
    created_at,
    updated_at
)
VALUES
    -- 厨师长: 报货/领料/验收入库
    (
        'RES_3101_009',
        'qhj_chef',
        '$2a$10$.Bh9K7HfMGY48nTtq4icoOuoMEZsMY0k2tS13fcpnZAgJPrDdQUOy',  -- 123456
        '秦皇荷 厨师长 (测试)',
        'restaurant_chef',
        true,
        15,
        'web,mobile',
        NOW(),
        NOW()
    ),
    -- 餐饮采购: 请购/采购确认/采购审批
    (
        'RES_3101_009',
        'qhj_purchase_mgr',
        '$2a$10$.Bh9K7HfMGY48nTtq4icoOuoMEZsMY0k2tS13fcpnZAgJPrDdQUOy',  -- 123456
        '秦皇荷 采购 (测试)',
        'restaurant_purchaser',
        true,
        15,
        'web,mobile',
        NOW(),
        NOW()
    ),
    -- 餐饮老板: 全餐饮运营 + 价格异常审批 + 月对账
    (
        'RES_3101_009',
        'qhj_owner',
        '$2a$10$.Bh9K7HfMGY48nTtq4icoOuoMEZsMY0k2tS13fcpnZAgJPrDdQUOy',  -- 123456
        '秦皇荷 老板 (测试)',
        'restaurant_owner',
        true,
        5,
        'web,mobile',
        NOW(),
        NOW()
    )
ON CONFLICT (username) DO NOTHING;

COMMIT;
