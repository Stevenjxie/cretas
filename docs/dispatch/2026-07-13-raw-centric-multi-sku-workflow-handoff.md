# 一图多SKU（以原料为主 / raw-centric 分支 workflow）— GOAL + HANDOFF

> 换新 chat 做。两段分别复制：**GOAL** 贴给 `/goal`（或直接当首条需求），**HANDOFF** 贴在后面给上下文。

---

## ============ GOAL PROMPT（贴 /goal 或首条需求）============

把「产品工序 Workflow」从**一图一成品**升级成**以原料为主的一图多成品（raw-centric multi-SKU）**，并且**纯前端点击**就能全流程走通：建图 → 发布 → 启用 → 建生产计划 → 逐道报工（每道点击保存）→ 写入批次记录 → 成品入库 → 计算成本。全部实现，能用，不许假数据/降级。

**权威参照拓扑（客户给的两张图，六膳门 F006 猪蹄）**：
一个原料 `原料猪蹄` → `拆包/分切` → **分两支**：
- 支A：`焯水` → **再分流** → `五香猪蹄熟制`→`气调包装`→【成品①五香】 和 `红烧猪蹄熟制`→`气调包装`→【成品②红烧】
- 支B：`烧毛` → `焯水` → `泰式酸辣猪蹄熟制` → `泡制` → `气调包装` →【成品③泰式】

= **1 原料 → 1 张 workflow → 3 个出品 SKU**，含**物料级分支**（一个物料喂两个不同下游工序：拆包分切→焯水+烧毛；焯水→五香熟制+红烧熟制）+ 共享上游（拆包分切/焯水一次投料喂多个下游成品链）。

另外两个真实产品也要能建能报（Excel 在 `B:\Download-Chrome\`）：
- `干式熟成鸡.xlsx`：明显**两个规格的出成**（一道工序多产出/分流，已支持的 fan-out）。
- `纸皮牛肉（牛腱）V3.0.xlsx`：修油→滚揉→焯水→熟制→气调（线性5步，已支持）。
- `五香去骨猪蹄` + `泰式酸辣猪蹄`：就是上面猪蹄图那张，**两个猪蹄放一张图/一个原料**出多 SKU。

**核心要解决的架构缺口（已实测确认，见 HANDOFF 的"当前状态"）**：
1. **模型是 1 workflow ↔ 1 productTypeId**（activation 按成品 SKU 键；`materializeIfActive` 用 `productTypeId.equals(workflow.productTypeId)` 过滤）。→ 需要让**一张 workflow 声明它的多个终端成品 SKU**（从图里的 FINISHED_GOOD cell 派生），并让**任一出品 SKU 都能解析回这张 workflow**。
2. **生产计划识别不到分支多出品图**：实测建了 23 节点 3 成品的图，draft/publish/activate 全过，但 `GET .../process-sheet/workflow-config` **返 0 道工序**（连激活主键那个成品也 0）→ materialize 产 0 task。→ 需要让**计划/生产运行按原料为主（raw-centric）覆盖整张分支图**，逐道报工能走全部工序（含分支），每个 FINISHED_GOOD 终端各自入自己的成品 SKU 库 + 各自算成本；**共享上游工序（拆包分切/焯水）一次报工、一次扣料**，不能因为多成品重复扣原料。
3. **AI 生成器（buildWorkflowFromSpec）只支持线性+扇出+合流，不支持分支**（spec 是 `rawMaterials + steps[]` 线性）。→ 要么扩 spec 支持分支表达（一个物料多下游），要么先允许手工画分支（编辑器已能连多边），AI 增强可放后一阶段。

**验收（Steve 原话："全部实现并且能用纯前端去操作点击走通报工且保存且记录到批次记录钟且入库且能计算成本"）**：
- 在 `/system/product-processes` 画布**手工或AI**建出猪蹄那张 3 成品分支图 → 发布 → 启用（3 个成品都被这张图服务）。
- 建生产计划：无论按哪种方式（raw-centric 一次跑 / 或按任一成品建计划），前端 `/production/plans` 逐工序录入抽屉能列出**该成品对应路径的全部工序**（含共享上游）。
- **纯前端点击**逐道报工每一道（选批次+填量→保存），共享上游只扣一次料。
- 报完 → **小结**：3 个成品各自生成批次、进成品库存、各自 cost-breakdown 有原料/人工成本（不含包材调料，客户明确说先不管这俩）。
- 防呆铁律不破：workflow 定死的产出 SKU/单位/半成品|成品 kind、投入物料类型在逐道报工里**只读固定**，操作员**只选物理批次+填数量**（这条已在鸭子 SKU 上验证通过，别回归）。

**分阶段建议（新 chat 可自行拆 goal 子任务，但这是合理次序）**：
- **G1 模型+解析（最卡）**：workflow 多终端成品声明 + 任一成品 SKU 解析回图 + materialize 覆盖分支图（raw-centric 运行）+ 共享上游一次扣料。这块通了才谈得上报工。
- **G2 逐道报工前端**：分支图的逐工序录入 UI（工序按图拓扑列出，含分支；防呆选择不变）+ 每道点击保存 + 批次记录 + 入库。
- **G3 成本**：多成品各自 cost-breakdown（原料+人工，忽略包材/调料）。
- **G4 AI 分支生成**：扩 AI spec 支持分支表达（可选/后置）。
- 每阶段末：F006 真数据 headed 走一遍（claude-in-chrome，Steve 授权用真 Chrome）+ API E2E 兜底断言。

---

## ============ HANDOFF PROMPT（贴在 GOAL 之后当上下文）============

**项目**：白垩纪 Cretas 食品溯源，六膳门工厂 F006。产品工序 Workflow 编辑器（Vue web-admin）+ 逐道报工运行时（Java 后端）。测试账号 `f006_admin / 123456`。

**先读**：`memory/project_2026_07_12_workflow_multi_output_2b2.md`（本条工作全历史，尤其末尾"🔴 Jul 13 关键发现"和"逐道报工防呆验证"两节）。`.claude/rules/organizer-protocol.md` + `worktree-and-main-only-deploy.md`（隔离/部署铁律）。

### 当前状态（已实测，别重复踩）
- **已支持且防呆已验**：线性链 / 一道多产出(扇出 fan-out) / 合流(N→1 merge)。逐道报工→小结→成品入库→成本 对单成品链全通。防呆已验：workflow 定的产出SKU/单位/kind/投入物料类型在逐道**只读固定**，操作员只选批次+填量（鸭子SKU headed 验过）。
- **不支持（本次要做）**：一图多成品 + 物料级分支（一物料喂多下游）。实测：23节点3成品分支图 draft/publish/activate **全过**，但每个成品建 plan+batch 后 `workflow-config` 返 **0 道工序** → 逐道报工空。
- **AI 生成器**：`buildWorkflowFromSpec`（ProductProcessWorkflowEditor.vue）只线性+扇出+合流，无分支。后端 spec 提示在 `CanvasAIController.PRODUCT_PROCESS_WORKFLOW_SPEC_PROMPT`。

### 根因定位（已挖到代码，G1 从这动手）
- **materialize**：`service/workflow/impl/ProductProcessWorkflowRuntimeServiceImpl.java#materializeIfActive`
  - L86：只在 `batch.getWorkflowSelectionMode()==WORKFLOW` 才 materialize。
  - L96-99：过滤 `productTypeId.equals(candidate.getProductTypeId())` —— **workflow 只认自己那个 productTypeId**（多成品其余认不到）。
  - L108/121：`compiled = compiler.compile(...)`；`for (compiledTask : compiled.reportableTasks())` 建 `WorkProcessTask` + `WorkflowTaskPort`。**分支图 reportableTasks 疑似产 0 或编译器只认线性/单终端** —— 需查 `CompiledProductProcessWorkflow` 编译器对分支/多 FINISHED_GOOD 终端的处理。
- **逐道配置**：`service/workflow/impl/WorkflowClerkSheetServiceImpl.java` 从**已 materialize 的** `WorkProcessTask`+`WorkflowTaskPort` 拼 descriptor（L77-148）。0 task → 0 config。
- **create-batch / 计划**：`service/impl/ProductionPlanServiceImpl.java`（设 batch.workflowSelectionMode/selectedWorkflowId）。
- **逐道报工写入**：`service/processentry/impl/ProcessSheetServiceImpl.java`（POST process-sheet/row：rawMaterialInputs + upstreamSources → 产出批次；小结 interim-settle → 成品库存+成本）。
- **发布/启用/版本**：`controller/ProductProcessWorkflowController.java` + `service/impl/ProductProcessWorkflowServiceImpl.java`。publish 要求 ≥1 原料 cell + ≥1 成品 cell（多成品已被接受）。
- **activation 实体**：`entity/workflow/*`（activation 行：productTypeId→activeWorkflowId，enabled）。**这是"1图1成品"的锚点，多SKU要改这层**。

### 关键端点（curl @file 避转义）
- `PUT /api/mobile/F006/product-process-workflows/{productTypeId}/draft`（body=定义 {schemaVersion,nodes[],edges[],viewport}）
- `POST .../publish` {lockVersion} · `PUT .../{workflowId}/activation`
- `POST /F006/production-plans` {productTypeId,plannedQuantity,plannedDate,expectedCompletionDate,sourceType:'SAFETY_STOCK',skipProcessReporting:false,customerOrderNumber,notes}
- `POST .../production-plans/{planId}/create-batch`
- `GET .../production-plans/{planId}/process-sheet/workflow-config`（逐道 UI 契约，返每道 processName/plannedUnit/inputs[]/output）
- `POST .../production-plans/{planId}/process-sheet/row`（逐道报工一行）· `POST .../interim-settle`（小结）
- 逐道报工 payload 关键坑：上游 WIP 半成品用 `upstreamSources[].semiFinished:false`（true 会 SFI_NOT_FOUND）；原料 `rawMaterialInputs[]`；合流=两者并存；原料批次单位必须与本道投入单位一致（g≠kg 报 409 SOURCE_UNIT_MISMATCH）。

### 前端关键文件
- `web-admin/src/views/system/product-processes/index.vue`（顶部产品选择器 + 兼容列表；已用 `/product-types/options` 精简端点）
- `.../workflow/ProductProcessWorkflowEditor.vue`（2100+行画布主逻辑：buildWorkflowFromSpec / publish/activate / 自动保存 / 版本预览）
- `.../workflow/workflowModel.ts`（createProcessBranch / evaluateWorkflowConnection / autoLayout —— 判断连线合法性，**要确认它允许一个物料连多个下游工序=分支**）
- `.../workflow/WorkflowSkuPicker.vue`（两级 SKU 选择器）
- `web-admin/src/views/production/components/processSheet/ProcessDataTable.vue`（逐道录入行 UI；**防呆核心**：产出SKU/单位/kind/投入物料只读固定，只 `原料批次`/`来源批`下拉 + 数量。设计注释在 L232）+ `ProcessSheet.vue`
- `web-admin/src/views/production/plans/list.vue`（逐道抽屉，深链 `?openProcessEntryPlan=<planId>`）

### 前端 headed 测试怎么进（Steve 授权用 claude-in-chrome 真 Chrome）
- 深链逐道抽屉：`/production/plans?openProcessEntryPlan=<planId>`；深链编辑器：`/system/product-processes?productTypeId=<id>`。
- **token 注入**（合规，不输密码框）：JS 打 `POST /api/mobile/auth/unified-login {username:'f006_admin',password:'123456'}` → localStorage 存 `cretas_access_token` + `cretas_user`。**`cretas_user` 必带 `userType:'factory'` + 嵌套 `factoryUser:{role,factoryId,factoryType,businessDomain,permissions}`**（否则 isFactoryUser=false→currentRole=unactivated→/403）。unified-login 返回是**扁平**结构（role/factoryId/permissions 在顶层，token 在 data.token/accessToken）。
- ⚠️ **并发物流 session 抢同源 localStorage token**（一进 root 被路由去 logistics workbench）：破法 = inject 后**用 navigate 工具直接深链** production 页（非 location.href）；javascript_tool 读 token 会被 redact（setItem 仍生效）。
- 独立自动化优先用 `mcp__playwright-test__*`（独立实例，不碰 Steve 真 Chrome）；只有 Steve 明确要看时才 claude-in-chrome。

### 环境/建数据坑
- F006 建**原料类型**要 16 位分段编码（不便新建）→ 合流/原料输入 skuId **复用现有有可用批次的原料类型**，节点 data.name 用显示名即可。有可用 kg 批次的类型：`RMT_1777441647274`冻猪蹄 / `RMT_1777689969263`膝软骨 / `RMT_1777690110201`牛肉前腱子 / `RMT_1777690082465`冷冻猪舌（各 `GET /F006/material-batches/status/AVAILABLE?warehouseId=<WH-LOG>&size=300` 里挑 kg 单位、qty>15、非 WIP-/CLK- 的）。
- 建产品类型 `POST /F006/product-types` 只需 name+unit(+productCategory: SEMI_FINISHED/FINISHED_PRODUCT/RAW_MATERIAL)。
- **测试数据清理**：产品/工序 DELETE 200 软删（批量删按名前缀）；生产计划带批次 **DELETE 404 删不掉**（cancel 端点也无），残留 SAFETY_STOCK 测试计划无害。清理脚本模式见 scratchpad（按 `/^(PREFIX)-/` 匹配 `/product-types/options` + `/work-processes/active` 批量删）。

### 部署铁律（改后端必读）
- 干净 worktree off origin/main：`git worktree add -b feat/X ../cretas-X origin/main`。commit 前 `git fetch + rebase origin/main`（常并发），`git commit -- <files>` 锁 scope。
- 后端 blue-green：`bash scripts/deploy/deploy-backend.sh --env prod`（切后 green active/blue inactive 正常，Java 启动 ~80s 轮询 health）。
- web-admin：`echo YES-PROD | bash scripts/deploy/deploy-web-admin.sh --env prod`（PROD prompt 要 piped）。**部署后 grep served bundle 含改动唯一串**别只看 exit0（deploy-web-admin vite build 失败仍可能 exit0）。
- Flyway：smartbi/主库 schema 变更走 migration 文件，别裸 psql（见 server-operations.md）。多 SKU 若加表/字段注意版本号别撞。

### 复用的验证脚手架（scratchpad，可参考重写）
`D:\Temp\claude\...\<session>\scratchpad\` 下：`workflow-fullflow-verify.mjs`（4步合流全流程报工+小结+断言25盒，PASS）、`zhuti-multisku-test.mjs`（23节点3成品分支图，暴露 workflow-config=0 的 gap）、`ui-selection-setup.mjs`（2步建台供 headed 验防呆）。这些是 API E2E 模板（login/api helper/建 workflow def/publish/activate/plan/batch/report/settle 全套），新 chat 直接抄骨架。

### 核心设计悬念（新 chat 要先定，可先跟 Steve 确认一句）
**共享上游 + 分支下游怎么建模**：一次 `拆包分切` 投料喂 3 个成品链。是
(a) **raw-centric 生产运行**：计划/运行按原料（整张图）一次跑，逐道报工覆盖全图，各终端各自入成品库 —— 最贴 Steve"以原料为主"，但改 plan 模型（现在 plan.productTypeId=单成品）；还是
(b) 保持按成品建计划，但让 materialize 抽取"到该成品的路径子图"，共享上游工序**跨兄弟计划去重只报一次/只扣一次料**（防重复扣原料）。
(a) 更干净更符合客户心智，推荐先探 (a)。
