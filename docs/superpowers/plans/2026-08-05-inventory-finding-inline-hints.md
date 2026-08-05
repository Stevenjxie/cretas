# 库存 Finding 层 + 同步顺带提示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「查库存」这个动作在返回汇总数字的同时，顺带说出最重要的 1–2 条低库存异常；异常检测逻辑落在一个可被其他出口（列表页 footer / 定时日报 / 告警）复用的 `Finding` 层里。

**Architecture:** 新增 `service/finding` 包，定义 `Finding` 数据结构 + `FindingProvider` 接口（每条规则一个实现）+ `FindingService`（收集、排序、截断）。首个实现 `LowStockFindingProvider` 包装已有的 `MaterialBatchService#getLowStockWarnings`（不新写 SQL，不改口径）。渲染由 `FindingTextRenderer#renderInline` 负责——**`Finding` 只装结构化数据，不装话术**。出口侧只改 `MaterialStockSummaryTool` 一个 Tool。

**Tech Stack:** Java 21 + Spring Boot 3.2.12 + JUnit 5 + Mockito（`@ExtendWith(MockitoExtension.class)`，参照 `StockAlertWorkdeskToolTest`）

## Global Constraints

- **禁止降级处理**：Provider 抛异常时不得静默当作「无异常」——必须把该规则从 `checkedRules` 移除，绝不能让 UI 说出「已检查 X，均正常」。
- **禁止创建 IntentHandler**（`.claude/rules/ai-intent-tool-skill-architecture.md`）：本计划不新增 Tool，只改已有 Tool。
- **字段命名**：Java camelCase ↔ JSON camelCase，禁止 `@JsonProperty("snake_case")`。
- **口径单一来源**：低库存的判定**只能**来自 `MaterialBatchService#getLowStockWarnings`。禁止新写 SQL——`ListSummaryServiceImpl.java:43-50` 记录了上次自写口径导致 footer「808项」vs KPI 卡片接近 0 的事故。
- **不接 LLM**：本计划全程零 LLM 调用，话术走模板。
- **不新增查询**：`MaterialStockSummaryTool` 改造后对 `getLowStockWarnings` 的调用次数必须仍是 **1 次**（改造前是 1 次，不得变成 2 次）。
- **worktree 隔离**（`.claude/rules/worktree-and-main-only-deploy.md`）：全部工作在 off `origin/main` 的独立 worktree 内完成。
- **commit 用 `--only` 模式**（`.claude/rules/concurrent-edit-safety.md` 5b）：`git commit -m "msg" -- <file1> <file2>`，防止并发 session 的 staged 文件被吞进来。

## 既有事实（实现者必读，不要重新推断）

`MaterialBatchServiceImpl#getLowStockWarnings(factoryId)`（`MaterialBatchServiceImpl.java:1063-1125`）返回 `List<Map<String,Object>>`，每个 Map 的 key 是（`LinkedHashMap`，顺序即下）：

| key | 类型 | 说明 |
|---|---|---|
| `materialTypeId` | String | 原材料类型 ID |
| `materialName` | String | 名称 |
| `materialCode` | String | 编码 |
| `category` | String | 分类 |
| `currentStock` | BigDecimal | 当前库存 |
| `safetyStock` | BigDecimal | 安全库存（来自 `RawMaterialType.minStock`） |
| `unit` | String | 单位 |
| `gap` | BigDecimal | `safetyStock - currentStock` |
| `stockRatio` | **long**（`Math.round` 的结果） | 百分比整数，如 `40` 表示 40% |
| `warningLevel` | String | `CRITICAL` / `WARNING` / `INFO` |

⚠️ **没有 `preferredSupplier`**。`MaterialLowStockAlertTool.java:126` 的 `containsKey("preferredSupplier")` 分支是死代码。**任何话术不得引用供应商。**

⚠️ `minStock` 为 null 或 ≤0 的物料被 `continue` 跳过（`MaterialBatchServiceImpl.java:1079-1082`），即**未设安全库存的物料不会产生任何预警**。这是既有行为，本计划不改。

返回结果**已按 CRITICAL > WARNING > INFO 排序**（`MaterialBatchServiceImpl.java:1117-1121`）。

`MaterialStockSummaryTool#doExecute`（`MaterialStockSummaryTool.java:101-125`）当前返回扁平 Map：`totalBatches` / `lowStockCount` / `batches` / `message`。改造必须**保留这 4 个 key 及其语义**，只做新增。

---

## File Structure

**Create（6 个源文件）：**

| 文件 | 职责 |
|---|---|
| `service/finding/Finding.java` | 单条发现的数据结构（record）+ 嵌套 `Severity` 枚举 + `rankScore()` |
| `service/finding/FindingProvider.java` | 规则接口：`domain()` / `ruleName()` / `detect(factoryId)` |
| `service/finding/FindingService.java` | 服务接口 + 嵌套 `Result` record |
| `service/finding/impl/FindingServiceImpl.java` | 收集同 domain 的 provider、排序、截断、异常隔离 |
| `service/finding/impl/LowStockFindingProvider.java` | 低库存规则，包装 `getLowStockWarnings` |
| `service/finding/FindingTextRenderer.java` | `renderInline(Result)` → 顺带提示文案 |

**Modify（1 个）：** `ai/tool/impl/material/MaterialStockSummaryTool.java`

**Test（4 个）：** 与源文件同包镜像路径下的 `*Test.java`

> **为什么 `Result` 嵌在 `FindingService` 里**：减少文件数，且它只服务于这个接口。
> **为什么 `inline-max` 用 `@Value` 而不是 `@ConfigurationProperties`**：v1 只有一个配置项。等第 2 个配置项（临期天数）出现时再升级成 `FindingProperties`。

---

## 全部 Task 一览

| Task | 交付物 | 依赖 |
|---|---|---|
| 0 | worktree 就绪 | — |
| 1 | `Finding` + `Severity` + `rankScore()` | 0 |
| 2 | `FindingProvider` + `FindingService` 接口 | 1 |
| 3 | `LowStockFindingProvider` | 2 |
| 4 | `FindingServiceImpl`（排序/截断/异常隔离） | 2 |
| 5 | `FindingTextRenderer` | 1 |
| 6 | `MaterialStockSummaryTool` 接入 | 3,4,5 |
| 7 | 变异验证（证明测试会红） | 6 |

---

### Task 0: worktree 就绪

**Files:** 无（环境准备）

**Interfaces:**
- Consumes: 无
- Produces: 一个 off `origin/main` 的干净工作目录

- [ ] **Step 1: 建 worktree**

```bash
cd C:/Users/Steve/my-prototype-logistics
git fetch origin
git worktree add -b codex/claude-inventory-finding ../cretas-finding origin/main
cd ../cretas-finding
```

- [ ] **Step 2: 确认基底干净**

Run: `git status --short && git log --oneline -1`
Expected: `git status` 无输出；`git log` 显示的 commit 与 `git rev-parse origin/main` 一致。

- [ ] **Step 3: 确认 Maven 能跑起来（基线绿）**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=StockAlertWorkdeskToolTest test`
Expected: BUILD SUCCESS。若失败，**先停下排查环境**，不要开始写代码——否则后面无法区分「我写挂了」和「基线本来就挂」。

---

### Task 1: `Finding` 数据结构

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/Finding.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `com.cretas.aims.service.finding.Finding` — record，构造参数顺序为 `(String code, String domain, Severity severity, int actionability, String subjectId, String subjectName, Map<String,Object> facts)`
  - `Finding.Severity` — 枚举 `CRITICAL(3) / WARNING(2) / INFO(1)`，方法 `int weight()`
  - `int Finding#rankScore()` — 返回 `severity.weight() * 100 + actionability`

- [ ] **Step 1: 写失败的测试**

Create `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingTest.java`:

```java
package com.cretas.aims.service.finding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Finding}. */
class FindingTest {

    private Finding of(Finding.Severity severity, int actionability) {
        return new Finding("LOW_STOCK", "inventory", severity, actionability,
                "M001", "鲈鱼", Map.of("gap", 38));
    }

    @Test
    @DisplayName("UT-FND-01: severity 权重 CRITICAL=3 > WARNING=2 > INFO=1")
    void severityWeights() {
        assertEquals(3, Finding.Severity.CRITICAL.weight());
        assertEquals(2, Finding.Severity.WARNING.weight());
        assertEquals(1, Finding.Severity.INFO.weight());
    }

    @Test
    @DisplayName("UT-FND-02: rankScore = severity*100 + actionability")
    void rankScoreFormula() {
        assertEquals(350, of(Finding.Severity.CRITICAL, 50).rankScore());
        assertEquals(250, of(Finding.Severity.WARNING, 50).rankScore());
        assertEquals(199, of(Finding.Severity.INFO, 99).rankScore());
    }

    @Test
    @DisplayName("UT-FND-03: severity 压过 actionability —— INFO 满 actionability 也排不过 WARNING")
    void severityDominatesActionability() {
        assertTrue(of(Finding.Severity.WARNING, 0).rankScore()
                > of(Finding.Severity.INFO, 99).rankScore());
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingTest test`
Expected: 编译失败，`cannot find symbol: class Finding`

- [ ] **Step 3: 写最小实现**

Create `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/Finding.java`:

```java
package com.cretas.aims.service.finding;

import java.util.Map;

/**
 * 一条「发现」——由规则（{@link FindingProvider}）产出的结构化异常事实。
 *
 * <p>⛔ 刻意不含任何展示文案。同一条 Finding 会被多个出口渲染（同步顺带提示 /
 * 定时日报 / 告警），各出口措辞需求不同；文案一旦塞进这里，出口分叉时就会开始
 * 往 Finding 里加 if。渲染一律由出口侧的 renderer 负责。
 *
 * @param code          稳定机器码，如 {@code LOW_STOCK}。渲染层按它分派模板。
 * @param domain        领域，取值对齐 {@code ListSummaryService.SUPPORTED}
 *                      （inventory / salesOrder / qualityInspection / ...），
 *                      以便将来挂到列表页 footer 时天然对齐。
 * @param severity      严重度，由 provider 自评。
 * @param actionability 可行动性 0–99：「现在动手还来不来得及」。与 severity 正交
 *                      ——已过期是高 severity 低 actionability，临期 3 天是中
 *                      severity 高 actionability。v1 只有一个 provider，排序由
 *                      severity 主导；本字段先建好，等第 2 个 provider 进来再调权重。
 * @param subjectId     指向的具体对象 ID（如 materialTypeId）。
 * @param subjectName   对象名称（如 鲈鱼）。
 * @param facts         结构化事实，渲染层取值用。
 */
public record Finding(
        String code,
        String domain,
        Severity severity,
        int actionability,
        String subjectId,
        String subjectName,
        Map<String, Object> facts
) {

    public enum Severity {
        CRITICAL(3),
        WARNING(2),
        INFO(1);

        private final int weight;

        Severity(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }
    }

    /** 排序分。severity 主导（×100），actionability 作为同级内的次序。 */
    public int rankScore() {
        return severity.weight() * 100 + actionability;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingTest test`
Expected: PASS，3 个测试全绿

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(finding): add Finding record with severity/actionability rank score" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/Finding.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingTest.java
```

---

### Task 2: `FindingProvider` + `FindingService` 接口

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingProvider.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingService.java`
- Test: 无（纯接口，无行为可测；由 Task 3/4 的实现测试覆盖）

**Interfaces:**
- Consumes: `Finding`（Task 1）
- Produces:
  - `FindingProvider` — `String domain()` / `String ruleName()` / `List<Finding> detect(String factoryId)`
  - `FindingService` — `Result detectInline(String factoryId, String domain)`
  - `FindingService.Result` — record `(List<Finding> findings, List<String> checkedRules, int totalCount, Map<String,Integer> countsByCode)`

- [ ] **Step 1: 写 `FindingProvider`**

Create `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingProvider.java`:

```java
package com.cretas.aims.service.finding;

import java.util.List;

/**
 * 一条发现规则。每条规则一个 {@code @Component} 实现，由
 * {@link FindingService} 按 {@link #domain()} 收集。
 *
 * <p>⛔ 实现禁止新写口径 SQL。规则必须复用已有的 service 方法，否则会出现
 * 同名指标两套定义（见 ListSummaryServiceImpl.java:43-50 记录的 footer
 * 「808项」vs KPI 卡片接近 0 的事故）。
 */
public interface FindingProvider {

    /** 领域，对齐 ListSummaryService 的 entityType 词汇（inventory / salesOrder / ...）。 */
    String domain();

    /** 规则的人类可读名，会出现在「已检查 XXX，均正常」里。如「低库存」。 */
    String ruleName();

    /** 执行检测。返回空列表表示本规则未发现异常。 */
    List<Finding> detect(String factoryId);
}
```

- [ ] **Step 2: 写 `FindingService`**

Create `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingService.java`:

```java
package com.cretas.aims.service.finding;

import java.util.List;
import java.util.Map;

/** 发现层统一入口。同步顺带提示 / 定时日报 / 告警三个出口共用此层。 */
public interface FindingService {

    /**
     * 为「顺带提示」出口检测异常。
     *
     * @param factoryId 工厂
     * @param domain    领域，如 {@code inventory}
     */
    Result detectInline(String factoryId, String domain);

    /**
     * @param findings     已排序并截断到 inline 上限的发现（可能为空）
     * @param checkedRules **实际成功跑完**的规则名。抛异常的规则不在此列——
     *                     否则 UI 会说出「已检查 X，均正常」这种假话。
     * @param totalCount   截断前的发现总数，用于「还有 N 项」
     * @param countsByCode 按 code 分组的**截断前**计数，供调用方复用
     *                     （如 lowStockCount = countsByCode.get("LOW_STOCK")）
     */
    record Result(
            List<Finding> findings,
            List<String> checkedRules,
            int totalCount,
            Map<String, Integer> countsByCode
    ) {}
}
```

- [ ] **Step 3: 确认编译通过**

Run: `cd backend/java/cretas-api && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(finding): add FindingProvider and FindingService interfaces" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingProvider.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingService.java
```

---

### Task 3: `LowStockFindingProvider`

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/LowStockFindingProvider.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/LowStockFindingProviderTest.java`

**Interfaces:**
- Consumes: `Finding`（Task 1）、`FindingProvider`（Task 2）、`MaterialBatchService#getLowStockWarnings(String)`（既有）
- Produces: `LowStockFindingProvider`，`domain()` 返回 `"inventory"`，`ruleName()` 返回 `"低库存"`，产出 `Finding.code == "LOW_STOCK"`

- [ ] **Step 1: 写失败的测试**

Create `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/LowStockFindingProviderTest.java`:

```java
package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finding.Finding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link LowStockFindingProvider}. */
@ExtendWith(MockitoExtension.class)
class LowStockFindingProviderTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private LowStockFindingProvider provider;

    @Mock
    private MaterialBatchService materialBatchService;

    /** 严格复刻 MaterialBatchServiceImpl#getLowStockWarnings 的 key 集合与类型。 */
    private Map<String, Object> warning(String id, String name, String level,
                                        String current, String safety, String gap, long ratio) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("materialTypeId", id);
        w.put("materialName", name);
        w.put("materialCode", "MC-" + id);
        w.put("category", "水产");
        w.put("currentStock", new BigDecimal(current));
        w.put("safetyStock", new BigDecimal(safety));
        w.put("unit", "kg");
        w.put("gap", new BigDecimal(gap));
        w.put("stockRatio", ratio);
        w.put("warningLevel", level);
        return w;
    }

    @Test
    @DisplayName("UT-LSF-01: domain=inventory, ruleName=低库存")
    void metadata() {
        assertEquals("inventory", provider.domain());
        assertEquals("低库存", provider.ruleName());
    }

    @Test
    @DisplayName("UT-LSF-02: 把 warning map 映射成 Finding，facts 保留数值字段")
    void mapsWarningToFinding() {
        when(materialBatchService.getLowStockWarnings(anyString()))
                .thenReturn(List.of(warning("M001", "鲈鱼", "WARNING", "12", "50", "38", 24L)));

        List<Finding> findings = provider.detect(FACTORY_ID);

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("LOW_STOCK", f.code());
        assertEquals("inventory", f.domain());
        assertEquals(Finding.Severity.WARNING, f.severity());
        assertEquals("M001", f.subjectId());
        assertEquals("鲈鱼", f.subjectName());
        assertEquals(new BigDecimal("12"), f.facts().get("currentStock"));
        assertEquals(new BigDecimal("50"), f.facts().get("safetyStock"));
        assertEquals(new BigDecimal("38"), f.facts().get("gap"));
        assertEquals("kg", f.facts().get("unit"));
        assertEquals(24L, f.facts().get("stockRatio"));
    }

    @Test
    @DisplayName("UT-LSF-03: warningLevel 三值分别映射到对应 Severity")
    void mapsAllSeverityLevels() {
        when(materialBatchService.getLowStockWarnings(anyString())).thenReturn(List.of(
                warning("M001", "A", "CRITICAL", "0", "50", "50", 0L),
                warning("M002", "B", "WARNING", "20", "50", "30", 40L),
                warning("M003", "C", "INFO", "35", "50", "15", 70L)));

        List<Finding> findings = provider.detect(FACTORY_ID);

        assertEquals(Finding.Severity.CRITICAL, findings.get(0).severity());
        assertEquals(Finding.Severity.WARNING, findings.get(1).severity());
        assertEquals(Finding.Severity.INFO, findings.get(2).severity());
    }

    @Test
    @DisplayName("UT-LSF-04: 未知 warningLevel 降级为 INFO 而不是抛异常")
    void unknownLevelFallsBackToInfo() {
        when(materialBatchService.getLowStockWarnings(anyString()))
                .thenReturn(List.of(warning("M001", "鲈鱼", "SOMETHING_NEW", "12", "50", "38", 24L)));

        assertEquals(Finding.Severity.INFO, provider.detect(FACTORY_ID).get(0).severity());
    }

    @Test
    @DisplayName("UT-LSF-05: 无预警时返回空列表（不是 null）")
    void emptyWhenNoWarnings() {
        when(materialBatchService.getLowStockWarnings(anyString())).thenReturn(List.of());

        List<Finding> findings = provider.detect(FACTORY_ID);

        assertNotNull(findings);
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("UT-LSF-06: 话术不得引用供应商 —— facts 里禁止出现 preferredSupplier")
    void factsMustNotClaimSupplier() {
        when(materialBatchService.getLowStockWarnings(anyString()))
                .thenReturn(List.of(warning("M001", "鲈鱼", "WARNING", "12", "50", "38", 24L)));

        assertFalse(provider.detect(FACTORY_ID).get(0).facts().containsKey("preferredSupplier"),
                "getLowStockWarnings 从不产出 preferredSupplier，facts 不得凭空造出该字段");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=LowStockFindingProviderTest test`
Expected: 编译失败，`cannot find symbol: class LowStockFindingProvider`

- [ ] **Step 3: 写最小实现**

Create `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/LowStockFindingProvider.java`:

```java
package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 低库存发现规则。
 *
 * <p>口径**完全**来自 {@link MaterialBatchService#getLowStockWarnings(String)}
 * （materialType 级，对比 RawMaterialType.minStock）。本类只做形状转换，
 * 不做任何判定 —— 这样它跟 web-admin 的「低库存预警」KPI 卡片永远一致。
 */
@Component
@RequiredArgsConstructor
public class LowStockFindingProvider implements FindingProvider {

    /**
     * 低库存的可行动性。v1 恒定 50：所有低库存的处置动作都是「去补货」，
     * 彼此之间没有可区分的紧迫度差异。等临期（今天不用就废，高）和呆滞
     * （随时可处理，低）两个 provider 进来后，这个常量才有对比意义。
     */
    private static final int ACTIONABILITY = 50;

    private final MaterialBatchService materialBatchService;

    @Override
    public String domain() {
        return "inventory";
    }

    @Override
    public String ruleName() {
        return "低库存";
    }

    @Override
    public List<Finding> detect(String factoryId) {
        List<Map<String, Object>> warnings = materialBatchService.getLowStockWarnings(factoryId);
        List<Finding> findings = new ArrayList<>();
        for (Map<String, Object> w : warnings) {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("currentStock", w.get("currentStock"));
            facts.put("safetyStock", w.get("safetyStock"));
            facts.put("gap", w.get("gap"));
            facts.put("unit", w.get("unit"));
            facts.put("stockRatio", w.get("stockRatio"));
            findings.add(new Finding(
                    "LOW_STOCK",
                    "inventory",
                    toSeverity((String) w.get("warningLevel")),
                    ACTIONABILITY,
                    (String) w.get("materialTypeId"),
                    (String) w.get("materialName"),
                    facts));
        }
        return findings;
    }

    private Finding.Severity toSeverity(String warningLevel) {
        if ("CRITICAL".equals(warningLevel)) {
            return Finding.Severity.CRITICAL;
        }
        if ("WARNING".equals(warningLevel)) {
            return Finding.Severity.WARNING;
        }
        return Finding.Severity.INFO;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=LowStockFindingProviderTest test`
Expected: PASS，6 个测试全绿

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(finding): add LowStockFindingProvider wrapping getLowStockWarnings" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/LowStockFindingProvider.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/LowStockFindingProviderTest.java
```

---

### Task 4: `FindingServiceImpl`（排序 / 截断 / 异常隔离）

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/FindingServiceImpl.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/FindingServiceImplTest.java`

**Interfaces:**
- Consumes: `Finding`（Task 1）、`FindingProvider` / `FindingService`（Task 2）
- Produces: `FindingServiceImpl`，构造函数 `FindingServiceImpl(List<FindingProvider> providers, int inlineMax)`

- [ ] **Step 1: 写失败的测试**

Create `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/FindingServiceImplTest.java`:

```java
package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingProvider;
import com.cretas.aims.service.finding.FindingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link FindingServiceImpl}. */
class FindingServiceImplTest {

    private static final String FACTORY_ID = "F006";

    /** 固定返回给定 finding 的假 provider。 */
    private static FindingProvider stub(String domain, String ruleName, Finding... findings) {
        return new FindingProvider() {
            @Override public String domain() { return domain; }
            @Override public String ruleName() { return ruleName; }
            @Override public List<Finding> detect(String factoryId) { return List.of(findings); }
        };
    }

    /** 必定抛异常的假 provider。 */
    private static FindingProvider exploding(String domain, String ruleName) {
        return new FindingProvider() {
            @Override public String domain() { return domain; }
            @Override public String ruleName() { return ruleName; }
            @Override public List<Finding> detect(String factoryId) {
                throw new IllegalStateException("boom");
            }
        };
    }

    private static Finding finding(String code, String name, Finding.Severity severity) {
        return new Finding(code, "inventory", severity, 50, "id-" + name, name, Map.of());
    }

    @Test
    @DisplayName("UT-FSI-01: 只跑同 domain 的 provider")
    void filtersByDomain() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存", finding("LOW_STOCK", "鲈鱼", Finding.Severity.WARNING)),
                stub("salesOrder", "逾期未发货", finding("OVERDUE", "SO-1", Finding.Severity.CRITICAL))
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(List.of("低库存"), r.checkedRules());
        assertEquals(1, r.findings().size());
        assertEquals("鲈鱼", r.findings().get(0).subjectName());
    }

    @Test
    @DisplayName("UT-FSI-02: 按 rankScore 降序排序")
    void sortsByRankScoreDescending() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存",
                        finding("LOW_STOCK", "低", Finding.Severity.INFO),
                        finding("LOW_STOCK", "高", Finding.Severity.CRITICAL),
                        finding("LOW_STOCK", "中", Finding.Severity.WARNING))
        ), 3);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(List.of("高", "中", "低"),
                r.findings().stream().map(Finding::subjectName).toList());
    }

    @Test
    @DisplayName("UT-FSI-03: 截断到 inlineMax，totalCount 仍是截断前总数")
    void truncatesToInlineMax() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存",
                        finding("LOW_STOCK", "A", Finding.Severity.CRITICAL),
                        finding("LOW_STOCK", "B", Finding.Severity.WARNING),
                        finding("LOW_STOCK", "C", Finding.Severity.INFO),
                        finding("LOW_STOCK", "D", Finding.Severity.INFO))
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(2, r.findings().size());
        assertEquals(4, r.totalCount());
    }

    @Test
    @DisplayName("UT-FSI-04: countsByCode 统计的是截断前的数量")
    void countsByCodeUsesPreTruncationTotals() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存",
                        finding("LOW_STOCK", "A", Finding.Severity.CRITICAL),
                        finding("LOW_STOCK", "B", Finding.Severity.WARNING),
                        finding("LOW_STOCK", "C", Finding.Severity.INFO)),
                stub("inventory", "临期", finding("EXPIRING", "D", Finding.Severity.WARNING))
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(3, r.countsByCode().get("LOW_STOCK"));
        assertEquals(1, r.countsByCode().get("EXPIRING"));
    }

    @Test
    @DisplayName("UT-FSI-05: 🔴 provider 抛异常时，该规则不得出现在 checkedRules 里")
    void failedProviderIsNotReportedAsChecked() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("inventory", "低库存"),
                exploding("inventory", "临期")
        ), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertEquals(List.of("低库存"), r.checkedRules(),
                "炸掉的规则若留在 checkedRules 里，UI 会说出「已检查 临期，均正常」这种假话");
        assertTrue(r.findings().isEmpty());
    }

    @Test
    @DisplayName("UT-FSI-06: 无 provider 匹配时返回空 Result 而不是 null")
    void noMatchingProviderReturnsEmptyResult() {
        FindingService svc = new FindingServiceImpl(List.of(
                stub("salesOrder", "逾期未发货")), 2);

        FindingService.Result r = svc.detectInline(FACTORY_ID, "inventory");

        assertNotNull(r);
        assertTrue(r.findings().isEmpty());
        assertTrue(r.checkedRules().isEmpty());
        assertEquals(0, r.totalCount());
        assertTrue(r.countsByCode().isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingServiceImplTest test`
Expected: 编译失败，`cannot find symbol: class FindingServiceImpl`

- [ ] **Step 3: 写最小实现**

Create `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/FindingServiceImpl.java`:

```java
package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingProvider;
import com.cretas.aims.service.finding.FindingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发现层实现：收集同 domain 的 provider、按 rankScore 排序、截断到 inline 上限。
 *
 * <p>单条 provider 抛异常时**隔离**：不中断其他规则，且把该规则从
 * checkedRules 里剔除。禁止把失败当作「无异常」——那会让 UI 说出
 * 「已检查 X，均正常」的假话（禁止降级处理）。
 */
@Slf4j
@Service
public class FindingServiceImpl implements FindingService {

    private final List<FindingProvider> providers;
    private final int inlineMax;

    public FindingServiceImpl(List<FindingProvider> providers,
                              @Value("${cretas.finding.inline-max:2}") int inlineMax) {
        this.providers = providers;
        this.inlineMax = inlineMax;
    }

    @Override
    public Result detectInline(String factoryId, String domain) {
        List<Finding> all = new ArrayList<>();
        List<String> checked = new ArrayList<>();

        for (FindingProvider provider : providers) {
            if (!provider.domain().equals(domain)) {
                continue;
            }
            try {
                all.addAll(provider.detect(factoryId));
                checked.add(provider.ruleName());
            } catch (Exception e) {
                log.warn("Finding 规则执行失败, 已从 checkedRules 剔除: rule={}, domain={}, factoryId={}",
                        provider.ruleName(), domain, factoryId, e);
            }
        }

        Map<String, Integer> countsByCode = new LinkedHashMap<>();
        for (Finding f : all) {
            countsByCode.merge(f.code(), 1, Integer::sum);
        }

        all.sort(Comparator.comparingInt(Finding::rankScore).reversed());
        int total = all.size();
        List<Finding> top = total > inlineMax ? all.subList(0, inlineMax) : all;

        return new Result(List.copyOf(top), List.copyOf(checked), total, Map.copyOf(countsByCode));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingServiceImplTest test`
Expected: PASS，6 个测试全绿

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(finding): add FindingServiceImpl with rank/truncate/failure-isolation" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/FindingServiceImpl.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/impl/FindingServiceImplTest.java
```

---

### Task 5: `FindingTextRenderer`

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingTextRenderer.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingTextRendererTest.java`

**Interfaces:**
- Consumes: `Finding`（Task 1）、`FindingService.Result`（Task 2）
- Produces: `String FindingTextRenderer#renderInline(FindingService.Result)`

> **方法名刻意叫 `renderInline` 而不是 `render`**：将来日报出口会加 `renderDigest`。从第一天就按出口命名，可以防止两个出口共用一句话、需求分叉后开始往里塞 if。

- [ ] **Step 1: 写失败的测试**

Create `backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingTextRendererTest.java`:

```java
package com.cretas.aims.service.finding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link FindingTextRenderer}. */
class FindingTextRendererTest {

    private final FindingTextRenderer renderer = new FindingTextRenderer();

    private static Finding lowStock(String name, String current, String safety, String gap) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("currentStock", new BigDecimal(current));
        facts.put("safetyStock", new BigDecimal(safety));
        facts.put("gap", new BigDecimal(gap));
        facts.put("unit", "kg");
        facts.put("stockRatio", 24L);
        return new Finding("LOW_STOCK", "inventory", Finding.Severity.WARNING, 50,
                "M-" + name, name, facts);
    }

    @Test
    @DisplayName("UT-FTR-01: 无发现时输出「已检查 X，均正常」，且只列实际跑过的规则")
    void allClearListsOnlyCheckedRules() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of("低库存"), 0, Map.of());

        String text = renderer.renderInline(r);

        assertTrue(text.contains("已检查"), text);
        assertTrue(text.contains("低库存"), text);
        assertTrue(text.contains("正常"), text);
        assertFalse(text.contains("临期"), "不得声称检查了未注册的规则: " + text);
    }

    @Test
    @DisplayName("UT-FTR-02: 🔴 checkedRules 为空时返回空串 —— 一条规则都没跑成，不许说任何话")
    void nothingCheckedRendersNothing() {
        FindingService.Result r = new FindingService.Result(
                List.of(), List.of(), 0, Map.of());

        assertEquals("", renderer.renderInline(r),
                "全部规则失败时若仍输出「均正常」，就是把故障渲染成了健康");
    }

    @Test
    @DisplayName("UT-FTR-03: 单条低库存渲染出名称/当前量/安全线/缺口")
    void rendersLowStockNumbers() {
        FindingService.Result r = new FindingService.Result(
                List.of(lowStock("鲈鱼", "12", "50", "38")), List.of("低库存"), 1,
                Map.of("LOW_STOCK", 1));

        String text = renderer.renderInline(r);

        assertTrue(text.contains("鲈鱼"), text);
        assertTrue(text.contains("12"), text);
        assertTrue(text.contains("50"), text);
        assertTrue(text.contains("38"), text);
        assertTrue(text.contains("kg"), text);
    }

    @Test
    @DisplayName("UT-FTR-04: 🔴 渲染文案不得出现供应商 —— 该字段全链路不存在")
    void neverMentionsSupplier() {
        FindingService.Result r = new FindingService.Result(
                List.of(lowStock("鲈鱼", "12", "50", "38")), List.of("低库存"), 1,
                Map.of("LOW_STOCK", 1));

        String text = renderer.renderInline(r);

        assertFalse(text.contains("供应商"),
                "getLowStockWarnings 不产出 preferredSupplier，渲染层不得凭空提及: " + text);
    }

    @Test
    @DisplayName("UT-FTR-05: 超出上限时提示「还有 N 项」，N = totalCount - 已显示条数")
    void showsRemainingCount() {
        FindingService.Result r = new FindingService.Result(
                List.of(lowStock("鲈鱼", "12", "50", "38"), lowStock("带鱼", "5", "40", "35")),
                List.of("低库存"), 7, Map.of("LOW_STOCK", 7));

        String text = renderer.renderInline(r);

        assertTrue(text.contains("还有 5 项"), text);
    }

    @Test
    @DisplayName("UT-FTR-06: 未超出上限时不出现「还有」")
    void noRemainingHintWhenNotTruncated() {
        FindingService.Result r = new FindingService.Result(
                List.of(lowStock("鲈鱼", "12", "50", "38")), List.of("低库存"), 1,
                Map.of("LOW_STOCK", 1));

        assertFalse(renderer.renderInline(r).contains("还有"));
    }

    @Test
    @DisplayName("UT-FTR-07: 未知 code 走兜底模板，不抛异常也不输出 null")
    void unknownCodeFallsBack() {
        Finding unknown = new Finding("SOMETHING_NEW", "inventory",
                Finding.Severity.WARNING, 50, "X1", "神秘物料", Map.of());
        FindingService.Result r = new FindingService.Result(
                List.of(unknown), List.of("神秘规则"), 1, Map.of("SOMETHING_NEW", 1));

        String text = renderer.renderInline(r);

        assertTrue(text.contains("神秘物料"), text);
        assertFalse(text.contains("null"), text);
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingTextRendererTest test`
Expected: 编译失败，`cannot find symbol: class FindingTextRenderer`

- [ ] **Step 3: 写最小实现**

Create `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingTextRenderer.java`:

```java
package com.cretas.aims.service.finding;

import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 「顺带提示」出口的文案渲染。**纯模板，零 LLM**——数字全部来自 Finding.facts，
 * 渲染层不做任何计算，也不得引用 facts 里不存在的字段。
 */
@Component
public class FindingTextRenderer {

    public String renderInline(FindingService.Result result) {
        // 一条规则都没成功跑完 —— 什么都不说。绝不能渲染成「均正常」，
        // 那是把故障说成健康（禁止降级处理）。
        if (result.checkedRules().isEmpty()) {
            return "";
        }

        String checked = String.join(" / ", result.checkedRules());

        if (result.findings().isEmpty()) {
            return "✅ 已检查 " + checked + "，均正常。";
        }

        String lines = result.findings().stream()
                .map(this::renderOne)
                .collect(Collectors.joining("\n"));

        int remaining = result.totalCount() - result.findings().size();
        String more = remaining > 0 ? "\n还有 " + remaining + " 项待查看" : "";

        return "⚠️ 顺带 " + result.findings().size() + " 件事：\n" + lines + more;
    }

    private String renderOne(Finding f) {
        if ("LOW_STOCK".equals(f.code())) {
            Object unit = f.facts().get("unit");
            return String.format(" · %s 剩 %s%s，低于安全线 %s%s（缺 %s%s）",
                    f.subjectName(),
                    f.facts().get("currentStock"), unit,
                    f.facts().get("safetyStock"), unit,
                    f.facts().get("gap"), unit);
        }
        // 兜底：新 code 上线但模板未跟上时，至少说出对象名，不输出 null。
        return " · " + f.subjectName();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingTextRendererTest test`
Expected: PASS，7 个测试全绿

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(finding): add FindingTextRenderer for the inline-hint outlet" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingTextRenderer.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/finding/FindingTextRendererTest.java
```

---

### Task 6: `MaterialStockSummaryTool` 接入

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/material/MaterialStockSummaryTool.java:101-125`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/material/MaterialStockSummaryToolTest.java`

**Interfaces:**
- Consumes: `FindingService#detectInline`（Task 4）、`FindingTextRenderer#renderInline`（Task 5）
- Produces: `doExecute` 返回的 Map 新增 2 个 key：`findings`（`List<Finding>`）、`findingsText`（`String`）

> **关键约束**：改造后 `getLowStockWarnings` 的调用次数**必须仍是 1 次**。原实现在 Tool 里直接调用它取 `size()`；改造后该调用移入 `LowStockFindingProvider`，Tool 改从 `countsByCode` 取 `lowStockCount`。若两处都调用就变成 2 次查询——UT-MSS-05 会抓住这个。

- [ ] **Step 1: 写失败的测试**

Create `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/material/MaterialStockSummaryToolTest.java`:

```java
package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit tests for {@link MaterialStockSummaryTool} 的 Finding 接入。 */
@ExtendWith(MockitoExtension.class)
class MaterialStockSummaryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private MaterialStockSummaryTool tool;

    @Mock
    private MaterialBatchService materialBatchService;

    @Mock
    private FindingService findingService;

    @Mock
    private FindingTextRenderer findingTextRenderer;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        PageResponse<MaterialBatchDTO> page = mock(PageResponse.class);
        lenient().when(page.getContent()).thenReturn(List.of());
        lenient().when(page.getTotalElements()).thenReturn(42L);
        lenient().when(materialBatchService.getMaterialBatchList(anyString(), any())).thenReturn(page);
    }

    private static Finding lowStock(String name) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("currentStock", new BigDecimal("12"));
        facts.put("safetyStock", new BigDecimal("50"));
        facts.put("gap", new BigDecimal("38"));
        facts.put("unit", "kg");
        facts.put("stockRatio", 24L);
        return new Finding("LOW_STOCK", "inventory", Finding.Severity.WARNING, 50,
                "M-" + name, name, facts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute() throws Exception {
        Method m = MaterialStockSummaryTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(tool, FACTORY_ID, Map.of(), Map.of());
    }

    @Test
    @DisplayName("UT-MSS-01: 保留既有 key —— totalBatches / lowStockCount / batches / message")
    void keepsExistingKeys() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(), List.of("低库存"), 0, Map.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("✅ 已检查 低库存，均正常。");

        Map<String, Object> result = execute();

        assertEquals(42L, ((Number) result.get("totalBatches")).longValue());
        assertNotNull(result.get("lowStockCount"));
        assertNotNull(result.get("batches"));
        assertNotNull(result.get("message"));
    }

    @Test
    @DisplayName("UT-MSS-02: 新增 findings / findingsText 两个 key")
    @SuppressWarnings("unchecked")
    void addsFindingKeys() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(lowStock("鲈鱼")), List.of("低库存"), 1,
                        Map.of("LOW_STOCK", 1)));
        when(findingTextRenderer.renderInline(any())).thenReturn("⚠️ 顺带 1 件事：\n · 鲈鱼 ...");

        Map<String, Object> result = execute();

        List<Finding> findings = (List<Finding>) result.get("findings");
        assertEquals(1, findings.size());
        assertEquals("鲈鱼", findings.get(0).subjectName());
        assertTrue(((String) result.get("findingsText")).contains("鲈鱼"));
    }

    @Test
    @DisplayName("UT-MSS-03: lowStockCount 取自 countsByCode 的截断前计数")
    void lowStockCountComesFromCountsByCode() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(lowStock("A"), lowStock("B")),
                        List.of("低库存"), 7, Map.of("LOW_STOCK", 7)));
        when(findingTextRenderer.renderInline(any())).thenReturn("...");

        Map<String, Object> result = execute();

        assertEquals(7, ((Number) result.get("lowStockCount")).intValue(),
                "应取截断前的 7，不是 findings 列表长度 2");
    }

    @Test
    @DisplayName("UT-MSS-04: countsByCode 无 LOW_STOCK 时 lowStockCount 为 0")
    void lowStockCountDefaultsToZero() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(), List.of("低库存"), 0, Map.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("✅ 已检查 低库存，均正常。");

        Map<String, Object> result = execute();

        assertEquals(0, ((Number) result.get("lowStockCount")).intValue());
    }

    @Test
    @DisplayName("UT-MSS-05: 🔴 Tool 不得自己再调 getLowStockWarnings —— 否则变成 2 次查询")
    void doesNotQueryLowStockWarningsDirectly() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(), List.of("低库存"), 0, Map.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("...");

        execute();

        verify(materialBatchService, never()).getLowStockWarnings(anyString());
    }

    @Test
    @DisplayName("UT-MSS-06: message 里带上 findingsText")
    void messageIncludesFindingsText() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(lowStock("鲈鱼")), List.of("低库存"), 1,
                        Map.of("LOW_STOCK", 1)));
        when(findingTextRenderer.renderInline(any())).thenReturn("⚠️ 顺带 1 件事：\n · 鲈鱼 剩 12kg");

        String message = (String) execute().get("message");

        assertTrue(message.contains("库存汇总"), message);
        assertTrue(message.contains("鲈鱼"), message);
    }

    @Test
    @DisplayName("UT-MSS-07: findingsText 为空串时不往 message 里拼空行")
    void emptyFindingsTextDoesNotPolluteMessage() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(), List.of(), 0, Map.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("");

        String message = (String) execute().get("message");

        assertFalse(message.endsWith("\n"), "空 findingsText 不应留下尾随换行: [" + message + "]");
    }

    @Test
    @DisplayName("UT-MSS-08: 用 inventory 这个 domain 调用发现层")
    void usesInventoryDomain() throws Exception {
        when(findingService.detectInline(anyString(), anyString())).thenReturn(
                new FindingService.Result(List.of(), List.of("低库存"), 0, Map.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("...");

        execute();

        verify(findingService).detectInline(FACTORY_ID, "inventory");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=MaterialStockSummaryToolTest test`
Expected: 编译失败或多个测试 FAIL（`findings` key 不存在、`getLowStockWarnings` 仍被调用）

- [ ] **Step 3: 改 `MaterialStockSummaryTool`**

在 import 区加入：

```java
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
```

在 `@Autowired private MaterialBatchService materialBatchService;` 之后加入（保持既有的字段注入风格）：

```java
    @Autowired
    private FindingService findingService;

    @Autowired
    private FindingTextRenderer findingTextRenderer;
```

把 `doExecute` 整个方法体替换为：

```java
    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("查询库存汇总: factoryId={}", factoryId);

        PageResponse<MaterialBatchDTO> batchPage = materialBatchService.getMaterialBatchList(
                factoryId, PageRequest.of(1, 100));
        List<MaterialBatchDTO> batches = batchPage.getContent();
        long total = batchPage.getTotalElements();

        // 低库存不再由本 Tool 直接查询 —— 统一走发现层，避免同一口径在两处各写一遍
        // (见 ListSummaryServiceImpl.java:43-50 的 footer/KPI 口径漂移事故)。
        FindingService.Result findingResult = findingService.detectInline(factoryId, "inventory");
        int lowStock = findingResult.countsByCode().getOrDefault("LOW_STOCK", 0);

        Map<String, Object> result = new HashMap<>();
        result.put("totalBatches", total);
        result.put("lowStockCount", lowStock);
        result.put("batches", batches.size() > 20 ? batches.subList(0, 20) : batches);
        result.put("findings", findingResult.findings());

        String findingsText = findingTextRenderer.renderInline(findingResult);
        result.put("findingsText", findingsText);

        StringBuilder sb = new StringBuilder();
        sb.append("库存汇总：");
        sb.append("总批次数: ").append(total);
        sb.append("，低库存预警: ").append(lowStock).append("个");
        if (!findingsText.isEmpty()) {
            sb.append("\n\n").append(findingsText);
        }

        result.put("message", sb.toString());

        return result;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=MaterialStockSummaryToolTest test`
Expected: PASS，8 个测试全绿

- [ ] **Step 5: 跑全部 Finding 相关测试，确认没打破邻居**

Run: `cd backend/java/cretas-api && mvn -q -Dtest='Finding*Test,LowStockFindingProviderTest,MaterialStockSummaryToolTest,StockAlertWorkdeskToolTest' test`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(material): surface top low-stock findings inline in stock summary" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/material/MaterialStockSummaryTool.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/material/MaterialStockSummaryToolTest.java
```

---

### Task 7: 变异验证（证明这些测试真的会响）

**Files:** 临时改动，全部回退，不产生 commit

**Interfaces:**
- Consumes: Task 1–6 的全部产物
- Produces: 无（验证动作）

> **为什么必须做**：断言写了不等于断言能抓错。以下 3 个变异各自针对一条**最容易在未来被顺手改坏**的不变量。每个变异都必须**变红**；若某个变异全绿，说明对应的测试是哑的，必须补强测试而不是跳过。

- [ ] **Step 1: 变异 A —— 失败的规则重新混进 checkedRules**

在 `FindingServiceImpl#detectInline` 的 catch 块里加一行 `checked.add(provider.ruleName());`

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingServiceImplTest test`
Expected: **FAIL**，UT-FSI-05 报错，信息含「炸掉的规则若留在 checkedRules 里」

- [ ] **Step 2: 回退变异 A**

```bash
git checkout -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/impl/FindingServiceImpl.java
```

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingServiceImplTest test`
Expected: PASS（确认已回到干净状态）

- [ ] **Step 3: 变异 B —— `checkedRules` 为空时也渲染「均正常」**

把 `FindingTextRenderer#renderInline` 开头的 `if (result.checkedRules().isEmpty()) { return ""; }` 整段删掉

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingTextRendererTest test`
Expected: **FAIL**，UT-FTR-02 报错，信息含「全部规则失败时若仍输出「均正常」」

- [ ] **Step 4: 回退变异 B**

```bash
git checkout -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/FindingTextRenderer.java
```

Run: `cd backend/java/cretas-api && mvn -q -Dtest=FindingTextRendererTest test`
Expected: PASS

- [ ] **Step 5: 变异 C —— `lowStockCount` 退回用截断后的列表长度**

把 `MaterialStockSummaryTool#doExecute` 里的
`int lowStock = findingResult.countsByCode().getOrDefault("LOW_STOCK", 0);`
改成
`int lowStock = findingResult.findings().size();`

Run: `cd backend/java/cretas-api && mvn -q -Dtest=MaterialStockSummaryToolTest test`
Expected: **FAIL**，UT-MSS-03 报错「应取截断前的 7，不是 findings 列表长度 2」

- [ ] **Step 6: 回退变异 C 并确认全绿**

```bash
git checkout -- backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/material/MaterialStockSummaryTool.java
```

Run: `cd backend/java/cretas-api && mvn -q -Dtest='FindingTest,FindingServiceImplTest,FindingTextRendererTest,LowStockFindingProviderTest,MaterialStockSummaryToolTest' test`
Expected: BUILD SUCCESS，30 个测试全绿（3+6+7+6+8）

- [ ] **Step 7: 确认工作区干净（没留下变异残渣）**

Run: `git status --short`
Expected: 无输出

---

## 完工检查

- [ ] `git log --oneline origin/main..HEAD` 显示 6 个 commit（Task 1–6）
- [ ] `git diff origin/main...HEAD --stat` 只含本计划列出的 10 个文件，无 sister session 的文件
- [ ] 全量编译通过：`cd backend/java/cretas-api && mvn -q -DskipTests package`

## 刻意不做（scope 边界）

| 不做的事 | 为什么 |
|---|---|
| 临期 / 呆滞 / FIFO / 已过期 provider | 第一刀只验证形状。加规则是复制 `LowStockFindingProvider` 的机械活。 |
| 已过期检测 | 它是**数据正确性问题**（过期批次挂在可用库存会让 BOM 算料算错），该走修数据/告警，不该来抢顺带提示这 2 个位置。 |
| 挂到 `ListSummaryServiceImpl` 的 10 个实体 footer | 等 Finding 形状被一个真实出口验证过再铺开。 |
| 定时日报出口 / 告警出口 | `renderDigest` / 送达去重升级都是独立工程。 |
| 接 LLM 润色话术 | 模板不会编数字。等形态跑顺再说。 |
| `@ConfigurationProperties` 阈值中心 | v1 只有 `inline-max` 一个配置项；低库存阈值本来就是 per-material 的 `RawMaterialType.minStock`。等临期的「7 天」进来才需要。 |
| 修 `MaterialLowStockAlertTool:126` 的 `preferredSupplier` 死代码 | 已知缺陷，但不在本次 scope。单独开 issue。 |
| RN 侧展示 | 若要在仓管/质检的 RN 屏幕上露出，**必须先过 `ux-flow` skill 的 UX Flow Gate**（CLAUDE.md 硬性要求）。本计划只做后端返回结构。 |

## Self-Review

**1. Spec 覆盖**

| 讨论中定下的要求 | 落在哪 |
|---|---|
| 规则住在可复用的层，不住在 Tool 里 | Task 2–4 |
| severity 由 provider 自己产出 | Task 1 `Finding.severity` + Task 3 `toSeverity` |
| actionability 与 severity 正交，先建字段后调权重 | Task 1（含 javadoc 说明 v1 不调） |
| Finding 只装数据不装话术 | Task 1 javadoc + Task 5 渲染分离 |
| renderer 按出口命名（`renderInline`） | Task 5 |
| 口径单一来源，不新写 SQL | Global Constraints + Task 3 javadoc |
| 「均正常」只能列实际跑过的规则 | Task 4 UT-FSI-05 + Task 5 UT-FTR-01/02 |
| 不新增查询 | Task 6 UT-MSS-05 |
| 话术不得引用不存在的供应商 | Task 3 UT-LSF-06 + Task 5 UT-FTR-04 |
| 2 条封顶 + 「还有 N 项」 | Task 4 UT-FSI-03 + Task 5 UT-FTR-05 |
| 阈值 `inline-max` 可配 | Task 4 `@Value("${cretas.finding.inline-max:2}")` |
| 零 LLM | Global Constraints + Task 5 |

**2. 占位符扫描**：无 TODO / TBD / 「similar to Task N」；每个代码步骤都是完整可粘贴的代码。

**3. 类型一致性**：`Finding` 构造参数顺序在 Task 1 定义，Task 3/4/5/6 的测试与实现全部按 `(code, domain, severity, actionability, subjectId, subjectName, facts)` 使用；`FindingService.Result` 的 4 个字段 `(findings, checkedRules, totalCount, countsByCode)` 在 Task 2 定义，Task 4/5/6 一致；`rankScore()` / `weight()` / `detectInline()` / `renderInline()` / `domain()` / `ruleName()` / `detect()` 命名全程未变。
