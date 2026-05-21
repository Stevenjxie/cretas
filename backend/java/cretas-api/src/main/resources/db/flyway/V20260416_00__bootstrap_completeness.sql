-- =============================================================================
-- Bootstrap completeness migration: 14 Hibernate-managed tables missing from
-- V20260415_99__bootstrap_legacy_tables.sql.
--
-- WHY THIS EXISTS:
-- V20260415_99 (PR #881) shipped a 33-table bootstrap to fix the CI fresh-DB
-- e2e-pr-gate failure caused by Hibernate ddl-auto=update lazily creating
-- tables in prod that no Flyway script ever defined. The task #13 audit found
-- 14 additional Hibernate-managed tables (mostly @MappedSuperclass / config
-- domain) which V20260415_99 missed. ~25 downstream migrations (DML INSERT /
-- UPDATE / ALTER) target these tables and continue to break the e2e-pr-gate
-- on fresh CI databases:
--
--   ERROR: relation "module_schemas" does not exist
--   ERROR: relation "users" does not exist
--   ERROR: relation "role_definitions" does not exist
--   ...
--
-- WHAT THIS DOES:
-- Creates the 14 missing tables with CREATE TABLE IF NOT EXISTS so:
--   - Prod / test (where the tables already exist via Hibernate ddl-auto)
--     this migration is a no-op.
--   - CI / fresh dev DBs get the minimum schema needed for downstream
--     ALTERs / INSERTs / UPDATEs to succeed. Hibernate ddl-auto=update
--     fills in any remaining columns after Flyway completes.
--
-- DEPLOYMENT NOTE:
-- This file is V20260416_00 (between V20260415_99 and V20260416_01) so it
-- runs immediately after the original bootstrap. The properties baseline-
-- version has been lowered from 20260416.99 to 20260415.98, which causes
-- V20260415_99 + this V20260416_00 + later V20260416_xx files to apply on
-- fresh DBs while remaining a no-op on long-lived prod/test envs (CREATE
-- TABLE IF NOT EXISTS).
--
-- DESIGN NOTES (mirror V20260415_99):
--   1. Each CREATE uses IF NOT EXISTS for idempotency.
--   2. Includes id + factory_id (where applicable) + BaseEntity audit
--      (created_at / updated_at / deleted_at) for every table.
--   3. NO foreign key constraints — Hibernate adds them via ddl-auto=update
--      after Flyway succeeds, avoiding "referenced table does not exist"
--      ordering problems in CI.
--   4. NO indexes other than PK — existing flyway CREATE INDEX scripts use
--      IF NOT EXISTS and run later. Avoiding duplicate index definitions.
--   5. NO triggers — V20260416_00 is structural creation only.
--   6. Id types match @Entity definitions:
--        - BIGSERIAL for Long-typed @GeneratedValue(IDENTITY) ids
--        - VARCHAR(36) for uuid2-generated string ids
--        - VARCHAR(100) for ProductType (length=100 in entity)
--        - VARCHAR(255) for Factory id (no length → Hibernate default)
--        - VARCHAR(50) for FactorySettings (Integer IDENTITY)
--   7. JSONB for @Type(JsonBinaryType.class) columns (module_schemas).
--      TEXT (with original "JSON" / "TEXT" columnDefinition) preserved for
--      Hibernate string-backed JSON fields.
--   8. Columns intentionally OMITTED because a later V*.sql adds them:
--        - worker_daily_efficiency.source_batch_id (V20260519_05)
--        - customer_tracking_records.tracking_type   (V20260608_01)
--        - role_definitions.data_scope               (V20260519_08)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- module_schemas — Canvas schema-driven module configuration
-- -----------------------------------------------------------------------------
-- Source: entity/config/ModuleSchema.java
-- ~17 downstream migrations (V20260425_01..V20260513_01) UPDATE / INSERT here.
-- field_schema MUST be JSONB — V20260425_01 uses jsonb_set().
CREATE TABLE IF NOT EXISTS module_schemas (
    id                  BIGSERIAL    PRIMARY KEY,
    module_code         VARCHAR(64)  NOT NULL,
    module_name         VARCHAR(100) NOT NULL,
    module_category     VARCHAR(32)  NOT NULL,
    module_version      INTEGER      NOT NULL DEFAULT 1,
    field_schema        JSONB        NOT NULL,
    workflow_schema     JSONB,
    validation_schema   JSONB,
    permission_schema   JSONB,
    default_config      JSONB        NOT NULL,
    description         TEXT,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP,
    -- @Column(name="module_code", unique=true) — required for downstream
    -- ON CONFLICT (module_code) DO NOTHING patterns in seed migrations.
    CONSTRAINT uk_module_schemas_module_code UNIQUE (module_code)
);

-- -----------------------------------------------------------------------------
-- users — Auth + RBAC core table
-- -----------------------------------------------------------------------------
-- Source: entity/User.java extends BaseEntity
-- Referenced by FK created_by in many child tables (most already exist in
-- V20260415_99). Seed migrations (V20260430_01, V20260514_04, V20260603_01,
-- V20260701_03) insert users for test fixtures.
-- @Table uniqueConstraints = username; FK constraints omitted per design rule.
CREATE TABLE IF NOT EXISTS users (
    id                      BIGSERIAL    PRIMARY KEY,
    factory_id              VARCHAR(255) NOT NULL,
    username                VARCHAR(255) NOT NULL,
    password_hash           VARCHAR(255) NOT NULL,
    phone                   VARCHAR(255),
    email                   VARCHAR(100),
    full_name               VARCHAR(255),
    is_active               BOOLEAN      NOT NULL DEFAULT TRUE,
    department              VARCHAR(255),
    position                VARCHAR(255),
    role_code               VARCHAR(255),
    level                   INTEGER,
    platform_type           VARCHAR(20)  DEFAULT 'web,mobile',
    reports_to              BIGINT,
    secondary_reports_to    BIGINT,
    last_login              TIMESTAMP,
    monthly_salary          NUMERIC(10,2),
    expected_work_minutes   INTEGER,
    ccr_rate                NUMERIC(8,4),
    employee_code           VARCHAR(10),
    hire_type               VARCHAR(20)  DEFAULT 'FULL_TIME',
    contract_end_date       DATE,
    -- columnDefinition="JSON" on legacy MySQL entity; PG accepts JSONB.
    -- Kept as TEXT here because Hibernate string-backed @Column maps to text.
    skill_levels            TEXT,
    hourly_rate             NUMERIC(10,2),
    avatar_url              VARCHAR(255),
    hire_date               DATE,
    created_at              TIMESTAMP    DEFAULT NOW(),
    updated_at              TIMESTAMP    DEFAULT NOW(),
    deleted_at              TIMESTAMP,
    -- @UniqueConstraint(columnNames = {"username"})  per @Table on User
    CONSTRAINT uk_users_username UNIQUE (username),
    -- @Column(name="employee_code", unique=true)
    CONSTRAINT uk_users_employee_code UNIQUE (employee_code)
);

-- -----------------------------------------------------------------------------
-- factories — Organization / tenant root
-- -----------------------------------------------------------------------------
-- Source: entity/Factory.java extends BaseEntity
-- Referenced by every multi-tenant table's factory_id. Seed migrations
-- (V20260430_01 F999, V20260603_01 F006) insert factory rows.
-- @Table uniqueConstraints = name.
CREATE TABLE IF NOT EXISTS factories (
    id                  VARCHAR(255) PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    -- Org type/level (Sprint per organization-hierarchy refactor)
    type                VARCHAR(32)  NOT NULL DEFAULT 'FACTORY',
    parent_id           VARCHAR(191),
    level               INTEGER      NOT NULL DEFAULT 0,
    industry            VARCHAR(255),
    address             VARCHAR(255),
    employee_count      INTEGER,
    subscription_plan   VARCHAR(255),
    survey_company_id   VARCHAR(255),
    contact_name        VARCHAR(255),
    contact_phone       VARCHAR(255),
    contact_email       VARCHAR(255),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    confidence          REAL,
    factory_year        INTEGER,
    industry_code       VARCHAR(255),
    -- columnDefinition="json" on entity; text-backed @Column.
    inference_data      TEXT,
    legacy_id           VARCHAR(255),
    manually_verified   BOOLEAN      NOT NULL DEFAULT FALSE,
    region_code         VARCHAR(255),
    sequence_number     INTEGER,
    ai_weekly_quota     INTEGER      NOT NULL DEFAULT 20,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP,
    -- @UniqueConstraint(columnNames = {"name"})
    CONSTRAINT uk_factories_name UNIQUE (name)
);

-- -----------------------------------------------------------------------------
-- product_types — SKU / product template registry
-- -----------------------------------------------------------------------------
-- Source: entity/ProductType.java extends BaseEntity, id length=100
-- V20260603_01 seeds PT-F006-TEST-001/002. uniqueConstraints = (factory_id, code).
CREATE TABLE IF NOT EXISTS product_types (
    id                              VARCHAR(100) PRIMARY KEY,
    factory_id                      VARCHAR(255) NOT NULL,
    code                            VARCHAR(50)  NOT NULL,
    name                            VARCHAR(255) NOT NULL,
    category                        VARCHAR(50),
    unit                            VARCHAR(20)  NOT NULL,
    unit_price                      NUMERIC(10,2),
    production_time_minutes         INTEGER,
    shelf_life_days                 INTEGER,
    form_template_id                VARCHAR(100),
    default_sop_config_id           VARCHAR(50),
    work_hours                      NUMERIC(10,2),
    -- columnDefinition="JSON" on entity; text-backed @Column.
    processing_steps                TEXT,
    skill_requirements              TEXT,
    equipment_ids                   TEXT,
    quality_check_ids               TEXT,
    complexity_score                INTEGER,
    custom_schema_overrides         TEXT,
    package_spec                    VARCHAR(100),
    product_category                VARCHAR(50),
    specification                   VARCHAR(200),
    related_customer                VARCHAR(100),
    image_url                       VARCHAR(500),
    temperature_zone                VARCHAR(20),
    box_conversion_coefficient      NUMERIC(10,4),
    inventory_warning_threshold     NUMERIC(15,2),
    minimum_order_quantity          NUMERIC(15,2),
    brand                           VARCHAR(100),
    settlement_method               VARCHAR(50),
    tax_included_unit_price         NUMERIC(15,4),
    template_id                     VARCHAR(100),
    customer_id                     VARCHAR(191),
    recipe_version                  VARCHAR(50),
    is_active                       BOOLEAN      NOT NULL DEFAULT TRUE,
    notes                           TEXT,
    created_by                      BIGINT       NOT NULL,
    created_at                      TIMESTAMP    DEFAULT NOW(),
    updated_at                      TIMESTAMP    DEFAULT NOW(),
    deleted_at                      TIMESTAMP,
    -- @UniqueConstraint(columnNames = {"factory_id", "code"})
    CONSTRAINT uk_product_types_factory_code UNIQUE (factory_id, code)
);

-- -----------------------------------------------------------------------------
-- factory_validation_rules — Per-factory per-module validation rules
-- -----------------------------------------------------------------------------
-- Source: entity/config/FactoryValidationRule.java (Long IDENTITY id, no BaseEntity)
-- uniqueConstraints = (factory_id, module_code, rule_code).
-- Note: entity does NOT extend BaseEntity so NO deleted_at column.
CREATE TABLE IF NOT EXISTS factory_validation_rules (
    id                  BIGSERIAL    PRIMARY KEY,
    factory_id          VARCHAR(50),
    module_code         VARCHAR(64)  NOT NULL,
    rule_code           VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32),
    condition           TEXT         NOT NULL,
    error_message       TEXT         NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    severity            VARCHAR(16)  NOT NULL DEFAULT 'BLOCK',
    sort_order          INTEGER      DEFAULT 0,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    CONSTRAINT idx_fvr_factory_module_rule UNIQUE (factory_id, module_code, rule_code)
);

-- -----------------------------------------------------------------------------
-- factory_settings — Per-factory configuration (1:1 with factories)
-- -----------------------------------------------------------------------------
-- Source: entity/FactorySettings.java extends BaseEntity, id is Integer IDENTITY
-- @Column(name="factory_id", unique=true) — exactly one settings per factory.
CREATE TABLE IF NOT EXISTS factory_settings (
    id                          SERIAL       PRIMARY KEY,
    factory_id                  VARCHAR(50)  NOT NULL,
    factory_name                VARCHAR(255),
    factory_address             VARCHAR(255),
    contact_phone               VARCHAR(255),
    contact_email               VARCHAR(255),
    working_hours               INTEGER      NOT NULL DEFAULT 0,
    ai_settings                 TEXT,
    ai_weekly_quota             INTEGER      DEFAULT 20,
    allow_self_registration     BOOLEAN      DEFAULT FALSE,
    require_admin_approval      BOOLEAN      DEFAULT TRUE,
    default_user_role           VARCHAR(50)  DEFAULT 'viewer',
    notification_settings       TEXT,
    work_time_settings          TEXT,
    production_settings         TEXT,
    inventory_settings          TEXT,
    data_retention_settings     TEXT,
    language                    VARCHAR(10)  DEFAULT 'zh-CN',
    timezone                    VARCHAR(50)  DEFAULT 'Asia/Shanghai',
    date_format                 VARCHAR(20)  DEFAULT 'yyyy-MM-dd',
    currency                    VARCHAR(10)  DEFAULT 'CNY',
    enable_qr_code              BOOLEAN      DEFAULT TRUE,
    enable_batch_management     BOOLEAN      DEFAULT TRUE,
    enable_quality_check        BOOLEAN      DEFAULT TRUE,
    enable_cost_calculation     BOOLEAN      DEFAULT TRUE,
    enable_equipment_management BOOLEAN      DEFAULT TRUE,
    enable_attendance           BOOLEAN      DEFAULT TRUE,
    created_by                  BIGINT,
    updated_by                  BIGINT,
    last_modified_at            TIMESTAMP,
    created_at                  TIMESTAMP    DEFAULT NOW(),
    updated_at                  TIMESTAMP    DEFAULT NOW(),
    deleted_at                  TIMESTAMP,
    -- @UniqueConstraint(columnNames = {"factory_id"})
    CONSTRAINT uk_factory_settings_factory_id UNIQUE (factory_id)
);

-- -----------------------------------------------------------------------------
-- factory_home_layout — Per-factory home page layout config
-- -----------------------------------------------------------------------------
-- Source: entity/decoration/FactoryHomeLayout.java extends BaseEntity, Long id
-- @Column(name="factory_id", unique=true) — exactly one layout per factory.
CREATE TABLE IF NOT EXISTS factory_home_layout (
    id                  BIGSERIAL    PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    -- columnDefinition="JSON" on entity; text-backed @Column.
    modules_config      TEXT         NOT NULL,
    theme_config        TEXT,
    status              INTEGER      DEFAULT 1,
    version             INTEGER      DEFAULT 1,
    ai_generated        INTEGER      DEFAULT 0,
    ai_prompt           TEXT,
    created_by          BIGINT,
    grid_columns        INTEGER      DEFAULT 2,
    time_based_enabled  INTEGER      DEFAULT 0,
    morning_layout      TEXT,
    afternoon_layout    TEXT,
    evening_layout      TEXT,
    usage_stats         TEXT,
    last_suggestion_at  TIMESTAMP,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP,
    -- @UniqueConstraint(columnNames = {"factory_id"})
    CONSTRAINT uk_factory_home_layout_factory_id UNIQUE (factory_id)
);

-- -----------------------------------------------------------------------------
-- worker_daily_efficiency — Worker daily KPI summary
-- -----------------------------------------------------------------------------
-- Source: entity/WorkerDailyEfficiency.java extends BaseEntity, Long id
-- source_batch_id column intentionally OMITTED — added by V20260519_05.
CREATE TABLE IF NOT EXISTS worker_daily_efficiency (
    id                          BIGSERIAL    PRIMARY KEY,
    factory_id                  VARCHAR(50)  NOT NULL,
    worker_id                   BIGINT       NOT NULL,
    worker_name                 VARCHAR(50),
    work_date                   DATE         NOT NULL,
    shift_type                  VARCHAR(20),
    work_start_time             TIMESTAMP,
    work_end_time               TIMESTAMP,
    total_work_minutes          INTEGER,
    break_minutes               INTEGER,
    effective_work_minutes      INTEGER,
    total_piece_count           INTEGER,
    qualified_count             INTEGER,
    defect_count                INTEGER,
    quality_rate                NUMERIC(5,2),
    pieces_per_hour             NUMERIC(10,2),
    average_time_per_piece      NUMERIC(10,2),
    efficiency_score            NUMERIC(5,2),
    efficiency_trend            VARCHAR(20),
    workstation_id              VARCHAR(50),
    workstation_name            VARCHAR(100),
    process_stage_type          VARCHAR(50),
    product_type_id             VARCHAR(50),
    standard_pieces_per_hour    NUMERIC(10,2),
    compared_to_standard        NUMERIC(7,2),
    rank_in_team                INTEGER,
    ai_detected_count           INTEGER,
    manual_adjust_count         INTEGER,
    notes                       TEXT,
    extra_data                  TEXT,
    -- source_batch_id BIGINT — OMITTED, V20260519_05 adds it.
    created_at                  TIMESTAMP    DEFAULT NOW(),
    updated_at                  TIMESTAMP    DEFAULT NOW(),
    deleted_at                  TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- customer_tracking_records — Customer follow-up / visit history
-- -----------------------------------------------------------------------------
-- Source: entity/CustomerTrackingRecord.java extends BaseEntity, Long id
-- tracking_type column intentionally OMITTED — added by V20260608_01.
CREATE TABLE IF NOT EXISTS customer_tracking_records (
    id              BIGSERIAL    PRIMARY KEY,
    factory_id      VARCHAR(191) NOT NULL,
    customer_id     VARCHAR(191) NOT NULL,
    record_time     TIMESTAMP    NOT NULL,
    recorder_name   VARCHAR(100),
    recorder_id     BIGINT,
    content         TEXT,
    contact_person  VARCHAR(100),
    contact_phone   VARCHAR(50),
    address         VARCHAR(500),
    remark          TEXT,
    -- tracking_type VARCHAR(32) — OMITTED, V20260608_01 adds it.
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- form_templates — Customizable Formily schema per (factory, entity_type)
-- -----------------------------------------------------------------------------
-- Source: entity/config/FormTemplate.java extends BaseEntity, uuid2 id (length=36).
CREATE TABLE IF NOT EXISTS form_templates (
    id                  VARCHAR(36)  PRIMARY KEY,
    factory_id          VARCHAR(50),
    name                VARCHAR(100) NOT NULL,
    entity_type         VARCHAR(50)  NOT NULL,
    schema_json         TEXT,
    ui_schema_json      TEXT,
    description         VARCHAR(500),
    version             INTEGER      NOT NULL DEFAULT 1,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by          BIGINT,
    source              VARCHAR(20)  DEFAULT 'MANUAL',
    source_package_id   VARCHAR(50),
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- purchase_order_items — PO line items (1:N with purchase_orders)
-- -----------------------------------------------------------------------------
-- Source: entity/inventory/PurchaseOrderItem.java extends BaseEntity, Long id
-- parent purchase_orders table created in V20260415_99.
CREATE TABLE IF NOT EXISTS purchase_order_items (
    id                  BIGSERIAL    PRIMARY KEY,
    purchase_order_id   VARCHAR(191) NOT NULL,
    material_type_id    VARCHAR(191) NOT NULL,
    material_name       VARCHAR(200),
    quantity            NUMERIC(15,4) NOT NULL,
    unit                VARCHAR(20)  NOT NULL,
    unit_price          NUMERIC(15,4),
    tax_rate            NUMERIC(5,2) DEFAULT 0,
    received_quantity   NUMERIC(15,4) NOT NULL DEFAULT 0,
    remark              VARCHAR(500),
    specification       VARCHAR(200),
    box_quantity        NUMERIC(15,2),
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- role_definitions — RBAC role registry (NO JPA entity)
-- -----------------------------------------------------------------------------
-- No @Entity exists. Schema deduced from:
--   - db/migration-pg-converted/V2025_12_27__add_role_hierarchy_fields.sql:68
--     (legacy migration outside db/flyway/, hence the gap)
--   - DataScopeResolver.java line 89: SELECT data_scope FROM role_definitions
--     WHERE role_code = ?
--   - V20260519_08__role_data_scope.sql: ALTER TABLE adds data_scope column
--     conditionally (DO block). After our V20260416_00 creates the base
--     table, V20260519_08's IF EXISTS check passes and data_scope gets added.
-- data_scope column intentionally OMITTED — added by V20260519_08.
CREATE TABLE IF NOT EXISTS role_definitions (
    id              SERIAL       PRIMARY KEY,
    role_code       VARCHAR(50)  NOT NULL,
    display_name    VARCHAR(50)  NOT NULL,
    description     VARCHAR(200),
    level           INTEGER      NOT NULL DEFAULT 99,
    department      VARCHAR(50),
    is_deprecated   BOOLEAN      DEFAULT FALSE,
    -- data_scope VARCHAR(32) NOT NULL DEFAULT 'ALL' — OMITTED, V20260519_08 adds it.
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uk_role_definitions_role_code UNIQUE (role_code)
);

-- -----------------------------------------------------------------------------
-- system_enums — Database-driven enum config (replaces Java enum hard-codes)
-- -----------------------------------------------------------------------------
-- Source: entity/config/SystemEnum.java extends BaseEntity, uuid2 id (length=36).
-- V20260506_01 seeds MATERIAL_CATEGORY / MATERIAL_STORAGE_TYPE enums.
CREATE TABLE IF NOT EXISTS system_enums (
    id                  VARCHAR(36)  PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    enum_group          VARCHAR(50)  NOT NULL,
    enum_code           VARCHAR(50)  NOT NULL,
    enum_label          VARCHAR(100) NOT NULL,
    enum_description    VARCHAR(200),
    enum_value          VARCHAR(100),
    sort_order          INTEGER      DEFAULT 0,
    is_active           BOOLEAN      DEFAULT TRUE,
    is_system           BOOLEAN      DEFAULT TRUE,
    -- columnDefinition="JSON" on entity; text-backed @Column.
    metadata            TEXT,
    parent_code         VARCHAR(50),
    icon                VARCHAR(50),
    color               VARCHAR(20),
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP,
    -- @UniqueConstraint(columnNames = {"factory_id", "enum_group", "enum_code"})
    -- Required for ON CONFLICT (factory_id, enum_group, enum_code) in
    -- V20260506_01 seed migration.
    CONSTRAINT uk_system_enums_factory_group_code UNIQUE (factory_id, enum_group, enum_code)
);

-- -----------------------------------------------------------------------------
-- unit_of_measurements — Unit registry + conversion config
-- -----------------------------------------------------------------------------
-- Source: entity/config/UnitOfMeasurement.java extends BaseEntity, uuid2 id (length=36).
-- V20260507_03 seeds 26 common units (kg, g, ton, L, ...).
CREATE TABLE IF NOT EXISTS unit_of_measurements (
    id                  VARCHAR(36)  PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    unit_code           VARCHAR(20)  NOT NULL,
    unit_name           VARCHAR(100) NOT NULL,
    unit_symbol         VARCHAR(20),
    base_unit           VARCHAR(20)  NOT NULL,
    conversion_factor   NUMERIC(15,6),
    category            VARCHAR(50),
    decimal_places      INTEGER      DEFAULT 2,
    is_base_unit        BOOLEAN      DEFAULT FALSE,
    is_active           BOOLEAN      DEFAULT TRUE,
    is_system           BOOLEAN      DEFAULT TRUE,
    sort_order          INTEGER      DEFAULT 0,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP,
    -- @UniqueConstraint(columnNames = {"factory_id", "unit_code"})
    CONSTRAINT uk_unit_of_measurements_factory_code UNIQUE (factory_id, unit_code)
);

-- =============================================================================
-- BOOTSTRAP-TABLE COLUMN GAP FIXES — comprehensive entity-vs-bootstrap diff
-- =============================================================================
-- Some V20260415_99 bootstrap tables also lack Hibernate-supplied columns that
-- downstream Flyway migrations reference (or could once reference). Reactive
-- single-column patching failed 3 times in CI; this section is the result of
-- a systematic entity-vs-bootstrap diff for every bootstrap table the entity
-- file actually owns.
--
-- Pattern: ALTER TABLE … ADD COLUMN IF NOT EXISTS — idempotent on prod/test
-- where Hibernate already supplied these columns, while populating fresh CI DBs.
--
-- METHOD: Each @Column in the entity was diffed against V20260415_99's CREATE
-- TABLE block. Columns added by a later V*.sql ALTER (e.g. abaca, vflag, OCR,
-- tax_direct, with_goods, marker_color, settlement_date, customer_status,
-- vehicle_number/driver_*, lock columns, embedding) are EXCLUDED here.
--
-- Tables omitted (NOT referenced by any later migration DML/DDL, so Hibernate
-- ddl-auto=update can keep filling them post-Flyway with no Flyway error):
--   - smart_bi_datasource, factory_module_configs, ai_learned_expressions,
--     intent_match_records (the only ALTERs are pgvector adds covered already),
--     bom_items (already matched by bootstrap; entity wholly contained),
--     factory_equipment / equipment_alerts / equipment_maintenance /
--     batch_equipment_usage / suppliers / raw_material_types / material_batches
--     (all already match bootstrap or are covered by existing later ALTERs).
-- Latent risk for these tables falls back to ddl-auto=update on first boot,
-- which has not been a CI blocker historically (no Flyway script depends on
-- those entity-only columns).

-- ---------- invoice_records (3 migrations) ----------
-- Source: entity/finance/InvoiceRecord.java
-- Bootstrap (V20260415_99:717-733) created 15 columns. Entity declares 38.
-- Already covered elsewhere: OCR 7 cols (V20260606_03), tax_direct 6 cols (V20260519_01).
-- Bootstrap diverges: bootstrap has related_order_id/issue_date/notes/created_by
-- but entity has none of those; missing columns Hibernate filled in prod:
ALTER TABLE invoice_records
    ADD COLUMN IF NOT EXISTS sales_order_id    VARCHAR(191),
    ADD COLUMN IF NOT EXISTS customer_name     VARCHAR(200),
    ADD COLUMN IF NOT EXISTS amount            NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS tax_breakdown     JSONB,
    ADD COLUMN IF NOT EXISTS requested_by      BIGINT,
    ADD COLUMN IF NOT EXISTS requested_at      TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reviewed_by       BIGINT,
    ADD COLUMN IF NOT EXISTS reviewed_at       TIMESTAMP,
    ADD COLUMN IF NOT EXISTS review_notes      TEXT,
    ADD COLUMN IF NOT EXISTS issued_at         TIMESTAMP,
    ADD COLUMN IF NOT EXISTS invoice_pdf_url   VARCHAR(500),
    ADD COLUMN IF NOT EXISTS invoice_file_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS remark            TEXT;

-- ---------- sales_orders (10 migrations) ----------
-- Source: entity/inventory/SalesOrder.java
-- Bootstrap (V20260415_99:547-565) created 15 columns. Entity declares 38+.
-- Already covered: salesperson_id (V20260423_01 then V20260425_09 cast to BIGINT),
-- version (V20260424_07), default_tax_rate / default_invoice_type (V20260516_14),
-- marker_color (V20260516_11), vflag (V20260602_01).
ALTER TABLE sales_orders
    ADD COLUMN IF NOT EXISTS salesperson           VARCHAR(100),
    ADD COLUMN IF NOT EXISTS shipping_included     BOOLEAN,
    ADD COLUMN IF NOT EXISTS shipping_fee          NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS extra_fees            JSONB,
    ADD COLUMN IF NOT EXISTS actual_shipped_amount NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS finance_reviewed_by   BIGINT,
    ADD COLUMN IF NOT EXISTS finance_reviewed_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS finance_review_notes  TEXT,
    ADD COLUMN IF NOT EXISTS estimated_cost        NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS estimated_profit      NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS invoice_status        VARCHAR(32),
    ADD COLUMN IF NOT EXISTS invoiced_amount       NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS settlement_flag       BOOLEAN,
    ADD COLUMN IF NOT EXISTS paid_amount           NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS quote_id              VARCHAR(191),
    ADD COLUMN IF NOT EXISTS transport_plan_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS delivery_reminder_date DATE,
    ADD COLUMN IF NOT EXISTS box_quantity          NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS contract_file_url     VARCHAR(500),
    ADD COLUMN IF NOT EXISTS contract_file_name    VARCHAR(255);

-- ---------- purchase_orders (9 migrations) ----------
-- Source: entity/inventory/PurchaseOrder.java
-- Already covered: version (V20260424_07), is_imported (V20260606_05),
-- sales_order_id (V20260424_03), marker_color (V20260516_11), vflag (V20260602_01),
-- inquiry_quote_id (V20260606_17).
ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS finance_reviewed_by  BIGINT,
    ADD COLUMN IF NOT EXISTS finance_reviewed_at  TIMESTAMP,
    ADD COLUMN IF NOT EXISTS finance_review_notes TEXT;

-- ---------- customers (8 migrations) ----------
-- Source: entity/Customer.java
-- Already covered: version (V20260424_05), customer_status/importance/source/
-- last_contacted_at (V20260516_13), default_tax_rate/default_invoice_type
-- (V20260516_14), assigned_sales_user_id (V20260605_02), settlement_date
-- (V20260606_05), credit_period_days/credit_status (V20260606_20).
-- Only assigned_sales_user_assigned_at remains as Hibernate-only.
ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS assigned_sales_user_assigned_at TIMESTAMP;

-- ---------- sales_order_items (3 migrations) ----------
-- Source: entity/inventory/SalesOrderItem.java
-- Already covered: source_warehouse_code (V20260514_01), locked_qty/reserved_qty
-- (V20260603_06). Hibernate-only columns:
ALTER TABLE sales_order_items
    ADD COLUMN IF NOT EXISTS cost_unit_price NUMERIC(15, 4),
    ADD COLUMN IF NOT EXISTS tax_rate        NUMERIC(5, 2),
    ADD COLUMN IF NOT EXISTS specification   VARCHAR(200),
    ADD COLUMN IF NOT EXISTS box_quantity    NUMERIC(15, 2);

-- ---------- production_plans (3 migrations) ----------
-- Source: entity/ProductionPlan.java
-- Already covered: planned_date (V20260424_02), is_locked/lock_reason/locked_at/
-- locked_by (V20260606_02), vflag (V20260602_01).
-- Bootstrap (V20260415_99:327-343) creates a thin 14-col table; entity has 40+.
ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS expected_completion_date    DATE,
    ADD COLUMN IF NOT EXISTS plan_type                   VARCHAR(32),
    ADD COLUMN IF NOT EXISTS customer_order_number       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS priority                    INTEGER,
    ADD COLUMN IF NOT EXISTS allocated_quantity          NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS is_fully_matched            BOOLEAN,
    ADD COLUMN IF NOT EXISTS estimated_material_cost     NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS actual_material_cost        NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS estimated_labor_cost        NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS actual_labor_cost           NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS estimated_equipment_cost    NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS actual_equipment_cost       NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS estimated_other_cost        NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS actual_other_cost           NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS suggested_production_line_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS estimated_workers           INTEGER,
    ADD COLUMN IF NOT EXISTS assigned_supervisor_id      BIGINT,
    ADD COLUMN IF NOT EXISTS source_type                 VARCHAR(30),
    ADD COLUMN IF NOT EXISTS source_order_id             VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_order_item_id        VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_customer_name        VARCHAR(100),
    ADD COLUMN IF NOT EXISTS process_name                VARCHAR(200),
    ADD COLUMN IF NOT EXISTS batch_date                  DATE,
    ADD COLUMN IF NOT EXISTS ai_confidence               INTEGER,
    ADD COLUMN IF NOT EXISTS forecast_reason             VARCHAR(255),
    ADD COLUMN IF NOT EXISTS cr_value                    NUMERIC(5, 2),
    ADD COLUMN IF NOT EXISTS is_mixed_batch              BOOLEAN,
    ADD COLUMN IF NOT EXISTS mixed_batch_type            VARCHAR(30),
    ADD COLUMN IF NOT EXISTS related_orders              TEXT,
    ADD COLUMN IF NOT EXISTS current_probability         NUMERIC(5, 4),
    ADD COLUMN IF NOT EXISTS probability_updated_at      TIMESTAMP,
    ADD COLUMN IF NOT EXISTS is_force_inserted           BOOLEAN,
    ADD COLUMN IF NOT EXISTS requires_approval           BOOLEAN,
    ADD COLUMN IF NOT EXISTS approval_status             VARCHAR(20),
    ADD COLUMN IF NOT EXISTS approver_id                 BIGINT,
    ADD COLUMN IF NOT EXISTS approver_name               VARCHAR(50),
    ADD COLUMN IF NOT EXISTS approved_at                 TIMESTAMP,
    ADD COLUMN IF NOT EXISTS approval_comment            VARCHAR(500),
    ADD COLUMN IF NOT EXISTS force_insert_reason         VARCHAR(500),
    ADD COLUMN IF NOT EXISTS force_insert_by             BIGINT,
    ADD COLUMN IF NOT EXISTS force_inserted_at           TIMESTAMP;

-- ---------- return_orders (2 migrations) ----------
-- Source: entity/inventory/ReturnOrder.java
-- Bootstrap has return_type/return_date/reason/status/total_amount/created_by.
-- It has related_order_id whereas entity uses counterparty_id + source_order_id.
-- Already covered: with_goods (V20260514_03), vflag (V20260602_01).
ALTER TABLE return_orders
    ADD COLUMN IF NOT EXISTS counterparty_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS source_order_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS approved_by     BIGINT,
    ADD COLUMN IF NOT EXISTS approved_at     TIMESTAMP,
    ADD COLUMN IF NOT EXISTS remark          TEXT,
    ADD COLUMN IF NOT EXISTS version         BIGINT;

-- ---------- internal_transfers (1 migration) ----------
-- Source: entity/inventory/InternalTransfer.java
-- Bootstrap uses factory_id but entity has source_factory_id + target_factory_id.
-- Already covered: vflag (V20260602_01). Wide column gap follows.
ALTER TABLE internal_transfers
    ADD COLUMN IF NOT EXISTS transfer_type         VARCHAR(32),
    ADD COLUMN IF NOT EXISTS source_factory_id     VARCHAR(191),
    ADD COLUMN IF NOT EXISTS target_factory_id     VARCHAR(191),
    ADD COLUMN IF NOT EXISTS expected_arrival_date DATE,
    ADD COLUMN IF NOT EXISTS total_amount          NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS requested_by          BIGINT,
    ADD COLUMN IF NOT EXISTS requested_at          TIMESTAMP,
    ADD COLUMN IF NOT EXISTS approved_by           BIGINT,
    ADD COLUMN IF NOT EXISTS approved_at           TIMESTAMP,
    ADD COLUMN IF NOT EXISTS shipped_at            TIMESTAMP,
    ADD COLUMN IF NOT EXISTS received_at           TIMESTAMP,
    ADD COLUMN IF NOT EXISTS confirmed_at          TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reject_reason         VARCHAR(500),
    ADD COLUMN IF NOT EXISTS remark                TEXT,
    ADD COLUMN IF NOT EXISTS production_plan_id    VARCHAR(191);

-- ---------- wastage_records (1 migration) ----------
-- Source: entity/restaurant/WastageRecord.java
-- Bootstrap has item_id/item_type but entity uses raw_material_type_id +
-- material_batch_id. Bootstrap missing many entity columns.
-- Already covered: vflag (V20260602_01).
ALTER TABLE wastage_records
    ADD COLUMN IF NOT EXISTS wastage_number       VARCHAR(50),
    ADD COLUMN IF NOT EXISTS type                 VARCHAR(32),
    ADD COLUMN IF NOT EXISTS status               VARCHAR(32),
    ADD COLUMN IF NOT EXISTS raw_material_type_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS material_batch_id    VARCHAR(191),
    ADD COLUMN IF NOT EXISTS estimated_cost       NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS reported_by          BIGINT,
    ADD COLUMN IF NOT EXISTS approved_by          BIGINT,
    ADD COLUMN IF NOT EXISTS approved_at          TIMESTAMP;

-- ---------- factory_trigger_chains (2 migrations) ----------
-- Source: entity/config/FactoryTriggerChain.java
-- Bootstrap created chain_name/trigger_event/config but entity has chain_code/
-- event_type/steps/error_strategy/description. V20260421_03 references chain_code
-- — this is the critical gap.
-- Already covered: last_executed_at/last_execution_status/last_execution_error/
-- execution_count (V20260416_02).
ALTER TABLE factory_trigger_chains
    ADD COLUMN IF NOT EXISTS chain_code     VARCHAR(64),
    ADD COLUMN IF NOT EXISTS event_type     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS steps          JSONB,
    ADD COLUMN IF NOT EXISTS error_strategy VARCHAR(20),
    ADD COLUMN IF NOT EXISTS description    TEXT;

-- ---------- factory_scheduler_configs (1 migration) ----------
-- Source: entity/config/FactorySchedulerConfig.java
-- Bootstrap created scheduler_name/cron_expression/config but entity has
-- task_code/tool_or_method/params/description.
-- Already covered: last_executed_at/_status/_error/execution_count (V20260416_04).
ALTER TABLE factory_scheduler_configs
    ADD COLUMN IF NOT EXISTS task_code      VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tool_or_method VARCHAR(100),
    ADD COLUMN IF NOT EXISTS params         JSONB,
    ADD COLUMN IF NOT EXISTS description    TEXT;

-- ---------- finished_goods_batches (1 migration) ----------
-- Source: entity/inventory/FinishedGoodsBatch.java — bootstrap covers almost all.
-- Hibernate-only: version (@Version).
ALTER TABLE finished_goods_batches
    ADD COLUMN IF NOT EXISTS version BIGINT;

-- ---------- ar_ap_transactions (1 migration) ----------
-- Source: entity/finance/ArApTransaction.java
-- Bootstrap has thin 13-col table; entity has many more.
-- Already covered: approval_status/approved_by/approved_at (V20260426_01).
ALTER TABLE ar_ap_transactions
    ADD COLUMN IF NOT EXISTS transaction_number VARCHAR(50),
    ADD COLUMN IF NOT EXISTS counterparty_name  VARCHAR(200),
    ADD COLUMN IF NOT EXISTS sales_order_id     VARCHAR(191),
    ADD COLUMN IF NOT EXISTS purchase_order_id  VARCHAR(191),
    ADD COLUMN IF NOT EXISTS pos_order_sync_id  BIGINT,
    ADD COLUMN IF NOT EXISTS balance_after      NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS payment_method     VARCHAR(32),
    ADD COLUMN IF NOT EXISTS payment_reference  VARCHAR(100),
    ADD COLUMN IF NOT EXISTS due_date           DATE,
    ADD COLUMN IF NOT EXISTS operated_by        BIGINT,
    ADD COLUMN IF NOT EXISTS remark             VARCHAR(500);

-- ---------- notifications (1 migration) ----------
-- Source: entity/Notification.java — bootstrap covers core columns.
-- Already covered: target_role (V20260416_01).
-- Hibernate-only columns:
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS read_at    TIMESTAMP,
    ADD COLUMN IF NOT EXISTS source     VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_id  VARCHAR(100),
    ADD COLUMN IF NOT EXISTS action_url VARCHAR(500);

-- ---------- payroll_records (1 migration) ----------
-- Source: entity/PayrollRecord.java
-- Bootstrap has 8 columns; entity declares 25+. Entity uses worker_id (not user_id),
-- period_start/period_end (not pay_period). Most are Hibernate-only.
-- Already covered: vflag (V20260602_01).
ALTER TABLE payroll_records
    ADD COLUMN IF NOT EXISTS worker_id          BIGINT,
    ADD COLUMN IF NOT EXISTS worker_name        VARCHAR(50),
    ADD COLUMN IF NOT EXISTS period_start       DATE,
    ADD COLUMN IF NOT EXISTS period_end         DATE,
    ADD COLUMN IF NOT EXISTS period_type        VARCHAR(20),
    ADD COLUMN IF NOT EXISTS total_piece_count  INTEGER,
    ADD COLUMN IF NOT EXISTS piece_rate_wage    NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS piece_rule_id      BIGINT,
    ADD COLUMN IF NOT EXISTS overtime_wage      NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS overtime_hours     NUMERIC(8, 2),
    ADD COLUMN IF NOT EXISTS bonus_amount       NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS deduction_amount   NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS total_wage         NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS average_efficiency NUMERIC(8, 2),
    ADD COLUMN IF NOT EXISTS total_work_hours   NUMERIC(8, 2),
    ADD COLUMN IF NOT EXISTS efficiency_rating  VARCHAR(1),
    ADD COLUMN IF NOT EXISTS status             VARCHAR(20),
    ADD COLUMN IF NOT EXISTS approved_by        BIGINT,
    ADD COLUMN IF NOT EXISTS approved_at        TIMESTAMP,
    ADD COLUMN IF NOT EXISTS paid_at            TIMESTAMP;

-- =============================================================================
-- End of V20260416_00 bootstrap completeness migration.
-- =============================================================================
