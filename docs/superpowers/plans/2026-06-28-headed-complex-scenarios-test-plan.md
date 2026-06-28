# 纯-Headed 复杂场景测试计划(从 SKU + 配方规则录入开始)

> **For agentic workers:** 这是测试计划。每个场景一个独立 headed Playwright 脚本(`tests/e2e-yield-mixed-sku/`),对 prod F006 跑,复用已验证的 headed helper(`selectByText` via getByRole / `fillNum` / `activePane` / `gotoTab` / `waitSaved`)。步骤用 `- [ ]` 跟踪。

**Goal:** 用纯 headed(全程点真实前端)覆盖三类复杂生产场景 —— 跨天成本归属、半成品跨计划领用、撤回/重结单 —— 且每条都**从 UI 录入 SKU(产品) + 配置 BOM 配方规则开始**,不复用既有产品/配方。

**Architecture:** 一个共享的 headed 工厂函数 `setupSkuAndBom(page)`(UI 建产品 + 配 BOM 行)产出一个干净的被测 SKU;三个场景脚本各自调用它建独立 SKU,再 headed 驱动各自流程,末尾用 API 只读 + 第一性 oracle 三方核对数字。计划创建因模态 el-select 与遮罩冲突仍可 API 前置(非被测核心),但 **SKU 创建 + BOM 配置 + 逐道录入 + 结单全 headed**。

**Tech Stack:** Playwright(headed,`headless:false`,1920×1080,`--lang=zh-CN`,独立 PORT 9258),Element Plus,prod F006 `http://139.196.165.140:8086`,账号 `f006_admin/123456`。

---

## 关键技术风险与对策(必读)

1. **模态内 el-select(teleport 到 body)与遮罩 z-index 冲突** —— 之前计划创建弹窗里 `getByRole('option')` 取到的选项被判 hidden。
   - 对策 A:产品/物料/BOM 表单若是**抽屉(el-drawer)**或**整页**,el-select 用 `getByRole('option').filter({hasText}).click()` 可行(逐道录入抽屉已验证)。
   - 对策 B:若确为**模态弹窗**且选项 hidden → 改键盘流:`input.click() → input.fill(关键词) → input.press('ArrowDown') → input.press('Enter')`;仍不行则 `page.locator('.el-select-dropdown__item').filter({hasText}).first().click({force:true})`。
   - 对策 C:每个 setup 步骤后**截图 + API 回读**确认真建成,失败 fail-fast(prereq 数据缺失按 depth-first-e2e Rule 1 降级,不静默跳过)。
2. **测试自污染**(回归已踩):原料批次发现的 `productTypeId` 过滤被后端忽略 → 必须客户端排除计数单位包材(`!/件|个|只|pcs/`),只取重量单位原料。
3. **每个场景用独立新 SKU**,避免相互干扰 + 数据可识别(SKU 名带时间戳前缀 `T<ts>`)。

---

## 共享步骤:Task 0 — `setupSkuAndBom(page)` headed 工厂

**Files:**
- Create: `tests/e2e-yield-mixed-sku/_headed-helpers.mjs`(导出 login/selectByText/fillNum/activePane/gotoTab/waitSaved/setupSkuAndBom)

**前置调研(写脚本前先 grep 确认 UI):**
- [ ] **Step 0.1 — 确认产品创建 UI 是模态还是抽屉**
  - 看 `web-admin/src/views/system/products/index.vue`:新建产品按钮文案、表单字段(name/code/unit/category/**gramsPerUnit 标准克重**)、是模态还是整页。
  - 记录:新建按钮选择器、单位 select、克重 input、保存按钮。
- [ ] **Step 0.2 — 确认 BOM 配置 UI(配方规则)**
  - 看 `web-admin/src/views/production/bom/index.vue`:它写 `POST /bom/items`(BomItem,= 物料建议/领料/成本真源,见 §11 两套 BOM 系统)。确认:产品选择、加 BOM 行的表单(materialTypeId / standardQuantity 配比 / yieldRate 损耗 / materialCategory RAW|PACKAGING / unit)。
  - 记录:产品 select、加行按钮、物料 select、配比 input、损耗 input、分类 select、保存按钮。
- [ ] **Step 0.3 — 确认原料物料是否够用**:F006 是否有可投产的**重量单位**原料(`raw-material-types?materialKind=原料`,unit kg/g)。setup 用现成原料即可(不必新建原料物料),除非场景需要。

**`setupSkuAndBom(page, {namePrefix, gramsPerUnit, bomLines})` 行为:**
- [ ] **Step 0.4 — UI 新建产品(SKU)**
  - 进 `/system/products`(或对应路由),点新建,填 name=`${namePrefix}-${ts}`、code、unit=`盒`、category、**标准克重 gramsPerUnit**(默认 80),保存。
  - 截图 `00-sku-created`;API 回读 `product-types/active` 确认新 SKU 存在 → 取 productTypeId。fail-fast if 未建成。
- [ ] **Step 0.5 — UI 配置该 SKU 的 BOM(配方规则)**
  - 进 `/production/bom`(BomController `/bom/items`),选刚建的产品,逐行加 `bomLines`:
    - RAW 行:选一个重量单位原料,配比 standardQuantity(每盒克重 e.g. 100 g),损耗 yieldRate(e.g. 90),分类 RAW,unit g。
    - PACKAGING 行(可选,若场景需要):吸塑盒,配比 1,损耗 99.5,分类 PACKAGING,unit 个。
  - 每行保存后截图 + API 回读 `/bom/items/{productTypeId}` 确认行落库。
- [ ] **Step 0.6 — 该 SKU 配工序链(若该产品无工序)**
  - 若新建 SKU 无 `product-work-processes`,逐道录入无 tab。两条路:
    - (a) 选一个**已有完整工序链**的产品做 SKU(复用工序),只新配 BOM —— 推荐,省工序配置 headed 的巨大成本。
    - (b) 若必须新建工序:进 `/system/product-processes` headed 配 ≥2 道(首道 + 一道下游)。仅当场景必须独立工序时做。
  - **决策**:默认走 (a) —— "录入 SKU + 配方规则" 的核心是 BOM 配方(配比/损耗/分类),工序链复用既有产品的;若你要连工序也新建,标注后走 (b)。
- [ ] **Step 0.7 — 提交**:`git commit -m "test: headed setupSkuAndBom factory"`

---

## Task A:跨天成本归属(cross-day cost attribution)

**Files:** Create `tests/e2e-yield-mixed-sku/headed-crossday-cost.mjs`

**业务口径**(见 §成本算法 + `buildRequest` processDate):逐道录入按工序的**日期列开始日**作 `processDate`,后端把该道的人工/调料成本报工归到**真实操作日**(非录入当天)。

- [ ] **A.1 setup**:`setupSkuAndBom` 建 SKU-CD(复用有工序链的产品 + 新 BOM)。
- [ ] **A.2 API 前置计划**(plannedQty)+ start。
- [ ] **A.3 headed 逐道录入跨天**:首道 processDate = D-2(前天),二道 processDate = D-1(昨天),三道 = D0(今天)。每道填日期列(el-date-picker 选历史日期)+ 投入/产出 + 保存。
- [ ] **A.4 验证(API 只读 + oracle)**:
  - 读 `process-sheet/rows` 各道 → 确认每道 `processDate` 落的是录入的历史日(D-2/D-1/D0),不是录入当天。
  - 读各道报工(若有按日成本端点)→ 该道人工/调料成本归到对应日。
  - **断言**:首道行 processDate==D-2,二道==D-1,三道==D0;成本随批次流转(继承成本链)正确。
- [ ] **A.5 headed 核对**:出成率卡渲染各道(日期/成本);截图。
- [ ] **A.6 提交**。

**oracle**:每道 step yield = 产出/投入;cum 链式;processDate 严格等于录入历史日。

---

## Task B:半成品跨计划领用(cross-plan WIP consumption)

**Files:** Create `tests/e2e-yield-mixed-sku/headed-crossplan-wip.mjs`

**业务**:计划 A 首道产出 WIP 批,计划 B 的工序消耗 A 的 WIP 批(跨计划血缘 + 成本继承)。验证 B 能选到 A 的 WIP、成本从 A 继承、A 的剩余正确扣减。

- [ ] **B.1 setup**:`setupSkuAndBom` 建 SKU-XP。
- [ ] **B.2 计划 A**(API 前置 + start);headed 首道录 1-2 个原料批 → 产出 WIP_A(截图 + API 取 WIP_A 批次号 + 单价/剩余)。
- [ ] **B.3 计划 B**(同 SKU 或相容 SKU,API 前置 + start);headed 打开 B 的逐道录入,在某道(单上游或混锅)的上游下拉里**选 WIP_A**。
  - ⚠ 关键验证点:B 的上游下拉**能否跨计划看到 A 的 WIP**。若设计上不允许跨计划(上游仅限本计划),则记录为"by design 不支持跨计划领用",转而验证**结单领用**或**半成品消耗**路径是否支持跨计划。先 grep 后端 `getInventoryItems`/upstream 解析是否按 planId scope。
- [ ] **B.4 验证**:
  - B 的该道继承成本 == 从 WIP_A 按消耗占比继承(oracle:feed/A_produced × A_rowTotalCost,逐边 scale-2)。
  - WIP_A 剩余按 B 消耗扣减。
  - 跨计划血缘:B 行 sourceBatchNumber == WIP_A 批次号。
- [ ] **B.5 提交**。

**前置 grep(B.3 前必做)**:确认 upstream/WIP 库存解析是否 plan-scoped。若是 → 调整为"跨计划领用走结单半成品消耗"或标注 defer。

---

## Task C:撤回 / 重结单(reverse & re-settle,不双计)

**Files:** Create `tests/e2e-yield-mixed-sku/headed-reverse-resettle.mjs`

**业务**(类比已修的 P&L 结转双计 bug —— 见 memory `feedback_pl_closing_aggregation_excludes_closing_vouchers`):生产计划结单后**撤回**(reverse),再**重结单**,不能双计成品/库存/成本。

- [ ] **C.1 前置 grep**:后端有没有"撤回结单 / 反结单 / reverse settlement"端点?
  - grep `reverse|撤回|反结单|unsettle|revertSettle|postingStatus` in ProductionPlanController/ServiceImpl + list.vue。
  - 若**无撤回功能** → 记录"生产侧暂无撤回结单",改测**重复结单幂等**(已在 §13 覆盖,可加强)或**仓库实收差异中转挂账**路径。
- [ ] **C.2 setup + 计划 + headed 录入 + 首次结单**(headed,COMPLETED + PENDING_WAREHOUSE_RECEIPT)。
- [ ] **C.3 撤回**(若有):headed 点撤回 → 状态回退;API 校验成品批/库存/成本凭证被红冲或回退,**不残留双份**。
- [ ] **C.4 重结单**:headed 再结单 → 再次 COMPLETED;API 校验最终成品量/成本==单次,**未双计**(库存增量、成本核算 total 都只算一次)。
- [ ] **C.5 验证**:reverse→resettle 后 `settlement` 回读 actualFinishedQuantity 不翻倍;成本核算页 total 不翻倍(headed 核对)。
- [ ] **C.6 提交**。

---

## 全局验证 / 完成定义(per depth-first-e2e)

- 每个脚本:`depth: deep`(真填+真提交+渲染回读+detail/数字核对),≥1 真断言能在后端 500 / 前端崩 / 数字错时 FAIL。
- prereq(SKU/BOM/WIP)缺失 → fail-fast,不静默跳过(降级为 medium 视为不合格)。
- 数字三方核对:oracle(脚本第一性)== API == 渲染 DOM。
- 自污染防护:原料发现排除计数单位包材。
- 每个脚本独立 SKU(时间戳命名),跑完不互相干扰。
- 末尾输出 `*-result.json` + 截图;状态 PASS 才算过。

## 顺序与交付

1. Task 0 setup 工厂(最关键,先打通 SKU+BOM headed)。
2. Task A 跨天(最独立)。
3. Task B 跨计划 WIP(需先 grep plan-scope 决定可行性)。
4. Task C 撤回(需先 grep 撤回端点存在性)。

B、C 都有"功能是否存在"的前置判定 —— 若后端无该能力,如实记录"by design 不支持/暂无",不硬造测试(per verify-first)。

---

## VERIFY-FIRST 结论(2026-06-28 调研,改写 B/C)

调研后端坐实 4 点(代码证据):
1. **产品创建 = 模态 el-dialog**(`system/products/index.vue:1250`,新增产品 → 字段 name/code/unit/category/**标准克重 gramsPerUnit**/装箱换算,确定提交)。模态内 el-select teleport → 用 selectByKeyboard 退路。
2. **BOM 配置 = 模态 el-dialog**(`production/bom/index.vue:1766`,添加 → materialCategory/materialTypeId/standardQuantity/yieldRate/unit,确定 → POST `/bom/items`)。
3. **上游 WIP 是 plan-scoped**(`ProcessSheetServiceImpl.getInventory:355` 按 `planId` 过滤)→ **跨计划领用 by-design 不支持**。
4. **生产侧无"直接撤回结单"端点** —— 只有 `/request-cancel`(PRODUCTION_REVERSAL 审批流)+ `/warehouse-receipt`(确认入库,PENDING_WAREHOUSE_RECEIPT 的正向下一步)。

### Task B 改写 → 半成品 plan-scope 隔离(负向正确性)
跨计划领用既然 by-design 不支持,改测**隔离正确性**:计划 A 产 WIP_A,计划 B 的上游下拉**不应**出现 WIP_A(只见自己计划的)。headed 验证 B 的 upstream dropdown 不含 WIP_A 批次号 → 确认 plan-scope 隔离对(防串料)。

### Task C 改写 → 结单后正向流 + 撤回审批
直接撤回不存在,改测真实存在的两条:
- **C1 仓库确认入库**(`/warehouse-receipt`):结单 COMPLETED+PENDING_WAREHOUSE_RECEIPT → headed 确认入库 → 成品入库 + **实收差异走中转挂账**(§6.12)。验证差异核对防呆。
- **C2 撤回审批请求**(`/request-cancel`):headed 申请撤回 → 触发 PRODUCTION_REVERSAL 审批流(不直接红冲)。验证它是审批流而非直接撤回(防呆:高风险操作走审批)。
