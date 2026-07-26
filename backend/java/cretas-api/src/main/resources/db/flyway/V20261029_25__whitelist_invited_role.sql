-- 白名单邀请开户：由工厂管理员预设角色，员工以手机号完成开户。
-- NULL 保留历史白名单的“注册后待管理员激活”语义，避免自动提权。
ALTER TABLE whitelists
    ADD COLUMN IF NOT EXISTS invited_role_code VARCHAR(50);

COMMENT ON COLUMN whitelists.invited_role_code IS
    'FactoryUserRole invited by a factory administrator; NULL keeps legacy inactive registration';

CREATE INDEX IF NOT EXISTS idx_whitelist_invited_role
    ON whitelists(factory_id, invited_role_code)
    WHERE deleted_at IS NULL;
