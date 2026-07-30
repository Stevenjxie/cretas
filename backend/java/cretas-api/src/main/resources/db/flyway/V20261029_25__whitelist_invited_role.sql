-- 白名单邀请开户：由工厂管理员预设角色，员工以手机号完成开户。
-- NULL 保留历史白名单的“注册后待管理员激活”语义，避免自动提权。
-- whitelists 只由 Hibernate 实体建表, Flyway 目录里没有 CREATE TABLE。Spring Boot 先跑
-- Flyway 后跑 ddl-auto, 所以全新库跑到这里时该表还不存在, 裸语句会让整个应用起不来
-- (老库上表早已存在, 因此这个缺陷只在 CI 的全新库上出现)。守卫写法同 V20261027_41。
DO $$
BEGIN
    IF to_regclass('public.whitelists') IS NULL THEN
        RAISE NOTICE 'V20261029_25 skipped: whitelists not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE whitelists
        ADD COLUMN IF NOT EXISTS invited_role_code VARCHAR(50);

    COMMENT ON COLUMN whitelists.invited_role_code IS
        'FactoryUserRole invited by a factory administrator; NULL keeps legacy inactive registration';

    CREATE INDEX IF NOT EXISTS idx_whitelist_invited_role
        ON whitelists(factory_id, invited_role_code)
        WHERE deleted_at IS NULL;
END $$;
