# 餐饮 AI chat 多轮上下文继承 (Phase 1) 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让餐饮 chat 第二轮的"纯时间续接"(如 `营收趋势` → `上个月呢`)继承上一轮话题域,不再掉到 `PROCESS_TASK_ANALYSIS`。

**Architecture:** 根因是持久化 gap —— 第一轮成功查询走短路汇聚点 `executeWithExplicitIntent`,从不创建 `conversation_memory` 行也不写 `lastIntentCode`,致第二轮 X1 续接无可继承。修复 = (1) 在该汇聚点尾部补持久化(建行 + 写 lastIntentCode);(2) 扩展 `CONTINUATION_BARE_TIME` 时间词表。X1 续接逻辑本身不动。

**Tech Stack:** Java 21 + Spring Boot 3.2 + JUnit 5 + Mockito + AssertJ;Maven。验证用 curl + psql(prod cretas_prod_db)。

**Spec:** `docs/superpowers/specs/2026-06-04-restaurant-multiturn-context-design.md`

**Worktree:** `feat/restaurant-multiturn-context` (off `origin/main`,已建于 `C:/Users/Steve/cretas-multiturn`)。所有命令在该 worktree 内执行。

---

## 关键约束(每个 task 都要守)

- **无 session 不受影响**:所有改动只在 `request.getSessionId()` 非空时生效。`IntentParityTest`(70)+ `IntentGoldenAssertionTest`(15)全是 `sessionId=null`,必须保持绿。
- **不碰 System B**:不动 `ConversationService` / 槽位填充承重路径。
- **部署只从 main**:feature 分支只做开发 + 单测;prod 部署在 Task 5 合并 main 后从 main 执行。
- **并发安全**:每次 commit 用 `git commit -- <显式文件>`,commit 前 `git status --short` 确认无 sister 文件夹带。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java` | 改 | 新增 `persistConversationMemoryForExplicitIntent` 包级方法 + 在 `executeWithExplicitIntent` 尾部调用 |
| `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/IntentExecutionOrchestratorMemoryPersistTest.java` | 建 | 验证持久化:带 session → 调 getOrCreateContext+updateLastIntent;无 session → 零交互 |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java` | 改 | 扩展 `CONTINUATION_BARE_TIME` 正则(日粒度/周末/半年/近N个月) |
| `backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/IntentContinuationAugmentTest.java` | 改 | 新增 bare-time 扩展用例 |
| `scripts/qa-multiturn-context-probe.sh` | 建 | prod 两轮真实验证探针(curl execute + psql DB 检查 + log grep) |

---

## Task 1: 创建 prod 验证探针 + 修前确认持久化 gap(证据先行)

**Files:**
- Create: `scripts/qa-multiturn-context-probe.sh`

> 这一步先把诊断在**当前 prod**(尚无修复)上跑实,确认 `last_intent_code` 在第一轮后为 null/无行(= 持久化 gap)。同一探针 Task 6 复用做修后验收。
> JWT 由操作者提供(qhj_prod, role=factory_super_admin, factoryId 在 token payload):通过 `/api/mobile/auth/*` 登录 qhj_prod 账号获取,或复用现有有效 token,导出为环境变量 `CRETAS_JWT`。账号凭证不写进脚本/repo(本地 `.env.test`)。
> Java 后端 10010/10020 仅对 nginx 网关开放,故探针在**服务器 localhost** 跑(绕国内 ISP 的 SSH stream RST)。

- [ ] **Step 1: 写探针脚本**

```bash
# scripts/qa-multiturn-context-probe.sh
# 用法: CRETAS_JWT=<qhj_prod token> ROUND1="营收趋势" ROUND2="上个月呢" \
#       ssh root@47.100.235.168 'bash -s' < scripts/qa-multiturn-context-probe.sh
# (或把脚本 scp 上去再跑) —— 在服务器 localhost 命中活跃蓝绿端口。
set -u
JWT="${CRETAS_JWT:?need CRETAS_JWT (qhj_prod factory_super_admin token)}"
R1="${ROUND1:-营收趋势}"
R2="${ROUND2:-上个月呢}"
FACTORY="RES_3101_009"

# 蓝绿端口探测: 优先 10020(green), 回退 10010(blue)
if curl -s -o /dev/null -w '%{http_code}' "http://localhost:10020/api/mobile/health" | grep -q 200; then
  PORT=10020
else
  PORT=10010
fi
SID="mt-verify-$(date +%s)"
echo "PORT=$PORT  SID=$SID"
EXEC="http://localhost:$PORT/api/mobile/$FACTORY/ai-intents/execute"

post() {  # $1 = userInput
  curl -s -X POST "$EXEC" \
    -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
    -d "{\"userInput\":\"$1\",\"sessionId\":\"$SID\"}"
}
dbcheck() {
  PGPASSWORD=cretas123 psql -h 127.0.0.1 -U cretas_user -d cretas_prod_db -tAc \
    "SELECT session_id, last_intent_code FROM conversation_memory WHERE session_id='$SID';"
}

echo "=== ROUND 1: $R1 ==="; post "$R1" | head -c 600; echo
echo "=== DB after round 1 (expect last_intent_code populated AFTER fix; null/empty BEFORE fix) ==="; dbcheck
echo "=== ROUND 2: $R2 ==="; post "$R2" | head -c 600; echo
echo "=== logs (X1-Continuation marker appears only AFTER fix) ==="
grep -aE "X1-Continuation|$SID" /www/wwwroot/cretas/logs/cretas-backend.log | tail -30
```

- [ ] **Step 2: 修前跑一次,确认 gap**

Run:
```bash
CRETAS_JWT=<qhj_prod token> ssh root@47.100.235.168 'bash -s' < scripts/qa-multiturn-context-probe.sh
```
Expected(修前 = 确认持久化 gap):
- ROUND 1 返回正常营收趋势结果(intentCode = `RESTAURANT_REVENUE_TREND` 或等价)。
- **DB after round 1: 无行 / `last_intent_code` 为空** ← 这就是持久化 gap 的实锤。
- ROUND 2 `上个月呢` → `intentCode=PROCESS_TASK_ANALYSIS`(或非餐饮意图)。
- logs: **无** `X1-Continuation`。

把输出贴进 Task 6 的对照(修后再跑一次对比)。若服务器/JWT 暂不可达,记录并把修前+修后两次都放到 Task 6 一起跑(至少修后必须做)。

- [ ] **Step 3: Commit 探针脚本**

```bash
git status --short
git add scripts/qa-multiturn-context-probe.sh
git commit -m "test(qa): 餐饮多轮上下文 prod 验证探针 (execute 两轮 + DB last_intent_code 检查)" -- scripts/qa-multiturn-context-probe.sh
git show --name-only --oneline HEAD
```

---

## Task 2: 持久化修复 —— `executeWithExplicitIntent` 汇聚点(核心)

**Files:**
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/IntentExecutionOrchestratorMemoryPersistTest.java` (create)
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java`

- [ ] **Step 1: 写失败测试**

新建 `IntentExecutionOrchestratorMemoryPersistTest.java`:

```java
package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * X1 Part B 修复 —— 短路 / 显式意图路径持久化对话记忆。
 *
 * <p>主 execute() 流程在末尾持久化 (updateConversationMemory),但短语短路 / 显式 intentCode /
 * 多意图 / 会话完成 都汇聚到 executeWithExplicitIntent 并直接返回,之前既不建 conversation_memory
 * 行也不写 lastIntentCode → 下一轮 X1 续接无可继承。本测试直接验证抽出的
 * persistConversationMemoryForExplicitIntent 包级方法:带 session → 建行 + 写 lastIntent;
 * 无 session → 零交互 (parity/golden 不受影响)。
 *
 * <p>构造镜像 IntentExecutionOrchestratorNegationTest:全 mock 协作者
 * (writeGuardService 为 @Autowired 字段,不在构造器,本方法也不用它)。
 */
@DisplayName("IntentExecutionOrchestrator — X1 explicit-intent memory persistence")
class IntentExecutionOrchestratorMemoryPersistTest {

    private IntentExecutionOrchestrator orchestrator;
    private ConversationMemoryService memory;

    @BeforeEach
    void setUp() {
        memory = mock(ConversationMemoryService.class);
        orchestrator = new IntentExecutionOrchestrator(
                mock(AIIntentService.class),
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                memory,
                new ObjectMapper(),
                mock(DashScopeClient.class),
                mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class),
                mock(AIAnalysisResultRepository.class),
                mock(ToolRegistry.class),
                mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class),
                mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
    }

    @Test
    @DisplayName("带 session 的显式意图执行 → 建行 (getOrCreateContext) + 写 lastIntentCode")
    void withSession_persistsLastIntent() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_REVENUE_TREND").build();
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("sess-mt-1").userInput("营收趋势").build();
        IntentExecuteResponse resp = IntentExecuteResponse.builder()
                .status("COMPLETED").build();

        orchestrator.persistConversationMemoryForExplicitIntent(
                "RES_3101_009", req, resp, intent, 9L);

        verify(memory).getOrCreateContext("RES_3101_009", 9L, "sess-mt-1");
        verify(memory).updateLastIntent("sess-mt-1", "RESTAURANT_REVENUE_TREND");
    }

    @Test
    @DisplayName("无 session 的显式意图执行 → 完全不碰对话记忆 (parity/golden 安全)")
    void withoutSession_noMemoryInteraction() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_REVENUE_TREND").build();
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .userInput("营收趋势").build();  // 无 sessionId
        IntentExecuteResponse resp = IntentExecuteResponse.builder()
                .status("COMPLETED").build();

        orchestrator.persistConversationMemoryForExplicitIntent(
                "RES_3101_009", req, resp, intent, 9L);

        verifyNoInteractions(memory);
    }
}
```

- [ ] **Step 2: 跑测试,确认失败(编译失败 = 方法不存在)**

Run:
```bash
cd backend/java/cretas-api && mvn -q -Dtest=IntentExecutionOrchestratorMemoryPersistTest test
```
Expected: 编译错误 `cannot find symbol: method persistConversationMemoryForExplicitIntent`。

- [ ] **Step 3: 实现持久化方法 + 在汇聚点调用**

在 `IntentExecutionOrchestrator.java`,`executeWithExplicitIntent` 方法的尾部 —— 即 `applyFormattedTextFallback(response);` 之后、`return response;`(约 line 590)之前 —— 插入一行调用:

```java
        applyResultFormatting(response);
        applyFormattedTextFallback(response);

        // X1 Part B 修复:短路 / 显式意图路径也持久化对话记忆,供下一轮续接继承。
        persistConversationMemoryForExplicitIntent(factoryId, request, response, intent, userId);

        return response;
```

并在该类内(建议放在 `updateConversationMemory` 方法附近,"对话记忆" 区块)新增方法:

```java
    /**
     * X1 Part B 修复 —— 在短路 / 显式意图路径持久化对话记忆。
     *
     * <p>主 execute() 流程在 line ~476 调 updateConversationMemory 持久化,但所有短路路径
     * (orchestrator 短语短路 / 显式 intentCode / 多意图 / 会话完成)都汇聚到
     * executeWithExplicitIntent 后直接返回,既不建 conversation_memory 行也不写
     * lastIntentCode,导致下一轮 X1 续接无可继承。这里补上。
     *
     * <p>仅当 sessionId 非空时生效:无 session 的独立查询 (IntentParityTest /
     * IntentGoldenAssertionTest 全部) 完全不受影响。fail-soft:异常只 warn,不影响主响应。
     *
     * <p>包级可见,便于单测直接调用。
     *
     * @param intent 已解析并执行的意图,其 code 写入 lastIntentCode
     */
    void persistConversationMemoryForExplicitIntent(String factoryId, IntentExecuteRequest request,
                                                    IntentExecuteResponse response,
                                                    AIIntentConfig intent, Long userId) {
        if (request.getSessionId() == null || request.getSessionId().isEmpty()) {
            return;
        }
        try {
            // 1) 确保记忆行存在(关键:否则 updateLastIntent / addMessage 在行缺失时静默 no-op)
            conversationMemoryService.getOrCreateContext(factoryId, userId, request.getSessionId());
            // 2) 镜像主流程持久化(写 lastIntentCode + 消息 + 实体槽)
            IntentMatchResult syntheticMatch = IntentMatchResult.builder()
                    .bestMatch(intent)
                    .userInput(request.getUserInput())
                    .build();
            updateConversationMemory(request.getSessionId(), request, response,
                    syntheticMatch, factoryId, userId);
        } catch (Exception e) {
            log.warn("X1 explicit-intent path memory persist failed: {}", e.getMessage());
        }
    }
```

确认 import 已存在(同文件已用):`com.cretas.aims.dto.intent.IntentMatchResult`、`com.cretas.aims.entity.config.AIIntentConfig`、`com.cretas.aims.dto.ai.IntentExecuteRequest`、`com.cretas.aims.dto.ai.IntentExecuteResponse`。若 `IntentMatchResult` 未 import 则补 `import com.cretas.aims.dto.intent.IntentMatchResult;`。

- [ ] **Step 4: 跑测试,确认通过**

Run:
```bash
cd backend/java/cretas-api && mvn -q -Dtest=IntentExecutionOrchestratorMemoryPersistTest test
```
Expected: PASS(2 tests)。

- [ ] **Step 5: Commit**

```bash
cd C:/Users/Steve/cretas-multiturn
git status --short
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/IntentExecutionOrchestratorMemoryPersistTest.java
git commit -m "fix(ai-intent): 短路/显式意图路径持久化 lastIntentCode (X1 多轮续接根因)" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/IntentExecutionOrchestratorMemoryPersistTest.java
git show --name-only --oneline HEAD
```

---

## Task 3: 时间词表扩展 —— `CONTINUATION_BARE_TIME`

**Files:**
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/IntentContinuationAugmentTest.java` (modify)
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java`

- [ ] **Step 1: 写失败测试(加在 `IntentContinuationAugmentTest` 的 "continuation markers that MUST be augmented" 区块内)**

```java
    @Test
    @DisplayName("day-granularity '今天呢' after RESTAURANT_REVENUE_TREND -> augments")
    void bareTimeTodayAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "今天呢", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
        assertThat(result).startsWith("今天");
    }

    @Test
    @DisplayName("day-granularity '昨天' after RESTAURANT_REVENUE_TREND -> augments")
    void bareTimeYesterdayAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "昨天", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
        assertThat(result).startsWith("昨天");
    }

    @Test
    @DisplayName("half-year '上半年呢' after RESTAURANT_REVENUE_TREND -> augments")
    void bareTimeHalfYearAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "上半年呢", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
        assertThat(result).startsWith("上半年");
    }

    @Test
    @DisplayName("rolling window with 个 '近3个月' after RESTAURANT_REVENUE_TREND -> augments")
    void rollingWindowWithGeAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "近3个月", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
        assertThat(result).startsWith("近3个月");
    }
```

- [ ] **Step 2: 跑测试,确认失败**

Run:
```bash
cd backend/java/cretas-api && mvn -q -Dtest=IntentContinuationAugmentTest test
```
Expected: 4 个新测试 FAIL(`result` 为 null —— `今天/昨天/上半年/近3个月` 当前不在 `CONTINUATION_BARE_TIME`,核心不被识别为纯时间词)。既有用例仍 PASS。

- [ ] **Step 3: 扩展正则**

在 `IntentRecognitionPipelineServiceImpl.java`,把 `CONTINUATION_BARE_TIME`(约 line 3808-3817)替换为:

```java
    private static final Pattern CONTINUATION_BARE_TIME = Pattern.compile(
            "^(?:" +
            "这个月|上个月|本月|当月|上月" +
            "|今天|昨天|前天|今日|昨日" +                       // X1 P1: 日粒度
            "|本周末|上周末|周末" +                            // X1 P1: 周末
            "|本季度|上季度|第[一二三四1-4]季度" +
            "|本星期|这周|本周|上星期|上周" +
            "|上半年|下半年" +                                 // X1 P1: 半年
            "|本年度|本年|今年|去年|前年" +
            "|最近\\d{1,3}\\s*个?\\s*[天日周月年]|近\\d{1,3}\\s*个?\\s*[天日周月年]" +  // X1 P1: 可选「个」(近3个月)
            "|\\d{4}\\s*[年/\\-]\\s*(?:1[0-2]|0?[1-9])\\s*月?" +
            "|\\d{4}\\s*年" +
            ")的?$");
```

(唯一改动:加日粒度 / 周末 / 半年 三组,并给 `最近/近 N` 分支加可选 `个?` 修 "近3个月"。其余不变。)

- [ ] **Step 4: 跑测试,确认通过**

Run:
```bash
cd backend/java/cretas-api && mvn -q -Dtest=IntentContinuationAugmentTest test
```
Expected: 全部 PASS(原有 + 4 新)。

- [ ] **Step 5: Commit**

```bash
cd C:/Users/Steve/cretas-multiturn
git status --short
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/IntentContinuationAugmentTest.java
git commit -m "feat(ai-intent): 续接时间词表扩展 (今天/昨天/上半年/近N个月) X1 Part B" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/IntentContinuationAugmentTest.java
git show --name-only --oneline HEAD
```

---

## Task 4: 本地全回归(闸门:绿才能进 Task 5)

**Files:** 无(仅运行)。

- [ ] **Step 1: 跑新增 + 回归套件**

Run:
```bash
cd backend/java/cretas-api && mvn -q -Dtest=IntentContinuationAugmentTest,IntentExecutionOrchestratorMemoryPersistTest,IntentExecutionOrchestratorNegationTest,IntentParityTest,IntentGoldenAssertionTest test
```
Expected: 全 PASS。重点确认:
- `IntentParityTest` 70/70 绿(全 `sessionId=null`,持久化改动不触达)。
- `IntentGoldenAssertionTest` 15/15 绿。
- `IntentExecutionOrchestratorNegationTest` 仍绿(构造器签名未变)。

- [ ] **Step 2: 模块编译(确保 main 代码无编译错误)**

Run:
```bash
cd backend/java/cretas-api && mvn -q -DskipTests compile
```
Expected: BUILD SUCCESS。

(本 Task 无 commit。)

---

## Task 5: PR → 合并 main → 从 main 部署 prod

**Files:** 无代码改动。

- [ ] **Step 1: 推分支 + scope 自检**

Run:
```bash
cd C:/Users/Steve/cretas-multiturn
git diff origin/main...HEAD --stat
git push -u origin feat/restaurant-multiturn-context
```
Expected: diff 只含本任务文件(spec / plan / 探针 / `IntentExecutionOrchestrator.java` + 其测试 / `IntentRecognitionPipelineServiceImpl.java` + 其测试)。若出现 sister 文件 → 说明基底不干净,停手排查(worktree 应 off origin/main)。

- [ ] **Step 2: 开 PR**

Run:
```bash
gh pr create --base main --head feat/restaurant-multiturn-context \
  --title "fix(ai-intent): 餐饮 chat 多轮纯时间续接继承 (持久化 gap 修复 + 时间词表扩展) Phase 1" \
  --body "接 #494/#495 唯一 defer 项。根因: executeWithExplicitIntent (短路汇聚点) 不持久化 lastIntentCode → 第二轮 X1 续接无可继承。修复: (1) 汇聚点尾部补持久化; (2) CONTINUATION_BARE_TIME 扩展。案例 3&4 (实体+指标切换) 见 Phase 2 spec。Spec: docs/superpowers/specs/2026-06-04-restaurant-multiturn-context-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

- [ ] **Step 3: 合并 main(CI 绿后)**

Run:
```bash
gh pr merge --squash --delete-branch=false
```
(若 CI 有 pre-existing 不相关 red,按项目惯例确认后 admin-merge;合并前再 `git fetch origin main` + `git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway 2>/dev/null | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d` 确认无 Flyway 版本撞车 —— 本任务无迁移,应为空。)

- [ ] **Step 4: 从 main 部署 prod**

Run:
```bash
cd C:/Users/Steve/my-prototype-logistics
git checkout main && git pull origin main
./scripts/deploy/deploy-backend.sh --env prod
```
Expected: 部署成功 + 健康检查通过。记录活跃端口(蓝绿轮换,10010 / 10020)。

- [ ] **Step 5: 核对运行 jar 含修复**

Run:
```bash
ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar 'BOOT-INF/classes/com/cretas/aims/service/execution/IntentExecutionOrchestrator.class' | strings | grep -c 'persistConversationMemoryForExplicitIntent'"
```
Expected: ≥ 1(运行 jar 确含新方法)。

---

## Task 6: prod 真实两轮验收(强制闸门)

**Files:** 无(运行 Task 1 的探针)。

- [ ] **Step 1: 主案例两轮验证**

Run(用 Task 1 的探针,默认 `营收趋势` → `上个月呢`):
```bash
CRETAS_JWT=<qhj_prod token> ssh root@47.100.235.168 'bash -s' < scripts/qa-multiturn-context-probe.sh
```
Expected(修后):
- ROUND 1 后 **DB `last_intent_code = RESTAURANT_REVENUE_TREND`**(行已建、已写)。
- ROUND 2 `上个月呢` → `intentCode` 继承(`RESTAURANT_REVENUE_TREND` / `RESTAURANT_DAILY_REVENUE`)+ 时间生效,**非** `PROCESS_TASK_ANALYSIS`。
- logs 出现 `[X1-Continuation] inherit prior intent: lastIntent=RESTAURANT_REVENUE_TREND ...`。

- [ ] **Step 2: 多话题 + 时间变体覆盖**

Run(改环境变量复跑探针):
```bash
# 菜品话题
CRETAS_JWT=<token> ROUND1="哪个菜卖得最好" ROUND2="上个月呢" ssh root@47.100.235.168 'bash -s' < scripts/qa-multiturn-context-probe.sh
# 门店话题
CRETAS_JWT=<token> ROUND1="门店营收排行" ROUND2="本月呢" ssh root@47.100.235.168 'bash -s' < scripts/qa-multiturn-context-probe.sh
# 时间词表扩展 (近N个月)
CRETAS_JWT=<token> ROUND1="营收趋势" ROUND2="近3个月" ssh root@47.100.235.168 'bash -s' < scripts/qa-multiturn-context-probe.sh
```
Expected:
- 菜品:ROUND1 写 `RESTAURANT_BESTSELLER_QUERY`;ROUND2 继承畅销话题。
- 门店:ROUND1 写 `RESTAURANT_STORE_REVENUE_RANK`;ROUND2 继承门店排行 + 本月。
- 近3个月:ROUND2 命中续接(`[X1-Continuation]` 出现,时间 = 近3个月)—— 验证词表扩展生效。

- [ ] **Step 3: 记录验收证据**

把 Task 1(修前)与 Task 6(修后)的输出对照写进验收记录(可放 `docs/qa-audits/` 或 PR 评论):修前 `last_intent_code` null + ROUND2=PROCESS_TASK_ANALYSIS + 无 X1 log;修后 `last_intent_code` 已写 + ROUND2 继承 + 有 `[X1-Continuation]` log。

判据(全满足才算完成):
1. 修前实测确认 gap(last_intent_code null/无行)。
2. 修后:第一轮即写 lastIntentCode;第二轮纯时间续接继承话题 + 时间生效,非 PROCESS_TASK_ANALYSIS,`[X1-Continuation]` 出现;多话题 × 时间变体通过。
3. 本地 `IntentParityTest` 70/70 + `IntentGoldenAssertionTest` 15/15 绿。
4. 运行 jar 含 `persistConversationMemoryForExplicitIntent`。

---

## Self-Review(写计划时已核对)

- **Spec 覆盖**:§4 改动1(持久化)→ Task 2;§4 改动2(时间词表)→ Task 3;§5 单测 → Task 2/3,回归 → Task 4,prod 验证(Step 0 修前 + 修后)→ Task 1/6;§6 流程/部署 → Task 5。Phase 2(案例 3&4)明确不在本计划。✓
- **无占位符**:所有 step 含真实代码/命令/预期。JWT 与账号凭证是操作者提供的运行时输入(安全:不硬编码),非占位符。✓
- **类型一致**:`persistConversationMemoryForExplicitIntent(String, IntentExecuteRequest, IntentExecuteResponse, AIIntentConfig, Long)` 在 Task 2 定义与调用一致;测试中 `IntentMatchResult.builder().bestMatch(...).userInput(...)`、`AIIntentConfig.builder().intentCode(...)` 与既有用法(orchestrator line 927 / 实体 @Builder)一致;`conversation_memory.last_intent_code` 表/列名已核对。✓

---

## 并行工作建议

### Subagent: ✅ 适合
Task 2(持久化,`IntentExecutionOrchestrator`)与 Task 3(时间词表,`IntentRecognitionPipelineServiceImpl`)改不同文件、互不依赖,可并行两个 subagent(各自 TDD + commit)。Task 1(探针)亦独立可并行。Task 4(回归)需 2&3 都合入后串行。

### 多Chat: ❌ 不建议
本任务单一主题、文件少且 Task 4/5/6 强串行依赖(回归→部署→验收),多 chat 收益低且有 `IntentExecutionOrchestrator` 共改风险。单 chat + subagent 即可。
