-- V20261029_59: MOCK_REST 采购部账号 (mock_purchase)
--
-- 背景: V20261029_58 把采购做成餐饮第五个部门, 但 MOCK_REST 没有采购账号
-- —— 部门建好了没人能登进去看。prod 全库两个 restaurant_purchaser 账号
-- (dr_qhj_chef 那批) 都是 is_active=f 且属于已停用租户。
--
-- 与既有四个部门账号同规: mock_ops / mock_market / mock_finance / mock_hr,
-- 口令统一 123456 (弱口令约定见 db-credentials.md, gitignored)。
--
-- 🔑 password_hash **直接复用 mock_ops 的哈希**: 同一个明文口令, bcrypt 校验
-- 与盐无关, 复制现成哈希即可, 免得在 migration 里硬编码一个我算不出正确性的
-- 字符串。若 mock_ops 不存在(非 prod 环境), 整条 INSERT 自然不插入 —— 用
-- SELECT ... WHERE EXISTS 表达, 不报错。
--
-- 幂等: ON CONFLICT 走 users 的 username 唯一约束。

INSERT INTO users (username, password_hash, factory_id, role_code, full_name, email, is_active, created_at, updated_at)
SELECT 'mock_purchase', u.password_hash, 'MOCK_REST', 'restaurant_purchaser',
       '采购部账号', 'mock_purchase@cretas.com', true, NOW(), NOW()
  FROM users u
 WHERE u.username = 'mock_ops'
   AND u.factory_id = 'MOCK_REST'
ON CONFLICT (username) DO NOTHING;
