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
    price_cents      INTEGER NOT NULL,
    cost_cents       INTEGER NOT NULL,
    groupon_eligible INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS "order" (
    id             INTEGER PRIMARY KEY,
    order_no       TEXT NOT NULL UNIQUE,
    store_id       INTEGER NOT NULL REFERENCES store(id),
    channel        TEXT NOT NULL,          -- dine_in / takeaway / groupon
    placed_at      TEXT NOT NULL,          -- ISO8601 UTC
    biz_date       TEXT NOT NULL,          -- YYYY-MM-DD 营业日
    gross_cents    INTEGER NOT NULL,
    discount_cents INTEGER NOT NULL DEFAULT 0,
    net_cents      INTEGER NOT NULL,
    guest_count    INTEGER NOT NULL DEFAULT 1,
    seq            INTEGER NOT NULL        -- 全局单调游标
);

CREATE TABLE IF NOT EXISTS order_item (
    id          INTEGER PRIMARY KEY,
    order_id    INTEGER NOT NULL REFERENCES "order"(id),
    dish_id     INTEGER NOT NULL REFERENCES dish(id),
    qty         INTEGER NOT NULL,
    price_cents INTEGER NOT NULL,
    amount_cents INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS payment (
    id           INTEGER PRIMARY KEY,
    order_id     INTEGER NOT NULL REFERENCES "order"(id),
    method       TEXT NOT NULL,           -- cash / wechat / alipay / platform
    amount_cents INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_order_seq ON "order"(seq);
CREATE INDEX IF NOT EXISTS idx_order_store_date ON "order"(store_id, biz_date);
