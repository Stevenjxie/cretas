-- V20261029_53: 餐饮租户收敛 —— 只留 MOCK_REST, 其余 37 个停用
--
-- 只翻状态位, 不删任何业务数据(POS/Silver/Gold/预订/损耗行全部原地保留)。
--
-- ⚠️ 必须连用户一起停: MobileAuthServiceImpl:127/171 只校验 user.getIsActive(),
-- 全文没有一处校验 factory 的。只停租户会让用户登录成功却被 AI 网关
-- (ToolPrincipalPolicy:54) 全拒 —— 半死状态比直接拒绝登录更糟。
--
-- ⚠️ 台账两个列分开: factories.id 是 varchar(255), users.id 是 bigint。
-- 共用一个 object_id 列时, INSERT 有隐式转换看不出问题, 只有回滚时的
-- IN (SELECT object_id) 比较才炸(V20261029_44 原样事故)。

-- ── 0. Fail-closed 前置断言 ──────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM factories WHERE id = 'MOCK_REST' AND type = 'RESTAURANT') THEN
        RAISE EXCEPTION 'MOCK_REST 不存在或不是 RESTAURANT 类型, 中止 —— 否则会把所有餐饮租户都停掉';
    END IF;
END $$;

-- ── 1. 回滚台账 ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS restaurant_consolidation_ledger_20260805 (
    id           bigserial PRIMARY KEY,
    entity_kind  varchar(16)  NOT NULL CHECK (entity_kind IN ('FACTORY','USER')),
    factory_id   varchar(255),           -- 对应 factories.id (varchar)
    user_id      bigint,                 -- 对应 users.id (bigint) —— 刻意与上面分开
    recorded_at  timestamp NOT NULL DEFAULT now(),
    CHECK ((entity_kind = 'FACTORY' AND factory_id IS NOT NULL AND user_id IS NULL)
        OR (entity_kind = 'USER'    AND user_id   IS NOT NULL))
);

INSERT INTO restaurant_consolidation_ledger_20260805 (entity_kind, factory_id)
SELECT 'FACTORY', id FROM factories
 WHERE type = 'RESTAURANT' AND id <> 'MOCK_REST' AND is_active = true;

INSERT INTO restaurant_consolidation_ledger_20260805 (entity_kind, factory_id, user_id)
SELECT 'USER', u.factory_id, u.id FROM users u
 WHERE u.factory_id IN (SELECT id FROM factories WHERE type = 'RESTAURANT' AND id <> 'MOCK_REST')
   AND u.is_active = true;

-- ── 2. 停用 ──────────────────────────────────────────────────────────
UPDATE factories SET is_active = false
 WHERE type = 'RESTAURANT' AND id <> 'MOCK_REST' AND is_active = true;

UPDATE users SET is_active = false
 WHERE factory_id IN (SELECT id FROM factories WHERE type = 'RESTAURANT' AND id <> 'MOCK_REST')
   AND is_active = true;

-- ── 3. Fail-closed 后置断言 ──────────────────────────────────────────
DO $$
DECLARE active_count int;
BEGIN
    SELECT count(*) INTO active_count FROM factories WHERE type = 'RESTAURANT' AND is_active = true;
    IF active_count <> 1 THEN
        RAISE EXCEPTION '收敛后活跃餐饮租户应为 1, 实际 %', active_count;
    END IF;
END $$;
