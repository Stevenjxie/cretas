-- 餐饮外部平台模拟器: connector 游标表 + 平台门店映射 + MOCK_REST 租户种子
-- spec: docs/superpowers/specs/2026-07-29-restaurant-mock-platform-api-design.md
-- plan: docs/superpowers/plans/2026-07-29-mock-platform-foundation-and-pos.md (Task 6)
--
-- ⚠️ 本文件的表结构假设全部经过 2026-07-29 生产实测校正, 不是凭记忆写的:
--   * dim_store **没有** store_code 列(实际: store_id/factory_id/name/brand/
--     city/province/region, 唯一约束 (factory_id, name)), 且被 23 处外键引用
--     → 门店映射另建 platform_store_map, 不动 dim_store 结构。
--   * fact_pos_transaction **没有** transaction_no 列, 是 source_type +
--     source_bill_no(均 NOT NULL), 且除主键外无任何唯一约束。
--   * fact_pos_payment **没有** method 文本列, 是 NOT NULL 的 channel_id 外键
--     → 必须先种 dim_payment_channel。
--
-- runner 以 `sudo -u postgres` 执行(超级用户绕过 RLS), 故本文件不需要
-- set_config('app.factory_id', ...)。

BEGIN;

-- ══════════════════════════════════════════════════════════════════════
-- 1. connector 增量拉取游标
-- ══════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS platform_sync_cursor (
    factory_id   TEXT        NOT NULL,
    platform     TEXT        NOT NULL,
    cursor_value TEXT        NOT NULL DEFAULT '0',
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, platform)
);

ALTER TABLE platform_sync_cursor ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_sync_cursor FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS platform_sync_cursor_select ON platform_sync_cursor;
CREATE POLICY platform_sync_cursor_select ON platform_sync_cursor
    FOR SELECT USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_sync_cursor_insert ON platform_sync_cursor;
CREATE POLICY platform_sync_cursor_insert ON platform_sync_cursor
    FOR INSERT WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_sync_cursor_update ON platform_sync_cursor;
CREATE POLICY platform_sync_cursor_update ON platform_sync_cursor
    FOR UPDATE USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    ) WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_sync_cursor_delete ON platform_sync_cursor;
CREATE POLICY platform_sync_cursor_delete ON platform_sync_cursor
    FOR DELETE USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON platform_sync_cursor TO smartbi_user;

COMMENT ON TABLE platform_sync_cursor IS
    '外部平台增量拉取游标. 每租户每平台一行, connector 拉完一页后推进.';

-- ══════════════════════════════════════════════════════════════════════
-- 2. 平台门店映射
--    不同平台对同一门店有不同 code(美团≠抖音≠POS), 按平台建映射本就更对;
--    且 dim_store 被 23 处外键引用, 不为这个功能给它加列。
-- ══════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS platform_store_map (
    factory_id          TEXT        NOT NULL,
    platform            TEXT        NOT NULL,
    platform_store_code TEXT        NOT NULL,
    store_id            BIGINT      NOT NULL REFERENCES dim_store(store_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, platform, platform_store_code)
);

ALTER TABLE platform_store_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_store_map FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS platform_store_map_select ON platform_store_map;
CREATE POLICY platform_store_map_select ON platform_store_map
    FOR SELECT USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_store_map_insert ON platform_store_map;
CREATE POLICY platform_store_map_insert ON platform_store_map
    FOR INSERT WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_store_map_update ON platform_store_map;
CREATE POLICY platform_store_map_update ON platform_store_map
    FOR UPDATE USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    ) WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_store_map_delete ON platform_store_map;
CREATE POLICY platform_store_map_delete ON platform_store_map
    FOR DELETE USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON platform_store_map TO smartbi_user;

COMMENT ON TABLE platform_store_map IS
    '外部平台门店 code → dim_store.store_id 映射. dim_store 无 store_code 列, '
    '且不同平台对同一门店 code 不同, 故按 (租户, 平台, 平台code) 建映射.';

-- ══════════════════════════════════════════════════════════════════════
-- 3. 幂等 —— 本 migration **不新建任何索引**, 这是刻意的
--
--    fact_pos_transaction 上已存在:
--        uq_fact_pos_txn UNIQUE CONSTRAINT
--            (factory_id, source_type, store_id, source_bill_no)
--    connector 的 writer 直接用它做 ON CONFLICT DO NOTHING 即可, 无需新索引。
--
--    踩坑留痕(2026-07-29): 起初以为该表除主键外无唯一约束, 打算加一个
--    部分唯一索引。依据是 (factory_id, source_type, source_bill_no) 上有
--    151,978 组"重复"(全表 1,382,267 行) —— 但那个统计漏了 store_id, 而
--    真实唯一键**含 store_id**, 同一单号跨门店本来就合法, 根本不是重复。
--    误判来源: 查 \d 时把输出 head 截断, 恰好切掉了 Indexes 段。
--    结论: 138 万行的表上少加一个索引, 用现成约束。
-- ══════════════════════════════════════════════════════════════════════

-- ══════════════════════════════════════════════════════════════════════
-- 4. MOCK_REST 租户种子
--    门店 name 必须与模拟端 mock_platform/world/seed.py 的 _STORES 逐字一致;
--    平台 code MK01..MK10 是 adapter 定位门店的唯一依据, 写错会让所有订单
--    静默落到错门店。
-- ══════════════════════════════════════════════════════════════════════
INSERT INTO dim_store (factory_id, name, brand, city, province)
VALUES
    ('MOCK_REST', '模拟·打浦桥日月光店',   '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·徐汇美罗城店',     '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·静安嘉里中心店',   '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·陆家嘴正大店',     '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·长宁龙之梦店',     '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·杨浦五角场店',     '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·普陀真如社区店',   '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·闵行莘庄社区店',   '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·宝山大场社区店',   '模拟餐饮', '上海', '上海'),
    ('MOCK_REST', '模拟·浦东金桥社区店',   '模拟餐饮', '上海', '上海')
ON CONFLICT (factory_id, name) DO NOTHING;

INSERT INTO platform_store_map (factory_id, platform, platform_store_code, store_id)
SELECT 'MOCK_REST', 'keruyun', v.code, s.store_id
FROM (VALUES
    ('MK01', '模拟·打浦桥日月光店'), ('MK02', '模拟·徐汇美罗城店'),
    ('MK03', '模拟·静安嘉里中心店'), ('MK04', '模拟·陆家嘴正大店'),
    ('MK05', '模拟·长宁龙之梦店'),   ('MK06', '模拟·杨浦五角场店'),
    ('MK07', '模拟·普陀真如社区店'), ('MK08', '模拟·闵行莘庄社区店'),
    ('MK09', '模拟·宝山大场社区店'), ('MK10', '模拟·浦东金桥社区店')
) AS v(code, name)
JOIN dim_store s ON s.factory_id = 'MOCK_REST' AND s.name = v.name
ON CONFLICT (factory_id, platform, platform_store_code) DO NOTHING;

-- fact_pos_payment.channel_id 是 NOT NULL 外键, 没有 method 文本列。
-- 名字与模拟端 generator.py 的 _PAY_BY_CHANNEL 值对应:
--   cash→现金  wechat→微信  alipay→支付宝  platform→平台代收
INSERT INTO dim_payment_channel (factory_id, name, category)
VALUES
    ('MOCK_REST', '现金',     'cash'),
    ('MOCK_REST', '微信',     'wallet'),
    ('MOCK_REST', '支付宝',   'wallet'),
    ('MOCK_REST', '平台代收', 'platform')
ON CONFLICT (factory_id, name) DO NOTHING;

-- ══════════════════════════════════════════════════════════════════════
-- 5. 落地自检 —— 种子必须真的进去了
--    ON CONFLICT DO NOTHING 会把"因 RLS 或约束被拒"伪装成"已存在",
--    静默产出一个空租户。这里显式断言, 不满足就整个事务回滚,
--    宁可 ABORT 部署也不要一个看起来成功的空租户。
-- ══════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_stores  INT;
    v_maps    INT;
    v_channels INT;
BEGIN
    SELECT count(*) INTO v_stores   FROM dim_store           WHERE factory_id = 'MOCK_REST';
    SELECT count(*) INTO v_maps     FROM platform_store_map  WHERE factory_id = 'MOCK_REST';
    SELECT count(*) INTO v_channels FROM dim_payment_channel WHERE factory_id = 'MOCK_REST';
    IF v_stores <> 10 THEN
        RAISE EXCEPTION 'MOCK_REST 门店种子落地失败: 期望 10 行, 实际 %', v_stores;
    END IF;
    IF v_maps <> 10 THEN
        RAISE EXCEPTION 'MOCK_REST 门店映射落地失败: 期望 10 行, 实际 %', v_maps;
    END IF;
    IF v_channels <> 4 THEN
        RAISE EXCEPTION 'MOCK_REST 支付渠道落地失败: 期望 4 行, 实际 %', v_channels;
    END IF;
END $$;

COMMIT;
