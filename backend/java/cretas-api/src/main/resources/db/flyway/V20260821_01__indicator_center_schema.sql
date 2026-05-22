-- =====================================================================
-- Food Industry Indicator Center — Schema (Phase 1 Sprint 1 Day 1)
-- Spec: docs/superpowers/specs/2026-05-20-food-industry-indicator-center-design.md
--
-- 5 tables:
--   indicators                   — 指标定义
--   indicator_tree_nodes         — 父子树关系
--   indicator_thresholds         — GREEN/YELLOW/RED 阈值
--   indicator_computations       — 计算策略 (FORMULA / PYTHON_ENDPOINT / JPA_QUERY)
--   indicator_versions           — 历史快照 (append-only audit)
--
-- 设计约定:
--   - VARCHAR(191) PK (Cretas convention)
--   - 软删除三元组 created_at / updated_at / deleted_at
--   - indicator_versions 不带 deleted_at 过滤 (append-only)，但保留列以维持 BaseEntity 契约
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. indicators — 指标定义
-- ---------------------------------------------------------------------
CREATE TABLE indicators (
    id                  VARCHAR(191)   PRIMARY KEY,
    factory_id          VARCHAR(50)    NOT NULL,
    code                VARCHAR(100)   NOT NULL,
    name                VARCHAR(200)   NOT NULL,
    description         TEXT,
    category            VARCHAR(30)    NOT NULL,                              -- FACTORY / RESTAURANT / QUALITY
    unit                VARCHAR(30),
    is_active           BOOLEAN        NOT NULL DEFAULT TRUE,
    compute_strategy    VARCHAR(30)    NOT NULL DEFAULT 'CACHED',             -- REALTIME / CACHED / PRECOMPUTED
    cache_ttl_seconds   INTEGER                 DEFAULT 3600,
    last_value          NUMERIC(18,4),
    last_computed_at    TIMESTAMP,
    display_order       INTEGER,
    config              JSONB                   DEFAULT '{}'::jsonb,
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE UNIQUE INDEX idx_ind_factory_code ON indicators (factory_id, code);
CREATE INDEX        idx_ind_category     ON indicators (factory_id, category);

-- ---------------------------------------------------------------------
-- 2. indicator_tree_nodes — 父子关系 + 权重 + 物化路径
-- ---------------------------------------------------------------------
CREATE TABLE indicator_tree_nodes (
    id                  VARCHAR(191)   PRIMARY KEY,
    factory_id          VARCHAR(50)    NOT NULL,
    indicator_id        VARCHAR(191)   NOT NULL,
    parent_id           VARCHAR(191),                                          -- NULL = root
    weight              NUMERIC(10,4),
    depth               INTEGER        NOT NULL DEFAULT 0,
    materialized_path   VARCHAR(1000),
    display_order       INTEGER,
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE INDEX idx_itn_factory   ON indicator_tree_nodes (factory_id);
CREATE INDEX idx_itn_indicator ON indicator_tree_nodes (indicator_id);
CREATE INDEX idx_itn_parent    ON indicator_tree_nodes (parent_id);

-- ---------------------------------------------------------------------
-- 3. indicator_thresholds — GREEN/YELLOW/RED 三色预警
-- ---------------------------------------------------------------------
CREATE TABLE indicator_thresholds (
    id                       VARCHAR(191)   PRIMARY KEY,
    factory_id               VARCHAR(50)    NOT NULL,
    indicator_id             VARCHAR(191)   NOT NULL,
    alert_level              VARCHAR(20)    NOT NULL,                          -- GREEN / YELLOW / RED
    operator                 VARCHAR(10)    NOT NULL,                          -- GT / GTE / LT / LTE / EQ / BETWEEN
    threshold_value          NUMERIC(18,4)  NOT NULL,
    threshold_value_upper    NUMERIC(18,4),                                    -- BETWEEN 用
    is_active                BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at               TIMESTAMP
);

CREATE INDEX idx_ith_factory   ON indicator_thresholds (factory_id);
CREATE INDEX idx_ith_indicator ON indicator_thresholds (indicator_id);
CREATE INDEX idx_ith_level     ON indicator_thresholds (indicator_id, alert_level);

-- ---------------------------------------------------------------------
-- 4. indicator_computations — 计算策略
-- ---------------------------------------------------------------------
CREATE TABLE indicator_computations (
    id              VARCHAR(191)   PRIMARY KEY,
    indicator_id    VARCHAR(191)   NOT NULL,
    compute_type    VARCHAR(30)    NOT NULL,                                   -- FORMULA / PYTHON_ENDPOINT / JPA_QUERY
    compute_source  TEXT           NOT NULL,                                   -- SpEL / endpoint path / named query
    params          JSONB                   DEFAULT '{}'::jsonb,
    is_active       BOOLEAN        NOT NULL DEFAULT TRUE,
    priority        INTEGER        NOT NULL DEFAULT 1,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_ic_indicator ON indicator_computations (indicator_id);
CREATE INDEX idx_ic_type      ON indicator_computations (compute_type);

-- ---------------------------------------------------------------------
-- 5. indicator_versions — 历史快照 (append-only)
-- ---------------------------------------------------------------------
CREATE TABLE indicator_versions (
    id                VARCHAR(191)   PRIMARY KEY,
    factory_id        VARCHAR(50)    NOT NULL,
    indicator_id      VARCHAR(191)   NOT NULL,
    period_start      DATE           NOT NULL,
    period_end        DATE           NOT NULL,
    value             NUMERIC(18,4)  NOT NULL,
    computed_at       TIMESTAMP      NOT NULL,
    alert_level       VARCHAR(20),
    compute_source    VARCHAR(500),
    created_at        TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMP                                                -- 保留列以维持 BaseEntity 契约 (永不写入)
);

CREATE INDEX idx_iv_factory_indicator ON indicator_versions (factory_id, indicator_id);
CREATE INDEX idx_iv_period            ON indicator_versions (indicator_id, period_start, period_end);
CREATE INDEX idx_iv_computed_at       ON indicator_versions (indicator_id, computed_at);
