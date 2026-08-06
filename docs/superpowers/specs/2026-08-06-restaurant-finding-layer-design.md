# 餐饮发现层 (domain="restaurant") 设计

**日期**: 2026-08-06
**范围**: 四步走的第 1 步 —— 往已有 `FindingService` 注册 `domain="restaurant"` 的 provider
**目标租户**: MOCK_REST
**总目标判据**: 店长打开系统，在没有提问的情况下，看到一条他本来不知道、且今天就能动手的事

---

## 1. 既有存量 (先假设它已经存在，查过了)

三样东西已经在 `origin/main` 上并且在跑。本设计**复用前者、绕开后两者的口径**。

### 1.1 发现层地基 —— 存在，且比交接描述的更完整

`backend/java/cretas-api/src/main/java/com/cretas/aims/service/finding/` 六个文件：
`Finding` / `FindingProvider` / `FindingService` / `impl/FindingServiceImpl` /
`impl/LowStockFindingProvider` / `FindingTextRenderer`。

`FindingService.Result` 已经带 `failedRules` + `complete()`，`FindingTextRenderer`
已经会区分「已检查 X 均正常」与「另有 Y 检查失败，暂无法判断」。即三态里的**两态已就位**。

> ⚠️ 排查记录：首次 grep 报「不存在」是因为主工作目录停在
> `codex/claude-rn-product-md`，落后于 `origin/main`。**在陈旧 checkout 上 grep
> 得出的"不存在"不是证据**。本设计全部结论基于 off `origin/main` 的干净 worktree。

### 1.2 损耗率诊断链 —— 活的，但对 MOCK_REST 静默

`health_check_metrics.py` 算 `ingredient_waste_rate`，公式
`wastage_cost_total / (requisition_cost_total + wastage_cost_total) * 100`，
源表 gold `agg_restaurant_daily_totals`。下游：`ingredient_waste_rate_high.yaml`
playbook（含 rxActions，WR-A01 owner=店长）→ `RestaurantHealthAlertBridgeService`
（standing alert，dedup + auto-resolve）→ `AlertRestaurantHealthCheckScheduler`（定时）。

**prod 实测**（`GET /api/smartbi/restaurant/MOCK_REST/health-check-report`，2026-08-06）：

```
coverage.ingredient_waste_rate = "ok"        ← 真算出来了，不是跳过
actualValue = 3.75   benchmarkRange = [8, 25]
status = "偏低"      severity = "info"
criticalCount = 0    warningCount = 0
```

`RestaurantHealthAlertBridgeService` 明写 info 不构成 standing alert，所以这条链
对 MOCK_REST **一条都不推**。它绿得有道理 —— 损耗率低于行业下限按
`higher_is_worse` 判定就是「好」。只是这条口径答不了本目标的问题。

> **本设计不重定义 `ingredient_waste_rate`。** 它已有唯一权威定义。

> **已发现未修（另开 issue，不在本 scope）**：诊断结论是「偏低」，却仍挂
> `ingredient_waste_rate_high` playbook，`suggestionZh` 在劝店长「削减过量采购 /
> 减少过期报废」。损耗已低于标杆还劝人降损耗，是反向建议。

### 1.3 `RestaurantWastageAnomalyTool` —— 名字对，数据源错，且在说假话

`ai/tool/impl/restaurant/RestaurantWastageAnomalyTool.java`（2026-03-07），读
`materialBatchRepository.findExpiredBatches(factoryId)` —— **主库 `cretas_db`**。

prod 实测：

| 表 | 库 | MOCK_REST 行数 |
|---|---|---|
| `material_batches` | cretas_prod_db | **0** |
| `wastage_records` | cretas_prod_db | **0** |
| `fact_restaurant_wastage` | smartbi_prod_db | **9,458** / ¥934,580 |

所以它恒定返回「近7天未检测到明显损耗异常，库存管理状态良好」。手里躺着 30 天
¥894K 的损耗，它告诉店长一切良好。它的 catch 块还返回「损耗异常检测功能正在
建设中」—— **两处都把「无数据」和「查询失败」渲染成正常**，正是本目标铁律第三条
禁止的。

> 本设计**把它的身子整个换掉**（见 §4 出口）。主库读取路径删除，不保留。

---

## 2. 数据事实 (写字段名前查过写入路径)

### 2.1 MOCK_REST 损耗数据

`smartbi_prod_db.fact_restaurant_wastage`（RLS `app.factory_id='MOCK_REST'`）：

```
9,458 行 / 25 种食材 / 2026-06-29 .. 2026-08-06
近 30 天: 8,075 行 / ¥894,270
按类型: 加工损耗 5,790 笔 ¥532,352 | 客诉退菜 2,407 笔 ¥81,059 | 变质 1,261 笔 ¥321,170
领料基数 ¥20,256,634 → 损耗率 4.22%
```

> ⚠️ `wastage_type` 实际存的是**中文自由文本**（加工损耗 / 客诉退菜 / 变质）。
> 建表注释写的 `EXPIRED / DAMAGED / SPOILED / PROCESSING / OTHER` **是陈旧的，
> 不要按它写代码**。`restaurant_ops_router.py` 的 `type_name_map` 对当前数据是
> no-op（原值已是中文，直接穿透）。

### 2.2 实际读取的表：`agg_restaurant_daily_ops`

规则不直接读 fact 表，读 gold 聚合（与 `resolve_wastage_top` 同源，口径一致）：

| kpi_kind | 行数 | 合计 | 维度 |
|---|---|---|---|
| `wastage_cost` | 579 | ¥934,338 | `dim_value_id` → `dim_ingredient.ingredient_id` |
| `wastage_qty` | 579 | 29,072 | 同上 |
| `wastage_cost_by_type` | 117 | ¥934,338 | `dim_value_str` = 类型中文字符串 |

三种 kpi 全部非零（`materialize_gold_daily_ops` 对本租户已跑过）。合计
¥934,338 与 fact 表 ¥934,580 一致到千分之三，可用。

### 2.3 🔴 07-30 数据生成过程切换 (本设计最关键的输入)

`fact_restaurant_wastage` 的 `created_at` vs 业务 `date`：

```
written_on   rows   covers
2026-07-29   5338   2026-06-29 .. 2026-07-29   ← 一次性回填整月
2026-07-30    320   2026-07-30                 ← 起，每天生成
2026-07-31    320   2026-07-31
2026-08-01    580   2026-08-01 .. 今天         ← 起，食材数 13 → 25
```

单笔均价：回填期 ¥18–30，日生成期 ¥200–228。

**结论：07-30 之前与之后是两个不同的数据生成过程，不是业务变化。**

后果（实测）：朴素的「近7天 vs 前30天」在此数据上，**25 个食材全部呈现
16–34 倍增长**。那不是 25 条发现，是一次数据装载事件被误读。

同质历史实际只有 **08-01 .. 08-06 共 6 天**。

> 由此推出的硬结论：**R1 今天在 MOCK_REST 上必须输出「判不了」而不是任何发现。**
> 这不是缺陷，是正确答案，也正好是三态里的第三态。

> ⚠️ 撤回一条中间结论：本设计过程中曾算出「鸡腿肉份额 +3.39pp」并当作可信发现，
> 那是拿 08-01~08-06（新生成器）比 07-08~07-29（回填）得到的**跨生成器比较，
> 不可信**，已作废。

### 2.4 🔴 Java 读不到这张表 (落点被证据定死)

```
smartbi_user  rolbypassrls = f      cretas_user  rolbypassrls = f
不设 GUC 查 fact_restaurant_wastage → 0 行 (实测)
全 Java 代码库 grep "app.factory_id" / "current_setting" → 无任何设置点
Java smartbi 数据源 = Hikari 连接池 (SmartBIPostgresPool, max 15)
```

在池化连接上设会话级 GUC 会跨租户泄漏。所以**规则必须落在 Python**（数据在那，
`_resolve_tenant` 已按连接管 GUC），Java 侧只做转发。

代价：本步骤要动**两条部署链**，且有顺序（Python 先，Java 后）。

> 现成的 `resolve_wastage_top` **不能直接复用** —— 它返回拼好的中文
> `top_list_text` 给 LLM 读，不是结构化数字。Finding 需要 facts，不能拿散文填。

---

## 3. 架构

```
Python  (规则在这，因为数据在这)
  smartbi/gold/restaurant/wastage_findings.py
    ├ detect_share_spike(pool, factory_id)         → R1
    ├ detect_type_concentration(pool, factory_id)  → R2
    └ ACTIONABLE_WASTAGE_TYPES                     (类型可行动性唯一定义)
  两者内部 SET app.factory_id 后读 agg_restaurant_daily_ops
  返回结构化 dict (数字，零话术)

  端点  GET /api/smartbi/gold/restaurant/{factory_id}/wastage-findings?rule=<share_spike|type_concentration>

Java  (发现层在这)
  service/finding/impl/RestaurantWastageShareSpikeProvider      domain="restaurant"
  service/finding/impl/RestaurantWastageConcentrationProvider   domain="restaurant"
    经 PythonSmartBIClient 调上面端点，只做 dict → Finding 形状转换
    ⛔ 不做任何判定 —— 否则口径出现第二处定义

  FindingServiceImpl   收集 domain="restaurant"，判定逻辑不改，新增 skip 分支
  FindingTextRenderer  新增两个 code 的模板 + 第三态分支
  出口 RestaurantWastageAnomalyTool  身子换成发现层
```

### 3.1 为什么两个 provider 而不是一个

`FindingProvider` 的既有契约是「每条规则一个 `@Component` 实现」，`ruleName()`
是单数。一个 provider 发两个 code 会让 `checkedRules` 里一个名字覆盖两条规则，
削弱「已检查 X 均正常」的诚实性。按契约走。

代价：同一次 `detectInline` 调 Python 两次。**请求级缓存刻意不做** —— 等真测出
延迟问题再加（YAGNI）。端点带 `rule` 参数，Python 只算被问的那条。

### 3.2 为什么出口选 `RestaurantWastageAnomalyTool`

它今天在给店长发假的全清信号（§1.3）。把它的身子换成发现层，一次同时完成
「建发现层」和「杀掉一条假话」，并且给第 1 步一个真实可验的入口（店长问损耗）。

---

## 4. 三态 (铁律：不许把失败或缺数据渲染成正常)

发现层现有两态。第三态「数据没采集到」无处安放 —— 塞进 `failedRules` 等于把
「历史不够」说成「查询失败」，正是禁止的。故**共享层新增一态**：

| 情况 | 触发 | 落点 | 渲染 |
|---|---|---|---|
| 真的没有 | 规则跑完，0 条发现 | `checkedRules`（既有） | 「✅ 已检查 损耗类型集中度，均正常。」 |
| 数据没采集到 | 抛 `FindingNotApplicableException(reason)` | **新增 `skippedRules`** | 「ℹ️ 食材损耗离群：两期食材名单不可比（25 vs 13），暂不判断。」 |
| 查询失败 | 抛其它异常 | `failedRules`（既有） | 「⚠️ 另有 X 检查失败，暂无法判断。」 |

**改动面**：
- 新增 `service/finding/FindingNotApplicableException` —— **必须 extends
  `RuntimeException`**：`FindingProvider#detect` 的既有签名不声明 `throws`，
  改成受检异常会破坏 `LowStockFindingProvider` 与全部既有实现
- `FindingService.Result` 加 `List<SkippedRule> skippedRules`（含 `ruleName` + `reason`）
- `FindingServiceImpl` 在既有 catch 之前加一个 `catch (FindingNotApplicableException)`
- `FindingTextRenderer` 加第三态分支

**对库存域的影响：零。** `LowStockFindingProvider` 从不抛该异常，`skippedRules`
恒空，渲染分支不进入。既有测试断言不变。

---

## 5. R1 `WASTAGE_SHARE_SPIKE` —— 食材损耗份额放大

### 5.1 口径

```
cur  = date >  CURRENT_DATE - 7   AND date <= CURRENT_DATE     → 恰好 7 天
base = date >  CURRENT_DATE - 28  AND date <= CURRENT_DATE - 7 → 恰好 21 天

share(i, w)  = 食材 i 在窗口 w 的 wastage_cost / 窗口 w 的全店 wastage_cost
amplification(i) = share(i, cur) / share(i, base)

触发 = amplification(i) ≥ 1.4  AND  share(i, cur) ≥ 0.05
```

> ⚠️ 边界用 `>` 不用 `>=`。`date >= CURRENT_DATE - 7` 取到的是 **8 天**不是 7 天，
> 两个窗口还会在 `CURRENT_DATE - 7` 那天重叠。本设计过程中的探索性 SQL 用的正是
> `>=`，实施时不要照抄。

**食材只在一个窗口出现时**（`share(i, base)` 为 0 或缺行）：**不参与 amplification
计算，也不产生 finding**。除零得不到「涨了无穷倍」这种结论，它只是没有基线。
这类食材已被闸 B 计入 Jaccard 的分母 —— 数量一多就整条规则 skip，数量少则忽略。

分母用**全店总损耗**，这是它扛住「全店一起跳 24 倍」的机制 —— 均匀缩放在分子
分母中对消。

### 5.2 两道同质闸 (缺任何一道 07-30 都会漏成 25 条假警报)

```
闸 A  基线历史长度：base 窗内有数据的 distinct 天数 < 14
      → FindingNotApplicableException("基线历史不足：仅 N 天")

闸 B  食材名单同质：
      Jaccard = |食材(cur) ∩ 食材(base)| / |食材(cur) ∪ 食材(base)|
      Jaccard < 0.8
      → FindingNotApplicableException("两期食材名单不可比：cur N 种 / base M 种")
```

两道闸各管一种失效模式：**份额归一化管幅度跳变，Jaccard 闸管口径跳变。**

今天 MOCK_REST：cur 25 种、base 13 种、Jaccard = 13/25 = **0.52 → 闸 B skip**。

> 闸 A 单独**挡不住**今天这个 case：base 窗（07-09..07-29）有 21 天数据，会通过。
> 这正是必须有闸 B 的原因。

### 5.3 话术约束

份额是零和的 —— 某食材份额上升有一部分是别的食材下降的机械结果。所以文案
**只能说「涨得比全店快 N 倍」，不得说「涨了 N 倍」**。

模板（`{}` 内的值全部取自 `facts`，渲染层不做任何计算）：

```
 · {subjectName} 近7天损耗 ¥{costCur}，占全店 {shareCur}%（基线 {shareBase}%），
   涨得比全店快 {amplification} 倍
```

### 5.4 Finding 形状

```
code          = "WASTAGE_SHARE_SPIKE"
domain        = "restaurant"
severity      = amplification ≥ 2.0 ? WARNING : INFO
actionability = 60
subjectId     = ingredient_id (String)
subjectName   = dim_ingredient.name
facts         = { costCur, shareCur, shareBase, amplification, windowDays, unit }
```

---

## 6. R2 `WASTAGE_TYPE_CONCENTRATION` —— 损耗类型集中

### 6.1 口径

```
窗口 = 最近 7 天，单窗口，无基线（故不受 §2.3 影响，任何租户第一天可用）
来源 = agg_restaurant_daily_ops，kpi_kind = 'wastage_cost_by_type'，dim_value_str = 类型

share(t) = cost(t) / 窗口总损耗
触发 = type 属于可行动类型  AND  share(t) ≥ 0.30
```

**不设绝对金额闸**（拍板：按占比）。理由：占比对不同规模门店自动适配，避免拍一个
对小店太高、对大店太低的数。已知风险：新店只有几百块损耗时也会报 —— 接受，
留待真实反例出现再加闸。

### 6.2 类型可行动性配置 (唯一定义)

```python
# smartbi/gold/restaurant/wastage_findings.py
# 唯一定义处。⛔ 不得在 Java 侧或 web-admin 再写一份。
ACTIONABLE_WASTAGE_TYPES = {
    "变质":     True,   # 可行动：备货量 / FIFO / 冷链
    "客诉退菜": True,   # 可行动：出品质量 / 菜品调整
    "加工损耗": False,  # 结构性：切配边角料是常态，店长知道也动不了
}
# 未知新类型 → 默认 True（宁多报不漏报）。取值是自由中文文本，按精确串匹配。
```

今天 MOCK_REST 近 7 天：加工损耗 52.9%（结构性，不报）、**变质 37.2% ¥291K
（报）**、客诉退菜 9.9%（未过 30% 闸）→ **恰好 1 条发现**。

### 6.3 Finding 形状

```
code          = "WASTAGE_TYPE_CONCENTRATION"
domain        = "restaurant"
severity      = share ≥ 0.50 ? WARNING : INFO
actionability = 70
subjectId     = 类型字符串
subjectName   = 类型字符串
facts         = { cost, share, windowDays, totalCost }
```

---

## 7. 错误处理

| 场景 | 行为 |
|---|---|
| Python 服务不可达 / 超时 | provider 抛普通异常 → `failedRules` → 「检查失败，暂无法判断」。**绝不返回空当作正常。** |
| Python 返回 `success: false` | 同上 |
| 窗口内零损耗记录 | 不是错误。R2 返回 0 条 → `checkedRules` → 「均正常」 |
| 同质闸不通过 | `FindingNotApplicableException` → `skippedRules`（§4） |
| `wastage_cost` 全零但 totals 非零 | `materialize_gold_daily_ops` 未对该租户跑过 → `FindingNotApplicableException("per-ingredient 成本 KPI 未物化")`。**不得对一堆零做排序。** |

---

## 8. 测试

### 8.1 Python

两个 detect 函数各自单测。**必须包含一条照抄 07-30 真实形状的 fixture**
（13 种 → 25 种、¥5K/天 → ¥118K/天），断言 R1 在其上 `skip` 而非报警。

### 8.2 Java

- 两个 provider 的 dict → Finding 形状转换（含 Python 返回 skip 时抛正确异常）
- `FindingServiceImpl` 的 `skippedRules` 与 `failedRules` **互不串台**
- `FindingTextRenderer` 三态各一条。「判不了」的断言**必须只针对 skip 那一行**，
  不能断言整段输出不含「正常」—— 三态可以同时出现（一条规则跑完且无发现 →
  「均正常」，另一条 skip），那时整段里出现「正常」是对的。断言错了会逼实施者
  把正确行为改坏。
- 🔴 组合态：`checkedRules` 非空 + `skippedRules` 非空 + 0 条 finding 时，输出
  **必须同时**说出「已检查 X 均正常」和「Y 暂不判断」。只说前半句 = 把「判不了」
  渲染成了「都正常」
- `RestaurantWastageAnomalyTool` 不再调 `materialBatchRepository`（`verify(never())`）

### 8.3 变异验证 (每条必须真变红；绿了说明断言是哑的)

| 变异 | 预期变红 |
|---|---|
| 删掉闸 B（Jaccard） | 阶跃 fixture 上 R1 从 skip 变成喷 25 条 |
| 份额归一化换成绝对值比较 | 同 fixture 直接爆表 |
| `skippedRules` 并回 `checkedRules` | renderer 把「判不了」渲染成「均正常」 |
| `ACTIONABLE_WASTAGE_TYPES` 里把「加工损耗」翻成 True | R2 从 1 条变 2 条 |
| provider 异常时返回空列表而非上抛 | 「查询失败」被渲染成「均正常」 |

### 8.4 真机验收 (不看 execution_status，看返回内容)

对 MOCK_REST 实调：

1. R1 返回 **skip + 理由文本**，理由中含实际的 25 / 13 数字
2. R2 返回**变质**一条，金额与直接 SQL 查出的对得上
3. R2 **不含**加工损耗
4. `RestaurantWastageAnomalyTool` 的输出里**不再出现**「库存管理状态良好」

---

## 9. 部署

两条链，有顺序：

1. **Python 先**：`deploy-smartbi-python.sh`。改的是 `backend/python/` 下的文件，
   在同步范围内。新函数必须在 `smartbi/gold/__init__.py` 的 import 块**和**
   `__all__` 双注册，并**真跑一次 import** 验证。
2. **Java 后**：`release-cretas.sh`。判据 `DEPLOY_EXIT=0` 且
   `RELEASE_FINAL_STATUS` 恰好 1 次，之后核对运行 jar 含新类。

Python 未部署时 Java 侧调用会 404 → 走 `failedRules` → 显示「检查失败」，
不会显示假的「正常」。顺序错了不会产生假数据，只会显示失败。

---

## 10. 刻意不做

| 不做 | 为什么 |
|---|---|
| 重定义 `ingredient_waste_rate` | 已有唯一权威定义（DiagnosticsEngine），铁律禁止第二处 |
| 修 playbook 反向建议缺陷（§1.2） | 真缺陷，但属诊断链，单独开 issue |
| 接 LLM 润色话术 | 模板不会编数字。异常检测用规则不用 LLM |
| `WorkdeskRole` 加餐饮岗位 | 第 4 步 |
| 主动出口 / 日报 / web-admin 页面 | 第 3 步 |
| 顺带提示接进餐饮查询 | 第 2 步 |
| 请求级缓存（消除两次 HTTP） | YAGNI，等测出延迟问题 |
| 查清 07-30 阶跃是谁造的 | 已知是回填（§2.3），成因不影响规则设计 |
| R2 的绝对金额闸 | 拍板按占比；等真实反例出现再加 |
| RN 侧展示 | 若要在 RN 屏幕露出，须先过 `ux-flow` 的 UX Flow Gate |

---

## 11. 本设计如何对上总目标

> 店长打开系统，在没有提问的情况下，看到一条他本来不知道、且今天就能动手的事。

第 1 步交付后，MOCK_REST 上会得到**恰好一条**：

```
变质损耗近 7 天 ¥291,112，占全店损耗 37.2%
```

他本来不知道 —— 因为唯一会告诉他损耗情况的两条链，一条说「损耗率 3.75% 偏低」
（静默），一条说「库存管理状态良好」（读空表）。今天能动手 —— 备货量与 FIFO
是店长权限内的事（对应 playbook WR-A01 / WR-A02，owner 正是店长）。

「没有提问」这一半要到第 3 步才真正满足；第 1 步的出口仍是他问损耗时才看到。
