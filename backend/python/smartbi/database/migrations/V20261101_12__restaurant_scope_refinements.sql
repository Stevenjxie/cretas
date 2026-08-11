-- 2026-08-11 门店范围 refinement context —— 让「先答后收窄」不必重走 T3。
--
-- 背景 (docs/superpowers/specs/2026-08-11-store-scope-refinement-context.md):
-- 多店租户首轮问「米饭的销量」时系统直接答全部门店并显式声明范围(PR #2368)。
-- 已知残余代价写在 `_apply_store_scope_guard` 的注释里: 用户拿到全店答案后再说
-- 一个店名来收窄, 那是一个**新问句**, 要重走一次 T3 —— 而 goal 的 hard criterion
-- 明写「在原本确定性的路径上新增 LLM 调用」是设计失败。本表消除那个代价。
--
-- ⛔ 为什么不复用 restaurant_pending_clarifications: 两者语义**相反**。
--      pending    = 「我问了你, 你下一句一定是答案」 -> **无条件**拼接
--                    (消费端 combined_query = original + " " + new)
--      refinement = 「我答了你, 但范围是我替你选的; 你下一句**可能**是收窄,
--                    也可能是完全无关的新问题」 -> 必须**有条件**消费
--    复用的后果 2026-08-07 撤回记录已写明: 给「已答完」的问题登记 pending,
--    下一个不相关的新问题会被拼到旧问句后面。
--
-- ⛔ 也不给 pending 表加一个 `kind` 列: 同一张表会诱使下一个人写出
--    「顺手也 pop 一下另一种」的代码, 而两者的消费语义正好相反。
--
-- 语义(与 pending 对齐的部分):
--   * 每个 (factory_id, session_key) 一行 —— PK, 新的一轮 UPSERT 覆盖旧的
--   * 消费即删: pop = 单语句 DELETE ... RETURNING(两个 worker 竞争时只有一个拿到)
--   * TTL 由 Python 侧按 created_at 判; pop 时顺带清扫超 1 小时的行, 防膨胀

CREATE TABLE IF NOT EXISTS restaurant_scope_refinements (
    factory_id  TEXT NOT NULL,
    session_key TEXT NOT NULL,
    -- 产生上一轮答案的**密封问句**(已含菜品/时间/指标)。收窄时与新说的门店名
    -- 拼接后走既有的显式槽位编译路径, 不调 T3。
    resolver_query_seed TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (factory_id, session_key)
);

-- ⛔ GRANT 与建表放在**同一个**迁移里, 不另开一个文件。
--    2026-07-08 的教训(V20260708_02): 迁移 runner 以 postgres 超级用户执行,
--    建出来的表对 app 连接池用户不可访问 —— 线上症状是每次 put/pop 都打
--    "permission denied for table ..." 的 fail-open 警告, continuation 静默失效,
--    而**没有任何东西会变红**。分成两个文件就给了「只写了前一个」的机会。
GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_scope_refinements TO smartbi_user;
