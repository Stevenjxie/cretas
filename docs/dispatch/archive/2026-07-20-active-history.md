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

### `F006-M07-YIELD-COST-UNITS-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`8e98b715ba0818c375b5050294876d52647b4de5`
- 根因一：持久化 yield-card 直接用 canonical 产出数量 `5 box` 除以 `4.5kg/5kg`，没有优先使用报工快照中的 `productWeight=4kg`，造成 step/cumulative 跨单位直接相除。
- 根因二：上游 WIP 的 MaterialBatch 历史行缺少 `unitPrice`，虽然对应 ProductionBatch 已有 `totalCost=56`，物化成本边仍按零价写入；历史成品批的旧总成本低于继承成本时，读模型又直接相减产生负 addedCost。
- 根因三：气调历史行直接渲染 Workflow canonical output unit，绕过共享 `displayProcessUnit`，所以 `box` 泄漏到中文 UI。
- 范围：yield-card 用 `productWeight` 做 kg/g/mg 可比产出换算；写入端按 ProductionBatch 总成本/产量补全解析单价；读端保证总成本不低于继承成本并重算单价；历史行统一 display-only 单位格式化。未修改 canonical payload/数据库，未触碰 F006 生产记录与 LIUSHANMEN。
- 验收：Web 目标测试 3 files / 30 tests 通过；唯一 Java release 生命周期 46/46 通过并生成可信 JAR；唯一 Web release build 与可信 archive 通过。生产同一 planId 仅做只读核验。
- Scope 锁：已释放。

### `F006-M07-BY-STOCK-SETTLEMENT-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`8d5af3daa8f7bcfa1b96c19bd1a736fc7bb4481f`
- 根因：BY_STOCK 的 `interim-settle` 只写 `production_interim_settlement` 并已直接生成 FG 库存；仓库状态/确认却只读取普通核对结单写入的 `production_settlements`，两条模型没有桥接。若直接复用普通仓库确认，又会再创建 `FG-{planNo}` 第二批成品库存。
- 范围：停产事务从连续小结会话、全部 SUBMITTED/已小结报工行和唯一 FG 批次严格派生一条 `PENDING_WAREHOUSE_RECEIPT` 元数据；提供受权限保护的幂等单计划历史桥接端点；BY_STOCK 仓库确认只复用既有小结 FG，差异 fail-closed，重复确认沿既有幂等/409 规则；普通核对结单路径保持原逻辑。Web 以 canonical unit 提交、中文 displayUnit 展示，并明确小结 FG 不会重复建批。
- 验收：唯一 Java release 生命周期 46/46 通过，JAR SHA-256 `ff4013deb67c0024043a7eab386cb1afaea26515bfdb960cfaf14a7d3e27a0e0`；Web 目标测试 2 files / 17 tests 通过，唯一 release build 729 assets，archive SHA-256 `9d99f011087abfc5d18b071b7dc4611b15f53ce15dd28365a5b0279989eb2ef9`、index SHA-256 `a063ad6573391647b7cb1e6a70e8f668d5917d4f5fffad070575a94f31426ec0`。
- 生产边界：部署 exact main 后，仅对 F006 指定 plan `1ff1bd66-627f-47c0-b890-2f54a2e8b529` 调用一次幂等历史桥接，补建缺失 settlement 元数据；不得重放小结、报工、停产或改动 PB/FG/原料/WIP/yield/cost。随后只读核验并通知原测试 Chat 单击一次仓库确认。
- Scope 锁：已释放。
