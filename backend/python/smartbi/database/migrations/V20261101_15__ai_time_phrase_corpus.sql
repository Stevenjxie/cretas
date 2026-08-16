-- 2026-08-16 时间词语料与晋升 (card 1): 确定性层 `_resolve_sales_date_range`
-- 认不出「最近」这类时间说法时, LLM double-check 会认出来并给出规范短语 ——
-- 但今天这个知识用完就扔, 同一个词每次都要重新花一次 LLM 调用。
--
-- 本表把 LLM 认出的 (原句 -> 规范短语 -> 结构化窗口) 记下来, 攒够了由**人工**
-- 晋升进 `_resolve_sales_date_range` 的确定性分支 (与 ai_promoted_routes 的
-- 「人审后落地, 不自动生效」是同一条纪律)。写这张表是 Task 1 (本文件 +
-- smartbi/gold/restaurant/time_phrase_corpus.py); 接到 parse_restaurant_query
-- 是 Task 2; CLI/跑批是 Task 3。
--
-- 表形状照抄 ai_promoted_routes 的骨架 (domain/normalized_phrase 复合 PK,
-- hit_count, reviewed_by) —— ⛔ 不另发明一套。
--
-- 为什么本表**没有** RLS (与 ai_promoted_routes 的关键差异):
--   ai_promoted_routes 的注释白纸黑字要求任何读写它的新代码显式
--   `set_config('app.factory_id', …)`。但本表的写入方在
--   `parse_restaurant_query` 的**聊天热路径**上, 实测那条路从不
--   set_config (同路径的 `_pending_put` / `_pending_pop` 也不设) ——
--   给它挂 RLS 会让每次写入在 RLS 默认拒绝下静默失败, 而「表永远 0 行」
--   正是这张表存在的意义所要消灭的那个歧义 (「语料一直是空的」 vs
--   「根本没有这类问句」)。最接近的同类先例是
--   restaurant_pending_clarifications (同样运行时写、同样带 factory_id 列、
--   同样在聊天热路径上): V20260708_01 + V20260708_02, 没有 RLS, 只有 GRANT。
--   本表跟随这个先例, 不跟 ai_promoted_routes 的 RLS。
--
-- Version collision check against origin/main frontier (V20261101_14):
--   git ls-tree origin/main backend/python/smartbi/database/migrations/ \
--     | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | tail -1
--   -> V20261101_14, so V20261101_15 is above the frontier.
--
-- Idempotent: CREATE ... IF NOT EXISTS / ON CONFLICT.

CREATE TABLE IF NOT EXISTS ai_time_phrase_corpus (
    domain            VARCHAR(32)  NOT NULL,
    normalized_phrase TEXT         NOT NULL,
    factory_id        VARCHAR(64)  NOT NULL,
    raw_query         TEXT         NOT NULL,
    llm_phrase        VARCHAR(64)  NOT NULL,
    llm_time_range    JSONB,
    hit_count         BIGINT       NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_by       VARCHAR(64),
    promoted_at       TIMESTAMPTZ,
    promoted_note     TEXT,
    CONSTRAINT ai_time_phrase_corpus_pkey
        PRIMARY KEY (domain, normalized_phrase),
    CONSTRAINT chk_ai_time_phrase_corpus_phrase
        CHECK (normalized_phrase <> '' AND normalized_phrase = btrim(normalized_phrase))
);

-- 跑批读路径: "这个 domain 下还没晋升的语料, 最近命中的排前面"。
CREATE INDEX IF NOT EXISTS idx_ai_time_phrase_corpus_unpromoted
    ON ai_time_phrase_corpus (domain, last_seen_at DESC)
    WHERE promoted_at IS NULL;

-- GRANT DML (recurring grant-gap — 迁移 runner 以 postgres 超级用户建表且
-- 不自动 grant; 漏了它, 写入方是 fail-open + warning, 会让每次写入都被吞成
-- 一条 warning、表永远 0 行, 而"表永远 0 行"正是这张表要消灭的那个歧义。
-- 镜像 V20260708_02__grant_pending_clarifications.sql。)
-- 无 DELETE: 语料清理不是运行时职责, 需要就走人工 SQL。
GRANT SELECT, INSERT, UPDATE ON ai_time_phrase_corpus TO smartbi_user;

COMMENT ON TABLE ai_time_phrase_corpus IS
    '确定性时间规则认不出、但 LLM double-check 认出的时间说法语料。攒够了由人工晋升进 _resolve_sales_date_range 的确定性分支; 本表自身不自动生效任何规则。';
COMMENT ON COLUMN ai_time_phrase_corpus.hit_count IS
    '仅用于人工晋升排优先级, ⛔ 不是"用户问了几次"的频次指标 —— parse_restaurant_query 一次提问可能触发 2+ 次记录 (chat.py 的 peek + _try_tiered_restaurant_intent 各解析一次), 已在累加语义里折叠, 不代表去重后的真实问询次数。';
COMMENT ON COLUMN ai_time_phrase_corpus.llm_phrase IS
    'LLM 给出的规范化时间短语 (如"最近30天"), 不是原句。';
COMMENT ON COLUMN ai_time_phrase_corpus.llm_time_range IS
    'LLM 给出的结构化时间窗口 (spec.window_from_llm_phrase 的产出), 供人工晋升时参考, 不由本表重新计算。';
COMMENT ON COLUMN ai_time_phrase_corpus.promoted_at IS
    '非空即表示已人工晋升进确定性规则; list_unpromoted 只返回此列为 NULL 的行。';
COMMENT ON COLUMN ai_time_phrase_corpus.factory_id IS
    '首次记录到这句话的租户; ON CONFLICT ⛔ 不刷新它 —— 不是「谁问的」的权威来源';

-- Verification (run after apply):
--   SELECT count(*) FROM ai_time_phrase_corpus;                      -- expect 0 (no seed)
--   SET ROLE smartbi_user;
--   INSERT INTO ai_time_phrase_corpus
--       (domain, normalized_phrase, factory_id, raw_query, llm_phrase, llm_time_range)
--     VALUES ('restaurant', '最近损耗怎么样', 'DEMO_REST', '最近损耗怎么样',
--             '最近30天', '{"type":"relative","unit":"day","count":30}'::jsonb);
--   SELECT domain, normalized_phrase, hit_count, promoted_at FROM ai_time_phrase_corpus;
--   -- expect 1 row, hit_count=1, promoted_at=NULL
--   RESET ROLE;
--
-- Rollback:
--   DROP TABLE IF EXISTS ai_time_phrase_corpus;
--   (the runtime fails open to "no corpus recorded" when the table is missing)
