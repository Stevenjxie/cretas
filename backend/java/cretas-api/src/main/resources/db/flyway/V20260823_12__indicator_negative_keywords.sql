-- Sprint 11 D6+ — close keyword routing gaps for BI Tools.
-- Real AI audit showed 6/10 query route to INDICATOR_QUERY family, 2 fail to REPORT_QUALITY
-- (良品率怎么样) / SKU_GROSS_MARGIN (菜品毛利) / BATCH_CONSUMPTION_QUERY (这批用了哪些原料).
--
-- Add negative_keywords (penalty terms that score-deduct when matched) on competing intents,
-- so they yield to INDICATOR_QUERY / LINEAGE_QUERY for indicator-class queries.
--
-- Verification: post-deploy expect 10/10 BI routing accuracy on prior 10 query test set.
-- Reference: AI 工厂 chat audit 2026-05-22 evening, after PR #199 deploy.
--
-- ⚠️ 2026-06-01 修 e2e-pr-gate 全新 CI DB 启动失败 (column-existence + row-existence 双重守卫):
--   ai_intent_configs.negative_keywords 是 Hibernate entity 列 (AIIntentConfig.java:150,
--   columnDefinition JSON), 由 ddl-auto=update 建, 无 Flyway ADD COLUMN。这些 intent 行
--   (REPORT_QUALITY 等) 也由 app 运行时 seed (DataInitializer), 非 Flyway INSERT。
--   全新 DB 上 Flyway 先于 Hibernate ddl-auto + app seed 跑 → (a) 列不存在 → 裸 UPDATE...SET
--   negative_keywords 报 "column does not exist" 直接阻断启动; (b) 即便列在, 行也不存在 →
--   UPDATE no-op + count NULL。整个 body 包进 column-existence 守卫: 列存在才跑 (prod 早已
--   有列+行 → 行为不变); 列不存在则整体跳过 (Hibernate 随后建列, app 启动后 seed keywords)。
--   不静默 — 跳过时 RAISE NOTICE。validate-on-migrate=false → 改已 apply migration 不破 prod。

DO $$
DECLARE
    rq_count INT;
    sgm_count INT;
    bcq_count INT;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'ai_intent_configs' AND column_name = 'negative_keywords'
    ) THEN
        RAISE NOTICE 'V_23_12 skipped: ai_intent_configs.negative_keywords column absent (fresh DB pre-ddl-auto) — Hibernate creates column + app seeds keywords at runtime, non-fatal';
        RETURN;
    END IF;

    -- REPORT_QUALITY — yield 良品率/客单价/食安 indicator queries to INDICATOR_QUERY
    UPDATE ai_intent_configs SET negative_keywords = jsonb_build_array(
        '良品率怎么样', '客单价', '食安通过率', '翻台率', '菜品毛利',
        '损耗率', '指标', '指标走势', '指标趋势', '指标现状',
        '今天客单价'
    ) WHERE intent_code = 'REPORT_QUALITY';

    -- SKU_GROSS_MARGIN — yield 菜品毛利 indicator queries to INDICATOR_QUERY
    UPDATE ai_intent_configs SET negative_keywords = jsonb_build_array(
        '客单价', '良品率', '食安', '翻台率', '菜品毛利',
        '损耗率', '指标', '今天', '怎么样'
    ) WHERE intent_code = 'SKU_GROSS_MARGIN';

    -- BATCH_CONSUMPTION_QUERY — yield 原料追溯 queries to LINEAGE_QUERY
    UPDATE ai_intent_configs SET negative_keywords = jsonb_build_array(
        '哪些原料', '原料流向', '批次溯源', '召回', '溯源',
        '上游下游', '流向了哪些'
    ) WHERE intent_code = 'BATCH_CONSUMPTION_QUERY';

    SELECT jsonb_array_length(negative_keywords::jsonb) INTO rq_count
    FROM ai_intent_configs WHERE intent_code = 'REPORT_QUALITY';
    SELECT jsonb_array_length(negative_keywords::jsonb) INTO sgm_count
    FROM ai_intent_configs WHERE intent_code = 'SKU_GROSS_MARGIN';
    SELECT jsonb_array_length(negative_keywords::jsonb) INTO bcq_count
    FROM ai_intent_configs WHERE intent_code = 'BATCH_CONSUMPTION_QUERY';

    RAISE NOTICE 'V_23_12: REPORT_QUALITY negative_keywords: %', rq_count;
    RAISE NOTICE 'V_23_12: SKU_GROSS_MARGIN negative_keywords: %', sgm_count;
    RAISE NOTICE 'V_23_12: BATCH_CONSUMPTION_QUERY negative_keywords: %', bcq_count;

    -- 行不存在时 count=NULL (intent 行由 app 运行时 seed) → COALESCE + WARNING, 不阻断启动。
    IF COALESCE(rq_count,0) < 5 OR COALESCE(sgm_count,0) < 5 OR COALESCE(bcq_count,0) < 5 THEN
        RAISE WARNING 'V_23_12: negative_keywords not populated (rq=%, sgm=%, bcq=% — fresh-DB intent rows seeded by app at runtime, non-fatal)', rq_count, sgm_count, bcq_count;
    END IF;
END $$;
