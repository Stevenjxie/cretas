# 六扇门「全天备货看板」设计 (剩余可能订单对账 P1)

**日期**: 2026-06-03
**作者**: Claude (brainstorming with Steve)
**状态**: 待用户复审
**触发**: Steve "分析 6.3 剩余可能订单等等, 这部分需要能被体现出来"
**前置审核**: `docs/qa-audits/2026-06-03-liushanmen-6.1-6.3-field-process-conformance-audit.md`

---

## 1. 目标 & 背景

六扇门给叮咚供货, 订单按天爆量波动 (猪蹄 6.1=505 份, 6.3=**7088 份**, 14×)。这种尖峰不可能当天现做, 必须靠**前几天的结存 (剩余成品/在产半成品) + 提前排产**来填。当前系统能查单个订单的成品可用 (`InventoryMatchingService.checkAvailability`), 但**没有跨订单、全天维度的"需求 vs 可用结存 vs 缺口"对账视图**, 也没有"缺口一键转生产计划"。

**本功能 = 全天备货看板**: 选一个交货日, 按产品汇总当天全部订单需求, 对比可用结存 (成品 + 在产半成品折算 + 已排产), 算出缺口, 并让操作员把缺口一键转成生产计划草稿。

**防呆价值** (per `.claude/rules/fool-proof-design.md` Rule 1 + Rule 5): 操作员不用手算, 看板直接告诉他"这个产品还差多少、要不要补产", 缺口行一键预填好量去建计划。

---

## 2. 范围

### 本 spec 含 (P1)
- 全天备货看板: **产品级聚合** (不按仓), 按交货日。
- 可用口径 = 成品 FG + 在产半成品 WIP (折成品估算) + 已排产计划 (三层)。
- 缺口 → 逐产品生成生产计划草稿 (人工确认)。
- web-admin 看板页 + 一个只读后端端点。

### 本 spec 不含 (各自独立 spec, 后续)
- **P3 多仓订单** (1 采购单 → 6 目的仓): 看板"按仓"拆解依赖它。
- **P4 kg↔盒 标准化** (统一 FG/生产存储单位)。
- **P2 真实产品工序模板** — **只做 3 套 (猪舌/牛腱/掌中宝), 猪蹄 defer, 猪舌做 pilot** (客户张权 2026-06-03 决策: 猪蹄前半段工序"混在一起", 数据印证其首道 sheet=`领料-分切-化冻捞出-焯水油炸-修猪蹄` 5步揉1道, 现建模必100%改 → 缓做; 详见审核文档 §5.1)。**P1 备货看板不受此影响, 4 品 (含猪蹄需求) 都在看板内。**
- **P5 调料 BOM + 包材材料成本**。

P1 假设 FG 按产品单位 (盒) 读; 单位不一致时用 `gramsPerUnit` 折 + 看板标注。

---

## 3. 架构 & 组件 (方案 A — 实时聚合, 零新持久表)

```
web-admin 备货看板页 (新)
   │ GET /api/mobile/{factoryId}/restock-board?deliveryDate=YYYY-MM-DD
   ▼
RestockBoardController (新, 只读)
   ▼
RestockBoardService (新, 只读聚合)
   ├─ 需求:   SalesOrderItemRepository.sumDemandByProductForDeliveryDate (新查询)
   ├─ 成品:   FinishedGoodsBatchRepository.sumAvailableQuantityByProductType (已有)
   ├─ 在产:   SemiFinishedInventoryRepository.sumBalanceByProduct (新查询)
   ├─ 已排产: ProductionPlanRepository.sumOpenPlannedByProduct (新查询)
   └─ 折算:   UnitConversion (gramsPerUnit) + ProductType.wipToFgYield
   ▼
RestockBoardDTO { deliveryDate, rows[], summary }

缺口行「建计划」→ 复用现有 POST /api/mobile/{factoryId}/production-plans (建 MANUAL 草稿)
```

**设计原则**: 看板是**只读派生视图**, 不持久化、不缓存, 每次实时聚合已有数据 (订单/FG/WIP/计划), 永远准确。新增的全是只读查询 + 一个聚合服务 + 一个页面。

### 3.1 单元边界

| 单元 | 职责 | 依赖 | 可独立测 |
|---|---|---|---|
| `RestockBoardService` | 给定 (factoryId, date) 返回看板行 | 4 个 repo + UnitConversion | ✅ mock repo |
| `UnitConversion` helper | kg ↔ 盒 (用 gramsPerUnit) | ProductType | ✅ 纯函数 |
| `RestockBoardController` | HTTP 端点 + 权限 | RestockBoardService | ✅ MockMvc |
| web-admin `RestockBoard.vue` | 看板 UI + 缺口建计划弹框 | 端点 + 现有 production-plans API | ✅ E2E |

---

## 4. 数据模型 & 计算

### 4.1 RestockBoardDTO

```
RestockBoardDTO:
  deliveryDate: LocalDate
  rows: List<RestockRow>
  summary: { totalProducts, shortfallProducts, fullySatisfiedProducts }

RestockRow:
  productTypeId: String
  productName: String
  unit: String            # 盒 (产品单位)
  demandQty: BigDecimal           # 需求 (盒)
  fgAvailableQty: BigDecimal      # 成品可用 (盒)
  wipEstimatedQty: BigDecimal     # 在产折成品 (盒, 估)
  scheduledQty: BigDecimal        # 已排产 (盒)
  totalAvailableQty: BigDecimal   # = fg + wip估 + scheduled
  shortfallQty: BigDecimal        # = max(demand - totalAvailable, 0)
  status: String                  # SATISFIED | SHORTFALL
  wipIsEstimated: boolean         # true → 在产列带"估"角标
  conversionWarning: String|null  # gramsPerUnit 未配置等
```

### 4.2 每行计算逻辑

对 `deliveryDate` 当天 (按需求/可用并集) 的每个 productTypeId:

| 字段 | 公式 / 来源 |
|---|---|
| `demandQty` | Σ `SalesOrderItem.quantity` where `SalesOrder.requiredDeliveryDate = date` AND `SalesOrder.status` 有效 (见 4.4), group by productTypeId。单位=盒。 |
| `fgAvailableQty` | `sumAvailableQuantityByProductType(factoryId, productTypeId)` (= Σ produced − shipped − reserved, status=AVAILABLE)。 |
| `wipEstimatedQty` | `sumAvailableByProduct(factoryId, productTypeId)` (= Σ `SemiFinishedInventory.availableQuantity` where availableQuantity>0, kg) × `wipToFgYield` × 1000 / `gramsPerUnit` → 盒。 |
| `scheduledQty` | `sumNotStartedPlannedByProduct(factoryId, productTypeId)` = **Σ `plannedQuantity` where status ∈ {PLANNED, PENDING}** (= 已提交但未开工的计划)。单位=盒 (计划单位)。 |

> ⚠️ **double-count 修正 (写计划时发现)**: `ProductionPlan.allocatedQuantity` 是"已分配**原料**数量"**不是生产进度**, 故 spec 早稿的"plannedQuantity − allocatedQuantity"是错的。更关键: **IN_PROGRESS / PAUSED 的计划其产出已经变成 `SemiFinishedInventory`(在产 WIP)或 FG(成品)**, 若再把它们的 plannedQuantity 算进"已排产"就会**同批算两次**(正是审计 F1 担心的)。**正确无重复口径: 已排产只算 NOT-STARTED 计划 (PLANNED + PENDING) 的整 plannedQuantity**; IN_PROGRESS/PAUSED 排除(产出在 WIP/FG)、COMPLETED 排除(在 FG)、CANCELLED 排除、PREPARED/PREP 草稿排除(可能丢弃)。三层 (成品FG / 在产WIP / 已排产NOT-STARTED) 因此互不相交。 |
| `totalAvailableQty` | fgAvailableQty + wipEstimatedQty + scheduledQty |
| `shortfallQty` | `max(demandQty − totalAvailableQty, 0)` |
| `status` | shortfallQty == 0 → SATISFIED; else SHORTFALL |

**口径来自用户决策**: 全天看板 (产品级) · 可用 = 成品+在产+已排产 · 缺口=max(需求−合计,0) · 缺口逐产品转草稿。

### 4.3 单位 & WIP 折算

- **kg → 盒**: `盒 = kg × 1000 / ProductType.gramsPerUnit`。
- **WIP 下游出率系数 `wipToFgYield`**: `ProductType` 新增可空列 `wip_to_fg_yield DECIMAL(5,4)`。
  - 已配置 → `wipEstimatedQty = wipKg × wipToFgYield × 1000 / gramsPerUnit`, `wipIsEstimated=true`。
  - 未配置 (null) → 按 1.0 折 (`wipKg × 1000 / gramsPerUnit`), `wipIsEstimated=true` + `conversionWarning="未配置在产出率, 按1:1估算"`。
  - 在产列 UI **永远带"估"角标** (半成品离成品远近不同, 不是精确值)。
- **成品 FG 单位**: P1 **直接按产品单位 (盒) 读** `FinishedGoodsBatch.producedQuantity`, **不对 FG 做自动单位转换** (系统无法逐行判断某条 FG 存的是 kg 还是盒)。真实气调流程 FG 入库就是盒 (气调 sheet "入库数量/盒"), 这是 P1 的口径假设。历史合成数据 (本会话 E2E batch 1924=540kg) 的 kg/盒 不一致归 **P4 (kg↔盒标准化) 清理**, 不在 P1。
- **gramsPerUnit 为 null**: 需要 kg→盒 折算的列 (在产 WIP) 显 "—" + `conversionWarning="未配置规格(gramsPerUnit)"`, **不静默算错** (成品/已排产本就是盒, 不受影响)。

### 4.4 订单状态过滤 (需求口径)

需求只算**有效订单**: `SalesOrder.status` ∈ 已确认且未取消的状态 (具体枚举值实现时对照 `SalesOrder` 状态机, 至少含 CONFIRMED, 排除 CANCELLED / DRAFT)。草稿/已取消不计入需求。

### 4.5 审计加固项 (superpowers 审计 2026-06-03, F1/F2, 均 LOW)

43-agent 对抗审计确认 0 CRITICAL/HIGH。两条 LOW 加固直接纳入本设计:

**F1 — FG 单日快照语义 (跨日不自洽风险, 已部分缓解)**:
`fgAvailableQty = produced − shipped − reserved` 已对【已财审 + 库存充足】订单 net out 预留 (`InventoryMatchingService.reserveStock` 在财审通过事件按 FEFO 设 `reservedQuantity`)。但**未预留的 FG** (订单仍 DRAFT/CONFIRMED/未财审, 或财审时缺货转了生产计划) 不归属某交货日 → 多个交货日的看板会各自把这批未预留 FG 算满一次, **跨日不自洽**。
- 本 spec 明确: **看板是单日快照, fgAvailableQty 显示"当前总可用未预留成品", 不按交货日跨日归属; 多日并存时未预留 FG 的归属需人工判断。**
- UI 在 fgAvailableQty 列加角标提示"未预留成品, 多日订单请人工分配"。
- 完整 FEFO 跨日预留分配标 **P-later** (不在 P1)。

**F2 — 需求侧单位一致性 (latent, F006 当前不触发)**:
`demandQty` 把 `SalesOrderItem.quantity` 当"盒"累加, 但 `SalesOrderItem.unit` 是自由文本 (非枚举)。F006 真实订单行 unit 一律=份(=盒 1:1), 当前不会算错; 仅未来同产品订单行混用单位(箱/kg)才触发。
- 本 spec 假设: **P1 假设同产品所有计入订单行 unit 均=盒(份)**。
- 防御: 聚合时若同一产品的订单行出现**不一致 unit** → 该产品 `demandQty` 显 "—" + `conversionWarning="订单行单位不一致, 需人工核对"`, 不静默按盒算。

---

## 5. 缺口 → 生产计划草稿 (防呆闭环)

缺口行 (shortfallQty > 0) 显示「建计划」按钮:

1. 点击 → 弹框预填 (防呆 Rule 1 预先显示 + Rule 2 带身份):
   - 标题: `补产计划 — {productName}`
   - 产品: {productName} (只读)
   - **建议产量**: `shortfallQty` 盒 (可调), 旁边自动显示折 kg = `shortfallQty × gramsPerUnit / 1000`
   - 交期: deliveryDate (可调)
   - 来源标注: "来自 {date} 备货看板缺口"
2. 操作员确认/调数 → 调用现有 `POST /api/mobile/{factoryId}/production-plans` (sourceType=MANUAL, productTypeId, plannedQuantity, plannedDate)。
3. 成功 → toast + 看板刷新 (已排产列增加, 缺口减少)。

**不自动提交**: 量预填好, 操作员一眼看到要补多少, 不用手算, 但保留确认权 (Rule 5 不 dead-end, 直接进建计划流)。

---

## 6. API 契约

### GET 看板
```
GET /api/mobile/{factoryId}/restock-board?deliveryDate=2026-06-03
权限: @RequirePermission({"production:read"}) (或现有生产读权限)
200 → { success, data: RestockBoardDTO, message }
```
- `deliveryDate` 必填 (缺 → 400 "deliveryDate 必填")。
- 当天无订单且无可用 → `data.rows=[]` + summary 全 0 (前端显示"该日无订单")。

### 建计划草稿 (复用现有)
```
POST /api/mobile/{factoryId}/production-plans
body: { sourceType:"MANUAL", productTypeId, plannedQuantity, plannedDate, remark }
```

---

## 7. 错误处理 & 边界

| 场景 | 行为 |
|---|---|
| `gramsPerUnit` 为 null | 折算列 (fg折/wip/scheduled 中需折的) 显 "—" + conversionWarning, 不静默算错 |
| `wipToFgYield` 未配置 | 1.0 折 + 标注"未配置在产出率,1:1估算" + 估角标 |
| 当天无订单 | 空看板 "该日无订单" |
| 缺口 ≤ 0 | status=SATISFIED, 无「建计划」按钮 |
| 已取消/草稿订单 | 不计入需求 |
| WIP/计划无该产品 | 该列 0 |
| 负的合计 (理论不会) | 缺口仍 max(.,0) 兜底 |
| 同产品订单行 unit 不一致 (F2) | demandQty 显 "—" + "订单行单位不一致需人工核对" |
| 未预留 FG + 多日并存 (F1) | 单日快照, fgAvailable 列带"未预留,需人工分配"角标 |

无降级假数据 (per CLAUDE.md 核心原则): 缺配置就显警告, 不编造数字。

---

## 8. 测试

### 单元 (RestockBoardService, mock repo)
- 需求聚合: 多订单同产品累加; 已取消订单不计入。
- 成品/WIP/已排产 各自聚合正确。
- 缺口: 需求>合计 → 正缺口; 需求≤合计 → 0 + SATISFIED。
- WIP 折算: 有 wipToFgYield / 无 (1.0+估) / gramsPerUnit null (—+警告)。
- 边界: 无订单 (空)、负合计兜底、单产品只有 WIP 没成品。
- F2 异质单位: 同产品两订单行 unit 一个"盒"一个"箱" → demandQty="—" + 警告 (不静默累加)。

### 单元 (UnitConversion)
- kg→盒 正算; gramsPerUnit null → 返 null/抛受控异常。

### 集成 (RestockBoardController, MockMvc + seed)
- seed 订单(交期=date)+FG+WIP+计划 → 端点返回正确行 + 缺口。
- deliveryDate 缺失 → 400。

### E2E (headed web-admin, per playwright-headed-mode rule)
- 用真实 6.3 数据 (猪蹄需求 7088): 看板渲染需求/成品/在产/已排产/缺口 + 状态色 + 中文无方块。
- 点缺口「建计划」→ 弹框预填缺口量 → 确认建草稿 → 看板刷新缺口减少。
- 截图留证 + headed verification block。

---

## 9. 实现单元 (供 writing-plans 拆 task)

1. **UnitConversion helper** + 单测 (kg↔盒, gramsPerUnit null)。
2. **3 个新只读 repo 查询**: `sumDemandByProductForDeliveryDate` (SalesOrderItem) / `sumBalanceByProduct` (SemiFinishedInventory) / `sumOpenPlannedByProduct` (ProductionPlan) + 各自 repo 测。
3. **迁移**: `ProductType` 加 `wip_to_fg_yield DECIMAL(5,4) NULL` (Flyway, to_regclass/列存在守卫; PR 前查 origin/main 版本号防撞车 per `feedback_flyway_cross_session_dup_collision`)。
4. **RestockBoardService** + DTO + 单测 (聚合/折算/边界)。
5. **RestockBoardController** + 集成测 (MockMvc)。
6. **web-admin RestockBoard.vue** 看板页 + 路由 + 菜单 (挂"生产管理") + 缺口建计划弹框 (复用 production-plans API)。
7. **E2E** headed spec + 真实 6.3 数据验证 + 截图。

---

## 10. 并行工作建议

### Subagent (单 Chat 内)
✅ 后端 (单元 1-5) 与 前端 (单元 6) 接口先定后可两 agent 并行。E2E (单元 7) 依赖前后端完成, 串行。

### 多 Chat 窗口
✅ P1 (本 spec) 与 P2/P3/P4/P5 文件互不冲突, 可并行。
⚠️ 冲突风险: 若 P4 (kg↔盒标准化) 同时改 FG 单位语义, 与 P1 的折算逻辑有交叉 — 建议 P1 先落地 (用 gramsPerUnit 折 + 标注), P4 后续统一时再回收 P1 的折算 workaround。
⚠️ 看板"按仓"增强依赖 P3, 不在本 spec, 留接口扩展位 (RestockRow 未来加 warehouseId 维度)。

---

## 11. 验收标准

- [ ] 看板端点对真实 6.3 数据返回正确的 需求/成品/在产/已排产/缺口 (猪蹄需求 7088)。
- [ ] 缺口产品状态 SHORTFALL + 满足产品 SATISFIED。
- [ ] 缺口「建计划」预填缺口量 (盒+折kg) → 建 MANUAL 草稿成功 → 看板刷新。
- [ ] gramsPerUnit/wipToFgYield 未配置时显警告/估角标, 不静默算错。
- [ ] headed E2E 截图 + 中文正常 + verification block。
- [ ] 零新持久表 (除 ProductType 加一列); 全只读聚合。
