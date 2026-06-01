-- Sprint 11 D6+ hotfix — fix V_23_12 SQL error.
-- V_23_12 used `jsonb_build_array(...)::text` but the column `negative_keywords` is `jsonb` not text.
-- Postgres error: "column negative_keywords is of type jsonb but expression is of type text".
-- This crashed all Java backend startups and blocked V_23_12 application.
--
-- Fix: remove ::text cast — jsonb_build_array() already returns jsonb.
--
-- ⚠️ 2026-06-01 修 e2e-pr-gate 全新 CI DB (同 V_23_12 — column-existence + row-existence 守卫):
--   ai_intent_configs.negative_keywords 是 Hibernate entity 列 (无 Flyway ADD COLUMN), 这些
--   intent 行也由 app 运行时 seed (非 Flyway INSERT)。全新 DB 上 Flyway 先于 ddl-auto+app seed
--   跑 → 裸 UPDATE...SET negative_keywords 报 "column does not exist" 阻断启动。整 body 包进
--   column-existence 守卫: 列存在才跑 (prod 不变); 列不存在整体跳过 (Hibernate 建列, app seed)。

DO $$
DECLARE rq INT; sgm INT; bcq INT;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'ai_intent_configs' AND column_name = 'negative_keywords'
    ) THEN
        RAISE NOTICE 'V_23_13 skipped: ai_intent_configs.negative_keywords column absent (fresh DB pre-ddl-auto) — Hibernate creates column + app seeds keywords at runtime, non-fatal';
        RETURN;
    END IF;

    -- REPORT_QUALITY — yield indicator-class queries to INDICATOR_QUERY
    UPDATE ai_intent_configs SET negative_keywords = jsonb_build_array(
        '良品率怎么样', '客单价', '食安通过率', '翻台率', '菜品毛利',
        '损耗率', '指标', '指标走势', '指标趋势', '指标现状',
        '今天客单价'
    ) WHERE intent_code = 'REPORT_QUALITY';

    -- SKU_GROSS_MARGIN
    UPDATE ai_intent_configs SET negative_keywords = jsonb_build_array(
        '客单价', '良品率', '食安', '翻台率', '菜品毛利',
        '损耗率', '指标', '今天', '怎么样'
    ) WHERE intent_code = 'SKU_GROSS_MARGIN';

    -- BATCH_CONSUMPTION_QUERY
    UPDATE ai_intent_configs SET negative_keywords = jsonb_build_array(
        '哪些原料', '原料流向', '批次溯源', '召回', '溯源',
        '上游下游', '流向了哪些'
    ) WHERE intent_code = 'BATCH_CONSUMPTION_QUERY';

    SELECT jsonb_array_length(negative_keywords) INTO rq FROM ai_intent_configs WHERE intent_code = 'REPORT_QUALITY';
    SELECT jsonb_array_length(negative_keywords) INTO sgm FROM ai_intent_configs WHERE intent_code = 'SKU_GROSS_MARGIN';
    SELECT jsonb_array_length(negative_keywords) INTO bcq FROM ai_intent_configs WHERE intent_code = 'BATCH_CONSUMPTION_QUERY';
    RAISE NOTICE 'V_23_13: REPORT_QUALITY neg_keywords: %', rq;
    RAISE NOTICE 'V_23_13: SKU_GROSS_MARGIN neg_keywords: %', sgm;
    RAISE NOTICE 'V_23_13: BATCH_CONSUMPTION_QUERY neg_keywords: %', bcq;
    -- 行不存在 count=NULL → COALESCE + WARNING, 不阻断启动。
    IF COALESCE(rq,0) < 5 OR COALESCE(sgm,0) < 5 OR COALESCE(bcq,0) < 5 THEN
        RAISE WARNING 'V_23_13: negative_keywords not populated (rq=%, sgm=%, bcq=% — fresh-DB intent rows seeded by app at runtime, non-fatal)', rq, sgm, bcq;
    END IF;
END $$;
