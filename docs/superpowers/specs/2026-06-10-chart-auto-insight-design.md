# 全图表自动洞察 (Chart Auto-Insight) — 设计文档 v2 (对抗审计后定稿)

**日期**: 2026-06-10
**状态**: v2 — 已过 4-critic superpowers 对抗审计, 4 个 BLOCKER 修复已并入, 待 Steve 终审
**触发**: Steve 硬性需求 — 餐饮+工厂所有图表(生成式+固定)都带数据驱动洞察; 不全靠 LLM(多模态读图最贵), 以图表背后**数据**做分析; 建"图表可能性分析库"落成固定回答; 用自学习系统做 LLM 蒸馏。**同时是参赛 demo 现场演示的 AI 能力**(6/13)。

## 决策记录 (Steve 拍)
1. **范围** = **全闭环现建** (Steve 坚持; 审计警示 3 天偏险, 已知情决策 → 必须建对, 不建天真版)
2. **深度** = 可配置 (一次产出结构化 `{发现, 含义, 建议}`, UI 两档渲染)
3. **架构** = 混合 (前端 rules-first 瞬时 + 后端 Python 蒸馏服务)

## 审计裁决 (4-critic 收敛, 4 BLOCKER 已修)
| ID | 审计发现 | 本 v2 修复 |
|---|---|---|
| B1 | 前端无图表语义元数据(只有 ECharts options, fieldMappings 被剥) | 后端附 `_meta:{xDim,yMetric,agg,domain}` 随图表响应下发 (见 §2.5) |
| B2 | 模板参数化空白(LLM 出散文非可填模板) | Tier2b LLM **返结构化 JSON** `{finding_tpl, implication_tpl, suggestion_tpl, slots}`, 生成时即参数化 (见 §2.2) |
| B3 | RBAC 泄露(洞察嵌 ¥ 绕脱敏; 跨角色模板命中) | 权限门控 ¥值 + permissionTier 进签名 + Python 从 JWT 取 factoryId/role (见 §2.6, **红线**) |
| B4 | dataPattern 保证不了正确性 + 建议瞎归因 | 模板**纯参数化纯关系型**(不烧方向/因果) + 建议观察动词白名单 + 量化子桶 (见 §2.1/§2.3) |

---

## 1. 架构 — 三层 resolver + 蒸馏闭环

```
图表渲染 → 用 chart._meta(后端下发的语义) + seriesData 构 InsightContext
  │
  ├ TIER 1 — 前端 chartInsight.ts (0-token 瞬时, **替换**现有 getChartMiniInsight)
  │    模式族识别(用 _meta) → 确定性生成器 → InsightResult{finding, implication?, suggestion?} | null
  │    满足最小数据契约才出, 否则 null
  │    ↓ (null/复杂 → 需更强分析)
  ├ TIER 2 — 后端 Python ChartInsightService  POST /api/smartbi/chart-insight (JWT 鉴权)
  │    signature = SHA256(chartType|xDim|yMetric|agg|domain|dataPattern|permissionTier|factoryId)
  │    2a 库查 ai_insight_templates(is_active) 命中 → **按权限**填占位符 → 固定答案(0 LLM)
  │    2b 未命中 → 预算护栏(AgentBudgetTracker) → LLM **返结构化模板 JSON**
  │       → 捕获 upsert(signature, 结构化模板, source=LLM_FALLBACK, proposal_count++, is_active=false)
  └ TIER 3 — 蒸馏提升 (复用 ExpressionLearning 飞轮 pattern, Python 镜像)
       proposal_count ≥ PROMOTE_THRESHOLD(**可配置/factory**, 演示=1, prod 默认=3) AND 过强化防毒守卫
       AND 模板已全参数化(validate_template_parameterization) AND (含 suggestion → is_verified 或仅 finding 自动提升)
       → is_active=true → 下次同签名走 2a
```

**成本曲线**: LLM 调用随蒸馏库增长单调↓ → 趋近 0。

### 现场 demo 钥匙 (可配阈值)
演示租户 `PROMOTE_THRESHOLD=1`: 首看图 → 2b LLM 现算(慢, 徽章"AI 生成") → 立即捕获+提升 → 刷新 → 2a 命中(瞬时, 徽章"数据驱动·已学习") → **15 秒真实演出闭环**。阈值是合法可调参数(低阈=学快), 非造假。

---

## 2. 关键设计点

### 2.1 签名 + dataPattern 量化子桶 (修 B4)
签名特征(全部从 `_meta` + seriesData 确定性抽取):
`chartType | xDim(time/store/product/channel/category) | yMetric(revenue/qty/margin/...) | aggregation | domain | dataPattern | permissionTier | factoryId`

**dataPattern 量化子桶**(防"上升模板套先涨后崩"):
- 趋势: 单调上升 / 单调下降 / 先升后降 / V型 / 平 (用 monotonicity 非仅 first-vs-last) + 波动档(σ/μ low/high)
- 分布: top-share 桶(50-65/65-80/80+) + 类目数档(2-3 / 4-8 / 9+)
- 异常: 有/无 + 偏离量档(轻/中/重)

> 即便子桶, **正确性最终靠"模板纯关系型 + 应用前轻量校验"**(见 2.2/2.7), 不靠 dataPattern 穷举。

### 2.2 模板参数化 — LLM 生成时即结构化 (修 B2)
Tier2b LLM **不返散文**, 返 JSON:
```json
{ "finding_tpl": "营收最高 {topName} 占 {topShare}%, 是末位 {botName} 的 {ratio} 倍",
  "implication_tpl": "头部集中度{concLevel}", "suggestion_tpl": "建议关注 {topName} 与末位的结构差异",
  "slots": ["topName","topShare","botName","ratio","concLevel"] }
```
- proposal_count 数的是**结构相同**的模板(按 finding_tpl 规范化哈希去重), 多数投票决定提升哪个
- `validate_template_parameterization()`: 拒绝含字面店名/品名/绝对数字的模板(必须全 `{slot}`)

### 2.3 五族 rules + 观察动词白名单 (修 B4 归因)
| 族 | 触发(_meta) | 最小数据契约 | 骨架 |
|---|---|---|---|
| TREND | LINE+time | ≥4 点 | 涨跌幅+monotonicity + 含义 |
| RANKING | BAR+类目 | ≥2 且有可测差 | 头尾倍差+头部占比 |
| PROPORTION | PIE | ≥2 切片 | 主导占比+长尾 |
| COMPARISON | 双系列 | 2 可比系列 | 差异方向+幅度 |
| KPI | 单值/仪表 | 必带 actual+target 或 上期 | 达成度+对比 |
- **建议只用观察动词**(关注/排查/分析/了解), **禁因果处方动词**(复制/引流/加大/扩张/推广)
- 不满足契约 → 严格 null(不编)

### 2.4 复用现有基建 (审计校正)
| 复用 | 判定 | 用法 |
|---|---|---|
| ExpressionLearning 飞轮 pattern | ✅ 镜像(独立域, 独立表) | proposal_count→promote→is_active 逻辑 + PROMOTE_THRESHOLD 常量 |
| `AgentBudgetTracker` | ✅ 直接复用(无耦合) | Tier2b LLM 预算; 注: 与 chat 共享 factory 日预算 |
| `statistical.py` INSIGHT_TEMPLATES | ⚠️ 仅复用模板**字符串** | 不调 `generate_statistical_insights(df)`(输入是 df 非 series) |
| `narrative_cache` | ❌ **不复用**(键/失效语义不符) | Tier2 缓存=ai_insight_templates 表本身(is_active 即缓存) |
| `getChartMiniInsight()` | ⚠️ **替换非叠加** | 新 chartInsight.ts 取代它, 单一 Tier1 |
| `revenueInsight.ts` | ✅ 诚实 null 模式参考 | 泛化为 chartInsight.ts |
| `smart_bi_distillation_samples` 表(已存在) | 区分 | 那是 LoRA 语料, ≠ 本 active-template 库; 但可作蒸馏故事佐证 |

### 2.5 后端语义元数据下发 (修 B1)
图表生成时, 后端从 `DynamicChartConfig.fieldMappings`(FieldMappingWithChartRole: role/chartAxis/standardField/aggregationType) 派生 `_meta:{xDim,yMetric,aggregation,domain}` 附在图表响应里; 前端类型扩 `chart.meta?`. **无此前端 Tier1 无法识别族 = B1 BLOCKER 的根治**。~1-2h 后端。

### 2.6 RBAC 安全 (修 B3 — 🔒 红线, 不可妥协)
- **值门控**: 含 finance 性质的 yMetric(revenue/margin/profit/cost), 洞察**默认只出比率/百分比**; 仅当 caller 持 `finance:read_write` 才出绝对 ¥值
- **签名含 permissionTier**(finance_visible/price_hidden/finance_hidden) → 模板不跨权限边界
- **Python 端点从 JWT 取 factoryId + role**(Java 转发或 nginx 注 header), **不信请求体**的 factoryId → 防跨租户读模板
- 模板按 `required_permission` 标记; Tier2a 填充前校验 caller 权限, 不足→降级到无绝对值变体
- 前端 Tier1 同样接 `userPermissions` prop 门控 ¥值

### 2.7 应用前轻量校验 (兜底正确性)
Tier2a 填模板前跑一遍 rules 断言校验: 模板隐含的方向断言(若有)在新数据上是否仍成立; 不成立→不用该模板, 落 Tier2b/Tier1。(纯关系型模板天然恒真, 此校验主要兜 implication 层)

---

## 3. 组件 / 文件
### 前端
- `smart-bi/components/chartInsight.ts`(五族, 接 _meta+permissions, 替换 getChartMiniInsight)
- `smart-bi/components/ChartInsight.vue`(简洁/详细 + 徽章"数据驱动"/"已学习"; **不用"已蒸馏"**避免信任膨胀)
- 挂 `ChartGridItem.vue`(已有 miniInsight prop, 低风险)
### 后端 Python
- `services/insights/chart_insight_service.py`(签名/库查/rules/LLM结构化/蒸馏提升/权限填充)
- `api/chart_insight.py`(`POST /api/smartbi/chart-insight`, JWT)
- `database/migrations/V20260927_01__ai_insight_templates.sql`(**号必 >现有最高 V20260926_01**, 走 smartbi runner)
### Java
- 图表响应附 `_meta`(从 fieldMappings 派生) — `DynamicChartConfigBuilder` 附近
### DB (smartbi_db/prod)
`ai_insight_templates`: id, factory_id, signature_hash(idx), chart_signature(JSONB), insight_template(JSONB 参数化), required_permission, source_type, confidence, hit_count, proposal_count, is_active, is_verified, created_at, updated_at; UNIQUE(signature_hash, factory_id)

---

## 4. 测试
- 前端 `chartInsight.spec.ts`: 五族正例+null 契约+模板填充+**断言无因果处方词** `/复制|引流|加大|扩张|推广/`
- 后端: 签名确定性 / 库命中-未命中 / proposal 同结构投票 / 阈值提升 / validate_template_parameterization / 防毒 / **RBAC(低权角色不得绝对¥值, 跨租户拒绝)** / 预算降级

---

## 5. 错误处理 / 诚实 (修 B4 filler 张力)
- 各层数据不足→null→不显(禁降级); Tier2b LLM prompt 显式: "数据不足以得出有意义业务观察 → 返 {finding:null,...}, 不编"
- demo 预筛能出非空洞察的图(不假装每图必有)
- 强化防毒: 黑名单 + 因果处方词正则 + suggestion 非空模板需 is_verified 才自动提升(仅 finding 模板可纯自动)

---

## 6. 实施节奏 (建对→测→迭代; 时间充裕, 不强塞 3 天 — Steve 定调)
**哲学**: 审计已给全部修复, 烧进去后**建→test→改优化迭代到对**, 不在上游求一次完美。deck 已完工, 算力释放。

**Phase 1 — 最小可演闭环 (参赛 demo 优先, 早 ready 早演)**:
_meta 后端下发(B1) + chartInsight.ts TREND+RANKING(前端瞬时洞察) + 迁移 V20260927_01 + Tier2b LLM 结构化 + 捕获 + Tier2a 命中 + 演示租户阈值=1(现场真演闭环) + RBAC 填充 + 成本曲线 slide。
→ 6/13 参赛用这刀(餐饮+工厂主图有洞察 + 一张图现场演 LLM→学习→瞬时)。

**Phase 2 — 全族 + 正确性硬化 (test 驱动迭代)**:
余 3 族(PROPORTION/COMPARISON/KPI) + dataPattern 量化子桶 + validate_template_parameterization + 应用前校验 + 防毒强化 + is_verified 门 + 全测试套件。**跑真实数据 test → 抓错洞察 → 修 → 再测**, 迭代到稳。

**Phase 3 — 铺开**: 全部 ~50-100 图表实例 + 跨 factory 模板共享 + 语义签名匹配。

> 派发: 前端(Composer/Sonnet) ‖ 后端 service(Sonnet in-harness) ‖ Java _meta(Sonnet) 并行; RBAC/迁移/部署 Opus 终审从 main。每 Phase 后 Steve 验 + 迭代。

---

## 7. 🔒 红线 (Opus 终审, 不可外包)
- **RBAC/脱敏(§2.6)** — 数据泄露红线, 必经 Opus 终审 (项目史: BUG-01-RBAC/营收脱敏)
- DB 迁移 V20260927_01 — Flyway/smartbi runner 撞号(合并前再查 origin/main), Opus gate
- 多租户隔离(signature+JWT factoryId) — Opus 终审
- prod 部署(web-admin+Python+Java) — 从 main, Opus gate
- 蒸馏防毒守卫 + is_verified 门 — Opus 审(防错洞察永久入库)

---

## 8. 残留风险 (Steve 知情; 时间充裕 → 靠 test+迭代化解, 非一次到位)
- 正确性(B4 错洞察)靠 Phase 2 真实数据 test 迭代抓修, 不假设上游一次穷举对。
- 现场 LLM 调用依赖 DashScope 可用 + 预算; demo 前确认。
- 截图返工: demo 看板加洞察条后若要进 deck 需重截(或加专门 AI 洞察页) — Phase 1 末按需。
- RBAC(§2.6) 是唯一"必须一次做对"的(数据泄露不可迭代试错) → Opus 终审死守。
