# 设计 — 餐饮 AI chat 多轮上下文继承 (Phase 1)

**日期**: 2026-06-04
**分支**: `feat/restaurant-multiturn-context` (off `origin/main`)
**接续**: `docs/handoff/2026-06-04-restaurant-multiturn-context-handoff.md`（#494/#495 餐饮 chat QA 修复唯一 defer 项）
**关联 memory**: `project_2026_06_04_restaurant_chat_qa_fixes`, `feedback_intent_gate_must_cover_all_execution_paths`, `feedback_self_evidence_disqualified_cross_verify_required`, `feedback_worktree_main_only_deploy`

---

## 1. 要解决的问题（用户面）

餐饮 chat 多轮对话，第二轮的"纯时间续接"应继承上一轮的话题：

| 第一轮 | 第二轮 | 期望 | 修前现状 (prod 实测) |
|---|---|---|---|
| 营收趋势 | 上个月呢 | 上个月营收趋势 | ❌ → `PROCESS_TASK_ANALYSIS`（LLM 瞎编） |
| 哪个菜卖得最好 | 上个月呢 | 上个月畅销菜品 | ❌ → `PROCESS_TASK_ANALYSIS` |

**本 spec 只覆盖案例 1 & 2（"同意图 + 加时间过滤" 续接）。** 案例 3 & 4（`它的趋势怎么样`、`那家店的客单价呢` —— "继承实体 + 切换指标"）是另一类更大/更敏感的工作，见 §7 拆分为 Phase 2 独立 spec。

`conversationRound` 已在 X1 Part A 修复并上线（之前返 null），不在本任务范围。

---

## 2. 根因（代码级定位，已逐行确认）

execute 路径有两套独立会话系统（详见交接文档 §2）。X1 续接逻辑本身正确且**在第二轮确实被触达**，问题是它**读到的 `lastIntentCode` 永远是 null** —— 因为第一轮成功查询走的是**短路路径**，从不创建记忆行、从不写 `lastIntentCode`。

### 逐轮追踪（基于 `origin/main`）

**第一轮 `营收趋势`** → 命中餐饮短语 → orchestrator 走**短语短路**：
- `IntentExecutionOrchestrator.execute()` line 258 `tryOrchestratorPhraseShortcut` 命中 → line 271 `executeWithExplicitIntent(...)` → 在 **line 590 返回**。
- `executeWithExplicitIntent`（line 485-591）**从不调用** `getOrCreateContext`，**也不调用** `updateConversationMemory`。`updateConversationMemory` 只在主识别流程末尾 line 476 被调用，而短路已经把主流程跳过了。
- 结果：**`conversation_memory` 无行被创建，`lastIntentCode` 从未写入。**

**第二轮 `上个月呢`**：
- 到达 `recognizeIntentWithConfidence`（orchestrator line 304）→ pipeline `IntentRecognitionPipelineServiceImpl` line 518 `getOrCreateContext` 此刻才**新建一行**（`lastIntentCode = null`）。
- line 529 `maybeAugmentContinuation` 读 `context.getLastIntentCode()` → null → 在 line 3844 提前返 null → **不增强、无 `[X1-Continuation]` 日志** → 落到 LLM → `PROCESS_TASK_ANALYSIS`。

### 三个 prod 症状全部对上
1. 第二轮 → `PROCESS_TASK_ANALYSIS`（LLM 兜底）。
2. **没有** `[X1-Continuation]` 日志（lastIntentCode=null，在打 log 前就返回了）。
3. `检测到会话延续` / `会话不存在` 日志 = System B (`ConversationService` 槽位填充) 噪音，红鲱鱼 —— 对任何带 sessionId 的请求它都会打，找不到槽位会话就返 CANCELLED。

### 关键确认（代码事实）
- `getOrCreateContext`（`ConversationMemoryServiceImpl` line 112-153，line 126 `createNewMemory`）是**唯一**创建 `conversation_memory` 行的入口。
- `addMessage`（line 267-270）/ `updateLastIntent`（line 445-446）在行不存在时**静默 no-op**。
- `updateConversationMemory`（orchestrator line 1508-1524）只在主流程 line 476 调用一次；它内部不创建行，依赖主流程 pipeline line 518 已 `getOrCreateContext` 建好行。
- `executeWithExplicitIntent` 是**所有短路路径的汇聚点**（line 506-511 注释明示："the convergence point all explicit-code / forced / multi-intent / phrase-shortcut / conversation-continuation paths funnel through"）。

**结论**：交接文档的二选一（null=持久化 gap / 有值=触达 gap）→ 代码级确定为**持久化 gap**，具体是**短路路径同时跳过了"建行"和"写 lastIntentCode"**。X1 逻辑无需改动。

---

## 3. 范围

**Phase 1（本 spec）**：
- 案例 1 & 2（纯时间续接）。
- 修复 = 持久化修复（建行 + 写 lastIntentCode）+ 时间词表扩展。

**不在范围**：案例 3 & 4（见 §7，Phase 2 独立 spec）。

---

## 4. 设计

### 方案选择（持久化改在哪）

| | 方案 | 结论 |
|---|---|---|
| **A ✅** | 在 `executeWithExplicitIntent` 成功尾部持久化（汇聚点 line 506-511） | **采用** —— 一处覆盖所有短路路径；改动最小；与主流程 line 476 一致 |
| B | 在每个短路调用点（271/832/201/687）分别持久化 | 重复、易漏 |
| C | 重构 `execute()` 让短路落到 line 476 | 改动大，路由回归风险 |

### 改动 1 — 持久化修复（核心）

**文件**: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java`
**位置**: `executeWithExplicitIntent` 成功尾部（line 588 之后、line 590 `return response` 之前）。

逻辑（伪代码，最终以实现为准）：

```java
// X1 Part B 修复：短路 / 显式意图路径也要持久化对话记忆。
// 主 execute() 流程在 line 476 持久化，但短路路径（短语短路 #0.25 / 显式 intentCode #0 /
// 多意图 / 会话完成）汇聚到这里后直接 return，既不建 conversation_memory 行也不写 lastIntentCode，
// 导致第二轮续接 (X1) 无可继承。仅当 sessionId 非空时触发；无 session 的独立查询完全不受影响。
if (request.getSessionId() != null && !request.getSessionId().isEmpty()) {
    try {
        // 1) 确保记忆行存在（缺失的关键步；否则 updateLastIntent/addMessage 全 no-op）
        conversationMemoryService.getOrCreateContext(factoryId, userId, request.getSessionId());
        // 2) 镜像主流程持久化（写 lastIntentCode + 消息 + 实体槽）
        IntentMatchResult syntheticMatch = IntentMatchResult.builder()
                .bestMatch(intent)                       // executeWithExplicitIntent 已解析的 AIIntentConfig
                .userInput(request.getUserInput())
                .build();
        updateConversationMemory(request.getSessionId(), request, response, syntheticMatch, factoryId, userId);
    } catch (Exception e) {
        log.warn("X1 explicit-intent path memory persist failed: {}", e.getMessage());  // fail-soft
    }
}
```

**安全性论证**：
- **不双写**：`execute()` 中所有 `executeWithExplicitIntent` 调用（line 201/271/832/687）都是 `return executeWithExplicitIntent(...)` 终态返回，永不再走到主流程 line 476。同一请求只持久化一次。
- **无 session 不触发**：守卫 `sessionId != null`。`IntentParityTest` / `IntentGoldenAssertionTest` 全部 `sessionId=null`，不受影响。
- **不碰 System B**：完全不动 `ConversationService`（承重的槽位填充路径）。会话完成路径（line 687）多写一次 lastIntentCode 到 System A，无害甚至有益。
- **fail-soft**：异常只 warn，不影响主响应（与 line 1521 既有写法一致）。
- **持久化时机**：到达尾部即持久化（早期返回 WRITE_CONFIRM_REQUIRED / 审批 / 业态门 / 预览 / 无权限 / 未找到 都在 line 588 之前，不会到这里）。与主流程 line 476 的"任何状态都持久化"语义一致。
- **写意图无副作用**：即便持久化了一个写意图的 code，它不在 `CONTINUATION_CANONICAL_PHRASE` 白名单，第二轮续接不会被放大成写操作。

### 改动 2 — 时间词表扩展

**文件**: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java`
**位置**: `CONTINUATION_BARE_TIME` 常量（line 3808-3817）。

补充时间修饰词（纯正则扩展，不改任何控制流）：
- 日粒度：`今天 / 昨天 / 前天 / 今日 / 昨日`
- 周末：`本周末 / 上周末 / 周末`
- 半年：`上半年 / 下半年`
- 修 `近N个月`：当前 `近\d{1,3}\s*[天日周月年]` 在 "近3个月" 的 `个` 处断掉 → 改为允许可选 `个`，例如 `(?:最近|近)\d{1,3}\s*个?\s*[天日周月年]`。

**安全性**：`CONTINUATION_BARE_TIME` 只在 `maybeAugmentContinuation` 闸 4 内、且前置闸（白名单意图 / 长度≤8 / 无领域名词）全过后才生效。词表扩大只让更多"纯时间续接"被识别，不会让带领域名词/指代+指标的句子误增强（那些被闸 3 / case 分支拦下，仍返 null）。

---

## 5. 测试

### 单元测试 (Java)
- `maybeAugmentContinuation`（既有测试文件，补用例）：
  - 增强：`今天呢` / `昨天` / `上半年呢` / `近3个月` + lastIntent=RESTAURANT_REVENUE_TREND → 返 `<时间>收入趋势`。
  - 仍返 null：`它的趋势怎么样`、`那家店的客单价呢`（领域名词/指代+指标，确认 Phase 1 不误碰它们）。
  - 仍返 null：lastIntent 不在白名单 / 长度>8 / 含领域名词。
- orchestrator 持久化（mock `conversationMemoryService`）：
  - `executeWithExplicitIntent` 带非空 sessionId + 成功响应 → 验证 `getOrCreateContext` 与 `updateLastIntent` 被调用（intentCode 正确）。
  - `sessionId=null` → 验证两者**均未**被调用。

### 回归
- `IntentParityTest` 70/70、`IntentGoldenAssertionTest` 15/15 必须保持绿（全 `sessionId=null`）。

### prod 真实验证（强制、闸门 —— 交接文档 §6 原方法）
真实租户 **qhj_prod `RES_3101_009`**，execute 端点带同一 sessionId 跑 2 轮。活跃端口蓝绿轮换（10010 blue / 10020 green），先 health 探测。

- **Step 0（修前确认 gap，先做）**：新 sessionId → 第一轮 `营收趋势` → `SELECT last_intent_code FROM conversation_memory WHERE session_id=<sid>`（cretas_prod_db，写库用户 `cretas_user`）→ 预期 **null / 无行**（确认持久化 gap，对齐代码诊断）。
- **修后验收**：
  1. 第一轮 `营收趋势` → 查 DB：`last_intent_code = RESTAURANT_REVENUE_TREND`（行已建、已写）。
  2. 第二轮 `上个月呢` → `intentCode` 继承（`RESTAURANT_REVENUE_TREND` / `RESTAURANT_DAILY_REVENUE`）+ 时间生效，**非** `PROCESS_TASK_ANALYSIS`；日志出现 `[X1-Continuation]`。
  3. 覆盖多话题 × 时间变体：`哪个菜卖得最好 → 上个月呢`（→ 畅销）、`门店营收排行 → 本月呢`、`营收趋势 → 近3个月`（验词表扩展）。
- 抓日志：`ssh root@47.100.235.168 "grep -aE 'X1-Continuation|<sid>' /www/wwwroot/cretas/logs/cretas-backend.log | tail"`。

判据：第二轮 intentCode 继承第一轮话题域且时间生效；`[X1-Continuation]` 出现；DB 第一轮即写入 lastIntentCode。

---

## 6. 流程 / 部署

- 全程在 worktree `feat/restaurant-multiturn-context`（off `origin/main`）。
- PR → 合并 main（合前 `git diff origin/main...HEAD --stat` 确认 scope 干净，无 sister 文件夹带）。
- **从 main** 部署 prod：`git checkout main && git pull` → `./scripts/deploy/deploy-backend.sh --env prod`（绝不从 feature 分支部署 prod）。
- 部署后核对运行中的 jar 含修复，再跑修后验收。
- 单文件提交用 `git commit -- <file>` 或 `safe-commit.sh`，commit 前 `git status` 防并发污染。

---

## 7. Phase 2（独立 spec，本任务不做）

案例 3 & 4 是"继承上一轮**实体** + 切换**新指标**"，与案例 1 & 2 的"同意图 + 加时间"本质不同，且需要大量当前不存在的机制：

| 案例 | 现状 | 需要的新机制 |
|---|---|---|
| `它的趋势怎么样` | `怎么样`→GENERAL_QUESTION → orchestrator line 286 `handleEarlyQuestionTypeDetection` → Agentic RAG (line 768)，**绕过 X1** | (a) 餐饮结果实体抽取（上一轮畅销菜）；(b) `它` 指代覆盖（现 `PRODUCT_REFERENCE_PATTERN` 不含 `它`）；(c) "实体+指标切换"续接形态；(d) 让续接在 RAG 改道**之前**有机会 |
| `那家店的客单价呢` | `那家店` 无对应槽（无 STORE slot；`那家` 误中 SUPPLIER 模式）→ `客单价` 关键词 → `ORDER_STATISTICS` | (a) STORE 槽类型 + 餐饮门店实体抽取；(b) `那家店/该店/这家店` 指代；(c) 客单价→门店派生指标路由 |

无正确捷径：缺了继承实体，`它的趋势`→**整体**趋势（错，应为**该菜**趋势），`那家店的客单价`→**整体**客单价（错）。故必须独立设计。Phase 1 的持久化修复会**顺带**为 Phase 2 打基础（`updateConversationMemory` 也会调 `extractAndUpdateEntitySlots`），但实体抽取键（`productTypeId`/`supplierId`）对餐饮 gold 结果不匹配，需 Phase 2 专门处理。

---

## 8. 验收标准（Phase 1）

1. 单测全绿（新增 + 回归 `IntentParityTest` 70/70 + `IntentGoldenAssertionTest` 15/15）。
2. prod Step 0 实测确认修前 `last_intent_code` 为 null/无行。
3. prod 修后实测：第一轮即写入 lastIntentCode；第二轮 `上个月呢` 等纯时间续接继承话题 + 时间生效，非 `PROCESS_TASK_ANALYSIS`，`[X1-Continuation]` 日志出现；多话题 × 时间变体通过。
4. 无 session 独立查询行为不变；System B 槽位填充路径不受影响。
5. 从 main 部署 prod，核对运行 jar 含修复。
