-- V20260901_04__fix_qhj_prod_restaurant_factory_mapping.sql
--
-- 修 qhj_prod 账号在 test DB (cretas_db) 上错误映射到 F001 (测试工厂, FACTORY)。
--
-- 背景 (2026-06-01 systematic-debugging):
--   "餐饮租户登录返 factoryType=FACTORY" 一度被当作登录 API bug 排查, 实为 test-DB 数据问题:
--   - prod (cretas_prod_db): qhj_prod → RES_3101_009 (QHJ_PROD, RESTAURANT)  ✓ 正确
--   - test (cretas_db):      qhj_prod → F001        (测试工厂, FACTORY)      ✗ 孤儿/陈旧
--   登录 API 代码无 bug (MobileAuthServiceImpl 正确返回 user.factory.type), 只是 test DB 里
--   qhj_prod 这一行历史遗留指向 F001。其 4 个兄弟账号 (qhj_warehouse_mgr / qhj_finance_mgr /
--   qhj_sales_mgr / qhj_operator, 由 V20260514_04 seed) 都正确在 RES_3101_009; 所有代码/测试
--   (phase-iia-final-demo.spec.ts 等) 也都假设 qhj_prod ↔ RES_3101_009。唯独 qhj_prod 自己漂了。
--   后果: web-admin 业态门控 (hideForFactoryTypes) E2E 在 test 环境验不了餐饮方向 (qhj_prod 被
--   当 FACTORY → 制造项不隐藏), IA 重设计 E2E 的餐饮 gating test 因此被 skip。
--
-- 修复: 把 qhj_prod 从 F001 重指向 RES_3101_009 (与 prod + 4 兄弟账号 + 所有测试期望一致)。
--   - 幂等 + 精确: 仅当 qhj_prod 当前在 F001 时改 (WHERE 双条件)。prod 上 qhj_prod 已在
--     RES_3101_009 → WHERE 不命中 → no-op, prod 零影响。
--   - F001 (测试工厂) 本身不动, factory_admin1 等 F001 账号不受影响。
--   - RES_3101_009 工厂在两库都存在 (factories 表 type=RESTAURANT), 重指向后该账号可正常登录。
--   - 仅当 RES_3101_009 存在时才执行 (to_regclass-style 防御: 防极端 fresh DB 缺该工厂)。

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM factories WHERE id = 'RES_3101_009' AND type = 'RESTAURANT') THEN
        UPDATE users
        SET factory_id = 'RES_3101_009', updated_at = NOW()
        WHERE username = 'qhj_prod' AND factory_id = 'F001';
        RAISE NOTICE 'V20260901_04: qhj_prod F001→RES_3101_009 remap applied (rows affected logged by UPDATE)';
    ELSE
        RAISE NOTICE 'V20260901_04 skipped: RES_3101_009 RESTAURANT factory absent (fresh/minimal DB), non-fatal';
    END IF;
END $$;
