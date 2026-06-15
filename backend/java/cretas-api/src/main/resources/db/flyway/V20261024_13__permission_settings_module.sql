ALTER TABLE user_module_access
    ADD COLUMN IF NOT EXISTS permission_level VARCHAR(16);

UPDATE user_module_access
SET permission_level = CASE
    WHEN access_type = 'GRANT' THEN 'write'
    WHEN access_type = 'DENY' THEN 'hidden'
    ELSE 'hidden'
END
WHERE permission_level IS NULL;

ALTER TABLE user_module_access
    ALTER COLUMN permission_level SET DEFAULT 'hidden';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = current_schema()
          AND t.relname = 'user_module_access'
          AND c.conname = 'ck_user_module_access_permission_level'
    ) THEN
        ALTER TABLE user_module_access
            ADD CONSTRAINT ck_user_module_access_permission_level
            CHECK (permission_level IN ('hidden', 'read', 'write'));
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_user_module_access_factory_user_module
    ON user_module_access(factory_id, user_id, module_code);

INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES
    ('factory_super_admin', 'permission_settings', 'rw'),
    ('factory_super_admin', 'permission_employee_management', 'rw'),
    ('factory_super_admin', 'permission_role_templates', 'rw'),
    ('factory_super_admin', 'permission_employee_overrides', 'rw'),
    ('factory_super_admin', 'permission_preview', 'rw'),
    ('platform_admin', 'permission_settings', 'rw'),
    ('platform_admin', 'permission_employee_management', 'rw'),
    ('platform_admin', 'permission_role_templates', 'rw'),
    ('platform_admin', 'permission_employee_overrides', 'rw'),
    ('platform_admin', 'permission_preview', 'rw'),
    ('permission_admin', 'permission_settings', 'rw'),
    ('permission_admin', 'permission_employee_management', 'rw'),
    ('permission_admin', 'permission_role_templates', 'rw'),
    ('permission_admin', 'permission_employee_overrides', 'rw'),
    ('permission_admin', 'permission_preview', 'rw')
ON CONFLICT (role_code, module_code) DO NOTHING;
