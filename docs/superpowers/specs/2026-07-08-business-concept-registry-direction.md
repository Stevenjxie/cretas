# 统一业务概念注册表 — 方向 spec（供拍板，不含实施）

**日期**: 2026-07-08
**触发**: 语义层五共识 #1（立足业务概念）对照现状暴露的结构性问题 + Phase 2 委托 gate 的根因复盘
**输入**: 四套词汇表现状盘点（2026-07-08 Explore 报告，要点内联于 §1）
**性质**: 方向性文档。给出问题定义、两个候选形态、推荐路径和不做清单；实施需单独 spec + 排期。

---

## 1. 问题：同一批业务概念，五份互不知晓的定义

| # | 词汇表 | 规模 | 定义处 | 消费方 | 维护方式 |
|---|---|---|---|---|---|
| 1 | Java `ai_intent_configs` | RESTAURANT_* 94 码 + 制造业 + SMARTBI 类目 | DB 表（Flyway 迁移维护） | Java 8 层意图管线 + Python `ai/` 镜像（同表直读，5min 快照） | Flyway SQL |
| 2 | Python `RESTAURANT_OPS_*` | 8 码 + SAMPLE_QUERIES + 冻结词表 | `restaurant_ops_router.py` 硬编码 | chat.py / tiered router / gold_reads | 代码 PR |
| 3 | 物化模板 | 40 个 snake_case 码 | templates/registry 装饰器 | per-upload xlsx 路由 + 向量索引 | 代码 PR |
| 4 | owner-action 场景 | 13 个 | **前端** registry.ts | AIQuery.vue 前端推断 | 代码 PR |
| 5 | owner-action 后端镜像 | 同 13 个 | `restaurant_sections.py` 重复实现 | /owner-action-chat | 代码 PR（与 #4 各自漂移） |

**已实证的代价**（不是理论风险）：
- **路由抢跑**：Java `RESTAURANT_REVENUE_TREND` 与 Python `RESTAURANT_OPS_TREND_ANALYSIS` 语义同物、互不知晓 → Stage-8 LLM 0.95 直选 Java 码执行，丢窗口丢盈亏（2026-07-07 实锤，Phase 2 委托 gate 是运行时补丁不是治本）。
- **同义词表 ×4 维护**：营收/趋势/毛利/门店对比四个高频概念，每套各养一份中文关键词表，改一处别处不知道。
- **概念生死无处可查**："这个概念还活着吗/该路由到哪个执行体"目前只能人工 grep 四处代码。
- 既有映射全是补丁级：V20260630_01 直接复制 Python 码字符串当 Java intent_code（字符串耦合无登记）；Phase 2 委托 guard（运行时桥）。

## 2. 目标形态候选

### 方案 A：概念映射表（渐进，推荐起步）
新增**一张登记表**（repo 内 YAML/JSON，非 DB）：中立概念 ID → 各词汇表的对应码。

```yaml
# docs/concepts/restaurant.yaml (示意)
REVENUE_TREND:
  java_intent: RESTAURANT_REVENUE_TREND
  python_ops: RESTAURANT_OPS_TREND_ANALYSIS
  materialized: [monthly_trend, period_comparison_trend]
  owner_action: revenue_growth
  notes: Java 执行前经 Phase2 委托 gate; 窗口/盈亏槽位归 Python
```

- **改动量小**：不动任何运行时路由，先把"谁对应谁"从人脑/grep 移进一个可 diff、可 CI 校验的文件。
- **立刻可得的收益**：CI 校验器（新码上线必须登记；登记的码必须真实存在——防死链）；审计/新人一张图看全；为方案 B 提供数据基础。
- **局限**：不消除四份词表，只让漂移可见。

### 方案 B：单一注册表生成四处绑定（终态，先不做）
概念注册表成为唯一 source of truth，生成/驱动：ai_intent_configs 迁移、SAMPLE_QUERIES、模板 sample、场景 terms。
- 治本（一处改全局生效），但工程量大（四套消费机制各异、Java DB vs Python 硬编码 vs 前端 TS）、风险高（等价重构 94+8+40+13 个码），且现阶段收益存疑——demo 期概念集合还在快速变动，先固化生成机制会拖慢迭代。
- **判定**：等方案 A 跑 1-2 个月、概念集合稳定、且出现第二个"抢跑级"事故时再评估。

## 3. 推荐路径（分三步，每步独立有收益）

1. **A0（半天）**：建 `docs/concepts/restaurant.yaml`，人工登记餐饮域 ~15 个高频概念的四表映射（用盘点报告直接填）+ 一个 pytest 校验器（登记码必须存在于对应源；RESTAURANT_OPS_* 全部必须登记）。
2. **A1（一天）**：owner-action 前后端双实现收敛——后端 `_infer_owner_action_scenario_from_message` 改为读前端同源的 terms 数据（JSON 提出来两端共享，或后端为准前端拉取），消掉第五漂移点。这是五份里唯一"同一套东西写两遍"的纯冗余，先杀。
3. **A2（按需）**：新概念上线流程写进 runbook——任何新 intent/模板/场景必须先登记概念 ID，CI 强制。

## 4. 不做清单
- 不做方案 B 的生成机制（时机未到，判定条件见 §2）
- 不合并四套运行时路由（Phase 2 委托 gate 已covering 关键冲突面）
- 不建 DB 表/后台管理页（YAML in repo 够用且可 review）
- 不动制造业域（先餐饮验证形态）

## 5. 需 Steve 拍板
1. 方案 A 路径（A0→A1→A2）是否认可？
2. A1 的 owner-action 收敛方向：后端为准（前端拉取）还是共享 JSON 数据文件？（推荐共享数据文件，前端零请求成本）
3. 排期：A0 可随下一个餐饮迭代顺带做；A1 建议单独小 PR。
