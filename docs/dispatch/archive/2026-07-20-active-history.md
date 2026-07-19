# Dispatch 完成记录 — 2026-07-20

### `BOM-M04-BLOCKERS-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`e12a6633f2b88d780751c0bdb9d346ffbfd854b9`
- PR：[#1519](https://github.com/Stevenjxie/cretas/pull/1519)；登记 Base 在构建期间被并发 main 提交推进，按门禁从 direct-main fastlane 回退为单次 PR。
- 根因一：Web 已将“盒/箱”规范化为 `box/case`，但 Java DTO 正则和 PostgreSQL `chk_bri_unit` 仍只接受本地化单位，导致合法 canonical payload 被 400/数据库约束拒绝。
- 根因二：v1 激活时替换了 Hibernate `orphanRemoval=true` 管理的 `BomRecipe.items` 集合引用，事务提交时报 `all-delete-orphan collection was no longer referenced` 并返回 500。
- 范围：统一 BOM 明细写入到共享 `UnitContractService`，删除过时静态单位白名单/数据库约束，保留未知与空单位的明确 4xx；激活和建稿改为保持 managed collection identity；补齐 DTO、迁移、Service、真实 JPA 激活和 Web 单位契约测试。未触碰 LIUSHANMEN。
- 验收：唯一 Java release 生命周期 102/102 通过，JAR SHA-256 `f0dd7208f36e1805aa21e0223097df42d783816a95bd425e1572bd1341b937b2`；Web BOM 目标测试 16/16 通过；最终 Vite production build 与可信 Web archive 通过。
- 生产边界：代码合并后从 clean exact main 复用 backend/web tree 匹配的可信制品，部署 Java/Web，并对 F006 指定 recipe `9e2eafed-9205-4627-aa4e-8acf20c460fd` 做已授权连续验收；禁止删除、重建配方头或另造 v1。
- Scope 锁：已释放。
