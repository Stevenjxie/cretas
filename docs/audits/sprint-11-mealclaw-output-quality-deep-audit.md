# Sprint 11 MealClaw — Output Quality Deep Audit

**审计触发**: Steve 质疑 "PM 100% DONE" 是技术意义 (routing/schema PASS) 还是产品意义 (输出真有价值)
**审计日期**: 2026-05-23
**审计 owner**: 餐饮 AI chat (原 PM)
**结论**: 🚨 **客户视角看到的是错误信息堆, 不是经营建议. 不应该 demo.**

---

## Q1: 实际输出内容 (4 phrase × prod curl)

### 测试条件
- Endpoint: `POST http://47.100.235.168:10010/api/mobile/RES_3101_009/ai-intents/execute`
- Account: qhj_warehouse_mgr (RES_3101_009)
- Date: 2026-05-23

### 4 phrase formattedText (用户看到的文字)

**ALL 4 phrases 返回 IDENTICAL message**:

```
部分数据不可用: P&L 一页纸 / 档口损溢 / 成本刚性. 已基于可用数据生成分析, 不可用部分需明确标注.
```

**完整 message 字段** (含 `(缓存结果)` debug prefix + 嵌套 JSON dump):

```
(缓存结果) {
  "data": {
    "dataAvailable": false,
    "summary": {
      "source": "store_pnl_one_pager",
      "dataAvailable": false,
      "message": "P&L 一页纸数据获取失败: 暂无法生成「store_pnl_one_pager」分析：未提供 financial_metrics (需 DiagnosticsHandler 先运行或直接传入)\n\n请先上传相关数据或补充所需参数后重试。"
    },
    "topItems": {
      "source": "shrinkage_analysis",
      "dataAvailable": false,
      "message": "档口损溢数据获取失败: 暂无法生成「shrinkage_analysis」分析：未提供 shrinkage_rows 参数 (需要各档口的 standard_cost 和 actual_cost)\n\n请先上传相关数据或补充所需参数后重试。"
    },
    "recommendations": {
      "source": "cost_rigidity",
      "dataAvailable": false,
      "message": "成本刚性数据获取失败: 暂无法生成「cost_rigidity」分析：未提供 financial_data.current\n\n请先上传相关数据或补充所需参数后重试。"
    },
    "evidence": [...3 error items...],
    "message": "部分数据不可用: P&L 一页纸 / 档口损溢 / 成本刚性..."
  },
  "success": true
}
```

### 判断: **(B) 数据缺信息**, NOT (A) 经营建议

- ❌ 不是 "您 上月 X 菜亏 Y 元" 这种经营建议
- ❌ 不是 "建议调整 Z" 这种 actionable advice
- ❌ 不是 Top N 异常品 ranking
- ✅ 是 "P&L 数据获取失败 / 请先上传相关数据" 错误堆
- ✅ 是 raw JSON 嵌套 dump (客户读到嵌套 JSON)
- ✅ 是 cache prefix "(缓存结果)" debug 信息 (不该给客户看)
- ✅ 是 generic — 4 个不同 phrase 返回完全 IDENTICAL formattedText

**Phrase-specificity test**: "帮我看上月损溢异常" vs "哪个菜亏钱" vs "损益分析" vs "上月成本" → SAME response.

---

## Q2: 3 sub-Tool 数据接通

### 架构
3 个 sub-Tool 都 extends `AbstractRestaurantDiagnosticTool` (`backend/java/.../restaurant/diagnostic/`):

| sub-Tool | getSectionName | Python endpoint | 真 query |
|---|---|---|---|
| RestaurantStorePnlOnePagerTool | `store_pnl_one_pager` | `pythonClient.callRestaurantSection(...)` | **未执行 — 缺 financial_metrics 上下文** |
| RestaurantShrinkageAnalysisTool | `shrinkage_analysis` | 同上 | **未执行 — 缺 shrinkage_rows 上下文** |
| RestaurantCostRigidityAnalysisTool | `cost_rigidity` | 同上 | **未执行 — 缺 financial_data.current 上下文** |

### Root cause

`AbstractRestaurantDiagnosticTool.doExecute()` 调用 `pythonClient.callRestaurantSection(sectionName, request)`. **Java 端只是 thin wrapper, 无实际数据查询**. Python section endpoint 期望上游 DiagnosticsHandler 已经把 `financial_metrics` / `shrinkage_rows` / `financial_data.current` 写入 context. **MealClaw composite tool 没 wire up data auto-fetch pipeline** — sub-Tool 收不到 context, Python 返 "数据获取失败".

### dataAvailable=false 真原因

不是 "数据库没数据" — 是 "Composite Tool 没有从 DB 加载 context 给 Python". 这是**实现缺陷**, 不是数据问题.

---

## Q3: 餐饮表 inventory + Path A 覆盖率

### cretas_prod_db 餐饮相关表 (15 张)
```
auto_recipe_drafts, bom_recipe_items, bom_recipes, disposal_records,
pos_connections, pos_order_syncs, recipes, restaurant_performance_rules,
restaurant_piecework_configs, restaurant_sales_plans,
restaurant_shift_schedules, restaurant_shift_templates,
user_menu_permissions, v_current_recipes, v_recipes_effective
```

### smartbi_prod_db 分析表 (25+ 张)
agg_* / dim_* / fact_* 全套, 含 `fact_pos_item` (646K POS rows), `agg_restaurant_daily_ops`, `dim_ingredient`, `dim_product`, `agg_restaurant_product_cost`.

### Path A (AIChat Composite Tool) 实际查表数
- **0 张** — Java 端 wrapper, 不直接查 DB
- 期望 Python 收到已 populated 的 context, 但 context **没有 wire**
- 结果: 0 张表被 Composite Tool 使用

### 覆盖率
- 餐饮表总数: 40+
- Path A 实际查: **0**
- 覆盖率: **0%**

### Path B (Python composite endpoint, /api/smartbi/restaurant/llm-composite) 实际查表
- 3 张: `fact_pos_item` + `dim_product` + `fact_pos_transaction` (per Round 2 audit)
- 覆盖率: 3/40 = 7.5%
- **但 Path B 客户不会调用** (走 AIChat = Path A)

---

## Q4: 69 Restaurant Tool 测试覆盖

### Tool 总数
**69** 个 Tool (verified: `find backend/.../restaurant -name '*Tool.java' | wc -l`)

### 测试覆盖
| 维度 | 数 | % |
|---|---|---|
| Java 单测 | **4 / 69** | **5.8%** |
| Playwright E2E | **1 / 69** (仅 loop-6 测 RestaurantEconomicsAnalysisTool) | **1.4%** |
| Prod smoke | **1 / 69** (仅 RestaurantEconomicsAnalysisTool) | **1.4%** |

### 单测 4 个: 
- AbstractRestaurantDiagnosticToolTest
- MealPeriodNormalizerTest
- RestaurantEconomicsAnalysisToolTest
- RevenueReportGenerateToolTest

### 68 个 Tool **完全没测试**, 其中含 Phase 4 demo 同类风险的 64 个 (e.g. RestaurantBomLayerStatusTool / RestaurantDishCostAnalysisTool / RestaurantPnlBreakdownTool 等)

---

## Q5: 4 phrase × 5 字段 = 20 cell 矩阵

| Phrase | summary | topItems | recommendations | evidence | dataAvailable |
|---|---|---|---|---|---|
| 帮我看上月损溢异常 | ❌ "P&L 数据获取失败" | ❌ "档口损溢数据获取失败" | ❌ "成本刚性数据获取失败" | ❌ 3 个 error object | ❌ false |
| 损益分析 | ❌ 同 | ❌ 同 | ❌ 同 | ❌ 同 | ❌ false |
| 上月成本 | ❌ 同 | ❌ 同 | ❌ 同 | ❌ 同 | ❌ false |
| 哪个菜亏钱 | ❌ 同 | ❌ 同 | ❌ 同 | ❌ 同 | ❌ false |

**20/20 = 100% cells 是错误信息**, **0 个 cell 含真实经营内容**.

**生产/演示评估**: 完全不可用作客户演示.

---

## Q6: Deploy Decision (4 选 1)

### Option A: 按 brief 发微信 demo
- **客户失望率估算**: **95%+**
- 风险: 极高. 客户看到 "数据获取失败" × 3 + raw JSON dump + (缓存结果) prefix → 直接判定"软件 broken / 不靠谱"
- 后果: 客户对 Cretas 整体不信任, MealClaw scope 烂尾
- **PM 不推荐** ❌

### Option B: 不发, 先 wire DiagnosticsHandler context (修复数据 pipeline)
- 工时估算: **3-5 天** (1 天 wire context auto-fetch + 1-2 天测试 3 sub-Tool 真返数据 + 1-2 天 E2E real-data verify)
- 风险: 中. 真正修复, 但延迟客户曝光
- 后果: 修完后再 demo, 客户看到真经营建议
- **PM 推荐 ✅✅**

### Option C: 改 brief, 让客户走 Path B (SmartBI composite endpoint 直接调)
- 工时: 1 hr 改 brief
- 风险: 高. 客户用 curl/Postman 不现实, 改 demo 流程跟 MealClaw 公告不一致
- 后果: 客户感觉怪 — "为啥不是 AIChat"
- **PM 不推荐** ❌

### Option D: Steve 陪同演示 + 实时解释 "AI 哨兵 USP"
- 工时: 1 小时陪同 demo
- 风险: 中-高. 客户能听懂 "诚实告知数据缺" 是 USP 还是软件烂?
- 后果: 50/50. 如果客户买账, OK. 如果买不买账, 跟 Option A 一样 95% 失望
- **PM 不推荐** — 但比 Option A 稍好

### 推荐: **Option B**

**理由**:
1. Q5 显示 100% cell 是 error, 不修无法 demo
2. Q4 显示 69 Tool 中 68 个未测, 修 1 个不够 (但 MealClaw scope 就靠这 1 个 Composite)
3. Q1 显示客户看到 JSON dump + "(缓存结果)" debug — 修了才能演示
4. ROI: 3-5 天 vs 客户失望成本 + Cretas 品牌伤害

---

## Q7 + Q8: UI Playwright (subagent 跑中)

详见 `web-admin/tests/e2e-customer-journey/mealclaw-customer.spec.ts` + `docs/audits/sprint-11-mealclaw-screenshots/`.

但根据 Q1-Q5 evidence, **UI 截图会显示 = "数据获取失败" 错误堆**. Q7/Q8 截图只 visualize 同一 root cause, 不改变 Q6 决策结论.

---

## "PM 100% DONE" 真实评分修正

| 之前 self-report | 真实评分 | Gap |
|---|---|---|
| Phase 1: 7/7 ✅ | 7/7 ✅ | OK |
| Phase 2: 6 PR + 96% 单测 ✅ | 但 **5.8% Tool 覆盖** | 单测只覆盖 4/69 |
| Phase 3: 6 rounds E2E P0=0 P1≤2 ✅ | 但 **测的是 routing 不是 content** | 仅 1 个 Tool 测 |
| Phase 4 PM: prod ship + retro ✅ | 但 **output 100% 是 error** | content invalid |
| 最终硬验证 PART 1: 4 phrase 路由 ✅ | 路由 ✅ 但 **content 全 error** | 仅 1 维 verified |

**真 PM done 评分**: **40%** (路由 + schema work, content / business value = 0)

**gap (剩 60%)**:
- (a) DiagnosticsHandler context auto-fetch wire-up
- (b) 3 sub-Tool 真返 RES_3101_009 数据
- (c) 68 个其他 Restaurant Tool 测试覆盖
- (d) cache prefix "(缓存结果)" 清理
- (e) formattedText 包装 (不是 raw JSON dump)
- (f) Phrase-specificity (4 phrase 不该返同一答案)

---

## 致歉 + 教训

PM 之前 claim "PM 100% DONE" 是基于 routing PASS + schema schema match. **没 verify 客户视角看到的实际文字**. 这违反 superpowers:verification-before-completion skill — claim 完成前没真验证 user-facing output.

Lesson: 下次 PM done 必须**模拟客户视角看 UI 实际显示**, 不只是 API schema PASS.

**Steve 的质疑 100% 正确**. 致歉.

---

## 参考文件

- 4 phrase prod evidence: `/tmp/q1-prod-responses.txt` (curl raw)
- 5-字段 matrix evidence: `/tmp/q5-matrix.txt`
- Q2 source: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/diagnostic/AbstractRestaurantDiagnosticTool.java`
- Q3 SQL queries: 见正文
- Q4 grep: `find backend/.../restaurant -name '*Tool.java' | wc -l` = 69 / 单测 4 / E2E 1 / smoke 1
- Q7/Q8 Playwright: subagent in flight (将 commit screenshots + video)

---

## Decision 待 Steve confirm

**PM 推荐 Option B** (3-5 天 wire context + 重新 demo).

Steve 你说 (A/B/C/D)?
