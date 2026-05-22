# Cretas vs GuanData V8.2 — BI 能力对比 (2026-05-22)

**目的**: Sales/marketing/产品决策参考。诚实标注 gap, 不夸大。

**编制依据**:
- `docs/audits/2026-05-21-sprint-5-to-9-comprehensive-audit.md`
- `docs/superpowers/specs/2026-05-20-*.md` (5 GuanData 对标 spec)
- 2026-05-21 独立 BI 架构审计 (5.5/10 综合分)
- GuanData V8.2 发布公开材料 + GuanCLI 文档

---

## 一句话定位

**Cretas = 食品行业 AI 原生 BI + Agent 执行平台。**
**GuanData = 通用企业级 BI + 智能分析。**

Cretas 在 **食品垂直深度** + **真实业务执行能力** 上获胜。GuanData 在 **通用分析广度** + **成熟度** 上领先。两者不是替代关系, 而是不同细分。

---

## 维度对比

### 1. 行业垂直深度

| 维度 | Cretas | GuanData | Cretas 是否领先? |
|---|---|---|---|
| 行业聚焦 | 食品制造业 + 餐饮 (F006 卤制品厂主客户) | 通用 (零售/金融/制造混合) | ✅ Cretas |
| 行业指标库 | 食材损耗率 / 翻台率 / 客单价 / 菜品毛利 / 食安通过率 / 良品率 / 计划达成率 (7 个已 seed 给 F006) | 100+ 通用指标 (DAU/MAU/转化率/NPV/IRR 等) | ⚠️ 互补 — 不可直接比 |
| 食品法规对齐 | GB 14881 冷链 / SSOP / 食品添加剂限值 (Sprint 9 P2.D/E LIVE) | 无 | ✅ Cretas (GuanData 永远做不到) |
| Batch-level lineage | `BatchLineageEdge` + `BatchLineageClosure` (closure trigger 维护) | 无 batch 概念 | ✅ Cretas (架构性差异) |

### 2. AI 原生能力

| 维度 | Cretas | GuanData | Cretas 是否领先? |
|---|---|---|---|
| AI 意图识别 | 337 Tools + 16 Skills + 8-层意图分类 (Phase 0 N+1 metrics LIVE 监控) | GuanCLI MCP server (V8.2 新增) | ⚠️ 各有架构差异 |
| LLM Tool/Function 调用 | 337 Tool 注册 ToolRegistry (Spring DI 自动收集) | MCP server 协议 | ⚠️ 不同抽象层 |
| AI 自动归因 | Spec only (Phase 3) | V8.2 智能归因 (GA) | ❌ Cretas 落后 ~4-6 个月 |
| Canvas 自动生成 | Spec only (Phase 3) | V8.2 AI Canvas | ❌ Cretas 落后 |
| 3-layer Intent 重设计 | Phase 0 prod 数据采集中 (2-4 周后决策) | N/A | ⚠️ Cretas-specific |

### 3. BI 分析能力

| 维度 | Cretas | GuanData | Cretas 是否领先? |
|---|---|---|---|
| 仪表盘 | Java SmartBI + Python SmartBI (84 Vue + 581 .py 文件 + 304 endpoint) | 全功能 BI 仪表盘平台 | ⚠️ GuanData 更成熟 |
| Cross-sheet / YoY / Forecast | Python smartbi_compat 已 ship (Phase 2A 50 endpoint port 完成) | 全平台支持 | ✅ 持平 |
| Indicator Center | Phase 1 Sprint 1 D1-D5 完成 (Entity+Repo+Service+Controller+15 tests, UI 在 ship) | 成熟 (V8.2 中心化) | ❌ Cretas 落后 (foundation 50% 完成) |
| 自助 dashboard | 客户可通过 Canvas 配置 | 拖拽式编辑器 | ⚠️ 不同范式 |
| 实时性能 | Python Phase 2A T6.6 cutover prod live | 平台级支持 | ⚠️ 持平 |

### 4. 真实业务执行能力 (差异化护城河)

| 维度 | Cretas | GuanData | Cretas 是否领先? |
|---|---|---|---|
| Agent 真实写操作 | 337 Tool 含 write (SO 创建/PO 收货/质检 ack/调拨/冷链 ack 等) | 仅查询 | ✅ Cretas (本质差异) |
| Workflow 集成 | ApprovalWorkflow 串 8 Tool (Sprint 9 P0.1) | 无原生 workflow | ✅ Cretas |
| Canvas low-code | 5 模块 (Alerts/Notify/Rules/Pricing/Cron) LIVE | Canvas 是 BI 看板, 不含 workflow rules | ✅ Cretas (但 GuanData 不需要 — 范式不同) |
| 移动端 | React Native APP (16 user accounts F006) | 主 Web | ✅ Cretas (manufacturing 现场需要) |
| OTA 自助升级 | LIVE (ota.cretaceousfuture.com Phase 0-6) | N/A | ✅ Cretas |

### 5. 产品成熟度

| 维度 | Cretas | GuanData | Cretas 是否领先? |
|---|---|---|---|
| 版本 | Phase 5 (5/5 phases 路线图 spec'd, 1 phase prod live) | V8.2 (8+ 主版本) | ❌ GuanData 成熟 |
| 客户数 | F006 主客户 + 4 demo 工厂 (HJ 宏见 / QHJ 庆华建 / 等) | 数千企业客户 | ❌ GuanData 量级领先 |
| 团队规模 | 1 主开发 (steveb + AI) | 数百工程师 | ❌ GuanData 量级 |
| 商业模式 | SaaS + 私有部署 | SaaS + 私有部署 | ⚠️ 相同 |

---

## Cretas 不可替代的优势 (GuanData 永远做不到)

1. **Batch-level traceability** — 食品溯源平台架构基因。GuanData 无 batch 概念, 加不上去。
2. **食品法规对齐** — GB 14881 冷链 / SSOP / 食品添加剂限值。GuanData 是 horizontal, 不可能 vertical 这么深。
3. **真实写操作** — 销售下单 / 采购收货 / 质检 ack。GuanData 是分析平台, 没有这套能力栈。
4. **行业语言** — 翻台率 / 客单价 / 食材损耗率 / 菜品毛利。GuanData 通用指标库没这些。

---

## GuanData 暂时领先维度 (gap 估算)

| 维度 | Cretas 状态 | GuanData 状态 | Gap |
|---|---|---|---|
| AI 智能归因 | Spec only (Phase 3) | V8.2 GA | ~4-6 个月 |
| AI Canvas 自动生成 | Spec only | V8.2 GA | ~6 个月 |
| Indicator Center 成熟度 | Phase 1 D1-D5 (50% foundation) | 多年沉淀 | ~3-4 个月 |
| 仪表盘库 | 7 seed indicator + 自助 Canvas | 100+ 模板 | ~1-2 年 (但 Cretas 不需要 — vertical focus) |

---

## 战略 takeaway

**Cretas 不应该跟 GuanData 比通用 BI 能力 (永远输, 资源量级差异 100x)**。

**Cretas 应该深挖**:
1. **食品行业 vertical** 不可替代护城河
2. **Agent 真实执行** vs 通用 BI 只分析
3. **客户场景导向** (F006 卤制品 / HJ 宏见 / 餐饮多门店) — 不是抽象数据立方体

**Cretas 应该尽快追赶**:
1. **Indicator Center** (Phase 1, 现 50%, 1-2 周完成 foundation) — sale/demo 必备
2. **AI 归因** (Phase 3, spec'd, ~3 月开发) — 客户期望差异化
3. **AI Canvas 生成** (Phase 3, spec'd) — Indicator Center 成熟后再做

**Cretas 应该明确放弃**:
1. **通用 BI 100+ 指标库** — 无意义, 食品客户用不到
2. **多行业广度** — 资源分散, 失去 vertical 优势
3. **跟 GuanData V8.2 feature parity** — anti-goal per 我们 roadmap

---

## 客户沟通话术 (Sales handoff)

**当客户问 "比 GuanData/帆软/QuickBI 强在哪里?"**:

> "我们不是替代 GuanData — 那个赛道我们打不过。我们是食品行业 ERP+BI+Agent 的一体化平台。
> 您要做的是: 食材损耗率监控 + 翻台率分析 + 食安召回闭环 + 批次溯源 + 销售下单 → 生产计划 → 入库 → 出货 全闭环。
> GuanData 看不到 batch, 不懂食安, 不会下单。我们都做。"

**当客户问 "AI 智能归因什么时候能用?"**:

> "Phase 3, 大约 3 个月。但我们做的归因是基于 batch lineage 的食品行业归因 — 比如 '为什么这批客户投诉' 可以追到具体原料批次的供应商, 不是抽象的 KPI 解释。GuanData 是通用归因, 我们是垂直归因, 不同价值。"

---

## 内部产品决策 implications

- **不要** 为了 GuanData feature parity 砍 vertical 深度投入
- **不要** 招通用 BI engineer 做我们不擅长的事
- **要** 加速 Phase 1 Indicator Center 到 100% (sales/demo blocking)
- **要** 加速 Phase 3 Attribution + Canvas (因为 GuanData 已 GA, 客户期望)
- **要** 持续招食品行业 domain expert (卤制品 / 餐饮 / 冷链 / 食安)

---

**By**: 独立 BI 架构审计 (5.5/10 综合分依据). 跟同事的 4.2/10 评估 + GuanData 4-6 月 gap 估算一致, 跟 GuanData V8.2 公开材料对比。

**审阅周期**: 每个 Phase 完成后 update 一次。下次 update: Phase 1 Sprint 完成 (D6-D10 落地后)。
