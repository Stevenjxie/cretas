# Sprint 8 AI Workdesk Implementation Design Spec

**Goal**: 参见 `2026-05-20-sprint-8-ai-workdesk-goal.md`
**Audit base**: 3 份独立 audit 一致结论 (本 session 预测 + Other chat 深度审计 + Round 14 HJ 对比量化)
**作者**: brainstorming skill via Steve organizer
**日期**: 2026-05-20
**Sprint 8 timebox**: 3-4.5 周 (P0 1-2d + P1-P4 各 3-5d)

---

## §0 Background & Architecture Context

### 0.1 已有架构基础 (不重做, 利用)

| 组件 | 现状 | 备注 |
|---|---|---|
| Tool registry | 476 Tool 已注册 (`ai/tool/impl/`) | 但 160+ 返 null/empty, 需 P0 audit |
| Skill registry | 18 Skill (`service/skill/impl/SkillRegistryImpl.java`) | 全是 Q1 老 Skill 单域, Sprint 5+6+7 0 新 |
| Intent config | 286 intent in `ai_intent_configs` table | 多数没绑 `tool_name`, 走 LLM 兜底 |
| 意图识别 | 8 路 (EXACT/PHRASE/REGEX/KEYWORD/SEMANTIC/CLASSIFIER/FUSION/LLM) | 框架完整 |
| Preview 机制 | `AbstractBusinessTool.doPreview()` TCC | 已支持, P3 写操作 Tool 必用 |
| 防呆 4 位一体 | `.claude/rules/fool-proof-design.md` 5 规则 | 全 UI/Tool 必遵 |

### 0.2 Sprint 5+6+7 ship 但缺 Tool 包装的 customer entity (本 Sprint 8 主战场)

| Entity | 文件 | Tool 缺口 |
|---|---|---|
| `WechatRecord` | `entity/WechatRecord.java` | 5 Tool (query/recent/create/edit/delete) |
| `CallRecord` | `entity/CallRecord.java` | 5 Tool (含 OSS upload + transcribe trigger) |
| `SalesOpportunity` | `entity/SalesOpportunity.java` | 6 Tool (query/create/transition/funnel/kanban/alert) |
| `SalesTarget` + `Commission` | `entity/SalesTarget.java` etc | 5 Tool (target/progress/leaderboard/commission/rule) |
| `AccountingPeriod` | `entity/finance/AccountingPeriod.java` | 4 Tool (status/open/close/reopen) |
| `Account` | `entity/finance/Account.java` | 3 Tool (query/tree/lookup) |
| 三大报表 (BalanceSheet/IncomeStatement/CashFlow) | service/finance/ (DTO not entity) | 3 Tool + R0 修可达性 (audit 揭真页路由冲突) |
| `WagePolicy`+`HourlyRateRule` | `entity/WagePolicy.java` etc | 3 Tool (policy/calc/preview) |
| `PurchaseRequisition` | `entity/inventory/PurchaseRequisition.java` | 3 Tool (query/create/approve) |
| `Voucher` | (existing, 复式 already ship) | 2 Tool (query/aggregate) |

**总 Tool 需新增**: ~50 个 + Preview support。

### 0.3 食品行业 P0 法定缺失 (P3 phase 补完)

| 缺失 | 法定依据 | 现状 |
|---|---|---|
| HACCP CCP 管理 | 食品安全法 + GB/T 27341 | 完全缺 |
| GB 2760 添加剂限量校验 | GB 2760-2014 | 完全缺 |
| 食品召回闭环 | 食品安全法第 63 条 | 单 `batch_trace` Tool 有, Skill 编排缺 |
| 留样追踪 (48h) | GB 31654-2021 | 完全缺 (推 Sprint 9 P1) |
| 营养标签 | GB 28050 | 完全缺 (推 Sprint 9 P1) |

---

## §P0 修信任 + Audit Cleanup (1-2d, BLOCKING)

### P0.1 目标

不再让 Sprint 5+6+7 "ship 完整但用户看不见" 状态继续。3 件事:
1. 修 Sprint 7 T3 三大报表用户可达性
2. 清 11 占位页 (`<el-empty description="功能开发中">`)
3. 归类 160+ 空壳 Tool + 102 @Deprecated, 输出 audit-cleanup-report.md

### P0.2 任务清单

#### P0.2.1 修 Sprint 7 T3 路由冲突 (~3h)

**问题确认 step**:
```bash
# 1. 找两个 finance reports 路径
find web-admin/src/views/finance -name "*.vue" | grep -iE "report"

# 2. 看路由挂哪个
grep -nE "finance/report|finance/reports" web-admin/src/router/index.ts

# 3. 看菜单挂哪个
grep -rn "finance/report\|finance/reports" web-admin/src/layouts/
```

**修复 step**:
- 如果路由+菜单挂的是 `finance/reports/list.vue` (占位), 改成挂 T3 新建的 `finance/report/index.vue` (3 tab parent)
- 删除旧 `finance/reports/list.vue` (避免后续混淆)
- F006 prod 账号验证 (smoke): 财务 → 报表 → 看到 3 tab 不是 el-empty

**Deliverable**: PR `sprint8/p0-fix-t3-reports-routing`, 1 file modified + 1 file deleted

#### P0.2.2 清 11 占位页 audit (~4h)

**步骤**:
```bash
# 1. 列出全部 el-empty 占位页
grep -rln '<el-empty.*description.*开发中\|<el-empty.*description.*敬请期待\|<el-empty.*description.*占位' web-admin/src/views/

# 2. 对每个文件决定:
#   - DELETE (无任何依赖)
#   - HIDE behind feature flag (路由保留但隐藏菜单入口)
#   - KEEP (是合法 "empty state" 不算占位)
```

**输出 `docs/audits/2026-05-XX-placeholder-audit.md`**:
| 文件 | 决策 | 理由 |
|---|---|---|
| `finance/reports/list.vue` | DELETE (P0.2.1 已处理) | T3 ship 后冗余 |
| `equipment/maintenance/index.vue` | HIDE | Sprint 9 backlog 才做 |
| `CalibrationListView.vue:537` | HIDE | 后端 API 未开始 |
| ... (剩 8 个待 audit) | ... | ... |

**Deliverable**: PR `sprint8/p0-cleanup-placeholders`, ~10 file modified

#### P0.2.3 audit 160+ null tool (~6h)

**步骤**:
```bash
# 1. grep all Tool 返回 null/empty 的位置
grep -rln "return null;\|return Collections.emptyList();\|return new HashMap<>();\|return new ArrayList<>();" backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/

# 2. 对每个文件读 doExecute() 方法 + 分类:
#   - REAL_NOT_IMPLEMENTED: 业务逻辑未实现, 必修 (P0 - 列优先级)
#   - TEST_STUB: 故意空 (例如 Tool 还没绑 intent, 等以后扩展)
#   - FAIL_FAST: 主动 fail-safe, 防止假数据 (不修)
#   - DEAD_CODE: 老 Tool 已无 caller (P0 - 删除)
```

**输出 `docs/audits/2026-05-XX-null-tool-audit.md`** (~10 KB):
- 总数 + 各分类 count
- REAL_NOT_IMPLEMENTED top 20 (按 entity 重要性排序)
- DEAD_CODE 删除清单

**Deliverable**: PR `sprint8/p0-audit-null-tools`, 仅 audit doc + 删 DEAD_CODE (~10-30 文件)

#### P0.2.4 audit 102 @Deprecated (~3h)

**步骤**:
```bash
# 1. grep @Deprecated 在新代码 (Sprint 5+ 之后加的)
git log --since="2026-04-01" --diff-filter=AM -p -- '*.java' | grep -B2 '@Deprecated' | head -100

# 2. 分类:
#   - LEGACY_KEPT: 旧设计废弃但还有 caller, 等迁移 (列迁移目标)
#   - JUST_DEPRECATED: 加完就 deprecated, 设计反复 (问 why, 决定保留/删)
#   - REAL_DEAD: 无 caller (删)
```

**Deliverable**: 同 audit doc, 决策清单

### P0.3 P0 验收门 (BLOCKING P1 开始)

- [ ] P0.2.1 PR merged + F006 真账号 smoke (Steve 亲验)
- [ ] P0.2.2 PR merged + 11 占位页全 audit 完
- [ ] P0.2.3 audit doc + DEAD_CODE 删除 PR merged
- [ ] P0.2.4 audit doc 完成
- [ ] 整理 P0 总报告 commit 到 main: `docs/audits/2026-05-XX-pre-sprint-8-cleanup-summary.md`

不全通过, P1 不能 dispatch。

---

## §P1 卤味老板 Workdesk V1 (3-5d, F006 真场景)

### P1.1 用户故事

> 张老板 (六腾门卤味) 早上 8 am 打开 Cretas, 移动端或 web。说一句 "今天哪些客户该跟进?" 系统 5 秒内输出排序客户清单, 每个客户标:
> - 跟进优先级 (🔴 🟡 🟢)
> - 上次接触时间 + 渠道 (微信/电话)
> - 商机阶段 (NEGOTIATE 3 周未推进 = ⚠️)
> - 历史订单趋势 (本月 vs 上月)
> - 推荐行动 (报价 / 回访 / 寄样)

张老板 30 秒内决定今天先打 3 个电话, 不用自己看 8 个菜单 (HJ 模式 = 客户列表 → 微信记录 → 通话记录 → 商机阶段 → 订单历史, 5 屏 5 分钟)。

### P1.2 Tool 包装清单 (8 个 + 1 复用)

| Tool name | 类型 | 来源 entity | 参数 schema | preview? |
|---|---|---|---|---|
| `customer_priority_query` | READ | Customer + CustomerImportance | `{factoryId, includeStages: [...]}` | N |
| `wechat_record_recent_query` | READ + FILTER | WechatRecord | `{factoryId, daysSince: 7, customerId?: string}` | N |
| `wechat_record_create` | WRITE | WechatRecord | `{customerId, direction, messageContent, recordTime}` | **Y** (R4 5min dedup preview) |
| `call_record_followup_pending` | READ + FILTER | CallRecord | `{factoryId, daysSince: 7, callType: MISSED}` | N |
| `opportunity_stage_alert` | READ + FILTER | SalesOpportunity | `{factoryId, ownerId?, slaDays: 21}` | N |
| `opportunity_transition_stage` | WRITE | SalesOpportunity | `{id, newStage, reason}` | **Y** (state machine validation preview) |
| `customer_revenue_trend` | AGGREGATE | SalesOrder (existing) | `{factoryId, customerId?, periodMonths: 2}` | N |
| `processing_capacity_today` | READ | (existing) ProcessingService | (existing) | N |

**Tool implementation skeleton** (mirror `.claude/rules/ai-intent-tool-skill-architecture.md`):
```java
@Slf4j
@Component
public class WechatRecordRecentQueryTool extends AbstractBusinessTool {

    @Autowired private WechatRecordRepository wechatRecordRepository;

    @Override public String getToolName() { return "wechat_record_recent_query"; }

    @Override public String getDescription() {
        return "查询近 N 天未回的微信记录, 按客户优先级排序. " +
               "LLM 触发场景: 用户问 '哪些客户还没回微信' / '微信跟进列表' / '我的微信待办'";
    }

    @Override public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object",
            "properties", Map.of(
                "daysSince", Map.of("type", "integer", "default", 7,
                    "description", "查询多少天内的微信记录, 默认 7 天"),
                "customerId", Map.of("type", "string",
                    "description", "可选 — 仅查询某客户")
            ),
            "required", List.of()
        );
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        int daysSince = getInt(params, "daysSince", 7);
        String customerId = getString(params, "customerId", null);
        Long currentUserId = (Long) context.get("currentUserId");

        Pageable pageable = PageRequest.of(0, 50);
        Page<WechatRecord> records = customerId != null
            ? wechatRecordRepository.findByCustomerIdAndRecordTimeAfter(customerId,
                LocalDateTime.now().minusDays(daysSince), pageable)
            : wechatRecordRepository.findByFactoryIdAndAssignedSalesUserIdAndRecordTimeAfter(
                factoryId, currentUserId, LocalDateTime.now().minusDays(daysSince), pageable);

        return buildSimpleResult(
            String.format("找到 %d 条近 %d 天微信记录", records.getNumberOfElements(), daysSince),
            records.getContent().stream().map(this::toDTO).toList()
        );
    }

    private Map<String, Object> toDTO(WechatRecord r) {
        return Map.of(
            "id", r.getId(),
            "customerId", r.getCustomerId(),
            "customerName", /* join from Customer */ "",
            "direction", r.getDirection().name(),
            "messageContent", r.getMessageContent(),
            "recordTime", r.getRecordTime().toString(),
            "daysAgo", ChronoUnit.DAYS.between(r.getRecordTime(), LocalDateTime.now())
        );
    }
}
```

### P1.3 Skill 编排: `daily-customer-followup`

**SKILL.md** (定义在 `service/skill/impl/`):
```yaml
---
name: daily-customer-followup
description: 销售员每日客户跟进任务编排. 触发: 用户问 "今天该跟谁?" / "今日跟进列表" / "我的客户跟进"
category: SALES
priority: HIGH
---

steps:
  1. tool: customer_priority_query
     args: { includeStages: [QUALIFIED, DEMO, PROPOSAL, NEGOTIATE, VERBAL] }
     output_key: customers

  2. tool: wechat_record_recent_query
     args: { daysSince: 7 }
     output_key: recent_wechat

  3. tool: call_record_followup_pending
     args: { daysSince: 7, callType: MISSED }
     output_key: missed_calls

  4. tool: opportunity_stage_alert
     args: { slaDays: 21 }
     output_key: stale_opportunities

  5. tool: customer_revenue_trend
     args: { periodMonths: 2 }
     output_key: revenue_trends

aggregate:
  type: LLM_SUMMARIZE
  prompt: |
    根据以下数据, 给销售员输出"今日跟进清单":
    - 客户优先级: {customers}
    - 近 7 天未回微信: {recent_wechat}
    - 7 天内 missed call: {missed_calls}
    - 商机超 SLA 未推进: {stale_opportunities}
    - 收入趋势变化: {revenue_trends}

    格式:
    🔴 高优先: 客户 X (理由) — 建议行动 ABC
    🟡 中优先: 客户 Y (理由) — 建议行动 XYZ
    🟢 低优先: 客户 Z (理由)

    末尾加: "今日 N 客户值得跟进, 推荐先打 X 的电话"
```

**Java 注册** (`SkillRegistryImpl.java` 加 1 行):
```java
register(new DailyCustomerFollowupSkill());
```

### P1.4 Workdesk 入口 (Vue)

`web-admin/src/views/workdesk/SalesOwnerWorkdesk.vue` (新建, ~200 行):
- 顶部对话框 (复用 AIChat component)
- 默认询问 "今天该跟谁?" 自动显示
- 输出区: 客户卡片 (颜色 🔴🟡🟢 + 行动按钮 [发微信] [打电话] [更新商机])
- 行动按钮调对应 Tool

**Route**:
```ts
{ path: '/workdesk/sales-owner', component: SalesOwnerWorkdesk, name: 'SalesOwnerWorkdesk' }
```

**菜单挂载** (`layouts/menu.ts`):
```
🏪 我的工作台
  └ 销售老板工作台 → /workdesk/sales-owner
```

### P1.5 Intent DB migration

Flyway `V20260820_01__sprint8_p1_workdesk_intents.sql`:
```sql
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category,
  tool_name, skill_name, keywords, is_active, sensitivity_level, examples)
VALUES
  (gen_random_uuid(), 'DAILY_CUSTOMER_FOLLOWUP', '今日客户跟进', 'WORKDESK',
   NULL, 'daily-customer-followup',
   '["今天跟谁","今日跟进","我的客户跟进","今天该跟谁","跟进列表"]',
   true, 'LOW',
   '["今天该跟谁","我的客户跟进列表","今日要跟进哪些客户"]'),

  -- Tool-level intents
  (gen_random_uuid(), 'WECHAT_RECENT_QUERY', '查询近期微信', 'QUERY',
   'wechat_record_recent_query', NULL,
   '["微信记录","最近微信","客户微信","微信跟进"]',
   true, 'LOW', '["我最近 7 天的微信记录","查张总的微信"]'),

  -- ... 6 more for other Tools
ON CONFLICT (intent_code) DO NOTHING;
```

### P1.6 E2E test + 录屏 (验收)

**Smoke test** (Steve 亲跑):
1. F006 测试账号登录 (sales 角色)
2. 访问 `/workdesk/sales-owner`
3. 输入"今天该跟谁?"
4. 验证: AI 输出客户分组清单 + 行动按钮可点
5. 点击 [发微信] → 弹 Preview 框确认 → 创建 WechatRecord 成功

**录屏**: 5 min E2E (Boss 演示弹药 #1)

### P1.7 Phase 1 Deliverable

- [ ] 8 Tool implementations + unit test each (`*ToolTest`)
- [ ] 1 Skill + SKILL.md + Java register
- [ ] 1 Vue Workdesk view + route + menu
- [ ] 1 Flyway intent migration (8+ intents)
- [ ] E2E smoke pass (Steve F006 账号验)
- [ ] 5min mp4 录屏
- [ ] PR: `sprint8/p1-sales-owner-workdesk`

### P1.8 Dispatch 策略

1 agent dispatch (worktree isolated, 120 min budget):
- Phase A (40 min): 8 Tool implementations
- Phase B (30 min): Skill + Vue Workdesk
- Phase C (20 min): Flyway intent migration
- Phase D (30 min): Unit tests + Vue build + 自验

Brief 强制约束 (per audit lessons):
- ⛔ CRITICAL: worktree isolation (pwd verify, 无 cd to main)
- ✅ grep WechatRecord/CallRecord entity 确认字段名后再写 Tool
- ✅ 防呆 R4 (5min dedup) 必实施 for wechat_record_create
- ✅ Tool description 写得让 LLM 能正确触发 (举 3 个具体用户问法)

---

## §P2 财务主管 Workdesk (3-5d)

### P2.1 用户故事

> 财务主管刘女士月末打开 Cretas, 问 "5 月经营怎么样?" 系统输出:
> - 期间状态: PENDING_CLOSE / OPEN / CLOSED
> - 营业收入 / 成本 / 净利润 (vs 上月)
> - 工资成本拆分 (按件 / 按时 / 提成)
> - 商机漏斗值 / 应收账龄警告
> - 三大报表一键跳转

### P2.2 Tool 包装清单 (14 个)

| Tool name | 类型 | preview? |
|---|---|---|
| `account_query` | READ + FILTER | N |
| `account_tree_lookup` | READ + TREE | N |
| `period_status_query` | READ | N |
| `period_request_close` | WRITE | **Y** (changeable preview) |
| `period_confirm_close` | WRITE | **Y** (BLOCKING action) |
| `period_reopen` | WRITE | **Y** (需 reason) |
| `balance_sheet_query` | READ + AGGREGATE | N |
| `income_statement_query` | READ + AGGREGATE | N |
| `cashflow_statement_query` | READ + AGGREGATE | N |
| `wage_cost_summary` | READ + AGGREGATE | N |
| `wage_policy_query` | READ + FILTER | N |
| `opportunity_funnel_stats` | READ + AGGREGATE | N |
| `commission_pending_total` | READ + AGGREGATE | N |
| `accounts_receivable_aging` | READ + AGGREGATE | N |

### P2.3 Skill 编排: `monthly-financial-close`

```yaml
name: monthly-financial-close
description: 月度财务关账 + 经营摘要. 触发: 用户问 "X 月经营怎么样?" / "本月财务" / "月度复盘"

steps:
  1. tool: period_status_query
     output_key: period_status
  2. parallel:
     - tool: balance_sheet_query
       output_key: balance_sheet
     - tool: income_statement_query
       output_key: income_statement
     - tool: cashflow_statement_query
       output_key: cashflow
     - tool: wage_cost_summary
       output_key: wage_cost
     - tool: opportunity_funnel_stats
       output_key: funnel
     - tool: commission_pending_total
       output_key: commission_pending
     - tool: accounts_receivable_aging
       output_key: ar_aging

aggregate:
  type: LLM_SUMMARIZE
  prompt: |
    根据以下数据, 给财务主管输出"月度经营摘要":
    - 期间: {period_status}
    - 利润表: {income_statement}
    - 资产负债表: {balance_sheet}
    - 现金流: {cashflow}
    - 工资成本: {wage_cost}
    - 商机漏斗: {funnel}
    - 应付提成: {commission_pending}
    - 应收账龄: {ar_aging}

    格式:
    📊 期间状态: ...
    💰 营业收入: ... (vs 上月 ±X%)
    💸 营业成本: ... (vs 上月 ±X%)
    💵 净利润: ... (利润率 X%)
    📑 三表已生成 [资产负债表] [利润表] [现金流量表] (点击跳转)
    🔔 警告: 应收账龄超 60 天 X 家客户 ¥Y

    末尾建议: 关账状态 + 应付提成 + 警告事项
```

### P2.4 Vue Workdesk

`web-admin/src/views/workdesk/FinanceManagerWorkdesk.vue`:
- 输出区可点的"三大报表"链接 → 跳 P0.2.1 修复后的 `finance/report/index.vue`
- 应收账龄警告卡片可点 → 跳 `finance/receivable/aging`

### P2.5 P0 验证 (顺手)

P2 ship 必须验证 P0.2.1 修复成果:
- AI 输出"[资产负债表]"链接, 点击跳转必到 T3 真页面 (非 el-empty)
- 不通过则 P0 修复失败, 回退重做

### P2.6 Deliverable

- [ ] 14 Tool + unit test
- [ ] 1 Skill + SKILL.md
- [ ] 1 Vue Workdesk
- [ ] Flyway `V20260820_02__sprint8_p2_finance_intents.sql`
- [ ] 5min mp4 录屏 (Boss 演示弹药 #2)
- [ ] PR: `sprint8/p2-finance-workdesk`

---

## §P3 食品安全召回 Workdesk (5d, HJ vs Cretas 差异化护城河)

### P3.1 战略意义

这是 Cretas 永远赢 HJ 的方向:
- HJ 没食品行业垂直深度
- 食品安全召回是法定 P0
- Cretas 已有 batch_trace 单 Tool 基础, 加 HACCP+GB 2760 + 召回闭环 Skill 就成杀手锏
- Boss 演示场景: "某客户吃了拉肚子, 5 min 启动召回" — 极具张力

### P3.2 新建 entity (4 个)

#### P3.2.1 `HaccpCheckpoint`
```java
@Entity @Table(name = "haccp_checkpoints")
public class HaccpCheckpoint extends BaseEntity {
    @Id @GeneratedValue private Long id;
    @Column(nullable = false) private String factoryId;
    @Column(nullable = false) private String checkpointCode;  // e.g. "CCP-01"
    @Column(nullable = false) private String name;             // "中心温度"
    @Column(nullable = false) private String hazardType;       // "BIOLOGICAL", "CHEMICAL", "PHYSICAL"
    private String description;
    @Column(nullable = false) private BigDecimal criticalLimitMin;
    @Column(nullable = false) private BigDecimal criticalLimitMax;
    @Column(nullable = false) private String unit;             // "℃", "min", "mg/kg"
    private String monitoringProcedure;  // "每批次产品出锅前用红外测温计测量中心位置"
    private String correctiveAction;     // "中心温度不达标 → 继续加热 5 min 重测"
    private String verificationProcedure;
    private String recordKeeping;
    @Column(nullable = false) private boolean active = true;
}
```

#### P3.2.2 `HaccpMonitoringRecord` (每次实际监控记录)
```java
@Entity @Table(name = "haccp_monitoring_records")
public class HaccpMonitoringRecord extends BaseEntity {
    @Id @GeneratedValue private Long id;
    @Column(nullable = false) private String factoryId;
    @Column(nullable = false) private Long checkpointId;
    @Column(nullable = false) private String batchNumber;
    @Column(nullable = false) private LocalDateTime monitoringTime;
    @Column(nullable = false) private BigDecimal measuredValue;
    @Column(nullable = false) private Long operatorUserId;
    private boolean isDeviation;        // 是否偏离限值
    private String deviationAction;     // 偏离时的处置
    private String notes;
}
```

#### P3.2.3 `AdditiveLimit` (GB 2760 国标 seed)
```java
@Entity @Table(name = "additive_limits")
public class AdditiveLimit extends BaseEntity {
    @Id @GeneratedValue private Long id;
    @Column(nullable = false) private String additiveName;      // "亚硝酸钠"
    @Column(nullable = false) private String additiveCode;      // "INS 250"
    @Column(nullable = false) private String foodCategory;      // "08.02 熟肉制品"
    @Column(nullable = false) private BigDecimal maxLimit;
    @Column(nullable = false) private String unit;              // "mg/kg"
    @Column(nullable = false) private String regulationRef;     // "GB 2760-2014 Table A.1"
    private boolean active = true;
}
```

Flyway seed `V20260820_04__gb2760_additive_limits_seed.sql` — 30-50 个常用熟肉/卤味添加剂限量。

#### P3.2.4 `RecallEvent` + `RecallAction`
```java
@Entity @Table(name = "recall_events")
public class RecallEvent extends BaseEntity {
    @Id @GeneratedValue private Long id;
    @Column(nullable = false) private String factoryId;
    @Column(nullable = false) private String eventCode;        // "RECALL-20260801-001"
    @Column(nullable = false) private String triggerReason;    // 客户投诉 / 内部发现 / 监管通知
    @Column(nullable = false) private String affectedProductCategory;
    private LocalDateTime triggerTime;
    private Long triggeredByUserId;
    @Column(nullable = false) private String status;           // INVESTIGATING / NOTIFYING / FROZEN / REPORTED / COMPLETED
    private LocalDateTime completedAt;
    private BigDecimal estimatedLoss;
}

@Entity @Table(name = "recall_actions")
public class RecallAction extends BaseEntity {
    @Id @GeneratedValue private Long id;
    @Column(nullable = false) private Long recallEventId;
    @Column(nullable = false) private String actionType;       // FREEZE_INVENTORY / NOTIFY_CUSTOMER / REGULATORY_REPORT
    @Column(nullable = false) private String targetEntityType; // CUSTOMER / BATCH / SUPPLIER
    @Column(nullable = false) private Long targetEntityId;
    @Column(nullable = false) private String status;           // PENDING / COMPLETED / FAILED
    private LocalDateTime executedAt;
    private String executionNotes;
}
```

### P3.3 Tool 包装 (8 个)

| Tool | 类型 | preview? |
|---|---|---|
| `batch_trace_by_customer_date` | READ | N |
| `batch_full_trace` | READ + GRAPH | N |
| `haccp_checkpoint_review` | READ | N |
| `additive_compliance_check` | READ + RULE | N |
| `inventory_freeze` | WRITE | **Y** (BLOCKING) |
| `customer_notify_batch` | WRITE + 短信/微信 | **Y** (多条通知前确认) |
| `regulatory_report_generate` | READ + DOC GEN | N |
| `recall_loss_estimate` | READ + AGGREGATE | N |

### P3.4 3 Skill 编排

#### `haccp-checkpoint-management`
监控 CCP → 偏离自动告警 → 处置记录:
```yaml
trigger: 用户问 "X 批次 HACCP 通过吗?" / "今天 CCP 监控"
steps:
  1. haccp_checkpoint_review (查 batch 的 CCP 全记录)
  2. 如有 deviation: alert + correctiveAction lookup
  3. LLM 输出: 通过 / 不通过 + 建议
```

#### `food-additive-compliance`
GB 2760 限量校验 + 营养标签:
```yaml
trigger: 用户问 "X 批次添加剂合规吗?" / "X 产品营养标签"
steps:
  1. 查 batch 的 ingredients
  2. 对每 ingredient 跑 additive_compliance_check (GB 2760 lookup)
  3. LLM 输出: 合规清单 + 超限警告 + 营养标签草稿
```

#### `food-safety-recall` (主 Skill)
召回完整闭环:
```yaml
trigger: 用户说 "启动召回 X 批次" / "X 客户吃了拉肚子启动召回"
steps:
  1. batch_trace_by_customer_date (找 root batch)
  2. batch_full_trace (找原料 + 影响客户)
  3. haccp_checkpoint_review (HACCP 数据 audit)
  4. additive_compliance_check (添加剂合规复查)
  5. parallel:
     - inventory_freeze (preview)
     - customer_notify_batch (preview)
     - regulatory_report_generate
  6. recall_loss_estimate

aggregate:
  type: LLM_SUMMARIZE_DECISIONS
  prompt: |
    根据召回分析数据, 输出"召回行动方案":
    - 原料追溯: {batch_trace}
    - HACCP audit: {haccp}
    - 添加剂复查: {additives}
    - 影响客户: {customer_notify_preview}
    - 库存冻结: {inventory_freeze_preview}
    - 监管文件: {regulatory_report}
    - 预估损失: {loss}

    格式: 数据表 + 4 个一键执行按钮 [冻结库存] [通知客户] [生成监管文件] [关闭召回事件]
```

### P3.5 Workdesk Vue + Demo

`web-admin/src/views/workdesk/QualityManagerWorkdesk.vue`:
- 一键启动召回 dialog
- 输入: 客户名 / 投诉日期 / 投诉描述 → 系统自动追溯 batch → 进入召回 wizard

**Demo 场景** (Boss 演示):
- 输入: "鲜湘缘餐厅 5/18 卤猪蹄客户吃了拉肚子"
- 3 秒输出: 5/18 批次 = B-20260518-A03, HACCP audit (冷却 2h ⚠️ 超 1.5h 上限), GB 2760 复查 (合规), 影响 12 家客户 (列表), 建议 4 行动
- 一键执行: 冻结 → 通知 → 上报 → 关闭事件 (1 分钟完成 HJ 30 分钟工作)

### P3.6 Deliverable

- [ ] 4 entity + repository
- [ ] 4 Flyway: V20260820_03__haccp_checkpoints / _04__additive_limits + GB 2760 seed / _05__recall_events / _06__recall_actions
- [ ] 8 Tool implementations + unit test
- [ ] 3 Skill SKILL.md + Java register
- [ ] 1 Vue Workdesk + dialog
- [ ] Flyway `V20260820_07__sprint8_p3_food_safety_intents.sql`
- [ ] 5min mp4 录屏 (Boss 演示弹药 #3, 这是 Cretas vs HJ 的杀手锏)
- [ ] PR: `sprint8/p3-food-safety-recall-workdesk`

---

## §P4 仓管员+采购员+质量主管 Workdesk + LLM Router Tuning (5d)

### P4.1 3 Workdesk 一周齐发

#### Workdesk 4 仓管员 (per 防呆 rule "告诉他要收多少就行")
- 触发: "今天要收什么货?"
- Tool 5 个: `material_today_receiving_query` / `material_disposal_recommendation` / `receive_with_limit` (R1 max + getLimits API) / `receive_quality_check_today` / `pda_scan_task_generate`
- Vue: `WarehouseKeeperWorkdesk.vue`

#### Workdesk 5 采购员
- 触发: "下周采购什么?"
- Tool 5 个: `stock_alert` (existing) / `sales_forecast_7day` / `supplier_delivery_eta` / `price_history_query` / `requisition_create` (Preview)
- Vue: `PurchaserWorkdesk.vue`

#### Workdesk 6 质量主管
- 触发: "这批卤猪蹄能放行吗?"
- Tool 5 个: `quality_check_summary` / `haccp_status_query` (P3 已 ship) / `additive_compliance` (P3 已 ship) / `customer_quality_standard` / `release_decision` (WRITE + Preview)
- Vue: `QualityChiefWorkdesk.vue`

### P4.2 LLM Router Tuning

- 286 intent 去重 (合并同义 intent)
- 每个 intent 绑 tool_name (不再走 LLM 兜底)
- 51 → 80+ test intent (覆盖 P1-P4 全部 Workdesk 触发场景)

Flyway `V20260820_08__sprint8_p4_intent_cleanup_and_workdesks.sql`:
- 200+ INSERT for new test intents
- UPDATE 286 旧 intent 绑 tool_name

### P4.3 3 Demo 视频汇总

最后 1 天集中录:
- Boss 演示弹药矩阵 (mp4 × 5):
  - P1 卤味老板 (5min)
  - P2 财务主管 (5min)
  - P3 食品召回 (5min) ← 杀手锏
  - P4 仓管员 (3min)
  - P4 质量主管 (3min)

### P4.4 Deliverable

- [ ] 15 Tool (3 Workdesk × 5 each)
- [ ] 3 Vue Workdesk
- [ ] Intent migration + 51→80+ test intent
- [ ] 5 demo mp4 汇总
- [ ] AI 化评分追踪 update: 3 → 7-8 / 10
- [ ] PR: `sprint8/p4-multi-role-workdesks-llm-tuning`

---

## §X Cross-cutting Architecture

### X.1 Tool 包装通用 pattern (per `ai-intent-tool-skill-architecture.md`)

每个新 Tool:
1. extends `AbstractBusinessTool`
2. `@Component` 自动注册到 `ToolRegistry`
3. `getToolName()` 用 `{domain}_{action}` 格式 (e.g. `wechat_record_recent_query`)
4. `getDescription()` 写 3 个典型用户问法 (LLM 触发依据)
5. `getParametersSchema()` JSON Schema 必有 required/optional 分清
6. `doExecute()` 调 service 层, 不重复业务逻辑
7. 写操作必 override `supportsPreview()` + `doPreview()`
8. 配 unit test `*ToolTest`

### X.2 Skill 包装通用 pattern

每个新 Skill:
1. 实现 `Skill` interface 或继承 `AbstractSkill`
2. SKILL.md frontmatter (name/description/category/priority)
3. Steps 串 Tool 调用 (支持 parallel)
4. Aggregate 调 LLM 输出最终用户文案
5. `SkillRegistryImpl.java` 注册 1 行
6. 配 SkillExecutor 测试

### X.3 Workdesk Vue 通用 pattern

每个 Workdesk:
1. `web-admin/src/views/workdesk/{Role}Workdesk.vue`
2. Layout: 顶部对话框 + 中区结果卡片 + 底部一键行动
3. 复用现有 AIChat component
4. 路由 `/workdesk/{role}` + 菜单挂"🏪 我的工作台"分组
5. 路径冲突自检 (避免 P0.2.1 同类问题)
6. F006 真账号 smoke test 必过

### X.4 Preview pattern (R4 4 位一体)

写操作 Tool 必:
1. `doPreview()` 返回变更预览 (不实际写)
2. AI Workdesk 必先调 preview 给用户确认
3. 用户确认后再调 `doExecute()` 真写
4. 5min dedup window 防双击

### X.5 防呆 4 位一体 (per `.claude/rules/fool-proof-design.md`)

每个 Tool 错误处理必:
1. 后端 response.message 具体 (非 "操作失败")
2. UI toast 显后端原文
3. toast sticky (duration:0 + showClose)
4. 含 next action 提示 + actionHint URL

### X.6 数据库 Flyway 版本规划

Sprint 8 用 V20260820_01 ~ V20260820_08 (8 migrations):
- V20260820_01: P1 intent migration
- V20260820_02: P2 intent migration
- V20260820_03: P3 haccp_checkpoints + haccp_monitoring_records
- V20260820_04: P3 additive_limits + GB 2760 seed
- V20260820_05: P3 recall_events + recall_actions
- V20260820_06: P3 RecallAction relationships
- V20260820_07: P3 food safety intent migration
- V20260820_08: P4 intent cleanup + new Workdesks intent

(Sprint 7 用到 V20260720_02, 中间留缓冲)

### X.7 Agent dispatch brief 模板 (per `feedback_agent_worktree_isolation_cwd_drift`)

每个 Phase agent brief 必含开头:

```
## ⛔ CRITICAL — READ FIRST: Worktree isolation

FORBIDDEN:
- ❌ DO NOT cd C:\Users\Steve\my-prototype-logistics (main repo)
- ❌ DO NOT cd .. or paths outside your worktree
- ❌ DO NOT use absolute paths starting with C:\Users\Steve\my-prototype-logistics\backend\... for Write/Edit

REQUIRED first 2 commands:
1. pwd — confirm ends with .claude/worktrees/agent-<your-id>
2. git branch --show-current — should show worktree-agent-<your-id>

Branch from worktree: git checkout -b sprint8/<phase>-<name>

Stay in worktree throughout.
```

---

## §Y Testing Strategy

### Y.1 Unit test (per Tool / per Skill)
- 每个 Tool 配 `*ToolTest` (mvn test PASS)
- 每个 Skill 配 `*SkillTest` (mock tool registry)
- 目标: P0-P4 总新增 ~80 test, 100% PASS

### Y.2 Integration test
- 每个 Workdesk 配 1 个 integration test (启 Spring context, 跑 Tool + Skill 真链路)
- 目标: 5 Workdesk × 1 test = 5 integration test

### Y.3 E2E + Smoke test (Steve 必跑)
- 每 Phase 完 Steve F006 真账号 smoke
- 5 个 Workdesk 各录 5min mp4
- 总 mp4 ~ 25 min, 作为 Boss 演示弹药

### Y.4 AI 化评分追踪

每 Phase 完更新评分 (类似 audit):
- P0 完 → 评分 3 → 4 (信任建立)
- P1 完 → 评分 4 → 5 (1 个 Workdesk 跑通)
- P2 完 → 评分 5 → 6 (财务三件套 + Sprint 7 T3 修复)
- P3 完 → 评分 6 → 7 (食品垂直 Skill)
- P4 完 → 评分 7 → 8 (5 Workdesk 全 + LLM tuning)

---

## §Z Rollout & Validation

### Z.1 部署节奏

每 Phase ship 后立即 deploy 到 test → smoke 通过 → deploy prod (Blue-Green):
- P0 (1-2d): 1 deploy cycle
- P1 (3-5d): 1-2 deploy cycle
- P2-P4 each: 1 deploy cycle

总 deploy 次数: ~6-8 次, 每次 Blue-Green seamless

### Z.2 风险 + Mitigation

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| Agent worktree drift 再发生 | 中 | 高 | 每 brief 必含 CRITICAL section + pwd verify + 5min 内 main repo grep check |
| Tool description LLM 触发不准 | 高 | 中 | 每 Tool 写 3+ 用户问法 examples + Intent migration 显式绑 keywords |
| Skill 编排 step parallel race | 低 | 高 | 单线程串行优先, 仅 stateless query 并行 |
| Steve smoke test 不过 | 中 | 高 | 每 Phase 完先内部 Playwright smoke 验证 |
| P3 食品 entity 设计反复 | 中 | 中 | 先做 V1 简化版 (CCP 3 字段, Additive 限量库), Sprint 9 再深化 |
| GB 2760 国标 seed 不全 | 高 | 低 | V1 只 seed 卤味/熟肉 30 项, Sprint 9 加烘焙/乳制品 |
| Workdesk demo 在 Boss 演示前 demo 不稳 | 中 | 高 | 每 mp4 录 3 版 (顺利 / 边界 / 故障恢复) |

### Z.3 成功验收

Sprint 8 完整成功 = 以下全 ✅:
- [ ] 5 Workdesk demo mp4 全录
- [ ] 50+ Tool 全 unit test PASS
- [ ] 5 Skill 全 register + integration test PASS
- [ ] AI 化评分 8/10 (audit 独立验证)
- [ ] Steve F006 真账号每 Workdesk smoke PASS
- [ ] Sprint 7 T3 三大报表用户可达
- [ ] 11 占位页全 audit (delete 或 hide)
- [ ] 160+ null tool 全分类
- [ ] 102 @Deprecated 全 audit

---

## §AA Spec Self-review (per brainstorming skill)

1. **Placeholder scan**: ❌ 无 TBD/TODO/incomplete sections
2. **Internal consistency**: ✅ §0 列的 Tool 包装清单 vs §P1-P4 detail 一致
3. **Scope check**: ✅ 单 Sprint plan, 不需要进一步分解
4. **Ambiguity check**:
   - "AI 化评分 3 → 8" 衡量方法: 引用 audit doc §3 5 维度 (Tool 增量/Skill 增量/Intent 增量/自然语言可达性/跨域 Workdesk 闭环), 每维度 audit 单独打分平均
   - "Steve smoke test" 定义: F006 prod 真账号登录 + 走 demo 完整用户路径 + 通过= 输出符合预期

---

**Spec 完成 (~1000 行). 等 Steve review 后调 writing-plans skill 写 P0-P4 detailed task plan**。
