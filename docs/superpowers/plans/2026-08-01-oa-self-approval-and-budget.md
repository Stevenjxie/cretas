# OA 自审修复 + 审批内容可读可操作 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让六膳门这类单人工厂的 admin 能审批自己发起的单，并让 OA 待办列表里的审批条目显示得懂、点得动。

**Architecture:** 把 `PurchaseServiceImpl` 里已有但 private 的「点名例外」提取成独立的 `SelfApprovalPolicy` bean，销售/采购/调拨三处统一委托它（点名 OR 工厂超管）；展示侧让后端把权威表 `DecisionTypeMetadataRegistry` 的中文名直接放进 DTO，前端不再维护第二份表；BUDGET 走已有的 `/{instanceId}/actions` 统一入口接上会计期间关账。

**Tech Stack:** Java 21 + Spring Boot 3.2.12 + JPA；JUnit 5 + Mockito；Vue 3 + TypeScript + Element Plus + Vitest。

**Spec:** `docs/superpowers/specs/2026-08-01-oa-self-approval-and-budget-design.md`

## Global Constraints

- 基线 `origin/main` = `f327994223`，worktree `codex/claude-oa-selfapproval-budget`。
- 跑 Java 测试**必须** `mvn clean test`，不能只 `mvn test`（maven 增量编译不重编，会给假红/假绿）。
- 本机 Java 需 `export JAVA_HOME="C:/Program Files/Zulu/zulu-21"`。
- commit 用 `git commit -m "..." -- <显式路径>` 锁范围；**新建文件必须先 `git add`**，否则 `git commit -- <paths>` 会静默跳过。
- 每个测试都要**先证明它在实现前是红的**，再写实现。
- 工厂超管角色码字面量为 `factory_super_admin`。
- 🔒 标记的 Task 属红线（财务关账）：做完 PR 即停，**不自合、不自部署**。
- **不动**这三处自审校验：`FactoryStocktakeServiceImpl:402`、`FactoryStocktakeServiceImpl:902`、`ReportReversalServiceImpl:406`（它们是独立业务语义，理由见 spec §1）。

---

## File Structure

**PR-1（自审，不碰财务）**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/SelfApprovalPolicy.java` — 自审例外的唯一判定处
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/SelfApprovalPolicyTest.java`
- Create: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/SelfApprovalCarrierContractTest.java` — 承载点清点契约
- Modify: `.../service/inventory/impl/PurchaseServiceImpl.java`（删 private 方法、改为委托）
- Modify: `.../service/inventory/impl/SalesServiceImpl.java:1125-1129`
- Modify: `.../service/inventory/impl/TransferServiceImpl.java:711-715`

**PR-2（展示 + BUDGET）🔒**
- Modify: `.../dto/workflow/WorkflowInstancePendingDTO.java`（加 `moduleLabel`、`systemInitiated`）
- Modify: `.../controller/workflow/WorkflowInstanceController.java`（hydrate + BUDGET 分支）
- Modify: `.../service/finance/AccountingPeriodService.java` + `impl/AccountingPeriodServiceImpl.java`（加 `applyWorkflowAction`）
- Modify: `web-admin/src/views/workflow/pending.vue`
- Tests: 对应 `src/test/java/...` 与 `web-admin/src/views/workflow/__tests__/`

---

# PR-1：自审例外统一

### Task 1: SelfApprovalPolicy —— 把点名例外提取成唯一判定处

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/SelfApprovalPolicy.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/SelfApprovalPolicyTest.java`

**Interfaces:**
- Consumes: `ApprovalWorkflowService#getById(String, String)`、`#deserializeNodes(String)`（已存在，见 `PurchaseServiceImpl:852-892` 的用法）
- Produces: `SelfApprovalPolicy#allowsSelfApproval(String factoryId, ApprovalWorkflowInstance instance, Long actorId, String actorRole) -> boolean` —— Task 2/3/4 都调它

- [ ] **Step 1: 写失败测试**

```java
package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.workflow.ApprovalWorkflow;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelfApprovalPolicyTest {

    private ApprovalWorkflowService approvalWorkflowService;
    private SelfApprovalPolicy policy;

    @BeforeEach
    void setUp() {
        approvalWorkflowService = mock(ApprovalWorkflowService.class);
        policy = new SelfApprovalPolicy(approvalWorkflowService);
    }

    private ApprovalWorkflowInstance instanceAtNode(String nodeId) {
        ApprovalWorkflowInstance instance = new ApprovalWorkflowInstance();
        instance.setWorkflowId("wf-1");
        instance.setCurrentNodeIds(List.of(nodeId));
        return instance;
    }

    private void givenNodeApprovers(String nodeId, List<Object> approverUserIds) {
        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setNodesJson("[]");
        when(approvalWorkflowService.getById(anyString(), anyString()))
                .thenReturn(Optional.of(workflow));
        ApprovalWorkflowNode node = new ApprovalWorkflowNode();
        node.setId(nodeId);
        node.setType("approval");
        node.setConfig(Map.of("approverUserIds", approverUserIds));
        when(approvalWorkflowService.deserializeNodes(any())).thenReturn(List.of(node));
    }

    @Test
    void 节点显式点名发起人时允许自审() {
        givenNodeApprovers("admin_approval", List.of(1638));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isTrue();
    }

    @Test
    void 工厂超管即使没被点名也允许自审() {
        givenNodeApprovers("admin_approval", List.of(9999));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "factory_super_admin"))
                .isTrue();
    }

    @Test
    void 既未点名又非超管则不允许自审() {
        givenNodeApprovers("admin_approval", List.of(9999));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isFalse();
    }

    @Test
    void 点名以字符串形式配置时同样识别() {
        givenNodeApprovers("admin_approval", List.of("1638"));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isTrue();
    }

    @Test
    void 实例没有当前节点时不允许自审() {
        ApprovalWorkflowInstance instance = new ApprovalWorkflowInstance();
        instance.setWorkflowId("wf-1");
        instance.setCurrentNodeIds(List.of());
        assertThat(policy.allowsSelfApproval("LIUSHANMEN", instance, 1638L, "sales_manager"))
                .isFalse();
    }

    @Test
    void 超管判定不依赖工作流服务_服务不可用时仍放行超管() {
        SelfApprovalPolicy noServicePolicy = new SelfApprovalPolicy(null);
        assertThat(noServicePolicy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "factory_super_admin"))
                .isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认它是红的**

```bash
cd backend/java/cretas-api && export JAVA_HOME="C:/Program Files/Zulu/zulu-21"
mvn clean test -Dtest=SelfApprovalPolicyTest
```
Expected: FAIL —— 编译不过，`SelfApprovalPolicy` 不存在。

- [ ] **Step 3: 写实现**

把 `PurchaseServiceImpl:852-892` 的 `isExplicitCurrentNodeApprover` **移动**（不是复制）到这里：

```java
package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.workflow.ApprovalWorkflow;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 「发起人能否审批自己的单」的唯一判定处。
 *
 * <p>此前该判定散落在各业务 service：采购单有 {@code isExplicitCurrentNodeApprover}
 * 私有实现，销售单与调拨单则无条件禁止 —— 同一条规则三处承载、行为不一致。
 *
 * <p>⚠️ 盘点（{@code STOCKTAKE_SELF_APPROVAL_FORBIDDEN}，两处）与撤回冲销
 * （{@code SELF_APPROVAL_FORBIDDEN}）**刻意不走本类**：它们是独立业务语义
 * （盘点仅在存在盘盈/盘亏时以 409 拦截，判据还含录入人与提交人），不是本规则的实例。
 * 见 spec 2026-08-01-oa-self-approval-and-budget-design.md §1。
 */
@Component
public class SelfApprovalPolicy {

    private static final String FACTORY_SUPER_ADMIN = "factory_super_admin";

    private final ApprovalWorkflowService approvalWorkflowService;

    @Autowired(required = false)
    public SelfApprovalPolicy(ApprovalWorkflowService approvalWorkflowService) {
        this.approvalWorkflowService = approvalWorkflowService;
    }

    /**
     * @return true 表示「虽然 actor 就是发起人，但允许他审批」
     */
    public boolean allowsSelfApproval(String factoryId,
                                      ApprovalWorkflowInstance instance,
                                      Long actorId,
                                      String actorRole) {
        if (FACTORY_SUPER_ADMIN.equals(actorRole)) {
            return true;
        }
        return isExplicitCurrentNodeApprover(factoryId, instance, actorId);
    }

    /**
     * A workflow may intentionally name the initiator as the approver for a single-user factory.
     * Role membership alone is not enough to bypass separation of duties: the active node must
     * explicitly contain the actor in {@code approverUserIds}.
     */
    private boolean isExplicitCurrentNodeApprover(
            String factoryId,
            ApprovalWorkflowInstance instance,
            Long actorId) {
        if (approvalWorkflowService == null
                || actorId == null
                || instance.getCurrentNodeIds() == null
                || instance.getCurrentNodeIds().isEmpty()) {
            return false;
        }
        ApprovalWorkflow workflow = approvalWorkflowService
                .getById(factoryId, instance.getWorkflowId())
                .orElse(null);
        if (workflow == null) {
            return false;
        }
        String currentNodeId = instance.getCurrentNodeIds().get(0);
        ApprovalWorkflowNode currentNode = approvalWorkflowService
                .deserializeNodes(workflow.getNodesJson()).stream()
                .filter(node -> currentNodeId.equals(node.getId()))
                .findFirst()
                .orElse(null);
        if (currentNode == null
                || !"approval".equalsIgnoreCase(currentNode.getType())
                || currentNode.getConfig() == null) {
            return false;
        }
        Object configuredApprovers = currentNode.getConfig().get("approverUserIds");
        if (!(configuredApprovers instanceof Iterable<?> approvers)) {
            return false;
        }
        for (Object configuredApprover : approvers) {
            if (configuredApprover instanceof Number number
                    && actorId.equals(number.longValue())) {
                return true;
            }
            if (configuredApprover != null
                    && actorId.toString().equals(String.valueOf(configuredApprover).trim())) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: 跑测试确认绿**

```bash
mvn clean test -Dtest=SelfApprovalPolicyTest
```
Expected: PASS，6 个测试全过。

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/SelfApprovalPolicy.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/SelfApprovalPolicyTest.java
git commit -m "feat(oa): 提取 SelfApprovalPolicy 作为自审例外的唯一判定处" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/SelfApprovalPolicy.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/SelfApprovalPolicyTest.java
```

---

### Task 2: 采购单改为委托（行为放宽：多了 admin 分支）

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java`（第 679-685 行的判断；删除 852-892 的 private 方法）

**Interfaces:**
- Consumes: `SelfApprovalPolicy#allowsSelfApproval`（Task 1）
- Produces: 无新接口

- [ ] **Step 1: 写失败测试**

Create `backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/PurchaseSelfApprovalDelegationTest.java`：

```java
package com.cretas.aims.service.inventory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采购单的自审例外必须委托 SelfApprovalPolicy，不能再留私有实现 ——
 * 私有实现正是销售/调拨两处无法复用、进而行为不一致的原因。
 */
class PurchaseSelfApprovalDelegationTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java");

    private String source() throws IOException {
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    @Test
    void 采购单委托给统一策略() throws IOException {
        assertThat(source())
                .as("PurchaseServiceImpl 应调用 selfApprovalPolicy.allowsSelfApproval")
                .contains("selfApprovalPolicy.allowsSelfApproval");
    }

    @Test
    void 私有实现已被移除() throws IOException {
        assertThat(source())
                .as("isExplicitCurrentNodeApprover 应已移动到 SelfApprovalPolicy，此处不应残留私有副本")
                .doesNotContain("private boolean isExplicitCurrentNodeApprover");
    }
}
```

- [ ] **Step 2: 跑测试确认它是红的**

```bash
mvn clean test -Dtest=PurchaseSelfApprovalDelegationTest
```
Expected: FAIL —— 两条都失败（现在既没有委托调用，私有方法也还在）。

- [ ] **Step 3: 改实现**

在 `PurchaseServiceImpl` 字段区（约 234 行 `approvalWorkflowService` 附近）加注入：

```java
    @Autowired(required = false)
    private SelfApprovalPolicy selfApprovalPolicy;
```

把 679-685 行改成：

```java
        if (actorId != null
                && actorId.equals(instance.getInitiatedBy())
                && (selfApprovalPolicy == null
                    || !selfApprovalPolicy.allowsSelfApproval(factoryId, instance, actorId, actorRole))) {
            throw new BusinessException(403, "发起人不能审批自己的采购单")
                    .withCode("PURCHASE_SELF_APPROVAL_FORBIDDEN")
                    .withHint("请由当前 OA 节点授权的其他审批人处理，或在 Canvas 中明确将发起人配置为该节点审批人");
        }
```

删除 852-892 行的 `private boolean isExplicitCurrentNodeApprover(...)` 整个方法。
加 import：`com.cretas.aims.service.workflow.SelfApprovalPolicy`。

- [ ] **Step 4: 跑测试确认绿（含既有采购测试不回归）**

```bash
mvn clean test -Dtest='PurchaseSelfApprovalDelegationTest,Purchase*Test'
```
Expected: PASS，且既有 Purchase 测试数量与改动前一致。

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/PurchaseSelfApprovalDelegationTest.java
git commit -m "refactor(oa): 采购单自审例外改为委托 SelfApprovalPolicy" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/PurchaseSelfApprovalDelegationTest.java
```

---

### Task 3: 销售单与调拨单接上同一策略

**Files:**
- Modify: `.../service/inventory/impl/SalesServiceImpl.java:1125-1129`
- Modify: `.../service/inventory/impl/TransferServiceImpl.java:711-715`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/SelfApprovalCarrierContractTest.java`

**Interfaces:**
- Consumes: `SelfApprovalPolicy#allowsSelfApproval`（Task 1）
- Produces: 无新接口

- [ ] **Step 1: 写失败测试（横跨三处的一致性契约）**

```java
package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「发起人不能审批自己」这条规则在本仓有多个承载点。历史上采购单修了而销售/调拨没跟，
 * 造成同一条规则三种行为。本契约锁两件事：
 *   1) 销售/采购/调拨三处必须委托同一个 SelfApprovalPolicy；
 *   2) 承载点总数必须与已登记的一致 —— 新增第 7 处时强制先做归类决定。
 */
class SelfApprovalCarrierContractTest {

    private static final Path SRC = Path.of("src/main/java");

    /** 统一语义的三处：必须委托 SelfApprovalPolicy。 */
    private static final List<String> UNIFIED = List.of(
            "com/cretas/aims/service/inventory/impl/SalesServiceImpl.java",
            "com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java",
            "com/cretas/aims/service/inventory/impl/TransferServiceImpl.java");

    /** 刻意保持独立语义的三处：不得改为委托（改了要先改 spec）。 */
    private static final List<String> INDEPENDENT = List.of(
            "com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImpl.java",
            "com/cretas/aims/service/reversal/impl/ReportReversalServiceImpl.java");

    @Test
    void 统一语义的三处都委托同一策略() throws IOException {
        for (String relative : UNIFIED) {
            String source = Files.readString(SRC.resolve(relative), StandardCharsets.UTF_8);
            assertThat(source)
                    .as(relative + " 必须委托 SelfApprovalPolicy.allowsSelfApproval，"
                            + "否则同一条规则又会三处三种行为")
                    .contains("selfApprovalPolicy.allowsSelfApproval");
        }
    }

    @Test
    void 自审校验承载点总数未悄悄增加() throws IOException {
        Pattern marker = Pattern.compile("SELF_APPROVAL_FORBIDDEN");
        int found = 0;
        List<Path> files;
        try (var walk = Files.walk(SRC)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            Matcher m = marker.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                found++;
            }
        }
        assertThat(found)
                .as("自审校验承载点从 6 变了。新增一处时必须先决定它属于"
                        + "『统一语义』(委托 SelfApprovalPolicy) 还是『独立业务语义』"
                        + "(如盘点仅盘盈亏时 409 拦截)，并同步更新本契约与 spec")
                .isEqualTo(6);
    }

    @Test
    void 独立语义的三处未被顺手统一() throws IOException {
        for (String relative : INDEPENDENT) {
            String source = Files.readString(SRC.resolve(relative), StandardCharsets.UTF_8);
            assertThat(source)
                    .as(relative + " 是独立业务语义，不应委托 SelfApprovalPolicy。"
                            + "若确实要统一，先改 spec 再改这里")
                    .doesNotContain("selfApprovalPolicy.allowsSelfApproval");
        }
    }
}
```

- [ ] **Step 2: 跑测试确认它是红的**

```bash
mvn clean test -Dtest=SelfApprovalCarrierContractTest
```
Expected: FAIL —— `统一语义的三处都委托同一策略` 在 Sales/Transfer 上失败（此刻只有 Purchase 委托了）。
另两条应当通过。

- [ ] **Step 3: 改实现**

`SalesServiceImpl` 字段区加注入，并把 1125-1129 改成：

```java
        if (actorId != null
                && actorId.equals(instance.getInitiatedBy())
                && (selfApprovalPolicy == null
                    || !selfApprovalPolicy.allowsSelfApproval(factoryId, instance, actorId, actorRole))) {
            throw new BusinessException(403, "发起人不能审批自己的销售订单")
                    .withCode("SALES_SELF_APPROVAL_FORBIDDEN")
                    .withHint("请由当前 OA 节点授权的其他审批人处理，或在 Canvas 中明确将发起人配置为该节点审批人");
        }
```

`TransferServiceImpl` 同样加注入，把 711-715 改成：

```java
        if (actorId != null
                && actorId.equals(instance.getInitiatedBy())
                && (selfApprovalPolicy == null
                    || !selfApprovalPolicy.allowsSelfApproval(factoryId, instance, actorId, actorRole))) {
            throw new BusinessException(403, "发起人不能审批自己的调拨单")
                    .withCode("TRANSFER_SELF_APPROVAL_FORBIDDEN")
                    .withHint("请由当前 OA 节点授权的其他审批人处理，或在 Canvas 中明确将发起人配置为该节点审批人");
        }
```

两个文件都加 import：`com.cretas.aims.service.workflow.SelfApprovalPolicy`
与 `org.springframework.beans.factory.annotation.Autowired`（若尚未引入）。

- [ ] **Step 4: 跑测试确认绿**

```bash
mvn clean test -Dtest='SelfApprovalCarrierContractTest,SelfApprovalPolicyTest,PurchaseSelfApprovalDelegationTest,Sales*Test,Transfer*Test'
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/SelfApprovalCarrierContractTest.java
git commit -m "fix(oa): 销售单与调拨单接上统一自审策略, 并锁住承载点清单" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/SalesServiceImpl.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/TransferServiceImpl.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/SelfApprovalCarrierContractTest.java
```

---

### Task 4: PR-1 收口

- [ ] **Step 1: 全量回归**

```bash
cd backend/java/cretas-api && export JAVA_HOME="C:/Program Files/Zulu/zulu-21"
mvn clean test -Dtest='SelfApproval*Test,Purchase*Test,Sales*Test,Transfer*Test,Stocktake*Test' 2>&1 | grep -E "Tests run:.*Failures|BUILD"
```
Expected: `Failures: 0, Errors: 0` 且 `BUILD SUCCESS`。

- [ ] **Step 2: 确认 scope 干净**

```bash
git diff origin/main...HEAD --stat
```
Expected: 只有本 PR 的 6 个文件 + spec/plan 文档，无并发 session 夹带。

- [ ] **Step 3: 开 PR**

```bash
git push origin codex/claude-oa-selfapproval-budget
gh pr create --title "fix(oa): 发起人自审例外统一到 SelfApprovalPolicy (销售/采购/调拨三处)" --body "见 docs/superpowers/specs/2026-08-01-oa-self-approval-and-budget-design.md §1/§3.A

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_015SKW6xUrwfH6FKk9bAWM8Y"
```

---

# PR-2：展示可读 + BUDGET 可操作 🔒

> 从这里开始碰会计期间关账。**做到 PR 为止，不自合、不自部署。**

### Task 5: DTO 加 moduleLabel 与 systemInitiated

**Files:**
- Modify: `.../dto/workflow/WorkflowInstancePendingDTO.java`
- Modify: `.../controller/workflow/WorkflowInstanceController.java`（hydrate 段，约 200-245 行）
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/workflow/PendingModuleLabelTest.java`

**Interfaces:**
- Consumes: `DecisionTypeMetadataRegistry`（已存在，36 个 moduleCode 各带 `chineseName`）
- Produces: DTO 字段 `String moduleLabel`、`boolean systemInitiated` —— Task 7 的前端依赖它们

- [ ] **Step 1: 写失败测试**

```java
package com.cretas.aims.controller.workflow;

import com.cretas.aims.service.workflow.DecisionTypeMetadataRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PendingModuleLabelTest {

    @Autowired
    private DecisionTypeMetadataRegistry registry;

    @Test
    void 权威表每个moduleCode都能解析出中文名() {
        registry.all().forEach(metadata -> {
            if (metadata.getModuleCode() == null) return;
            assertThat(metadata.getChineseName())
                    .as("moduleCode " + metadata.getModuleCode()
                            + " 没有中文名, 前端会显示成「未知状态(" + metadata.getModuleCode() + ")」")
                    .isNotBlank();
            assertThat(metadata.getChineseName()).doesNotContain("未知");
        });
    }
}
```

> ⚠️ 实现前先确认 `DecisionTypeMetadataRegistry` 暴露的遍历方法名。若不是 `all()`，
> 用它实际提供的只读集合访问器替换，并在本任务内保持一致。

- [ ] **Step 2: 跑测试确认它是红的或直接可跑**

```bash
mvn clean test -Dtest=PendingModuleLabelTest
```
Expected: 若 `all()` 不存在则编译失败 → 改用真实方法名后应 PASS（这条是护栏，用于证明权威表本身是完整的）。

- [ ] **Step 3: 加 DTO 字段与 hydrate**

`WorkflowInstancePendingDTO` 加两个字段：

```java
    /** 业务类型中文名, 取自 DecisionTypeMetadataRegistry (权威表)。前端不再自行维护映射。 */
    private String moduleLabel;

    /** true 表示该实例由定时任务等系统流程发起, 没有人类申请人 (initiatedBy 为 null)。 */
    private boolean systemInitiated;
```

`WorkflowInstanceController` 在 build DTO 处补：

```java
                    .moduleLabel(resolveModuleLabel(inst))
                    .systemInitiated(inst.getInitiatedBy() == null)
```

新增私有方法：

```java
    /**
     * BUDGET 一码多用 (预算 + 超预算授权 + 期间结账), 泛称「预算审批」对期间结账不够准,
     * 故按 context 的 entityType 细化。取不到时返回 null, 由前端兜底 —— 后端不编造。
     */
    private String resolveModuleLabel(ApprovalWorkflowInstance instance) {
        String moduleCode = instance.getModuleCode();
        if (moduleCode == null) {
            return null;
        }
        if ("BUDGET".equals(moduleCode)
                && instance.getContextJson() != null
                && instance.getContextJson().contains("ACCOUNTING_PERIOD")) {
            return "会计期间结账";
        }
        if (decisionTypeMetadataRegistry == null) {
            return null;
        }
        return decisionTypeMetadataRegistry.findByModuleCode(moduleCode)
                .map(DecisionTypeMetadata::getChineseName)
                .orElse(null);
    }
```

> ⚠️ 若 `DecisionTypeMetadataRegistry` 没有 `findByModuleCode`，在该 registry 上新增一个
> 只读查找方法（PostConstruct 时一并 build 一个 `Map<String, DecisionTypeMetadata>`），
> 不要在 Controller 里遍历。

- [ ] **Step 4: 跑测试确认绿**

```bash
mvn clean test -Dtest='PendingModuleLabelTest,WorkflowInstance*Test'
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/test/java/com/cretas/aims/controller/workflow/PendingModuleLabelTest.java
git commit -m "feat(oa): 待办 DTO 补 moduleLabel 与 systemInitiated, 中文名取自权威表" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/dto/workflow/WorkflowInstancePendingDTO.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/controller/workflow/WorkflowInstanceController.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/DecisionTypeMetadataRegistry.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/controller/workflow/PendingModuleLabelTest.java
```

---

### Task 6: BUDGET 的 businessSummary 可读编号

**Files:**
- Modify: `.../controller/workflow/WorkflowInstanceController.java`（hydrate 段）
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/workflow/BudgetBusinessSummaryTest.java`

**Interfaces:**
- Consumes: `AccountingPeriodRepository`（按 id 批量查）
- Produces: 无新接口

- [ ] **Step 1: 写失败测试**

```java
package com.cretas.aims.controller.workflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUDGET 待办此前把 businessEntityId 的裸 UUID 甩给用户 ——
 * hydrate 只覆盖了 PURCHASE_ORDER 与 SALES_ORDER。
 */
class BudgetBusinessSummaryTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/cretas/aims/controller/workflow/WorkflowInstanceController.java");

    @Test
    void BUDGET有独立的businessSummary分支() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertThat(source)
                .as("BUDGET 必须有 businessSummary hydrate 分支, 否则用户只看到裸 UUID")
                .contains("BUDGET".concat("\".equals(inst.getModuleCode())"));
    }

    @Test
    void BUDGET批量查期间而非逐条() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertThat(source)
                .as("与 PO/SO 一致, BUDGET 也要批量 fetch 避免 1+N")
                .contains("findAllById(periodIds)");
    }
}
```

- [ ] **Step 2: 跑测试确认它是红的**

```bash
mvn clean test -Dtest=BudgetBusinessSummaryTest
```
Expected: FAIL —— 两条都没有。

- [ ] **Step 3: 加 BUDGET 分支**

在收集 id 的循环里加：

```java
            if ("BUDGET".equals(inst.getModuleCode()) && inst.getBusinessEntityId() != null) {
                periodIds.add(inst.getBusinessEntityId());
            }
```

批量 fetch：

```java
        Map<String, AccountingPeriod> periodById = new HashMap<>();
        if (!periodIds.isEmpty() && accountingPeriodRepository != null) {
            try {
                accountingPeriodRepository.findAllById(periodIds)
                        .forEach(p -> periodById.put(p.getId(), p));
            } catch (Exception e) {
                log.warn("批量加载会计期间失败 (businessSummary 退化为 fallback): {}", e.getMessage());
            }
        }
```

在 `buildBusinessSummary` 相应处加：

```java
        if ("BUDGET".equals(inst.getModuleCode())) {
            AccountingPeriod period = periodById.get(inst.getBusinessEntityId());
            if (period != null) {
                return String.format("%d 年 %d 月 会计期间", period.getYear(), period.getMonth());
            }
        }
```

查不到时保持既有 fallback，不改。

- [ ] **Step 4: 跑测试确认绿**

```bash
mvn clean test -Dtest='BudgetBusinessSummaryTest,WorkflowInstance*Test'
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/test/java/com/cretas/aims/controller/workflow/BudgetBusinessSummaryTest.java
git commit -m "feat(oa): BUDGET 待办显示可读期间编号而非裸 UUID" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/controller/workflow/WorkflowInstanceController.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/controller/workflow/BudgetBusinessSummaryTest.java
```

---

### Task 7: 前端 —— 用权威表、显示系统发起

**Files:**
- Modify: `web-admin/src/views/workflow/pending.vue`
- Test: `web-admin/src/views/workflow/__tests__/pendingModuleLabel.source.spec.ts`

**Interfaces:**
- Consumes: DTO 的 `moduleLabel`、`systemInitiated`（Task 5）
- Produces: 无

- [ ] **Step 1: 写失败测试**

```typescript
import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

const source = fs.readFileSync(
  path.resolve(__dirname, '../pending.vue'),
  'utf-8',
);

describe('待办列表业务类型与申请人', () => {
  it('业务类型优先用后端权威表给的 moduleLabel', () => {
    expect(source).toContain('row.moduleLabel');
  });

  it('MODULE_LABELS 降级为兜底并写明权威表在后端', () => {
    expect(source).toContain('DecisionTypeMetadataRegistry');
  });

  it('系统发起的实例显示「系统自动发起」而不是空白', () => {
    expect(source).toContain('系统自动发起');
    expect(source).toContain('row.systemInitiated');
  });
});
```

- [ ] **Step 2: 跑测试确认它是红的**

```bash
cd web-admin && npx vitest run src/views/workflow/__tests__/pendingModuleLabel.source.spec.ts
```
Expected: FAIL —— 三条都没有。

- [ ] **Step 3: 改实现**

`PendingApproval` interface 加两个字段：

```typescript
  moduleLabel?: string;
  systemInitiated?: boolean;
```

`MODULE_LABELS` 上方加注释并保留：

```typescript
// ⚠️ 权威表在后端 DecisionTypeMetadataRegistry (36 个 moduleCode, 各带 chineseName),
// 后端已通过 DTO 的 moduleLabel 下发。此处仅为后端没给时的离线兜底 ——
// 不要在这里加新码, 加了也会漂 (历史上这里只有 4 个, 另外 30 多个全显示「未知状态(X)」)。
const MODULE_LABELS: Record<string, string> = {
```

业务类型列改为：

```vue
        <el-table-column label="业务类型" width="120">
          <template #default="{ row }">{{ row.moduleLabel || enumLabel(row.moduleCode, MODULE_LABELS) }}</template>
        </el-table-column>
```

申请人列改为：

```vue
        <el-table-column label="申请人" width="130">
          <template #default="{ row }">
            {{ row.systemInitiated ? '系统自动发起' : (row.initiatedByUsername || '—') }}
          </template>
        </el-table-column>
```

- [ ] **Step 4: 跑测试确认绿**

```bash
cd web-admin && npx vitest run src/views/workflow/ && npx vue-tsc -b --force
```
Expected: PASS 且类型检查无报错。

- [ ] **Step 5: Commit**

```bash
git add web-admin/src/views/workflow/__tests__/pendingModuleLabel.source.spec.ts
git commit -m "fix(oa): 待办业务类型改用后端权威表, 系统发起不再显示空白申请人" -- \
  web-admin/src/views/workflow/pending.vue \
  web-admin/src/views/workflow/__tests__/pendingModuleLabel.source.spec.ts
```

---

### Task 8 🔒: 会计期间接入 OA 动作

**Files:**
- Modify: `.../service/finance/AccountingPeriodService.java`
- Modify: `.../service/finance/impl/AccountingPeriodServiceImpl.java`
- Modify: `.../controller/workflow/WorkflowInstanceController.java`（`executeDomainAction`）
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finance/AccountingPeriodWorkflowActionTest.java`

**Interfaces:**
- Consumes: 已有的 `confirmClose(String factoryId, Integer year, Integer month, Long userId)`
- Produces: `AccountingPeriodService#applyWorkflowAction(String factoryId, String periodId, Long actorId, HistoryAction action, String notes) -> AccountingPeriod`

- [ ] **Step 1: 写失败测试**

```java
package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AccountingPeriodWorkflowActionTest {

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    @Test
    void 审批通过则期间关闭() {
        AccountingPeriod period = accountingPeriodService.requestClose("F001", 2026, 3, 1L);
        assertThat(period.getStatus()).isEqualTo(AccountingPeriod.Status.PENDING_CLOSE);

        AccountingPeriod after = accountingPeriodService.applyWorkflowAction(
                "F001", period.getId(), 1L, HistoryAction.APPROVE, null);
        assertThat(after.getStatus()).isEqualTo(AccountingPeriod.Status.CLOSED);
    }

    @Test
    void 驳回则期间回到OPEN() {
        AccountingPeriod period = accountingPeriodService.requestClose("F001", 2026, 4, 1L);
        AccountingPeriod after = accountingPeriodService.applyWorkflowAction(
                "F001", period.getId(), 1L, HistoryAction.REJECT, "成本还没核完");
        assertThat(after.getStatus()).isEqualTo(AccountingPeriod.Status.OPEN);
    }

    @Test
    void 重复驳回幂等不报错() {
        AccountingPeriod period = accountingPeriodService.requestClose("F001", 2026, 5, 1L);
        accountingPeriodService.applyWorkflowAction(
                "F001", period.getId(), 1L, HistoryAction.REJECT, "第一次");
        AccountingPeriod again = accountingPeriodService.applyWorkflowAction(
                "F001", period.getId(), 1L, HistoryAction.REJECT, "第二次");
        assertThat(again.getStatus()).isEqualTo(AccountingPeriod.Status.OPEN);
    }
}
```

- [ ] **Step 2: 跑测试确认它是红的**

```bash
mvn clean test -Dtest=AccountingPeriodWorkflowActionTest
```
Expected: FAIL —— `applyWorkflowAction` 不存在，编译失败。

- [ ] **Step 3: 写实现**

`AccountingPeriodService` 接口加：

```java
    /**
     * OA 统一动作入口。APPROVE → confirmClose (含库存台账快照); REJECT → 期间回 OPEN。
     *
     * <p>🔒 APPROVE 会执行月度关账: 期间转 CLOSED、生成库存台账快照、凭证进入 20 天调整窗口。
     */
    AccountingPeriod applyWorkflowAction(String factoryId, String periodId, Long actorId,
                                         HistoryAction action, String notes);
```

`AccountingPeriodServiceImpl` 实现：

```java
    @Override
    @Transactional
    public AccountingPeriod applyWorkflowAction(String factoryId, String periodId, Long actorId,
                                                HistoryAction action, String notes) {
        AccountingPeriod period = accountingPeriodRepository.findById(periodId)
                .filter(p -> factoryId.equals(p.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("会计期间不存在: " + periodId));

        if (action == HistoryAction.APPROVE) {
            // 复用既有关账链路 (含 InventoryLedgerSnapshotService), 不另写一套。
            return confirmClose(factoryId, period.getYear(), period.getMonth(), actorId);
        }

        // REJECT: 撤销本次 requestClose, 期间重新开放, 由财务改完再发起。
        if (period.getStatus() == AccountingPeriod.Status.OPEN) {
            log.debug("[AccountingPeriod] REJECT 幂等命中 (already OPEN): {}/{}-{}",
                    factoryId, period.getYear(), period.getMonth());
            return period;
        }
        if (period.getStatus() != AccountingPeriod.Status.PENDING_CLOSE) {
            throw new BusinessException(409, String.format(
                    "%d-%02d 期间状态=%s, 仅 PENDING_CLOSE 可驳回",
                    period.getYear(), period.getMonth(), period.getStatus()));
        }
        period.setStatus(AccountingPeriod.Status.OPEN);
        accountingPeriodRepository.save(period);
        log.info("[AccountingPeriod] REJECT → OPEN {}-{}-{} by user={} notes={}",
                factoryId, period.getYear(), period.getMonth(), actorId, notes);
        return period;
    }
```

`WorkflowInstanceController.executeDomainAction` 在 `INVENTORY_ADJUSTMENT` 分支后加：

```java
        } else if ("BUDGET".equals(instance.getModuleCode())) {
            com.cretas.aims.entity.finance.AccountingPeriod period =
                    accountingPeriodService.applyWorkflowAction(
                            factoryId, instance.getBusinessEntityId(), user.getId(), action, notes);
            businessEntityId = period.getId();
            businessStatus = period.getStatus().name();
```

- [ ] **Step 4: 跑测试确认绿**

```bash
mvn clean test -Dtest='AccountingPeriodWorkflowActionTest,AccountingPeriod*Test'
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/test/java/com/cretas/aims/service/finance/AccountingPeriodWorkflowActionTest.java
git commit -m "feat(oa): 会计期间接入统一 OA 动作 (APPROVE 关账 / REJECT 回 OPEN)" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/AccountingPeriodService.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/AccountingPeriodServiceImpl.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/controller/workflow/WorkflowInstanceController.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/finance/AccountingPeriodWorkflowActionTest.java
```

---

### Task 9 🔒: 前端放开 BUDGET 并特化确认文案

**Files:**
- Modify: `web-admin/src/views/workflow/pending.vue`
- Test: `web-admin/src/views/workflow/__tests__/budgetApprovalConfirm.source.spec.ts`

**Interfaces:**
- Consumes: Task 8 的后端 BUDGET 分支
- Produces: 无

- [ ] **Step 1: 写失败测试**

```typescript
import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

const source = fs.readFileSync(
  path.resolve(__dirname, '../pending.vue'),
  'utf-8',
);

describe('BUDGET 关账二次确认', () => {
  it('BUDGET 已进入可操作模块', () => {
    expect(source).toContain("'BUDGET'");
    const actionable = source.match(/ACTIONABLE_MODULE_CODES = new Set\(\[(.*?)\]\)/s)?.[1] ?? '';
    expect(actionable).toContain('BUDGET');
  });

  it('通过前的确认文案写明关账后果而不是通用文案', () => {
    expect(source).toContain('生成库存台账快照');
    expect(source).toContain('20 天调整窗口');
  });
});
```

- [ ] **Step 2: 跑测试确认它是红的**

```bash
cd web-admin && npx vitest run src/views/workflow/__tests__/budgetApprovalConfirm.source.spec.ts
```
Expected: FAIL —— 两条都没有。

- [ ] **Step 3: 改实现**

```typescript
const ACTIONABLE_MODULE_CODES = new Set([
  'PURCHASE_ORDER', 'SALES_ORDER', 'INVENTORY_TRANSFER', 'INVENTORY_ADJUSTMENT', 'BUDGET',
]);
```

`act()` 的 APPROVE 分支改为按模块特化文案：

```typescript
    } else {
      // BUDGET 通过 = 直接执行月度关账 (期间转 CLOSED + 生成库存台账快照 +
      // 凭证进入 20 天调整窗口, 逾期硬锁)。通用文案「确认通过 xxx？」完全没有传达
      // 这个后果, 在待办列表这种批量处理场景下误点代价很高。
      const budgetWarning = row.moduleCode === 'BUDGET'
        ? `\n\n这将关闭「${row.businessSummary || row.businessEntityId}」，并生成库存台账快照。`
          + '\n关账后凭证进入 20 天调整窗口，逾期将硬锁。'
        : '';
      await ElMessageBox.confirm(
        `确认通过「${row.businessSummary || row.businessEntityId}」？${budgetWarning}`,
        row.moduleCode === 'BUDGET' ? '确认关账' : 'OA 审批确认',
        {
          confirmButtonText: row.moduleCode === 'BUDGET' ? '确认关账' : '审批通过',
          cancelButtonText: '取消',
          type: row.moduleCode === 'BUDGET' ? 'warning' : undefined,
        },
      );
    }
```

- [ ] **Step 4: 跑测试确认绿**

```bash
cd web-admin && npx vitest run src/views/workflow/ && npx vue-tsc -b --force
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add web-admin/src/views/workflow/__tests__/budgetApprovalConfirm.source.spec.ts
git commit -m "feat(oa): BUDGET 待办可操作, 通过前明确告知关账后果" -- \
  web-admin/src/views/workflow/pending.vue \
  web-admin/src/views/workflow/__tests__/budgetApprovalConfirm.source.spec.ts
```

---

### Task 10 🔒: PR-2 收口并停下报告

- [ ] **Step 1: 全量回归**

```bash
cd backend/java/cretas-api && export JAVA_HOME="C:/Program Files/Zulu/zulu-21"
mvn clean test -Dtest='SelfApproval*Test,AccountingPeriod*Test,WorkflowInstance*Test,Budget*Test,Pending*Test' 2>&1 | grep -E "Tests run:.*Failures|BUILD"
cd ../../../web-admin && npx vitest run && npx vue-tsc -b --force
```
Expected: 两边都 0 失败。

- [ ] **Step 2: 确认 scope**

```bash
git diff origin/main...HEAD --stat
```

- [ ] **Step 3: 开 PR 并停**

```bash
gh pr create --title "feat(oa): 审批内容可读 + 会计期间接入统一 OA (🔒 含关账)" --body "见 spec §3.B/§3.C。

🔒 **本 PR 含财务关账行为变更, 未自合未自部署**, 等 Steve 终审。
影响面: 所有工厂的会计期间结账审批从「只读」变为「可一键关账」。

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_015SKW6xUrwfH6FKk9bAWM8Y"
```

- [ ] **Step 4: 向 Steve 报告** —— 列出：行为变更影响面、哪些工厂会受影响、回滚方式（revert PR 即可，无 migration）。**不要自行合并或部署。**

---

## Self-Review 结果

- **Spec 覆盖**：§3.A → Task 1–3；§3.B → Task 5–7；§3.C → Task 8–9；§4 测试 T1/T1b → Task 3，T2 → Task 1，T3/T4 → Task 8，T5 → Task 6，T6 → Task 5，T7/T8 → Task 5，T9 → Task 7。§6 拆包 → PR-1（Task 1–4）/ PR-2（Task 5–10）。
- **类型一致性**：`allowsSelfApproval(String, ApprovalWorkflowInstance, Long, String)` 在 Task 1 定义，Task 2/3 调用签名一致；`applyWorkflowAction(String, String, Long, HistoryAction, String)` 在 Task 8 定义并在同任务内被 Controller 调用，参数顺序一致。
- **已知需实现时确认的两点**（已在对应步骤标注，不是占位符）：`DecisionTypeMetadataRegistry` 的遍历方法名与是否已有 `findByModuleCode`；两处都给了「若不存在则如何补」的明确指示。
