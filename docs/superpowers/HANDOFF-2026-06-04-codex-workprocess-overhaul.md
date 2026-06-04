# HANDOFF → codex：工序配置 UX+AI 化 overhaul（接 Claude 的进度，做完剩下的）

> 你（codex）接手"工序/产品工序配置 + 生产计划链路"改造的剩余部分。Claude 已完成设计 + 批 A + 批 B/C（待合并部署），剩 **合并部署 B/C** 和 **建批 D（4 个 AI 功能）**。本文件含：当前状态、必读文件（含 Claude 才能看到的路径）、prod 访问/部署手册、关键坑、D 的集成事实。

---

## 0. 先读这些（in-repo，已在 main 或 feat/wp-config-bcd 分支）

| 文件 | 在哪 | 内容 |
|---|---|---|
| `docs/superpowers/specs/2026-06-04-workprocess-config-ux-ai-design.md` | main（#519 已合） | **总设计 A-D + E 决定**。最重要，先读。 |
| `docs/superpowers/plans/2026-06-04-workprocess-config-batch-a.md` | main | 批 A 实施计划（已完成，参考体例） |
| `docs/superpowers/specs/2026-06-04-workprocess-teamleader-assignment-design.md` + `plans/...` + `HANDOFF-2026-06-04-workprocess-assignment.md` | main | 前置功能"工序-小组长分配"（已上线），背景 |

**B/C 代码在分支 `feat/wp-config-bcd`（PR #523，未合并）**：`git fetch && git checkout feat/wp-config-bcd` 看改动；或 `git diff origin/main...feat/wp-config-bcd`。

## 0b. 只有 Claude 能看到、codex 需自己去读的文件（本机文件，给你路径）

- **Claude 记忆库（关键背景/账号/坑全在这）**：`C:\Users\Steve\.claude\projects\C--Users-Steve-my-prototype-logistics\memory\MEMORY.md`（索引）+ 同目录下 `project_2026_06_04_workprocess_teamleader_assignment.md`、`feedback_*.md`、`reference_prod_no_real_customers_yet.md` 等。**这些不在 git 里，codex 若能读本机磁盘就去读；读不到的话本文件 §3/§4 已把关键事实内联。**
- **项目规则**：`.claude/rules/*.md`（在 git 里）—— 尤其 `worktree-and-main-only-deploy.md`、`concurrent-edit-safety.md`、`fool-proof-design.md`、`server-operations.md`、`ai-intent-tool-skill-architecture.md`、`database-entity-sync.md`。**必读**。
- Claude 的 subagent 探查/构建 transcript 在 `D:\Temp\claude\...\tasks\*.output`（JSONL，巨大，别整读）——其关键结论已内联到本文件 §5（D 集成）。

---

## 1. 当前状态（2026-06-04）

- **批 A（卡流程 bug + 生产计划页）= 完成 + 已部署 prod**。PR #519 已 squash-merge main（`437b864ae`），web-admin 已部署 8086。含：A1 配责任人发整行修 @NotBlank 400 / A2 弹窗点外不丢 / A3 计划页工序只读 / A4 订单单行自动匹配+产品类型锁定 / A5 批次日期默认今天 / A6 移除"生成工序任务"按钮 / E2 指派主管非必填 / E3 计划日期改"计划生产日"(batchDate 被后端消费，保留两字段+加说明)。**A1 已 prod 实证**（旧部分体→400，新整行→200）。
- **批 B+C = 已写完 + 已提交 + PR #523（未合并、未部署）**。分支 `feat/wp-config-bcd`，3 commit：
  - `27e9f2ffb` 批 C 产品工序配置 UX：C4 草稿/显式保存（本地 pendingOps + "保存"按钮无变化时禁用 + 有改动/已保存指示 + 切产品/刷新前确认）/ C2 左右两栏 / C3 责任人下拉 filterable 搜索 / C1 原生 HTML5 拖拽排序（保留上下移）。**全在 `web-admin/src/views/system/product-processes/index.vue`**。
  - `4f3a8cedc` 批 C5 重复工序检测：查重升级(名+类别+单位) + `GET /work-processes/duplicates` 端点 + 工序管理页"检测重复工序"面板(停用/启用)。后端 `WorkProcessServiceImpl/Repository/DTO/Service/Controller` + `web-admin/.../work-processes/index.vue` + `processProduction.ts`。8 单测。
  - `557f98f2d` 批 B 财审：B1 财审通过慢→`SupplyChainOrchestrator.onSalesOrderFinanceApproved` 加 `@Async`（同步事件阻塞响应）/ B2 成本=0→`>0` 守卫+缺数据诚实显示'-'(禁假数据) / B3 售价趋势对比。`SalesServiceImpl/Service/Controller/SalesOrderItemRepository/SalesPriceTrendDTO(新)/SupplyChainOrchestrator` + `web-admin/.../sales/finance-review/detail.vue` + `salesFinanceReview.ts`。10 单测。
  - **验证**：web-admin `npm run build` ✓(4036 modules)；backend 36 单测 ✓；CI #523 **java-build-test ✓ / rn-test ✓ / vue-build-check 的 Build+Type-check ✓**；红的是 pre-existing（python-lint-test[本 PR 无 py]、vue 的 Vitest[smart-bi Dashboard `default.connect` echarts mock，与本改无关]、e2e-pr-gate）。
  - **B/C/D 全程无 DB 迁移**（用现有列），无 Flyway 撞号风险。
- **批 D（4 个 AI 功能）= 未开始**（设计 + 集成探查已做，见 §5）。

### 你（codex）要做的
1. **合并 + 部署 B/C**：admin-merge PR #523（跨过上面 3 个 pre-existing 红，它们非本 PR 引入）→ 从 **main** 部署 backend + web-admin（见 §2）。
2. **建批 D**（D1-D4，见 §5 集成事实 + 设计 §5）→ 合并 → 部署 → 验证。
3. 全部完成后让 Steve 按完整流程（订单→财审→计划→转批次→RN 报工）测。

---

## 2. prod 访问 + 部署手册（务必照做）

### 访问
- **web-admin prod**：http://139.196.165.140:8086 （test：8097）。登录 `f006_admin` / `123456`（factory_super_admin，权限 `*:*`，能做全部步骤）。
- **API 网关**：`http://139.196.165.140:8086/api/mobile/*`（路由到活跃的 Java 实例；对 localhost CORS 反射任意 origin）。
- **Java prod 蓝绿**：blue=10010 / green=10020，**活跃端口随部署轮换**（查：`ssh root@47.100.235.168 "curl -s -o /dev/null -w '%{http_code}' http://localhost:10010/api/mobile/health"` 哪个 200 哪个活）。
- **prod DB**：`ssh root@47.100.235.168`，然后 `PGPASSWORD=cretas123 psql -h 127.0.0.1 -U cretas_user -d cretas_prod_db`（owner=**cretas_user** 不是 cretas）。
- **账号**（密码均 `123456`）：主管 `f006_admin`(1309)/`f006_workshop`(1310)/`f006_dept_admin`(1563)；小组长(operator) `f006_moyun`(1615)/`f006_weizj`(1616)/`f006_xushifu`(1617)/`f006_yongzhen`(1618)/`f006_worker1`(1311)。
- **产品 id**：猪舌=`4e345886-52e4-494a-bcb3-3f0ee9e126b2`、掌中宝=`1d7fbd73-8797-4933-83f1-46413a45992d`、牛腱=`c2974690-4ac7-4c17-9ad4-5ee5b12bb26c`。
- Maven：`C:/tools/apache-maven-3.9.6/bin/mvn.cmd`。

### 部署（HARD RULE：只从 main 部署，绝不从 feature 分支）
```
# 1) 先把分支合进 main（admin-merge 跨过 pre-existing 红）
gh pr merge 523 --squash --admin
# 2) 开/切到一个 off origin/main 的 worktree（含本次合并）
git fetch origin main && git worktree add --detach ../cretas-deploy origin/main   # 或复用现有
cd ../cretas-deploy
# 3) 部署（prod 需要交互确认 → 管道喂）
bash scripts/deploy/deploy-backend.sh --env prod          # Java：mvn 打包→上传→蓝绿切→健康检查
cd web-admin && npm install --prefer-offline --legacy-peer-deps && cd ..
echo "YES-PROD" | bash scripts/deploy/deploy-web-admin.sh --env prod   # web-admin：vite build→上传 139:8086
# RN（若动 RN）：source ~/.ota-env && bash scripts/ota/push-bundle.sh production android
```
**部署后必核**运行中的 jar/前端确含你的改动（见 `.claude/rules/worktree-and-main-only-deploy.md`）。

### 现成 worktree（可复用，别在主目录干活）
- `C:/Users/Steve/cretas-wp-overhaul` —— 当前在 `feat/wp-config-bcd`（B/C 代码 + web-admin node_modules 已装）。
- `C:/Users/Steve/cretas-rn-test` —— main，RN（Expo 曾跑 localhost:3011，指向 prod）。
- 主目录 `C:/Users/Steve/my-prototype-logistics` 在别的 feature 分支、被多 session 共享，**别在它上面改/部署**。

---

## 3. 必知的坑（血泪，违反会出事）

1. **`@PreAuthorize` 在本仓库是 NO-OP**（SecurityAutoConfiguration 全 profile excluded）。要限角色用自定义 **`@RequireRole({"factory_super_admin",...})`**（`com.cretas.aims.config.RequireRole`）。
2. **`SecurityUtils`/`SecurityContextHolder` 是死的**（同上）。取当前角色：controller 用 `@RequestAttribute("role") String role`；service 用 `RequestContextHolder.getRequestAttributes().getAttribute("role", RequestAttributes.SCOPE_REQUEST)`（JwtAuthInterceptor 设的 "role" 属性）。
3. **更新 ProductWorkProcess 必须发整行**（`ProductWorkProcessDTO` 对 `productTypeId`+`workProcessId` 有 `@NotBlank`，update 用 `@Valid`）——只发 `{responsibleWorkerId}` 会 400（A1 的根因，已修）。
4. **两套工序任务模型别搞混**：`WorkProcessTask`（Long id，有 `assigned_to`，逐道报工/出成率链，由"转为批次"自动 spawn）vs `ProductTask`/`ProcessTask`（String id，无 assigned_to，`/normal` 需审批链）。本功能=前者。**别用"生成工序任务"按钮**（已删，那是后者）。
5. **转批次自动 spawn**：`ProductionPlanServiceImpl`(约 :1413-1418)转批次时从 `product_work_processes` spawn `work_process_tasks`，`assigned_to` = `responsibleWorkerId`（fail-soft，未配工序不阻塞建批次）。
6. **并发编辑安全**：多 agent 别改同一文件（会互相覆盖）；commit 用 `git commit -- <paths>` 锁范围；commit 前 `git status` 看暂存区。
7. **Flyway 跨 session 撞号**：加迁移前 `git ls-tree -r origin/main .../db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | tail` 取最高+1（**B/C/D 无迁移**，但 D4 若加列要注意）。
8. **CI admin-merge over pre-existing 红**：python-lint-test（无 py 改动时）、vue 的 Vitest（smart-bi Dashboard echarts mock）、e2e-pr-gate 经常 pre-existing 红；真正闸门是 java-build-test / rn-test / vue 的 Build+Type-check。
9. **禁假数据/禁静默失败**（项目铁律）：缺数据诚实显示（如 B2 的 '-' + actionHint），不要塞 0/假值。
10. **headed 验证 web-admin**：el-select 会被 sticky header 拦点击；用 `browser_evaluate` 直接打 API 验证（A1 就是这么实证的）。prod 目前**无真实客户**（pre-launch），部署/测试相对安全。

---

## 4. 待办决定（E，Steve 已拍板，已在批 A 落地，仅供理解）
- E1 计划数量=**保留**。E2 指派主管(大组长)=**保留但非必填**。E3 计划日期=**改"计划生产日"**，batchDate 被后端消费(ProductionProgressDashboard 按批次日期过滤 + CUSTOMER_ORDER 校验)→**保留两字段+加 tooltip 区分**。

---

## 5. 批 D 实施事实（4 个 AI 功能；集成已探查，照此建）

**总原则**：复用现有 Tool-Skill + CanvasAIController + AIChatPanel + 现有 WorkProcess 字段，**不另起 LLM 栈**。设计见总设计文档 §5。

### D1 产品工序配置页 自然语言配工序 + D3 工序管理页 自然语言增改工序
- **后端 AI chat 端点（通用，可复用）**：`POST /api/mobile/{factoryId}/config/v2/ai/chat`（`CanvasAIController.java:98`）。请求 `AIRequest{message, mode:"autopilot|plan|action", moduleCode}`；响应 `AIResponse{reply, diffs:[{tool,params,description,type}], applied}`。Plan 模式确认用 `POST .../config/v2/ai/apply-diffs`（`@RequireRole({factory_super_admin, permission_admin})`）。**它只认 `canvas_*` 前缀工具**（CANVAS_TOOL_PATTERN）。
- **新工具**：`ProductWorkProcessConfigTool`（D1）/ `WorkProcessCatalogTool`（D3）extends `AbstractBusinessTool`（`ai/tool/AbstractBusinessTool.java`，实现 `getToolName/getDescription/getParametersSchema/doExecute`，可选 `doPreview` 走 Plan 模式）。`@Component` 自动注册到 `ToolRegistry`。样板：`ai/tool/impl/workprocess/WorkProcessTaskSpawnTool.java`。**工具名建议 `canvas_` 前缀**以走上面端点（或评估走通用 IntentExecutor 路径 `IntentExecutorServiceImpl.execute`）。doExecute 里调 `ProductWorkProcessService.create/update`（D1）/ `WorkProcessService.create/update`（D3）。prompt 喂入本厂工序 catalog + 小组长名单，把 NL 映射到工序+人，找不到的工序提示新建。
- **前端**：`AIChatPanel.vue`（`web-admin/.../canvas-editor/components/AIChatPanel.vue`，props `factoryId/selectedModule`，emit `applyDiff`）**硬编码了 canvas 端点 → 需做个参数化 wrapper 或 `WorkProcessAIChatPanel.vue`**，挂到 `product-processes/index.vue`(D1)/`work-processes/index.vue`(D3)。D1 出的草稿走 C4 的 pendingOps 预览再提交。
- **语音**：web 端先纯文字/浏览器 SpeechRecognition；真机 RN 走 iflytek（P2，可后做）。

### D2 新建产品后推荐工序链
- **Hook**：`ProductTypeServiceImpl.createProductType`（`:57`，save 在 `:117` 后）加 post-create 推荐（或前端 create 成功后异步取）。
- **相似产品**：`ProductType` **无显式相似字段**，用 `productCategory`/`unit`/`temperatureZone` 隐式匹配。查 `ProductWorkProcessRepository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc` 跨相似产品聚合工序链（按频次打分），Top-3-5 推荐。冷启动(无历史)→ LLM 据产品名/类别给"建议"链(标注"AI 建议，请核对")。用户进配置页见预填草稿(C4)，改即可。
- 新工具 `ProductWorkProcessRecommendTool` 同 D1/D3 模式。

### D4 出成率上下限/标准时薪 自动计算
- **字段已存在**（`WorkProcess`）：`standardYieldMin/Max`(BigDecimal 6,4)、`standardHourlyRate`(8,2)、`outputUnit`、`needsInput`。**无需迁移**。写回用 `WorkProcessService.update(factoryId, id, dto)`（`WorkProcessServiceImpl:117`，仅 set 非 null；校验 min<max）。
- ⚠️ **现成 `YieldAnalysisService.aggregateByProcess(factoryId,start,end,productTypeId)` 只给单一平均 `conversionRate`（`ProcessYieldAggDTO`：inputQuantity/outputQuantity/conversionRate/wastageRate/unitComparable/batchCount），不给分布**。出成率**上下限需要按批/按报工采样算 P20-P80** → 要**新写**一个按 `workProcessId` 分组、取每批 input/output 算 per-batch 出成率、求分位数的聚合（数据源 `ProductionReport`：inputQuantity/outputQuantity/totalWorkMinutes/totalWorkers/laborCost + workProcessTaskId/processOrder）。标准时薪由 laborCost/(workMinutes/60×workers) 推。
- **数据不足（<N 批，如 3）→ 不算，留手填**；写回为"系统推算值"，UI 标注"自动/手填"来源(手填优先)。不需要这些字段的工序(按 needsInput/类别)隐藏。
- **定时任务**：照 `scheduler/AccountingPeriodScheduler.java` 的 `@Scheduled(cron)`+`@SchedulerLock`+按工厂循环+try/catch 计数 模式，新建 `YieldStandardCalculationScheduler`（如每日 03:00）。

### D 并行/文件边界（防并发覆盖）
- D1 改 `product-processes/index.vue`（**已被 C4 大改，串行接着改**）+ 新后端工具。D3 改 `work-processes/index.vue`（已被 C5 改）+ 新后端工具。D2 后端 hook+新工具+`products/index.vue`。D4 后端聚合+scheduler+写回（UI 标注可碰 work-processes/product-processes）。**同文件串行，不同文件可并行 subagent**。

---

## 6. 验收（D 建完后，Steve 跑完整流程）
web-admin(f006_admin)：系统管理→工序管理(AI 增改工序/查重) + 产品-工序配置(AI 配工序/草稿提交/拖拽/搜索) → 新建产品(D2 推荐) → 订单→确认→财审(B 性能/成本/趋势)→计划→**转批次(自动分派)** → RN(localhost:3011, 4 小组长各报各的工序)。

---

## 7. 一句话给 codex
"读总设计 doc + 本 handoff + `.claude/rules/*` + Claude memory(§0b 路径)。先 admin-merge #523 并从 main 部署 B/C；再按 §5 建 D1-D4(复用 CanvasAI/Tool-Skill，D4 要自写按批分位聚合)；同文件串行、跨文件并行 subagent；只从 main 部署 prod；遵守 §3 的坑。"
