"""Create the target factory row, factory_settings, and a demo-login super_admin user.
Source factory's own users are cloned separately (masked) by the engine; this adds the
known-credential login account the /demo chooser uses."""
import bcrypt

DEMO_PASSWORD = "demo2026"  # internal; frontend uses demo-login endpoint, not this directly


async def provision_factory(conn, target_id: str, name: str, ftype: str):
    await conn.execute("""
        INSERT INTO factories (id, name, type, is_active, manually_verified, ai_weekly_quota, created_at, updated_at)
        VALUES ($1,$2,$3,true,true,9999,now(),now())
        ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, type=EXCLUDED.type,
            is_active=true, manually_verified=true, ai_weekly_quota=9999, updated_at=now()
    """, target_id, name, ftype)
    await conn.execute("""
        INSERT INTO factory_settings (factory_id, working_hours, version, created_at, updated_at)
        VALUES ($1, 8, 0, now(), now())
        ON CONFLICT (factory_id) DO NOTHING
    """, target_id)


async def provision_demo_user(conn, target_id: str, username: str):
    pw = bcrypt.hashpw(DEMO_PASSWORD.encode(), bcrypt.gensalt()).decode()
    await conn.execute("""
        INSERT INTO users (factory_id, username, password_hash, role_code, full_name, is_active, created_at, updated_at)
        VALUES ($1,$2,$3,'factory_super_admin','演示账号',true,now(),now())
        ON CONFLICT (username) DO UPDATE SET password_hash=EXCLUDED.password_hash, is_active=true
    """, target_id, username, pw)
