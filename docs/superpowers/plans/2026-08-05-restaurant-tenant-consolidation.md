# 餐饮租户收敛（只留 MOCK_REST）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把餐饮板块从 38 个租户收敛到只有 `MOCK_REST` 一个可用，并让它在 Java 餐饮判定、演示身份、四部门权限三条链路上都真正站得住。

**Architecture:** 先修代码（让 `MOCK_REST` 这个 ID 在所有餐饮判定处被认出来、删掉 `DEMO_REST` 别名、放开四个部门 module 白名单），再改数据（补 L1 权限行、停用 37 租户 + 45 用户、建 4 个部门账号），最后清运维面（撤演示流 timer、收敛审计名单）。顺序不能反：判定没修好就停用其它租户，会让唯一活着的租户带着已知缺陷裸奔。

**Tech Stack:** Java 21 + Spring Boot 3.2.12 + JPA/Hibernate 6 + Flyway；Vue 3 + TypeScript（web-admin）；PostgreSQL 15；JUnit 5 + Mockito；systemd。

**Spec:** `docs/superpowers/specs/2026-08-05-restaurant-tenant-consolidation-design.md`
**Worktree:** `C:\Users\Steve\cretas-rest-consolidation`，分支 `codex/claude-restaurant-tenant-consolidation`
**Base:** `origin/main` @ `37e9e2685ecd7dfb861cf33454ab23106e81d075`

---

## Global Constraints

这些约束对**每一个** Task 都生效，不再逐条重复：

1. **不删任何业务数据。** 本计划只翻 `is_active` 状态位、改配置、加权限行、建账号。任何 `DELETE FROM` 业务表都是违规。
2. **不手工 psql 改 schema 或数据。** 所有 DB 变更走 Flyway migration（`backend/java/cretas-api/src/main/resources/db/flyway/`，当前最新 `V20261029_51`）。
3. **生产业务写入必须为 0。** 验证阶段只做只读查询与登录冒烟。
4. **每个断言都要做变异验证**：把被测的那一行改坏 → 测试必须变红 → 回退 → 复绿。**变异要能单独拆开**：断言"A 等于 B"时，变异只动 A 或只动 B，不能同时改。
5. **改源码的脚本必须 `assert` 命中数。** 本仓多个 Java 文件是 CRLF；用 `\n` 拼多行匹配串会静默匹配不上，导致"变异没应用却全绿"的假结论。
6. **`MOCK_REST` 在任何配置层都不得出现在 `cretas.demo.factory-ids` 里**（`application.properties` 默认值、`.env.prod` 的 `CRETAS_DEMO_FACTORY_IDS`、测试 fixture）。它必须保持完整写能力。
7. **commit 用 scope 锁定**：`git commit -m "..." -- <明确路径>`，不要裸 `git add .`。commit 前先 `git status --short` 确认没有并发 session 的文件混进来。
8. **只推分支，不自行合并、不自行部署。** 部署与合并由 Steve 决定。
9. 四个部门 module 的准确拼写（大小写敏感）：`restaurantOps`、`restaurantMarketing`、`restaurantHr`、`restaurantFinance`。

---

## File Structure

| 文件 | 责任 | Task |
|---|---|---|
| `backend/java/.../service/execution/IntentExecutionOrchestrator.java` | 餐饮判定 2 处 + 1 个 null 调用点 | T1 |
| `backend/java/.../service/execution/DynamicToolSelectionService.java` | 餐饮判定 1 处 | T1 |
| `backend/java/.../controller/AIIntentConfigController.java` | 餐饮判定 1 处 | T1 |
| `backend/java/.../service/execution/SseStreamingService.java` | 餐饮判定 1 处 | T1 |
| `backend/java/.../ai/tool/impl/restaurant/gold/GoldBackedRestaurantTool.java` | 删 `DEMO_REST→RES_3101_009` 别名 | T2 |
| `backend/java/.../controller/FactoryRoleModuleOverrideController.java` | L2 白名单加 4 键 | T3 |
| `backend/java/.../controller/platform/PlatformRolePermissionController.java` | L1 白名单加 4 键 | T3 |
| `db/flyway/V20261029_52__restaurant_department_module_permissions.sql` | 补 4 个载体角色的 L1 权限行 | T4 |
| `backend/java/.../resources/application.properties` | 演示身份停用 | T5 |
| `backend/java/.../service/mobile/impl/MobileAuthServiceImpl.java` | 空配置 fail-closed | T5 |
| `db/flyway/V20261029_53__deactivate_nonmock_restaurant_tenants.sql` | 停用 37 租户 + 45 用户 + 回滚台账 | T6 |
| `scripts/systemd/cretas-restaurant-demo-stream-*` | 删除 4 个 unit | T8 |
| `scripts/deploy/install-restaurant-demo-stream.sh` | 删除 | T8 |

---

## Task 1: 修 5 处餐饮租户判定，让 `MOCK_REST` 被认出来

**为什么先做这个**：`MOCK_REST` 的 ID 既不以 `RES_`/`REST_` 开头也不等于 `DEMO_REST`，5 处判定里 4 处判否、1 处因调用方传 `null` 退化成判否。这些路径今天靠其它租户兜着没暴露；一旦它成为唯一租户就全部裸奔。**这是整个计划里唯一的真风险项。**

`SseStreamingService:1064-1075` 的 Javadoc 已经用文字预言了这个洞：「a factory whose id doesn't match RES_/REST_/DEMO_REST but whose domain IS resolved as RESTAURANT would gate tiered-first in /execute but NOT here... **do not assume the two checks always agree**」。`MOCK_REST` 正是它描述的那种工厂。

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java:1762-1764`（`isRestaurantFactoryId`）、`:3516-3520`（`hasRestaurantOwnerActionSignal` 传 null）
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/DynamicToolSelectionService.java:225-231`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/AIIntentConfigController.java:547-549`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/SseStreamingService.java:1078-1086`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/RestaurantTenantDetectionTest.java`（新建）

**Interfaces:**
- Consumes: 无（第一个 Task）
- Produces: 五处判定在 `factoryId="MOCK_REST"` 且工厂 domain 为 `RESTAURANT` 时返回 `true`。后续 Task 依赖这一点，但不直接调用这些私有方法。

**正确样板（同仓已有两个，抄它们，不要发明第三种）：**
- `IntentExecutionOrchestrator.resolveFactoryDomainSafe(factoryId)` — DB 兜底解析，已在 `:328/:436/:441/:446/:452` 这样用
- `DynamicToolSelectionService` 里的 `configService.resolveBusinessDomain(factoryId)` — **就在 `filterCandidatesByBusinessType` 里，判否那个方法的隔壁**

⛔ **不要**用"把 `MOCK_REST` 加进 ID 前缀白名单"来修。那只会造出第 6 个、第 7 个承载点。修法一律是**让判定去问 domain**。

- [ ] **Step 1: 写失败测试**

新建 `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/RestaurantTenantDetectionTest.java`：

```java
package com.cretas.aims.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MOCK_REST 的 ID 不匹配 RES_/REST_/DEMO_REST 任何一种前缀, 只能靠
 * factories.type='RESTAURANT' 解析出的 domain 认出来。这组断言钉住
 * 「ID 认不出时必须回落到 domain」, 防止再退回纯前缀判定。
 */
class RestaurantTenantDetectionTest {

    private static final String MOCK = "MOCK_REST";
    private static final String RESTAURANT_DOMAIN = "RESTAURANT";

    @Test
    @DisplayName("orchestrator: domain=RESTAURANT 时 MOCK_REST 被认成餐饮租户")
    void orchestratorRecognizesMockRestByDomain() {
        assertThat(IntentExecutionOrchestrator.isRestaurantTenantId(MOCK, RESTAURANT_DOMAIN)).isTrue();
    }

    @Test
    @DisplayName("orchestrator: domain 缺失时 MOCK_REST 判否 —— 契约是「靠 domain」, 不是「靠名字里有 REST」")
    void orchestratorRejectsMockRestWithoutDomain() {
        assertThat(IntentExecutionOrchestrator.isRestaurantTenantId(MOCK, null)).isFalse();
    }

    @Test
    @DisplayName("orchestrator: 传统 RES_ 前缀租户不依赖 domain 仍判是")
    void orchestratorKeepsPrefixBehaviour() {
        assertThat(IntentExecutionOrchestrator.isRestaurantTenantId("RES_3101_009", null)).isTrue();
    }

    @Test
    @DisplayName("SSE: domain=RESTAURANT 时 MOCK_REST 被认成餐饮租户")
    void sseRecognizesMockRestByDomain() {
        assertThat(SseStreamingService.isRestaurantTenantId(MOCK, RESTAURANT_DOMAIN)).isTrue();
    }

    @Test
    @DisplayName("SSE: 工厂型租户即使 id 含 REST 字样也判否")
    void sseRejectsFactoryTenant() {
        assertThat(SseStreamingService.isRestaurantTenantId("F006", "FACTORY")).isFalse();
    }

    @Test
    @DisplayName("DynamicToolSelection: domain=RESTAURANT 时 MOCK_REST 被认成餐饮租户")
    void toolSelectionRecognizesMockRestByDomain() {
        assertThat(DynamicToolSelectionService.isRestaurantTenantId(MOCK, RESTAURANT_DOMAIN)).isTrue();
    }

    @Test
    @DisplayName("AIIntentConfig: domain=RESTAURANT 时 MOCK_REST 被认成餐饮租户")
    void intentConfigRecognizesMockRestByDomain() {
        assertThat(com.cretas.aims.controller.AIIntentConfigController
                .isRestaurantTenantId(MOCK, RESTAURANT_DOMAIN)).isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend/java/cretas-api
mvn -q -Dtest=RestaurantTenantDetectionTest test
```

预期：编译失败，`cannot find symbol: method isRestaurantTenantId`。这是对的——方法还不存在。

- [ ] **Step 3: 在四个类里各加一个 domain-aware 静态判定**

四处用**同一个签名和同一套语义**（这是有意为之：以后再有人要加第 6 处，抄哪一个都一样）。

`IntentExecutionOrchestrator.java` — 把 `:1762` 的 `isRestaurantFactoryId` 改写为委托：

```java
    /**
     * 餐饮租户判定的唯一语义: domain 是权威, ID 前缀只是 domain 拿不到时的兜底。
     * MOCK_REST 这类 id 不含 RES_/REST_ 前缀的租户只能靠 domain 认出来。
     */
    static boolean isRestaurantTenantId(String factoryId, String factoryDomain) {
        if ("RESTAURANT".equalsIgnoreCase(factoryDomain)) {
            return true;
        }
        if (factoryId == null || factoryId.isBlank()) {
            return false;
        }
        String normalized = factoryId.trim().toUpperCase(java.util.Locale.ROOT);
        return "DEMO_REST".equals(normalized)
                || normalized.startsWith("RES_")
                || normalized.startsWith("REST_");
    }

    private boolean isRestaurantFactoryId(String factoryId) {
        return isRestaurantTenantId(factoryId, resolveFactoryDomainSafe(factoryId));
    }
```

`SseStreamingService.java` — 替换 `:1078` 的 `isRestaurantTenant`：

```java
    static boolean isRestaurantTenantId(String factoryId, String factoryDomain) {
        if ("RESTAURANT".equalsIgnoreCase(factoryDomain)) {
            return true;
        }
        if (factoryId == null || factoryId.isBlank()) {
            return false;
        }
        String normalized = factoryId.trim().toUpperCase(java.util.Locale.ROOT);
        return "DEMO_REST".equals(normalized)
                || normalized.startsWith("RES_")
                || normalized.startsWith("REST_");
    }
```

⚠️ `SseStreamingService` 原先**没有** domain 解析能力（Javadoc 自己写了 "no factory-domain DB lookup is wired into SseStreamingService"）。必须给它接一个。查该类已注入的依赖，优先复用现成的 config service；若确实没有，注入 `AIIntentConfigService`（`DynamicToolSelectionService` 用的那个）并调 `resolveBusinessDomain(factoryId)`，**在调用点传入 domain**，不要在这个静态方法里做 DB 查询。

`DynamicToolSelectionService.java` — 替换 `:225`：

```java
    static boolean isRestaurantTenantId(String factoryId, String factoryDomain) {
        if ("RESTAURANT".equalsIgnoreCase(factoryDomain)) {
            return true;
        }
        if (factoryId == null || factoryId.isBlank()) {
            return false;
        }
        String normalized = factoryId.trim().toUpperCase(java.util.Locale.ROOT);
        return "DEMO_REST".equals(normalized)
                || normalized.startsWith("RES_")
                || normalized.startsWith("REST_");
    }

    private boolean isRestaurantTenant(String factoryId) {
        String biz;
        try { biz = configService.resolveBusinessDomain(factoryId); }
        catch (Exception e) { biz = null; }   // 解析失败退回 ID 判定, 与同类 filterCandidatesByBusinessType 一致
        return isRestaurantTenantId(factoryId, biz);
    }
```

`AIIntentConfigController.java` — 替换 `:547`，同样的静态方法（改 `public static`，因为测试在别的包），私有实例方法委托给它并传入本类可用的 domain 解析结果。

- [ ] **Step 4: 修 `hasRestaurantOwnerActionSignal` 传 null 那处**

`IntentExecutionOrchestrator.java:3516-3518` 现在是：

```java
    boolean hasRestaurantOwnerActionSignal(String factoryId, Map<String, Object> context) {
        if (isRestaurantOwnerActionFactory(factoryId, null)) {   // ← null 让 domain 分支永远走不到
            return true;
        }
```

改成：

```java
    boolean hasRestaurantOwnerActionSignal(String factoryId, Map<String, Object> context) {
        if (isRestaurantOwnerActionFactory(factoryId, resolveFactoryDomainSafe(factoryId))) {
            return true;
        }
```

给它补一条断言。这里**刻意用源码级断言而不是 mock**：`hasRestaurantOwnerActionSignal` 是实例方法且 `resolveFactoryDomainSafe` 要打 DB，装一整套 mock 只为验"有没有传 null"，成本高且容易把断言写成"mock 被调用过"这种不说明问题的形状。加进 `RestaurantTenantDetectionTest`：

```java
    @Test
    @DisplayName("hasRestaurantOwnerActionSignal 传真实 domain 而不是 null")
    void ownerActionSignalResolvesDomainInsteadOfPassingNull() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java"));

        // 取 hasRestaurantOwnerActionSignal 方法体的前若干行
        int start = src.indexOf("boolean hasRestaurantOwnerActionSignal(");
        assertThat(start).as("找不到 hasRestaurantOwnerActionSignal").isGreaterThan(0);
        String body = src.substring(start, Math.min(start + 400, src.length()));

        assertThat(body)
            .as("该方法内对 isRestaurantOwnerActionFactory 的调用必须传解析出的 domain")
            .contains("isRestaurantOwnerActionFactory(factoryId, resolveFactoryDomainSafe(factoryId))");
        assertThat(body)
            .as("不得再出现传 null 的调用 —— 那会让 domain 分支永远走不到")
            .doesNotContain("isRestaurantOwnerActionFactory(factoryId, null)");
    }
```

⚠️ 两条断言**必须分别变异**：把调用改回 `null` → 第一条红且第二条也红；只把 `resolveFactoryDomainSafe(factoryId)` 换成另一个等价表达式（例如先赋给局部变量再传） → 只有第一条红。第二种情况说明第一条断言写得太死（钉死了字面写法而不是行为），此时应放宽第一条为"不含 `, null)`"并保留第二条。

- [ ] **Step 5: 跑测试确认通过**

```bash
cd backend/java/cretas-api
mvn -q -Dtest=RestaurantTenantDetectionTest test
```

预期：全部 PASS。

- [ ] **Step 6: 变异验证（逐处单独做，共 5 次）**

对每一处，把 `if ("RESTAURANT".equalsIgnoreCase(factoryDomain)) return true;` 这一行删掉，跑测试，**必须变红**；红了再回退。

```bash
# 每次变异后:
grep -c 'RESTAURANT".equalsIgnoreCase(factoryDomain)' <被改的文件>   # 确认变异真的落地了
mvn -q -Dtest=RestaurantTenantDetectionTest test                      # 必须 FAIL
git checkout -- <被改的文件>                                          # 回退
```

⚠️ 若某处变异后测试仍绿，说明该处的断言没有真正打到这条路径——**修断言，不要跳过**。

- [ ] **Step 7: 跑周边回归**

```bash
cd backend/java/cretas-api
mvn -q -Dtest='IntentExecutionOrchestrator*Test,DynamicToolSelectionServiceTest,SseStreamingServiceTest,AIIntentConfigControllerTest' test
```

预期：无**新增**失败。⚠️ 开工前先在干净的 base 上跑一遍同样的选择器记下基线——本仓存在既有红测（BOM 域 origin/main 上就有 56 个），判据是"无新增"不是"全绿"。

- [ ] **Step 8: Commit**

```bash
git status --short
git commit -m "fix(restaurant): 餐饮租户判定改问 domain —— MOCK_REST 不再被 5 处判否

ID 前缀判定认不出 MOCK_REST(不含 RES_/REST_ 前缀, 也不等于 DEMO_REST),
5 处里 4 处直接判否, 第 5 处 hasRestaurantOwnerActionSignal 因为给
isRestaurantOwnerActionFactory 传 null 而退化成纯 ID 判定。

SseStreamingService:1064 的 Javadoc 早就写明这个洞「do not assume the two
checks always agree」, MOCK_REST 正是它描述的那种 domain 是餐饮、ID 不匹配
的租户。

四处统一成同签名的 isRestaurantTenantId(factoryId, factoryDomain): domain
是权威, ID 前缀只做 domain 拿不到时的兜底。刻意不把 MOCK_REST 加进前缀
白名单 —— 那只会造出第 6 个承载点。" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/DynamicToolSelectionService.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/SseStreamingService.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/controller/AIIntentConfigController.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/RestaurantTenantDetectionTest.java
```

---

## Task 2: 删除 `DEMO_REST → RES_3101_009` Gold 别名

**背景**：`GoldBackedRestaurantTool.resolveGoldFactoryId:384-390` 把 `DEMO_REST` 的 Gold 查询重定向到 `RES_3101_009`，而同文件 `:251-256` 说明 Python tiered 路径**故意不走**这个别名。结果同一个演示账号在 Java 与 Python 两条路径上读两个不同租户的数据（`DEMO_REST` 401 行 totals vs `RES_3101_009` 8 行）。两个租户都要停用，这条别名成为死代码。

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/gold/GoldBackedRestaurantTool.java:384-390`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/gold/GoldBackedRestaurantToolAliasTest.java`（新建）

**Interfaces:**
- Consumes: 无
- Produces: `resolveGoldFactoryId(x)` 对任意输入恒等返回 `x`。

- [ ] **Step 1: 写失败测试**

```java
package com.cretas.aims.ai.tool.impl.restaurant.gold;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 别名删除后 Gold 查询必须落在自己的租户上。断言钉住「返回值等于入参」这个
 * 行为, 而不是钉住某个字符串常量消失 —— 后者换个写法就绕过去了。
 */
class GoldBackedRestaurantToolAliasTest {

    @Test
    @DisplayName("DEMO_REST 不再被重定向到 RES_3101_009")
    void demoRestNoLongerAliased() {
        assertThat(GoldBackedRestaurantTool.resolveGoldFactoryIdStatic("DEMO_REST"))
                .isEqualTo("DEMO_REST");
    }

    @Test
    @DisplayName("MOCK_REST 原样返回")
    void mockRestUnchanged() {
        assertThat(GoldBackedRestaurantTool.resolveGoldFactoryIdStatic("MOCK_REST"))
                .isEqualTo("MOCK_REST");
    }

    @Test
    @DisplayName("大小写变体同样不被重定向")
    void lowercaseDemoRestUnchanged() {
        assertThat(GoldBackedRestaurantTool.resolveGoldFactoryIdStatic("demo_rest"))
                .isEqualTo("demo_rest");
    }
}
```

⚠️ 现有 `resolveGoldFactoryId` 是 `protected` 实例方法。若不便直接测，提取一个包级静态 `resolveGoldFactoryIdStatic` 并让实例方法委托它；**不要**为了测试把它改成 `public`。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend/java/cretas-api
mvn -q -Dtest=GoldBackedRestaurantToolAliasTest test
```

预期：`demoRestNoLongerAliased` FAIL，实际值是 `RES_3101_009`。

- [ ] **Step 3: 删掉别名分支**

```java
    protected String resolveGoldFactoryId(String factoryId) {
        // 2026-08-05: 删除 DEMO_REST → RES_3101_009 别名。两个租户均已停用
        // (租户收敛 spec §4.5), 且该别名曾导致同一演示账号在 Java Gold 路径与
        // Python tiered 路径上读两个不同租户的数据 —— 后者 (:251) 有意不做映射。
        return factoryId;
    }
```

同时更新 `:251-256` 与 `GoldFinanceClient.java:1402-1403` 里描述该别名的 Javadoc（它们现在成了假陈述）。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -q -Dtest=GoldBackedRestaurantToolAliasTest test
```

预期：PASS。

- [ ] **Step 5: 变异验证**

把 `return factoryId;` 改回 `if ("DEMO_REST".equalsIgnoreCase(factoryId)) return "RES_3101_009"; return factoryId;`，跑测试**必须红**，然后回退。

- [ ] **Step 6: 周边回归 + Commit**

```bash
mvn -q -Dtest='GoldBackedRestaurantTool*Test,RestaurantComprehensiveSynthesisGoldToolTest' test
git status --short
git commit -m "fix(restaurant): 删除 DEMO_REST→RES_3101_009 Gold 别名

该别名让同一个演示账号在 Java Gold 路径读 RES_3101_009(8 行 totals)、在
Python tiered 路径读 DEMO_REST 自己的数据(401 行 totals) —— 后者 :251 的
注释明说故意不做映射。两个租户都要停用, 别名成死代码。

顺带修正 :251 与 GoldFinanceClient:1402 里描述该别名的 Javadoc, 它们现在
是假陈述。" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/gold/GoldBackedRestaurantTool.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/client/GoldFinanceClient.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/gold/GoldBackedRestaurantToolAliasTest.java
```

---

## Task 3: 两处 `ALLOWED_MODULES` 白名单加入四个部门 module

**背景**：四个部门 module 不在白名单里，写入 API 会 `throw new IllegalArgumentException("无效模块")`。白名单有**两个承载点**，只改一个等于没改。

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/FactoryRoleModuleOverrideController.java:29-32`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/platform/PlatformRolePermissionController.java:41`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/RestaurantDepartmentModuleAllowlistTest.java`（新建）

**Interfaces:**
- Consumes: 无
- Produces: 两个 controller 的 `ALLOWED_MODULES` 均含 `restaurantOps`/`restaurantMarketing`/`restaurantHr`/`restaurantFinance`。Task 4 的 migration 写入的行，靠这个才能被后台界面管理。

- [ ] **Step 1: 写失败测试**

```java
package com.cretas.aims.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 四个部门 module 的白名单有两个承载点(L2 工厂级 / L1 平台级)。这组断言
 * 分别打这两处 —— 只改一个的话另一个会红。
 */
class RestaurantDepartmentModuleAllowlistTest {

    private static final List<String> DEPARTMENT_MODULES = List.of(
            "restaurantOps", "restaurantMarketing", "restaurantHr", "restaurantFinance");

    @Test
    @DisplayName("L2 工厂级覆盖接受四个部门 module")
    void factoryOverrideAcceptsDepartmentModules() {
        for (String module : DEPARTMENT_MODULES) {
            assertThatCode(() -> FactoryRoleModuleOverrideController.assertModuleAllowed(module))
                    .as("L2 应接受 %s", module)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("L1 平台级接受四个部门 module")
    void platformPermissionAcceptsDepartmentModules() {
        for (String module : DEPARTMENT_MODULES) {
            assertThatCode(() -> com.cretas.aims.controller.platform.PlatformRolePermissionController
                    .assertModuleAllowed(module))
                    .as("L1 应接受 %s", module)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("拼错的 module 名仍被拒 —— 白名单不是形同虚设")
    void typoStillRejected() {
        assertThatThrownBy(() -> FactoryRoleModuleOverrideController.assertModuleAllowed("restaurantops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> com.cretas.aims.controller.platform.PlatformRolePermissionController
                .assertModuleAllowed("restaurantHR"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

⚠️ 第三条断言很重要：module 名**大小写敏感**，`restaurantops` / `restaurantHR` 都是错的。没有这条，前两条即使把白名单改成"什么都接受"也能过。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend/java/cretas-api
mvn -q -Dtest=RestaurantDepartmentModuleAllowlistTest test
```

预期：编译失败（`assertModuleAllowed` 不存在）。

- [ ] **Step 3: 两处各加四个键并抽出可测的校验方法**

`FactoryRoleModuleOverrideController.java`：

```java
    private static final Set<String> ALLOWED_MODULES = Set.of(
        "dashboard","production","warehouse","quality","procurement","sales",
        "hr","equipment","finance","system","analytics","scheduling",
        "work_report","inventory","report","rd","restaurant",
        // 2026-08-05 餐饮四部门驾驶舱细分权限。语义见 permission.ts:552:
        //   最终 = min(restaurant 上限, 该部门声明值 ?? 上限)
        // 大小写敏感, 必须与前端 ModulePermissions 的键逐字一致。
        "restaurantOps","restaurantMarketing","restaurantHr","restaurantFinance");

    static void assertModuleAllowed(String module) {
        if (!ALLOWED_MODULES.contains(module)) {
            throw new IllegalArgumentException("无效模块: " + module);
        }
    }
```

并把 `:62` 改为调用 `assertModuleAllowed(module);`。

`PlatformRolePermissionController.java` 同样处理（`:41` 加四个键、`:109-111` 改为调用抽出的方法，保留它原有的错误消息格式 `"无效模块: " + module + ". 允许: " + ALLOWED_MODULES`）。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -q -Dtest=RestaurantDepartmentModuleAllowlistTest test
```

预期：PASS。

- [ ] **Step 5: 变异验证（两次，分开做）**

先只从 `FactoryRoleModuleOverrideController` 删掉四个键 → 只有 `factoryOverrideAcceptsDepartmentModules` 变红、`platformPermissionAcceptsDepartmentModules` 仍绿 → 回退。再对 `PlatformRolePermissionController` 做同样的事，红的应该是另一条。

**两次变异必须红不同的测试。** 如果同一条测试两次都红，说明断言没有分别打到两个承载点。

- [ ] **Step 6: Commit**

```bash
git status --short
git commit -m "feat(permission): 四个餐饮部门 module 加入两处 ALLOWED_MODULES 白名单

restaurantOps/Marketing/Hr/Finance 此前不在白名单, 写入 API 直接抛
「无效模块」, 导致部门级权限根本配不进去。白名单有两个承载点
(FactoryRoleModuleOverride L2 + PlatformRolePermission L1), 两处都加。

抽出 assertModuleAllowed 便于分别断言两处; 另加一条拼错大小写仍被拒的
断言 —— 否则把白名单改成「全接受」也能让前两条过。" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/controller/FactoryRoleModuleOverrideController.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/controller/platform/PlatformRolePermissionController.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/controller/RestaurantDepartmentModuleAllowlistTest.java
```

---

## Task 4: Migration —— 补四个载体角色的 L1 部门权限行

**背景**：`platform_role_permissions` 里 `module_code like 'restaurant%'` 只有 5 行笼统 `restaurant`，没有任何细分行。按 `最终 = min(上限, 声明 ?? 上限)`，`hr_admin`/`finance_manager`/`sales_manager` 的上限是 `-` → 四个部门全关。

**表结构（实测）**：`platform_role_permissions(id bigserial, role_code varchar NOT NULL, module_code varchar NOT NULL, permission_level varchar NOT NULL, updated_by bigint, updated_at timestamp, created_at timestamp, deleted_at timestamp)`

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261029_52__restaurant_department_module_permissions.sql`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/migration/RestaurantDepartmentPermissionMigrationTest.java`（新建）

**Interfaces:**
- Consumes: Task 3 的白名单（否则后台界面改不了这些行）
- Produces: 四个载体角色各 5 行权限，使 `restaurant_manager`=运营、`sales_manager`=市场、`finance_manager`=财务、`hr_admin`=人事 各自只看得见一个部门。

- [ ] **Step 1: 写 migration**

```sql
-- V20261029_52: 餐饮四部门驾驶舱的分角色细分权限 (L1 平台级)
--
-- 背景: platform_role_permissions 此前只有笼统的 module_code='restaurant',
-- 没有 restaurantOps/Marketing/Hr/Finance 四个细分键。而 permission.ts:552
-- 的规则是:
--     最终 = min(restaurant 上限, 该部门声明值 ?? 上限)
-- 上限是 '-' 时部门声明什么都没用 —— 所以 hr_admin / finance_manager /
-- sales_manager 在餐饮租户里四个部门一个都看不见。
--
-- 本迁移给四个「部门载体角色」各写 5 行(1 上限 + 4 细分), 让一个账号只看得见
-- 自己那一个部门。
--
-- ⚠️ 爆炸半径: 本表是平台全局 L1, 影响所有工厂的这四个角色。靠
-- permission.ts:326 的 FACTORY_TYPE_MODULE_FILTER.FACTORY={restaurant:'-'}
-- 兜住工厂型租户(四个部门随上限一起关)。验收必须实测 F006 的这三个角色
-- 看不见任何餐饮入口。
--
-- ⚠️ 语义改动(有意, 已记入 spec §4.5.5): 把 restaurant_manager 收窄成
-- 「只有运营」等于全局重定义了店长, 与 food_kb/api/manual_chat.py:458
-- 「店长可管理运营、市场、人事并只读财务」相矛盾。当前可接受 —— 租户收敛后
-- 只剩 MOCK_REST 一个活跃餐饮租户。**接入真实餐饮客户前必须重新评估。**
--
-- 刻意不动 restaurant_owner / restaurant_chef: 它们在本表零行(上限 '-',
-- 四部门全不可见), 与 fallback 矩阵矛盾, 属既有问题, 不在本轮范围。

INSERT INTO platform_role_permissions (role_code, module_code, permission_level)
VALUES
    -- 运营
    ('restaurant_manager', 'restaurant',          'rw'),
    ('restaurant_manager', 'restaurantOps',       'rw'),
    ('restaurant_manager', 'restaurantMarketing', '-'),
    ('restaurant_manager', 'restaurantHr',        '-'),
    ('restaurant_manager', 'restaurantFinance',   '-'),
    -- 市场
    ('sales_manager',      'restaurant',          'rw'),
    ('sales_manager',      'restaurantOps',       '-'),
    ('sales_manager',      'restaurantMarketing', 'rw'),
    ('sales_manager',      'restaurantHr',        '-'),
    ('sales_manager',      'restaurantFinance',   '-'),
    -- 财务
    ('finance_manager',    'restaurant',          'rw'),
    ('finance_manager',    'restaurantOps',       '-'),
    ('finance_manager',    'restaurantMarketing', '-'),
    ('finance_manager',    'restaurantHr',        '-'),
    ('finance_manager',    'restaurantFinance',   'rw'),
    -- 人事
    ('hr_admin',           'restaurant',          'rw'),
    ('hr_admin',           'restaurantOps',       '-'),
    ('hr_admin',           'restaurantMarketing', '-'),
    ('hr_admin',           'restaurantHr',        'rw'),
    ('hr_admin',           'restaurantFinance',   '-')
ON CONFLICT (role_code, module_code) DO UPDATE
    SET permission_level = EXCLUDED.permission_level,
        updated_at = now();
```

⚠️ **执行前必须确认 `(role_code, module_code)` 上有唯一约束**，否则 `ON CONFLICT` 会报错：

```sql
SELECT conname, pg_get_constraintdef(oid)
FROM pg_constraint WHERE conrelid = 'platform_role_permissions'::regclass;
```

若没有唯一约束，改成先 `DELETE FROM platform_role_permissions WHERE role_code IN (...) AND module_code LIKE 'restaurant%'` 再 `INSERT`——**但这属于删数据，须先在 prod 只读确认这些行只有上面查到的 5 条笼统行，且删除范围写死在 `module_code LIKE 'restaurant%'` 内**。

- [ ] **Step 2: 写 migration 契约测试**

```java
package com.cretas.aims.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住迁移写入的权限形状。断言的是「每个角色只有一个部门是 rw」这个不变量,
 * 不是字符串出现次数 —— 后者改个格式就绕过去了。
 */
class RestaurantDepartmentPermissionMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/flyway/V20261029_52__restaurant_department_module_permissions.sql");

    private static final String[] DEPARTMENTS = {
        "restaurantOps", "restaurantMarketing", "restaurantHr", "restaurantFinance" };

    @Test
    @DisplayName("每个载体角色恰好一个部门是 rw, 其余三个是 '-'")
    void eachRoleOwnsExactlyOneDepartment() throws Exception {
        String sql = Files.readString(MIGRATION);
        record Expected(String role, String ownedDepartment) {}
        var cases = new Expected[] {
            new Expected("restaurant_manager", "restaurantOps"),
            new Expected("sales_manager",      "restaurantMarketing"),
            new Expected("finance_manager",    "restaurantFinance"),
            new Expected("hr_admin",           "restaurantHr"),
        };
        for (var c : cases) {
            for (String dept : DEPARTMENTS) {
                String expectedLevel = dept.equals(c.ownedDepartment()) ? "rw" : "-";
                assertThat(rowLevel(sql, c.role(), dept))
                        .as("%s 对 %s 应为 %s", c.role(), dept, expectedLevel)
                        .isEqualTo(expectedLevel);
            }
        }
    }

    @Test
    @DisplayName("四个载体角色的 restaurant 上限都是 rw —— 上限是 '-' 时细分声明全部失效")
    void ceilingIsReadWriteForAllCarriers() throws Exception {
        String sql = Files.readString(MIGRATION);
        for (String role : new String[]{"restaurant_manager","sales_manager","finance_manager","hr_admin"}) {
            assertThat(rowLevel(sql, role, "restaurant"))
                    .as("%s 的 restaurant 上限", role)
                    .isEqualTo("rw");
        }
    }

    @Test
    @DisplayName("不碰 restaurant_owner / restaurant_chef —— 它们是本轮明确排除的既有问题")
    void doesNotTouchOwnerOrChef() throws Exception {
        String sql = Files.readString(MIGRATION);
        assertThat(sql).doesNotContain("'restaurant_owner'");
        assertThat(sql).doesNotContain("'restaurant_chef'");
    }

    /** 从 VALUES 行里取出 (role, module) 对应的 permission_level。 */
    private static String rowLevel(String sql, String role, String module) {
        var m = java.util.regex.Pattern
            .compile("\\('" + java.util.regex.Pattern.quote(role) + "'\\s*,\\s*'"
                   + java.util.regex.Pattern.quote(module) + "'\\s*,\\s*'([^']+)'\\)")
            .matcher(sql);
        return m.find() ? m.group(1) : null;
    }
}
```

- [ ] **Step 3: 跑测试确认通过**

```bash
cd backend/java/cretas-api
mvn -q -Dtest=RestaurantDepartmentPermissionMigrationTest test
```

- [ ] **Step 4: 变异验证（拆开做）**

把 `('hr_admin', 'restaurantHr', 'rw')` 改成 `'-'` → `eachRoleOwnsExactlyOneDepartment` 必须红。回退。
把 `('hr_admin', 'restaurant', 'rw')` 改成 `'-'` → `ceilingIsReadWriteForAllCarriers` 必须红。回退。
**这两次必须红不同的测试**——它们验的是不同的不变量（细分值 vs 上限）。

- [ ] **Step 5: prod 只读干跑**

```bash
# 1. 确认唯一约束存在
# 2. BEGIN ... 执行 migration SQL ... 查询结果 ... ROLLBACK
#    用 migration 文件里的真实 SQL 字符串, 不要手抄
```

干跑内必须验证：四个角色各自 5 行、上限均 rw、每角色恰好一个部门 rw。`ROLLBACK` 后再查一次确认库里没留下任何行。

- [ ] **Step 6: Commit**

```bash
git status --short
git commit -m "feat(permission): 补四个餐饮部门载体角色的 L1 细分权限行

platform_role_permissions 里此前只有笼统 restaurant, 没有四个部门细分键。
按 permission.ts:552 的「最终 = min(上限, 声明 ?? 上限)」, hr_admin /
finance_manager / sales_manager 上限是 '-' → 四部门全关, 所谓「hr_admin 当
纯人事账号」在 prod 根本看不见任何东西。

每个载体角色写 5 行(1 上限 rw + 4 细分, 恰好一个 rw)。

爆炸半径靠 FACTORY_TYPE_MODULE_FILTER.FACTORY={restaurant:'-'} 兜住工厂型
租户; 验收须实测 F006 的这三个角色看不见餐饮入口。

⚠️ 收窄 restaurant_manager 等于全局重定义店长, 与 manual_chat.py:458 矛盾,
当前可接受(只剩 MOCK_REST 一个活跃餐饮租户), 接真实客户前必须重评 —— 已写进
migration 注释。刻意不动 owner/chef。" -- \
  backend/java/cretas-api/src/main/resources/db/flyway/V20261029_52__restaurant_department_module_permissions.sql \
  backend/java/cretas-api/src/test/java/com/cretas/aims/migration/RestaurantDepartmentPermissionMigrationTest.java
```

---

## Task 5: 停用演示身份（`MOCK_REST` 必须保持可写）

**背景**：`cretas.demo.rest.factory-id=DEMO_REST` 指向即将停用的租户；`cretas.demo.factory-ids` 是**只读写闸**名单，`MOCK_REST` 绝不能进去（Steve：「要有操作设置的」）。

**⚠️ 本 Task 的第一步是读代码，不是改代码。** 配置置空后消费者的行为是"演示功能关闭"还是"匹配任意租户"——后者会开出比现状更大的洞。**不许假设。**

**Files:**
- Modify: `backend/java/cretas-api/src/main/resources/application.properties:111-113`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/mobile/impl/MobileAuthServiceImpl.java:59` 及其使用点
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/config/DemoReadOnlyInterceptor.java:41`（fallback 默认值）
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java:245`（fallback 默认值）
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/config/DemoIdentityDisabledTest.java`（新建）

**Interfaces:**
- Consumes: 无
- Produces: 演示身份关闭；`MOCK_REST` 不在任何只读名单内。

- [ ] **Step 1: 先读三个消费者，回答"空值走哪条分支"**

```bash
cd backend/java/cretas-api
grep -n "demoRestFactoryId\|demoRestUsername\|demoFactoryIds" -r src/main/java | head -30
```

逐个读使用点，回答：`""`（空串）时是跳过演示分支，还是被当成"匹配任意/匹配 null"？把结论写进本 Task 的 commit message。**若发现空值会导致 fail-open，必须在 Step 3 显式加 fail-closed 守卫。**

- [ ] **Step 2: 写失败测试**

```java
package com.cretas.aims.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DemoIdentityDisabledTest {

    private static final Path PROPS = Path.of("src/main/resources/application.properties");

    @Test
    @DisplayName("MOCK_REST 不在演示只读名单里 —— 它必须保持完整写能力")
    void mockRestIsNotReadOnly() throws Exception {
        String line = propertyLine("cretas.demo.factory-ids");
        assertThat(line)
            .as("cretas.demo.factory-ids 的值")
            .doesNotContain("MOCK_REST");
    }

    @Test
    @DisplayName("演示只读名单不再含已停用的 DEMO_REST")
    void demoRestRemovedFromReadOnlyList() throws Exception {
        assertThat(propertyLine("cretas.demo.factory-ids")).doesNotContain("DEMO_REST");
    }

    @Test
    @DisplayName("演示餐饮身份已停用(默认值为空)")
    void demoRestIdentityDisabled() throws Exception {
        assertThat(defaultValueOf("cretas.demo.rest.factory-id")).isEmpty();
        assertThat(defaultValueOf("cretas.demo.rest.username")).isEmpty();
    }

    @Test
    @DisplayName("代码里的 @Value fallback 默认值也不含 DEMO_REST —— 配置与 fallback 是两个承载点")
    void codeFallbacksAlsoCleaned() throws Exception {
        for (Path f : new Path[]{
                Path.of("src/main/java/com/cretas/aims/config/DemoReadOnlyInterceptor.java"),
                Path.of("src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java")}) {
            String src = Files.readString(f);
            assertThat(src)
                .as("%s 的 @Value fallback", f.getFileName())
                .doesNotContain("${cretas.demo.factory-ids:DEMO_REST");
        }
    }

    private static String propertyLine(String key) throws Exception {
        return Arrays.stream(Files.readString(PROPS).split("\\R"))
            .filter(l -> l.startsWith(key + "="))
            .findFirst().orElseThrow(() -> new AssertionError("找不到配置项: " + key));
    }

    /** 取 `key=${ENV:default}` 里的 default 部分。 */
    private static String defaultValueOf(String key) throws Exception {
        String line = propertyLine(key);
        int colon = line.indexOf(':', line.indexOf("${"));
        return line.substring(colon + 1, line.lastIndexOf('}'));
    }
}
```

第 4 条断言针对**第二个承载点**：`@Value("${cretas.demo.factory-ids:DEMO_REST,...}")` 里的 fallback 默认值。只改 `application.properties` 不改这两处，配置缺失时会悄悄退回旧名单。

- [ ] **Step 3: 跑测试确认失败，然后改配置**

```bash
mvn -q -Dtest=DemoIdentityDisabledTest test   # 预期 FAIL
```

`application.properties:111-113` 改为：

```properties
# 2026-08-05 租户收敛: 演示餐饮身份停用。DEMO_REST 已随其它 36 个餐饮租户
# 一并停用, 公开免登录演示入口下线, 演示一律用 MOCK_REST 的账号登录。
# ⛔ MOCK_REST 绝不能进 cretas.demo.factory-ids —— 那是只读写闸名单,
#    进去它就失去写能力, 而演示需要「有操作设置的」。
cretas.demo.factory-ids=${CRETAS_DEMO_FACTORY_IDS:DEMO_FACTORY2,F_DEMO}
cretas.demo.rest.factory-id=${CRETAS_DEMO_REST_ID:}
cretas.demo.rest.username=${CRETAS_DEMO_REST_USERNAME:}
```

同步把 `DemoReadOnlyInterceptor.java:41` 与 `IntentExecutionOrchestrator.java:245` 的 `@Value` fallback 默认值里的 `DEMO_REST` 去掉。若 Step 1 发现空值 fail-open，在此加显式守卫（空则直接 return / 跳过演示分支）。

- [ ] **Step 4: 跑测试确认通过 + 变异验证**

```bash
mvn -q -Dtest=DemoIdentityDisabledTest test    # 预期 PASS
```

变异 1：把 `MOCK_REST` 加进 `cretas.demo.factory-ids` → `mockRestIsNotReadOnly` 必须红 → 回退。
变异 2：把某个 `@Value` fallback 改回 `DEMO_REST,...` → `codeFallbacksAlsoCleaned` 必须红 → 回退。
两次必须红**不同的**测试。

- [ ] **Step 5: 周边回归 + Commit**

```bash
mvn -q -Dtest='DemoReadOnlyInterceptorTest,DemoFactoryGateConfigTest,MobileAuthServiceImplTest' test
git status --short
git commit -m "feat(demo): 停用演示餐饮身份, MOCK_REST 保持完整写能力

DEMO_REST 随租户收敛一并停用, 公开免登录演示入口下线。

⛔ 刻意不把 MOCK_REST 放进 cretas.demo.factory-ids: 那是只读写闸名单,
进去就失去写能力, 而 Steve 明确要求演示租户「要有操作设置的」。加断言钉死。

配置与 @Value fallback 是两个承载点, 两处都清 —— 只改 properties 的话配置
缺失时会悄悄退回旧名单。

空值消费者行为已逐个读过: <Step 1 的结论写在这里>" -- \
  backend/java/cretas-api/src/main/resources/application.properties \
  backend/java/cretas-api/src/main/java/com/cretas/aims/config/DemoReadOnlyInterceptor.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/mobile/impl/MobileAuthServiceImpl.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/config/DemoIdentityDisabledTest.java
```

---

## Task 6: Migration —— 停用 37 个租户 + 45 个用户（带回滚台账）

**背景**：登录只校验 `user.getIsActive()`（`MobileAuthServiceImpl:127/171`），**不校验 factory 的**。只停租户不停用户 = 用户登录成功但 AI 网关全拒（`ToolPrincipalPolicy:54`）——半死状态比直接拒绝更糟。

**⚠️ 类型陷阱（实测）**：`factories.id` 是 `varchar(255)`，`users.id` 是 **`bigint`**。台账表**必须两个不同类型的列**，不能共用一个 `object_id`——`V20261029_44` 就栽在这：写入时隐式转换毫无问题，**只有回滚时的 `IN (SELECT object_id)` 比较才炸**，部署/验收全看不见。

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261029_53__deactivate_nonmock_restaurant_tenants.sql`
- Create: `scripts/rollback/V20261029_53_rollback.sql`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/migration/DeactivateRestaurantTenantsMigrationTest.java`（新建）

**Interfaces:**
- Consumes: Task 1（判定修好后 MOCK_REST 才能独立承担）
- Produces: `select count(*) from factories where type='RESTAURANT' and is_active=true` = 1

- [ ] **Step 1: 写 migration**

```sql
-- V20261029_53: 餐饮租户收敛 —— 只留 MOCK_REST, 其余 37 个停用
--
-- 只翻状态位, 不删任何业务数据(POS/Silver/Gold/预订/损耗行全部原地保留)。
--
-- ⚠️ 必须连用户一起停: MobileAuthServiceImpl:127/171 只校验 user.getIsActive(),
-- 全文没有一处校验 factory 的。只停租户会让用户登录成功却被 AI 网关
-- (ToolPrincipalPolicy:54) 全拒 —— 半死状态比直接拒绝登录更糟。
--
-- ⚠️ 台账两个列分开: factories.id 是 varchar(255), users.id 是 bigint。
-- 共用一个 object_id 列时, INSERT 有隐式转换看不出问题, 只有回滚时的
-- IN (SELECT object_id) 比较才炸(V20261029_44 原样事故)。

-- ── 0. Fail-closed 前置断言 ──────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM factories WHERE id = 'MOCK_REST' AND type = 'RESTAURANT') THEN
        RAISE EXCEPTION 'MOCK_REST 不存在或不是 RESTAURANT 类型, 中止 —— 否则会把所有餐饮租户都停掉';
    END IF;
END $$;

-- ── 1. 回滚台账 ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS restaurant_consolidation_ledger_20260805 (
    id           bigserial PRIMARY KEY,
    entity_kind  varchar(16)  NOT NULL CHECK (entity_kind IN ('FACTORY','USER')),
    factory_id   varchar(255),           -- 对应 factories.id (varchar)
    user_id      bigint,                 -- 对应 users.id (bigint) —— 刻意与上面分开
    recorded_at  timestamp NOT NULL DEFAULT now(),
    CHECK ((entity_kind = 'FACTORY' AND factory_id IS NOT NULL AND user_id IS NULL)
        OR (entity_kind = 'USER'    AND user_id   IS NOT NULL))
);

INSERT INTO restaurant_consolidation_ledger_20260805 (entity_kind, factory_id)
SELECT 'FACTORY', id FROM factories
 WHERE type = 'RESTAURANT' AND id <> 'MOCK_REST' AND is_active = true;

INSERT INTO restaurant_consolidation_ledger_20260805 (entity_kind, factory_id, user_id)
SELECT 'USER', u.factory_id, u.id FROM users u
 WHERE u.factory_id IN (SELECT id FROM factories WHERE type = 'RESTAURANT' AND id <> 'MOCK_REST')
   AND u.is_active = true;

-- ── 2. 停用 ──────────────────────────────────────────────────────────
UPDATE factories SET is_active = false
 WHERE type = 'RESTAURANT' AND id <> 'MOCK_REST' AND is_active = true;

UPDATE users SET is_active = false
 WHERE factory_id IN (SELECT id FROM factories WHERE type = 'RESTAURANT' AND id <> 'MOCK_REST')
   AND is_active = true;

-- ── 3. Fail-closed 后置断言 ──────────────────────────────────────────
DO $$
DECLARE active_count int;
BEGIN
    SELECT count(*) INTO active_count FROM factories WHERE type = 'RESTAURANT' AND is_active = true;
    IF active_count <> 1 THEN
        RAISE EXCEPTION '收敛后活跃餐饮租户应为 1, 实际 %', active_count;
    END IF;
END $$;
```

- [ ] **Step 2: 写回滚脚本**

`scripts/rollback/V20261029_53_rollback.sql`：

```sql
-- V20261029_53 的精确回滚。⛔ 禁止用「把所有 RESTAURANT 改回 true」——
-- 那会把本来就该停用的租户也打开。只恢复台账里记下的那些。
--
-- 注意两个 IN 子查询用的是不同类型的列: factory_id (varchar) 与 user_id (bigint)。

UPDATE factories SET is_active = true
 WHERE id IN (SELECT factory_id FROM restaurant_consolidation_ledger_20260805
               WHERE entity_kind = 'FACTORY');

UPDATE users SET is_active = true
 WHERE id IN (SELECT user_id FROM restaurant_consolidation_ledger_20260805
               WHERE entity_kind = 'USER');
```

- [ ] **Step 3: 写契约测试**

```java
package com.cretas.aims.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeactivateRestaurantTenantsMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/flyway/V20261029_53__deactivate_nonmock_restaurant_tenants.sql");
    private static final Path ROLLBACK = Path.of(
        "../../../scripts/rollback/V20261029_53_rollback.sql");

    @Test
    @DisplayName("不删任何业务数据")
    void neverDeletes() throws Exception {
        assertThat(Files.readString(MIGRATION).toUpperCase()).doesNotContain("DELETE FROM");
    }

    @Test
    @DisplayName("用户与租户一起停 —— 只停租户会造出「能登录但 AI 全拒」的半死状态")
    void deactivatesUsersToo() throws Exception {
        assertThat(Files.readString(MIGRATION)).contains("UPDATE users SET is_active = false");
    }

    @Test
    @DisplayName("台账 factory_id 与 user_id 是两个不同类型的列")
    void ledgerSeparatesIdTypes() throws Exception {
        String sql = Files.readString(MIGRATION);
        assertThat(sql).contains("factory_id   varchar(255)");
        assertThat(sql).contains("user_id      bigint");
    }

    @Test
    @DisplayName("有 MOCK_REST 存在性前置断言, 否则会把所有餐饮租户停光")
    void hasFailClosedPrecondition() throws Exception {
        String sql = Files.readString(MIGRATION);
        assertThat(sql).contains("MOCK_REST 不存在或不是 RESTAURANT 类型");
        assertThat(sql).contains("RAISE EXCEPTION");
    }

    @Test
    @DisplayName("回滚按台账精确恢复, 不是「全部改回 true」")
    void rollbackIsLedgerDriven() throws Exception {
        String sql = Files.readString(ROLLBACK);
        assertThat(sql).contains("restaurant_consolidation_ledger_20260805");
        assertThat(sql).doesNotContain("WHERE type = 'RESTAURANT'");
    }
}
```

- [ ] **Step 4: 跑测试 + 变异验证**

```bash
cd backend/java/cretas-api
mvn -q -Dtest=DeactivateRestaurantTenantsMigrationTest test
```

变异（逐条，分开做）：删掉 `UPDATE users` 那段 → `deactivatesUsersToo` 红；把台账 `user_id` 改成 `varchar(255)` → `ledgerSeparatesIdTypes` 红；删掉前置 `DO $$` 断言块 → `hasFailClosedPrecondition` 红。每次都要回退。

- [ ] **Step 5: prod 干跑 —— 必须跑「迁移 + 回滚」完整往返**

```sql
BEGIN;
  -- 粘贴 migration 的真实 SQL（从文件复制，不要手抄）
  SELECT count(*) FROM factories WHERE type='RESTAURANT' AND is_active=true;   -- 期望 1
  SELECT entity_kind, count(*) FROM restaurant_consolidation_ledger_20260805 GROUP BY 1;
  -- 期望 FACTORY=37, USER=45

  -- 粘贴 rollback 的真实 SQL
  SELECT count(*) FROM factories WHERE type='RESTAURANT' AND is_active=true;   -- 期望恢复到 38
ROLLBACK;
```

⚠️ **回滚那一半不能省。** `V20261029_44` 的类型陷阱正是只在回滚时才炸——只跑正向干跑会漏掉它。

- [ ] **Step 6: Commit**

```bash
git status --short
git commit -m "feat(restaurant): 停用 37 个非 MOCK_REST 餐饮租户及其 45 个用户

只翻 is_active 状态位, 零数据删除。

必须连用户一起停: MobileAuthServiceImpl:127/171 只校验 user.getIsActive(),
不校验 factory 的。只停租户 = 登录成功但 AI 网关(ToolPrincipalPolicy:54)
全拒, 半死状态比直接拒绝更糟。

台账 factory_id(varchar) 与 user_id(bigint) 分成两列: 共用一个 object_id
时 INSERT 有隐式转换看不出问题, 只有回滚时的 IN 比较才炸 —— V20261029_44
原样事故。干跑跑了迁移+回滚完整往返, 不是只跑正向。

前后各一个 fail-closed 断言: 前置确认 MOCK_REST 存在(否则会把所有餐饮租户
停光), 后置确认活跃餐饮租户恰好 1 个。" -- \
  backend/java/cretas-api/src/main/resources/db/flyway/V20261029_53__deactivate_nonmock_restaurant_tenants.sql \
  scripts/rollback/V20261029_53_rollback.sql \
  backend/java/cretas-api/src/test/java/com/cretas/aims/migration/DeactivateRestaurantTenantsMigrationTest.java
```

---

## Task 7: 建 4 个部门账号（运维步骤）

> **C2 已定（Steve, 2026-08-05）：`MOCK_REST` 不改名。** 原计划的"改名"步骤取消。
> 理由自洽：公开免登录演示入口已在 Task 5 下线，该租户此后只能账号登录进入，
> `factories.name` 不再是对外可见面。**本 Task 不碰 `factories.name`。**

**Files:** 无代码改动。全部通过既有 API/后台界面操作，**不手工 INSERT**。

**Interfaces:**
- Consumes: Task 3（白名单）+ Task 4（L1 权限行）+ Task 6（其它租户已停用）
- Produces: `MOCK_REST` 下 5 个可登录账号

- [ ] **Step 1: 确认不改名**

`factories.name` 保持「模拟平台餐饮租户 (假 POS 数据接入验证)」不变。本步骤只做一件事：确认没有人顺手改了它。

```bash
# 期望输出仍是原名
```
用只读查询确认 `MOCK_REST` 的 `name` 未被改动。

- [ ] **Step 2: 建 4 个账号**

| 账号用途 | role_code | 建完应看见 |
|---|---|---|
| 运营 | `restaurant_manager` | 只有运营 |
| 市场 | `sales_manager` | 只有市场 |
| 财务 | `finance_manager` | 只有财务 |
| 人事 | `hr_admin` | 只有人事 |

- 走既有用户创建路径（后台用户管理），**不手工 INSERT**。
- 密码写入 `.claude/skills/server-operations/db-credentials.md`（gitignored），**不进仓库**。

- [ ] **Step 3: 逐个真机登录，按下表核对部门可见性 —— 正反两向都验**

| 账号 | 应看见 | **应看不见** |
|---|---|---|
| 运营 | 运营 | 市场、人事、财务 |
| 市场 | 市场 | 运营、人事、财务 |
| 财务 | 财务 | 运营、市场、人事 |
| 人事 | 人事 | 运营、市场、财务 |
| `mock_rest` | 全部四个 | — |

⚠️ **"应看不见"那一列才是关键。** 只验正向的话，权限根本没生效（全都看得见）也会"通过"。

- [ ] **Step 4: 每个账号各问一个属于本部门的 AI 问句**

- 运营：`全部门店最近30天损耗最多的食材是什么`
- 市场：`全部门店最近30天营收多少`
- 财务：`全部门店最近30天哪些菜的食材成本最高`
- 人事：`明天每家店午市和晚市各要排几个人`

四个都必须得到正常作答（不是 CLARIFY、不是报错）。

- [ ] **Step 5: 验证 `MOCK_REST` 写能力完好**

用 `mock_rest` 做一个 AI 写操作（改菜品或录一条损耗），确认**不被演示只读闸拦截**。这是 Task 5 那条硬约束的实地验证。

- [ ] **Step 6: 反向验证工厂侧未被波及**

用 F006 的 `hr_admin` / `finance_manager` / `sales_manager` 账号各登录一次，确认**看不见任何餐饮入口**。这验的是 Task 4 抬高 `restaurant` 上限的爆炸半径确实被 `FACTORY_TYPE_MODULE_FILTER` 兜住了。

- [ ] **Step 7: 记录**

把 5 个账号的用户名、角色、验证结果记入交接文档。密码只进 gitignored 的凭证文件。

---

## Task 8: 撤演示流 systemd unit + 收敛审计名单

**Files:**
- Delete: `scripts/systemd/cretas-restaurant-demo-stream-20260805.{service,timer}`
- Delete: `scripts/systemd/cretas-restaurant-demo-stream-qhj-20260805.{service,timer}`
- Delete: `scripts/deploy/install-restaurant-demo-stream.sh`
- Modify: 服务器上 `cretas-restaurant-audit.service` 的租户传参

**Interfaces:**
- Consumes: Task 6（租户已停用）
- Produces: 演示流 unit 不再存在；审计只跑 `MOCK_REST`

- [ ] **Step 1: 确认这四个 unit 确实没在跑**

```bash
ssh root@47.100.235.168 "systemctl is-enabled cretas-restaurant-demo-stream-20260805.timer cretas-restaurant-demo-stream-qhj-20260805.timer; journalctl -u cretas-restaurant-demo-stream-20260805.service --since '2026-08-05' --no-pager | tail -5"
```

预期：`disabled` / `disabled`，journal 无条目（2026-08-05 实测如此）。**若发现它们在跑，停下来问 Steve**——说明有人在演示后启用过。

- [ ] **Step 2: 删服务器上的 unit 文件（含 `.before-*` 备份）**

```bash
ssh root@47.100.235.168 "cd /etc/systemd/system && ls -la | grep demo-stream"
# 确认清单后逐个 rm, 然后 systemctl daemon-reload
```

- [ ] **Step 3: 删仓库里的 tracked 副本**

```bash
git rm scripts/systemd/cretas-restaurant-demo-stream-20260805.service \
       scripts/systemd/cretas-restaurant-demo-stream-20260805.timer \
       scripts/systemd/cretas-restaurant-demo-stream-qhj-20260805.service \
       scripts/systemd/cretas-restaurant-demo-stream-qhj-20260805.timer \
       scripts/deploy/install-restaurant-demo-stream.sh
```

- [ ] **Step 4: 收敛审计租户名单**

```bash
ssh root@47.100.235.168 "systemctl cat cretas-restaurant-audit.service | grep -A3 ExecStart"
```

把 `ExecStart` 里的三租户参数改为只有 `MOCK_REST`（脚本 `restaurant_adversarial_audit.py:315` 的 `--factory` 默认值本来就是 `MOCK_REST`）。改完 `daemon-reload`，手动跑一次确认输出只有一个租户区块。

- [ ] **Step 5: 找齐 Python refresh 白名单里的残留租户**

⚠️ **按功能搜，不要按前缀搜。** `refresh_*` 之外可能还有别的载体。

```bash
grep -rn "DEMO_REST\|RES_3101_009\|R_GML_DEMO\|R_XMX_CHAIN" backend/python/smartbi/scripts/ backend/python/scripts/ | grep -v test
```

逐个判断是名单还是注释；是名单的收敛到 `MOCK_REST`。

- [ ] **Step 6: 跑一次审计确认不掉分**

```bash
ssh root@47.100.235.168 "systemctl start cretas-restaurant-audit.service && sleep 90 && journalctl -u cretas-restaurant-audit.service -n 30 --no-pager | grep -E 'OK [0-9]+/|CLARIFY'"
```

判据：`MOCK_REST` **≥ 21/22**（与 2026-08-05 停用前持平，不许掉分）。

- [ ] **Step 7: Commit**

```bash
git status --short
git commit -m "chore(restaurant): 撤 8/5 演示流 unit, 审计名单收敛到 MOCK_REST

四个 demo-stream unit 实测 is-enabled=disabled 且 8/5 09:00-14:00 演示窗口
内 journal 零条目 —— 从未运行过。演示已结束, 一并撤除。

审计从三租户(MOCK_REST/R_GML_DEMO/RES_3101_009)收敛为只跑 MOCK_REST;
脚本 --factory 默认值本来就是 MOCK_REST, 三租户是 systemd 传参给的。" -- \
  scripts/systemd/ scripts/deploy/install-restaurant-demo-stream.sh
```

---

## 全量验收（全部 Task 完成后）

必须**全部**满足：

1. `select count(*) from factories where type='RESTAURANT' and is_active=true` **= 1**，且是 `MOCK_REST`。
2. 每日审计跑 `MOCK_REST` **≥ 21/22**。
3. 5 个账号逐个真机登录，部门可见性**正反两向**都符合 Task 7 Step 3 的表。
4. 4 个部门账号各问一个本部门 AI 问句，均正常作答。
5. `MOCK_REST` 写能力完好（AI 写操作不被只读闸拦）。
6. F006 的 `hr_admin`/`finance_manager`/`sales_manager` **看不见任何餐饮入口**。
7. 任取一个已停用租户的账号登录 → **被明确拒绝**，不是登进去半死。
8. 四个 demo-stream unit 在服务器与仓库均已不存在。
9. Java 目标测试通过；碰 Entity/Repository 的用真实 JPA Context；每条新断言都做过变异验证（红 → 回退 → 绿）。
10. 生产 ERP 业务数据写入 **0**。

---

## 待确认（阻塞对应 Task）

| # | 内容 | 阻塞 |
|---|---|---|
| ~~C2~~ | ~~`MOCK_REST` 对外显示的名字~~ → **已定 2026-08-05：不改名**，Task 7 Step 1 相应改写 | 无 |
| **S1** | Task 5 Step 1 的调研结论：`cretas.demo.rest.factory-id` 置空后消费者是"跳过"还是"匹配任意" | Task 5 Step 3 |

**已无待人工确认项。** S1 由 Task 5 的实施者读代码回答，不需要 Steve 介入。

---

## 本轮明确不做

- 不删任何数据；不跨租户搬数据。
- 不加"防止再长出新租户"的闸（Steve：当前无客户，无人建租户）。
- 不修飞轮（`ai_promoted_routes` 3 条 seed / 0 条 flywheel / hit_count 全 0）。
- 不修今早审计的 3 条真红项（`BUSINESS_OPTIMIZATION` grounding 驳回、`CHANNEL_MIX` 外卖占比、`STAFFING_ADVICE` 下月人效）。
- 不修 `restaurant_owner` / `restaurant_chef` 在 L1 表零行导致四部门全不可见的既有问题。
- 不查 `F002` 的孤儿聚合行来源。
