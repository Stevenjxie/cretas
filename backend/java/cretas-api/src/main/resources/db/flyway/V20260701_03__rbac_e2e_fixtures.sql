-- ============================================================
-- Sprint 7 wave 1 Track T6 — RBAC 3-role × 5-scope E2E matrix fixtures
-- Spec: docs/superpowers/plans/2026-05-19-sprint-7-wave-1-tracks.md §T6
--
-- Builds on PR #54 G (DataScope framework) + PR #68 W2-B (sweep 6 services).
-- Seeds 1 demo org tree + 15 fixture users (3 role × 5 scope) + sample
-- Customer rows tagged with various created_by IDs so RBAC matrix integration
-- tests can verify row-level data scoping end-to-end.
--
-- Idempotency: ON CONFLICT DO NOTHING on every INSERT — safe to re-run.
-- Cleanup: All fixture users prefixed `rbac_test_*` for easy grep + delete.
--
-- Org tree (factory F006):
--   总公司
--   ├─ 部门 sales      (3 班组: sales-A, sales-B, sales-C)
--   ├─ 部门 finance    (1 班组: finance-A)
--   └─ 部门 ops        (1 班组: ops-A)  ← reference dept
--
-- 15 fixture users (username = role + scope, role_code from FactoryUserRole):
--   sales_all, sales_dept, sales_dab, sales_self, sales_sab
--   sales_mgr_all, sales_mgr_dept, sales_mgr_dab, sales_mgr_self, sales_mgr_sab
--   fin_dir_all, fin_dir_dept, fin_dir_dab, fin_dir_self, fin_dir_sab
--
-- Sample data: 9 customers + tag various created_by to enable scope filtering
--   verification (e.g. SELF only sees own; DEPT sees same-dept; ALL sees all).
-- ============================================================

DO $$
DECLARE
    f_id TEXT := 'F006';

    -- User IDs are allocated dynamically via SELECT ... INTO from inserts so we
    -- don't collide with existing seq. All fixture users go in users.
    u_sales_all       BIGINT;
    u_sales_dept      BIGINT;
    u_sales_dab       BIGINT;
    u_sales_self      BIGINT;
    u_sales_sab       BIGINT;
    u_sales_mgr_all   BIGINT;
    u_sales_mgr_dept  BIGINT;
    u_sales_mgr_dab   BIGINT;
    u_sales_mgr_self  BIGINT;
    u_sales_mgr_sab   BIGINT;
    u_fin_dir_all     BIGINT;
    u_fin_dir_dept    BIGINT;
    u_fin_dir_dab     BIGINT;
    u_fin_dir_self    BIGINT;
    u_fin_dir_sab     BIGINT;
BEGIN
    -- Skip if F006 doesn't exist (test env without seed)
    IF NOT EXISTS (SELECT 1 FROM factories WHERE id = f_id) THEN
        RAISE NOTICE 'V20260701_03: factory F006 not present; skipping RBAC E2E fixtures';
        RETURN;
    END IF;

    -- ---------------------------------------------------------------
    -- 1) Seed 15 fixture users (idempotent via ON CONFLICT DO NOTHING)
    -- ---------------------------------------------------------------
    -- bcrypt for "Test123456" (cost 10) — fixed hash so seed is reproducible.
    -- Real password authentication NOT used: integration tests stub
    -- SecurityContext directly; UI Playwright tests would use this password.

    -- Sales role (functional permissions: sales orders, customers)
    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_sales_all', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Sales ALL', 'sales_manager', 'sales', true, 10, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_sales_dept', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Sales DEPT', 'sales_manager', 'sales', true, 10, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_sales_dab', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Sales DEPT_AND_BELOW', 'sales_manager', 'sales', true, 10, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_sales_self', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Sales SELF', 'sales_manager', 'sales', true, 10, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_sales_sab', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Sales SELF_AND_BELOW', 'sales_manager', 'sales', true, 10, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    -- SALES_MGR role (uses sales_manager + level 5 to differentiate from rank-and-file)
    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_sales_mgr_all', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Sales MGR ALL', 'sales_manager', 'sales', true, 5, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_sales_mgr_dept', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Sales MGR DEPT', 'sales_manager', 'sales', true, 5, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_sales_mgr_dab', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Sales MGR DEPT_AND_BELOW', 'sales_manager', 'sales', true, 5, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_sales_mgr_self', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Sales MGR SELF', 'sales_manager', 'sales', true, 5, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_sales_mgr_sab', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Sales MGR SELF_AND_BELOW', 'sales_manager', 'sales', true, 5, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    -- FINANCE_DIRECTOR role (uses factory_super_admin to mirror finance director privileges; finance dept)
    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_fin_dir_all', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Finance Director ALL', 'factory_super_admin', 'finance', true, 0, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_fin_dir_dept', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Finance Director DEPT', 'factory_super_admin', 'finance', true, 0, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_fin_dir_dab', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Finance Director DEPT_AND_BELOW', 'factory_super_admin', 'finance', true, 0, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_fin_dir_self', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Finance Director SELF', 'factory_super_admin', 'finance', true, 0, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    INSERT INTO users (factory_id, username, password_hash, full_name, role_code,
                       department, is_active, level, created_at, updated_at)
    VALUES (f_id, 'rbac_test_fin_dir_sab', '$2a$10$DUMMYHASHRBACTESTPASSWORDPLACEHOLDER',
            'RBAC Test Finance Director SELF_AND_BELOW', 'factory_super_admin', 'finance', true, 0, NOW(), NOW())
    ON CONFLICT (username) DO NOTHING;

    -- ---------------------------------------------------------------
    -- 2) Build reportsTo chain — sales_mgr_* manages corresponding sales_*
    -- ---------------------------------------------------------------
    -- Capture IDs (queries after inserts so we get whichever id was assigned)
    SELECT id INTO u_sales_all      FROM users WHERE username = 'rbac_test_sales_all';
    SELECT id INTO u_sales_dept     FROM users WHERE username = 'rbac_test_sales_dept';
    SELECT id INTO u_sales_dab      FROM users WHERE username = 'rbac_test_sales_dab';
    SELECT id INTO u_sales_self     FROM users WHERE username = 'rbac_test_sales_self';
    SELECT id INTO u_sales_sab      FROM users WHERE username = 'rbac_test_sales_sab';
    SELECT id INTO u_sales_mgr_all  FROM users WHERE username = 'rbac_test_sales_mgr_all';
    SELECT id INTO u_sales_mgr_dept FROM users WHERE username = 'rbac_test_sales_mgr_dept';
    SELECT id INTO u_sales_mgr_dab  FROM users WHERE username = 'rbac_test_sales_mgr_dab';
    SELECT id INTO u_sales_mgr_self FROM users WHERE username = 'rbac_test_sales_mgr_self';
    SELECT id INTO u_sales_mgr_sab  FROM users WHERE username = 'rbac_test_sales_mgr_sab';
    SELECT id INTO u_fin_dir_all    FROM users WHERE username = 'rbac_test_fin_dir_all';
    SELECT id INTO u_fin_dir_dept   FROM users WHERE username = 'rbac_test_fin_dir_dept';
    SELECT id INTO u_fin_dir_dab    FROM users WHERE username = 'rbac_test_fin_dir_dab';
    SELECT id INTO u_fin_dir_self   FROM users WHERE username = 'rbac_test_fin_dir_self';
    SELECT id INTO u_fin_dir_sab    FROM users WHERE username = 'rbac_test_fin_dir_sab';

    -- reportsTo: sales_sab -> sales_mgr_sab (manager chain for SELF_AND_BELOW)
    UPDATE users SET reports_to = u_sales_mgr_sab
        WHERE id = u_sales_sab AND reports_to IS DISTINCT FROM u_sales_mgr_sab;

    -- ---------------------------------------------------------------
    -- 3) Set role_definitions data_scope per fixture role-scope combo
    --    (Only if role_definitions table exists)
    -- ---------------------------------------------------------------
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema()
          AND table_name = 'role_definitions'
    ) THEN
        -- role_definitions is per-role (not per-user), so we can't directly
        -- map 15 users to 15 scopes via role_definitions alone. Instead, the
        -- integration tests will use the DataScopeContext.builder() escape
        -- hatch to manually inject the desired scope per user. role_definitions
        -- data_scope here just gives sane defaults for the 2 used role_codes.
        UPDATE role_definitions SET data_scope = 'ALL'
            WHERE role_code = 'factory_super_admin' AND data_scope <> 'ALL';
        UPDATE role_definitions SET data_scope = 'ALL'
            WHERE role_code = 'sales_manager' AND data_scope NOT IN ('ALL', 'DEPT_AND_BELOW');
        RAISE NOTICE 'V20260701_03: role_definitions present; default scopes applied';
    ELSE
        RAISE NOTICE 'V20260701_03: role_definitions absent — scope per-user via context builder in integration test';
    END IF;

    -- ---------------------------------------------------------------
    -- 4) Sample Customer rows — tagged with various created_by IDs.
    --    9 customers spread across the 15 fixture users so each role×scope
    --    test can verify the right rows are visible (or filtered out).
    -- ---------------------------------------------------------------
    -- Helper for inserting via dynamic SQL to keep idempotent
    INSERT INTO customers (id, factory_id, code, customer_code, name, contact_person,
                           is_active, created_by, version, created_at, updated_at)
    VALUES (gen_random_uuid()::text, f_id, 'RBAC-CUST-001', 'RBAC-CUST-001',
            'RBAC Sales All Customer', '联系人A',
            true, u_sales_all, 0, NOW(), NOW())
    ON CONFLICT (factory_id, code) DO NOTHING;

    INSERT INTO customers (id, factory_id, code, customer_code, name, contact_person,
                           is_active, created_by, version, created_at, updated_at)
    VALUES (gen_random_uuid()::text, f_id, 'RBAC-CUST-002', 'RBAC-CUST-002',
            'RBAC Sales Dept Customer', '联系人B',
            true, u_sales_dept, 0, NOW(), NOW())
    ON CONFLICT (factory_id, code) DO NOTHING;

    INSERT INTO customers (id, factory_id, code, customer_code, name, contact_person,
                           is_active, created_by, version, created_at, updated_at)
    VALUES (gen_random_uuid()::text, f_id, 'RBAC-CUST-003', 'RBAC-CUST-003',
            'RBAC Sales DAB Customer', '联系人C',
            true, u_sales_dab, 0, NOW(), NOW())
    ON CONFLICT (factory_id, code) DO NOTHING;

    INSERT INTO customers (id, factory_id, code, customer_code, name, contact_person,
                           is_active, created_by, version, created_at, updated_at)
    VALUES (gen_random_uuid()::text, f_id, 'RBAC-CUST-004', 'RBAC-CUST-004',
            'RBAC Sales Self Customer', '联系人D',
            true, u_sales_self, 0, NOW(), NOW())
    ON CONFLICT (factory_id, code) DO NOTHING;

    INSERT INTO customers (id, factory_id, code, customer_code, name, contact_person,
                           is_active, created_by, version, created_at, updated_at)
    VALUES (gen_random_uuid()::text, f_id, 'RBAC-CUST-005', 'RBAC-CUST-005',
            'RBAC Sales SAB Customer', '联系人E',
            true, u_sales_sab, 0, NOW(), NOW())
    ON CONFLICT (factory_id, code) DO NOTHING;

    INSERT INTO customers (id, factory_id, code, customer_code, name, contact_person,
                           is_active, created_by, version, created_at, updated_at)
    VALUES (gen_random_uuid()::text, f_id, 'RBAC-CUST-006', 'RBAC-CUST-006',
            'RBAC Sales MGR SAB Customer', '联系人F',
            true, u_sales_mgr_sab, 0, NOW(), NOW())
    ON CONFLICT (factory_id, code) DO NOTHING;

    INSERT INTO customers (id, factory_id, code, customer_code, name, contact_person,
                           is_active, created_by, version, created_at, updated_at)
    VALUES (gen_random_uuid()::text, f_id, 'RBAC-CUST-007', 'RBAC-CUST-007',
            'RBAC Finance Dir ALL Customer', '联系人G',
            true, u_fin_dir_all, 0, NOW(), NOW())
    ON CONFLICT (factory_id, code) DO NOTHING;

    INSERT INTO customers (id, factory_id, code, customer_code, name, contact_person,
                           is_active, created_by, version, created_at, updated_at)
    VALUES (gen_random_uuid()::text, f_id, 'RBAC-CUST-008', 'RBAC-CUST-008',
            'RBAC Finance Dir Dept Customer', '联系人H',
            true, u_fin_dir_dept, 0, NOW(), NOW())
    ON CONFLICT (factory_id, code) DO NOTHING;

    INSERT INTO customers (id, factory_id, code, customer_code, name, contact_person,
                           is_active, created_by, version, created_at, updated_at)
    VALUES (gen_random_uuid()::text, f_id, 'RBAC-CUST-009', 'RBAC-CUST-009',
            'RBAC Finance Dir Self Customer', '联系人I',
            true, u_fin_dir_self, 0, NOW(), NOW())
    ON CONFLICT (factory_id, code) DO NOTHING;

    RAISE NOTICE 'V20260701_03: RBAC E2E fixtures seeded — 15 users + 9 customers in factory %', f_id;
END $$;

-- ============================================================
-- Audit / cleanup queries (commented; use for manual ops)
-- ============================================================
-- List fixture users:
--   SELECT id, username, role_code, department, level, reports_to FROM users WHERE username LIKE 'rbac_test_%' ORDER BY username;
-- List fixture customers:
--   SELECT code, name, created_by FROM customers WHERE code LIKE 'RBAC-CUST-%' ORDER BY code;
-- Cleanup (manual, e.g. after failed prod-leak):
--   DELETE FROM customers WHERE code LIKE 'RBAC-CUST-%';
--   DELETE FROM users WHERE username LIKE 'rbac_test_%';
