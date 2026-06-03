# 餐饮 AI 路由收口 + 自愈加固 设计

**日期**: 2026-06-03
**业态**: RESTAURANT (验证工厂 RES_3101_009 = qhj_prod 青花椒真实数据)
**关联**: [[project_2026_06_03_restaurant_time_conditions_p1]] (P1 时间), [[project_2026_06_03_intent_classifier_w1b_negation_twin]] (W1b 路由修复), W0 写护栏

---

## 1. Goal

解决用户两个诉求：
1. **确保"刚才聊到的"错路由问题全部解决** —— 餐饮常用问题不再撞制造业垃圾意图 / 写意图。
2. **即便调用了 LLM，同样问题下次不再用 LLM**（自愈）—— 长尾问法首次解析后被记住，下次 0-token 命中。

非目标 (backlog)：付款渠道 (微信/美团)、菜品分类 等 gold 层缺失维度的**真实**数据 —— 需新建 gold ETL，本 spec 只保证它们不返垃圾。

---

## 2. Prod 实测根因 (已调查确认)

| 现象 | 根因 |
|---|---|
| `付款方式占比 → SKU_GROSS_MARGIN`(制造业, LLM 瞎编) | `ToolRouterService.retrieveCandidateTools(query, topK)` **无工厂参数** → 餐饮问题按纯向量相似度捞到全业态工具；`SKU_GROSS_MARGIN` business_type=**COMMON** → 路由业态过滤(餐饮放行 `{null,COMMON,RESTAURANT}`)不排它 |
| `堂食外卖对比 → REVENUE_REPORT_GENERATE`(写)/`REVENUE_TREND`(来回跳) | 同上 + 无确定性短语 → 落动态/LLM 路径, 不稳定 |
| `哪家店客单价最高 → RATING_REVENUE_CORRELATION`(维度错) | 无确定性短语 + 语义最近邻误命中; `RESTAURANT_AVG_TICKET` 是孤儿意图(business_type=RESTAURANT 但 **tool_name 为空**, 无 executor) |
| **LLM 路径永不自愈** | `DynamicToolSelectionService` **零 learn 调用**(grep `tryAutoLearn\|learnExpression\|recordSample` in `service/execution`+`ai/tool` = 空) → 掉到动态路径的问题每次都烧 LLM |

**已确认安全前提**:
- `ai_learned_expressions` 中 RES_3101_009 仅 3 行, 无 SKU 中毒 (付款方式占比 conf<0.70 没学); 两条餐饮行 (`RESTAURANT_REVIEW_VIP`/`RESTAURANT_OPS_STORE_MARGIN`) 正确 → Loop-1 自愈本身工作正常。
- Loop-1 自愈机制完好: LLM-fallback conf≥0.70 → `tryAutoLearnExpression`(line 4537) → `ai_learned_expressions` → 下次 Layer-1 EXACT `matchExactExpression`(0-token)。`learnExpression`/`matchExactExpression` 同 `toLowerCase().trim()` + `computeHash` 对称, 另有 fuzzy-exact 兜底。
- prod config (`application-pg-prod.properties`): `auto-learn.enabled=true`, conf≥0.9 学关键词+表达, 0.70≤conf<0.9 仅学表达。

---

## 3. 共享组件: `BusinessTypeScope` (DRY 基石)

业态兼容判断逻辑当前**重复**在 `IntentRecognitionPipelineServiceImpl` 两处 (line ~2408 早期过滤 / ~2415 `tryLlmFallback`)。抽成单一事实源, 三轨复用。

**新建** `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/BusinessTypeScope.java` (与 `WriteGuardService.java` 同包, 无状态 util):

```java
package com.cretas.aims.ai.tool;

/** 业态兼容判断 — 单一事实源。镜像 IntentRecognitionPipelineServiceImpl 历史 v32.1 过滤逻辑。 */
public final class BusinessTypeScope {
    private BusinessTypeScope() {}

    /**
     * 意图业态是否对当前工厂业态可见(可路由/可学习)。
     * 餐饮工厂: 放行 {null, COMMON, RESTAURANT}; 其它工厂(FACTORY/COMMON/default): 排除 RESTAURANT 专属。
     * @param intentBusinessType ai_intent_configs.business_type (可 null)
     * @param factoryBiz configService.resolveBusinessDomain(factoryId) 返回值
     */
    public static boolean isCompatible(String intentBusinessType, String factoryBiz) {
        String bt = intentBusinessType;
        if ("RESTAURANT".equals(factoryBiz)) {
            return bt == null || "COMMON".equals(bt) || "RESTAURANT".equals(bt);
        }
        return !"RESTAURANT".equals(bt);
    }
}
```

**重构** `IntentRecognitionPipelineServiceImpl` 两处过滤改调 `BusinessTypeScope.isCompatible(i.getBusinessType(), biz)` (行为不变, 纯 DRY; 回归测试 `IntentParityTest` 保证零回归)。

---

## 4. Track A — 确定性短语修复 (治已知, 人写规则)

### A1. 堂食外卖对比 → `RESTAURANT_ORDER_STATISTICS` (order_type_mix_gold)

**文件**: `config/IntentKnowledgeBase.java` `restaurantPhraseMapping` (~line 6861 区域, 紧邻 #480 hotfix 的菜品短语)。

```java
restaurantPhraseMapping.put("堂食外卖对比", "RESTAURANT_ORDER_STATISTICS");
restaurantPhraseMapping.put("堂食外卖占比", "RESTAURANT_ORDER_STATISTICS");
restaurantPhraseMapping.put("内用外卖对比", "RESTAURANT_ORDER_STATISTICS");
restaurantPhraseMapping.put("堂食和外卖", "RESTAURANT_ORDER_STATISTICS");
```

验证: `RESTAURANT_ORDER_STATISTICS` 已绑 `restaurant_order_type_mix_gold` (V20260902_01:41), 返真实堂食/外卖营收占比。order_type_mix_gold 自述"查的是服务模式(堂食/外卖)而非支付渠道" → 与 A1 语义完全匹配。

### A2. 哪家店客单价最高 → `RESTAURANT_STORE_REVENUE_RANK` + gold 工具增 客单价

**A2a 短语** (`IntentKnowledgeBase.restaurantPhraseMapping`):
```java
restaurantPhraseMapping.put("哪家店客单价最高", "RESTAURANT_STORE_REVENUE_RANK");
restaurantPhraseMapping.put("客单价排行", "RESTAURANT_STORE_REVENUE_RANK");
restaurantPhraseMapping.put("客单价最高的门店", "RESTAURANT_STORE_REVENUE_RANK");
restaurantPhraseMapping.put("人均消费最高", "RESTAURANT_STORE_REVENUE_RANK");
```

**A2b 增 客单价输出** `ai/tool/impl/restaurant/gold/RestaurantStoreRevenueRankGoldTool.java`:
该工具已输出每店 `营收`(revenue) + `单数`(bill_count) (line 86-88)。**派生** `客单价 = revenue / bill_count` (单数>0 才算, 否则 null), 加进每个 `entry` + message。**真实数据, 非编造** —— 营收与单数都是 gold agg 原始列。

```java
// 在 entry.put("单数", ...) 之后:
Object revObj = row.get("revenue");
Object billObj = row.get("bill_count");
if (revObj != null && billObj != null) {
    java.math.BigDecimal rev = new java.math.BigDecimal(revObj.toString());
    java.math.BigDecimal bill = new java.math.BigDecimal(billObj.toString());
    if (bill.compareTo(java.math.BigDecimal.ZERO) > 0) {
        entry.put("客单价", rev.divide(bill, 2, java.math.RoundingMode.HALF_UP));
    }
}
```

message 里同步追加 "客单价 ¥X" (排行展示)。**不改排序** (仍按营收排; "客单价最高"由展示客单价列满足, 不重排避免破坏现有 store-rank 调用方; backlog: 若需真按客单价排, 加 sortBy 参数)。

### A3. VIP占比 → 不动 (现返 VIP 评价对比, 可接受)。

**关键词补强** (可选, 提升 KEYWORD 层命中, 减少落动态路径): 给 `RESTAURANT_ORDER_STATISTICS` / `RESTAURANT_STORE_REVENUE_RANK` 的 `ai_intent_configs.keywords` 补 "堂食外卖"/"客单价"/"人均" (flyway, `ON CONFLICT DO UPDATE`)。短语层已覆盖, 关键词是双保险。

---

## 5. Track B — 动态路径业态感知 (治根, 灭垃圾源头)

### B1. 动态候选业态过滤

**文件**: `service/execution/DynamicToolSelectionService.java` `executeWithDynamicToolSelection` (line 52, 有 `factoryId`)。在 `retrieveCandidateTools(query, 10)` (line ~74) **之后**插入过滤:

```java
List<ToolRouterService.ToolCandidate> candidates = toolRouterService.retrieveCandidateTools(query, 10);
candidates = filterCandidatesByBusinessType(candidates, factoryId);   // ← 新增
if (candidates.isEmpty()) { ... return buildNoToolResponse(intent); }
```

**新增私有方法** (同类):
```java
private List<ToolRouterService.ToolCandidate> filterCandidatesByBusinessType(
        List<ToolRouterService.ToolCandidate> candidates, String factoryId) {
    String biz;
    try { biz = configService.resolveBusinessDomain(factoryId); }
    catch (Exception e) { return candidates; }   // 解析失败不过滤(保守)
    if (biz == null || biz.isEmpty()) return candidates;
    // tool_name → 拥有它的意图集合(可能 0/1/N)
    List<AIIntentConfig> all = configService.getAllIntents(factoryId);
    Map<String, List<AIIntentConfig>> byTool = all.stream()
        .filter(i -> i.getToolName() != null && !i.getToolName().isEmpty())
        .collect(Collectors.groupingBy(AIIntentConfig::getToolName));
    List<ToolRouterService.ToolCandidate> kept = candidates.stream().filter(c -> {
        List<AIIntentConfig> owners = byTool.get(c.getToolName());
        if (owners == null || owners.isEmpty()) return true;   // 孤儿工具不判断业态 → 保留
        // 任一拥有意图业态兼容 → 保留; 全部不兼容 → 丢
        return owners.stream().anyMatch(i -> BusinessTypeScope.isCompatible(i.getBusinessType(), biz));
    }).collect(Collectors.toList());
    if (kept.size() < candidates.size()) {
        log.info("动态候选业态过滤: {} → {} (biz={})", candidates.size(), kept.size(), biz);
    }
    return kept;
}
```

需注入 `IntentConfigManagementService configService` 到 `DynamicToolSelectionService` (若未注入)。

### B2. 重标确认的 COMMON-制造业意图 → MANUFACTURING

**Flyway** `V20260913_03__retag_manufacturing_common_intents.sql` (版本号: `_01` 被 sister `product_type_wip_to_fg_yield` 占用, `_02` 是 Track A 关键词, 故用 `_03`):
```sql
-- ⚠️ 重标值必须是 'FACTORY' 不是 'MANUFACTURING'!
-- resolveBusinessDomain 只返 'RESTAURANT'/'FACTORY' (从不返 'MANUFACTURING')。
-- BusinessTypeGate 用 intentBiz.equalsIgnoreCase(factoryDomain): 'FACTORY'=='FACTORY' 工厂租户放行 ✓;
--   若用 'MANUFACTURING' → 'MANUFACTURING'!='FACTORY' → 工厂租户被误拦"本店为餐饮业态" (回归!)。
-- 'FACTORY': BusinessTypeScope.isCompatible('FACTORY','RESTAURANT')=false 餐饮过滤排除 ✓;
--            isCompatible('FACTORY','FACTORY')=true 工厂租户保留 ✓; 两个 consumer 都正确。
UPDATE ai_intent_configs SET business_type = 'FACTORY', updated_at = NOW()
 WHERE intent_code = 'SKU_GROSS_MARGIN'
   AND business_type = 'COMMON';
```

**边界**: **只重标 `SKU_GROSS_MARGIN`** (生产批次毛利, 真工厂概念)。**重标值 = `'FACTORY'`** (系统 canonical 工厂业态值): 餐饮工厂 `isCompatible('FACTORY','RESTAURANT')=false` 排除; 工厂租户 `isCompatible('FACTORY','FACTORY')=true` + `BusinessTypeGate 'FACTORY'=='FACTORY'` 放行, **零回归**。**不可用 `'MANUFACTURING'`** (resolveBusinessDomain 不返该值 → BusinessTypeGate 误拦工厂租户)。

**⚠️ 终审修正**: 原 spec 还想重标 `REVENUE_REPORT_GENERATE` —— **错**。5-agent 终审确认它是**餐饮**功能 (`RevenueReportGenerateTool` 在 `ai/tool/impl/restaurant/`, intent_category=RESTAURANT, 生成青花椒餐饮收入报表 午市/晚市/堂食外卖)。误重标 FACTORY 会让 `BusinessTypeGate` 在餐饮租户上把"拉收入报表"拦成"工厂分析不适用"(回归)。它**保持 COMMON 不动**; 原"堂食外卖对比"误撞它的问题已由 Track A 确定性短语修复。更广的 COMMON 误标审计 = backlog。

---

## 6. Track C — 带护栏自愈 (核心诉求: 下次不再用 LLM)

### C1. 动态单工具成功执行后学习

**文件**: `DynamicToolSelectionService.java` 单工具路径 (`selectTools` 选中 → 执行成功构建 SUCCESS response 之后, return 之前; 即 line ~104 之后的单工具执行分支)。新增: 当 (a) 非多工具 auto-plan (单工具, `selected.getTools().size()==1`) + (b) 执行成功且有数据 (`"SUCCESS".equals(status) && resultData != null`) + (c) 选中工具的拥有意图业态兼容 + (d) 选中工具的检索相似度 ≥ 阈值 → 调学习。

**置信度来源 (重要, API 已核实)**: `SelectedTools` **无 `getConfidence()`**。用**选中工具对应 `ToolCandidate.getSimilarity()`**(向量检索余弦相似度, line 301) 作为置信度代理。阈值 `DYNAMIC_LEARN_MIN_SIMILARITY = 0.55` (类常量, 防极低相似度误学; 不复用 LLM-conf 阈值 0.70, 因检索相似度量纲不同)。

学习目标 intentCode = **选中工具的拥有意图** (configService 查 tool_name→intent; 多拥有取业态兼容的第一个)。落 `ai_learned_expressions`(query→intentCode), 下次 Layer-1 EXACT 命中该意图 → 走同一工具 → 0 LLM。

```java
private static final double DYNAMIC_LEARN_MIN_SIMILARITY = 0.55;

// 单工具成功执行、构建 response(status=SUCCESS) 之后, return 之前:
maybeLearnFromDynamicSelection(query, selectedTools, candidates, factoryId, response);
```

```java
private void maybeLearnFromDynamicSelection(String query, ToolRouterService.SelectedTools selected,
        List<ToolRouterService.ToolCandidate> candidates, String factoryId, IntentExecuteResponse response) {
    try {
        if (selected.getTools() == null || selected.getTools().size() != 1) return;       // 单工具
        if (response == null || !"SUCCESS".equals(response.getStatus())
                || response.getResultData() == null) return;                              // 执行成功+有数据
        String toolName = selected.getTools().get(0).getToolName();
        double sim = candidates.stream()
            .filter(c -> toolName.equals(c.getToolName()))
            .mapToDouble(ToolRouterService.ToolCandidate::getSimilarity).max().orElse(0.0);
        if (sim < DYNAMIC_LEARN_MIN_SIMILARITY) return;                                   // 相似度门
        String biz = configService.resolveBusinessDomain(factoryId);
        AIIntentConfig owner = configService.getAllIntents(factoryId).stream()
            .filter(i -> toolName.equals(i.getToolName()))
            .filter(i -> BusinessTypeScope.isCompatible(i.getBusinessType(), biz))         // 业态兼容护栏
            .findFirst().orElse(null);
        if (owner == null) return;                                                        // 无兼容拥有意图 → 不学
        expressionLearningService.learnExpression(factoryId, owner.getIntentCode(),
            query.trim(), sim, LearnedExpression.SourceType.DYNAMIC_SELECTION);            // 新 SourceType
        log.info("动态选择自愈学习: query={}, intent={}, tool={}, sim={}",
            truncate(query,40), owner.getIntentCode(), toolName, sim);
    } catch (Exception e) { log.warn("动态选择学习失败: {}", e.getMessage()); }  // fail-open
}
```

- `SelectedTool.getToolName()` (inner class line 353-357); `ToolCandidate.getSimilarity()` (line 301)。`IntentExecuteResponse` 是 `@Data` (`dto/ai/IntentExecuteResponse.java`): 用 `getStatus()`("SUCCESS"/"PARTIAL_SUCCESS"/"FAILED") + `getResultData()`(Object), **无** `isSuccess()`/`getData()`。
- **新枚举** `LearnedExpression.SourceType.DYNAMIC_SELECTION` (`entity/learning/LearnedExpression.java` line 245 enum, 现有 LLM_FALLBACK/USER_FEEDBACK/MANUAL/SEMANTIC_HIGH/KEYWORD_MATCH/LLM_RERANKING, 追加一个)。
- **注入** (DynamicToolSelectionService 现有 `@Autowired` field 风格, 同 `writeGuardService`): 加 `@Autowired IntentConfigManagementService configService` + `@Autowired ExpressionLearningService expressionLearningService` (matchingConfig 此处不再需要, 改用类常量 similarity 门)。

### C2. 防中毒护栏 (加到 Loop-1 现有学习点 + C1)

`tryAutoLearnExpression` (line 4537) 顶部加业态守卫 (单一 choke point, 覆盖 line 2312/2318/2618 三个 Loop-1 调用站):

```java
private void tryAutoLearnExpression(String userInput, String intentCode, String factoryId,
                                    double confidence, LearnedExpression.SourceType sourceType) {
    if (userInput == null || userInput.trim().isEmpty()) return;
    // 防中毒: 拒绝学习业态不兼容的意图(餐饮工厂永不学 SKU_GROSS_MARGIN 等)
    try {
        String biz = configService.resolveBusinessDomain(factoryId);
        AIIntentConfig cfg = configService.getAllIntents(factoryId).stream()
            .filter(i -> intentCode.equals(i.getIntentCode())).findFirst().orElse(null);
        if (cfg != null && !BusinessTypeScope.isCompatible(cfg.getBusinessType(), biz)) {
            log.warn("拒绝中毒学习: intent={} business_type={} 与工厂 biz={} 不兼容",
                intentCode, cfg.getBusinessType(), biz);
            return;
        }
    } catch (Exception e) { /* 解析失败不阻断既有学习, 保守放行 */ }
    // ... 原 learnExpression 逻辑不变
}
```

C1 的 learnExpression 调用也经业态护栏 (C1 内已 filter, 双重保险无害)。

**EXACT 优先级说明**: 中毒的 learned expression 是 Layer-1, 会盖过后加短语。本 spec 通过"学习前业态守卫"从源头防止中毒写入; 历史已有中毒(本 case 无)需手动清 `ai_learned_expressions` (非本 spec 范围, 已确认 RES_3101_009 无中毒)。

---

## 7. 数据流

```
用户问"堂食外卖对比"
  → orchestrator 短语短路 / recognition 短语层命中 RESTAURANT_ORDER_STATISTICS (Track A)
  → order_type_mix_gold 真实数据 ✓ (0 LLM, 确定性)

用户问未预设的长尾"我们家外送生意占几成"
  → 短语/关键词/语义 miss → 动态路径
  → retrieveCandidateTools → filterCandidatesByBusinessType 排除制造业工具 (Track B)
  → selectTools 选中 restaurant_order_type_mix_gold → 执行成功有数据
  → maybeLearnFromDynamicSelection: 单工具+成功+业态兼容+检索相似度≥0.55 → 学 query→RESTAURANT_ORDER_STATISTICS (Track C)
  → 下次同问法 → Layer-1 EXACT 命中 → 0 LLM ✓ (自愈闭环)

用户问"付款方式占比" (gold 无支付渠道维度)
  → 动态路径 → filterCandidatesByBusinessType + 重标(Track B) 排除 SKU_GROSS_MARGIN
  → 无制造业垃圾; 落餐饮工具(近似)或诚实 no-tool → 不再瞎编 ✓ (真实分类需 ETL = backlog)
```

---

## 8. 测试策略

**单元测试** (mvn, backend/java/cretas-api):
- `BusinessTypeScopeTest`: isCompatible 真值表 (RESTAURANT×{null,COMMON,RESTAURANT,MANUFACTURING}, FACTORY×{RESTAURANT,COMMON,MANUFACTURING})。
- `RestaurantStoreRevenueRankGoldToolTest`: 客单价 = 营收/单数 派生 (单数>0 / =0 / null 边界); 不破坏现有 entry。
- `DynamicToolSelectionBusinessFilterTest`: filterCandidatesByBusinessType — 制造业工具被排, 餐饮/COMMON/孤儿保留。
- `DynamicSelectionSelfHealLearnTest`: maybeLearnFromDynamicSelection — 单工具+SUCCESS+resultData非空+兼容+相似度≥0.55 学; 多工具/非SUCCESS/resultData=null/不兼容/相似度<0.55 不学。
- `AutoLearnPoisonGuardTest`: tryAutoLearnExpression 拒学不兼容意图; 兼容意图正常学。
- 回归: `IntentParityTest` + `IntentGoldenAssertionTest` 零回归 (BusinessTypeScope 重构不改行为)。

**Prod live verify** (RES_3101_009, 部署后):
- A1: 堂食外卖对比 → order_type_mix 真数据 (非 REVENUE_REPORT_GENERATE/REVENUE_TREND)。
- A2: 哪家店客单价最高 → store-rank, message 含"客单价 ¥X"。
- B: 付款方式占比 → 不再 SKU_GROSS_MARGIN (检查 intentCode 非 SKU/REVENUE_REPORT_GENERATE)。
- C: 选一个未预设长尾问法跑两次 → 2nd 次 `ai_learned_expressions` 新增行 + matchMethod=EXACT (0 LLM)。

---

## 9. 部署 + 安全

- **worktree off origin/main** (`feat/restaurant-route-selfheal`), PR → merge main → 蓝绿部署 prod (10010/10020 轮换) + test (10011)。
- **安全等级高** (意图路由 + 自学习): subagent-driven 每任务 spec-review + code-quality-review, **最终 5-agent 对抗终审** (同 W1b safety-critical classifier)。
- Flyway 版本号 **PR 前 + merge 后 `git ls-tree origin/main` 复核防撞车** (关联 [[feedback_flyway_cross_session_dup_collision]]): 本 spec 用 V20260913_01。
- 部署后 `unzip -p jar BusinessTypeScope.class | strings | grep isCompatible` 核对运行 jar 含修复。

---

## 10. Out of scope / backlog

- 付款渠道 (微信/美团/支付宝) 真实占比 → 需 gold `agg_payment_channel` ETL。
- 菜品分类 (川菜/凉菜/...) 销量 → 需 gold `agg_dish_category` ETL (菜名→分类实体解析)。
- `RESTAURANT_AVG_TICKET` 孤儿意图给独立 gold executor (当前 A2 用 store-rank 派生客单价覆盖)。
- 更广的 COMMON 误标制造业意图审计 (本 spec 只重标 2 个确认 offender)。
- store-rank 真按客单价重排 (当前仅展示客单价列, 排序仍按营收)。
- 历史中毒 learned_expressions 批量清理工具 (本 case 无中毒, 暂不需要)。
- **Track C 自愈对"长问题/无 session 问题"只部分生效** (5-agent 终审 IMPORTANT): 动态路径学的是 `query`(=finalQuery 或 raw userInput), Layer-1 EXACT 下次比对的是 `processedInput`。**session-present + 短问题**(web-admin/RN 聊天主路径, 21 题目录全是短问题)二者相等 → 自愈生效; 但 **session 为 null** (raw vs enhancedPreprocess 归一) 或 **超长问题** (finalQuery 之后又过 keyword-extraction) 二者哈希不等 → 学了的 EXACT 行命中不了 → 该问法下次仍走 LLM (只是多一行死 row, 不误路由不中毒)。fuzzy-exact (95%) 部分兜底。**彻底修**: 把 recognition 算出的 `processedInput` 透传进动态路径, 学它而非 `query`(中等改动, 留 backlog)。
