-- T3「标事件」：店长口头说的那件事，落成一条**主观数据**。
--
-- ## 为什么需要一张新表（本阶段唯一的新建）
--
-- 归因走到**无痕层**时（下一层在登记表里没有对应的 metric/dimension，
-- 库里再也没有能解释这个波动的东西），系统只能问：
--   「8 月 12 日 罗氏虾 卖了平时的 3 倍，这天有没有做什么活动 / 是不是有人在推？」
-- 他答了，那句话就是**目前唯一存在的解释**。不存下来，明天同一个波动
-- 会再问他一遍 —— 那正是「问过的问题不再问第二遍」要消除的。
--
-- ⛔ 不复用任何既有表：`fact_pos_*` 是实测流水，把主观标注混进去就是
--    **让下一次归因把「老板说那天做了活动」当成事实去推**。owner 明确裁定：
--    事件标注是主观数据，有自己的 provenance，绝不和实测混算。
--
-- ## 🔴 承重约束：它永远不进「实测」那一侧
--
--   · `provenance` 列**不可为空**且**只允许一个值** —— `REPORTED_BY_USER`。
--     用 CHECK 约束钉死：任何一行想标成 MEASURED 都写不进来。
--   · 表名带 `annotation` 而不是 `fact_` —— 命名本身就是一道提示。
--   · 闸见 `tests/test_event_annotation.py`：
--     `test_annotations_never_enter_measured_computation`
--
-- ## 幂等 / 隔离
--
--   · 唯一键 `(factory_id, event_date, subject_kind, subject_name)` ——
--     同一天同一个对象只留最后一次回答（他改口了以最后一次为准）。
--   · RLS + FORCE，与本库其它租户表一致。
--   · ⚠️ `answered_by` 存角色而不是姓名：这张表会被读进答案正文，
--     姓名进正文等于把内部人员信息端给别的角色看。

CREATE TABLE IF NOT EXISTS fact_restaurant_event_annotation (
    id                BIGSERIAL PRIMARY KEY,
    factory_id        VARCHAR(64)  NOT NULL,
    -- 这条标注解释的是**哪一天**的波动
    event_date        DATE         NOT NULL,
    -- 解释的对象：dish / store / all（全店）
    subject_kind      VARCHAR(16)  NOT NULL,
    subject_name      VARCHAR(120) NOT NULL DEFAULT '',
    -- 我们当时问他的那句话（原样存，⛔ 不重新措辞 —— 答案要能对上问题）
    asked_question    TEXT         NOT NULL,
    -- 他的原话。⚠️ 不做结构化抽取：抽出来的「活动类型」是我们猜的，
    --    而这张表的全部价值就在于它是**他说的**。
    answer_text       TEXT         NOT NULL,
    answered_by_role  VARCHAR(64)  NOT NULL DEFAULT '',
    -- 🔴 只允许一个值。想写 MEASURED 的行直接被约束挡住。
    provenance        VARCHAR(32)  NOT NULL DEFAULT 'REPORTED_BY_USER',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_event_annotation_is_subjective
        CHECK (provenance = 'REPORTED_BY_USER'),
    CONSTRAINT ck_event_annotation_subject_kind
        CHECK (subject_kind IN ('dish', 'store', 'all'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_event_annotation_subject
    ON fact_restaurant_event_annotation
       (factory_id, event_date, subject_kind, subject_name);

CREATE INDEX IF NOT EXISTS ix_event_annotation_factory_date
    ON fact_restaurant_event_annotation (factory_id, event_date DESC);

ALTER TABLE fact_restaurant_event_annotation ENABLE  ROW LEVEL SECURITY;
ALTER TABLE fact_restaurant_event_annotation FORCE   ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fact_restaurant_event_annotation;
CREATE POLICY tenant_isolation ON fact_restaurant_event_annotation FOR ALL
    USING (factory_id = current_setting('app.factory_id', TRUE))
    WITH CHECK (factory_id = current_setting('app.factory_id', TRUE));

GRANT SELECT, INSERT, UPDATE, DELETE
    ON fact_restaurant_event_annotation TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE fact_restaurant_event_annotation_id_seq
    TO smartbi_user;

-- 回滚（只在没写入过标注时安全）：
--   DROP TABLE IF EXISTS fact_restaurant_event_annotation;
