-- =============================================================================
-- Bootstrap migration: tables historically created by Hibernate ddl-auto=update.
--
-- WHY THIS EXISTS:
-- Prior to PR #881 (d61a53d47), application-pg.properties had:
--   spring.jpa.defer-datasource-initialization=true
--   spring.sql.init.mode=always
-- This wired Spring Boot's DataSourceInitializationDependencyConfigurer to
-- create a flyway -> primaryEntityManagerFactory depends-on edge, producing a
-- BeanCreation circular-dep error at context init in pg profile.
--
-- PR #881 set defer=false + sql.init.mode=never, breaking the cycle. But that
-- exposes the latent issue: ~33 tables exist in prod only because Hibernate's
-- ddl-auto=update populated the schema lazily over time. They have no CREATE
-- TABLE migration anywhere in db/flyway/. Many downstream flyway scripts
-- (V20260416_01__notifications_add_target_role.sql, ..., V20260607_*) assume
-- these tables exist and ALTER them — which crashes Flyway on a fresh DB
-- (e.g. CI's e2e-pr-gate) with:
--   ERROR: relation "notifications" does not exist
--
-- WHAT THIS DOES:
-- Creates the missing 33 tables with CREATE TABLE IF NOT EXISTS so:
--   - Prod (where the tables already exist) is a no-op
--   - CI / fresh dev DBs get the minimum schema needed for downstream ALTERs
--     to succeed. Hibernate ddl-auto=update fills in any remaining columns
--     after Flyway completes.
--
-- DESIGN NOTES:
--   1. Each CREATE uses IF NOT EXISTS for idempotency.
--   2. Includes id + factory_id + BaseEntity audit (created_at/updated_at/
--      deleted_at) for every table.
--   3. Adds specific columns that downstream ALTERs require to exist (see
--      sister-table grep audit in PR description).
--   4. NO foreign key constraints — Hibernate adds them via ddl-auto=update
--      after Flyway succeeds, avoiding "referenced table does not exist"
--      ordering problems in CI.
--   5. NO indexes — existing flyway CREATE INDEX scripts use IF NOT EXISTS.
--   6. Id types match the @Entity definitions:
--        - VARCHAR(191) for UUID-string ids (most business entities)
--        - BIGSERIAL/BIGINT for Long-typed Hibernate identity ids
--        - INTEGER (SERIAL) for the few Integer-typed ids (equipment_alerts).
--
-- See PR description for the full inventory of ALTERs that motivated each
-- column inclusion.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Reference / lookup tables
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ai_intent_configs (
    id              VARCHAR(50)  PRIMARY KEY,
    factory_id      VARCHAR(50),
    intent_code     VARCHAR(100) NOT NULL,
    intent_name     VARCHAR(200),
    intent_category VARCHAR(50),
    tool_name       VARCHAR(100),
    keywords        TEXT,
    is_active       BOOLEAN      DEFAULT TRUE,
    sensitivity_level VARCHAR(20),
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    -- Required for ON CONFLICT (intent_code) in V20260513_02 / V20260516_02 /
    -- V20260516_04 / V20260606_06 etc. Matches @Table uniqueConstraints =
    -- {@UniqueConstraint(columnNames = {"intent_code"})} on AIIntentConfig.
    CONSTRAINT uk_ai_intent_configs_intent_code UNIQUE (intent_code)
);

CREATE TABLE IF NOT EXISTS ai_learned_expressions (
    id              VARCHAR(50)  PRIMARY KEY,
    factory_id      VARCHAR(50),
    intent_code     VARCHAR(100),
    expression      TEXT,
    expression_hash VARCHAR(64),
    source_type     VARCHAR(20),
    confidence      NUMERIC(5,4) DEFAULT 0,
    hit_count       INTEGER      DEFAULT 0,
    last_hit_at     TIMESTAMP,
    is_verified     BOOLEAN      DEFAULT FALSE,
    is_active       BOOLEAN      DEFAULT TRUE,
    proposal_count  INTEGER      DEFAULT 1,
    last_proposed_at TIMESTAMP,
    embedding_vector BYTEA,
    embedding_model VARCHAR(50)  DEFAULT 'gte-base-zh',
    embedding_created_at TIMESTAMP,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS intent_match_records (
    id              VARCHAR(50)  PRIMARY KEY,
    factory_id      VARCHAR(50),
    user_query      TEXT,
    matched_intent  VARCHAR(100),
    match_score     NUMERIC(5,4),
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- Notifications
-- -----------------------------------------------------------------------------
-- V20260416_01__notifications_add_target_role.sql does:
--   ALTER TABLE notifications ADD COLUMN IF NOT EXISTS target_role VARCHAR(50);
-- so target_role is intentionally omitted here.

CREATE TABLE IF NOT EXISTS notifications (
    id              BIGSERIAL    PRIMARY KEY,
    factory_id      VARCHAR(50),
    user_id         BIGINT,
    title           VARCHAR(255),
    content         TEXT,
    type            VARCHAR(50),
    is_read         BOOLEAN      DEFAULT FALSE,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- Drools rules
-- -----------------------------------------------------------------------------
-- V20260417_02 does: ALTER COLUMN enabled SET NOT NULL/DEFAULT TRUE.
-- V20260427_01 / _02 do: INSERT INTO drools_rules (id, factory_id, rule_group,
--   rule_name, rule_content, rule_description, enabled, priority, version,
--   created_at, updated_at) — so all those columns must exist.

CREATE TABLE IF NOT EXISTS drools_rules (
    id                  VARCHAR(50)  PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    rule_group          VARCHAR(50)  NOT NULL,
    rule_name           VARCHAR(100) NOT NULL,
    rule_description    TEXT,
    rule_content        TEXT         NOT NULL,
    decision_table      BYTEA,
    decision_table_type VARCHAR(20),
    version             INTEGER      DEFAULT 1,
    enabled             BOOLEAN      DEFAULT TRUE,
    priority            INTEGER      DEFAULT 0,
    created_by          BIGINT,
    updated_by          BIGINT,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- BOM
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS bom_items (
    id              BIGSERIAL    PRIMARY KEY,
    factory_id      VARCHAR(50),
    product_type_id VARCHAR(191),
    material_type_id VARCHAR(191),
    quantity        NUMERIC(15,4),
    unit            VARCHAR(20),
    unit_price      NUMERIC(15,4),
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- SmartBI
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS smart_bi_datasource (
    id              VARCHAR(50)  PRIMARY KEY,
    factory_id      VARCHAR(50),
    name            VARCHAR(200),
    type            VARCHAR(50),
    config          TEXT,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- Customers
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS customers (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(255) NOT NULL,
    code            VARCHAR(50),
    customer_code   VARCHAR(50),
    name            VARCHAR(255),
    type            VARCHAR(50),
    business_type   VARCHAR(50),
    customer_type   VARCHAR(50),
    industry        VARCHAR(100),
    contact_name    VARCHAR(100),
    contact_person  VARCHAR(100),
    contact_phone   VARCHAR(20),
    phone           VARCHAR(20),
    contact_email   VARCHAR(100),
    email           VARCHAR(100),
    shipping_address VARCHAR(255),
    billing_address VARCHAR(255),
    tax_number      VARCHAR(50),
    business_license VARCHAR(100),
    payment_terms   VARCHAR(200),
    credit_limit    NUMERIC(12,2),
    current_balance NUMERIC(12,2) DEFAULT 0,
    rating          INTEGER,
    rating_notes    TEXT,
    bank_name       VARCHAR(200),
    bank_account    VARCHAR(100),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    notes           TEXT,
    created_by      BIGINT,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    -- Matches @Table uniqueConstraints on Customer. Also enables potential
    -- ON CONFLICT (factory_id, code) DO NOTHING patterns.
    CONSTRAINT uk_customers_factory_code UNIQUE (factory_id, code)
);

-- -----------------------------------------------------------------------------
-- Suppliers
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS suppliers (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(255) NOT NULL,
    code            VARCHAR(50),
    supplier_code   VARCHAR(50),
    name            VARCHAR(255),
    contact_name    VARCHAR(100),
    contact_person  VARCHAR(100),
    contact_phone   VARCHAR(20),
    phone           VARCHAR(20),
    contact_email   VARCHAR(100),
    email           VARCHAR(100),
    address         VARCHAR(255),
    business_license VARCHAR(100),
    tax_number      VARCHAR(50),
    bank_name       VARCHAR(100),
    bank_account    VARCHAR(50),
    supplied_materials TEXT,
    payment_terms   VARCHAR(200),
    delivery_days   INTEGER,
    business_type   VARCHAR(50),
    credit_level    VARCHAR(20),
    delivery_area   VARCHAR(200),
    credit_limit    NUMERIC(12,2),
    current_balance NUMERIC(12,2) DEFAULT 0,
    rating          INTEGER,
    rating_notes    TEXT,
    quality_certificates TEXT,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    notes           TEXT,
    created_by      BIGINT,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    -- Matches @Table uniqueConstraints on Supplier.
    CONSTRAINT uk_suppliers_factory_code UNIQUE (factory_id, code)
);

-- -----------------------------------------------------------------------------
-- Raw material types
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS raw_material_types (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(255) NOT NULL,
    code            VARCHAR(50),
    name            VARCHAR(255),
    category        VARCHAR(50),
    unit            VARCHAR(20),
    unit_price      NUMERIC(10,2),
    storage_type    VARCHAR(20),
    shelf_life_days INTEGER,
    min_stock       NUMERIC(10,2),
    max_stock       NUMERIC(10,2),
    moving_avg_price NUMERIC(12,4),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    notes           TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    -- Required for ON CONFLICT (factory_id, code) in V20260603_01
    -- (F006 seed) + matches @Table uniqueConstraints on RawMaterialType.
    CONSTRAINT uk_raw_material_types_factory_code UNIQUE (factory_id, code)
);

-- -----------------------------------------------------------------------------
-- Material batches
-- -----------------------------------------------------------------------------
-- V20260510_03 does: ALTER COLUMN warehouse_id SET NOT NULL.
-- So warehouse_id must already exist on the table.

CREATE TABLE IF NOT EXISTS material_batches (
    id                  VARCHAR(191) PRIMARY KEY,
    factory_id          VARCHAR(255) NOT NULL,
    batch_number        VARCHAR(50),
    material_type_id    VARCHAR(255),
    supplier_id         VARCHAR(255),
    inbound_date        DATE,
    production_date     DATE,
    purchase_date       DATE,
    expire_date         DATE,
    warehouse_id        VARCHAR(64),
    version             BIGINT,
    receipt_quantity    NUMERIC(10,2),
    quantity_unit       VARCHAR(20),
    weight_per_unit     NUMERIC(10,3),
    used_quantity       NUMERIC(10,2) DEFAULT 0,
    reserved_quantity   NUMERIC(10,2) DEFAULT 0,
    unit_price          NUMERIC(10,2),
    status              VARCHAR(50),
    storage_location    VARCHAR(100),
    quality_certificate VARCHAR(100),
    notes               TEXT,
    inbound_type        VARCHAR(30),
    source_doc_type     VARCHAR(32),
    source_doc_id       VARCHAR(64),
    created_by          BIGINT,
    last_used_at        TIMESTAMP,
    custom_fields       JSONB,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- Production plans
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS production_plans (
    id                  VARCHAR(191) PRIMARY KEY,
    factory_id          VARCHAR(255) NOT NULL,
    plan_number         VARCHAR(50),
    product_type_id     VARCHAR(191),
    planned_quantity    NUMERIC(10,2),
    actual_quantity     NUMERIC(10,2),
    planned_date        DATE,
    start_time          TIMESTAMP,
    end_time            TIMESTAMP,
    status              VARCHAR(50),
    notes               TEXT,
    created_by          BIGINT,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- Quality inspections
-- -----------------------------------------------------------------------------
-- V20260428_04 / _05 do: UPDATE quality_inspections SET result = 'PASS' WHERE
-- result = 'PASSED'. So 'result' column must exist.

CREATE TABLE IF NOT EXISTS quality_inspections (
    id                  VARCHAR(191) PRIMARY KEY,
    factory_id          VARCHAR(255) NOT NULL,
    production_batch_id BIGINT,
    inspector_id        BIGINT,
    inspection_date     DATE,
    sample_size         NUMERIC(10,2),
    pass_count          NUMERIC(10,2),
    fail_count          NUMERIC(10,2),
    pass_rate           NUMERIC(5,2),
    result              VARCHAR(20),
    inspection_mode     VARCHAR(20),
    notes               TEXT,
    custom_fields       JSONB,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- Equipment domain
-- -----------------------------------------------------------------------------
-- V20260420_01 does: ALTER TABLE factory_equipment ALTER COLUMN version SET NOT NULL.
-- V20260420_02 does same for equipment_maintenance/equipment_alerts/batch_equipment_usage.
-- So 'version' must already exist (with NULL allowed so existing rows backfill).

CREATE TABLE IF NOT EXISTS factory_equipment (
    id                  BIGSERIAL    PRIMARY KEY,
    factory_id          VARCHAR(255) NOT NULL,
    code                VARCHAR(50),
    equipment_code      VARCHAR(50),
    equipment_name      VARCHAR(191),
    type                VARCHAR(50),
    model               VARCHAR(100),
    manufacturer        VARCHAR(100),
    purchase_date       DATE,
    purchase_price      NUMERIC(12,2),
    depreciation_years  INTEGER,
    hourly_cost         NUMERIC(10,2),
    power_consumption_kw NUMERIC(10,2),
    status              VARCHAR(20)  NOT NULL DEFAULT 'idle',
    location            VARCHAR(100),
    total_running_hours INTEGER      DEFAULT 0,
    maintenance_interval_hours INTEGER,
    last_maintenance_date DATE,
    next_maintenance_date DATE,
    warranty_expiry_date DATE,
    serial_number       VARCHAR(100),
    created_by          BIGINT,
    operator_id         BIGINT,
    notes               TEXT,
    iot_device_code     VARCHAR(100),
    device_category     VARCHAR(20),
    scale_protocol_id   VARCHAR(50),
    scale_brand_model_id VARCHAR(50),
    mqtt_topic          VARCHAR(255),
    scale_connection_params TEXT,
    last_weight_reading NUMERIC(12,4),
    last_weight_time    TIMESTAMP,
    last_data_received  TIMESTAMP,
    version             INTEGER,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE TABLE IF NOT EXISTS equipment_alerts (
    id                  SERIAL       PRIMARY KEY,
    version             INTEGER,
    factory_id          VARCHAR(50)  NOT NULL,
    equipment_id        BIGINT       NOT NULL,
    alert_type          VARCHAR(50),
    level               VARCHAR(20),
    status              VARCHAR(20),
    message             TEXT,
    details             TEXT,
    triggered_at        TIMESTAMP,
    acknowledged_at     TIMESTAMP,
    acknowledged_by     BIGINT,
    acknowledged_by_name VARCHAR(100),
    resolved_at         TIMESTAMP,
    resolved_by         BIGINT,
    resolved_by_name    VARCHAR(100),
    resolution_notes    TEXT,
    ignored_at          TIMESTAMP,
    ignored_by          BIGINT,
    ignored_by_name     VARCHAR(100),
    ignore_reason       TEXT,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE TABLE IF NOT EXISTS equipment_maintenance (
    id                  BIGSERIAL    PRIMARY KEY,
    equipment_id        BIGINT       NOT NULL,
    maintenance_type    VARCHAR(50),
    maintenance_date    DATE,
    start_time          TIMESTAMP,
    end_time            TIMESTAMP,
    description         TEXT,
    cost                NUMERIC(10,2),
    performed_by        VARCHAR(100),
    next_maintenance_date DATE,
    notes               TEXT,
    version             INTEGER,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch_equipment_usage (
    id                  BIGSERIAL    PRIMARY KEY,
    batch_id            BIGINT       NOT NULL,
    equipment_id        BIGINT       NOT NULL,
    start_time          TIMESTAMP    NOT NULL,
    end_time            TIMESTAMP,
    usage_hours         NUMERIC(10,2),
    power_consumption   NUMERIC(10,2),
    equipment_cost      NUMERIC(10,2),
    version             INTEGER,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- Factory config + automation tables
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS factory_module_configs (
    id              VARCHAR(50)  PRIMARY KEY,
    factory_id      VARCHAR(50)  NOT NULL,
    module_id       VARCHAR(50),
    enabled         BOOLEAN      DEFAULT TRUE,
    config          JSONB,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS factory_scheduler_configs (
    id              VARCHAR(50)  PRIMARY KEY,
    factory_id      VARCHAR(50)  NOT NULL,
    scheduler_name  VARCHAR(100),
    cron_expression VARCHAR(50),
    enabled         BOOLEAN      DEFAULT TRUE,
    config          JSONB,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS factory_trigger_chains (
    id              VARCHAR(50)  PRIMARY KEY,
    factory_id      VARCHAR(50)  NOT NULL,
    chain_name      VARCHAR(100),
    trigger_event   VARCHAR(100),
    config          JSONB,
    enabled         BOOLEAN      DEFAULT TRUE,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- Sales / Purchase / Order domain
-- -----------------------------------------------------------------------------
-- V20260416_03 / V20260607_04 do: DROP CONSTRAINT IF EXISTS ck_po_status; ADD
-- CONSTRAINT ck_po_status CHECK (status IN (...)).  Need 'status' column.

CREATE TABLE IF NOT EXISTS purchase_orders (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(191) NOT NULL,
    order_number    VARCHAR(50),
    supplier_id     VARCHAR(191),
    purchase_type   VARCHAR(32)  DEFAULT 'DIRECT',
    order_date      DATE,
    expected_delivery_date DATE,
    total_amount    NUMERIC(15,2) DEFAULT 0,
    tax_amount      NUMERIC(15,2) DEFAULT 0,
    status          VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    created_by      BIGINT,
    approved_by     BIGINT,
    approved_at     TIMESTAMP,
    remark           TEXT,
    -- @Version on PurchaseOrder entity; V20260603_01 seed INSERT references it.
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    -- Required for ON CONFLICT (factory_id, order_number) in V20260603_01
    -- (F006 seed). Mirror of the natural business key.
    CONSTRAINT uk_purchase_orders_factory_order UNIQUE (factory_id, order_number)
);

CREATE TABLE IF NOT EXISTS sales_orders (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(191) NOT NULL,
    order_number    VARCHAR(50),
    customer_id     VARCHAR(191),
    order_date      DATE,
    required_delivery_date DATE,
    delivery_address TEXT,
    total_amount    NUMERIC(15,2) DEFAULT 0,
    discount_amount NUMERIC(15,2) DEFAULT 0,
    tax_amount      NUMERIC(15,2) DEFAULT 0,
    status          VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    created_by      BIGINT,
    confirmed_at    TIMESTAMP,
    remark          TEXT,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sales_order_items (
    id              BIGSERIAL    PRIMARY KEY,
    sales_order_id  VARCHAR(191) NOT NULL,
    product_type_id VARCHAR(191),
    product_name    VARCHAR(200),
    quantity        NUMERIC(15,4),
    unit            VARCHAR(20),
    unit_price      NUMERIC(15,4),
    discount_rate   NUMERIC(5,2) DEFAULT 0,
    delivered_quantity NUMERIC(15,4) DEFAULT 0,
    remark          VARCHAR(500),
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sales_delivery_items (
    id              BIGSERIAL    PRIMARY KEY,
    delivery_record_id VARCHAR(191) NOT NULL,
    product_type_id VARCHAR(191),
    product_name    VARCHAR(200),
    delivered_quantity NUMERIC(15,4),
    unit            VARCHAR(20),
    unit_price      NUMERIC(15,4),
    finished_goods_batch_id VARCHAR(191),
    remark          VARCHAR(500),
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

-- V20260510_04 does: ALTER COLUMN warehouse_id SET NOT NULL.  warehouse_id
-- must already exist as nullable so existing rows can be backfilled.

CREATE TABLE IF NOT EXISTS finished_goods_batches (
    id                  VARCHAR(191) PRIMARY KEY,
    factory_id          VARCHAR(191) NOT NULL,
    batch_number        VARCHAR(50),
    product_type_id     VARCHAR(191),
    product_name        VARCHAR(200),
    produced_quantity   NUMERIC(15,4),
    shipped_quantity    NUMERIC(15,4) DEFAULT 0,
    reserved_quantity   NUMERIC(15,4) DEFAULT 0,
    unit                VARCHAR(20),
    unit_price          NUMERIC(15,4),
    production_date     DATE,
    expire_date         DATE,
    storage_location    VARCHAR(100),
    production_plan_id  VARCHAR(191),
    warehouse_id        VARCHAR(64),
    status              VARCHAR(32)  DEFAULT 'AVAILABLE',
    created_by          BIGINT,
    remark              TEXT,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE TABLE IF NOT EXISTS shipment_records (
    id                  VARCHAR(191) PRIMARY KEY,
    factory_id          VARCHAR(255) NOT NULL,
    shipment_number     VARCHAR(50),
    customer_id         VARCHAR(191),
    order_number        VARCHAR(100),
    batch_number        VARCHAR(100),
    product_name        VARCHAR(255),
    quantity            NUMERIC(10,2),
    unit                VARCHAR(20),
    unit_price          NUMERIC(10,2),
    total_amount        NUMERIC(10,2),
    shipment_date       DATE,
    delivery_address    VARCHAR(255),
    logistics_company   VARCHAR(100),
    tracking_number     VARCHAR(100),
    status              VARCHAR(20)  DEFAULT 'pending',
    recorded_by         BIGINT,
    notes               TEXT,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE TABLE IF NOT EXISTS return_orders (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(191) NOT NULL,
    return_number   VARCHAR(50),
    related_order_id VARCHAR(191),
    return_type     VARCHAR(32),
    return_date     DATE,
    reason          TEXT,
    status          VARCHAR(32)  DEFAULT 'DRAFT',
    total_amount    NUMERIC(15,2) DEFAULT 0,
    created_by      BIGINT,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS internal_transfers (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(191) NOT NULL,
    transfer_number VARCHAR(50),
    source_warehouse_id VARCHAR(64),
    target_warehouse_id VARCHAR(64),
    transfer_date   DATE,
    status          VARCHAR(32)  DEFAULT 'DRAFT',
    notes           TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS wastage_records (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(191) NOT NULL,
    wastage_date    DATE,
    item_id         VARCHAR(191),
    item_type       VARCHAR(50),
    quantity        NUMERIC(15,4),
    unit            VARCHAR(20),
    reason          VARCHAR(255),
    notes           TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- Finance / AR-AP / Invoices
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ar_ap_transactions (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(191) NOT NULL,
    transaction_type VARCHAR(32),
    counterparty_id VARCHAR(191),
    counterparty_type VARCHAR(32),
    related_order_id VARCHAR(191),
    amount          NUMERIC(15,2),
    transaction_date DATE,
    status          VARCHAR(32)  DEFAULT 'PENDING',
    notes           TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS invoice_records (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(191) NOT NULL,
    invoice_number  VARCHAR(64),
    invoice_type    VARCHAR(32),
    related_order_id VARCHAR(191),
    customer_id     VARCHAR(191),
    issue_date      DATE,
    total_amount    NUMERIC(15,2),
    tax_amount      NUMERIC(15,2),
    status          VARCHAR(32)  DEFAULT 'PENDING',
    notes           TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- HR / Time tracking
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS payroll_records (
    id              BIGSERIAL    PRIMARY KEY,
    factory_id      VARCHAR(50),
    user_id         BIGINT,
    pay_period      VARCHAR(20),
    base_salary     NUMERIC(12,2),
    total_amount    NUMERIC(12,2),
    notes           TEXT,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS time_clock_records (
    id                  BIGSERIAL    PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    user_id             BIGINT       NOT NULL,
    clock_date          DATE,
    clock_in_time       TIMESTAMP,
    clock_out_time      TIMESTAMP,
    break_start_time    TIMESTAMP,
    break_end_time      TIMESTAMP,
    work_duration       INTEGER,
    break_duration      INTEGER,
    status              VARCHAR(20),
    attendance_status   VARCHAR(30),
    location            VARCHAR(255),
    device              VARCHAR(255),
    latitude            DOUBLE PRECISION,
    longitude           DOUBLE PRECISION,
    remarks             VARCHAR(500),
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- =============================================================================
-- End of V20260415_99 bootstrap.
-- =============================================================================
