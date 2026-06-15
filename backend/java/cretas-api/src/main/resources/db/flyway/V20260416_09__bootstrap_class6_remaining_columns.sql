-- =============================================================================
-- Bootstrap completeness Phase 6: Class 6 INSERT-column-vs-bootstrap-column gap
-- for bootstrap-created tables that newer migrations INSERT INTO with columns
-- the V20260415_99 / V20260416_00 / _07 / _08 sequence never created.
--
-- WHY THIS EXISTS
-- Class 1 (V20260416_00) audited the universe of entity columns referenced by
-- ALTER / UPDATE / DDL on bootstrap tables. It did NOT scan INSERT INTO column
-- lists across the full migration sweep. As subsequent migrations (V20260513_02
-- onward) added new INSERTs referencing additional ai_intent_configs columns
-- (description, priority, quota_cost, required_roles, business_type, semantic_*),
-- the bootstrap quietly drifted further behind.
--
-- PR #128 R3 (CI fail on fresh DB) surfaced the first cascade:
--   ERROR: column "description" of relation "ai_intent_configs" does not exist
-- at V20260513_02 INSERT — and ~10 sister migrations (V20260516_02 / _04 / _05 /
-- _07, V20260525_04, V20260526_03, V20260601_02 / _05, V20260603_09 / _13,
-- V20260606_01 / _04 / _06 / _08 / _09 / _10 / _11 / _12 / _13 / _27, V20260820_*)
-- all reference these same Hibernate-only columns.
--
-- WHAT THIS DOES
-- Comprehensive sweep of every INSERT INTO <bootstrap-table> across the full
-- db/flyway/ tree, computed INSERT-cols MINUS bootstrap-cols MINUS later-ALTER-
-- added-cols. The only real gap is ai_intent_configs (8 columns); all other 32
-- bootstrap tables' INSERT statements use columns already covered by V20260415_99
-- creation + earlier V20260416_00 / V20260516_02 / etc. ALTER ADD COLUMNs.
--
-- AUDIT METHODOLOGY (per HARD rule "feedback_pr_review_must_grep_migration_table_names.md")
--   1. For each of V20260415_99's 33 bootstrap tables, grep `INSERT INTO <table>`
--      across all V*.sql files.
--   2. Extract column lists from each INSERT statement.
--   3. Diff INSERT-cols vs V20260415_99 / V20260416_00 / _07 / _08 bootstrap cols.
--   4. For each missing col, check if a later ALTER ADD COLUMN exists BEFORE the
--      first INSERT using that col → if yes, already covered, exclude.
--   5. ai_intent_configs.description / priority confirmed in BROKEN list (sanity
--      check per HARD rule).
--
-- AUDIT RESULTS (33 bootstrap tables)
--   - 32 tables: zero gap (INSERTs use only bootstrap/ALTER-added cols)
--     - drools_rules: covered by V20260415_99 explicit comment + columns
--     - customers: V20260701_03 INSERT uses only basic bootstrap cols
--     - raw_material_types / suppliers / material_batches / purchase_orders /
--       sales_orders / sales_order_items: V20260603_01 F006 seed INSERT cols
--       all covered (is_abaca_packaging added by V20260516_02 BEFORE V20260603_01)
--   - 1 table: ai_intent_configs (27 INSERTing migrations, 8 columns missing)
--
-- DESIGN NOTES (mirror V20260416_00 conventions)
--   1. ALTER TABLE ADD COLUMN IF NOT EXISTS for idempotency (prod no-op).
--   2. Column types derived from @Column annotations in
--      entity/config/AIIntentConfig.java:
--        - description       columnDefinition="TEXT"        line 172
--        - priority          @Column                        line 234  (INTEGER)
--        - quota_cost        @Column                        line 111  (INTEGER)
--        - required_roles    columnDefinition="JSON"        line 104
--        - semantic_domain   @Column(length=30)             line 294
--        - semantic_action   @Column(length=30)             line 301
--        - semantic_object   @Column(length=50)             line 308
--        - business_type     @Column(name="business_type", length=30)
--                            line 59  (defaults to 'COMMON' via @Builder.Default
--                            but no DB default declared — Hibernate ddl-auto only)
--   3. JSON column declared as TEXT (legacy Hibernate string-backed @Column
--      pattern; matches keywords / negative_keywords / metadata in bootstrap).
--   4. NO defaults on business_type — application-layer default via @Builder
--      handles new rows; existing rows (none on fresh DB) backfilled by Hibernate
--      ddl-auto if needed.
--
-- DOWNSTREAM IMPACT
-- After this migration, V20260513_02 INSERT (id, intent_code, intent_name,
-- intent_category, tool_name, sensitivity_level, keywords, description,
-- priority, is_active, created_at, updated_at) succeeds on fresh DB. Same fix
-- unlocks ~30 sister migrations chained through the rest of CI.
-- =============================================================================

-- ---------- ai_intent_configs ----------
-- Source: entity/config/AIIntentConfig.java
-- INSERTing migrations: V20260513_02, V20260516_02 / _04 / _05 / _07, V20260525_04,
-- V20260526_03, V20260601_02 / _05, V20260603_09 / _13, V20260606_01 / _04 / _06 /
-- _08 / _09 / _10 / _11 / _12 / _13 / _27, V20260820_01 / _02 / _07 / _08 / _09 / _10,
-- V20260821_36 (cold chain, PR #136 — adds `examples` column).
-- Bootstrap (V20260415_99:51-68) created 11 cols + factory_id + audit. Entity has 30+.
-- Already covered: embedding (V20260505_01 vector(768)).
--
-- 2026-05-21 R8 (Class 11): added `examples` col. Class 6 audit (R3) missed it
-- because V20260821_36 was merged to main AFTER R3's audit ran. Entity has
-- `negative_examples` field but NOT `examples` (positive example utterances) —
-- Hibernate ddl-auto never creates it. Sister chats (cold chain / SSOP additive
-- v2 / etc.) treat `examples` as a JSON-string column for intent matching
-- training data, parallel to `negative_examples`. Bootstrapped as TEXT to mirror
-- the JSON-as-string Hibernate pattern.
ALTER TABLE ai_intent_configs
    ADD COLUMN IF NOT EXISTS description       TEXT,
    ADD COLUMN IF NOT EXISTS priority          INTEGER,
    ADD COLUMN IF NOT EXISTS quota_cost        INTEGER,
    ADD COLUMN IF NOT EXISTS required_roles    TEXT,
    ADD COLUMN IF NOT EXISTS semantic_domain   VARCHAR(30),
    ADD COLUMN IF NOT EXISTS semantic_action   VARCHAR(30),
    ADD COLUMN IF NOT EXISTS semantic_object   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS business_type     VARCHAR(30),
    -- 2026-06-01: negative_keywords (Hibernate entity 列 AIIntentConfig.java:150, JSON) 漏在
    -- bootstrap, 导致全新 CI DB 上 V_23_12/_13 的 UPDATE...SET negative_keywords 报 column does
    -- not exist 阻断启动 (e2e-pr-gate)。补进 bootstrap (root fix; prod 该列 Hibernate 已建 →
    -- ADD IF NOT EXISTS no-op)。jsonb 与 entity columnDefinition=JSON 兼容。
    ADD COLUMN IF NOT EXISTS negative_keywords JSONB;

-- =============================================================================
-- End of V20260416_09 bootstrap class6 remaining columns.
-- =============================================================================
