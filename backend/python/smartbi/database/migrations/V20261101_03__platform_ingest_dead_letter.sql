-- connector 死信表: 隔离永久性坏单据, 让游标能继续推进
--
-- 背景 —— 现在的失败语义是**宁可卡住也不漏**:
--   writer 里任一条记录写失败 → 整页事务回滚 → framework 抛 PlatformSyncError
--   → write_cursor 被跳过 → 游标不动 → 下轮重拉同一页。
--   一条**永久性**坏记录(门店映射查不到 / 菜名归一化后为空 / 支付方式未知)
--   会让那一类数据**永远停在那一页**, 后续数据全部再也进不来。
--
-- 本表把「永久坏」的那部分单独隔离出来, 使游标得以推进; 「瞬时故障」仍然
-- 照旧卡住重试 —— 两者的区别由 writer 的**写前校验**判定, 不靠猜。
--
-- ⚠️ 安全底线(见 writer 注释与测试): 游标**只在坏记录已成功落本表之后**才推进。
--   隔离写失败就必须抛错让游标停住 —— 否则就是静默丢数据, 那比现在卡住严重。
--
-- ⚠️ RLS 带 `__internal__` 逃生门 —— 与同批的 platform_sync_cursor 一致。
--   本表是**运维要读的表**: 没有逃生门的话, 内部工具用 __internal__ 上下文
--   查询会拿到假 0 行, 看起来"没有坏记录"而实际有(本仓 fact_pos_* 老表就
--   没有逃生门, 不能照抄那批)。
--
-- runner 以 `sudo -u postgres` 执行(超级用户绕过 RLS), 故本文件不需要
-- 额外的 GRANT。

BEGIN;

CREATE TABLE IF NOT EXISTS platform_ingest_dead_letter (
    id           BIGSERIAL PRIMARY KEY,
    factory_id   VARCHAR(64) NOT NULL,
    platform     VARCHAR(32) NOT NULL,
    -- order | requisition | wastage | stocktaking —— 与 connector 的游标键同源
    kind         VARCHAR(32) NOT NULL,
    -- 平台侧单据号。同一条坏单据重复拉到时靠它幂等, 不会刷出多行。
    source_ref   VARCHAR(128) NOT NULL,
    -- 原始报文, 供人工核对与修好后重放
    payload      JSONB       NOT NULL,
    -- 人能看懂的原因(writer 校验产出), 不是异常字符串堆
    reason       TEXT        NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- 又被拉到并再次判坏的次数 —— 用来看是"一次性脏数据"还是"上游持续在发"
    seen_count   INTEGER     NOT NULL DEFAULT 1,
    -- 人工处理后置位; 不删行, 保留审计痕迹
    resolved_at  TIMESTAMPTZ,
    UNIQUE (factory_id, platform, kind, source_ref)
);

-- 运维最常问的两个问题: 「现在有哪些没处理的」「某类近期新增了多少」
CREATE INDEX IF NOT EXISTS idx_pidl_unresolved
    ON platform_ingest_dead_letter (factory_id, kind, last_seen_at DESC)
    WHERE resolved_at IS NULL;

ALTER TABLE platform_ingest_dead_letter ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_ingest_dead_letter FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS platform_ingest_dead_letter_select ON platform_ingest_dead_letter;
CREATE POLICY platform_ingest_dead_letter_select ON platform_ingest_dead_letter
    FOR SELECT USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_ingest_dead_letter_insert ON platform_ingest_dead_letter;
CREATE POLICY platform_ingest_dead_letter_insert ON platform_ingest_dead_letter
    FOR INSERT WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_ingest_dead_letter_update ON platform_ingest_dead_letter;
CREATE POLICY platform_ingest_dead_letter_update ON platform_ingest_dead_letter
    FOR UPDATE USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    ) WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

DROP POLICY IF EXISTS platform_ingest_dead_letter_delete ON platform_ingest_dead_letter;
CREATE POLICY platform_ingest_dead_letter_delete ON platform_ingest_dead_letter
    FOR DELETE USING (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
    );

COMMIT;
