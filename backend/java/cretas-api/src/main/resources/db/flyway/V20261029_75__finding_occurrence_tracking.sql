-- 发现的「连续提醒天数」——让重复的提示变成升级的提示。
--
-- 背景（Steve 2026-08-08 定的方向）：
--   我原本提「同一条发现 7 天内不重复」。Steve 纠正：
--   > 「静默的话为什么要静默呢，只要问就是有问题啊」
--   他是对的 —— 按时间压制会在老板最想知道的那一刻压制。
--   但第八天还说一模一样的话也是浪费。
--   ⇒ 正解：**重复不消除，措辞升级**。第一次「罗氏虾卖不动」，
--     第八天「罗氏虾已连续提醒 8 天」——重复本身变成信息（你还没处理）。
--
-- ⛔ 语义必须精确：这里记的是「**这条提醒连续出现了几天**」，
--    **不是**「这道菜连续几天卖不动」。后者要按天回算规则，代价太大；
--    而且前者其实更有用 —— 它说的是老板已经被提醒 N 天还没动。
--    渲染层的措辞必须与此逐字对齐，⛔ 不许说成「已连续 N 天卖不动」。
--
-- ⚠️ 只在**同步顺带提示**这个出口写：其余 4 个消费者（物料工具/损耗工具/
--    REST 端点/行动方案）不计入，否则「提醒了几天」会被后台调用污染。
--
-- 📌 天数按 `first_seen_on`/`last_seen_on` 两个**日期**算，不是次数：
--    老板一天问十次不该变成「提醒了十天」。

CREATE TABLE IF NOT EXISTS finding_occurrence (
    id              BIGSERIAL PRIMARY KEY,
    factory_id      VARCHAR(64)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    -- 同一条规则可能同时指向多个对象（三个食材各一条损耗发现），
    -- 所以身份是 (规则码, 对象)，不是规则码本身。
    subject_id      VARCHAR(255) NOT NULL,
    first_seen_on   DATE         NOT NULL,
    last_seen_on    DATE         NOT NULL,
    -- 实际出现过的**不同日期**数。连续性由 last_seen_on 与今天的间隔判断：
    -- 中断超过 1 天视为重新开始（在应用层重置 first_seen_on）。
    seen_days       INTEGER      NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_finding_occurrence UNIQUE (factory_id, code, subject_id)
);

CREATE INDEX IF NOT EXISTS idx_finding_occurrence_factory
    ON finding_occurrence (factory_id, last_seen_on DESC);

COMMENT ON TABLE finding_occurrence IS
    '发现的连续提醒天数。记的是「这条提醒连续出现了几天」，不是「这件事持续了几天」——'
    '渲染措辞必须与此对齐。只由同步顺带提示这一个出口写入。';
