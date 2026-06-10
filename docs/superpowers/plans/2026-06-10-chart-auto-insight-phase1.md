# Chart Auto-Insight — Phase 1 (最小可演闭环) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (in-harness Sonnet, fresh subagent per unit, Opus two-stage review). Steps use `- [ ]`. **Workers have the spec + `.claude/rules` + codebase** — implement via TDD against the spec contracts below; don't re-derive decisions.

**Goal:** 餐饮+工厂演示看板每张主图显示数据驱动洞察(Tier1 瞬时), 新图现场调 LLM 产结构化模板并蒸馏入库(阈值=1 演示租户), 刷新即模板命中瞬时 — 现场真演完整闭环。

**Architecture:** 混合三层 — 前端 `chartInsight.ts` rules(0-token, 读后端下发的 `_meta` 语义) + Python `ChartInsightService`(签名→库查→LLM结构化→蒸馏提升, JWT 鉴权, RBAC 门控) + `ai_insight_templates` 表(复用 ExpressionLearning 飞轮)。

**Tech Stack:** Vue3/TS(web-admin) · FastAPI/asyncpg(Python smartbi 8083) · Spring Boot(Java _meta) · PostgreSQL(smartbi_db) · DashScope LLM。

**Spec:** `docs/superpowers/specs/2026-06-10-chart-auto-insight-design.md` (v2, 审计后定稿 — 含 B1-B4 修复 + §2.6 RBAC 红线)。

---

## 文件结构 (各文件单一职责)
| 文件 | 职责 | 单元 |
|---|---|---|
| `backend/java/.../service/smartbi/impl/DynamicChartConfigBuilder.java` (改) | 图表响应附 `_meta{xDim,yMetric,aggregation,domain}` (从 fieldMappings 派生) | U1 |
| `web-admin/src/views/smart-bi/components/chartInsight.ts` (新) | Tier1 五族 rules(Phase1: TREND+RANKING), 读 _meta+permissions, 返 InsightResult|null | U2 |
| `web-admin/src/views/smart-bi/components/ChartInsight.vue` (新) | 展示(简洁/详细 + 徽章), 异步槽接 Tier2 | U2 |
| `web-admin/src/views/smart-bi/SmartBIAnalysis.vue` (改) | 用 chartInsight.ts **替换** getChartMiniInsight; 挂 ChartInsight.vue | U2 |
| `backend/python/smartbi/database/migrations/V20260927_01__ai_insight_templates.sql` (新) | 模板库表 | U3 |
| `backend/python/smartbi/services/insights/chart_insight_service.py` (新) | 签名/库查(2a,权限填)/LLM结构化(2b)/捕获/蒸馏提升(可配阈值) | U4 |
| `backend/python/smartbi/api/chart_insight.py` (新) | `POST /api/smartbi/chart-insight` (JWT, factoryId/role 从 token) | U4 |
| demo 看板挂载 + 成本曲线 slide + 联调 | 集成 | U5 |

---

## 契约 (锁定决策 — 所有单元遵守)

**InsightResult (前后端一致)**:
```ts
interface InsightResult { finding: string; implication?: string; suggestion?: string;
  source: 'rules' | 'template' | 'llm'; tier: 1 | 2; }   // null = 数据不足, 不显
```
**_meta (U1 下发, U2 消费)**:
```ts
interface ChartMeta { xDim: 'time'|'store'|'product'|'channel'|'category'|'other';
  yMetric: 'revenue'|'quantity'|'margin'|'cost'|'count'|'pct'|'other';
  aggregation: 'sum'|'avg'|'max'|'count'; domain: 'restaurant'|'factory'|'finance'; }
```
**签名 (U4)**: `SHA256(chartType|xDim|yMetric|aggregation|domain|dataPattern|permissionTier|factoryId)`
**Tier2b LLM 必返结构化** (非散文): `{finding_tpl, implication_tpl?, suggestion_tpl?, slots[]}` 占位符版。
**RBAC (U2+U4, 🔒)**: finance 性质 yMetric → 默认只比率/%; 仅 `finance:read_write` 出绝对 ¥; Python 从 JWT 取 factoryId/role(非请求体); 模板 required_permission 标记+填充前校验。
**建议动词白名单**: 关注/排查/分析/了解; **禁** 复制/引流/加大/扩张/推广。
**阈值**: `PROMOTE_THRESHOLD` per-factory 配置, 演示租户=1, prod 默认=3。

---

## U1 — Java _meta 下发 (B1 根治, 前端依赖此)
**Files:** Modify `DynamicChartConfigBuilder.java` (+ DTO 加 `meta` 字段); Test: 对应 builder test
- [ ] 写失败测试: 给定带 fieldMappings(role=TIME/DIMENSION/METRIC, standardField, aggregationType)的图表配置 → builder 输出含 `meta.xDim/yMetric/aggregation/domain` 正确派生(TIME→time, standardField 含 revenue→revenue, factory businessType→factory)
- [ ] 跑测试确认失败
- [ ] 实现: fieldMappings → ChartMeta 映射(standardField 关键词→语义 + role→xDim/yMetric + factory businessType→domain); 附到图表响应 DTO
- [ ] 跑测试 PASS + `mvn test-compile`
- [ ] commit (scope: 2 文件)
**验收:** 前端拿到的图表对象含 `meta`; 派生正确(单测覆盖 time/store/product × revenue/qty)。无 _meta 则 Tier1 无法识别族。
**并行:** ✅ 与 U3 独立。**Opus 审:** 否(无红线)。

## U2 — 前端 Tier1 + 组件 (依赖 U1)
**Files:** Create `chartInsight.ts` + `ChartInsight.vue`; Modify `SmartBIAnalysis.vue`; Test: `chartInsight.spec.ts`
- [ ] 写失败测试 `chartInsight.spec.ts`(镜像 revenueInsight.spec.ts): TREND(≥4点上升→涨幅 finding)/RANKING(≥2有差→倍差+占比)/null契约(<契约返null)/RBAC(无finance权限→无绝对¥只%)/**断言无因果词** `/复制|引流|加大|扩张|推广/`
- [ ] 跑测试失败
- [ ] 实现 chartInsight.ts: 读 chart.meta 识别族 → TREND/RANKING 生成器 → InsightResult|null; 接 userPermissions 门控¥; 观察动词建议
- [ ] ChartInsight.vue: 简洁=finding / 详细=三段; 徽章"数据驱动"(非"已蒸馏"); 异步槽(Tier1 null 时占位待 Tier2)
- [ ] SmartBIAnalysis.vue: **删 getChartMiniInsight 调用, 换 chartInsight**; 挂 ChartInsight.vue
- [ ] 跑测试 PASS + `npm run build` + vue-tsc
- [ ] commit
**验收:** 演示看板 BAR/LINE 图下显结构化洞察; 无 finance 权限不显绝对¥; 测试断言无因果词。
**并行:** 依赖 U1。**Opus 审:** 是(RBAC 值门控 + 替换 getChartMiniInsight 无回归)。

## U3 — DB 迁移 (🔒 迁移红线)
**Files:** Create `V20260927_01__ai_insight_templates.sql` (**号必 >现有最高 V20260926_01, 合并前再查 origin/main**)
- [ ] 写迁移: 表 ai_insight_templates(id, factory_id, signature_hash idx, chart_signature jsonb, insight_template jsonb, required_permission, source_type, confidence, hit_count, proposal_count, is_active, is_verified, created_at, updated_at, UNIQUE(signature_hash,factory_id))
- [ ] test 环境 apply 验证(deploy-smartbi-python.sh --env test 的 runner)
- [ ] commit
**验收:** test 库表存在; runner apply success。**并行:** ✅ 与 U1 独立。**Opus 审:** 是(Flyway 撞号 + schema)。

## U4 — 后端 ChartInsightService + 端点 (🔒 RBAC 红线, 依赖 U3)
**Files:** Create `chart_insight_service.py` + `api/chart_insight.py`; Test: `tests/test_chart_insight_service.py`
- [ ] 写失败测试: 签名确定性(同输入同hash)/库命中2a(填占位+权限校验)/未命中2b(LLM结构化 mock)/捕获 proposal_count++/提升(count≥阈+全参数化+防毒)/**RBAC(低权角色拒绝绝对¥; 请求体 factoryId 被 JWT 覆盖; 跨租户拒)**/预算降级(budget blocked→null)
- [ ] 跑测试失败
- [ ] 实现 service: compute_signature(含 dataPattern 基础桶 + permissionTier) / lookup(2a, 权限填充) / llm_structured(2b, 返 {finding_tpl,...,slots}, 复用 AgentBudgetTracker) / capture(upsert) / maybe_promote(可配阈值 + validate_template_parameterization + 防毒正则 + suggestion需is_verified) ; **不复用 narrative_cache**(库表即缓存)
- [ ] 端点 chart_insight.py: JWT 提 factoryId+role(非请求体), 调 service
- [ ] 跑测试 PASS
- [ ] commit
**验收:** test 环境端点跑通; RBAC 测试全绿(低权无¥/跨租户拒); 阈值=1 时一次捕获即提升。
**并行:** 依赖 U3。**Opus 审:** 是(RBAC + 端点鉴权 + 防毒 + 蒸馏入库, 全红线)。

## U5 — 集成 + demo + 联调 (依赖 U2+U4)
- [ ] 演示租户 PROMOTE_THRESHOLD=1 配置
- [ ] demo 看板(餐饮 R_SSW_DEMO + 工厂 F_CLY_DEMO)挂 ChartInsight, Tier1 命中即显
- [ ] 现场闭环联调: 新签名图 → 2b LLM(徽章"AI生成") → 捕获+提升 → 刷新 → 2a 命中(徽章"已学习"瞬时) — 录 15s 演示
- [ ] 成本曲线 slide(rules-first+蒸馏 LLM 调用↓)
- [ ] (按需)重截带洞察条的 demo 截图进 deck
**验收:** 现场 15s 真演闭环; 餐饮+工厂主图有洞察。**Opus 审:** 是(上线前终审 + 从 main 部署)。

---

## Self-Review
- **Spec 覆盖:** B1→U1, Tier1五族(TREND+RANKING Phase1)→U2, 表→U3, 签名/2a/2b/蒸馏/RBAC/阈值→U4, demo/成本曲线→U5。Phase1 全覆盖; PROPORTION/COMPARISON/KPI + dataPattern 量化子桶 + is_verified 完整 = Phase2(spec §6, 非本计划)。✅
- **占位符:** 无 TBD; 契约/文件/验收具体。实现细节由 in-harness worker TDD(有 spec)。✅
- **类型一致:** InsightResult/ChartMeta/签名 跨单元一致。✅
- **红线:** U3(迁移)/U4(RBAC+鉴权)/U5(部署) 全标 Opus 终审。✅

## 🚦 分发总览 (并行 + scope 锁)
| 单元 | 模型 | 并行 | scope 锁 | 🔒 |
|---|---|---|---|---|
| U1 Java _meta | Sonnet in-harness | ✅ (与U3) | DynamicChartConfigBuilder + DTO | |
| U3 迁移 | Sonnet in-harness | ✅ (与U1) | smartbi migrations | 🔒 |
| U2 前端 | Sonnet/Composer | 依赖U1 | chartInsight.ts/ChartInsight.vue/SmartBIAnalysis.vue | 🔒RBAC |
| U4 后端 | Sonnet in-harness | 依赖U3 | chart_insight_service.py/api/chart_insight.py | 🔒RBAC+鉴权 |
| U5 集成 | Opus 编排 | 依赖U2+U4 | demo 看板 + slide | 🔒上线终审 |
> 隔离: 各 worktree off origin/main; commit 锁 scope; prod 从 main, Opus gate。
