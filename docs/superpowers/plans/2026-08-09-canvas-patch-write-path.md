# 画布补丁落库（只写草稿） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `canvas_product_process_workflow_config` 把已校验的画布补丁真正写进**草稿**，使 agent 能拼出任意工序拓扑而不只是线性链。

**Architecture:** 工具已有完整的补丁语言（5 种操作）与 `preview()` 实现，唯一缺的是 `execute()`——它今天硬性返回 `WORKFLOW_AI_PREVIEW_ONLY`。改为：复用 `preview()` 那条完全相同的「解析 → 打补丁 → 校验」链路，末尾多调一次 `ProductProcessWorkflowService#saveDraft`。落库安全性**不新发明机制**，全部靠 `saveDraft` 既有的四道闸（租户归属、草稿态校验、乐观锁、只写 DRAFT）。

**Tech Stack:** Java 21 / Spring Boot 3.2.12 / JUnit 5 / Mockito / Jackson

## Global Constraints

- **禁止降级处理** —— 不返回假数据，写不成必须明确报错（CLAUDE.md 核心原则 1）。
- **统一响应格式** —— `{ success, data, message }`（CLAUDE.md 核心原则 3）。
- **类型安全** —— 避免 `as any` / 裸 `Object` 强转，用明确类型（CLAUDE.md 核心原则 2）。
- **⛔ 只写 `DRAFT`，永不碰 `PUBLISHED`** —— 发布必须是人在 UI 上的独立动作。
- **⛔ `factoryId` 只从 `context` 取，绝不从 AI 参数取** —— AI 可控的入参不得决定写哪个租户。
- **⛔ 不放宽任何既有断言** —— `ProductProcessWorkflowConfigToolTest`(316 行) 与
  `ProductProcessWorkflowConfigToolBomFieldsTest`(370 行) 的断言**一条都不许改语义**；
  只允许为新增构造参数做**机械**适配。
- **⛔ 不新增第三方依赖。**
- 工作在独立 worktree：`git worktree add -b feat/canvas-patch-write ../cretas-canvas-write origin/main`
  （项目规则 `worktree-and-main-only-deploy.md`）。

---

## 🔴 计划修订记录（2026-08-09，派发前）

本计划第一版是照**过期代码**写的：我读的是工作分支上 473 行的旧版，
而 `origin/main` 上这个文件已经是 **698 行**。行号全部作废，且有一条结论是错的：

> ❌ 第一版写「补丁语言里没有任何辅料/包材的 key」——
> **`origin/main` 上已经有了**：`UPSERT_MATERIAL_BINDING` / `REMOVE_MATERIAL_BINDING`
> 两个操作，`ALLOWED_FIELD_ROOTS` 含 `materialBindings` / `injectionAmount` / `isByproduct`，
> `MATERIAL_BINDING_KEYS` 含 `dosagePerKgG`（每 kg 克数）与 `subsequentPotRatio`（锅序）。
> 源码注释标着「2026-08-07 阶段 4(画布即 BOM)」——**BOM 融合的 agent 侧已经落地了**。

📌 **计划的核心不受影响**：`execute()` 在 `origin/main` 上**仍然硬性拒绝**
（`:138` 起返回 `WORKFLOW_AI_PREVIEW_ONLY`）。要补的还是同一件事 —— 落库。
但正因为补丁语言已经覆盖辅料/锅序/注射量，**补上落库的收益比第一版估计的更大**：
agent 能配的不再只是工序骨架，而是连辅料克数一起。

⚠️ 判据：**动手前用 `origin/main` 复核一遍计划引用的每个行号与结论**。
worktree 是 off `origin/main` 开的，工作分支上的读数不作数。

---

## 地基事实（实施前必读，全部实测于 `origin/main` @ `6f84f6cb04`）

| 事实 | 出处 |
|---|---|
| `supportsPreview()` **已返回 true**，`preview()` **已完整实现** | `ProductProcessWorkflowConfigTool.java:99,114` |
| `execute()` 今天**硬性拒绝**，返回 `WORKFLOW_AI_PREVIEW_ONLY` | 同文件 `:138-150` |
| 工具继承 `AbstractTool`（**不是** `AbstractBusinessTool`），故用接口层 `preview()` 而非 `doPreview()` 钩子 | 同文件 `:22`；`ToolExecutor.java:128,143` |
| 允许的补丁操作 **7 种**（含 `UPSERT_MATERIAL_BINDING` / `REMOVE_MATERIAL_BINDING`） | 同文件 `:25-28` |
| 构造函数当前是 **2 参**（`ObjectMapper`, `ProductProcessWorkflowValidator`） | 同文件 `:69` |
| `preview()` 的拒绝分支用 `rejectionMessage(error)` 生成消息，**⛔ 改写时必须保留** | 同文件 `:132`、`:640` |
| `saveDraft(factoryId, productTypeId, definition)` 带 `@Transactional` | `ProductProcessWorkflowServiceImpl.java:65-66` |
| `requireWorkflowOwner` 挡跨租户写（productTypeId 不属本厂 → 400） | 同文件 `:233-241` |
| `assertCurrentVersion(null, entity)` → **抛 409**（空 lockVersion 覆盖不了已有草稿） | 同文件 `:273-279` |
| 无草稿时才新建，有草稿时走乐观锁 | 同文件 `:77-93` |
| `ProductProcessWorkflowDTO` 自带 `productTypeId` / `lockVersion` | `ProductProcessWorkflowDTO.java:27,34` |
| 主仓**没有** `writeToolAllowlist` / `ALLOW_WRITE_PREVIEW`（那是 `cretas-modular` 仓的机制） | 全仓 grep 命中 0 |
| 主仓 `ExecutionStatus` 只有 `NOT_APPLICABLE/ALLOW_READ/GUIDANCE/NO_MATCH` | `FactoryCapabilityPackRoutingPolicy.java:192-197` |

### ⚠️ 关于「进白名单」这一步：主仓**不需要做**

主仓的能力包是**纯只读**的（只有 `readToolAllowlist`），四个包（manager/operator/quality/warehouse）都不含写工具。
本工具在那四个角色下本来就是 `NO_MATCH`；在没有包的角色下走普通意图路径，**不受包管辖**。

⛔ **不要**为此把 `cretas-modular` 的 `writeToolAllowlist` 机制搬进主仓 —— 那是独立的一次架构改动，
不属于本计划范围，且两仓的能力包模型当前并不一致。

---

## File Structure

| 文件 | 职责 | 改动 |
|---|---|---|
| `.../ai/tool/impl/workprocess/ProductProcessWorkflowConfigTool.java` | 补丁语言 + 校验 + **落库** | 修改：抽出共享的「解析→打补丁→校验」，`execute()` 接上 `saveDraft` |
| `.../ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolTest.java` | 既有 316 行断言 | 修改：**仅**为新构造参数做机械适配 |
| `.../ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolBomFieldsTest.java` | 既有 370 行（辅料/锅序/注射量） | 修改：**仅**为新构造参数做机械适配（`:42` 处构造） |
| `.../ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolWriteTest.java` | 新写路径的承重断言 | 新建 |

⛔ **不新建 service / 不改 `ProductProcessWorkflowServiceImpl`** —— 落库逻辑一行都不重写，只调用。
这条是本计划最重要的边界：`saveDraft` 的四道闸是既有资产，重写等于把它们的保证作废。

---

## Task 1: 把「解析→打补丁→校验」抽成一处，preview 与 execute 共用

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigTool.java:92-131`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: `private ValidatedPatch buildValidatedCandidate(Map<String, Object> arguments)`
  其中 `private record ValidatedPatch(ProductProcessWorkflowDTO candidate, List<Map<String, Object>> patches)`
  —— 抛 `MissingDefinitionException` / `PatchRejectedException` / `BusinessException` / `IllegalArgumentException`；
  Task 2 的 `execute()` 调 `buildValidatedCandidate(...).candidate()` 拿落库用的 DTO。

**为什么先做这一步**：`preview` 和 `execute` 必须**逐字走同一条链路**。
若各写一份，就会出现「预览说能过、落库时校验不过」——那正是本仓反复栽的「同一概念两把尺子」。

- [ ] **Step 1: 写失败测试 —— 预览与落库必须用同一条校验链路**

在 `ProductProcessWorkflowConfigToolTest.java` 末尾（最后一个 `}` 之前）加：

```java
    @Test
    void previewAndWriteShareOneValidationPath() throws Exception {
        // 承重: 两条路必须对【同一批补丁】给出【同一个结论】。
        // 不同的话, 会出现「预览说能过、落库却校验不过」—— 那是两把尺子。
        List<Map<String, Object>> badPatches = List.of(
                setField("process:1", "conversionRule.mode", "NOT_A_REAL_MODE"));

        Map<String, Object> previewEnvelope = preview(badPatches);
        Map<String, Object> executeEnvelope = execute(badPatches);

        assertEquals(previewEnvelope.get("success"), executeEnvelope.get("success"));
        assertEquals(previewEnvelope.get("errorCode"), executeEnvelope.get("errorCode"));
    }

    private Map<String, Object> execute(List<Map<String, Object>> patches) throws Exception {
        String arguments = objectMapper.writeValueAsString(Map.of(
                "definition", definition(),
                "patches", patches));
        return objectMapper.readValue(
                tool.execute(
                        ToolCall.of("execute", tool.getToolName(), arguments),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});
    }
```

- [ ] **Step 2: 跑测试确认它红**

```bash
mvn -f backend/java/cretas-api/pom.xml test \
  -Dtest='ProductProcessWorkflowConfigToolTest#previewAndWriteShareOneValidationPath' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL —— `execute()` 现在恒返回 `WORKFLOW_AI_PREVIEW_ONLY`，
而 `preview()` 返回 `WORKFLOW_PATCH_REJECTED`，两个 errorCode 对不上。

- [ ] **Step 3: 抽出共享方法**

在 `ProductProcessWorkflowConfigTool.java` 中，把 `preview()` 内部那段改成调用新方法。
替换 `:92-113` 的 `preview` 方法体为：

```java
    @Override
    public String preview(ToolCall toolCall, Map<String, Object> context) {
        try {
            ValidatedPatch validated = buildValidatedCandidate(parseArguments(toolCall));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "PREVIEW");
            data.put("applied", false);
            // ⚠️ 必须原样回显 patches(列表), 不能换成节点数 ——
            // ProductProcessWorkflowConfigToolTest:95 断言它是列表且 size==3。
            data.put("patches", validated.patches());
            return buildSuccessResult(data);
        } catch (MissingDefinitionException missing) {
            return buildSemanticError(
                    "WORKFLOW_DEFINITION_REQUIRED", "Workflow definition is required for preview");
        } catch (PatchRejectedException | BusinessException | IllegalArgumentException error) {
            // ⛔ 保留 rejectionMessage(error) —— origin/main:132 用它给出具体拒绝原因,
            // 换成固定串会让 agent 失去「为什么被拒」的信息, 那是能力倒退。
            return buildSemanticError("WORKFLOW_PATCH_REJECTED", rejectionMessage(error));
        }
    }

    /**
     * 解析入参 → 打补丁 → 跑草稿校验，返回可落库的候选。
     *
     * <p>⛔ preview 与 execute <b>必须</b>都走这里。各写一份会让「预览说能过、落库却过不了」
     * 成为可能 —— 那是本仓反复栽过的「同一概念两把尺子」。
     */
    private ValidatedPatch buildValidatedCandidate(Map<String, Object> arguments) {
        if (!(arguments.get("definition") instanceof Map<?, ?> definition)) {
            throw new MissingDefinitionException();
        }
        List<Map<String, Object>> patches = sanitizePatches(arguments.get("patches"));
        ProductProcessWorkflowDTO candidate = objectMapper.convertValue(
                definition, ProductProcessWorkflowDTO.class);
        applyCandidateBatch(candidate, patches);
        workflowValidator.validateForDraft(candidate);
        return new ValidatedPatch(candidate, patches);
    }

    /**
     * 校验通过的候选 + 原始补丁清单。
     *
     * <p>两样都要带回去：{@code preview} 要原样回显 patches（既有断言检查它是列表），
     * {@code execute} 要拿 candidate 去落库。合成一个返回值是为了让两条路
     * <b>物理上</b>不可能各走各的校验。
     */
    private record ValidatedPatch(
            ProductProcessWorkflowDTO candidate, List<Map<String, Object>> patches) {
    }

    /** definition 缺失与补丁被拒是两种不同的错，errorCode 也不同，所以要能分开捕获。 */
    private static final class MissingDefinitionException extends RuntimeException {
        private MissingDefinitionException() {
            super(null, null, false, false);
        }
    }
```

⚠️ **为什么返回 record 而不是直接返回 DTO**：`ProductProcessWorkflowConfigToolTest:95`
断言 `data.get("patches")` 是**列表且 size == 3**。只返回 candidate 就拿不到 patches，
`preview` 的回显会被迫改成别的形状而打破那条既有断言 —— ⛔ 那是「改断言迁就实现」。
已实测确认这条断言存在，所以从第一步就用 record，不留「万一红了再改」的分支。

- [ ] **Step 4: 让 execute 也走这条链路（暂不落库）**

替换 `execute()` 方法体：

```java
    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) {
        try {
            buildValidatedCandidate(parseArguments(toolCall));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "VALIDATED");
            data.put("applied", false);
            return buildSuccessResult(data);
        } catch (MissingDefinitionException missing) {
            return buildSemanticError(
                    "WORKFLOW_DEFINITION_REQUIRED", "Workflow definition is required");
        } catch (PatchRejectedException | BusinessException | IllegalArgumentException error) {
            return buildSemanticError("WORKFLOW_PATCH_REJECTED", rejectionMessage(error));
        } catch (Exception unexpected) {
            return buildSemanticError("WORKFLOW_PATCH_FAILED", "Workflow patch batch failed");
        }
    }
```

- [ ] **Step 5: 跑全部既有测试，确认一条都没被改坏**

```bash
mvn -f backend/java/cretas-api/pom.xml test \
  -Dtest='ProductProcessWorkflowConfigToolTest,ProductProcessWorkflowConfigToolBomFieldsTest' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS，且 `Tests run` 数 = 原有条数 + 1。
⚠️ 若某条既有断言红了，**先停下** —— 说明抽方法时改了语义，⛔ 不许改断言迁就。

- [ ] **Step 6: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigTool.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolTest.java
git commit -m "refactor(canvas-tool): preview 与 execute 共用一条校验链路

⛔ 各写一份会让「预览说能过、落库却过不了」成为可能, 那是两把尺子。
本步 execute 仍不落库(返回 VALIDATED/applied=false), 落库在下一个 commit。"
```

---

## Task 2: `execute()` 落库到草稿

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigTool.java`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolTest.java`（仅构造参数适配）
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolWriteTest.java`（新建）

**Interfaces:**
- Consumes: Task 1 的 `buildValidatedCandidate(Map<String, Object>)` → `ValidatedPatch`（取 `.candidate()`）
- Produces: 构造函数新签名
  `ProductProcessWorkflowConfigTool(ObjectMapper, ProductProcessWorkflowValidator, ProductProcessWorkflowService)`
  —— Task 3 的测试依赖这个签名。

- [ ] **Step 1: 写失败测试（新文件）**

创建 `ProductProcessWorkflowConfigToolWriteTest.java`：

```java
package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画布补丁的<b>落库</b>路径。
 *
 * <p>⛔ 这里不断言措辞。断言的是三件能被证伪的事：
 * 写的是不是草稿、factoryId 从哪来、校验不过时有没有碰库。
 */
class ProductProcessWorkflowConfigToolWriteTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProductProcessWorkflowService workflowService =
            mock(ProductProcessWorkflowService.class);
    private ToolExecutor tool;

    @BeforeEach
    void setUp() {
        tool = new ProductProcessWorkflowConfigTool(
                objectMapper, new ProductProcessWorkflowValidator(), workflowService);
    }

    @Test
    @DisplayName("🔴 承重: 补丁落到 saveDraft, 且 factoryId 来自 context 不是 AI 入参")
    void validPatchIsWrittenToDraftWithFactoryIdFromContext() throws Exception {
        ProductProcessWorkflowDTO saved = new ProductProcessWorkflowDTO();
        saved.setLockVersion(7L);
        when(workflowService.saveDraft(any(), any(), any())).thenReturn(saved);

        Map<String, Object> envelope = execute(
                Map.of("factoryId", "F006"),
                definitionWithOwner("PT-001", 3L));

        ArgumentCaptor<String> factoryId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> productTypeId = ArgumentCaptor.forClass(String.class);
        verify(workflowService).saveDraft(
                factoryId.capture(), productTypeId.capture(), any());
        assertEquals("F006", factoryId.getValue());
        assertEquals("PT-001", productTypeId.getValue());

        assertTrue((Boolean) envelope.get("success"));
        Map<String, Object> data = asMap(envelope.get("data"));
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("DRAFT", data.get("status"));
        // 回传新 lockVersion, 下一次补丁才接得上; 不回传的话 agent 只能改一次。
        assertEquals(7, ((Number) data.get("lockVersion")).intValue());
    }

    @Test
    @DisplayName("🔴 承重: AI 入参里的 factoryId 【不得】覆盖 context 的")
    void factoryIdInArgumentsIsIgnored() throws Exception {
        ProductProcessWorkflowDTO saved = new ProductProcessWorkflowDTO();
        saved.setLockVersion(1L);
        when(workflowService.saveDraft(any(), any(), any())).thenReturn(saved);

        Map<String, Object> definition = new LinkedHashMap<>(definitionWithOwner("PT-001", 3L));
        definition.put("factoryId", "OTHER_TENANT");

        execute(Map.of("factoryId", "F006"), definition);

        verify(workflowService).saveDraft(eq("F006"), any(), any());
        verify(workflowService, never()).saveDraft(eq("OTHER_TENANT"), any(), any());
    }

    @Test
    @DisplayName("🔴 承重: 校验不过时【一次库都不碰】")
    void rejectedPatchNeverTouchesTheDatabase() throws Exception {
        Map<String, Object> envelope = objectMapper.readValue(
                tool.execute(
                        ToolCall.of("bad", tool.getToolName(), objectMapper.writeValueAsString(
                                Map.of("definition", definitionWithOwner("PT-001", 3L),
                                        "patches", List.of(Map.of(
                                                "op", "ACTIVATE_WORKFLOW", "workflowId", 9))))),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});

        assertEquals(Boolean.FALSE, envelope.get("success"));
        verify(workflowService, never()).saveDraft(any(), any(), any());
    }

    @Test
    @DisplayName("productTypeId 缺失 -> 明确报错, ⛔ 不猜一个")
    void missingProductTypeIdIsRejectedRatherThanGuessed() throws Exception {
        Map<String, Object> envelope = execute(
                Map.of("factoryId", "F006"), definitionWithOwner(null, 3L));

        assertEquals(Boolean.FALSE, envelope.get("success"));
        assertEquals("WORKFLOW_OWNER_REQUIRED", envelope.get("errorCode"));
        verify(workflowService, never()).saveDraft(any(), any(), any());
    }

    @Test
    @DisplayName("saveDraft 抛 409(草稿被别人改过) -> 如实透出, ⛔ 不吞不重试")
    void draftConflictIsReportedNotSwallowed() throws Exception {
        when(workflowService.saveDraft(any(), any(), any()))
                .thenThrow(new com.cretas.aims.exception.BusinessException(409, "该 Workflow 已被其他人更新")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT"));

        Map<String, Object> envelope = execute(
                Map.of("factoryId", "F006"), definitionWithOwner("PT-001", 3L));

        assertEquals(Boolean.FALSE, envelope.get("success"));
        assertNotNull(envelope.get("errorCode"));
    }

    private Map<String, Object> execute(
            Map<String, Object> context, Map<String, Object> definition) throws Exception {
        String arguments = objectMapper.writeValueAsString(Map.of(
                "definition", definition,
                "patches", List.of(Map.of(
                        "op", "SET_NODE_FIELD",
                        "nodeId", "process:1",
                        "path", "name",
                        "value", "改过的工序名"))));
        return objectMapper.readValue(
                tool.execute(ToolCall.of("exec", tool.getToolName(), arguments), context),
                new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    private Map<String, Object> definitionWithOwner(String productTypeId, Long lockVersion) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("schemaVersion", 1);
        definition.put("status", "DRAFT");
        definition.put("version", 1);
        if (productTypeId != null) {
            definition.put("productTypeId", productTypeId);
        }
        definition.put("lockVersion", lockVersion);
        definition.put("nodes", List.of(
                Map.of("id", "raw", "kind", "RAW_MATERIAL",
                        "data", Map.of("name", "原料", "baseUnit", "kg")),
                Map.of("id", "process:1", "kind", "PROCESS",
                        "data", Map.of("processName", "工序一", "inputUnit", "kg", "outputUnit", "kg",
                                "ports", List.of(
                                        Map.of("id", "input:1", "direction", "INPUT",
                                                "materialNodeId", "raw", "materialKind", "RAW_MATERIAL",
                                                "unit", "kg", "ordinal", 0),
                                        Map.of("id", "output:1", "direction", "OUTPUT",
                                                "materialNodeId", "semi", "materialKind", "SEMI_FINISHED",
                                                "unit", "kg", "ordinal", 0)))),
                Map.of("id", "semi", "kind", "SEMI_FINISHED",
                        "data", Map.of("name", "半成品", "baseUnit", "kg"))));
        definition.put("edges", List.of(
                Map.of("id", "e1", "source", "raw", "sourceHandle", "output",
                        "target", "process:1", "targetHandle", "input:1"),
                Map.of("id", "e2", "source", "process:1", "sourceHandle", "output:1",
                        "target", "semi", "targetHandle", "input")));
        definition.put("viewport", Map.of("x", 0, "y", 0, "zoom", 1));
        return definition;
    }
}
```

- [ ] **Step 2: 跑测试确认它红**

```bash
mvn -f backend/java/cretas-api/pom.xml test \
  -Dtest='ProductProcessWorkflowConfigToolWriteTest' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **编译失败** —— 构造函数还是两个参数。这就是本步要的红。

- [ ] **Step 3: 实现落库**

在 `ProductProcessWorkflowConfigTool.java`：

① 加 import：

```java
import com.cretas.aims.service.ProductProcessWorkflowService;
```

② 加字段与构造参数（替换 `:46-53`）：

```java
    private final ProductProcessWorkflowValidator workflowValidator;
    private final ProductProcessWorkflowService workflowService;

    public ProductProcessWorkflowConfigTool(
            ObjectMapper objectMapper,
            ProductProcessWorkflowValidator workflowValidator,
            ProductProcessWorkflowService workflowService) {
        this.objectMapper = objectMapper;
        this.workflowValidator = workflowValidator;
        this.workflowService = workflowService;
    }
```

③ 替换 Task 1 写的 `execute()`：

```java
    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) {
        try {
            ProductProcessWorkflowDTO candidate =
                    buildValidatedCandidate(parseArguments(toolCall)).candidate();

            // ⛔ factoryId 只从 context 取。AI 能控制 definition 里的任何字段,
            // 让它决定写哪个租户 = 把租户隔离交给模型自觉。
            String factoryId = requireFactoryId(context);
            String productTypeId = candidate.getProductTypeId();
            if (productTypeId == null || productTypeId.isBlank()) {
                // ⛔ 不猜: 没有归属就不知道这张画布属于哪个成品, 猜错等于写到别的产品上。
                return buildSemanticError(
                        "WORKFLOW_OWNER_REQUIRED",
                        "Workflow definition must carry productTypeId");
            }

            // ⛔ 只调 saveDraft。它按构造只写 DRAFT, 且自带租户归属校验 + 乐观锁。
            // 落库的四道闸全在它里面, 这里【一行都不重写】—— 重写等于把那些保证作废。
            ProductProcessWorkflowDTO saved =
                    workflowService.saveDraft(factoryId, productTypeId, candidate);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "DRAFT");
            data.put("applied", true);
            // 回传新 lockVersion: 不回传的话 agent 只能改一次, 第二次必然 409。
            data.put("lockVersion", saved.getLockVersion());
            data.put("hint", "已写入草稿。发布需要人在产品配置页确认。");
            return buildSuccessResult(data);
        } catch (MissingDefinitionException missing) {
            return buildSemanticError(
                    "WORKFLOW_DEFINITION_REQUIRED", "Workflow definition is required");
        } catch (BusinessException business) {
            // 409 冲突 / 400 归属不符都走这里。⛔ 不吞、不重试 —— 冲突意味着有人在同一张
            // 画布上工作, 悄悄重试会覆盖掉他。
            // ⚠️ 用 getErrorCode()(String) 不是 getCode() —— 后者返回 Integer 的 HTTP 码(409),
            // 传给 buildSemanticError(String, String) 会编译不过。
            String errorCode = business.getErrorCode() == null
                    ? "WORKFLOW_WRITE_REJECTED" : business.getErrorCode();
            return buildSemanticError(errorCode, business.getMessage());
        } catch (PatchRejectedException | IllegalArgumentException error) {
            return buildSemanticError("WORKFLOW_PATCH_REJECTED", rejectionMessage(error));
        } catch (Exception unexpected) {
            return buildSemanticError("WORKFLOW_PATCH_FAILED", "Workflow patch batch failed");
        }
    }

    /** ⛔ context 里没有 factoryId 就直接拒 —— 不许回退到入参里的任何值。 */
    private String requireFactoryId(Map<String, Object> context) {
        Object raw = context == null ? null : context.get("factoryId");
        String factoryId = raw == null ? null : raw.toString().trim();
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId missing from execution context");
        }
        return factoryId;
    }
```

④ 加 import（若尚未存在）：

```java
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
```
（`:5` 已有，确认即可）

- [ ] **Step 4: 适配既有测试的构造调用（⛔ 只改构造，不改任何断言）**

在 `ProductProcessWorkflowConfigToolTest.java`：

加 import：

```java
import com.cretas.aims.service.ProductProcessWorkflowService;
import static org.mockito.Mockito.mock;
```

替换 `setUp()`：

```java
    @BeforeEach
    void setUp() {
        // 本类只测 preview 与校验, 落库路径由 ProductProcessWorkflowConfigToolWriteTest 覆盖。
        // ⛔ 这里给 mock 不是为了让测试变绿, 是因为这些用例本来就不该碰库。
        tool = new ProductProcessWorkflowConfigTool(
                objectMapper,
                new ProductProcessWorkflowValidator(),
                mock(ProductProcessWorkflowService.class));
    }
```

- [ ] **Step 5: 跑两个测试类**

```bash
mvn -f backend/java/cretas-api/pom.xml test \
  -Dtest='ProductProcessWorkflowConfigToolTest,ProductProcessWorkflowConfigToolBomFieldsTest,ProductProcessWorkflowConfigToolWriteTest' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 两个类全 PASS，`Failures: 0, Errors: 0`。

⚠️ 若 `ProductProcessWorkflowConfigToolTest:95`（断言 `data.patches` 是列表且 size==3）红了，
说明 Task 1 的 record 没接对 —— 回去检查 `preview()` 回显的是 `validated.patches()`
而不是 candidate 的什么派生值。⛔ 不许改那条断言迁就实现。

- [ ] **Step 6: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigTool.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolTest.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigToolWriteTest.java
git commit -m "feat(canvas-tool): 补丁落库到【草稿】—— agent 能拼任意拓扑, 不再只有线性链

落库只调 ProductProcessWorkflowService#saveDraft, ⛔ 一行落库逻辑都不重写:
它按构造只写 DRAFT, 自带 requireWorkflowOwner(跨租户 400)与乐观锁(空 lockVersion 409)。

三条承重断言: 写的是草稿 / factoryId 只从 context 取(入参里的被忽略) /
校验不过时一次库都不碰。409 冲突如实透出, ⛔ 不吞不重试 ——
冲突意味着有人在同一张画布上工作, 悄悄重试会覆盖掉他。"
```

---

## Task 3: 变异验证 —— 证明三条承重断言真的承重

**Files:**
- 临时改动：`ProductProcessWorkflowConfigTool.java`（每次改完立刻还原）

**Interfaces:**
- Consumes: Task 2 的 `execute()` 实现
- Produces: 无（验证任务，不留代码）

**为什么必须做**：本仓反复出现「闸绿是因为它没跑」。绿了不算数，要证明它会红。

- [ ] **Step 1: 变异 A —— 把 factoryId 改成从入参取**

```bash
cd backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess
cp ProductProcessWorkflowConfigTool.java /tmp/canvas.bak
python3 - <<'PY'
import io
P = "ProductProcessWorkflowConfigTool.java"
s = io.open(P, "r", encoding="utf-8", newline="").read()
a = "String factoryId = requireFactoryId(context);"
assert s.count(a) == 1, f"锚点命中 {s.count(a)} 次"     # ⛔ 没有这行 assert, replace 会静默 no-op
s = s.replace(a, "String factoryId = String.valueOf(candidate.getFactoryId());")
io.open(P, "w", encoding="utf-8", newline="").write(s)
print("变异 A 已注入")
PY
```

Expected: `factoryIdInArgumentsIsIgnored` **红**。

```bash
mvn -f /c/Users/Steve/cretas-canvas-write/backend/java/cretas-api/pom.xml test \
  -Dtest='ProductProcessWorkflowConfigToolWriteTest' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

⚠️ **看红在哪条用例上**。若红的是别的用例，说明变异打偏了，不算验证通过。

- [ ] **Step 2: 还原并确认回绿**

```bash
cp /tmp/canvas.bak backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigTool.java
mvn -f backend/java/cretas-api/pom.xml test \
  -Dtest='ProductProcessWorkflowConfigToolWriteTest' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS。

- [ ] **Step 3: 变异 B —— 把校验挪到落库之后**

```bash
cd backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess
python3 - <<'PY'
import io
P = "ProductProcessWorkflowConfigTool.java"
s = io.open(P, "r", encoding="utf-8", newline="").read()
a = "        workflowValidator.validateForDraft(candidate);\n        return candidate;"
assert s.count(a) == 1, f"锚点命中 {s.count(a)} 次"
s = s.replace(a, "        return candidate;")
io.open(P, "w", encoding="utf-8", newline="").write(s)
print("变异 B 已注入")
PY
```

Expected: `rejectedPatchNeverTouchesTheDatabase` **红**（校验没跑，坏补丁进了 saveDraft）。

- [ ] **Step 4: 还原并确认回绿**

```bash
cp /tmp/canvas.bak backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess/ProductProcessWorkflowConfigTool.java
mvn -f backend/java/cretas-api/pom.xml test \
  -Dtest='ProductProcessWorkflowConfigToolTest,ProductProcessWorkflowConfigToolBomFieldsTest,ProductProcessWorkflowConfigToolWriteTest' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
git status --short   # ⛔ 必须干净, 确认探针没留下
```

Expected: PASS，且 `git status --short` 无该文件。

- [ ] **Step 5: 全量构建**

```bash
mvn -f backend/java/cretas-api/pom.xml test \
  -Dtest='ProductProcessWorkflowConfigToolTest,ProductProcessWorkflowConfigToolBomFieldsTest,ProductProcessWorkflowConfigToolWriteTest' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
echo "REAL_EXIT=$?"
```

Expected: `REAL_EXIT=0`。

⚠️ **本仓没有 `backend/java/pom.xml` 聚合 pom** —— `cretas-api` 直接带自己的 pom，
所以命令是 `-f backend/java/cretas-api/pom.xml`，**没有 `-pl` 也没有 `-am`**。
（`-pl/-am` 是 `cretas-modular` 仓的写法，本仓照抄会报 "POM file pom.xml ... does not exist"。）
`-Dsurefire.failIfNoSpecifiedTests=false` 仍然保留：它防的是「没有匹配测试」被误读成测试红。

- [ ] **Step 6: Commit（本任务无代码改动，仅记录验证结论）**

```bash
git commit --allow-empty -m "test(canvas-tool): 变异验证三条承重断言

变异 A: factoryId 改成从 AI 入参取 -> factoryIdInArgumentsIsIgnored 红
变异 B: 校验挪到落库之后         -> rejectedPatchNeverTouchesTheDatabase 红
两次都还原即绿, 探针无残留。

⚠️ 变异脚本都带 assert 锚点命中次数==1 ——
Python 的 str.replace 无匹配时静默返回原串, 那正好长得像「闸没抓住」。"
```

---

## Self-Review

**1. 覆盖检查**

| 目标 | 任务 |
|---|---|
| execute 能落库 | Task 2 Step 3 |
| 只写草稿不碰发布 | Task 2 Step 3（只调 `saveDraft`，结构性保证） |
| factoryId 不受 AI 控制 | Task 2 Step 3 `requireFactoryId` + Task 3 变异 A |
| 校验不过不碰库 | Task 1（共用链路）+ Task 3 变异 B |
| 冲突不吞 | Task 2 Step 1 第 5 条测试 |
| 既有断言不被改坏 | Task 1 Step 5 / Task 2 Step 4（明写⛔不许改断言） |

**2. 未覆盖 / 刻意不做**

- **`doPreview` 不用加** —— `supportsPreview()` 已返回 true，`preview()` 已实现（地基事实表第 1 行）。
- **「进白名单」不做** —— 主仓无 `writeToolAllowlist` 机制（地基事实表倒数第 2 行）。
- **BOM 融合后的辅料/包材字段不在本计划** —— `ALLOWED_FIELD_ROOTS` 目前无相关 key，
  扩展应随融合实施一起排，否则会出现「人能配、agent 配不了同一张画布」。
- **端到端真跑未列为任务** —— 本计划全是单元级。上线前需在 test 环境用真实产品走一次
  「agent 出补丁 → 落草稿 → 人在页面看到 → 人发布」，⛔ 单元绿不等于这条链通。

**3. 类型一致性**

`buildValidatedCandidate` 在 Task 1 定义、Task 2 消费，签名一致；
构造函数三参数签名在 Task 2 Step 3 定义，Task 2 Step 1/Step 4 两处调用一致；
`saveDraft(String, String, ProductProcessWorkflowDTO)` 与实测签名一致
（`ProductProcessWorkflowServiceImpl.java:66-69`）。
