-- V20261014_02: D-9 G4 — Seed 1 F006 cashier role test account in cretas_db.
--
-- Background
-- ----------
-- Audit 2026-06-10-d9-payment-chain-test-env.md confirmed:
--   - FactoryUserRole.cashier exists (SP12 T1 新增), permissionPrefix="finance"
--   - cashier → finance:rw + procurement:r (L1 platform_role_permissions row exists)
--   - /api/mobile/F006/payment-requests/approved requires finance:read_write
--   - markPaid requires finance:read_write
--   - No F006 cashier user existed → payment workflow testing blind spot
--
-- Purpose
-- -------
-- Enable end-to-end testing of the cashier payment workflow:
--   1. cashier can GET /approved (finance:rw satisfies finance:read_write)
--   2. cashier can PUT /mark-paid
--   3. cashier cannot POST / (create — requires procurement:read_write, cashier has procurement:r)
--   4. cashier cannot PUT /finance-approve (requires finance:read_write, cashier has finance:rw → same)
--      Note: finance:rw IS finance:read_write — the code aliases "rw" = "read_write"
--
-- Password
-- --------
-- "123456" (test convention). We reuse the bcrypt hash from f006_admin via
-- SELECT-FROM-existing pattern (identical approach to V20260514_04).
-- If f006_admin is absent (fresh dev bootstrap), INSERT is a no-op (idempotent).
--
-- Role mechanics
-- --------------
-- User.getRole() returns roleCode ?? position (fallback chain).
-- We set BOTH role_code AND position to 'cashier' (defensive, mirrors
-- V20260429_01__fix_f006_position.sql convention).
-- Level 15 = FactoryUserRole.cashier.getLevel().
-- platform_type = 'web,mobile' (cashier accesses both web admin and mobile).
--
-- Idempotency
-- -----------
-- ON CONFLICT (username) DO NOTHING — users table has UNIQUE index on username.
--
-- This seed is TEST DATA ONLY. It must NOT be applied to prod factories with
-- real cashier accounts (naming convention makes this obvious: f006_cashier_test).

INSERT INTO users (
    factory_id,
    username,
    password_hash,
    full_name,
    role_code,
    position,
    level,
    is_active,
    platform_type,
    created_at,
    updated_at
)
SELECT
    'F006',
    'f006_cashier',
    password_hash,
    '六扇门 出纳 (D9测试账号)',
    'cashier',
    'cashier',
    15,
    true,
    'web,mobile',
    NOW(),
    NOW()
FROM users
WHERE username = 'f006_admin'
LIMIT 1
ON CONFLICT (username) DO NOTHING;
