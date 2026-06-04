# Handoff → Codex：餐饮多轮 Phase 2a 门店实体续接（写实现计划 + 实现）

**日期**: 2026-06-04
**交接人**: Claude（已完成 brainstorm→spec→对抗审计；停在 writing-plans 中途）
**你的任务**: (1) 依据已审定的 spec 写出 TDD 实现计划；(2) 按计划实现 Phase 2a；(3) PR→合 main→从 main 部署 prod→真实会话验收。

---

## 0. ⚠️ Codex 看不到的文件（Claude-only），关键内容已在本文件内联

以下文件在 Claude 的 plugin/session 目录，**Codex 无法打开**。其关键结论我已抽取进本文件，无需你访问；列出仅供存档：

| 文件（Claude-only 路径） | 内容 | 本文件对应章节 |
|---|---|---|
| `D:\Temp\claude\C--Users-Steve-my-prototype-logistics\9668be83-...\tasks\wgmdfhm3w.output` | 8-agent 对抗审计原始结果（DC1-DC10 + R1-R6 + file:line） | §3 已内联 |
| `C:\Users\Steve\.claude\projects\C--Users-Steve-my-prototype-logistics\memory\project_2026_06_04_restaurant_multiturn_context_shipped.md` | Phase 1 复盘 + prod 验证账号/端口/表 | §5 已内联 |
| `C:\Users\Steve\.claude\plugins\cache\...\superpowers\5.1.0\skills\writing-plans` / `subagent-driven-development` | 计划格式 + 执行法 | §6 已内联 |

**Codex 能看到的（在 repo / worktree 内）**:
- 已审定 spec：`docs/superpowers/specs/2026-06-04-restaurant-multiturn-phase2a-store-coref-design.md`（在分支 `feat/restaurant-store-coref-p2a`，已 commit `e95fbb23f`，**未 push 未合 main**）。**这是权威设计，先读它。**
- Phase 1 交接：`docs/handoff/2026-06-04-restaurant-multiturn-context-handoff.md`（已在 main）。
- Phase 1 spec/plan：`docs/superpowers/specs/2026-06-04-restaurant-multiturn-context-design.md`、`docs/superpowers/plans/2026-06-04-restaurant-multiturn-context.md`（已在 main）。
- 验证探针：`scripts/qa-multiturn-context-probe.sh`（已在 main）。

---

## 1. 本会话已完成（已 shipped prod + live 验证）

| 项 | PR | 内容 | 状态 |
|---|---|---|---|
| Phase 1 持久化 | #510 | `executeWithExplicitIntent` 短路汇聚点补 `persistConversationMemoryForExplicitIntent`（建行+写 lastIntentCode）+ `CONTINUATION_BARE_TIME` 词表扩展 | 合 main + 部署 + live 验 |
| Phase 1 顺序修复 | #513 | `maybeAugmentContinuation` 移到 `preprocess` 前对 RAW userInput 判定（修时间词被展开成日期区间致续接失效） | 合 main + 部署 + live 验 |
| 残留#1 近N个月 | #518 | `GoldBackedRestaurantTool.parseNlTimeWindow` 加 `NL_LAST_N_MONTHS`（近3个月→窗口收窄） | 合 main + 部署 + live 验 |

**Phase 1 + 残留#1 已全部在 origin/main 且 prod 运行。** 当前 prod 活跃端口 = **green 10020**（蓝绿会随每次部署翻转，部署后以 health 探测为准）。

**本任务 Phase 2a**：spec 已写已审，分支 `feat/restaurant-store-coref-p2a` 有 spec commit，**代码一行未动**。从这里开始。

---

## 2. Phase 2a 要做什么（一句话）

第二轮「门店指代 + 门店指标」续接：`哪家店业绩最好` → `那家店的客单价呢`（或裸 `这家的客单价呢`）→ 返**那家(#1)门店**那一行的 营收/单数/客单价。复用现有门店排行数据，**不新建 gold ETL**。

案例3 `它的趋势`（=该菜月度趋势，需新 gold）= Phase 2b 另起，**不在本任务**。

---

## 3. 对抗审计结论（已内联，写计划必须遵守）

审计判 **NEEDS_REWORK**（初稿 6/7 维度不可建），修订后结论已并入 spec。关键 file:line（均已核对真实代码）：

- **客单价非 gold 字段**：Python `top_stores` 行只含 `{store_id, store_name, revenue, bill_count}`（`backend/python/smartbi/gold/queries.py:808-816`）；客单价由 Java `RestaurantStoreRevenueRankGoldTool.deriveAvgTicket`（静态法，line 138-146）现算。→ revenue+bill_count 已在行内，无需 ETL。
- **rank 工具无 store_name 过滤、top-N 写死 5**：`getParametersSchema` 仅 `month`（line 43-56）；`queryGold` 调 `gold.fetchFinanceSummary(factoryId,start,end,5)`（line 64）。
- **Java format 丢弃 store_id**：`format()` 循环（line 84-92）只 put 门店/营收/单数/客单价，丢了 `row.get("store_id")`（Python 有返）。
- **EntitySlot 无 STORE**：`EntitySlot.java:29-50` 枚举无 STORE。
- **实体捕获用 resultData 不用 affectedEntities**：affectedEntities 是写审计字段（action=CREATED/...），0 工具填；`mapEntityTypeToSlotType` 无 STORE（default→null）；resultData 兜底只读 `content`/List 不读嵌套；`extractSlot` 只 set `.id()` 不 set `.name()`。
- **客单价路由已对**（交接文档"→ORDER_STATISTICS 错"是过期信息）：客单价 → `RESTAURANT_STORE_REVENUE_RANK`（phrase `IntentKnowledgeBase.java:7080` + keyword 迁移 `V20260917_01`）。**新风险 R1**：客单价 同时是 `INDICATOR_QUERY/SMART_INDICATOR_QUERY` 关键词（`V20260823_04:25`、`V20260825_07:41` AVG_TICKET_PRICE）→ 必须加回归断言 post-coref `<门店名>的客单价呢` 真路由到 STORE_REVENUE_RANK 而非 INDICATOR。
- **两套 coref 串行（见 §4 完整链路）**。

---

## 4. ✅ 完整代码触点链路（我已逐文件读过，权威，按此写计划）

**worktree**: `C:/Users/Steve/cretas-nmonth`（分支 `feat/restaurant-store-coref-p2a`，off origin/main）。所有路径相对 `backend/java/cretas-api/src/main/java/com/cretas/aims/`（除 Python）。

### A. 第一轮：门店实体 → STORE 槽
1. **`dto/conversation/EntitySlot.java`**：`SlotType` 枚举（line 29-50）加 `STORE`；加工厂方法 `store(String id, String name)`（仿 `supplier()` line 115-125，displayValue `"门店 "+name`）。
2. **`ai/tool/impl/restaurant/gold/RestaurantStoreRevenueRankGoldTool.java`**：
   - `format()` 循环（line 84-92）每行加 `entry.put("store_id", row.get("store_id"))`。
   - `format()` 末尾（line 119-130 result 区）加 `result.put("top_store", <#1 行: {store_id,门店,营收,单数,客单价}>)`（取 storeRank.get(0)）。
   - `getParametersSchema`（43-56）加可选 `store_name`（string）。
   - `queryGold`（60-65）：top_n `5`→ 调大（`fetchFinanceSummary(...,50)`），覆盖全部门店（~19）。
   - `format()`：当 `goldResult`/params 带 `store_name` 时，过滤 `storeRank` 到该店一行（按 store_id 优先、名次之；找不到→优雅空，复用 `emptyMessage`）。注意 format 的入参是 goldResult，store_name 需经 queryGold/doExecute 透传，确认 `params` 可达 format（可能要把 store_name 放进 goldResult 或调整 format 签名——读 `GoldBackedRestaurantTool` 模板方法 line 129-167 确认）。
3. **`service/execution/IntentExecutionOrchestrator.java`** `mapEntityTypeToSlotType`（~line 1612-1622）：加 `case "STORE" -> EntitySlot.SlotType.STORE;`。
4. **同上** `extractAndUpdateEntitySlots`（~line 1553-1593）+ `extractSlot`（~1595）：加分支读 `response.getResultData()` 的 `top_store` map → `EntitySlot.store(id,name)` → `conversationMemoryService.updateEntitySlot(sessionId, STORE, slot)`。`extractSlot` 现只 set `.id()`，STORE 要 set name+id。
   - **协同**：Phase 1 的 `persistConversationMemoryForExplicitIntent` 已让 `updateConversationMemory`→`extractAndUpdateEntitySlots` 在短路路径也跑（门店排行走短路），故 STORE 槽会被持久化。

### B. 第二轮：那家店 → 门店名 + 注入工具
5. **`service/impl/ConversationMemoryServiceImpl.java`**（文本替换，让 resolved 含门店名）：
   - 加常量 `STORE_REFERENCE_PATTERN = Pattern.compile("那家店|这家店|该店|那个店|这个店|该门店|那家|这家")`（放 line 60-100 区）。
   - `resolveReference`（line 234-245）：在 SUPPLIER 行（238）**之前**加 `result = resolvePatternReference(result, STORE_REFERENCE_PATTERN, slots, EntitySlot.SlotType.STORE.name());`。
   - **天然槽位门控**：`resolvePatternReference`（251-259）只在 `slots.containsKey("STORE")` 时替换 → STORE 槽不存在（工厂租户）则该行 no-op，SUPPLIER 照常 → **工厂 CRM 零回归**（解审计 R3 + 用户的"业态感知"诉求）。STORE 先跑+`replaceFirst` 移除"那家"，SUPPLIER 行不再二次命中。
6. **`service/impl/QueryPreprocessorServiceImpl.java`** `getReferencePatterns`（line 791-806，switch）：加 `case STORE: return new String[]{"那家店","这家店","该店","那家","这家","那个店","这个店","该门店"};`。
   - 这样 `detectResolvedReferences`（761-786）在 STORE 槽存在 + 原文含门店指代 + resolved 含门店名时，自动产出 `ResolvedEntity{entityType="store", id, name}` → 进 `PreprocessedQuery.resolvedReferences`（line 261-272）。
7. **`service/execution/ToolDispatchService.java`** 注入 switch（line 192-208，`switch(ref.getEntityType().toUpperCase())`）：加
   ```java
   case "STORE":
       params.put("store_name", ref.getEntityName());
       if (ref.getEntityId() != null) params.put("store_id", ref.getEntityId());
       break;
   ```
   工具据此过滤（见 A.2）。

### C. 第一套 coref 服务是否要改（待你在真实路径核实）
- **`service/impl/CoreferenceResolutionServiceImpl.java`** 先于第二套跑（`IntentRecognitionPipelineServiceImpl.java:~535`）。`inferSlotType`（476-490）对"那家店"返 null（无 store 分支），且槽位兜底（line 457 `if type==PROXIMAL||PRONOUN`）**跳过 DISTAL** →「那家」(DISTAL) 不被这套处理 → 应**原样透传**"那家店的客单价呢"给第二套。
- **决策**：优先**不改**第一套（最小、最低风险）。先在真实路径核实第一套确实原样透传（审计 R5：确认它不在第二套前误改文本）。若发现它误改/吞掉门店指代，再按审计 DC4ii 给 `inferSlotType` 加 店→STORE + 给 line 457 fallback 加 `DISTAL`。**以第二套（QueryPreprocessor→ConversationMemoryService→ToolDispatchService）为注入工具引用的单一事实源。**

### D. 防呆（fool-proof-design Rule 5）
8. 识别到门店指代（`getReferencePatterns(STORE)` 命中输入）但**无 STORE 槽**（新 session 直接问 / 第一轮 0 门店）→ 返 clarification `请问您指的是哪家店？`（可列上一轮门店），**不**静默走全局 top-N。挂点：orchestrator 走到 store-metric 意图前判断（具体位置写计划时定，参考已有 clarification 分支如 `buildNegationVetoClarificationResponse`）。

---

## 5. prod 真实验证（必须 live，配方已内联）

**账号/端口/表（来自 Phase 1 memory，Codex 看不到该 memory，已内联）**：
- 登录：`POST http://localhost:<PORT>/api/mobile/auth/unified-login`，body `{"username":"qhj_sales_mgr","password":"123456","deviceInfo":{"deviceId":"x","platform":"Web"}}` → 取 `data.token`。
  - `qhj_sales_mgr`（sales_manager，RES_3101_009，有营收分析权限）。**勿用 `qhj_prod`**（factory_super_admin，密码被改过疑真人接管，未碰）。其余兄弟账号 `qhj_finance_mgr/qhj_warehouse_mgr/qhj_operator` 也都是 `123456`。
- 执行：`POST http://localhost:<PORT>/api/mobile/RES_3101_009/ai-intents/execute`，header `Authorization: Bearer <token>`，body `{"userInput":"...","sessionId":"..."}`。
- 端口：蓝绿 10010/10020，先 `curl -s -o /dev/null -w '%{http_code}' http://localhost:10020/api/mobile/health` 探活跃端口。
- DB：`PGPASSWORD=cretas123 psql -h 127.0.0.1 -U cretas_user -d cretas_prod_db -tAc "SELECT entity_slots FROM conversation_memory WHERE session_id='<sid>';"`（写库用户 `cretas_user`/`cretas123`；conversation_memory 表，列 `last_intent_code`/`entity_slots` jsonb）。
- **必须在服务器 localhost 跑**（本地→47 的 Java 端口仅对 nginx 网关开放 + 国内 ISP 对长 stream RST）：`ssh root@47.100.235.168 'bash -s' <<'EOF' ... EOF`。
- 日志：`/www/wwwroot/cretas/logs/cretas-backend.log`（注意 `[X1-Continuation]` 等日志行前缀是 request-id 不是 sessionId，别按 sessionId grep 那条）。
- 复用 `scripts/qa-multiturn-context-probe.sh` 的写法（它接 `CRETAS_JWT`；自登录版照上面 login 配方）。

**Phase 2a 验收判据**（写进计划的 live 步骤）：
1. 第一轮 `哪家店业绩最好` 后，DB `conversation_memory.entity_slots` 含 STORE（门店名+id）。
2. 第二轮 `那家店的客单价呢` / `这家的客单价呢`：日志/响应显示 `store_name` 参数到达工具；返**#1 门店那一行**的 营收/单数/客单价（非全局列表）；`intentCode = RESTAURANT_STORE_REVENUE_RANK`（**非 INDICATOR_QUERY/SMART_INDICATOR_QUERY**，验 R1）。
3. 无 STORE 槽（新 session 直接问 `那家店的客单价`）→ 反问 `请问您指的是哪家店？`。
4. 工厂租户 SUPPLIER coref 零回归。

---

## 6. 构建 / 测试 / 部署 / 流程机制

- **Maven 不在普通 PATH**：用 `& "C:\tools\apache-maven-3.9.6\bin\mvn.cmd" "-Dtest=Foo,Bar" test`（PowerShell），或项目 wrapper `backend/java/cretas-api/mvnw.cmd`。在 `backend/java/cretas-api` 目录跑。
- **回归硬闸**：`IntentParityTest`（70）+ `IntentGoldenAssertionTest`（15）必须绿（它们 sessionId=null，本改动不应触达）。
- **现有相关测试**（参考写法）：`service/intent/impl/IntentContinuationAugmentTest.java`、`ai/tool/impl/restaurant/gold/GoldBackedRestaurantToolTimeTest.java`、`service/execution/IntentExecutionOrchestratorMemoryPersistTest.java`、`service/execution/IntentExecutionOrchestratorNegationTest.java`（20-arg 构造器 mock 范式）。coref 服务**今天无单测**，需新建。
- **部署（只从 main）**：合 main 后 `git fetch origin main` → `git worktree add ../cretas-deploy-X --detach origin/main` → `cd` 进去 → `bash scripts/deploy/deploy-backend.sh --env prod`（脚本自带 mvn PATH + R2/OSS 凭证 source ~/.bashrc + 蓝绿切换 + 健康检查）。部署后核对运行 jar：`ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar 'BOOT-INF/classes/.../Xxx.class' | strings | grep -c '<标记>'"`。
- **Flyway 撞号**：本任务若加 keyword/路由迁移，合 main 后部署前 `git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d`（origin/main 现 ≥ V20260917_01；out-of-order=false，低号后合会静默跳过）。
- **CI**：PR 后 `java-build-test`/`e2e-pr-gate`/`rn-test` 是相关闸；`python-lint-test`(flake8)/`vue-build-check`(vitest) 是**预存无关红**（本 Java 改动不触），可 `gh pr merge <#> --squash --admin --delete-branch=false` 跳过（项目惯例）。
- **并发安全**：单文件 commit 用 `git commit -m "..." -- <file1> <file2>`；commit 前 `git status --short` 防并发 session 污染。

---

## 7. 硬规则（.claude/rules，必守）

- **worktree 隔离 + 只从 main 部署 prod**（`worktree-and-main-only-deploy.md`）：永远 off `origin/main` 开 worktree；prod 只从 main 部署（绝不从 feature 分支）；多 session 从 feature 分支部署会 last-write-wins 互相覆盖。
- **防呆设计**（`fool-proof-design.md`）：错误/空状态必带 next action（本任务 D 项）。
- **禁止降级/假数据**；统一响应 `{success,data,message}`；字段 camelCase(JSON)/snake_case(DB)。
- **TDD**：测试先行、小步提交。

---

## 8. 当前 git 状态

- worktree `C:/Users/Steve/cretas-nmonth` 在分支 `feat/restaurant-store-coref-p2a`（off origin/main `e521891fb`），含 spec commit `e95fbb23f` + 本 handoff（待你 commit）。**未 push 未合 main，代码未动。**
- 其它本会话 worktree（cretas-multiturn / cretas-deploy-* / cretas-nmonth 的旧分支）已清理。
- 你可继续用本 worktree，或 `git fetch origin main && git checkout -b feat/restaurant-store-coref-p2a-impl origin/main` 重开（注意把 spec/handoff 带过去，或直接在本分支上干）。

---

## 9. 写计划时的开放决策（需你定/核实）

1. **第一套 coref 服务改不改**（§4.C）：默认不改，先 live 核实它原样透传"那家店"；若误改再按 DC4ii 改。
2. **store_name 如何透传到 format()**（§4.A.2）：读 `GoldBackedRestaurantTool` 模板 `doExecute`/`queryGold`/`format` 签名（line 129-167），决定把 store_name 放 params→goldResult 还是调整 format 取 params。
3. **防呆 clarification 挂点**（§4.D）：orchestrator 哪一步判断"门店指代但无槽位"。
4. **是否需要 keyword/路由迁移**：客单价 路由已对（§3），大概率不需要新迁移；若加则注意 Flyway 撞号（§6）。

先读 spec（§0 路径）+ 本文件 §4 触点图，再写 `docs/superpowers/plans/2026-06-04-restaurant-multiturn-phase2a-store-coref.md`（TDD、每任务 file:line+完整代码+测试命令+预期、频繁提交），然后实现→PR→合 main→部署→§5 live 验收。
