# 原料入库两项改进 — 调研结论 + 批次续入设计 (待 Opus review)

**日期**: 2026-07-13
**worktree**: `C:/Users/Steve/cretas-dualmode` (分支 `feat/workflow-dual-mode`)
**触发**: F006 六膳门客户原话 — "原料入库时同一种原料会入很多次 → 一堆独立批次散着列,看着重复"。
**状态**: ①按物料汇总视图已实现(低风险)。②批次续入 = 🔒 库存红线, 本文档只给可行性结论 + 设计, 未写代码, 待 review 后再定。

---

## 1. 调研结论: 原料入库/批次模型在哪

### 前端
- **入库登记入口**: `web-admin/src/views/warehouse/materials/list.vue`(页面标题「原料 / 物料管理 (采购入库)」)。`入库登记`按钮弹 dialog → `POST /{factoryId}/material-batches`, 每次提交**必然新建一行 MaterialBatch**(批次号系统自动生成, 一物一码)。
- **相关只读视图**:
  - `web-admin/src/views/warehouse/inventory-total/index.vue` — 已有的「总库存查询」页, **工厂级、按物料聚合**(materialTypeId 一行), 有 `batchCount`/`warehouseCount` 但**没有展开看具体批次**。数据源 `GET /{factoryId}/material-batches/stock-summary`(后端聚合端点, 已存在)。
  - `web-admin/src/views/warehouse/inventory/index.vue` — 「库存盘点」批次级列表, 有「调整」按钮走 `/adjust`。

### 后端
- **`MaterialBatchController`**(`backend/java/cretas-api/.../controller/MaterialBatchController.java`): 26 个端点, 覆盖入库(`POST`)/更新/删除/FIFO/FEFO/预留/消耗/**调整(`POST /{batchId}/adjust`)**/汇总(`GET /stock-summary`)等。
- **`MaterialBatchServiceImpl.createMaterialBatch`**: 每次入库都 `new MaterialBatch()` + `UUID.randomUUID()` + 自动生成全局唯一 `batchNumber`(注释明确写"一物一码...防手敲重码"), 并调用 `updateMovingAvgPrice`(材料类型级移动均价, 不是批次级)。
- **`MaterialBatchServiceImpl.adjustBatchQuantity`**(line ~1454): 已有的、**干净的、带审计流水**的数量调整机制——
  ```java
  batch.setReceiptQuantity(batch.getReceiptQuantity().add(adjustment));
  MaterialBatchAdjustment adjustmentRecord = new MaterialBatchAdjustment();
  adjustmentRecord.setAdjustmentType(adjustment>0 ? "INCREASE" : "DECREASE");
  adjustmentRecord.setQuantityBefore(oldQuantity);
  adjustmentRecord.setQuantityAfter(newQuantity);
  ... // reason, adjustedBy, adjustmentTime 落 MaterialBatchAdjustment 表
  ```
  这就是"批次流水"—— 存在, 不需要新表就能记"这个批次的量什么时候被谁改了多少、为什么"。

### `MaterialBatch` 实体关键字段(决定续入可行性的核心)
```java
private BigDecimal receiptQuantity;   // 入库量 (可用量 = receiptQuantity - usedQuantity - reservedQuantity)
private BigDecimal unitPrice;         // 单价 — 批次级单值, 入库时由 totalValue/receiptQuantity 反算, 之后不再变
private LocalDate expireDate;         // 过期日期 — 批次级单值
private LocalDate productionDate;     // 生产日期 — 批次级单值
private String sourceDocType;         // 发起单类型 — 批次级单值 (P0-17 强制校验, 入库必须挂一张发起单)
private String sourceDocId;           // 发起单ID — 批次级单值
private String factoryNumber;         // 厂号 — 批次级单值 (一物一码标签用)
private String originPlace;           // 产地 — 批次级单值 (一物一码标签用)
private String qualityCertificate;    // 质量等级/证书 — 批次级单值
```
`getTotalValue()` = `unitPrice × receiptQuantity`(计算属性, 不是存储值)。

---

## 2. 批次续入可行性判断: ⚠️ 不能"干净地"直接做

### 表层看起来可行
`adjustBatchQuantity` 已经能把 `receiptQuantity` 往上调, 且有审计流水表 `MaterialBatchAdjustment`。如果只看"数量"这一个维度, 续入=调用一次 `adjust` 即可, 零新表零风险。

### 但深挖后, 至少 3 类字段会被静默弄错 — 这正是"批次"在本系统里被设计成**不可变追溯单元**(一物一码)导致的

1. **成本/财务口径 (🔒🔒 最硬红线, `.claude/rules/organizer-protocol.md` 明确列为暂留 Opus 的子集)**
   `unitPrice` 是批次级单值, `totalValue` 是 `unitPrice × receiptQuantity` 的计算属性。如果续入只是 `receiptQuantity += addQty`(照搬 `adjustBatchQuantity` 现有逻辑), 而新到货的单价与原批次不同, **总价值会用旧单价乘上新数量, 悄悄算错**(例: 原 100kg @¥10=¥1000, 追加 50kg @¥12, 若不重算单价 → 150kg×¥10=¥1500, 但实际成本应是 ¥1600, 差¥100 且不报错)。这直接污染库存估值/毛利/成本核算, 属于 `多模型分发` 规则里"暂留 Opus"的最硬红线, 不能由执行层顺手实现。
   - 若要做对, 必须按加权平均重算: `newUnitPrice = (oldQty×oldPrice + addQty×addPrice) / (oldQty+addQty)`, 且要在事务里同时写 `MaterialBatchAdjustment` 记录价格变化原因, 否则财务对账对不上。

2. **溯源退化 (食品溯源系统的核心合规诉求, 一物一码)**
   `sourceDocType`/`sourceDocId`(P0-17 强制的"发起单")、`expireDate`、`productionDate`、`factoryNumber`、`originPlace`、`qualityCertificate` 全是**批次级单值**。如果把第二次到货的量并进同一个 `batchNumber`:
   - 第二次到货各自的采购单/发起单来源丢失(只保留第一次的 `sourceDocId`), 出问题要追溯"这批货具体哪次交付出的问题"时做不到——这正是食品溯源系统存在的意义。
   - 如果两次到货保质期/生产日期不同(现实中几乎总是不同), 合并后批次只剩一个 `expireDate`, 要么保留较早的(低估可用期限, 尚可接受), 要么保留较新的(**高估过期风险, 可能让已过期的部分继续在售/在产**——直接的食品安全风险)。
   - `origin_place`/`factory_number`/`quality_certificate` 若两次不同(比如换了批号但同供应商同物料), 合并后无法区分。

3. **一物一码标签的物理一致性**
   `list.vue` 已有"生成标签"功能(`POST /labels/material-batch/{batchId}`), 客户描述里这是六扇门场景的合规要求。如果一个批次的标签已经打印贴到第一批货上, 之后又在同一 `batchNumber` 下追加了物理上完全不同的一批货(不同到货时间/可能不同小批号), 现场巡检时"一个码对应两批不同货"的物理事实与"一物一码"的设计初衷冲突。

### 结论
- **数量维度**: 可行, 现成机制(`adjustBatchQuantity` + `MaterialBatchAdjustment`)干净可复用, 零新表。
- **单价/溯源/标签维度**: 不能简单复用现成机制, 涉及 🔒🔒 成本口径 + 溯源正确性, 属于风险大类, **本次不自行实现**, 按任务指示交给 Opus review 定夺。

---

## 3. 推荐设计 (两个方案, 供 review 选择)

### 方案 A — 严格匹配续入 (低风险, 可作为窄 MVP)
只允许在以下**全部**条件成立时续入(否则前端直接引导"新建批次", 不给续入入口):
- 同 `materialTypeId`
- 同 `supplierId`
- 同 `unitPrice`(或干脆不问新单价, 强制沿用旧单价——如果客户实际到货单价一致, 这是安全的)
- 同 `expireDate`(或干脆要求"保质期剩余天数≥新到货保质期"才允许, 取更保守值)
- 同 `factoryNumber`/`originPlace`(为空则跳过校验)

若任一不匹配 → 用 409 + 明确提示("该批次的产地/厂号/单价与本次录入不同, 请改为新建批次以保证可追溯"), 遵循 `fool-proof-design.md` 4 位一体(message 具体 + toast sticky + 有 next action)。

实现: 新增一个 service 方法(不是简单复用 `/adjust`, 因为 `/adjust` 的语义是"盘点/报损修正", 不是"入库"), 例如 `replenishExistingBatch(factoryId, batchId, addQuantity, sourceDocType, sourceDocId, userId)`:
- 校验上面 5 项字段全部一致(不一致 409)
- `receiptQuantity += addQuantity`
- 写 `MaterialBatchAdjustment`, `adjustmentType = "REPLENISH"`(需要给该字段加新枚举值/校验, 目前是自由字符串 `INCREASE`/`DECREASE`, 加 `REPLENISH` 语义上更准确、且和"调整"区分开, 便于以后统计"续入" vs "盘点修正")
- 不改 `sourceDocType`/`sourceDocId`/`expireDate`/`unitPrice` 等既有字段(因为已强制一致, 不改也不会错)
- 发 `MaterialBatchCreatedEvent` 的姊妹事件或复用现有事件, 保证下游(自动匹配未来计划等)感知到量变

这个方案零新表, 财务/溯源都不会算错, 但**适用面很窄**——现实中"完全同供应商同单价同保质期"的补货场景可能不多, 客户真实诉求可能更宽(比如同供应商同物料但当天现货价格微调)。

### 方案 B — 加权平均续入 (完整满足客户诉求, 但改动面更大, 需 Opus 亲自写或审核实现)
允许 `materialTypeId` + `supplierId` 一致即可续入, 单价允许不同:
- 后端重算 `unitPrice`(加权平均, 见上方公式), `BigDecimal` 精度按 `.claude/rules/python-java-port.md` Rule 10/12 同款 HALF_UP 语义处理(虽然是纯 Java 场景, 但同样要小心 divide-then-multiply 中间精度)
- `expireDate` 取两者较早值(保守, 防止过期风险), 并在 UI 强提示"批次过期日期已更新为较早值 XXX, 因合并了不同到货批"
- `sourceDocId`/`sourceDocType` 单值字段不够用了 —— 需要新增一张轻量流水表(如 `material_batch_receipt_events`, 记录 `batchId, sourceDocType, sourceDocId, quantity, unitPrice, receivedAt`), 否则每次续入都会覆盖/丢失上一次的发起单来源, 破坏可追溯性。**这就是任务里说的"需要新表"的情形**——按指示应交给 Opus 判断是否值得为此建表, 还是客户的真实诉求用方案 A 的窄口径已经够用。
- Flyway: 若采用, 新迁移号从 `V20261028_60` 起(当前最高 `V20261028_59`, 已核对, 实操前需重新 `ls db/flyway` 防止并发撞号)。

### 建议
先上方案 A 作为 MVP(零新表零财务风险), 客户使用后若反馈"太严格, 我们经常是同供应商不同批号/价格也想续入", 再评估方案 B 的建表成本。方案 A 我可以在下一轮直接实现(不涉及 🔒🔒 红线, 只是新增一个校验严格的 service 方法 + 一个前端"续入到已有批次"入口), 但仍建议先过一遍 review 确认这个"严格匹配"边界是否符合客户实际使用习惯, 避免做出来后可用率太低。

---

## 4. 本轮已实现: 按物料汇总视图 (低风险, 已完成)

文件: `web-admin/src/views/warehouse/materials/list.vue`

- 在「原料 / 物料管理 (采购入库)」列表页的搜索栏加了 `el-radio-group`「按批次明细 / 按物料汇总」切换。
- 汇总模式: 纯前端聚合, 复用现有 `GET /{factoryId}/material-batches?page=1&size=2000&keyword=...` 分页端点(**未新增后端端点**), 按 `materialTypeId` 聚合成一行(物料名 + 合计可用量 + 单位 + 批次数 + 有价格权限则显示合计价值), 点击行左侧展开箭头可看该物料下的所有批次明细(批次号/供应商/数量/状态/过期日期/入库时间), 并保留原有的「查看/编辑/生成标签」操作。
- 防呆: 若一次拉取的 2000 条批次仍不足以覆盖全部(`totalElements > 2000`), 会在汇总视图顶部提示"数据量较大, 当前汇总仅基于前 N 条批次记录, 如有遗漏请缩小搜索范围重新汇总"——不静默漏算。
- 搜索/重置在两个视图下都生效(汇总模式下会重新拉取聚合数据)。
- 未新增后端端点、未新增 Flyway 迁移、未触碰生产计划/工序配置/新增产品表单等其他并行改动的文件范围。

---

## 5. 需要 Opus review 的问题清单

1. 方案 A vs 方案 B: 先上哪个? 还是先不做续入, 等客户实际反馈后再定?
2. 如果选方案 A, `MaterialBatchAdjustment.adjustmentType` 从"自由字符串"加一个 `REPLENISH` 值是否需要迁移历史数据/前端下拉展示逻辑同步(如 `inventory/index.vue` 的调整历史表格 `adjustmentType` 列)?
3. 如果客户诉求明确是"同供应商不同到货日/不同价格也要能续入"(即方案 B 的场景), 建议评估是否值得为此建 `material_batch_receipt_events` 表, 还是接受"每次不同就新建批次, 只是 UI 用汇总视图把它们看起来聚在一起"这个更简单的产品定位(即: 汇总视图本身可能已经解决了客户 80% 的"看着重复"痛点, 续入解决的是剩余 20% 的"操作便利性", ROI 需要重新评估)。
