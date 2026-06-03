# 意图分类器 W1b — 否定否决 + 读写孪生排序 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让"不用查库存了/别给我看订单"这类否定查询不再误路由到写意图(尤其破坏性写 `INVENTORY_CLEAR`),并让读措辞查询不再把写孪生排在前面造成多余确认弹窗。

**Architecture:** 新增无状态策略服务 `NegationTwinPolicy`(单一事实源孪生表 + 纯决策函数,复用 W0 `WriteGuardService`)。预处理层细分否定为 `NONE/EXCLUDE_CONTENT/VETO_WRITE/VETO_READ`。`IntentRecognitionPipelineServiceImpl` 加**早期 VETO 前置门**(在三个早退写短路之前:`VETO_READ` 立即澄清、`VETO_WRITE` 设标志守卫短路),下游单/多意图出口接策略。改动 fail-open + W0 护栏执行层兜底。

**Tech Stack:** Java 21 + Spring Boot 3.2 + JUnit 5 + Mockito + Maven。

**Spec:** `docs/superpowers/specs/2026-06-03-intent-negation-veto-twin-rerank-design.md`(de775f424)。
**Worktree/branch:** `C:\Users\Steve\cretas-negation-twin` / `feat/intent-negation-twin`(off origin/main 42d776740)。
**构建/测试命令(在 `backend/java/cretas-api`):**
- 单测单类:`mvn -q -Dtest=NegationTwinPolicyTest test`
- 编译:`mvn -q -DskipTests compile`

---

## File Structure

| 文件 | 责任 | 操作 |
|---|---|---|
| `ai/tool/NegationTwinPolicy.java` | 孪生表单一事实源 + 否定否决/孪生排序纯函数 | **Create** |
| `ai/tool/NegationTwinPolicyTest.java` | T1 单测 | **Create** |
| `service/QueryPreprocessorService.java` | 接口 `NegationInfo` 加 `kind` + 新 `enum NegationKind` | Modify(:330-345) |
| `service/impl/QueryPreprocessorServiceImpl.java` | 内部 `NegationInfo` 加 `kind` + `VETO_PATTERN` + `detectNegationVeto` + 双重否定守卫 + 转换接 `kind` | Modify(:102/:803/:1503/:1869) |
| `service/impl/QueryPreprocessorNegationVetoTest.java` | T2 单测 | **Create** |
| `service/intent/impl/IntentRecognitionPipelineServiceImpl.java` | 早期 VETO 门(~456 前)+ 三短路守卫 + 单意图出口接策略 + 多意图接否定+策略 + convertNegationIntent 委托 | Modify |
| `service/impl/SemanticRouterServiceImpl.java` | `READ_WRITE_TWIN_PAIRS` 委托 `NegationTwinPolicy`(消重) | Modify(:113) |

---

## 契约(先锁定,T1/T2 共用)

```java
// QueryPreprocessorService.java 内新增顶层(接口内)枚举
enum NegationKind { NONE, EXCLUDE_CONTENT, VETO_WRITE, VETO_READ }
```
- 接口 `NegationInfo`(QPS:334)加字段 `@lombok.Builder.Default private NegationKind kind = NegationKind.NONE;`,Lombok 自动生成 `getKind()`。
- 内部 `NegationInfo`(QPSImpl:1869)加 `private final NegationKind kind;`,保留 3-arg ctor(默认 `NONE`)+ 加 4-arg ctor + `getKind()`。
- `NegationTwinPolicy.applyNegationVetoAndTwinRerank(List<CandidateIntent>, NegationInfo, IntentKnowledgeBase.ActionType, Function<String,AIIntentConfig>)`。
- **读 = `IntentKnowledgeBase.ActionType.QUERY`**(无 READ 值)。`CandidateIntent` 无 `.config`,用 configResolver 还原。

---

## Task 1: `NegationTwinPolicy` 服务 + 单测(新文件,可与 T2 并行)

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/NegationTwinPolicy.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/NegationTwinPolicyTest.java`
- 依赖契约:本 task 先在 `QueryPreprocessorService.java` 加 `enum NegationKind`(仅枚举,不动 NegationInfo —— NegationInfo 字段由 T2 加;T1 用枚举即可)。

- [ ] **Step 1: 加 `NegationKind` 枚举(契约)**

在 `QueryPreprocessorService.java` 接口体内(`NegationInfo` 类定义之前,约 :327)加:
```java
    /** 否定细分类型 v-W1b */
    enum NegationKind { NONE, EXCLUDE_CONTENT, VETO_WRITE, VETO_READ }
```

- [ ] **Step 2: 写失败测试 `NegationTwinPolicyTest`**

```java
package com.cretas.aims.ai.tool;

import com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.QueryPreprocessorService.NegationInfo;
import com.cretas.aims.service.QueryPreprocessorService.NegationKind;
import com.cretas.aims.service.intent.IntentKnowledgeBase.ActionType;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.assertThat;

class NegationTwinPolicyTest {

    private final NegationTwinPolicy policy = new NegationTwinPolicy(new WriteGuardService());

    private CandidateIntent c(String code, double conf) {
        return CandidateIntent.builder().intentCode(code).confidence(conf).build();
    }
    private AIIntentConfig cfg(String code, String sens) {
        AIIntentConfig a = new AIIntentConfig();
        a.setIntentCode(code); a.setSensitivityLevel(sens);
        return a;
    }
    /** resolver: known codes → config w/ sensitivity; unknown → null */
    private Function<String, AIIntentConfig> resolver(Map<String,String> sens) {
        return code -> sens.containsKey(code) ? cfg(code, sens.get(code)) : null;
    }

    @Test
    void vetoRead_removesAllCandidates_includingWrite() {
        var in = List.of(c("INVENTORY_CLEAR", 0.75), c("MATERIAL_BATCH_QUERY", 0.72));
        var neg = NegationInfo.builder().kind(NegationKind.VETO_READ).hasNegation(true).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, ActionType.QUERY,
                resolver(Map.of("INVENTORY_CLEAR", "CRITICAL")));
        assertThat(out).isEmpty();                       // 不用查库存了 → 抑制
        assertThat(out).noneMatch(x -> x.getIntentCode().equals("INVENTORY_CLEAR"));
    }

    @Test
    void vetoWrite_convertsToReadTwin() {
        var in = List.of(c("PROCESSING_BATCH_START", 0.85));
        var neg = NegationInfo.builder().kind(NegationKind.VETO_WRITE).hasNegation(true).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, ActionType.UPDATE,
                resolver(Map.of()));
        assertThat(out).extracting(CandidateIntent::getIntentCode)
                .containsExactly("PROCESSING_BATCH_LIST");   // 别开始生产 → 读孪生
    }

    @Test
    void vetoWrite_dropsWriteWithoutTwin_safetyInvariant() {
        var in = List.of(c("SOME_WRITE_DELETE", 0.9));   // _DELETE suffix → write, no twin
        var neg = NegationInfo.builder().kind(NegationKind.VETO_WRITE).hasNegation(true).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, ActionType.DELETE, resolver(Map.of()));
        assertThat(out).isEmpty();                        // 无孪生 → 剔除,绝不留写
    }

    @Test
    void component2_promotesReadOverWriteTwin_withinMargin() {
        var in = List.of(c("PROCESSING_BATCH_COMPLETE", 0.76), c("REPORT_PRODUCTION", 0.74));
        var neg = NegationInfo.builder().kind(NegationKind.NONE).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, ActionType.QUERY, resolver(Map.of()));
        assertThat(out.get(0).getIntentCode()).isEqualTo("REPORT_PRODUCTION");  // 生产进度怎么样 偏读
    }

    @Test
    void component2_doesNotFire_whenWriteVerb() {
        var in = List.of(c("PROCESSING_BATCH_COMPLETE", 0.9), c("REPORT_PRODUCTION", 0.85));
        var neg = NegationInfo.builder().kind(NegationKind.NONE).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, ActionType.UPDATE, resolver(Map.of()));
        assertThat(out.get(0).getIntentCode()).isEqualTo("PROCESSING_BATCH_COMPLETE"); // 完成生产 保持写
    }

    @Test
    void kindNone_readPhrased_noWriteTop_unchanged() {
        var in = List.of(c("MATERIAL_BATCH_QUERY", 0.9), c("MATERIAL_BATCH_CREATE", 0.5));
        var neg = NegationInfo.builder().kind(NegationKind.NONE).build();
        var out = policy.applyNegationVetoAndTwinRerank(in, neg, ActionType.QUERY, resolver(Map.of()));
        assertThat(out.get(0).getIntentCode()).isEqualTo("MATERIAL_BATCH_QUERY"); // top 已是读 → 不动
    }

    @Test
    void readTwinOf_knownAndUnknown() {
        assertThat(policy.readTwinOf("INVENTORY_CLEAR")).isEqualTo("INVENTORY_QUERY");
        assertThat(policy.readTwinOf("SHIPMENT_CREATE")).isEqualTo("SHIPMENT_QUERY");
        assertThat(policy.readTwinOf("NOT_A_WRITE")).isNull();
    }

    @Test
    void isVetoToClarification_trueOnlyWhenVetoEmptiedNonEmpty() {
        var negRead = NegationInfo.builder().kind(NegationKind.VETO_READ).build();
        assertThat(policy.isVetoToClarification(List.of(c("X",0.5)), List.of(), negRead)).isTrue();
        var negNone = NegationInfo.builder().kind(NegationKind.NONE).build();
        assertThat(policy.isVetoToClarification(List.of(c("X",0.5)), List.of(), negNone)).isFalse();
    }

    @Test
    void nullOrEmptyCandidates_returnedAsIs() {
        assertThat(policy.applyNegationVetoAndTwinRerank(List.of(), null, ActionType.QUERY, c -> null)).isEmpty();
    }
}
```

- [ ] **Step 3: 运行测试,确认失败**

Run: `mvn -q -Dtest=NegationTwinPolicyTest test`
Expected: 编译失败(`NegationTwinPolicy` 不存在 / `NegationKind` 未加)。

- [ ] **Step 4: 写 `NegationTwinPolicy` 实现**

```java
package com.cretas.aims.ai.tool;

import com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.QueryPreprocessorService.NegationInfo;
import com.cretas.aims.service.QueryPreprocessorService.NegationKind;
import com.cretas.aims.service.intent.IntentKnowledgeBase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * W1b: negation veto + read/write twin rerank policy.
 * Stateless + thread-safe (callable from worker threads); MUST NOT read ThreadLocal/SecurityContext.
 * Single source of truth for the write->read twin map (was duplicated in
 * IntentRecognitionPipelineServiceImpl.convertNegationIntent + SemanticRouterServiceImpl.READ_WRITE_TWIN_PAIRS).
 */
@Service
public class NegationTwinPolicy {

    private final WriteGuardService writeGuard;

    public NegationTwinPolicy(WriteGuardService writeGuard) {
        this.writeGuard = writeGuard;
    }

    /** Component-2 rerank margin. Read twin within this score gap of a write top → promote read. */
    static final double TWIN_RERANK_MARGIN = 0.10;

    /** canonical write -> read twin. Verified codes only (see spec §5.1 finding 1). */
    private static final Map<String, String> WRITE_TO_READ_TWIN = Map.ofEntries(
            Map.entry("PROCESSING_BATCH_COMPLETE", "PROCESSING_BATCH_LIST"),
            Map.entry("PROCESSING_BATCH_START", "PROCESSING_BATCH_LIST"),
            Map.entry("PROCESSING_BATCH_PAUSE", "PROCESSING_BATCH_LIST"),
            Map.entry("PROCESSING_BATCH_CREATE", "PROCESSING_BATCH_LIST"),
            Map.entry("ALERT_ACKNOWLEDGE", "ALERT_LIST"),
            Map.entry("ALERT_CREATE", "ALERT_LIST"),
            Map.entry("EQUIPMENT_STOP", "EQUIPMENT_STATUS"),
            Map.entry("EQUIPMENT_START", "EQUIPMENT_STATUS"),
            Map.entry("EQUIPMENT_CONTROL", "EQUIPMENT_STATUS"),
            Map.entry("EQUIPMENT_STATUS_UPDATE", "EQUIPMENT_STATUS"),
            Map.entry("SHIPMENT_STATUS_UPDATE", "SHIPMENT_QUERY"),
            Map.entry("SHIPMENT_CREATE", "SHIPMENT_QUERY"),
            Map.entry("SHIPMENT_UPDATE", "SHIPMENT_QUERY"),
            Map.entry("MATERIAL_BATCH_CREATE", "MATERIAL_BATCH_QUERY"),
            Map.entry("MATERIAL_BATCH_CONSUME", "MATERIAL_BATCH_QUERY"),
            Map.entry("MATERIAL_EXPIRED_QUERY", "MATERIAL_BATCH_QUERY"),
            Map.entry("QUALITY_CHECK_EXECUTE", "QUALITY_CHECK_QUERY"),
            Map.entry("QUALITY_DISPOSITION_EXECUTE", "QUALITY_CHECK_QUERY"),
            Map.entry("CLOCK_IN", "ATTENDANCE_QUERY"),
            Map.entry("CLOCK_OUT", "ATTENDANCE_QUERY"),
            Map.entry("ATTENDANCE_RECORD", "ATTENDANCE_QUERY"),
            Map.entry("SUPPLIER_EVALUATE", "SUPPLIER_QUERY"),
            Map.entry("SCALE_ADD_DEVICE", "MATERIAL_BATCH_QUERY"),
            // W1b additions (verified-exist; INVENTORY_SUMMARY_QUERY is NOT config-backed → use INVENTORY_QUERY)
            Map.entry("INVENTORY_CLEAR", "INVENTORY_QUERY"),
            Map.entry("ORDER_DELETE", "ORDER_LIST"),
            Map.entry("ORDER_CANCEL", "ORDER_LIST"));

    public String readTwinOf(String writeIntentCode) {
        return writeIntentCode == null ? null : WRITE_TO_READ_TWIN.get(writeIntentCode);
    }

    /**
     * Unified negation-veto + twin-rerank decision. Does not mutate the input list.
     * VETO_READ  → drop all candidates (user negated the query); caller returns clarification.
     * VETO_WRITE → each write candidate → its read twin (or dropped if no twin).
     * NONE/EXCLUDE_CONTENT → component-2 twin rerank, only for read-phrased queries (QUERY).
     * Safety invariant: after a VETO_*, the result contains NO write intent.
     */
    public List<CandidateIntent> applyNegationVetoAndTwinRerank(
            List<CandidateIntent> candidates,
            NegationInfo negation,
            IntentKnowledgeBase.ActionType queryActionType,
            Function<String, AIIntentConfig> configResolver) {

        if (candidates == null || candidates.isEmpty()) return candidates;
        NegationKind kind = (negation == null || negation.getKind() == null)
                ? NegationKind.NONE : negation.getKind();
        List<CandidateIntent> result = new ArrayList<>(candidates);

        if (kind == NegationKind.VETO_READ) {
            result.clear();
        } else if (kind == NegationKind.VETO_WRITE) {
            List<CandidateIntent> converted = new ArrayList<>();
            for (CandidateIntent c : result) {
                if (isWrite(c, configResolver)) {
                    String twin = readTwinOf(c.getIntentCode());
                    if (twin != null) converted.add(retarget(c, twin));
                    // else: drop the write (no read twin)
                } else {
                    converted.add(c);
                }
            }
            result = converted;
        } else {
            // NONE / EXCLUDE_CONTENT → component-2 rerank only for read-phrased queries
            if (queryActionType == IntentKnowledgeBase.ActionType.QUERY) {
                result = twinRerank(result, configResolver);
            }
            return result;  // no veto safety filter on non-veto paths
        }

        // Safety invariant (铁律): VETO_* must never emit a write candidate.
        result.removeIf(c -> isWrite(c, configResolver));
        return result;
    }

    /** True if a VETO_* emptied a previously-non-empty list → caller should clarify, not execute. */
    public boolean isVetoToClarification(List<CandidateIntent> original,
                                         List<CandidateIntent> afterPolicy,
                                         NegationInfo negation) {
        if (negation == null || negation.getKind() == null) return false;
        boolean vetoFired = negation.getKind() == NegationKind.VETO_READ
                || negation.getKind() == NegationKind.VETO_WRITE;
        return vetoFired
                && (afterPolicy == null || afterPolicy.isEmpty())
                && original != null && !original.isEmpty();
    }

    private boolean isWrite(CandidateIntent c, Function<String, AIIntentConfig> resolver) {
        if (c == null || c.getIntentCode() == null) return false;
        AIIntentConfig cfg = resolver == null ? null : resolver.apply(c.getIntentCode());
        // isWriteIntent(null)==false → fall back to name-suffix (catches suffix-based writes when cfg unresolved)
        return writeGuard.isWriteIntent(cfg) || writeGuard.hasWriteSuffix(c.getIntentCode());
    }

    /** read-phrased query whose top is a write with a comparable read present → promote the read. */
    private List<CandidateIntent> twinRerank(List<CandidateIntent> result,
                                             Function<String, AIIntentConfig> resolver) {
        if (result.size() < 2) return result;
        CandidateIntent top = result.get(0);
        if (!isWrite(top, resolver)) return result;
        double topScore = top.getConfidence() == null ? 0.0 : top.getConfidence();
        for (int i = 1; i < result.size(); i++) {
            CandidateIntent r = result.get(i);
            double rScore = r.getConfidence() == null ? 0.0 : r.getConfidence();
            if (!isWrite(r, resolver) && (topScore - rScore) <= TWIN_RERANK_MARGIN) {
                List<CandidateIntent> reordered = new ArrayList<>();
                reordered.add(r);
                for (CandidateIntent c : result) if (c != r) reordered.add(c);
                return reordered;
            }
        }
        return result;
    }

    private CandidateIntent retarget(CandidateIntent from, String newCode) {
        return CandidateIntent.builder()
                .intentCode(newCode)
                .confidence(from.getConfidence())
                .matchScore(from.getMatchScore())
                .matchMethod(from.getMatchMethod())
                .matchedKeywords(from.getMatchedKeywords())
                .build();
    }
}
```

- [ ] **Step 5: 运行测试,确认通过**

Run: `mvn -q -Dtest=NegationTwinPolicyTest test`
Expected: PASS(9 tests)。若 `AIIntentConfig` 无 `setSensitivityLevel`/`setIntentCode` setter → 改用其真实构造(读 `entity/config/AIIntentConfig.java` 确认 Lombok @Data)。

- [ ] **Step 6: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/NegationTwinPolicy.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/NegationTwinPolicyTest.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/service/QueryPreprocessorService.java
git commit -m "feat(intent): NegationTwinPolicy service + canonical twin map (W1b T1)" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/NegationTwinPolicy.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/NegationTwinPolicyTest.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/QueryPreprocessorService.java
```

---

## Task 2: 否定检测扩展(QueryPreprocessor,可与 T1 并行)

**Files:**
- Modify: `service/QueryPreprocessorService.java:330-345`(接口 `NegationInfo` 加 `kind`)
- Modify: `service/impl/QueryPreprocessorServiceImpl.java`(:1869 内部 `NegationInfo` 加 `kind` + ctor;:102 后加 `VETO_PATTERN`;新增 `detectNegationVeto`;:803 `detectNegationSemantics` 接 veto;:1503 转换接 `kind`)
- Test: `service/impl/QueryPreprocessorNegationVetoTest.java`

- [ ] **Step 1: 接口 `NegationInfo` 加 `kind` 字段**

`QueryPreprocessorService.java` :334 类体内,`excludedContent` 之后加:
```java
        /** 否定细分类型 v-W1b(默认 NONE) */
        @lombok.Builder.Default
        private NegationKind kind = NegationKind.NONE;
```
> `@Data` 自动生成 `getKind()`。`@Builder.Default` 保证 builder 不传时为 `NONE`。`@AllArgsConstructor` 变 4-arg,但唯一构造点(QPSImpl:1506)用 builder,不受影响。

- [ ] **Step 2: 内部 `NegationInfo` 加 `kind` + 保留旧 ctor + 加 4-arg ctor**

`QueryPreprocessorServiceImpl.java` :1869 类:
```java
    public static class NegationInfo {
        private final boolean hasNegation;
        private final String negationWord;
        private final String excludedContent;
        private final QueryPreprocessorService.NegationKind kind;   // W1b

        /** 旧 3-arg ctor 保留,kind 默认 NONE(向后兼容 805/812/814 三处 call site) */
        public NegationInfo(boolean hasNegation, String negationWord, String excludedContent) {
            this(hasNegation, negationWord, excludedContent, QueryPreprocessorService.NegationKind.NONE);
        }

        /** W1b 4-arg ctor */
        public NegationInfo(boolean hasNegation, String negationWord, String excludedContent,
                            QueryPreprocessorService.NegationKind kind) {
            this.hasNegation = hasNegation;
            this.negationWord = negationWord;
            this.excludedContent = excludedContent;
            this.kind = kind == null ? QueryPreprocessorService.NegationKind.NONE : kind;
        }

        public boolean hasNegation() { return hasNegation; }
        public String getNegationWord() { return negationWord; }
        public String getExcludedContent() { return excludedContent; }
        public QueryPreprocessorService.NegationKind getKind() { return kind; }  // W1b

        @Override
        public String toString() {
            return hasNegation ?
                String.format("NegationInfo[word=%s, excluded=%s, kind=%s]", negationWord, excludedContent, kind) :
                "NegationInfo[none, kind=" + kind + "]";
        }
    }
```

- [ ] **Step 3: 加 `VETO_PATTERN` + `detectNegationVeto`(写失败测试先)**

写测试 `QueryPreprocessorNegationVetoTest`:
```java
package com.cretas.aims.service.impl;

import com.cretas.aims.service.QueryPreprocessorService.NegationKind;
import com.cretas.aims.service.intent.IntentKnowledgeBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QueryPreprocessorNegationVetoTest {

    private QueryPreprocessorServiceImpl svc;
    private IntentKnowledgeBase kb;

    @BeforeEach
    void setup() {
        // detectNegationVeto only needs the knowledge base; other ctor deps可传 null/mocked.
        kb = mock(IntentKnowledgeBase.class);
        // remainder verb → ActionType (mirror real detectActionType behavior for the test inputs)
        when(kb.detectActionType(contains("查"))).thenReturn(IntentKnowledgeBase.ActionType.QUERY);
        when(kb.detectActionType(contains("看"))).thenReturn(IntentKnowledgeBase.ActionType.QUERY);
        when(kb.detectActionType(contains("开始"))).thenReturn(IntentKnowledgeBase.ActionType.UPDATE);
        when(kb.detectActionType(contains("创建"))).thenReturn(IntentKnowledgeBase.ActionType.CREATE);
        svc = QueryPreprocessorServiceImpl.forNegationTest(kb);   // tiny test factory, see Step 5
    }

    @Test void vetoRead_buchaKucun() {
        assertThat(svc.detectNegationVeto("不用查库存了")).isEqualTo(NegationKind.VETO_READ);
    }
    @Test void vetoRead_bieGeiWoKan() {
        assertThat(svc.detectNegationVeto("别给我看订单")).isEqualTo(NegationKind.VETO_READ);
    }
    @Test void vetoWrite_bieKaishiShengchan() {
        assertThat(svc.detectNegationVeto("别开始生产了")).isEqualTo(NegationKind.VETO_WRITE);
    }
    @Test void vetoWrite_buyongChuangjian() {
        assertThat(svc.detectNegationVeto("不用创建订单")).isEqualTo(NegationKind.VETO_WRITE);
    }
    @Test void doubleNegative_guard_returnsNone() {
        assertThat(svc.detectNegationVeto("不是不想查库存")).isEqualTo(NegationKind.NONE);  // finding 6
        assertThat(svc.detectNegationVeto("不能不查质检")).isEqualTo(NegationKind.NONE);
    }
    @Test void legitWrite_noLeadingNegation_none() {
        assertThat(svc.detectNegationVeto("取消订单")).isEqualTo(NegationKind.NONE);   // 取消 不是否定副词
        assertThat(svc.detectNegationVeto("作废这张单")).isEqualTo(NegationKind.NONE);
    }
    @Test void plainQuery_none() {
        assertThat(svc.detectNegationVeto("查库存")).isEqualTo(NegationKind.NONE);
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

Run: `mvn -q -Dtest=QueryPreprocessorNegationVetoTest test`
Expected: 编译失败(`detectNegationVeto` / `forNegationTest` 不存在)。

- [ ] **Step 5: 实现 `VETO_PATTERN` + `detectNegationVeto` + 测试工厂**

`QueryPreprocessorServiceImpl.java` :104(`NEGATION_PATTERN` 之后)加:
```java
    /** W1b: 否定否决副词(语句级否定,非内容排除)。锚定句首/近句首以避免误吞合法写。 */
    private static final java.util.regex.Pattern VETO_ADVERB_PATTERN = java.util.regex.Pattern.compile(
            "^\\s*(我?|那|这个?|现在|先|那么)?\\s*(别|不用|不要|甭|无需|不必|先不|暂时不|不想|不需要)");
    /** "不用的"(unused)误触排除:不用/不想 紧跟 的/了的 名词时不是否决 */
    private static final java.util.regex.Pattern VETO_FALSE_FRIEND = java.util.regex.Pattern.compile(
            "(不用|不想|不要)的");
```

加方法(放在 `detectNegationSemantics` 附近,约 :816):
```java
    /**
     * W1b: 细分否定否决类型。语句级否定("别查/不用看")→ VETO_READ;
     * 否定写动词("别开始/不用创建")→ VETO_WRITE。双重否定优先返 NONE(finding 6)。
     */
    public QueryPreprocessorService.NegationKind detectNegationVeto(String input) {
        if (input == null) return QueryPreprocessorService.NegationKind.NONE;
        String s = input.trim();
        if (s.isEmpty()) return QueryPreprocessorService.NegationKind.NONE;
        // 1) 双重否定守卫:不是不想查/不能不查 → 实为肯定查询
        if (DOUBLE_NEGATIVE_PATTERN.matcher(s).find()) return QueryPreprocessorService.NegationKind.NONE;
        // 2) "不用的"(unused)等 false friend
        if (VETO_FALSE_FRIEND.matcher(s).find()) return QueryPreprocessorService.NegationKind.NONE;
        // 3) 否定副词(句首/近句首)
        java.util.regex.Matcher m = VETO_ADVERB_PATTERN.matcher(s);
        if (!m.find()) return QueryPreprocessorService.NegationKind.NONE;
        String remainder = s.substring(m.end()).trim();
        if (remainder.startsWith("给我")) remainder = remainder.substring(2).trim();
        // 4) 否定副词后的动词决定 写 vs 读
        IntentKnowledgeBase.ActionType at =
                knowledgeBase.detectActionType(remainder.isEmpty() ? s : remainder);
        if (at == IntentKnowledgeBase.ActionType.CREATE
                || at == IntentKnowledgeBase.ActionType.UPDATE
                || at == IntentKnowledgeBase.ActionType.DELETE) {
            return QueryPreprocessorService.NegationKind.VETO_WRITE;
        }
        // QUERY / AMBIGUOUS / UNKNOWN → 否定一个读/不明 → 抑制+澄清(安全方向)
        return QueryPreprocessorService.NegationKind.VETO_READ;
    }
```
> `knowledgeBase` 是 QPSImpl 已有依赖吗?**核对**:若 QPSImpl 未注入 `IntentKnowledgeBase`,改为 `detectNegationVeto(String input, IntentKnowledgeBase kb)` 传参(调用方 IRP 持有 kb),并相应改测试。读 QPSImpl ctor(:157)确认。

测试工厂(QPSImpl 末尾加,仅测试用,反射/最小构造):
```java
    /** test-only: build an instance wired just enough for detectNegationVeto. */
    static QueryPreprocessorServiceImpl forNegationTest(IntentKnowledgeBase kb) {
        QueryPreprocessorServiceImpl s = new QueryPreprocessorServiceImpl(
                null, null, null, null, new com.fasterxml.jackson.databind.ObjectMapper(), null, null);
        s.knowledgeBase = kb;   // 若 knowledgeBase 是 @Autowired field;若是 ctor 注入则改 ctor 传参方案
        return s;
    }
```
> ⚠️ 若 `knowledgeBase` 注入方式不允许(final/ctor),用传参方案(上注)替代测试工厂,测试改 `svc.detectNegationVeto(input, kb)`。

- [ ] **Step 6: `detectNegationSemantics` 与转换接入 `kind`**

`detectNegationSemantics`(:803)末尾合并 veto kind —— 把 exclusion 与 veto 统一进返回的 kind:
```java
    public NegationInfo detectNegationSemantics(String input) {
        if (input == null || input.trim().isEmpty()) {
            return new NegationInfo(false, null, null, QueryPreprocessorService.NegationKind.NONE);
        }
        QueryPreprocessorService.NegationKind vetoKind = detectNegationVeto(input);
        Matcher matcher = NEGATION_PATTERN.matcher(input);
        if (matcher.find()) {
            String negationWord = matcher.group(1);
            String excludedContent = extractExcludedContent(input, matcher.end());
            // veto 优先于 exclusion(用户语句级否决比内容过滤更强);否则 EXCLUDE_CONTENT
            QueryPreprocessorService.NegationKind kind =
                    (vetoKind != QueryPreprocessorService.NegationKind.NONE)
                            ? vetoKind : QueryPreprocessorService.NegationKind.EXCLUDE_CONTENT;
            return new NegationInfo(true, negationWord, excludedContent, kind);
        }
        // 无 exclusion 但有 veto(如 "不用查库存了" 不匹配 NEGATION_PATTERN)
        if (vetoKind != QueryPreprocessorService.NegationKind.NONE) {
            return new NegationInfo(true, null, null, vetoKind);
        }
        return new NegationInfo(false, null, null, QueryPreprocessorService.NegationKind.NONE);
    }
```
> 注意:`hasNegation` 对 veto 也置 `true`,但**仅当 `kind==EXCLUDE_CONTENT` 时** exclusion 老逻辑(extractExcludedContent 内容过滤)才生效;veto 路径由新策略处理(下游不读 excludedContent)。核对 :1474(HAS_NEGATION feature)与 :1505 转换不会因 veto 的 hasNegation=true 误触发 exclusion 副作用 —— 若有,gate 成 `kind==EXCLUDE_CONTENT`。

转换(:1503)带上 kind:
```java
        QueryPreprocessorService.NegationInfo interfaceNegationInfo = null;
        if (negationInfo.hasNegation() || negationInfo.getKind() != QueryPreprocessorService.NegationKind.NONE) {
            interfaceNegationInfo = QueryPreprocessorService.NegationInfo.builder()
                    .hasNegation(negationInfo.hasNegation())
                    .negationWord(negationInfo.getNegationWord())
                    .excludedContent(negationInfo.getExcludedContent())
                    .kind(negationInfo.getKind())
                    .build();
        }
```

- [ ] **Step 7: 运行测试确认通过 + 全量编译**

Run: `mvn -q -Dtest=QueryPreprocessorNegationVetoTest test` → PASS(7 tests)
Run: `mvn -q -DskipTests compile` → 编译通过(确认内部 NegationInfo ctor 三处 call site 805/812/814 仍编译 —— 它们用 3-arg ctor,已保留)

- [ ] **Step 8: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/QueryPreprocessorService.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/QueryPreprocessorServiceImpl.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/QueryPreprocessorNegationVetoTest.java
git commit -m "feat(intent): negation veto detection (VETO_READ/WRITE) + double-neg guard (W1b T2)" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/QueryPreprocessorService.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/QueryPreprocessorServiceImpl.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/QueryPreprocessorNegationVetoTest.java
```

---

## Task 3: 早期 VETO 前置门(IRP,finding 7 核心 —— 独立 task 独立 review)

**Files:**
- Modify: `service/intent/impl/IntentRecognitionPipelineServiceImpl.java`(早期短语匹配 ~456 之前插门;三短路守卫 ~456/~592/~1010)
- Test: `service/intent/impl/IntentPipelineEarlyVetoGateTest.java`(或整合到既有 pipeline 测试类)

**前置阅读(实施者必读)**:IRP `recognizeIntentWithConfidence` 主体(~440-660),定位 (a) 预处理产 `enhancedResult`/`negationInfo` 的位置、(b) 早期短语匹配块(~456-482)、(c) verb-noun 短路块(~580-625)、(d) `doRecognize` 内 verb-noun 短路(~1010-1045)、(e) 既有澄清结果构造(`clarificationResult`,~575)的 builder 形态(照抄用于 VETO_READ 澄清)。

- [ ] **Step 1: 写失败测试(early gate 行为)**

`IntentPipelineEarlyVetoGateTest`(用真实 Spring context `@SpringBootTest` 或对 pipeline 方法做窄集成;若启动太重,改为对一个抽出的 `decideEarlyVetoGate(negationInfo)` 纯方法单测):
```java
// 推荐:抽 helper 纯方法便于单测
// IntentMatchResult earlyVetoGateResultOrNull(NegationInfo neg, String userInput, String factoryId)
//   - VETO_READ → 非 null 澄清结果(NEED_MORE_INFO)
//   - VETO_WRITE → null(继续识别,但设 negationVetoWrite 标志)
//   - 其它 → null
@Test void vetoRead_returnsClarification() {
    var neg = ifaceNeg(NegationKind.VETO_READ);
    var r = pipeline.earlyVetoGateResultOrNull(neg, "不用查库存了", "F001");
    assertThat(r).isNotNull();
    assertThat(r.getStatusHint()).contains("NEED_MORE_INFO");  // 按真实字段调整
    assertThat(r.getBestMatch()).isNull();
}
@Test void vetoWrite_returnsNull_butSignalsSuppress() {
    var neg = ifaceNeg(NegationKind.VETO_WRITE);
    assertThat(pipeline.earlyVetoGateResultOrNull(neg, "别开始生产了", "F001")).isNull();
}
@Test void none_returnsNull() {
    assertThat(pipeline.earlyVetoGateResultOrNull(ifaceNeg(NegationKind.NONE), "查库存", "F001")).isNull();
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -Dtest=IntentPipelineEarlyVetoGateTest test` → 编译失败(`earlyVetoGateResultOrNull` 不存在)

- [ ] **Step 3: 实现早期门 helper + 插入主流程 + 三短路守卫**

(a) helper(IRP 私有方法):
```java
    /**
     * W1b finding 7: 早期 VETO 门。必须在所有早退写短路之前调用。
     * @return VETO_READ → 澄清结果(立即返回);否则 null(继续识别)
     */
    private IntentMatchResult earlyVetoGateResultOrNull(
            QueryPreprocessorService.NegationInfo neg, String userInput, String factoryId) {
        if (neg == null || neg.getKind() == null) return null;
        if (neg.getKind() == QueryPreprocessorService.NegationKind.VETO_READ) {
            return IntentMatchResult.builder()
                    .bestMatch(null)
                    .confidence(0.0)
                    .userInput(userInput)
                    .requiresConfirmation(false)
                    // 照抄既有 clarificationResult 的字段/状态(NEED_MORE_INFO + 文案)
                    .clarificationQuestion("您是要取消这次操作吗?需要我帮您查询或处理什么?")
                    .build();
        }
        return null;  // VETO_WRITE 由 negationVetoWrite 标志在短路处处理
    }
```
> ⚠️ 字段名(`clarificationQuestion`/状态枚举)以 `IntentMatchResult` 真实定义为准 —— 照抄 ~575 既有 `clarificationResult` 的构造。

(b) 主流程插入(预处理拿到 `enhancedResult`/`negationInfo` **之后**、早期短语匹配 ~456 **之前**):
```java
        QueryPreprocessorService.NegationInfo negationInfo =
                enhancedResult != null ? enhancedResult.getNegationInfo() : null;
        boolean negationVetoWrite = negationInfo != null
                && negationInfo.getKind() == QueryPreprocessorService.NegationKind.VETO_WRITE;
        IntentMatchResult earlyVeto = earlyVetoGateResultOrNull(negationInfo, userInput, factoryId);
        if (earlyVeto != null) {
            saveIntentMatchRecord(earlyVeto, factoryId, userId, sessionId, false);
            return attachTiming(earlyVeto, startTimeMs, preprocessEndMs);
        }
```

(c) 三个早退写短路块各加 `negationVetoWrite` 守卫(写动词被否定时跳过短路,落到下游 policy 转读孪生):
```java
        // 早期短语匹配 ~456:
        if (!negationVetoWrite && /* 原条件 */ ...) { ... return ...; }
        // verb-noun 短路 ~593:
        if (!negationVetoWrite && !isNegatedVerb && (recAction == ActionType.CREATE || ...)) { ... }
        // doRecognize 内 verb-noun 短路 ~1010:(需把 negationVetoWrite 传进 doRecognize 或读 enhancedResult)
        if (!negationVetoWrite && ...) { ... }
```
> `doRecognize` 内短路(~1010)若拿不到 `negationVetoWrite`:把该标志作为参数传入 `doRecognizeIntentWithConfidence(...)`,或在该方法内从 `enhancedResult.getNegationInfo().getKind()` 重算。二选一,核对签名。

- [ ] **Step 4: 运行确认通过 + 编译**

Run: `mvn -q -Dtest=IntentPipelineEarlyVetoGateTest test` → PASS
Run: `mvn -q -DskipTests compile` → 通过

- [ ] **Step 5: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/IntentPipelineEarlyVetoGateTest.java
git commit -m "feat(intent): early VETO gate before write short-circuits (W1b T3, finding 7)" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/IntentPipelineEarlyVetoGateTest.java
```

---

## Task 4: 下游策略接线 + 委托消重(IRP + SemanticRouter)

**Files:**
- Modify: `IntentRecognitionPipelineServiceImpl.java`(单意图出口 647-655 接 policy;`recognizeMultiIntent` 加否定检测 + policy;`convertNegationIntent` 5433 委托)
- Modify: `service/impl/SemanticRouterServiceImpl.java:113`(`READ_WRITE_TWIN_PAIRS` 委托 `NegationTwinPolicy`)
- Test: 扩 `IntentPipelineEarlyVetoGateTest` 或新 `IntentPipelineNegationWiringTest`

- [ ] **Step 1: 注入 `NegationTwinPolicy`**

IRP + SemanticRouter 加 `@Autowired`/ctor 注入 `NegationTwinPolicy negationTwinPolicy`(IRP 已有 `WriteGuardService`/`configService`/`knowledgeBase`,照样式加)。

- [ ] **Step 2: 写失败测试(单意图 VETO_WRITE 转读孪生 + 组件2 偏读)**

```java
@Test void vetoWrite_singleIntent_convertsToReadTwin() {
    var r = pipeline.recognizeIntentWithConfidence("别开始生产了", "F001", 5, null, null);
    assertThat(r.getBestMatch().getIntentCode()).isEqualTo("PROCESSING_BATCH_LIST");
}
@Test void readPhrased_promotesRead_component2() {
    var r = pipeline.recognizeIntentWithConfidence("生产进度怎么样", "F001", 5, null, null);
    // top 不应是写
    assertThat(writeGuard.hasWriteSuffix(r.getBestMatch().getIntentCode())).isFalse();
}
```
> 这些是窄集成(需 Spring context + 真实 KB/config)。若太重,降级为:用 mock 候选列表直接验 `negationTwinPolicy.applyNegationVetoAndTwinRerank` 在出口被调用(verify 交互)。

- [ ] **Step 3: 单意图出口(647-655)替换为 policy 调用**

把原 `convertNegationIntent` 块替换:
```java
        if (result != null && result.getTopCandidates() != null && !result.getTopCandidates().isEmpty()
                && enhancedResult != null) {
            QueryPreprocessorService.NegationInfo negationInfo = enhancedResult.getNegationInfo();
            IntentKnowledgeBase.ActionType at = knowledgeBase.detectActionType(userInput);
            java.util.function.Function<String, AIIntentConfig> resolver =
                    code -> configService.getIntentConfigByCode(factoryId, code);
            List<IntentMatchResult.CandidateIntent> after =
                    negationTwinPolicy.applyNegationVetoAndTwinRerank(
                            result.getTopCandidates(), negationInfo, at, resolver);
            if (negationTwinPolicy.isVetoToClarification(result.getTopCandidates(), after, negationInfo)) {
                // VETO 剔空 → 澄清(同 early gate 文案);理论上 VETO_READ 已被 early gate 截,
                // 此处兜 VETO_WRITE 无孪生剔空的情况
                IntentMatchResult clar = /* build NEED_MORE_INFO clarification, 同 T3 helper */;
                return attachTiming(clar, startTimeMs, preprocessEndMs);
            }
            if (!after.isEmpty()) {
                AIIntentConfig newBest = configService.getIntentConfigByCode(factoryId, after.get(0).getIntentCode());
                if (newBest != null) {
                    result = result.toBuilder().bestMatch(newBest).topCandidates(after).build();
                }
            }
        }
```

- [ ] **Step 4: 多意图 `recognizeMultiIntent`(664/669)加否定检测 + policy**

`recognizeMultiIntent` 内,拿到 `MultiIntentResult` 后(`classifyMultiLabel` 返回后):
```java
        QueryPreprocessorService.NegationInfo neg =
                queryPreprocessorService.detectNegationSemantics(userInput);  // 多意图原本不做,补上
        if (neg.getKind() == QueryPreprocessorService.NegationKind.VETO_READ) {
            // 抑制 → 单意图澄清
            return MultiIntentResult.builder().isMultiIntent(false)
                    .intents(Collections.emptyList())
                    .executionStrategy(MultiIntentResult.ExecutionStrategy.PARALLEL)
                    .clarification("您是要取消这次操作吗?需要我帮您查询或处理什么?")  // 按真实字段
                    .build();
        }
        // VETO_WRITE / 组件2:把 SingleIntentMatch 列表映射到 code,过策略,再剔/换
        // (SingleIntentMatch 只有 code+confidence,用 configResolver 还原)
        // 用 NegationTwinPolicy 的判定剔写孪生:read-phrased 且存在读替代时剔写
```
> 多意图侧把 `List<SingleIntentMatch>` 适配成 `List<CandidateIntent>`(code+confidence)过 `applyNegationVetoAndTwinRerank`,再映射回。`configResolver` 同单意图。**保守**:`查库存和销售` 这类真复合查询不应被组件2剔(它无 VETO 且多个读+无写孪生冲突)。

- [ ] **Step 5: `convertNegationIntent`(5433)+ SemanticRouter 委托消重**

- `convertNegationIntent(intentCode, true)` 体改为:`String twin = negationTwinPolicy.readTwinOf(intentCode); return twin != null ? twin : intentCode;`(保留方法签名,内部委托;若已无 caller 可删 —— 核对 648/5470 是否已被 Step 3 替换)。
- `SemanticRouterServiceImpl.READ_WRITE_TWIN_PAIRS` + `isTwinPair`:改为问 `negationTwinPolicy.readTwinOf(a)==b || readTwinOf(b)==a`(注入 policy);删本地 Set。

- [ ] **Step 6: 运行测试 + 全量单测**

Run: `mvn -q -Dtest=IntentPipelineNegationWiringTest test` → PASS
Run: `mvn -q test -Dtest='NegationTwinPolicyTest,QueryPreprocessorNegationVetoTest,IntentPipeline*Test,SemanticRouter*Test'` → 全 PASS(确认 SemanticRouter 委托无回归)

- [ ] **Step 7: 提交**

```bash
git add -A backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/SemanticRouterServiceImpl.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/
git status --short      # 并发安全:确认只有预期文件
git commit -m "feat(intent): wire NegationTwinPolicy into single+multi paths, dedupe twin map (W1b T4)" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/SemanticRouterServiceImpl.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/IntentPipelineNegationWiringTest.java
```

---

## Task 5: 回归 golden + live 验证

**Files:** 无源码改动(脚本 + 验证)。

- [ ] **Step 1: 全量编译 + 相关单测绿**

Run: `mvn -q -DskipTests compile` 然后 `mvn -q test -Dtest='*Negation*,*Intent*,*SemanticRouter*'`
Expected: 全绿。任何既有 intent 识别测试回归 → 修。

- [ ] **Step 2: 跑既有意图识别 golden(若存在)**

定位既有 golden/回归测试(`grep -rl "recognizeIntent\|IntentMatchResult" backend/java/cretas-api/src/test`),全跑确认 exclusion / 写动词 / 普通读路径零回归。

- [ ] **Step 3: 部署 test(10011)前置:merge 到 main**

> 按 `worktree-and-main-only-deploy`:PR → `git diff origin/main...HEAD --stat` 确认 scope 干净 → merge main → 从 main 部署。**不从 feature 分支部署**。(本 task 由人工/主流程驱动,见末尾 Handoff。)

- [ ] **Step 4: live 验证脚本(test 10011 真实 F001 认证多意图)**

复跑 spec §1.1 案例,断言:
```
不用查库存了   → status NEED_MORE_INFO/澄清,intents 不含 INVENTORY_CLEAR
别给我看订单   → 澄清
别开始生产了   → PROCESSING_BATCH_LIST(读),不弹写确认
取消订单       → 仍是写(ORDER_DELETE)+ 护栏确认
生产进度怎么样 → top 为读(REPORT_PRODUCTION/REPORT_*),不弹写确认
完成生产/暂停生产 → 仍是写 + 护栏
查库存和今天的销售 → 多意图正常(不被误剔)
```
餐饮(qhj/RES_3101_009)抽样:`哪个菜卖得最好`/`今天营业额` 无回归。

- [ ] **Step 5: 5-agent 对抗终审(安全攸关分类器)**

终审重点(spec §11):(a) 三短路真被 VETO_WRITE 守卫挡住;(b) `取消订单` 仍写;(c) 双重否定守卫生效;(d) 安全不变量(VETO 后无写);(e) 多意图不误剔复合查询;(f) fail-open 不破识别;(g) 内部 NegationInfo ctor 三处 call site 无漏。

---

## Self-Review(写完计划后自查)

**Spec 覆盖:**
- §5 NegationTwinPolicy → T1 ✓ | §6.1/§6.4 否定分类+检测 → T2 ✓ | §6.5 早期门(finding7) → T3 ✓ | §6.2 否决+安全不变量 → T1(逻辑)+T3/T4(接线) ✓ | §6.3 组件2 → T1(twinRerank)+T4(多意图) ✓ | §6.5 委托消重 → T4 ✓ | §10 测试 → T1/T2/T3/T4 单测 + T5 golden/live ✓
- 缺口:多意图组件2"剔写孪生"的精确实现 T4 Step4 留给实施者适配(SingleIntentMatch↔CandidateIntent)—— 已标注保守约束 + `查库存和销售` 测试锁。

**Placeholder 扫描:** 无 TBD/TODO。所有 code step 含真实代码;brownfield 插入点标注"核对真实字段/签名"是必要的实施者动作,非占位。

**类型一致:** `NegationKind`(QueryPreprocessorService 内)/ `IntentKnowledgeBase.ActionType.QUERY`(非 READ)/ `CandidateIntent.getConfidence():Double` / `configResolver: Function<String,AIIntentConfig>` / `WriteGuardService.isWriteIntent(AIIntentConfig)`+`hasWriteSuffix(String)` — T1↔T2↔T3↔T4 一致。

---

## 并行工作建议
- **Subagent:** T1 与 T2 并行(契约 `NegationKind`+`NegationInfo.kind` 先定);T3→T4 串行(都改 IRP,T4 依赖 T3 标志)。T3 独立 review(finding7 核心)。T5 在 T1-T4 后。
- **多Chat:** ❌ 不适合(集中改 IRP+QPSImpl 两大共享文件,worktree 单 chat 隔离)。
