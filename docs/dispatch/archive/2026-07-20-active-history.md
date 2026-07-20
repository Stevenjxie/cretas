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

### `M09-SETTLEMENT-OUTPUT-UNIT-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`3a37bcbf3baad8bedd3d9c0e4492c63bdffae68f`
- 根因：逐道报工的末道 payload 已持有 canonical `outputUnit=box`，但 confirmation-only 结单预填把 `quantityUnit` 固定留空，结单实体因此保存 null；GET settlement 原样返回 null，Web 仓库确认只能走旧兜底“件”，确认写路径也允许该展示兜底成为入库单位。
- 范围：末道正式报工 terminal outputs 成为结单数量与 canonical unit 的共同事实源；新结单持久化 `box/case/slice/kg/g` canonical 单位并拒绝与显式结单单位冲突；历史 null 结单的 GET 仅从同一计划正式末道报工只读恢复，不修改结单/库存；仓库确认核对请求单位与权威单位，缺失或冲突 fail-closed，成功确认时在同一事务把 canonical unit 写回既有 settlement、响应、挂账与唯一 FG。Web 已有 `displayUnit` 继续把 `box` 显示为“盒”，无需改 bundle。
- 验收：最新 `origin/main` backend tree 上唯一受控回退 release 生命周期执行 `ProductionPlanSettlementTest` 34/34 通过；backend tree `0add1c0a937c13637c814fb851d81bcc7f9917b0`，JAR SHA-256 `3f2d29140e89c687ef978164c2728c386672a772ff521d7f907daa5b7fefa6e8`。新增覆盖新结单 canonical 持久化、历史 null GET 零写恢复、中文盒请求生成 canonical box FG、单位冲突零写拒绝。
- 生产边界：exact-main 部署后仅 query-only 核验 settlement `7254a0d3-c14f-4ad8-abbb-b7de35b647b5` 的 GET 已恢复 `quantityUnit=box`、仍待仓库确认且无新 FG/库存写；不预先修改历史 settlement。测试 Chat 刷新同一确认步骤后单击一次，确认事务才安全写回 canonical unit 并生成唯一正式成品库存。
- Scope 锁：已释放。

### `MAIN-STARTUP-1532-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`b587e0ee779136a5fe87f6722a61617fe1a7a0db`
- 根因：#1532 新增的 `RestaurantGrossMarginChatRouteSelector` 同时保留运行时单参数构造器和测试用 `Clock` 双参数构造器，但没有标注 Spring 应选择哪一个；Bean 工厂因此退回无参实例化并报 `No default constructor found`，使 exact-main 新槽无法启动。日志中的 `operation_logs varchar(20)` 是 13:03 的历史异步审计错误，不属于 15:18 启动失败。
- 范围：只给运行时单参数构造器加显式 `@Autowired`，保留测试 clock 注入能力；新增真实 `AnnotationConfigApplicationContext` 实例化回归。未改数据库、Repository、业务数据、Web 或 LIUSHANMEN。
- 验收：唯一 Java release 生命周期同时执行 M09 `ProductionPlanSettlementTest` 34/34 与 selector 4/4，共 38/38 通过；backend tree `432d63ac28bbc6bb4d2f2ba63b419b4ad6c15353`，JAR SHA-256 `954bd71b13632343e5456925606e2a064693f4a3f960f82ea98c698523eb3bfe`。
- 发布边界：首次 #1533 JAR 启动失败时脚本保持 green/10020 upstream 并停止 blue；本修复合入 exact main 后复用新可信 manifest 重新部署，必须以 blue core/full readiness、切流观察和 M09 同一 settlement 只读恢复为完成条件。
- Scope 锁：已释放。

### `CRETAS-AI-ARCH-V2-D11-CLOSEOUT-20260720`

- 状态：`merged`
- Owner：Codex coordinator (`/root`)；用户授权多代理加速，所有代码/只读代理均已回收。
- 登记 Base SHA：`8d5af3daa8f7bcfa1b96c19bd1a736fc7bb4481f`
- PR：[#1532](https://github.com/Stevenjxie/cretas/pull/1532)
- 覆盖任务：`CRETAS-AI-ARCH-TRUTH-AUDIT`、confirmation boundary、descriptor drift、dead bypass、D11B Gateway design/implementation/review、Restaurant Chat route/review/RN lease/server idempotency、AgentOps Runtime Shadow/production-wiring gate、Factory Pack sync/SSE Router、Restaurant Action Workflow/provisioning、Inventory Skill split、integration/final read-only review 与架构文档收口。
- Gateway 收口：删除客户端 boolean confirmation authority 与无消费者的 `ToolExecutionManager`；descriptor 真值为 588 total / 577 legacy / 11 explicit / 10 runtime-approved；首批 3 个餐饮只读 Tool 进入默认关闭的 legacy migration lane，Gateway deny 不回退旧 direct path。
- 餐饮 Runtime/Chat：同步与 SSE 主入口确定性选择既有毛利归因 Runtime，RN assistant 消息展示真实 Run/Event；客户端使用有界 module lease，服务端按可信 factory/owner/route/window 原子 claim-or-reuse，避免跨进程重复 durable run。
- AgentOps：服务端可信 corpus 通过隔离 `InMemoryRunStore` 和 read-only Gateway 生成 actual，三轮 evaluator 可作为自动回归门禁；正常 run/event 与 ERP 写入为 0，旧 client-actual experiment 仅保留手工兼容语义。
- 工厂端：四岗位 Capability Pack 接入同步与 SSE 前门，仅服务 FACTORY/CENTRAL_KITCHEN；read allowlist，write 只给 Workflow/Form/Navigation guidance，Restaurant 路由互斥，开关默认关闭。
- Action Workflow：首批只允许缺失菜品成本数据 proposal；preview token 原子 claim/replay，审批通过只导航配方管理。新增/复活租户由激活预置和 confirm 懒预置覆盖，Factory 行锁保证跨进程幂等；canonical graph、exact workflow 行锁、服务端 definition digest 与 transition 写前复核共同关闭配置漂移和 TOCTOU，且不覆盖已有租户配置。
- Skill 三拆：`inventory-analysis` 成为首个固定 `Workflow → warehouse Pack → Gateway → Presenter` 实例，只执行 3 个只读 Tool、零 LLM，数据库额外 Tool/DAG/prompt 注入无效。
- 终审：第一轮只读审查发现并阻断 RN remount 重复 start、缺服务器端跨进程 claim、Factory SSE 旁路、未来租户缺 Workflow、canonical graph 漂移与 definition TOCTOU；修复后最终 P0=0、P1=0。
- 验收：latest `origin/main` rebase 后 Java 34 类/250 tests 通过；Python Restaurant Runtime 88 passed；Python AgentOps 31 passed/1 skipped；React Native 20 passed；Web AgentOps 16 passed；独立 PostgreSQL 16 RunStore suite 15 项执行到 100%，并清理 disposable schema/role/container；Java Flyway 无重复，Python 重复集与 main 相同；direct-call baseline 无增长；`git diff --check` 通过。
- 发布边界：本 PR 只合并代码与文档。未构建/上传生产制品，未执行 `V20261028_86`，未重启、切流、发布 Web 或 RN/OTA，所有相关 flag 保持默认关闭。部署必须从 clean exact `origin/main` 重新读取现场真值并取得独立确认。
- Scope 锁：全部 D11 scope 已释放。

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

### `F006-M09-SALES-PLAN-UNIT-DATE-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`2ac8552831d0946a4af50a47be339fabf195d886`
- PR：[#1530](https://github.com/Stevenjxie/cretas/pull/1530)
- 根因一：销售来源计划表单虽然维护独立的批次日期，但 `batch-from-so` payload 未携带该字段；Java 请求 DTO 也没有 `batchDate`，Service 明确把 `plannedDate` 同时写进两个日期列，导致实际转批次日被静默覆盖。
- 根因二：生产计划列表/详情优先使用 canonical `sourceDisplayUnit=box`，结单预览与逐道报工若干位置也直接渲染 canonical 单位，绕过已存在的 display formatter。
- 范围：Web 以 `YYYY-MM-DD` 原样提交独立 `batchDate`，Java DTO/Service 按 `LocalDate` 独立持久化；计划列表/详情、结单预览、WIP 提示与逐道报工单位统一 display-only 映射，API/DB canonical 快照不变；新增仅允许 CUSTOMER_ORDER/PENDING/无批次/无报工且符合旧 `batchDate==plannedDate` 形态的 CAS 历史桥接，相同目标重放 no-op、不同目标拒绝覆盖。未触碰 M08、PB/FG、LIUSHANMEN。
- 验收：Web 目标测试 3 files / 37 tests 通过；唯一 Java release 生命周期 3/3 通过，JAR SHA-256 `5977caa4513054c1e58e610733a5ce42f67aca0220bb57ca3cc00efd08b023ed`；唯一 Web release build 729 assets，archive SHA-256 `6dd2e1b4d491c231aad984993bce514b5e120a3943815c91e1d19590328f07a8`、index SHA-256 `f2f7e0c391348d0e784305c74c00b01acd66fbb2d1cac84aa92b4ff0147b5fb8`。
- 生产边界：exact-main 部署后只对 F006 计划 `457daec1-d602-43a1-81a1-708586bfb937` 调用一次受约束桥接，将 `batchDate` 从 `2026-07-21` 校正为 `2026-07-20`；不得改变 plannedDate、数量、状态、Workflow/BOM pin，不创建订单/计划/批次/报工或库存写入。随后全部 query-only 验收并通知原测试 Chat 从同一唯一 PENDING 计划继续。
- Scope 锁：已释放。

### `M09-SETTLEMENT-PINNED-BOM-20260720`

- 状态：`merged`
- Owner：Codex (`/root`)
- 登记 Base SHA：`9cb0aafa507ed25f2203ab17eb93188974c996b7`
- 根因：结单写路径用 `firstNonBlank(consumptionLine.productTypeId, plan.productTypeId)` 解析 BOM，逐道报工预填行携带的原料/SKU identity 因优先级更高而覆盖计划成品 identity；只读 eligibility 却固定使用计划成品，造成同一计划只读判定 `bomFound=true`、提交却错误 409 `PRODUCTION_BOM_REQUIRED`。
- 范围：计划存在 `selectedBomRecipeId/selectedBomVersion` 时，eligibility 与 settle 共用 pinned recipe resolver，并核对 factory、计划成品 identity、版本；忽略消费行 identity 和后续 current BOM 变化。仅未 pin 的旧混 SKU 计划保留行级 product identity 兼容。缺失/错配 pin 在任何 settlement、库存、计划写入前 fail-closed；未修改 Repository/Entity/迁移，未触碰 F006 生产记录与 LIUSHANMEN。
- 验收：唯一 Java release 生命周期执行 `ProductionPlanSettlementTest` 31/31 通过并生成可信 JAR；backend tree `cd911fd1b14cdbca20ae710e0ee77c0f2ac25147`，JAR SHA-256 `3e94c636a03e828dee52b5a64b57b6b24d68002f597b921c8b8ced78ef89d2b2`。
- 生产边界：exact-main 部署后仅 query-only 核验计划 `457daec1-d602-43a1-81a1-708586bfb937` 仍为唯一 IN_PROGRESS、两道报工各一行、PB 批次唯一且 settlement 不存在；不代测试重试结单。随后通知原测试 Chat 使用新的前端幂等键从同一记录继续一次结单。
- Scope 锁：已释放。

### `AGENT-CANARY-SHADOW-P1-20260720`

- 状态：`merged`
- Owner：Codex coordinator (`/root`)
- 登记 Base SHA：`7eeb0d2d763fe47f4a3ef05b0e92e92a45084df4`
- PR：[#1537](https://github.com/Stevenjxie/cretas/pull/1537)
- 根因：AgentOps Runtime Shadow 原先只有二值总开关，没有租户、角色与稳定百分比灰度；同时 Python 运行时允许写入 `RUNTIME_SHADOW` 与 75 秒 case timeout，但 V04 PostgreSQL 列宽和 CHECK 仅允许 `RUN/RERUN` 与 5 秒 timeout，既有 InMemory 测试未暴露真实持久化冲突。
- 范围：Java/Python 双边增加默认关闭、配置不完整即拒绝的 tenant/role allowlist 与 basis-point canary；使用统一 SHA-256 前 32 bit、`% 10000`、`bucket < sampleBps` 契约，并统一 role 小写、salt trim 和安全 403 透传。V07 前向迁移只替换 operation/source/bounds CHECK，保留 RUN/RERUN 原限制与既有 FK/self-check，为 Runtime Shadow 单独限定 20 cases、并发 2、1–75 秒。普通 AgentOps 不构造 Shadow runner，也不受灰度影响。
- 安全收口：disabled、非 internal、tenant/role/sample 拒绝均在 PostgreSQL pool/store/runner 或 Java service 调用前终止；只有精确 `AGENT_OPS_RUNTIME_SHADOW_CANARY_DENIED` 可作为 403 穿透 Java facade，其他上游 403 仍脱敏为 502。PostgreSQL 门禁移除 `smartbi_db` 允许项，避免 SSH tunnel 将生产库伪装成 loopback 后误跑迁移测试。
- 验收：一次性 PostgreSQL 16 真实门禁 1/1 通过并清理容器，证明 Runtime Shadow 75000ms 可持久化且非法 source/bounds 被数据库拒绝；Python AgentEval 38 passed、1 skipped；Java clean 目标测试 32/32；跨语言固定 bucket vectors 一致；`V20261028_07` 唯一；最终只读终审 P0=0、P1=0；GitHub tracked-secret-scan 通过。
- 发布边界：本 PR 只合并代码、迁移与测试；未部署 Java/Python，未在生产执行 V07，未重启或切流，未启用任何 Runtime Shadow flag，未触碰 Web、RN 或 OTA。部署必须从 clean exact `origin/main` 重新路由并取得独立确认。
- Scope 锁：已释放。
