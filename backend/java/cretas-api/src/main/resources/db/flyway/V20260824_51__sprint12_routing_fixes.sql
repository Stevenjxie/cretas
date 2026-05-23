-- Sprint 12 Canvas/Workdesk + AI Factory coop close — routing-bug fixes
-- 9 routing bugs documented in docs/audits/2026-05-23-sprint12-e2e-framework/AI-FACTORY-HANDOFF.md
-- Fixes: 100% strict-PASS audit close-gate row (was 80% blocked on routing)
-- Audit verification: bash docs/audits/2026-05-23-sprint12-e2e-framework/runner.sh after deploy
--
-- ============================================================================
-- Bug mapping to UPDATE blocks below
-- ============================================================================
-- Block 1 (Bug #4, #6) — MATERIAL_BATCH_CREATE: remove bare 入库/到货 read-shaped keywords
-- Block 2 (Bug #4, #5) — WAREHOUSE_KEEPER_TODAY_TASKS: add 本日待入库 family
-- Block 3 (Bug #1)     — MONTHLY_FINANCIAL_CLOSE: add 业绩 family
-- Block 4 (Bug #2, #3) — disable FOOD_KNOWLEDGE_QUERY + add HACCP family to FOOD_SAFETY_RECALL
-- Block 5 (Bug #7, #8) — PURCHASER_WEEKLY_PLAN: add 下周采购建议 / 下周补货清单 / 补货清单
-- Block 6 (Bug #5)     — REPORT_INVENTORY: add 入库统计 family (fallback for inventory stats queries)
-- Block 7 (Bug #7, #9) — ORDER_LIST: (defensive) verify no bare 采购 in keywords; current seed clean
--
-- Idempotency: all UPDATEs guarded by WHERE intent_code = '...'. No new rows inserted.
-- jsonb cast `'[...]'::jsonb` per database-entity-sync.md Sprint 9 V_36 lesson + V_23_13 fix.
-- Append pattern: `keywords::jsonb || '[...]'::jsonb` (PG ≥9.5) preserves existing keywords.
-- Remove pattern: `keywords::jsonb - 'bare_keyword'` (PG ≥10) removes string element by value.
--
-- Concurrent-edit-safety: per .claude/rules/concurrent-edit-safety.md, this file is single-
-- session authored. Verify with `git diff --stat` after commit.

-- ============================================================================
-- Block 1 — Bug #4 #6 — MATERIAL_BATCH_CREATE: strip bare 入库/到货 read-shaped keywords
-- ============================================================================
-- Current (per V2026_01_17_02): ["入库","原料入库","新到原料","到货","入库原料","新增原料批次","物料入库","原料到货"]
-- Bare 入库 + 到货 misroute single-noun / read-shaped queries ("本日待入库", "入库") to WRITE.
-- Keep write-shaped: 原料入库, 新到原料, 入库原料, 新增原料批次, 物料入库, 原料到货.
-- Plus add explicit verb-prefixed write triggers: 创建批次, 新增批次, 录入入库.
UPDATE ai_intent_configs
SET keywords = '["原料入库","新到原料","入库原料","新增原料批次","物料入库","原料到货","创建批次","新增批次","录入入库","入库新增","新批次","录入原料批次","新建原料批次"]'::jsonb,
    updated_at = NOW()
WHERE intent_code = 'MATERIAL_BATCH_CREATE';

-- ============================================================================
-- Block 2 — Bug #4 #5 — WAREHOUSE_KEEPER_TODAY_TASKS: add read-query keywords
-- ============================================================================
-- Current (per V20260820_08): ["今天要收什么货","今日到货","今天到什么货","待收货清单","今天有什么货","今日入库任务","今天有几个供应商来送货","今日待收","仓管员工作台","今天该收什么","明天到什么货","待收清单","仓管今日任务"]
-- Add: 本日待入库 / 今日入库 / 收什么货 / 待入库清单 / 入库清单
UPDATE ai_intent_configs
SET keywords = (keywords::jsonb || '["本日待入库","今日入库","收什么货","待入库清单","入库清单"]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'WAREHOUSE_KEEPER_TODAY_TASKS';

-- ============================================================================
-- Block 3 — Bug #1 — MONTHLY_FINANCIAL_CLOSE: strengthen 业绩 family keyword bindings
-- ============================================================================
-- Current (per V20260820_02): ["本月经营","月度复盘","X 月经营","经营怎么样","本月财务","月底总结","月度经营摘要","财务主管工作台","经营摘要","月度报告","财务月报","5 月经营怎么样","本月经营总结"]
-- Add: 业绩 / 本月业绩 / 业绩如何 / 经营如何 / 这个月业绩
UPDATE ai_intent_configs
SET keywords = (keywords::jsonb || '["业绩","本月业绩","业绩如何","经营如何","这个月业绩"]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'MONTHLY_FINANCIAL_CLOSE';

-- ============================================================================
-- Block 4 — Bug #2 #3 — FOOD_KNOWLEDGE_QUERY disable + FOOD_SAFETY_RECALL HACCP keywords
-- ============================================================================
-- Audit evidence: FOOD_KNOWLEDGE_QUERY matched but executor unbound → FAILED status.
-- Per handoff doc Option B (disable + reroute) — safer than binding unverified RAG endpoint.
-- IF EXISTS guard via WHERE — UPDATE is no-op if intent doesn't exist as DB row.
UPDATE ai_intent_configs
SET is_active = false,
    updated_at = NOW()
WHERE intent_code = 'FOOD_KNOWLEDGE_QUERY';

-- Add HACCP / 食安 family to FOOD_SAFETY_RECALL keyword list.
-- Current (per V20260820_07): ["启动召回","食品召回","拉肚子","客户投诉","出问题","召回 X 批次","召回 B-","召回流程","食品安全事件","需要召回","客户中毒","客户不舒服","食源性"]
-- Add: HACCP / 食安状态 / 食安监控 / 食安告警 / HACCP 状态 / HACCP 监控
UPDATE ai_intent_configs
SET keywords = (keywords::jsonb || '["HACCP","食安状态","食安监控","食安告警","HACCP 状态","HACCP 监控"]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'FOOD_SAFETY_RECALL';

-- ============================================================================
-- Block 5 — Bug #7 #8 — PURCHASER_WEEKLY_PLAN: add 下周采购建议 / 下周补货清单 / 补货清单
-- ============================================================================
-- Current (per V20260820_09): ["下周采购什么","下周采购","采购计划","采购员工作台","本周采购","下周买什么","下周该采购什么","下周需要采什么","采购员任务","本周采购计划","下周补货","下周缺什么"]
-- Add: 下周采购建议 / 下周补货清单 / 补货清单 / 本周缺料分析
-- (下周采购什么 already exists per V_09; do not re-add to avoid potential dupe noise)
UPDATE ai_intent_configs
SET keywords = (keywords::jsonb || '["下周采购建议","下周补货清单","补货清单","本周缺料分析"]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'PURCHASER_WEEKLY_PLAN';

-- ============================================================================
-- Block 6 — Bug #5 — REPORT_INVENTORY: add 入库统计 family (fallback for stats queries)
-- ============================================================================
-- Current (per V2026_01_04_3): ["库存报表", "库存统计", "原料报表", "成品库存", "库存盘点报告"]
-- Add: 上月入库统计 / 本月入库统计 / 入库统计
-- (REPORT_INVENTORY chosen over MATERIAL_BATCH_QUERY because inventory statistics align with
--  reporting category; closer semantic fit than batch query)
UPDATE ai_intent_configs
SET keywords = (keywords::jsonb || '["上月入库统计","本月入库统计","入库统计"]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'REPORT_INVENTORY';

-- ============================================================================
-- Block 7 — Bug #7 #9 — ORDER_LIST: defensive remove bare 采购 if present
-- ============================================================================
-- Current seed (per V2026_01_25_01) is clean: ["订单","订单列表","查订单","所有订单","订单查询","看订单","订单情况","订单记录","订单明细","查看订单"]
-- However the audit shows ORDER_LIST capturing "下周采购建议" and bare "采购" — likely via
-- partial-match on 订单/采购 substring. Defensive: remove bare 采购 if any later migration
-- added it. `jsonb - 'value'` is no-op if element absent (idempotent).
-- ALSO remove 下周采购建议 if present (Block 5 owns this keyword now).
UPDATE ai_intent_configs
SET keywords = (keywords::jsonb - '采购' - '下周采购建议'),
    updated_at = NOW()
WHERE intent_code = 'ORDER_LIST';

-- ============================================================================
-- Verification
-- ============================================================================
DO $$
DECLARE
    mbc_count INT;
    mbc_has_bare_inbound BOOLEAN;
    wkt_count INT;
    mfc_has_yeji BOOLEAN;
    fkq_active BOOLEAN;
    fsr_has_haccp BOOLEAN;
    pwp_has_buildchgliist BOOLEAN;
    rinv_has_inboundstats BOOLEAN;
BEGIN
    -- Block 1
    SELECT jsonb_array_length(keywords::jsonb) INTO mbc_count
        FROM ai_intent_configs WHERE intent_code = 'MATERIAL_BATCH_CREATE';
    SELECT (keywords::jsonb @> '"入库"'::jsonb) INTO mbc_has_bare_inbound
        FROM ai_intent_configs WHERE intent_code = 'MATERIAL_BATCH_CREATE';
    RAISE NOTICE 'V_24_51 Block 1: MATERIAL_BATCH_CREATE keywords count=%, has bare 入库=%',
        mbc_count, mbc_has_bare_inbound;
    IF mbc_has_bare_inbound THEN
        RAISE EXCEPTION 'V_24_51 FAIL Block 1: MATERIAL_BATCH_CREATE still has bare 入库 keyword';
    END IF;

    -- Block 2
    SELECT jsonb_array_length(keywords::jsonb) INTO wkt_count
        FROM ai_intent_configs WHERE intent_code = 'WAREHOUSE_KEEPER_TODAY_TASKS';
    RAISE NOTICE 'V_24_51 Block 2: WAREHOUSE_KEEPER_TODAY_TASKS keywords count=%', wkt_count;

    -- Block 3
    SELECT (keywords::jsonb @> '"业绩"'::jsonb) INTO mfc_has_yeji
        FROM ai_intent_configs WHERE intent_code = 'MONTHLY_FINANCIAL_CLOSE';
    RAISE NOTICE 'V_24_51 Block 3: MONTHLY_FINANCIAL_CLOSE has 业绩=%', mfc_has_yeji;

    -- Block 4
    SELECT is_active INTO fkq_active
        FROM ai_intent_configs WHERE intent_code = 'FOOD_KNOWLEDGE_QUERY';
    SELECT (keywords::jsonb @> '"HACCP"'::jsonb) INTO fsr_has_haccp
        FROM ai_intent_configs WHERE intent_code = 'FOOD_SAFETY_RECALL';
    RAISE NOTICE 'V_24_51 Block 4: FOOD_KNOWLEDGE_QUERY is_active=% (NULL if row absent), FOOD_SAFETY_RECALL has HACCP=%',
        fkq_active, fsr_has_haccp;

    -- Block 5
    SELECT (keywords::jsonb @> '"补货清单"'::jsonb) INTO pwp_has_buildchgliist
        FROM ai_intent_configs WHERE intent_code = 'PURCHASER_WEEKLY_PLAN';
    RAISE NOTICE 'V_24_51 Block 5: PURCHASER_WEEKLY_PLAN has 补货清单=%', pwp_has_buildchgliist;

    -- Block 6
    SELECT (keywords::jsonb @> '"入库统计"'::jsonb) INTO rinv_has_inboundstats
        FROM ai_intent_configs WHERE intent_code = 'REPORT_INVENTORY';
    RAISE NOTICE 'V_24_51 Block 6: REPORT_INVENTORY has 入库统计=%', rinv_has_inboundstats;

    -- Hard-fail invariants (skip when row absent → NULL fails IF check, treat as warning only)
    IF mbc_count IS NULL THEN
        RAISE NOTICE 'WARN V_24_51: MATERIAL_BATCH_CREATE row missing — Block 1 UPDATE was no-op';
    ELSIF mbc_count < 5 THEN
        RAISE EXCEPTION 'V_24_51 FAIL: MATERIAL_BATCH_CREATE has only % keywords, expected >=5', mbc_count;
    END IF;

    IF mfc_has_yeji IS NULL THEN
        RAISE NOTICE 'WARN V_24_51: MONTHLY_FINANCIAL_CLOSE row missing — Block 3 UPDATE was no-op';
    ELSIF NOT mfc_has_yeji THEN
        RAISE EXCEPTION 'V_24_51 FAIL: MONTHLY_FINANCIAL_CLOSE missing 业绩 keyword post-update';
    END IF;

    IF fsr_has_haccp IS NULL THEN
        RAISE NOTICE 'WARN V_24_51: FOOD_SAFETY_RECALL row missing — Block 4 UPDATE was no-op';
    ELSIF NOT fsr_has_haccp THEN
        RAISE EXCEPTION 'V_24_51 FAIL: FOOD_SAFETY_RECALL missing HACCP keyword post-update';
    END IF;
END $$;
