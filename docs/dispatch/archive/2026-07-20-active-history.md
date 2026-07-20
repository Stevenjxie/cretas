# Dispatch 完成记录 — 2026-07-20

### `F006-M09-SALES-DETAIL-SOURCE-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`7536b6f6e6122f06e60bda071a63e3c179841813`
- PR：[#1529](https://github.com/Stevenjxie/cretas/pull/1529)
- 根因一：`CreateSalesOrderRequest.SalesOrderItemDTO` 未声明 `sourceWarehouseCode`，导致请求字段在 JSON 反序列化边界被静默丢弃；create/update 映射也未写入该字段。实体与数据库列本身已经存在，因此详情 GET 只能回读 null。
- 根因二：销售订单详情、快捷发货、批次分配与利润详情直接渲染 API canonical `unit`，绕过共享 `displayUnit`，导致 `box` 泄漏给用户。
- 范围：DTO→Service→Entity→GET 补齐来源仓持久化并按当前工厂有效仓码 fail-closed；编辑缺省字段保留既有值，复制和拆单保留来源仓/包装快照；新增只允许填空、同值重放 no-op、不同值拒绝覆盖的历史桥接；Web 用户可见单位统一 `box/case/slice → 盒/箱/片` 且 payload/DB 保持 canonical。未触碰 M08、PB/FG、LIUSHANMEN。
- 验收：Web 目标测试 4 files / 19 tests 通过；唯一 Java release 生命周期 8/8 通过，JAR SHA-256 `a70bcf0148f45cbc0fcf3ecbe057fb1086e051e1b8f16584248ec305263eaca8`；唯一 Web release build 729 assets，archive SHA-256 `eafd82ba3f95535a7e61b95693b6ecc82be45ae6d728cb936c9637d4f3a2a84d`、index SHA-256 `9b68acb69b907fa6d8eb0952ca71ab67ff97becc1002232196f4c308eab03e1b`。
- 生产边界：exact-main 部署后只对 F006 订单 `ecd7f20b-21c2-4ea3-9103-2034d5d6547f` 的 item `726` 调用一次幂等桥接写入 `WH-LOG`；不得改变数量、价格、税率、状态、订单版本或创建生产计划。随后全部 query-only 验收并通知原测试 Chat 从同一已批准订单继续。
- Scope 锁：已释放。

### `F006-M09-SALES-PACKAGING-UNIT-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`a49d5aac5e635270535086d17c7e0d97cf9579ad`
- PR：[#1528](https://github.com/Stevenjxie/cretas/pull/1528)
- 根因：销售订单页直接把 SKU 中文展示单位写入请求，并把 `packageUnit` 或 `baseUnit` 任一匹配都视为可选箱规；F006 SKU 基础单位为“盒”、唯一箱规为“箱→盒”，于是 5盒订单错误携带 `unit=盒 + packagingSpecId`，与后端只允许包装单位选择箱规的严格 identity 契约冲突。
- 范围：表单状态和 payload 统一 canonical unit，单位下拉及箱规文案继续使用中文 display；基础单位下单显示“不涉及”且不提交 `packagingSpecId`，保留 `boxQuantity` 折算；包装单位下单才选择对应箱规；创建/编辑提交前 fail-closed 拦截 stale/mismatch。后端生产规则未放宽，只把目标测试切到真实 canonical 单位引擎并覆盖中文/canonical aliases。
- 验收：Web 目标测试 3 files / 20 tests 通过；唯一 Java release 生命周期 52/52 通过，JAR SHA-256 `9a3be242ff97e95b5aaf172223da680982b3dfcfa0454797aeef3ef3240da0f6`；唯一 Web release build 729 assets，archive SHA-256 `c825ffd266792f3448966e53f9f574a904a0293a9269ffac9d4a5f623303250b`、index SHA-256 `607a015a96ee737ebc8a653f9de5456eef284706ae33aabef9763d844ba35500`。
- 生产边界：部署 exact main 后只读核验同一客户仍存在、销售订单仍未创建、CPF0060015 在 WH-LOG 仍为唯一可用5盒；通知原测试 Chat 刷新同一页面创建且仅创建一单。禁止代建订单、修改客户/M08调拨/PB/FG或触碰 LIUSHANMEN。
- Scope 锁：已释放。

### `F006-M08-TRANSFER-DETAIL-UNIT-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`0dc264d546bfbb498348e7dcb1b035ed38658f3d`
- PR：[#1527](https://github.com/Stevenjxie/cretas/pull/1527)
- 根因：调拨创建页已通过共享 `displayUnit` 显示中文单位，但详情页主明细、各状态操作确认框、签收差异表与差异处理弹窗共 8 处直接渲染 API canonical `unit`，导致 `box` 在 DRAFT 及后续状态持续泄漏给用户。
- 范围：详情页所有用户可见调拨单位统一通过 `displayUnit`，覆盖 `box/case/slice → 盒/箱/片` 且保持 `g/kg`；API、DB 与 mutation payload 的 canonical unit 不变。未推进既有调拨 `0320fc6a-4199-4737-a7e1-8265e93a74b0` 状态，未改变库存，未触碰 LIUSHANMEN。
- 验收：目标 Vitest 3 files / 17 tests 通过，`vue-tsc -b` 通过；唯一 Web release build 729 assets，archive SHA-256 `809aee1fa54c4ea13a9fbf8daff777a173a20bd272686e1c48a55324e24eb512`，index SHA-256 `2e703ca87c4ae697c7580a5254945062d23ce7a9f5addbef9cd4ac869af5665d`。Java tree 未变化，发布判定 no-op。
- 生产边界：PR 合入后从 clean exact main 复用同 Web tree 制品并仅部署 Web；对既有 DRAFT 只读刷新验证单位后通知原测试 Chat 从同一 transferId 继续申请、审批、发运、签收与确认入库，绝不新建第二张。
- Scope 锁：已释放。

### `F006-M08-FG-TRANSFER-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`33025f28f3dbb9724e9c488f233f3eb5cefbdc06`
- PR：[#1526](https://github.com/Stevenjxie/cretas/pull/1526)
- 根因：手动调拨页所有行类型都固定消费 `/reference-data/materials`，行选择和提交也固定绑定 `materialTypeId`，因此“成品/菜品”既看不到成品库存，也无法提交后端所需的 `productTypeId`；库存上限还是全厂物料口径，单位输入直接泄漏 canonical `box`。
- 范围：复用现有 `/inventory/by-warehouse` 分仓库存真值，按调出仓聚合可用成品 SKU 和原料/包材；成品提交 `productTypeId`、原料/包材提交 `materialTypeId`；canonical unit 继续写入，Web 用共享 `displayUnit` 展示；Java 在建草稿前校验 identity、canonical unit 与源仓可用量，SHIP 事务原子门禁保持不变。未修改 F006 生产/库存数据，未触碰 LIUSHANMEN。
- 验收：Web 目标测试 2 files / 11 tests 与 `vue-tsc` 通过；唯一 Java release 生命周期 15/15 通过，JAR SHA-256 `657e34d0159e304c5b0fe6098becd6df3c50845e5cb5463d2f1c70b9524995df`；唯一 Web release build archive SHA-256 `5cab5ac1a0904364592a7c92466d938abb3773bf3cf508ccb029eba102b27e01`、index SHA-256 `0cafedd6f39c30b7b1edc2db87fed64d788dfd1472d766eaacb9b1776162a0a6`。
- 生产边界：exact-main 部署后只读证明同一 F006 成品仍唯一 5 盒，再通知原测试 Chat 从未创建调拨的现场续跑；不代替 QA 创建/审批/发运/签收/入库。
- Scope 锁：已释放。

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
