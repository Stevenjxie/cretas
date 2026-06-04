# 工序配置 UX + AI 化 设计（产品工序配置 / 工序管理 / 生产计划链路）

**日期**: 2026-06-04
**触发**: Steve 实地走查"产品工序配置 → 销售订单 → 财审 → 生产计划 → 转批次 → 报工"全链路，暴露约 20 项 UX / 功能 / bug 问题。
**客户痛点**: 一个产品数十道工序、每道手点配责任人 → 点击量巨大；工序管理新增信息字段多（名称/类别/单位/工时/出成率上下限/产出单位/时薪…）；同名工序在不同产品下重复。
**目标**: ① 修通卡流程的 bug；② 大幅降低配置点击量（草稿 / 拖拽 / 搜索 / 左右布局）；③ AI 自然语言 + 模板推荐 配工序；④ 自动计算工序参数。

---

## 0. 现状关键事实（Explore 核实，file:line）

- **WorkProcess 实体字段已齐全**（`entity/WorkProcess.java`）：processName / processCategory / description / unit / estimatedMinutes / sortOrder / isActive / **standardYieldMin / standardYieldMax**(BigDecimal 6,4) / needsInput / outputUnit / **standardHourlyRate**(8,2) / expectedByproducts(JSONB)。→ **自动计算(D4)要写回的字段都已存在**，无需迁移。
- **工序管理页** `web-admin/src/views/system/work-processes/index.vue`：新增表单字段 = 上述全部。查重**已有但只查 processName**（`WorkProcessServiceImpl:35` `existsByFactoryIdAndProcessName` → 409）。
- **产品工序配置页** `web-admin/src/views/system/product-processes/index.vue`：左面板 280px(选产品) + 右面板 flex(**上段=工序流程，下段=可添加工序，上下堆叠**)；排序用上移/下移按钮 → `batchSortProductWorkProcesses` (`PUT .../product-work-processes/batch-sort`)；责任人下拉**即时 PUT**（无草稿）。
- **A1 bug 根因确认**：`updateProductWorkProcess(id,{responsibleWorkerId})` 只发部分字段；后端 `ProductWorkProcessDTO` 对 `productTypeId`+`workProcessId` 加了 `@NotBlank`，`update` 用 `@Valid` → 部分体校验失败 → 400「产品类型ID不能为空 / 工序ID不能为空」。**这是 Task 6 引入的缺陷**（服务端 E2E 发整行才没暴）。
- **AI 基建已存在可复用**：`AIChatPanel.vue`(canvas-editor 用，3 模式 Autopilot/Plan/Action，调 `POST /{factory}/config/v2/ai/chat`，返回 `{reply, diffs:[{tool,params}]}`) + Tool-Skill 架构(`ai/tool/impl/workprocess/` 已有 WorkProcessTaskSpawnTool 等) + IntentExecutorService。→ **D1/D3 复用此模式**（新 Tool + 挂 AIChatPanel），不新建 LLM 栈。
- **语音**: web-admin 无 iflytek（在 RN/后端）。→ D1 语音 web 端先用浏览器 SpeechRecognition 或纯文字，真机语音走 RN（语音定 P2）。
- **自动计算数据源已存在**：`ProductionReport`(inputQuantity/outputQuantity/totalWorkMinutes/totalWorkers/laborCost + workProcessTaskId/processOrder) + `YieldAnalysisService.aggregateByProcess()` / `YieldCalculationService.calculateSteps()`。→ D4 = 聚合 job 写回 WorkProcess 推荐值。
- **产品创建**: `views/system/products/index.vue` → `POST /{factory}/product-types`（`ProductTypeController`）。→ D2 推荐的钩子点。
- ⚠️ **prod 目前无真实客户/历史少**（`reference_prod_no_real_customers_yet`）→ D4 自动计算 & D2 历史推荐 冷启动期数据不足，需"数据足才算、否则留手填/LLM 兜底"。

---

## 1. 分批 & 依赖总览

| 批 | 内容 | 性质 | 依赖 | 可并行 |
|---|---|---|---|---|
| **A** | A1–A6 卡流程 bug | 修复 | 无 | 多数独立可并行 |
| **B** | B1–B3 财审性能/数据 | 排查+修 | 无 | B1/B2 同域串行, B3 独立 |
| **C** | C1–C5 配置 UX | 改进 | C1/C2 依赖 C4 草稿(同文件) | C3/C5 独立, C1/C2/C4 同文件串行 |
| **D** | D1–D4 AI 大功能 | 新建(需设计) | C4 草稿 + 工序 catalog + 历史数据 | 各功能分 agent, 同文件处串行 |
| **E** | E1–E3 产品决策 | 待 Steve 拍板 | — | — |

**⛔ 同文件并发铁律**：`product-processes/index.vue` 被 A1 / C1 / C2 / C4 / D1 都改 → **这些不能并行**（项目反复踩过并发覆盖事故），归同一 worktree 串行或同一 agent。跨文件/跨域的才并行。

---

## 2. 批 A — 卡流程 bug（最先做，让 Steve 跑通链路）

### A1. 配责任小组长 400「工序ID/产品ID不能为空」
**根因**: web 发部分体 + 后端 `@NotBlank` 校验。
**修法（推荐·最小）**: web `updateProductWorkProcess` 带**整行**（`productTypeId` + `workProcessId` + `responsibleWorkerId`，行数据本就在视图里）。
**备选**: 后端用 validation groups（update 组不校验那两个字段）—— 改动更大，暂不。
**文件**: `web-admin/src/api/processProduction.ts` + `product-processes/index.vue`(`handleResponsibleWorkerChange` 传整行)。
**验收**: 删光重加工序后选责任人 → 200 落库。

### A2. 新建计划弹窗点窗口外 → 取消 + 丢数据
**修法**: el-dialog `:close-on-click-modal="false"`；若已填内容，关闭(X/取消)前 `ElMessageBox.confirm("有未保存内容，确定关闭？")`。
**文件**: `production/plans/list.vue` 的新建 dialog。

### A3. 生产计划页"工序"应只读展示（不在这配）
**修法**: 计划新建/详情里若出现工序区 → 改为**只读展示**该产品已配工序链；**未配**则空状态提示「该产品未配置工序，去[产品工序配置]配置」+ 跳转按钮（fool-proof Rule 5 dead-end 改导航）。**不允许在计划页增删工序**。
**文件**: `production/plans/list.vue`(新建 dialog 的工序区)。

### A4. 销售订单→计划 产品行自动匹配 + 产品行/产品类型联动
**修法**: 选订单后 → ① 单行订单**自动选中**该产品行 + 带出产品类型（免再点）；多行则列出供选。② **产品类型由产品行决定**（联动锁定/禁用，不能再选鸭/猪脑等无关产品）。
**澄清**: "产品行" = 销售订单里的一条明细（含该产品+数量）；"产品类型" = 主数据里的产品。计划应锁定为订单行对应的产品类型。
**文件**: `production/plans/list.vue` 的来源订单选择逻辑。

### A5. 批次日期默认今天
**修法**: 转批次/批次相关日期字段 default = today。
**文件**: 转批次 dialog / `ProductionPlanServiceImpl` create-batch（后端 default 或前端 default）。

### A6. 移除/隐藏"生成工序任务"按钮（ProcessTask 旧模型，误导）
**修法**: 产品工序配置页移除/隐藏"生成工序任务"按钮（它走 `process-tasks/generate-from-product` = 另一套 ProcessTask 模型，与本功能无关，点了无变化→误导）。本功能任务由"转为批次"自动 spawn。
**文件**: `product-processes/index.vue`（删按钮 + 相关 handler）。
**注**: 与 C4 草稿"已保存无变化不可重复保存"配合，根治"我到底存没存"困惑。

---

## 3. 批 B — 财审性能 / 数据

### B1. 财审"通过"很慢
**排查**: profile `finance-approve` 接口（疑同步算 BOM 成本 / 发通知 / 同步事件链）。
**修**: 慢点异步化 or 加 loading + 明确反馈；先定位再定方案。

### B2. 标准单位成本 / 标准行成本 / 实际行成本 = 0
**排查**: BOM 标准成本计算（产品未配 BOM、或 BOM 项缺单价 → 0）。
**修**: 若数据缺则诚实显示「未配 BOM / 无单价」而非 0（禁假数据）；补 BOM 后正确算。

### B3.（增强）财审加售价趋势/对比
**做**: 财审页展示该产品最近 N 单销售单价趋势 + 与历史/标准成本对比，辅助判断本单售价高低/异常。
**数据**: 历史 sales_order 行 + 该产品成本。

---

## 4. 批 C — 配置 UX

### C1. 工序拖拽排序
**做**: 工序流程列表加拖拽（sortablejs / vuedraggable），drop 后写草稿（C4）或调 `batchSort`。保留上/下移按钮兜底。

### C2. 布局改左右两栏
**做**: 右面板"工序流程"+"可添加工序"由**上下堆叠**改**左右两栏**（同屏对照：左=已配工序链，右=可添加工序池）。点右侧工序 → 加入左侧链。

### C3. 责任人下拉加搜索
**做**: 责任人 `el-select` 加 `filterable` + filter-method，支持按 fullName / 拼音首字母 / 前两字 过滤排列，快速选人。

### C4. 草稿机制（"草稿→提交"两段式）⭐
**做**: 产品工序配置改两段式：
- 添加 / 删除 / 移动 / 改责任人 → **即时改本地草稿**（免确认弹窗）。
- 改完点「保存草稿」→ 再点「提交生效」才写库匹配到产品。
- **已保存且无变化 → 禁用保存按钮**（解决"存没存"困惑）；有变化才可保存。
- 离开页面有未保存草稿 → 提示。
**注**: 这是 C1/C2/A1/A6 的载体（即时改的是草稿，提交才落库）→ **同文件，需统一在一个实现单元里做**。

### C5. 重复工序检测（增强 + 清理）
**做**: ① 工序管理查重由 `processName` → **processName + category + unit** 组合；② 对**现存重复**（如掌中宝两个"修油"，同前处理同 kg）提供检测列表 + 合并/停用工具；③ 产品工序配置加工序时若检出重复给提示。
**排查**: 先查掌中宝那两个"修油"是同名两条 WorkProcess 还是同一条被链两次，定清理方式。

---

## 5. 批 D — AI 大功能（设计）

**统一原则**: 复用现有 Tool-Skill + AIChatPanel + IntentExecutor + 现有 WorkProcess 字段，**不新建 LLM 栈**。每个功能 = 一个新 Tool + 一个挂载点。

### D1. AI 自然语言配工序流程（产品工序配置页）
**做**: 产品工序配置页挂一个 `AIChatPanel`（仿 canvas-editor）。用户文字（语音 P2）说「第一步修油、第二步滚揉保水交给莫云、第三步焯水归魏振江…」→ 新 Tool `ProductWorkProcessConfigTool` 解析为工序序列 + 责任人 → 返回 **draft**（不直接落库）→ 进 C4 草稿区预览，用户改 → 提交。
**解析**: Tool prompt 喂入 ① 本厂工序 catalog（名/类别/单位）② 本厂小组长名单 → LLM 把自然语言映射到 catalog 里的工序 + 人；找不到的工序提示"是否新建"。
**语音**: web 先纯文字 / 浏览器 SpeechRecognition；真机 RN 走 iflytek（P2）。

### D2. 工序模板 + 默认推荐（新建产品时）
**做**: 新建产品成功后（`ProductType` create hook）调 `ProductWorkProcessRecommendTool`：
- 数据足：基于**本厂相似产品**的工序链（频次 + 出成率质量打分）推荐。
- 冷启动/数据少：LLM 据产品名/类别 给"建议"工序链（标注"AI 建议，请核对"）。
用户进配置页即见**预填草稿**（C4），改即可，大幅降点击。
**钩子**: `ProductTypeServiceImpl.createProductType` 后 / 或前端 create 成功后异步取推荐。

### D3. AI 工序管理 agent（工序管理页）
**做**: 工序管理页挂 `AIChatPanel` + 新 Tool `WorkProcessCatalogTool`（增/改工序全字段）。自然语言「加一道焯水，单位 kg，出成率 30–60%，标准时薪 25，前处理类」→ 解析建/改 WorkProcess。含 C5 重复检测提示。
**字段缺省**: 用户没说的字段 → 用 D4 自动计算值 / 同类工序默认 / 留空（needsInput 等有 default）。

### D4. 自动计算（出成率上下限 / 标准时薪）
**做**: WorkProcess 字段已存在（standardYieldMin/Max/standardHourlyRate）。加聚合 job（复用 `YieldAnalysisService.aggregateByProcess`）：
- 按 workProcessId 聚合历史 ProductionReport：input/output → 出成率分布（取 P20–P80 作上下限）；totalWorkMinutes/totalWorkers/laborCost → 推算标准时薪。
- **数据不足（< N 批，如 3）→ 不算**，留手填 / LLM 兜底。
- 写回为"系统推算值"，UI 与手填**区分标注**（"系统据 X 批自动推算，可手动覆盖"），手填优先。
**触发**: 批次完工后增量 / 定时。
**位置**: 后端聚合服务（Java 复用现有 yield service；重统计可走 SmartBI/Python）。
**标注 UI**: 工序管理 + 产品工序配置 字段旁标"自动/手填"来源。
**注**: 若某些工序生产中根本不需要这些字段 → 该字段在表单/展示里隐藏（按 needsInput / 类别决定）。

---

## 6. 实现并行策略（Steve: 用 subagent 并行）

**可并行（不同文件/域，分 subagent）**:
- A2(计划弹窗) ‖ A3(计划工序只读) ‖ A4(订单匹配) ‖ A5(批次日期) — 都在 production/plans 域但不同区块，需评估是否同文件；A5 可后端。
- B1/B2(财审，串行同域) ‖ B3(售价趋势，独立)。
- C3(责任人下拉搜索) ‖ C5(工序管理查重) — 不同文件。
- D2(产品创建 hook) ‖ D4(后端聚合 job) — 不同文件。

**必须串行（同文件 `product-processes/index.vue`）**: A1 → C4(草稿地基) → C1(拖拽) → C2(左右布局) → A6(删按钮) → D1(挂 AIChatPanel)。建议**一个 subagent 按序做完这条线**，或拆成"先 C4 地基，再其余"。

**D3(工序管理 AIChatPanel)** 与 C5 同文件(work-processes) → 串行。

**结论**: 分 ~4–6 条并行线：①product-processes 线(A1+C1+C2+C4+A6+D1 串行) ②work-processes 线(C5+D3 串行) ③production/plans 线(A2+A3+A4+A5) ④财审线(B1+B2+B3) ⑤后端 AI/计算线(D2+D4)。线内串行，线间并行。

---

## 7. 待 Steve 拍板的产品决策（E，先不动）
- **E1** 生产计划"计划数量"是否保留（你倾向"有多少做多少"）。
- **E2** 生产计划"指派主管"= 大组长（非小组长）—— 字段/逻辑是否改。
- **E3** 批次日期 vs 计划日期 语义（计划日期 = 开始生产那天）—— 厘清后是否调整字段。

---

## 8. 建议交付顺序（逐批，每批独立可验收）
1. **批 A**（解卡，最高优先）→ Steve 能跑通完整链路。
2. **批 B**（财审性能/数据）。
3. **批 C**（配置 UX；C4 草稿是地基，先做）。
4. **批 D**（AI 大功能；依赖 C4 + catalog + 历史数据）。
每批：headed E2E + 回归，merge main，从 main 部署 prod（含 RN OTA 如涉及 RN）。

---

## 9. 自检
- 占位扫描：无 TBD（D 类标注了冷启动/数据不足的兜底）。
- 一致性：A1 修法与 C4 草稿不冲突（A1 是即时修法兜底；C4 落地后责任人改走草稿）。
- 范围：单文档覆盖 A–D，但实现按批拆 plan（每批一个 writing-plans 周期）。
- 歧义：A4"产品行 vs 产品类型"已澄清；D2 冷启动兜底已明确。
