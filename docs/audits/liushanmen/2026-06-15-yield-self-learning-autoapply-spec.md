# 出成率自学习自动应用 设计 spec

> **状态**: 设计定稿, 已派 Codex 实现 (2026-06-15)。
> **前序**: #853 已生成 `BomYieldSuggestion`(PENDING, P50, 异常剔除)但**零消费方** —— 建议从没被应用。
> **客户需求**: "下批领料用上批已算出成率作默认"。

---

## 一、转录依据 (粒度已定, 无需再问客户)

**出成率 = 产品级 / 转化阶段级**(不是逐个 BOM item):
- transcript.txt [26:47-26:53]: "原料出成品 / 原料出半生品 / 半生品出成品" —— 出成率按**转化阶段**, 即产品级主料转化率
- transcript-2b.txt [13:52]: 辅料/包材 "对应的它有一个比例" —— 辅料/包材是**比例**(100%, 不算出成率)
- transcript-2b.txt [33:15-33:25]: "下一次生产领量时, 用那个出成率数字, 按上一批已算好的去计算" —— 正是本需求
- transcript.txt [01:09] 的"达成率"(per工序 90%/100%/75%)是**工时维度**, 不是出成率(数量维度), 别混

**半成品产品 = 两段**(原料→半成品 + 半成品→成品), 每段一个出成率 = 每个 productType 一个。`BomYieldSuggestion` 本就 per-productTypeId, 天然对齐。

---

## 二、现状 (origin/main 核实)

| 元素 | 位置 | 说明 |
|---|---|---|
| item 级出成率 | `BomRecipeItem.yieldRate`(默认100) | 领料用此: `actualQuantity = standardQuantity/(yieldRate/100)` (ProductionPlanServiceImpl:351) |
| 产品级出成率 | `BomRecipe.overallYieldRate`(默认100) | 领料**当前不读** |
| 建议 | `BomYieldSuggestion`(suggestedYieldRate/previousYieldRate/sampleCount/excludedSampleCount/status=PENDING) | #853 报工完工生成, **零消费方** |

**冲突**: 产品级一个 suggestedYieldRate ↔ item 级多个 yieldRate。

---

## 三、设计决策 (3 个, 已定死)

### 决策1 — 粒度 = 产品级 (转录已定, 见上)
建议落到产品级生效出成率; 主料(BOM item yieldRate≠100)用它; 辅料/包材(yieldRate=100, 比例)不变。

### 决策2 — 自动应用到"当前生效出成率", 非破坏主数据 + guard + 审计
⛔ **不覆盖 `BomRecipeItem.yieldRate` 主数据**(影响成本卷积 + 历史 BOM 版本, 风险高)。改:
- 报工完工 → #853 生成 suggestion(已有 sample≥3 + P50 + 异常剔除)
- **自动 promote** 满足 guard → status PENDING→APPLIED + 写产品级生效出成率(`BomRecipe.overallYieldRate` 或新 `current_yield_rate`)
- **guard**: sampleCount≥3 + `|suggested−previous|/previous ≤ 0.30`(可配, 防单批异常拉偏)+ 异常剔除后样本≥3
- 超 guard → 留 PENDING 人审(**不报错, 不自动应用**, 防坏数据污染)
- 审计: previousYieldRate / suggestedYieldRate / appliedAt / appliedBy=SYSTEM / sourceBatchId

### 决策3 — 领料读"当前生效出成率"
下批领料/物料需求算 actualQuantity 时:
- 查产品最新 APPLIED suggestion(或 overallYieldRate≠100)→ 主料(yieldRate≠100 的 item)用它折算
- 辅料/包材(yieldRate=100)不变; 无 APPLIED → fallback 现有 item yieldRate(**现状不变, 别破坏**)
- 主料识别: BOM item 中 yieldRate≠100 的; **多个非100 → 不自动应用该产品, 留 PENDING + 日志**(不猜)

---

## 四、为什么这样 (设计理由)

- "当前生效出成率"是**运行时默认**(可追溯可回退), 满足"下批用上批出成率"且**不污染 BOM 主数据**
- guard(阈值+样本)防单批异常(报错产量)自动把出成率拉偏
- 复用 #853 的 P50/异常剔除算法, 只加"应用"层, 不重造

---

## 五、Flyway

若加 `current_yield_rate` 字段 → **V20261024_14**(预分配: 当前最大 V12, V13 给 RN 中转仓确认卡, 本卡 V14, 防撞号)。

---

## 六、红线

🔒 成本/出成率红线: Codex 只到 PR off origin/main, Opus 终审 + 从 main 部署。关联 [[feedback_ci_red_check_main_not_just_pr]] 同族(读 stale 文档把已 ship 当缺 —— 本 spec 已对 origin/main 代码核实)。
