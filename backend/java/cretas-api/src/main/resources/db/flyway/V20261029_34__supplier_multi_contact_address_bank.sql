-- V20261029_34__supplier_multi_contact_address_bank.sql
--
-- 客户反馈 (六膳门 F006 / 刘山门 LIUSHANMEN, Sheet 第 7 行):
--   一个供应商要能维护「多个联系人 / 多个地址 / 银行账户」, 外加一个「简称」让下拉里好认。
--
-- suppliers 表现状 (prod cretas_prod_db 实测 2026-07-30, 60 行 / 11 个 factory):
--   contact_person 50 行有值 / phone 50 / address 56 / bank_account 6 / bank_name 2 / email 8。
--   历史双写字段 contact_name(6) 与 contact_person(50)、contact_phone(12) 与 phone(50)
--   实测**从不同时有值且不一致** (both_diff = 0, ph_diff = 0), 所以回填按
--   COALESCE(新字段, 老字段) 取一个即可, 不会丢数据。
--
-- ┌── 兼容策略 (最重要, 决定了本迁移不删任何列) ─────────────────────────────
-- │ suppliers 上的单值列 (contact_person / phone / email / address /
-- │ bank_name / bank_account …) **保留**, 并由服务层继续维护成「主联系人 /
-- │ 主地址 / 主账户的镜像」。
-- │
-- │ 理由: 这些列的读者遍布采购单、收货、对账、导入导出、AI Tool、脱敏导出、
-- │ SupplierProfileValidator (创建/更新时**强制**要求 contactPerson+phone+address
-- │ 非空)。把它们改成计算值或删掉, 等于一次性推翻几十个读点。保留镜像后,
-- │ 所有旧读者零改动继续正确 —— 它们看到的就是主联系人。
-- └──────────────────────────────────────────────────────────────────────────
--
-- ⚠️ to_regclass 守卫 (沿用 V20260822_04 / V20261029_33 的做法): suppliers 是
--   Hibernate JPA entity, 全新 CI DB 上 Flyway 先于 ddl-auto 跑时表还不存在,
--   裸 ALTER / 裸 INSERT..SELECT 会报 "relation does not exist" 阻断启动。
-- ⚠️ 幂等: CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS /
--   CREATE INDEX IF NOT EXISTS, 回填用确定性 UUID + WHERE NOT EXISTS, 可重复执行。
-- ⚠️ 主键是 VARCHAR(36) 应用生成的 UUID, **没有 BIGSERIAL**, 所以不存在
--   「表授权了但 sequence 没授权导致 INSERT permission denied」那个坑。
-- ⚠️ 授权: 本库 (cretas_db / cretas_prod_db) 的 Flyway 跑在 spring.datasource 上,
--   即 cretas_user 自己 —— 新建表的 owner 就是 cretas_user, 本不需要 GRANT
--   (实测 prod: suppliers / purchase_orders / material_packaging_specs 的
--   tableowner 均为 cretas_user)。下面仍显式 GRANT 一次作为防御, 并用
--   pg_roles 存在性守卫, 免得在没有该角色的环境 (本地/CI 用别的用户) 上报错。

DO $$
BEGIN
    ------------------------------------------------------------------ 0) 简称
    IF to_regclass('public.suppliers') IS NOT NULL THEN
        ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS short_name VARCHAR(50);
        COMMENT ON COLUMN suppliers.short_name
            IS '供应商简称 (客户反馈: 下拉里好认)。可空; 为空时 UI 回退显示全称。工厂内不区分大小写唯一 —— 两家简称一样等于没解决"好认"。';

        -- 简称在工厂内唯一 (忽略大小写)。NULL 不参与 —— 存量 60 行回填后全是 NULL,
        -- 所以本索引在 prod 建立时必然无冲突 (已 BEGIN/ROLLBACK 干跑验证)。
        CREATE UNIQUE INDEX IF NOT EXISTS uq_suppliers_short_name
            ON suppliers(factory_id, lower(short_name))
            WHERE deleted_at IS NULL AND short_name IS NOT NULL;
    END IF;
END $$;

------------------------------------------------------------------ 1) 多联系人
CREATE TABLE IF NOT EXISTS supplier_contacts (
  id            VARCHAR(36)  PRIMARY KEY,
  factory_id    VARCHAR(255) NOT NULL,
  supplier_id   VARCHAR(191) NOT NULL,
  name          VARCHAR(100) NOT NULL,
  contact_type  VARCHAR(32)  NOT NULL DEFAULT 'OTHER',
  phone         VARCHAR(40),
  email         VARCHAR(100),
  position      VARCHAR(100),
  is_primary    BOOLEAN      NOT NULL DEFAULT FALSE,
  sort_order    INTEGER      NOT NULL DEFAULT 0,
  notes         TEXT,
  version       BIGINT       NOT NULL DEFAULT 0,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at    TIMESTAMP,
  CONSTRAINT ck_supplier_contact_type CHECK (contact_type IN (
      'OWNER', 'SALES', 'FINANCE', 'LOGISTICS', 'AFTER_SALES', 'OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_supplier_contacts_supplier
  ON supplier_contacts(factory_id, supplier_id, sort_order);

-- 一个供应商最多一个主联系人 (软删/停用行不参与)
CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_contacts_primary
  ON supplier_contacts(supplier_id)
  WHERE deleted_at IS NULL AND is_primary = TRUE;

------------------------------------------------------------------ 2) 多地址
CREATE TABLE IF NOT EXISTS supplier_addresses (
  id            VARCHAR(36)  PRIMARY KEY,
  factory_id    VARCHAR(255) NOT NULL,
  supplier_id   VARCHAR(191) NOT NULL,
  label         VARCHAR(60),
  address_type  VARCHAR(32)  NOT NULL DEFAULT 'BUSINESS',
  address       VARCHAR(500) NOT NULL,
  contact_name  VARCHAR(100),
  contact_phone VARCHAR(40),
  is_primary    BOOLEAN      NOT NULL DEFAULT FALSE,
  sort_order    INTEGER      NOT NULL DEFAULT 0,
  notes         TEXT,
  version       BIGINT       NOT NULL DEFAULT 0,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at    TIMESTAMP,
  CONSTRAINT ck_supplier_address_type CHECK (address_type IN (
      'BUSINESS', 'SHIPPING', 'BILLING', 'WAREHOUSE', 'OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_supplier_addresses_supplier
  ON supplier_addresses(factory_id, supplier_id, sort_order);

CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_addresses_primary
  ON supplier_addresses(supplier_id)
  WHERE deleted_at IS NULL AND is_primary = TRUE;

------------------------------------------------------------------ 3) 银行账户
CREATE TABLE IF NOT EXISTS supplier_bank_accounts (
  id             VARCHAR(36)  PRIMARY KEY,
  factory_id     VARCHAR(255) NOT NULL,
  supplier_id    VARCHAR(191) NOT NULL,
  account_name   VARCHAR(200) NOT NULL,
  bank_name      VARCHAR(100) NOT NULL,
  branch_name    VARCHAR(200),
  account_number VARCHAR(64)  NOT NULL,
  currency       VARCHAR(8)   NOT NULL DEFAULT 'CNY',
  is_primary     BOOLEAN      NOT NULL DEFAULT FALSE,
  sort_order     INTEGER      NOT NULL DEFAULT 0,
  notes          TEXT,
  version        BIGINT       NOT NULL DEFAULT 0,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at     TIMESTAMP,
  CONSTRAINT ck_supplier_bank_account_currency CHECK (currency IN (
      'CNY', 'USD', 'EUR', 'JPY', 'HKD'))
);

CREATE INDEX IF NOT EXISTS idx_supplier_bank_accounts_supplier
  ON supplier_bank_accounts(factory_id, supplier_id, sort_order);

CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_bank_accounts_primary
  ON supplier_bank_accounts(supplier_id)
  WHERE deleted_at IS NULL AND is_primary = TRUE;

-- 同一供应商下账号不重复 (防呆: 重复录入同一张卡)
CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_bank_accounts_number
  ON supplier_bank_accounts(supplier_id, account_number)
  WHERE deleted_at IS NULL;

------------------------------------------------------- 4) 存量单值数据回填
-- id 用 md5(supplier_id||':'||slot) 派生的确定性 UUID —— 重跑得到同一个 id,
-- 配合 WHERE NOT EXISTS 保证幂等 (跟 V20261029_22 同一手法)。
DO $$
BEGIN
    IF to_regclass('public.suppliers') IS NULL THEN
        RETURN;  -- 全新 CI DB: 表还没被 ddl-auto 建出来, 无存量可回填
    END IF;

    ---------------------------------------------------------- 4a) 主联系人
    INSERT INTO supplier_contacts (
        id, factory_id, supplier_id, name, contact_type, phone, email,
        is_primary, sort_order, version, created_at, updated_at)
    SELECT
        substr(md5(s.id || ':supplier-primary-contact'),  1, 8) || '-' ||
        substr(md5(s.id || ':supplier-primary-contact'),  9, 4) || '-' ||
        substr(md5(s.id || ':supplier-primary-contact'), 13, 4) || '-' ||
        substr(md5(s.id || ':supplier-primary-contact'), 17, 4) || '-' ||
        substr(md5(s.id || ':supplier-primary-contact'), 21, 12),
        s.factory_id, s.id,
        btrim(COALESCE(NULLIF(btrim(s.contact_person), ''), btrim(s.contact_name))),
        'OTHER',
        NULLIF(btrim(COALESCE(NULLIF(btrim(s.phone), ''), s.contact_phone)), ''),
        NULLIF(btrim(COALESCE(NULLIF(btrim(s.email), ''), s.contact_email)), ''),
        TRUE, 0, 0,
        COALESCE(s.created_at, CURRENT_TIMESTAMP), COALESCE(s.updated_at, CURRENT_TIMESTAMP)
    FROM suppliers s
    WHERE s.deleted_at IS NULL
      AND COALESCE(NULLIF(btrim(s.contact_person), ''), NULLIF(btrim(s.contact_name), '')) IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM supplier_contacts c
          WHERE c.supplier_id = s.id AND c.deleted_at IS NULL);

    ------------------------------------------------------------ 4b) 主地址
    INSERT INTO supplier_addresses (
        id, factory_id, supplier_id, label, address_type, address,
        contact_name, contact_phone, is_primary, sort_order, version,
        created_at, updated_at)
    SELECT
        substr(md5(s.id || ':supplier-primary-address'),  1, 8) || '-' ||
        substr(md5(s.id || ':supplier-primary-address'),  9, 4) || '-' ||
        substr(md5(s.id || ':supplier-primary-address'), 13, 4) || '-' ||
        substr(md5(s.id || ':supplier-primary-address'), 17, 4) || '-' ||
        substr(md5(s.id || ':supplier-primary-address'), 21, 12),
        s.factory_id, s.id, '默认地址', 'BUSINESS',
        left(btrim(s.address), 500),
        NULLIF(btrim(COALESCE(NULLIF(btrim(s.contact_person), ''), s.contact_name)), ''),
        NULLIF(btrim(COALESCE(NULLIF(btrim(s.phone), ''), s.contact_phone)), ''),
        TRUE, 0, 0,
        COALESCE(s.created_at, CURRENT_TIMESTAMP), COALESCE(s.updated_at, CURRENT_TIMESTAMP)
    FROM suppliers s
    WHERE s.deleted_at IS NULL
      AND NULLIF(btrim(s.address), '') IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM supplier_addresses a
          WHERE a.supplier_id = s.id AND a.deleted_at IS NULL);

    -------------------------------------------------------- 4c) 主银行账户
    -- 户名缺省用供应商全称 (对公账户户名 = 公司名, 是最合理的默认值);
    -- bank_name 缺省用「未填写开户行」占位 —— 列是 NOT NULL, 但存量有
    -- bank_account 却没有 bank_name 的行 (prod 实测 6 vs 2), 不能因此丢账号。
    INSERT INTO supplier_bank_accounts (
        id, factory_id, supplier_id, account_name, bank_name, account_number,
        currency, is_primary, sort_order, version, created_at, updated_at)
    SELECT
        substr(md5(s.id || ':supplier-primary-bank'),  1, 8) || '-' ||
        substr(md5(s.id || ':supplier-primary-bank'),  9, 4) || '-' ||
        substr(md5(s.id || ':supplier-primary-bank'), 13, 4) || '-' ||
        substr(md5(s.id || ':supplier-primary-bank'), 17, 4) || '-' ||
        substr(md5(s.id || ':supplier-primary-bank'), 21, 12),
        s.factory_id, s.id,
        left(btrim(s.name), 200),
        left(COALESCE(NULLIF(btrim(s.bank_name), ''), '未填写开户行'), 100),
        left(btrim(s.bank_account), 64),
        'CNY', TRUE, 0, 0,
        COALESCE(s.created_at, CURRENT_TIMESTAMP), COALESCE(s.updated_at, CURRENT_TIMESTAMP)
    FROM suppliers s
    WHERE s.deleted_at IS NULL
      AND NULLIF(btrim(s.bank_account), '') IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM supplier_bank_accounts b
          WHERE b.supplier_id = s.id AND b.deleted_at IS NULL);
END $$;

------------------------------------------------------------------ 5) 授权
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cretas_user') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON supplier_contacts TO cretas_user';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON supplier_addresses TO cretas_user';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON supplier_bank_accounts TO cretas_user';
    END IF;
END $$;

COMMENT ON TABLE supplier_contacts
  IS '供应商多联系人。suppliers.contact_person/phone/email 保留为 is_primary=TRUE 那条的镜像, 供既有读点零改动继续使用。';
COMMENT ON TABLE supplier_addresses
  IS '供应商多地址。suppliers.address 保留为 is_primary=TRUE 那条的镜像。';
COMMENT ON TABLE supplier_bank_accounts
  IS '供应商多银行账户。suppliers.bank_name/bank_account 保留为 is_primary=TRUE 那条的镜像。';
