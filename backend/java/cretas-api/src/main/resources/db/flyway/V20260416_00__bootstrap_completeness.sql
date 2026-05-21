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
-- BOOTSTRAP-TABLE COLUMN GAP FIXES
-- =============================================================================
-- Some V20260415_99 bootstrap tables also lack Hibernate-supplied columns that
-- downstream Flyway migrations reference. PR #117's per-migration DO $$ IF EXISTS
-- guards were a stopgap; this section fills the column gap directly so guarded
-- migrations can run cleanly (and we can simplify by removing the guards).
--
-- Pattern: ALTER TABLE … ADD COLUMN IF NOT EXISTS — idempotent on prod/test
-- where Hibernate already supplied these columns, while populating fresh CI DBs.

-- ---------- invoice_records (referenced by V20260419_01) ----------
-- Source: entity/finance/InvoiceRecord.java:56 + :128
-- V20260415_99 created invoice_records (lines 717-733 of bootstrap) WITHOUT
-- these two columns; Hibernate ddl-auto=update supplied them in prod/test.
ALTER TABLE invoice_records
    ADD COLUMN IF NOT EXISTS sales_order_id VARCHAR(191);

ALTER TABLE invoice_records
    ADD COLUMN IF NOT EXISTS requested_at TIMESTAMP;

-- =============================================================================
-- End of V20260416_00 bootstrap completeness migration.
-- =============================================================================
