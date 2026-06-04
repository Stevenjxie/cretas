# 批 A — 卡流程 bug + 生产计划页 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 修掉 Steve 走查"产品工序配置→订单→财审→计划→转批次"链路时卡住的 bug，让链路能完整跑通。

**Architecture:** 两条并行线，文件不重叠 → 可并行派 subagent；线内同文件串行。① **product-processes 线**(A1+A6, 改 `product-processes/index.vue` + `processProduction.ts`) ② **production/plans 线**(A2+A3+A4+A5+E2+E3, 改 `production/plans/list.vue` + 少量后端)。

**Tech Stack:** Vue3 + Element Plus (web-admin) / Java Spring Boot (后端) / 验证用 headed Playwright(prod 网关 139:8086)。

**Spec:** `docs/superpowers/specs/2026-06-04-workprocess-config-ux-ai-design.md` (批 A + E2/E3)。

**Worktree:** off origin/main。web-admin 验证 `cd web-admin && npm run build`；headed 用 prod 网关登录 `f006_admin/123456`。

---

## 文件结构（改动面）

**Line 1 — product-processes（A1 / A6）**
- `web-admin/src/api/processProduction.ts` — `updateProductWorkProcess` 调用点；`ProductWorkProcessItem` 接口
- `web-admin/src/views/system/product-processes/index.vue` — `handleResponsibleWorkerChange`(A1)、移除"生成工序任务"按钮+handler(A6)

**Line 2 — production/plans（A2 / A3 / A4 / A5 / E2 / E3）**
- `web-admin/src/views/production/plans/list.vue` — 新建计划 dialog（close-on-click-modal、工序只读区、订单产品行联动、批次/计划日期默认与厘清、指派主管非必填）
- 后端（按需）：`ProductionPlanServiceImpl` / `ProductionPlan` 实体（E3 batchDate 是否被消费的核查 + E2 主管校验放宽 + A5 日期 default）

> 实现期先 `grep`/读真实文件确认行号（行号以实现为准）。

---

# ===== Line 1: product-processes（A1 → A6，同文件串行）=====

## Task 1: A1 — 修配责任小组长 400「工序ID/产品ID不能为空」

**根因**: `updateProductWorkProcess(id, {responsibleWorkerId})` 只发部分体，后端 `ProductWorkProcessDTO` 对 `productTypeId`+`workProcessId` 加 `@NotBlank` + `update` 用 `@Valid` → 校验失败 400。

**Files:**
- Modify: `web-admin/src/views/system/product-processes/index.vue`（`handleResponsibleWorkerChange`）
- Modify (如需): `web-admin/src/api/processProduction.ts`（确保 update 透传整行字段）

- [ ] **Step 1: 读现状** —— 读 `handleResponsibleWorkerChange(item, value)`（约 line 124-136）确认它现在发的 body。读 `ProductWorkProcessItem` 接口确认 `productTypeId`/`workProcessId` 字段名。
- [ ] **Step 2: 改为发整行** —— `handleResponsibleWorkerChange` 里调用 `updateProductWorkProcess(factoryId, item.id, { productTypeId: item.productTypeId, workProcessId: item.workProcessId, responsibleWorkerId })`（`responsibleWorkerId`: 选中=number，清空=`-1`）。即把行已有的 `productTypeId`+`workProcessId` 一并带上，满足后端 `@NotBlank`。
- [ ] **Step 3: 构建** —— `cd web-admin && npm run build` 通过。
- [ ] **Step 4: headed 验证** —— web-admin(prod) → 系统管理→产品-工序配置→选猪舌 → 给某道选责任人 → **不再 400，落库成功**（刷新后保留）。
- [ ] **Step 5: Commit** —— `git commit -m "fix(配置): 配责任小组长发整行修 @NotBlank 400 (A1)" -- web-admin/src/views/system/product-processes/index.vue web-admin/src/api/processProduction.ts`

## Task 2: A6 — 移除误导的"生成工序任务"按钮

**Files:** Modify `web-admin/src/views/system/product-processes/index.vue`

- [ ] **Step 1: 定位** —— `grep -n "生成工序任务\|generateTasksFromProduct" web-admin/src/views/system/product-processes/index.vue`。
- [ ] **Step 2: 移除** —— 删该按钮 + 其 click handler（调 `generateTasksFromProduct` 的）+ 不再用的 import。（它走 ProcessTask 旧模型，与本功能无关；本功能任务由"转为批次"自动 spawn。）
- [ ] **Step 3: 构建** —— `npm run build` 通过。
- [ ] **Step 4: headed 验证** —— 配置页不再出现"生成工序任务"按钮。
- [ ] **Step 5: Commit** —— `git commit -m "fix(配置): 移除误导的生成工序任务按钮(ProcessTask旧模型) (A6)" -- web-admin/src/views/system/product-processes/index.vue`

---

# ===== Line 2: production/plans（A2/A3/A4/A5/E2/E3，同文件串行）=====

> 全部在 `web-admin/src/views/production/plans/list.vue`（+ 少量后端）。**一个 subagent 按序做完**（同文件，不可并行）。

## Task 3: A2 — 新建计划弹窗点窗口外不丢数据

**Files:** Modify `production/plans/list.vue`（新建计划 `el-dialog`）

- [ ] **Step 1: 定位** —— 找新建计划的 `<el-dialog>`（约 line 1000+，含 planForm）。
- [ ] **Step 2: 改** —— dialog 加 `:close-on-click-modal="false"`；并在关闭(X/取消)前，若 planForm 有已填内容则 `ElMessageBox.confirm('有未保存内容，确定关闭？')`，确认才关、否则留住。
- [ ] **Step 3: 构建** —— `npm run build`。
- [ ] **Step 4: headed 验证** —— 新建计划填一半 → 点窗口外 → **不消失/不丢数据**。
- [ ] **Step 5: Commit** —— `git commit -m "fix(计划): 新建弹窗点外不丢数据 (A2)" -- web-admin/src/views/production/plans/list.vue`

## Task 4: A3 — 计划页"工序"改只读展示（未配则提示去配置）

**Files:** Modify `production/plans/list.vue`（新建/详情里的工序区，如存在）

- [ ] **Step 1: 定位** —— `grep -n "工序\|workProcess\|processTask\|product-work-process" web-admin/src/views/production/plans/list.vue`。确认计划新建 dialog 里是否有"配工序"区块。
- [ ] **Step 2: 改只读** —— 若有可编辑工序区：改为**只读展示**该产品已配工序链（调 `getProductWorkProcesses(factoryId, productTypeId)` 拉取展示，不可增删）；**未配工序**时显示空状态「该产品未配置工序，请先到[产品工序配置]配置」+ 跳转按钮（`router.push('/system/product-processes?productTypeId=...')`）。计划页**不允许增删工序**。
- [ ] **Step 3: 构建 + headed** —— 选已配工序的产品 → 计划页只读列出工序；选未配产品 → 提示去配置 + 跳转可点。
- [ ] **Step 4: Commit** —— `git commit -m "fix(计划): 工序区改只读展示+未配引导去配置 (A3)" -- web-admin/src/views/production/plans/list.vue`

## Task 5: A4 — 订单产品行自动匹配 + 产品行/产品类型联动

**Files:** Modify `production/plans/list.vue`（来源订单选择 + 产品类型联动）

- [ ] **Step 1: 定位** —— 找 sourceType=CUSTOMER_ORDER 选订单后填充 productTypeId 的逻辑 + 产品类型 `el-select`。
- [ ] **Step 2: 自动匹配** —— 选订单后：拉订单明细行；**单行**→自动选中该行并带出 `productTypeId`（免再点）；多行→列出供选，选行后带出产品类型。
- [ ] **Step 3: 联动锁定** —— 产品类型由所选订单行决定：选订单来源时，产品类型 `el-select` 锁定/禁用为该行产品（不能再选鸭/猪脑等无关产品）；手工来源(MANUAL)时才可自由选。
- [ ] **Step 4: 构建 + headed** —— 选单行订单 → 产品行+类型自动带出；产品类型不能改成无关产品。
- [ ] **Step 5: Commit** —— `git commit -m "fix(计划): 订单单行自动匹配+产品类型联动锁定 (A4)" -- web-admin/src/views/production/plans/list.vue`

## Task 6: A5 + E3 — 日期默认今天 + 厘清/合并计划日期 vs 批次日期

**Files:** Modify `production/plans/list.vue`（+ 后端核查 `ProductionPlanServiceImpl` 是否消费 batchDate）

- [ ] **Step 1: 核查 batchDate 是否被消费** —— `grep -rn "batchDate\|getBatchDate" backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java`（含 create-batch 路径）。判定 `ProductionPlan.batchDate` 是否在任何业务逻辑（尤其转批次）被读取。
- [ ] **Step 2A（若 batchDate 未被消费）：合并** —— 表单/表格移除"批次日期"字段，只留"计划日期"（标签可改"计划生产日"），默认今天。
- [ ] **Step 2B（若 batchDate 被消费）：明确标注** —— 保留两字段但加 tooltip/说明：计划日期=计划生产日（必填，默认今天）；批次日期=实际转批次日（默认今天）。
- [ ] **Step 3: 默认今天** —— planForm 初始化时 `plannedDate`(及保留的 batchDate) default = 今天（`new Date()` ISO）。
- [ ] **Step 4: 构建 + headed** —— 新建计划日期默认今天；日期语义清晰（合并或标注）。
- [ ] **Step 5: Commit** —— `git commit -m "fix(计划): 日期默认今天 + 厘清/合并计划vs批次日期 (A5/E3)" -- web-admin/src/views/production/plans/list.vue [+后端如改]`

## Task 7: E2 — 指派主管改非必填

**Files:** Modify `production/plans/list.vue`（+ 后端如有 @NotNull）

- [ ] **Step 1: 定位** —— 找新建计划表单"指派主管/负责人"字段及其 required 校验。
- [ ] **Step 2: 改非必填** —— 去掉该字段 required 校验，label 标"(可选)"；后端若 `ProductionPlanDTO`/service 对该字段有 @NotNull/强制 → 放宽为可空。
- [ ] **Step 3: 构建 + headed** —— 不填指派主管也能建计划。
- [ ] **Step 4: Commit** —— `git commit -m "feat(计划): 指派主管改非必填 (E2)" -- [files]`

---

## Task 8: 批 A 整体 headed 验收（链路跑通）

- [ ] 用 `f006_admin` 在 prod web-admin 走：① 配责任人不报错(A1) ② 配置页无"生成工序任务"按钮(A6) ③ 新建计划填一半点外不丢(A2) ④ 计划页工序只读/未配引导(A3) ⑤ 选单行订单自动匹配+类型锁定(A4) ⑥ 日期默认今天+语义清晰(A5/E3) ⑦ 不填主管能建计划(E2)。
- [ ] 跑通 订单→确认→财审→计划→**转批次**（确认转批次自动 spawn 6 道 assigned 工序）。
- [ ] 记录验收 doc。

---

## 并行工作建议
### Subagent: ✅ **Line 1(Task1-2) 与 Line 2(Task3-7) 文件不重叠 → 两个 subagent 并行**。线内同文件串行。Task8 验收串行末尾。
### 多Chat: ❌ 同一 worktree，subagent 内做即可。

---

## Self-Review
- **Spec 覆盖**: 批 A(A1-A6) + E2 + E3 全部有对应 Task（A1=T1, A6=T2, A2=T3, A3=T4, A4=T5, A5+E3=T6, E2=T7, 验收=T8）。✅ E1(保留)无需 Task。
- **占位扫描**: 无 TBD；UI 逻辑类(A3/A4/E2/E3)给了精确文件+定位 grep+改动规格+验证，实现 subagent 读文件落地（符合 subagent-driven，subagent 有代码库）。✅
- **类型一致**: A1 用 `item.productTypeId`/`item.workProcessId`/`responsibleWorkerId`（`ProductWorkProcessItem` 字段，Task6 已确认存在）。✅
- **文件冲突**: Line1(product-processes) vs Line2(plans) 不重叠 → 并行安全；线内同文件串行（concurrent-edit rule）。✅
