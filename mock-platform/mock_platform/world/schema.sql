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

CREATE UNIQUE INDEX IF NOT EXISTS idx_order_seq ON "order"(seq);
CREATE INDEX IF NOT EXISTS idx_order_store_date ON "order"(store_id, biz_date);
