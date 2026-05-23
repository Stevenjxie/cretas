-- Sprint 11 D6+ — close keyword routing gaps for BI Tools.
-- Real AI audit showed 6/10 query route to INDICATOR_QUERY family, 2 fail to REPORT_QUALITY
-- (良品率怎么样) / SKU_GROSS_MARGIN (菜品毛利) / BATCH_CONSUMPTION_QUERY (这批用了哪些原料).
--
-- Add negative_keywords (penalty terms that score-deduct when matched) on competing intents,
-- so they yield to INDICATOR_QUERY / LINEAGE_QUERY for indicator-class queries.
--
-- Verification: post-deploy expect 10/10 BI routing accuracy on prior 10 query test set.
-- Reference: AI 工厂 chat audit 2026-05-22 evening, after PR #199 deploy.

-- ============================================================================
-- REPORT_QUALITY — yield 良品率/客单价/食安 indicator queries to INDICATOR_QUERY
-- (REPORT_QUALITY remains for explicit reporting requests like "质量报表导出")
-- ============================================================================
UPDATE ai_intent_configs SET negative_keywords = jsonb_build_array(
    '良品率怎么样', '客单价', '食安通过率', '翻台率', '菜品毛利',
    '损耗率', '指标', '指标走势', '指标趋势', '指标现状',
    '今天客单价'
)::text WHERE intent_code = 'REPORT_QUALITY';

-- ============================================================================
-- SKU_GROSS_MARGIN — yield 菜品毛利 indicator queries to INDICATOR_QUERY
-- (SKU_GROSS_MARGIN remains for explicit SKU-level "查 SKU 毛利率" requests)
-- ============================================================================
UPDATE ai_intent_configs SET negative_keywords = jsonb_build_array(
    '客单价', '良品率', '食安', '翻台率', '菜品毛利',
    '损耗率', '指标', '今天', '怎么样'
)::text WHERE intent_code = 'SKU_GROSS_MARGIN';

-- ============================================================================
-- BATCH_CONSUMPTION_QUERY — yield 原料追溯 queries to LINEAGE_QUERY
-- (BATCH_CONSUMPTION_QUERY remains for explicit "查某批次消耗量" requests)
-- ============================================================================
UPDATE ai_intent_configs SET negative_keywords = jsonb_build_array(
    '哪些原料', '原料流向', '批次溯源', '召回', '溯源',
    '上游下游', '流向了哪些'
)::text WHERE intent_code = 'BATCH_CONSUMPTION_QUERY';

-- ============================================================================
-- Verify
-- ============================================================================
DO $$
DECLARE
    rq_count INT;
    sgm_count INT;
    bcq_count INT;
BEGIN
    SELECT jsonb_array_length(negative_keywords::jsonb) INTO rq_count
    FROM ai_intent_configs WHERE intent_code = 'REPORT_QUALITY';

    SELECT jsonb_array_length(negative_keywords::jsonb) INTO sgm_count
    FROM ai_intent_configs WHERE intent_code = 'SKU_GROSS_MARGIN';

    SELECT jsonb_array_length(negative_keywords::jsonb) INTO bcq_count
    FROM ai_intent_configs WHERE intent_code = 'BATCH_CONSUMPTION_QUERY';

    RAISE NOTICE 'V_23_12: REPORT_QUALITY negative_keywords: %', rq_count;
    RAISE NOTICE 'V_23_12: SKU_GROSS_MARGIN negative_keywords: %', sgm_count;
    RAISE NOTICE 'V_23_12: BATCH_CONSUMPTION_QUERY negative_keywords: %', bcq_count;

    IF rq_count < 5 OR sgm_count < 5 OR bcq_count < 5 THEN
        RAISE EXCEPTION 'V_23_12 FAIL: negative_keywords not populated';
    END IF;
END $$;
