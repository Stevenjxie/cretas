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

### `BOM-UNIT-DISPLAY-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`1f94a0c4772ab54e33649fc6310f7aab8072f11a`
- PR：[#1522](https://github.com/Stevenjxie/cretas/pull/1522)
- 根因：BOM 主表与相关详情直接渲染 canonical `row.unit`，共享 `formatPriceUnit()` 也直接拼接 canonical code，且缺少 `slice → 片` 映射，导致已正确保存的 `box/case/slice` 在用量、单位与自动单价中泄露英文值。
- 范围：统一 BOM 用量、单位、版本产出、成本、编辑提示、复制候选、微调预览与 BOM 树的 display formatter；保留 `canonicalUnitCode()` 写入契约，不修改生产数据或 Java。
- 验收：BOM/unit 目标测试 9 files / 42 tests 通过；唯一 Vite release build 生成 729 assets，archive SHA-256 `30c3e0ce62f1d07791b78cb2b6ad08cdc1b46239c3831f143aec7a29dcf65931`、index SHA-256 `d31c0544ab07489c666ba23fcb14bd68ba98d8bf19ee76e5ab8a21bd450ecf3c`。
- 生产边界：仅部署 Web；Java 后端 tree 未变化应判定 no-op。部署后通知 F006 E2E Chat 从现有 v2 `b1f27a9b-cca3-4644-bc16-bd79c86dba41` 原现场续跑，不删除、重建或激活 v2，不触碰 LIUSHANMEN。
- Scope 锁：已释放。

### `F006-M07-WIP-DEDUCTION-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`fbe90fff754f2b1377ffbc76ff74856c0aec7c0c`
- 根因：下游定量包装在多上游来源模式只写入批次身份，没有把所选批次可用量写入 `upstreamSources[].feedQuantityKg`；成品重量可从库存回读计算，但确认框与 submit payload 以该字段为准，因而显示并提交 `0kg`。后端又会静默过滤零数量来源，存在伪造正数汇总投入绕过批次真实投入校验的风险。
- 范围：选择本计划在制或公共半成品时按当前可用量自动回填实际 kg；确认预览与正式提交共用同一 request；后端在写入前拒绝任何零/空投入的声明来源，并保留指定批次原子消费与重复提交保护。未触碰 F006 生产记录与 LIUSHANMEN。
- 验收：Web process-sheet 全目录 12 files / 69 tests 通过；唯一 Java release 生命周期 29 tests 通过并生成可信 JAR；唯一 Web release build 生成 729 assets 与可信 archive。生产部署与同一 planId 只读核验在 exact-main 发布阶段完成。
- Scope 锁：已释放。
