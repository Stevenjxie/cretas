-- 注意: 这里全是 `CREATE TABLE IF NOT EXISTS`。改这个文件对已存在的 data.db
-- 不会自动生效 —— 已建表会被跳过，不会重新建/加约束。开发期问题不大（测试都用
-- tmp_path 新建库），但如果之后动这个 schema，记得已有库需要手动迁移或删库重建。
--
-- 金额列（*_cents）与计数列（qty/guest_count/seq）都加了
-- CHECK(typeof(x) = 'integer')：SQLite 是弱类型，INTEGER 只是"类型亲和性"，
-- 不加 CHECK 的话 58.5 这种浮点值能直接插进 INTEGER 列。金额一律以"分"为
-- 单位的整数，浮点累加会让跨平台对账出现假性不平。没用 STRICT 表是因为
-- STRICT 需要 SQLite 3.37+，139 服务器的版本未核实，CHECK 没有版本风险。

CREATE TABLE IF NOT EXISTS store (
    id             INTEGER PRIMARY KEY,
    code           TEXT NOT NULL UNIQUE,
    name           TEXT NOT NULL,
    format         TEXT NOT NULL,          -- flagship / community / mall
    traffic_factor REAL NOT NULL           -- 客流基准系数，1.0 = 平均
);

CREATE TABLE IF NOT EXISTS dish (
    id               INTEGER PRIMARY KEY,
    name             TEXT NOT NULL UNIQUE,
    category         TEXT NOT NULL,
    price_cents      INTEGER NOT NULL CHECK(typeof(price_cents) = 'integer'),
    cost_cents       INTEGER NOT NULL CHECK(typeof(cost_cents) = 'integer'),
    groupon_eligible INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS "order" (
    id             INTEGER PRIMARY KEY,
    order_no       TEXT NOT NULL UNIQUE,
    store_id       INTEGER NOT NULL REFERENCES store(id),
    channel        TEXT NOT NULL,          -- dine_in / takeaway / groupon
    placed_at      TEXT NOT NULL,          -- ISO8601 UTC
    biz_date       TEXT NOT NULL,          -- YYYY-MM-DD 营业日
    gross_cents    INTEGER NOT NULL CHECK(typeof(gross_cents) = 'integer'),
    discount_cents INTEGER NOT NULL DEFAULT 0 CHECK(typeof(discount_cents) = 'integer'),
    net_cents      INTEGER NOT NULL CHECK(typeof(net_cents) = 'integer'),
    guest_count    INTEGER NOT NULL DEFAULT 1 CHECK(typeof(guest_count) = 'integer'),
    seq            INTEGER NOT NULL CHECK(typeof(seq) = 'integer')        -- 全局单调游标
);

CREATE TABLE IF NOT EXISTS order_item (
    id          INTEGER PRIMARY KEY,
    order_id    INTEGER NOT NULL REFERENCES "order"(id),
    dish_id     INTEGER NOT NULL REFERENCES dish(id),
    qty         INTEGER NOT NULL CHECK(typeof(qty) = 'integer'),
    price_cents INTEGER NOT NULL CHECK(typeof(price_cents) = 'integer'),
    amount_cents INTEGER NOT NULL CHECK(typeof(amount_cents) = 'integer')
);

CREATE TABLE IF NOT EXISTS payment (
    id           INTEGER PRIMARY KEY,
    order_id     INTEGER NOT NULL REFERENCES "order"(id),
    method       TEXT NOT NULL,           -- cash / wechat / alipay / platform
    amount_cents INTEGER NOT NULL CHECK(typeof(amount_cents) = 'integer')
);

-- ── 后厨供应链 (2026-07-29) ─────────────────────────────────────────
-- 一家真实门店一天不只有前厅卖东西: 还要领食材、扔掉坏的、隔几天盘一次库。
-- 这几张表让模拟器把后厨那摊也演出来, 领料量从"当天真卖了哪些菜"× 配方推出来,
-- 不是凭空造数。
--
-- 用量单位: qty_milli = 实际用量 × 1000 的整数(千分之一单位)。与金额用"分"
-- 同一个理由 —— SQLite 弱类型下浮点会静默混进整数列, 且浮点累加会让对账假性不平。
-- 下游 Silver 的 numeric(14,4) 能无损接住毫单位。

CREATE TABLE IF NOT EXISTS ingredient (
    id               INTEGER PRIMARY KEY,
    name             TEXT NOT NULL UNIQUE,
    category         TEXT NOT NULL,          -- 肉类 / 水产 / 蔬菜 / 米面 / 调料 / 干货
    unit             TEXT NOT NULL,          -- kg / L / 个
    unit_price_cents INTEGER NOT NULL CHECK(typeof(unit_price_cents) = 'integer'),
    shelf_life_days  INTEGER NOT NULL CHECK(typeof(shelf_life_days) = 'integer'),
    storage_type     TEXT NOT NULL           -- 冷藏 / 冷冻 / 常温
);

CREATE TABLE IF NOT EXISTS recipe (
    dish_id       INTEGER NOT NULL REFERENCES dish(id),
    ingredient_id INTEGER NOT NULL REFERENCES ingredient(id),
    qty_milli     INTEGER NOT NULL CHECK(typeof(qty_milli) = 'integer'),  -- 每份用量 ×1000
    PRIMARY KEY (dish_id, ingredient_id)
);

-- 领料/损耗/盘点三类事件。seq 与订单一样是全局单调游标, 供拉取端分页。
-- 一天一店一食材一条领料, 所以 (biz_date, store_id, ingredient_id) 唯一。
CREATE TABLE IF NOT EXISTS requisition (
    id            INTEGER PRIMARY KEY,
    doc_no        TEXT NOT NULL UNIQUE,
    store_id      INTEGER NOT NULL REFERENCES store(id),
    ingredient_id INTEGER NOT NULL REFERENCES ingredient(id),
    biz_date      TEXT NOT NULL,
    qty_milli     INTEGER NOT NULL CHECK(typeof(qty_milli) = 'integer'),
    cost_cents    INTEGER NOT NULL CHECK(typeof(cost_cents) = 'integer'),
    seq           INTEGER NOT NULL CHECK(typeof(seq) = 'integer'),
    UNIQUE (biz_date, store_id, ingredient_id)
);

CREATE TABLE IF NOT EXISTS wastage (
    id            INTEGER PRIMARY KEY,
    doc_no        TEXT NOT NULL UNIQUE,
    store_id      INTEGER NOT NULL REFERENCES store(id),
    ingredient_id INTEGER NOT NULL REFERENCES ingredient(id),
    biz_date      TEXT NOT NULL,
    wastage_type  TEXT NOT NULL,          -- 变质 / 加工损耗 / 客诉退菜
    qty_milli     INTEGER NOT NULL CHECK(typeof(qty_milli) = 'integer'),
    cost_cents    INTEGER NOT NULL CHECK(typeof(cost_cents) = 'integer'),
    seq           INTEGER NOT NULL CHECK(typeof(seq) = 'integer'),
    UNIQUE (biz_date, store_id, ingredient_id, wastage_type)
);

CREATE TABLE IF NOT EXISTS stocktaking (
    id              INTEGER PRIMARY KEY,
    doc_no          TEXT NOT NULL UNIQUE,
    store_id        INTEGER NOT NULL REFERENCES store(id),
    ingredient_id   INTEGER NOT NULL REFERENCES ingredient(id),
    biz_date        TEXT NOT NULL,
    system_qty_milli INTEGER NOT NULL CHECK(typeof(system_qty_milli) = 'integer'),
    actual_qty_milli INTEGER NOT NULL CHECK(typeof(actual_qty_milli) = 'integer'),
    diff_cost_cents  INTEGER NOT NULL CHECK(typeof(diff_cost_cents) = 'integer'),
    seq             INTEGER NOT NULL CHECK(typeof(seq) = 'integer'),
    UNIQUE (biz_date, store_id, ingredient_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_order_seq ON "order"(seq);
CREATE INDEX IF NOT EXISTS idx_order_store_date ON "order"(store_id, biz_date);
CREATE UNIQUE INDEX IF NOT EXISTS idx_requisition_seq ON requisition(seq);
CREATE UNIQUE INDEX IF NOT EXISTS idx_wastage_seq ON wastage(seq);
CREATE UNIQUE INDEX IF NOT EXISTS idx_stocktaking_seq ON stocktaking(seq);
