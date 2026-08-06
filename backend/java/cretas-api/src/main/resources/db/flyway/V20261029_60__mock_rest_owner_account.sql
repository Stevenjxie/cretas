-- V20261029_60: MOCK_REST 老板账号 (mock_owner)
--
-- 背景: V20261029_56 给 restaurant_owner 补了全模块只读权限, 但 prod 全库两个
-- restaurant_owner 账号都是 is_active=f 且属于已停用租户 —— 权限配好了没人能用。
-- 本迁移给唯一活跃餐饮租户 MOCK_REST 建老板账号。
--
-- 与既有部门账号同规: mock_ops / mock_market / mock_finance / mock_hr /
-- mock_purchase, 口令统一 123456 (弱口令约定见 db-credentials.md, gitignored)。
--
-- 老板与五个部门的区别: 部门角色在自己那个 restaurant* 模块上是 rw, 老板是
-- **全部 21 个模块只读**。他要看得见全貌, 但执行动作留在各部门 —— 给 rw 会成为
-- 绕过 V20261029_52 刚收窄的部门边界的后门。
--
-- 🔑 password_hash 直接复用 mock_ops 的哈希: 同一明文口令, bcrypt 校验与盐无关,
-- 复制现成哈希比在 migration 里硬编码一个算不出正确性的字符串可靠。
-- 用 SELECT ... WHERE 表达, mock_ops 不存在的环境(非 prod)自然不插入且不报错。
--
-- ⚠️ 版本号 60: 写之前查过 prod 的 flyway_schema_history(最高 20261029.59)
-- 与仓库文件名, 两边都空。**只看仓库文件名不够** —— 2026-08-06 撞过两次,
-- 第二次是别的 session 把已应用的 55 改名成 57 占掉了号。
--
-- 幂等: ON CONFLICT 走 users 的 username 唯一约束。

INSERT INTO users (username, password_hash, factory_id, role_code, full_name, email, is_active, created_at, updated_at)
SELECT 'mock_owner', u.password_hash, 'MOCK_REST', 'restaurant_owner',
       '老板账号', 'mock_owner@cretas.com', true, NOW(), NOW()
  FROM users u
 WHERE u.username = 'mock_ops'
   AND u.factory_id = 'MOCK_REST'
ON CONFLICT (username) DO NOTHING;
