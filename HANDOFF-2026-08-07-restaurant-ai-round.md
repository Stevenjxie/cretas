# 交接：餐饮 AI「问什么答什么」第一轮（2026-08-07）

> 起点：`HANDOFF-2026-08-06-restaurant-ai-and-departments.md`（小蓝店长六块）
> 本轮 goal：LLM 只做「自然语言 → 意图+槽位」，取数/算数/判定/成文全部确定性代码。

---

## ⚠️ 先读这条：我在本轮中途误判「CI 全仓停摆」—— 那是错的

**订正**：CI 一直正常。本轮 4 个 PR 的合并点在 main 上**全部 CI 绿**，
PR#2361 的合并点 `e6f52e826d` 跑满 6 个工作流（`Python Gate` / `Web Admin Gate` /
`JPA repository query gate (post-merge)` / `CI/CD Pipeline` / `Secret regression gate` /
`Web dist artifact`）**全部 success**。

**我为什么会判错**（这条比结论本身值得记）：
当时本地时间约 **01:50 CST**，我看到最后一次 run 是 `2026-08-06T16:54Z`，
直接相减得出「9 小时没动」。实际 01:50 CST = `17:50Z`，只差 **1 小时**。
🔑 **判据：`gh` 返回的时间戳是 UTC，本机是 CST(+8)，永远不要直接相减。**
用 `date -u` 取当前 UTC 再比。

**第二个误判**：我在 PR 上看到 `Python Gate / Web Admin Gate / Secret regression gate`
三个 failure，以为是代码红。实际三个都是 **`cancelled`** ——
这些工作流带 `concurrency: cancel-in-progress: true`，而 push 与 pull_request
在同一个 ref 上各触发一次，互相取消。
🔑 **判据：看到 `failure` 先查 job 的 `conclusion` 是不是 `cancelled`；
`gh pr checks` 把取消也显示成失败。**

```bash
# 正确的查法
gh api "repos/Stevenjxie/cretas/actions/runs?branch=<b>&per_page=10" \
  --jq '.workflow_runs[] | .created_at+" | "+.name+" | "+(.conclusion//.status)+" | "+.event'
gh run view <id> --json jobs --jq '.jobs[] | .name+" -> "+(.conclusion//.status)'
```

📌 **真实存在的一条**：`.claude/rules/worktree-and-main-only-deploy.md` 写
「CI `JPA repository query startup gate` 挂在 PR 上」——**已过期**。
`e6d1fffe75 refactor(ci): JPA 检查改为合并后告警而非 PR 门禁, 文件同步改名`
把 `jpa-gate-pr.yml` 删了，现在只剩 `jpa-gate-main.yml`（`push: branches: [main]`）。
那条规则正是用这个理由要求「backend 代码必须走 PR」，理由已经不成立 ——
规则本身仍然对（PR 号是台账锚点），但**依据要换**。

---

## 一句话

本轮**没有**做完六块。做完的是两件由 prod 实测驱动的事，外加一个把最大缺口
量出来但**主动撤回**的半成品。撤回那条比做完的两条更值得读。

---

## 已上线（4 个 PR，全部 prod 实测）

| PR | 内容 | 合并点 |
|---|---|---|
| #2361 | 采购第五部门权限补齐 + 发现层毛利谜题规则 | `e6f52e826d` |
| #2362 | 谜题菜份数渲染成 `143188.0 份` | `8fa379812a` |
| #2363 | 策划案域硬编码 inventory + 补 REST 出口 | `a20c038a93` |
| #2364 | 行动建议把两条无关发现揉成一条 | `a6bed736ac` |

**最终 prod 状态**：运行中 jar 含 `RestaurantPuzzleDishProvider` /
`RestaurantMarginFindingReader` / `RestaurantFindingPayloadMapper` /
`FindingActionPlanService` 各 1 个 class；迁移 `20261029.64` / `.65` 均
`success=true`；Python `8083`=200。
⚠️ 活跃槽本轮变了三次：10010 → 10020 → **10010**。
（`/actuator/health` 返 404 是该路径未暴露，不是服务挂 —— 业务接口全部 200 带数据。）

**归因修复的前后对照**（同一批发现，prod 实测原文）：

| 修复前（错） | 修复后（对） |
|---|---|
| ·排查**罗氏虾变质原因**，降低损耗成本 | ·主推**罗氏虾**，提升其销量 |
| ·排查**罗氏虾10个菜品**销售表现 | ·排查**变质损耗**，降低占比 |
| — | ·暂不处理食材损耗名单 |

第三条尤其值得注意：它正确处理了**被诚实跳过**的那条规则，说「暂不处理」
而不是编一个动作出来 —— 三态一路活到了 LLM 产出。

---

### 首两个 PR 的详细判据

**上线判据（逐条实测，不是「合了就算」）**

| 项 | 判据 |
|---|---|
| Java + Web | `RELEASE_FINAL_STATUS=deployed`；`WEB_HASH_FOUR_WAY=pass`（四方哈希 `56a013c9…` 全一致） |
| 蓝绿 | blue → green，**活跃槽现在是 10020**（部署前是 10010，本轮又变了一次） |
| 迁移 | prod `flyway_schema_history`：`20261029.64` / `20261029.65` 均 `success=true` |
| 采购权限 | prod 实查 **6 行 → 22 行**；`dashboard=r` / `procurement=rw` / `restaurantOps=r` / `restaurantProcurement=rw` |
| 阴性对照 | `mock_purchase` 本租户 **200** / 无 token **401** / 跨租户 F006 **403** —— 那个 200 是真放行，不是闸没跑 |
| Python | `deploy-smartbi-python.sh` 两次均「Python 3.11 服务 (8083) 与回滚契约验证成功」 |
| 新规则真跑了 | `checkedRules` 含 **菜品毛利谜题**，`failedRules=[]`，`complete=true` |

**店长零提问在落地页看到的（prod 实测原文）**：

```
· 罗氏虾 每份赚 ¥78.57，是有配方的 10 道菜里的高位（中位 ¥27.51）；
  近30天卖 143188 份，低于中位 159114 份 —— 最赚钱的菜没卖动
· 变质损耗近7天 ¥258495.17，占全店损耗 36.5%
ℹ️ 食材损耗离群：两期食材名单不可比…暂不判断。
```

毛利那条排在损耗**前面**（`rankScore` 275 > 270），所以不用删损耗规则，
排序天然把它挤到次位。三态在端到端链路上完整保留。

📌 **PR #2362 是首次上线后肉眼发现的**：份数渲染成「143188.0 份」——
`round(x, 0)` 返回 float。判据：`facts["qty"] == 143188` 对 float 也成立，
**光比值钉不住它，必须比类型**。已补 `isinstance(..., int)` 断言。



### 1. 采购第五部门只立了一半 —— 部长打不开自己部门的任何一个动作

prod 实测：六个餐饮角色里五个都是 22 行权限（6 个 `restaurant*` + 16 个基础模块），
唯独载体角色 `restaurant_purchaser` 只有 **6 行、零个基础模块**。

```sql
SELECT role_code, count(*),
       count(*) FILTER (WHERE module_code NOT LIKE 'restaurant%')
  FROM platform_role_permissions
 WHERE role_code IN ('restaurant_manager','sales_manager','finance_manager',
                     'hr_admin','restaurant_purchaser','restaurant_owner')
 GROUP BY role_code;
```

而权威 `departmentConfig.ts` 给采购部门列的三个动作：

| 动作 | 闸 | 结果 |
|---|---|---|
| 供应商进货录入 | `module='dashboard'` | 无此模块 ❌ |
| 报货/采购计划 | `module='procurement'` | 无此模块 ❌ |
| 领料 / 盘点管理 | `module='restaurantOps'='-'` | ❌ |

**采购部门唯一能打开的页面是它自己的看板，看板上列的三个动作全部打不开。**

🔑 **判据**：「采购职责并入市场、餐饮采购退役」这个已被推翻的口径有**三处**承载
—— `menuConfig` 的注释 / 两个菜单项的 roles 名单 / **一条会红的断言**。
只清前两处的话第三处会把它拉回来。这是「退役只做在数据层」的翻版，
只不过这次代码层的载体是**测试**。找承载点时别忘了把测试也数进去。

- `V20261029_64` 补齐 16 行基础模块
- `V20261029_65` 30 行矩阵完整重述 + `L1-AUTHORITY` 标记，`restaurantOps` `-`→`r`

📌 顺带发现：`permission.fallback-matches-l1.spec.ts` 守卫**只覆盖 6 个
`restaurant*` 模块**，基础 16 个不在覆盖内 —— 所以「fallback 有基础模块而 L1 没有」
这种不一致它是**绿的**。又一例「闸测的比需要的窄」。

### 2. 发现层换毛利口径 —— 谜题菜

🔴 **规则形状是被 prod 数据否掉一版之后定的。** 先做的「低单份毛利菜」实测
**产出 0 条**：

| 菜 | 销量 | 单份毛利 | 象限 |
|---|---|---|---|
| 米饭 | 143,238 | ¥2.19 | 瘦狗 |
| 酸梅汤 | 143,583 | ¥9.21 | 瘦狗 |
| …… | | | |
| **罗氏虾** | **143,188** | **¥78.57** | **谜题** |

低毛利的米饭/酸梅汤恰好都在销量中位数（159,114）以下被销量闸挡掉；去掉销量闸
则只报米饭+酸梅汤 —— 而「米饭不赚钱」不是店长不知道的事。全店也没有亏本菜。
真正有信息量的是**谜题象限**：罗氏虾单份赚 ¥78.57 全店最高（中位 ¥27.51），
销量却在最低档 —— 店长以为它很好（营收全店第一）。

🔑 **判据**：**规则写完先拿真数据跑一遍看它出几条**。「阈值看起来合理」和
「在真客户数据上说得出话」是两件事。

- `dish_margin.py`：把 `/gross-margin` 里**内联的第二份** per-dish 成本口径原样
  搬出来共用（它自己的注释就承认了）。该口径已有 5 处承载，`FindingProvider`
  明写「禁止新写口径 SQL」—— 规则调这个函数，不做第 6 处。
- 阈值**全是中位数这类相对量**，一个绝对金额/销量常数都没有，并有一条**扫源码**
  的测试钉住这一点（MOCK_REST 是假数据，绝对阈值是对小说调参）。

---

## 🔴 撤回的那条 —— 本轮最大的发现

**多店租户的首轮基础问句拿不到答案，只拿到一句反问。**

prod 实测（`mock_ops`，MOCK_REST 有 10 家门店）：

| 问句 | 实际返回 |
|---|---|
| 最近30天总营收是多少 | ❓「你想看哪几家门店的总营收？」 |
| 加权毛利率是多少 | ❓「你想看哪家门店的加权毛利率？」 |
| 哪个菜卖得最好 | ❓「这项分析要看哪一组门店、哪个时间范围？」 |
| 外卖和堂食各占多少 | ❓「想看哪些门店的堂食和外卖占比？」 |

注意第一条：用户**已经说了「最近30天」**，时间槽填上了，卡住的是门店槽。
那个恒定的 **~2220ms** 就是 T3 LLM 被调用了、然后产出一句反问 ——
**花了 LLM 的钱，没拿到答案**。`plan_cache` 命中只要 20ms，但**缓存的是那句反问**。

这落进了 goal 明令禁止的**第四种归宿**（既不是 A 有答案 / B 诚实缺数据 /
C 不在范围，是反问）。

### 为什么撤回而不是修

`_apply_store_scope_guard` 的最终分支同时服务两种情形，而它们要的处置相反：

- **首轮新问句** → 想要「默认全部门店直接答」
- **延续轮**（用户刚答完时间）→ 想要「继续问门店」，因为
  「时间 → 门店按钮 → 答案」整条是**零 LLM** 的确定性延续

用 `spec.is_clarification_continuation` 区分，能把 20 条红压到 8 条。但剩下 8 条
揭示了真代价：**首轮默认之后，用户再说店名收窄就得重走一次 T3**。

正确解法是「给答案的同时留下可延续的范围槽」，但**不能复用
`restaurant_pending_clarifications`** —— 它的消费端会把新问句拼到旧问句上
（`combined_query = original_query + " " + norm_query`），给「已答完」的问题登记
pending，等于让下一个不相关的新问题被拼到旧问句后面。要做对得新增一个**独立的
refinement context**，那是个真功能，不是微调。

🔑 **判据**：半成品在这里是**净亏** —— goal 里 hard criterion 明写「多轮 agentic
tool-calling 视为设计失败」，**在一条原本确定性的路径上新增 LLM 调用，是直接违反
判据**；而「首轮多一次澄清」只是 UX 成本，不是架构违规。而且我没有任何数据支持
「收窄很罕见」（prod 真实 AI 用量是 0），拿频率猜来做取舍正是「对假数据调参」。

**接手要做的**：新增独立 refinement context，然后首轮默认全部门店 + 在答案里
显式声明范围（`store_scope_defaulted` 那套已验证可行，代码在 git 历史里，
本轮 revert 掉了）。

---

## 📊 G0 基线 + G1 归宿表（2026-08-07 prod 实测，15 个代表性问句）

以 `mock_ops` 打 `POST /api/smartbi/gold/restaurant/tiered-answer`。
token 与 LLM 次数取自 `smart_bi_llm_usage` 表在每次请求前后的增量（不是估算）。

| 问句 | 耗时ms | token | LLM次 | 归宿 |
|---|---:|---:|---:|---|
| 最近30天总营收是多少 | 4318 | 0 | 0 | **D-反问** |
| 加权毛利率是多少 | 4226 | 3162 | 1 | **D-反问** |
| 哪个菜卖得最好 | 27 | 0 | 0 | **D-反问**(plan_cache 命中) |
| 毛利最低的菜品有哪些 | 2949 | 3152 | 1 | **D-反问** |
| 外卖和堂食各占多少 | 4320 | 3155 | 1 | **D-反问** |
| 哪个时段生意最好 | 4222 | 6649 | 3 | **D-反问** |
| 食材成本占营收多少 | 4868 | 3395 | 2 | **D-反问** |
| 最近损耗情况怎么样 | 4704 | 0 | 0 | **D-反问** |
| 库存有什么要注意的 | 3008 | 3411 | 1 | A-有答案 |
| 哪个供应商报价最贵 | 10228 | 6571 | 3 | **D-反问** |
| 员工人效怎么样 | 10228 | 3387 | 1 | A-有答案 |
| 营收趋势怎么样 | 4222 | 1246 | 1 | **D-反问** |
| 各门店对比如何 | 4222 | 3386 | 1 | **D-反问** |
| 折扣力度多大 | 4221 | 3397 | 1 | **D-反问** |
| 明天天气怎么样 | 2835 | 3377 | 1 | C-不在范围 |

### 🔴 G1 归宿分布：A=2 / B=**0** / C=1 / **D=12（80%）**

**goal 明写「没有第四种归宿」，而 80% 的代表性问句正落在第四种。**

更要命的是 **D 类不是免费的**：

| | 条数 | token | LLM 调用 |
|---|---:|---:|---:|
| **D-反问（无答案）** | 12 | **34,113** | **14** |
| A + C（有结果） | 3 | 10,175 | 3 |
| 合计 | 15 | 44,288 | 17 |

> **77% 的 token、82% 的 LLM 调用，产出的是一句反问而不是答案。**

抽样验证（确认 D 不是被我错分的 B）：

```
哪个供应商报价最贵 → kind=clarification → 「你这次最想先看哪件事？」
食材成本占营收多少 → kind=clarification → 「这次想查询哪几家门店的食材成本数据？」
营收趋势怎么样     → kind=clarification → 「这次想查看哪几家门店的营收趋势？」
```

🔴 **B 类 = 0 是独立的一条缺陷**：系统**从不**给出「这项数据没采集」的诚实回答。
「哪个供应商报价最贵」的诚实答案本该是 B ——「供应商报价（`agg_supplier_price`）
0 行，录入后才能比价」；实际返回的却是一句毫无帮助的「你这次最想先看哪件事？」。

### 延迟基线

P95 = **10,228ms** · 中位 = **4,222ms** · 最快 27ms（`plan_cache` 命中，
但**缓存的是那句反问**）。大量问句稳定落在 ~4,220ms —— 那是 T3 LLM 跑完后
产出反问的时间。

### ✅ 修复后的对照表（PR#2370 已上线）

**⚠️ 两次测量必须分开看，问句集不同：**

**(a) 同一套问句（11/15 条不带时间）—— 苹果对苹果**

| | 修复前 | PR#2370 后 |
|---|---:|---:|
| A-有答案 | 2 | **3** |
| D-反问 | 12 | **11** |

只翻了「最近30天总营收是多少」一条 —— 而它恰恰是**唯一带了时间**的那条。
翻过来之后它是 `RESTAURANT_OPS_SALES_SUMMARY`，**0 token / 0 LLM 调用**（plan_cache）。

**(b) 把问句改成店长真想拿答案时的打法（补上时间）**

| | 条数 | token | LLM |
|---|---:|---:|---:|
| **A-有答案** | **10** | 21,459 | 9 |
| C-不在范围 | 2 | 6,584 | 2 |
| **D-反问** | **4** | 17,044 | 5 |
| B-诚实缺数据 | **0** | — | — |

对比原始基线（A=2 / D=12 / D 类烧 34,113 token）：**D 从 12 降到 4，
D 类的 token 浪费从 34,113 降到 17,044**。3 条 A 是 0 token / 0 LLM。

### 🔴 一条我自己的度量错误（比修复本身更该记）

**我最初把「所有反问」都判成 D-禁止，那是错的。** goal 的架构原则原文是：

> 同时要够智能：意图覆盖面要宽；识别不出来就**澄清**或诚实说不在范围，
> 绝不自由发挥填空。

**澄清是被 goal 明确允许的** —— 它禁的第四种是「LLM 自由发挥」。
「加权毛利率是多少」没说时间，问一句时间是**正当的**；
「最近30天总营收是多少」已经给了时间，**不答就是缺陷**。

我的问句集 15 条里有 11 条没带时间，于是原始的 D=12 **严重高估**了缺陷面。
🔑 **判据：判「这个反问该不该存在」，先看用户有没有给过它要问的那个槽位。**
度量脚本 `scripts/dev/measure-restaurant-g0g1.sh` 的分类器仍是粗口径
（`kind==clarification` 一律记 D），读它的输出时要按这条判据人工复核。

### ✅ B 类归宿已补上（PR#2371 + #2372 已上线）

prod 实测原文（`最近30天哪个供应商报价最贵`）：

```
**供应商报价目前还没有数据**，所以这个问题现在算不出来。
- 现状：本店一条供应商报价记录都没有
- 怎么才能有：在「供应商进货录入」里录入各供应商的报价后，就能做比价和采购价异常分析
在那之前我不会用别的计算方法凑一个数给您。
```

改动前它说的是「**天气、新闻这类外部信息不在我的数据范围内**」—— 那是误导：
供应商报价不是天气，它是我们打算支持、只是客户还没录的东西。
（`明天天气怎么样` 仍正确落 C，没误伤。）

**🔴 真查表，不硬编码「没数据」**：客户开始录入之后 `honest_gap_answer` 返回
`None`，照旧走原出口。把「数据没到」写死成常量就是另一种降级处理 ——
数据来了却还在说没有。查不动时同样返回 `None`（猜任何一侧都是假话）。

**🔑 一条 goal 与仓内既有契约的冲突，以及它的解法**：
goal 要求 B 类「必须点名缺哪张表哪个字段」，我第一版把 `agg_supplier_price`
写进了客户文案 —— **prod 上渲染成「缺的是：``（本店 0 行）」**，表名被
`customer_text._INTERNAL_IDENTIFIER` 抹掉了。那道闸是**刻意的**：客户不该看到
表名，对店长它也毫无意义。

解法是拆成两侧，各取所需：
- 用户看到**业务上那件事**（供应商报价）+ 具体行动
- 表名进 `meta.missing_table`，给工程侧/交接/排查看

并补了一条**会红的哨兵**：客户文案里出现表名或任何带下划线的标识符即红 ——
否则下次有人「为了更精确」把表名加回去，不会报错，只会被静默抹成空。

⚠️ 已知小账：响应的 `code` 仍是 `RESTAURANT_OPS_OUT_OF_DOMAIN` 而不是
`RESTAURANT_OPS_DATA_GAP`（服务层用的是**计划里的** intent code，不是 resolver
返回的 code）。文案与 `meta.data_gap` 都对，只是 code 这一格没跟上，未修。

---

### 📊 本轮最终 G1 结果（全部改动上线后 prod 实测）

| | 原始基线 | **本轮结束** |
|---|---:|---:|
| **A-有答案** | 2 | **10** |
| **B-诚实缺数据** | **0** | **1** |
| C-不在范围 | 1 | 1 |
| **D-反问** | **12** | **3** |

**D 从 12 降到 3。** 全部由四个 PR 累加而成：

| PR | 修的是 |
|---|---|
| #2370 | T3 说「唯一缺项是门店」时不再反问，默认全部门店并声明范围 |
| #2371/#2372 | B 类归宿：「这项数据还没采集」不再被说成「不在我的数据范围」 |
| #2374 | 「**加权**毛利率」的「加权」被当成菜名 → 全店指标问句被打成「查无此菜」 |

⚠️ **run-to-run 有波动**：同一问句在不同轮次可能落 A 也可能落 D
（`各门店对比` / `折扣力度` / `供应商报价` 都出现过）。T3 是 LLM，规划结果本身不
确定。**判读这张表要多跑几轮，单次结果不能下结论** —— 我自己就因为单次结果误把
已经修好的供应商那条记成 D。

### ⛔ 剩下的 D —— 三条各有不同性质，根因全部查实

**先补了一道可观测性（PR#2376）**：`_execution_mismatch` 会把用户问题挡成反问，
却**一行日志都不留**。判据：**会拦下用户问题的闸，必须留下它拦的是什么** ——
否则下一个人只能靠猜，或者靠放宽 `_RESOLVER_DIMENSIONS`，而那是能力**声明**，
resolver 不支持却放宽 = 用错粒度回答，比反问更糟。加了日志之后，下面三条当场查清。

| 问句 | 日志给出的真根因 | 性质 |
|---|---|---|
| `哪个时段生意最好` | `intent=SALES_SUMMARY dimensions=('time',)`，而该 resolver 只声明 `{store}` | **路由已修（#2377），但暴露出更深一层**，见下 |
| `食材成本占营收多少` | `intent=RECIPE_COST dimensions=('ingredient',) metrics=(recipe_cost,revenue)` | **planner 标错维度** —— 这是全店比率问题，不是食材粒度。RECIPE_COST 声明 `{dish}` 是对的，不该放宽 |
| `折扣力度多大` | 反问「你这次最想先看哪件事？」 | T3 对「折扣」这个明确指标识别不出意图 |

#### 🔴 `哪个时段生意最好` 修完路由后暴露的真缺口

PR#2377 让 `_is_daypart_business_query` 认出了这句（原来日段词表只有**具体**的
午市/晚市，没有泛指的「时段」；疑问词表也没有「最好/最忙/最高」）。
路由确实到了 staffing resolver，但它**刻意拒绝**：

> 这条问题问的是历史或当前时段表现，**不能把它偷换成明天的预测排班**。

那道守卫（`requests_non_forecast_staffing_window`）是**对的** —— 预测排班只做未来。

🔑 **所以真正的缺口是：「历史时段表现」没有 resolver。**

⚠️ **订正我自己上一版的话**：我先写了「数据是有的，`agg_daily_order_type_meal`
有 meal_period 维度」—— 那是**看 `staffing_forecast.py` 里的 SQL 推断的，没查表**。
实查 MOCK_REST：

```sql
SELECT meal_period, SUM(gross_amount) FROM agg_daily_order_type_meal
 WHERE factory_id='MOCK_REST' AND date > CURRENT_DATE - 30 GROUP BY 1;
--  未分类 | 15,572,759        ← 只有这一行, 时段分类根本没物化
```

**聚合表的 `meal_period` 全是「未分类」。** 能算是因为**原始 POS 有时间戳**，
按小时现切确实出得来（实测，近 30 天）：

| 时段 | 单量 | 营收 |
|---|---:|---:|
| 晚市 | 109,772 | ¥41,319,090 |
| 午市 | 72,716 | ¥27,319,788 |
| 下午茶 | 18,194 | ¥6,853,738 |

**下一轮要做的**是 `resolve_daypart_performance`，从 `fact_pos_transaction` 现算
（或先修 ETL 让 `meal_period` 真的分类）。

⛔ **别再写一份时段切分 SQL** —— `staffing_forecast.py` 里已经有那段
`CASE WHEN EXTRACT(HOUR FROM time) BETWEEN 10 AND 13 THEN '午市' …`。
先把它抽成共用片段，两边调同一处（与本轮 `dish_margin.py` 同一个做法），
否则时段边界会长成两处定义。

📌 **判据（本轮第二次栽同一个坑）**：**「数据是有的」必须是查表查出来的，
不能是读到一段 SQL 就推断出来的。** 第一次是我以为「员工表 0 行所以人效算不了」
（实际 `restaurant_staffing_policy` 有 160 行，人效答得出来）；这次反过来，
我以为聚合表有时段而它全是「未分类」。**两个方向都栽过。**

---|---|
| `最近30天哪个时段生意最好` | 「查询维度超出计划 resolver 的能力范围」—— planner 选的 dimensions 与 resolver 能力不匹配（`_execution_mismatch`） |
| `最近30天食材成本占营收多少` | 同上 |
| `最近30天折扣力度多大` | 反问「**你这次最想先看哪件事？**」—— 一句毫无帮助的澄清，T3 没能识别意图 |

前两条是**同一根因**：需要对齐 `_RESOLVER_DIMENSIONS` 与 planner 实际会产出的
dimensions 组合。第三条要看 T3 为何对「折扣」这个明确指标识别不出意图。

---

### ⛔ 仍然存在（下一轮）

1. **D=4**：`最近30天加权毛利率是多少`（2 次 LLM / 6,820 token）、
   `哪个时段生意最好`、`食材成本占营收多少`、`各门店对比如何`。
   这四条**时间已经给了还在反问**，是真缺陷，要逐条查 T3 的 `missing_fields`。
2. **B=0**：系统从不说「这项数据没采集」。
   `最近30天哪个供应商报价最贵` 现在落 C-不在范围 —— 比之前的「你这次最想先看
   哪件事？」好，但仍不是 B（正确答案是「`agg_supplier_price` 0 行，录入后可比价」）。
3. 裸店名收窄仍要重走一次 T3（独立 refinement context）。

---

### 🔴 D=12 的**真根因**（2026-08-07 尾声查实，改动上线后 D 仍是 12）

我先改了 `_apply_store_scope_guard`（首轮默认全部门店，PR#2368 已上线、480 测试全绿），
**重跑后 D 依然是 12** —— 改动生效了（服务器上 `store_scope_defaulted` 命中 3 次），
但没碰到根因。

**反问的原文是「你想看哪几家门店的营收？」—— 这是 T3 LLM 自己写的问句**，
不是 `STORE_SCOPE_CLARIFICATION_QUESTION`（「这项分析要看哪一组门店？请选择…」）。
两者措辞不同，我早先看到过却没顺着追。

链路：

```python
# _apply_store_scope_guard 顶部
or (spec.clarification_needed
    and "time" not in _slots_of_clarification(spec.clarification_question))
    → 提前 return, 默认逻辑根本没机会跑
```

`_slots_of_clarification` 是**拿问句与两个已知常量做字符串相等比较**来判槽位的。
LLM 自撰的问句两个都不匹配 → 返回空 `frozenset()` → `"time" not in frozenset()`
恒为真 → **早退**。

🔑 **所以 D=12 的产生处不是那道守卫，是 T3 planner 自己决定要反问。**
门店槽守卫只管它自己发出的那一类反问；LLM 发的那一类完全绕过它。

**✅ 真根因的修复已写好，在分支 `codex/claude-t3-store-default-WIP`（commit `a8c77fe61f`）**
—— **未合并未部署**，因为还有 4 条测试钉着旧契约。

判定移到了 T3 产出处（`_semantic_spec_from_t3`），判据只认**结构化的
`missing_fields`**，不认措辞：

```python
if (clarification_needed
        and tuple(missing_fields) == ("store_scope",)
        and not store_scope and not store_names):
    clarification_needed = False        # 不反问
    store_scope = "all"                 # 默认全部门店
    store_scope_defaulted = True        # 答案里显式声明范围
```

还缺别的槽位（时间/指标/对象）时**照旧反问** —— 那些默认值有实质歧义。

**接手要做的只剩一件事**：这 4 条测试按新契约重构（都是多轮链，第 2/3 轮依赖
门店按钮，与已处理过的那 3 条同型，可照抄改法）：

| 行号 | 测试 |
|---|---|
| 401 | `test_semantic_first_store_buttons_only_offer_data_bearing_dish_stores` |
| 1949 | `test_semantic_first_store_choice_is_merged_and_not_asked_twice` |
| 2047 | `test_semantic_first_three_turn_metric_time_store_chain_keeps_original_metric` |
| 2387 | `test_semantic_first_week_comparison_action_keeps_all_slots_after_store_button` |

它们断言的都是「LLM 只问门店 → 我们照样反问用户」，**正是 goal 判为 D 的行为**。
当前 476/480 passed。改完合并部署，再跑一次
`scripts/dev/measure-restaurant-g0g1.sh` 对比 D。

**原下一轮落点（已完成前两项，留作记录）**：
1. 在 T3 返回之后、组装响应之前，加一道**确定性**的「这个反问是不是只缺门店」判定
   —— 不能再靠与常量做字符串比较（LLM 措辞每次都不同），要靠 T3 返回的
   `missing_fields` / 结构化槽位判断。
2. 判定为「只缺门店」时套用已上线的默认逻辑（`store_scope="all"` +
   `store_scope_defaulted=True` + 范围声明），那套代码已经在了、有测试。
3. B 类归宿（诚实说缺数据）同样要在这一层加 —— 见「还欠的」。

⚠️ **PR#2368 不是白做**：它覆盖了守卫自己发出的那类反问，并且把
`store_scope_defaulted` + 披露 + 480 条测试的地基铺好了。第 2 步可以直接复用。
但**它没有降低 D**，这一点必须说清楚，别让下一个人以为已经修好了。

### 这张表怎么用

**改善的判据不是「更快了」，是「D 归零」。** 门店槽默认值那条改动
（见下「撤回的那条」）直接冲着这 12 条 D 去 —— 它把「时间已给、门店没给」
那一支从反问改成直接出答案。做完后重跑本表，D 应从 12 降到个位数，
且省下的是那 34,113 token 里的大部分。

复现命令在服务器 `/tmp/g0g1.sh`（token 差值取自 `smart_bi_llm_usage`）。

---

## ✅ G2 主动性：通过

**零提问落地页第一条是非损耗口径的发现**（prod 实测原文，`mock_ops`）：

```
· 罗氏虾 每份赚 ¥78.57，是有配方的 10 道菜里的高位（中位 ¥27.51）；
  近30天卖 143188 份，低于中位 159114 份 —— 最赚钱的菜没卖动   ← 毛利口径
· 变质损耗近7天 ¥258495.17，占全店损耗 36.5%                    ← 损耗口径(次位)
ℹ️ 食材损耗离群：两期食材名单不可比…暂不判断。
```

毛利那条排在损耗**前面**是设计使然：`rankScore = severity×100 + actionability`，
谜题菜 `WARNING(2)×100+75 = 275` > 损耗集中度 `270`。所以**不必删损耗规则**，
排序天然把它挤到次位。

**两个出口同一份定义**：`RestaurantFindingHintAppender`（对话顺带）与
`FindingController`（主动出口）都调 `findingService.detectInline(factoryId, "restaurant")`
—— 同一个 service、同一批 `FindingProvider`、同一套阈值。
⚠️ 精确地说：它们是**两次调用**（分属两个 HTTP 请求，这无法避免），
但**不是两份实现** —— goal 要防的「两处各算一遍」指的是后者。

---

## ✅ G3 阴性对照：通过（硬证据）

goal 的判据是「把 LLM 断开，数字仍应正确产出」。在 prod 上直接数 LLM 调用：

```
打 /findings 前  [llm_router] 行数: 1845
→ 返回 2 条发现（罗氏虾谜题 + 变质损耗），数字齐全
打完之后:                          1845
本次新增 LLM 调用 = 0
```

发现层这条链（Python SQL + `statistics` → Java 纯模板渲染）**结构性地不可能编
数字** —— 不是靠提示词约束，是链路里根本没有 LLM。

日志标记：`grep -c '\[llm_router\]' /www/wwwroot/cretas/python-prod.log`

---

## ④ 策划案：已修并上线（PR #2363 + #2364）

交接原文写「没有 REST 出口」，**实测缺口是两个叠加**：

1. **域硬编码**：`FindingActionPlanTool:43` 的 `DOMAIN = "inventory"`。餐饮租户
   走它拿到的是**库存域**发现（`LowStockFindingProvider`）。提示词还写死
   「你是食品加工厂的生产助理」—— 对店长身份就是错的。
2. **它只是个 `@Tool`**：餐饮提问到不了 Java Tool。

修法照发现层的「一层两出口」：抽 `FindingActionPlanService`（领域参数化），
Tool 与新的 `GET /api/mobile/{fid}/findings/action-plan?domain=` 共用。
Tool 侧**仍固定 inventory** —— 它的 name/description 都写着「库存异常」，
改成跟随租户会让一个自称管库存的工具悄悄去答餐饮问题。

🔑 **上线后又实测到第二个缺陷（#2364）**：产出是

```
·排查【罗氏虾变质原因】，降低损耗成本
·排查【罗氏虾10个菜品】销售表现
```

罗氏虾是谜题菜、「变质」是另一条独立发现 —— **模型把两条无关的发现揉成了一条**。
数字一个没编（`GroundedNumberValidator` 放行是对的），但**归因错了**。

**判据：校验器只管数字，管不了归因。** 而按这个类自己 javadoc 的话「它会被照着
执行」—— 归因错和编数字一样糟。根因是喂进去的是裸字典 `{对象: 罗氏虾}` /
`{对象: 变质}`，只有「对象」两个字。修法是改喂 `FindingTextRenderer` 已经渲染好
的**整句**（纯模板零 LLM，归因天然正确），模型只排先后、给动作。

---

## ✅ ② 岗位入口：结论是**不该做**

- `workdesk_role_capabilities` **零 REST 出口、web-admin 零消费**，只作为 `@Tool`
  存在 → 对餐饮结构性不可达
- 但餐饮的「这个岗位能干什么」**已经有承载了**：5 个部门看板
  （`departmentConfig.ts` 列出每个部门的 KPI + 动作 → `DepartmentDashboard.vue`，
  5 条路由），而且是可达的
- 给 `WorkdeskRole` 补 4 个角色再配 REST 出口 = 给「岗位能干什么」造**第二处定义**

🔑 **判据：先问「这件事已经有承载了吗」，再问「该不该新建一个」。**
造第二处定义的代价比缺一个入口高。

---

## 🔴 可达性扫描 —— 本轮最后、也最该复用的一件事

做「六块可达性表」时顺手写了一道闸（`routedPagesAreReachable.spec.ts`），
结果它**连着扫出三个「有路由、有组件、用户到不了」的页面**：

| 页面 | 症状 | 处置 |
|---|---|---|
| `/dashboard/ai-value`（**③ AI 工作台**） | 路由 title/icon/module 齐全，menuConfig 零命中 | PR#2378 补入口 |
| `/dashboard/widgets` | 同上 | 开发演示页，显式豁免并写理由 |
| `/restaurant/commission`（营销员提成） | 无菜单入口、**无任何页面链接** | PR#2381 见下 |

### `/restaurant/commission` 的根因不是「忘了加菜单」

**两条既有契约在它身上不可能同时满足**：

- `restaurantMenuRouteAlignment`：每个餐饮功能页必须挂在五个部门之一
- 同一 spec：菜单项的 `module` 必须 == 路由 `meta.module`

而它的路由 `meta.module` 是 `'restaurant'` —— 那是**板块准入**不是部门。
用 `restaurant` 时前一条判红，改菜单为部门模块时后一条判红。**两边都进不去，
于是它一直悬着。** 修法是路由与菜单**两侧一起归位**到市场部门。

🔑 **判据：一个页面长期「没人加菜单」，先问是不是它根本不满足某条归属契约。**
只改一侧都是把问题挪个位置 —— 我先后被那两条闸各判红一次，**是闸逼出了真修法**。

📌 **同类还有一个未处理**：顶层 `/unit-dictionary` 的路由 meta 明写
`showInMenu: true`，menuConfig 里却没有它。属平台管理域
（`factory_super_admin`/`platform_admin`/`permission_admin`），不在本轮餐饮范围。

📌 **接手可以直接复用**：把 `routedPagesAreReachable.spec.ts` 一组一组扩出去
（`/procurement/*`、`/sales/*`…）。每扩一组先看红几条，逐条判「补菜单」还是
「写豁免理由」。⚠️ 别一次扩到全仓 —— 会一次红几十条变成噪音，然后被整体豁免掉。

⚠️ 扩的时候注意：路由是**嵌套相对路径**，靠正则取 `path:` 会把不同层级混在一起。
我第一次扫 `/restaurant/*` 就把顶层的 `unit-dictionary` 算成了餐饮子路由
（差点加出一条 `/restaurant/unit-dictionary` 的错菜单项）—— **按缩进/父块判层级**。

---

## 🏁 最终 G1 结果

**A=12 / B(不稳定) / C=1 / D=2**，原始基线是 **A=2 / B=0 / C=1 / D=12**。

三轮实测（第 7 位 `食材成本占营收` 本轮修好，三轮全 A）：

```
run1  A A A A A A A A A C A A A D C
run2  A A A A A A A A A D A A D A C
run3  A A A A A A A A A D A A D A C
```

⚠️ **这次三轮不一致**（上一轮是逐字一致）。稳态取后两轮。
🔑 判据仍然是**连跑多轮**：单次读数会漂，而且**漂的位置每轮不同**。

### 剩下两条的真根因（都已查实，且都不是路由问题）

| 问句 | 真相 |
|---|---|
| `最近30天哪个供应商报价最贵` | **B 类文案是好的**——实测返回「供应商报价目前还没有数据…录入后就能做比价」。⚠️ **我上一版说「data_gaps 没接上」是错的**。真实情况是 **T3 在不同轮次把这句路由到不同意图**（B/C/D 都出现过），是 LLM 不确定性，不是接线问题。要稳下来得让「已知缺口」的判定早于 T3 路由，而不是挂在 out-of-domain 的兜底分支上。 |
| `最近30天各门店对比如何` | **答案契约失败**，不是维度闸：「本次结果没有可靠覆盖问题中要求的全部指标和动作」。resolver 跑了，但产出没覆盖 T3 请求的 metrics/action。要查的是 `answer_contract.required_elements` 与该 resolver 实际产出的差集。 |

### 本轮修掉的三条 D，每条的形状都不同

| 问句 | 根因 | 修法 |
|---|---|---|
| `哪个时段生意最好` | **缺终点** + 改写指向一个必然拒答的 resolver | 新建 `resolve_daypart_performance`（#2383）+ 改写目标跟着换（#2384） |
| `食材成本占营收` | **维度误标**（`ingredient`，实为全店比率） | 两条件守卫：没点名实体 **且** 无 resolver 支持才去掉（#2386） |
| `加权毛利率是多少` | **菜名抽取器**把「加权」当菜名 | 补进 `_DISH_GENERIC_TOKENS`（#2374） |

🔑 **三条形状全不同 —— 「D=N」这个数字本身没有诊断价值，必须逐条看日志。**
`执行前拦截` 那条日志（#2376）是本轮性价比最高的一次改动：它把「查不下去」
变成了「一眼看出 intent/dimensions/metrics」。

---

## 🏁 上一版记录（连跑三轮逐字一致）

```
run1  A A A A A A D A A C A A D A C
run2  A A A A A A D A A C A A D A C
run3  A A A A A A D A A C A A D A C
```

| | 原始基线 | **最终** |
|---|---:|---:|
| **A-有答案** | 2 | **11** |
| C-不在范围 | 1 | 2 |
| **D-反问** | **12** | **2** |

🔑 **必须连跑三轮才算数**：单次读数会漂（同一问句在不同轮次落 A 或 D 都见过，
缓存刚失效时尤其明显）。我中途有一次单跑读到 D=3 且构成完全不同，
差点写成结论。**T3 是 LLM，单次结果不是稳态。**

### 剩下的 2 条 D（根因已查实）

| 问句 | 根因 |
|---|---|
| `最近30天食材成本占营收多少` | **planner 标错维度**：T3 给 `dimensions=('ingredient',)`，但这是**全店比率**不是食材粒度。`RECIPE_COST` 声明 `{dish}` 是对的，⛔ **不该放宽能力表** —— 要改的是 planner 的维度判定 |
| `最近30天各门店对比如何` | 待查（本轮末尾才稳定落 D，之前多次为 A）。用 `执行前拦截` 日志看它的 intent/dimensions |

### ⚠️ `供应商报价` 稳定落 C 而不是 B —— data_gaps 没接上

PR#2371/#2372 的 B 类文案**单打接口时是对的**（实测返回「供应商报价目前还没有
数据…」），但走完整问答链路时稳定落 **C-不在范围**。说明 `resolve_out_of_domain`
里那个 `honest_gap_answer` 分支在这条链路上没被走到 —— 大概率是
`query` 参数没透传进 resolver（`resolve_by_code` 按签名过滤 kwargs）。

🔑 **判据：「单打接口通了」≠「走用户那条路也通」。**本轮第二次栽在这上面
（第一次是 ④ 只挂 Tool）。**验收必须走用户实际那条链路。**

---

## 🎯 剩余 3 条 D：性质已全部确定，下一轮直接照做

**两条是缺 resolver（数据都在，实测有量），一条是 planner 标错维度。没有一条是路由问题。**

| 问句 | 性质 | 数据实测 | 下一轮要做的 |
|---|---|---|---|
| `哪个时段生意最好` | **缺 resolver** | 按 POS 时间戳现切：晚市 109,772 单/¥41,319,090 · 午市 72,716/¥27,319,788 · 下午茶 18,194/¥6,853,738 | `resolve_daypart_performance`。⚠️ 聚合表 `agg_daily_order_type_meal.meal_period` **全是「未分类」**，要么现算要么先修 ETL |
| `折扣力度多大` | **缺 resolver**（无折扣意图、无 resolver） | 近 30 天 **76,768 / 201,926 单（38%）有折扣**，`fact_pos_transaction.discount_amount` 有值 | 新增折扣意图 + resolver。⚠️ `fact_pos_discount` / `dim_discount` 都是 **0 行**，只能用 transaction 上的金额，别去 join 那两张 |
| `食材成本占营收多少` | **planner 标错维度** | — | T3 给了 `dimensions=('ingredient',)`，但这是**全店比率**不是食材粒度。`RECIPE_COST` 声明 `{dish}` 是对的，**不该放宽能力表** |

⛔ **前两条别再写一份时段/折扣切分 SQL**：时段那段 `CASE WHEN EXTRACT(HOUR ...)`
已经在 `staffing_forecast.py` 里；先抽共用片段再用（与本轮 `dish_margin.py` 同一做法），
否则时段边界会长成两处定义。

🔑 **判据（本轮反复验证）：`_RESOLVER_DIMENSIONS` 是能力声明，不是开关。**
resolver 不支持却放宽它，等于用错粒度回答，比反问更糟。这三条要修的都是
**终点（resolver）或 planner**，不是那张表。

---

## 📋 六块可达性表（2026-08-07 prod 实测，`mock_ops` / 活跃槽 10010）

| 块 | 后端出口 | 前端入口 | prod 实测 | 状态 |
|---|---|---|---|---|
| **①** 顺带提示 | `GET /api/mobile/{fid}/findings?domain=restaurant` | `DashboardRestaurant.vue`（店长落地页「今日营运台」） | **200**；`checkedRules=['菜品毛利谜题','损耗类型集中度']`、`failedRules=[]`、三态完整 | ✅ |
| **②** 岗位入口 | 粗粒度 `restaurant:*` + 五部门模块 | `DepartmentDashboard.vue` × 5 条路由（`/restaurant/{ops,marketing,procurement,hr,finance}`） | 采购角色 **6 行 → 22 行**；阴性对照 200/401/403 | ✅ |
| **③** AI 价值汇总 | `GET /api/mobile/{fid}/ai/value-summary` | `AiValueSummary.vue`（`/dashboard/ai-value`，**菜单入口本轮才补上**） | `success=true`；`costInYuan=null` + `costUnavailableReason`「编一个费率会得到看似精确的假数字」 | ✅ |
| **④** 策划案 | `GET /api/mobile/{fid}/findings/action-plan?domain=` | 同 ① 的落地页可展开 | **409**「生成的行动建议里出现了系统数据中不存在的数字 **[80]**，已拒绝返回」 | ✅ 见下 |
| **⑤** 改价改品 | `POST .../action-proposals/{code}/preview` → confirm（**已存在**，通用框架） | ❌ **无**（web-admin 里没有菜品管理页，只有只读的「菜品分析」） | 接口在，无 UI | ⚠️ |
| **⑥** 动态办公室 | 后端即 ③ | 即 ③ 的「AI 工作台」页（本轮补上入口后可达） | 同 ③ | ⚠️ 见下 |

### ④ 的 409 是**闸在正常工作**，不是故障

`GroundedNumberValidator` 抓到 LLM 编了个「80」，**整次生成被拒**。这是 goal 架构
原则在 prod 运行时的实证 —— 「一个编了数字的行动建议比没有建议更糟，它会被照着执行」。
早先同一接口返回 200 是那次生成恰好没编数字（LLM 不确定性）。
🔑 **判据：同一个接口多打几次，才分得清「坏了」和「闸响了」。**

### ⑤⑥ 的准确状态

- **⑤**：REST 出口**已存在且是通用的**（`RestaurantAgentRunController` 的
  preview→confirm）。缺的是菜品页面上的入口 —— 而 web-admin **没有菜品管理页**，
  要从零建。goal 写「别另发明」，而这是**写路径**（改客户菜单价格），
  我不在本轮建。接手请接已有的那两个端点，**别新造写接口**（会绕过 preview→confirm）。
- **⑥**：memory 记「后端已被 ③ 覆盖，剩前端」。③ 的前端就是标题字面为
  「**AI 工作台**」的 `AiValueSummary.vue` —— 它一直存在但**点不到**，本轮补上入口后可达。
  「动态」还应额外做什么，仓里**没有 spec**；goal 明写「别另发明」，故未扩。

---

## ⛔ 还欠的

### ⑤ 对话改价改品 —— REST 出口**已经存在**，缺的是 UI

`RestaurantAgentRunController`（`/api/mobile/{factoryId}/restaurant-agent/runs`）
已经有一套**通用的 preview → confirm 框架**：

```
POST /{runId}/action-proposals/{proposalCode}/preview   -> 预览(不落库)
POST /{runId}/action-proposals/{proposalCode}/...       -> 凭 previewToken 执行
```

`proposalCode` / `actionCode` 是**动态**的（不是枚举），所以框架本身不限定动作类型。

🔑 **这和 ② 是同一个形状**：接口层已经有承载，缺的是前端入口。
再写一个 `POST /dishes/{id}/price` 就是给「改价」造第二处写入口，而且会**绕过**
已有的 preview→confirm 契约（仓里 `_READ_ONLY_ACTION_WARNING` 的原话：
「如需执行，请切换到操作模式，**先生成预览，确认后再执行**」）。

**接手要做的**：在菜品页面接上面那两个端点，而不是新造写接口。
⛔ 这是**写路径**（改价格、下架菜品），按 CLAUDE.md 的 UX Flow Gate 必须先过
`ux-flow` skill —— 低技术素养用户 + 不可逆的价格改动，误点代价很高。

### 🔴 ③「AI 价值汇总」一直点不到 —— 有路由没菜单入口（PR#2378 已修上线）

交接和 goal 里都写着「③ ✅ 有独立页」。**页面确实在，但用户点不到。**

```
router/index.ts : path 'dashboard/ai-value' + title 'AI 工作台' + icon + module ← 齐全
menuConfig.ts   : ai-value 零命中                                              ← 没入口
```

按 goal 自己的判据「**调用次数 0 = 没做，不接受『代码在那儿』**」，③ 此前是不达标的。
我前面几轮一直把它记成 ✅，因为**只看了路由那一侧**。

🔑 **判据：判可达性要菜单/路由两侧一起看。** 只看路由会以为它通了。

**已补一道会红的闸** `routedPagesAreReachable.spec.ts`（`dashboard/*` 下有路由的
顶层业务页必须有菜单入口）。它**当场又抓到第二个**（`/dashboard/widgets`，确实是
开发演示页，按「加白名单要写理由」显式豁免）。

⚠️ 我自己在这道闸上又栽一次：白名单第一版按**组件名**写（`dashboard/widget-demo`），
而路由路径是 `dashboard/widgets` —— 白名单没生效，闸照样红。**闸把我的错也抓出来了。**

⚠️ 闸刻意只守 `dashboard/*`：一次性扩到全仓会一次红几十条，变成噪音然后被整体豁免。
另有一条断言钉住「至少解析到 3 条路由」—— 正则失效时先红，而不是静默通过 0 条。

**接手可以做的**：把这道闸扩到 `/restaurant/*`、`/procurement/*` 等分组，
一组一组来（每扩一组，先看红几条，逐条判是补菜单还是写豁免理由）。

### ⑥ 动态办公室 —— 未开工


后端已被 ③ 覆盖（`system_ai_value_summary` + `AiValueSummary.vue`，**入口已于
PR#2378 补上**），再写一份会违反「一个指标只能有一个定义」。剩纯前端。

⚠️ **订正**：我前面几轮一直说 ⑥「须过 `ux-flow` 闸」，那是**过度应用**。
CLAUDE.md 的 UX Flow Gate 触发条件是 **RN 屏幕 + operator/仓管/质检员** 角色/路径，
⑥ 是 web-admin 的店长看板，**不在闸的范围内**。它不是被闸挡住的，是确实没做。
（⑤ 的 UI 是**写路径**改价格，那个该慎重，但同样不是 ux-flow 闸的适用对象。）

### 🔑 ②⑤ 两次得出同一个结论，值得单独记

我在本轮**两次**准备「补一个非对话出口」，两次都在动手前发现**承载已经存在**：

| 块 | 我以为缺的 | 实际已有 | 真正缺的 |
|---|---|---|---|
| ② 岗位入口 | 餐饮五部门的 workdesk | `departmentConfig.ts` + `DepartmentDashboard.vue` + 5 条路由 | 采购部长的权限（**本轮已修**） |
| ⑤ 改价改品 | 菜品写接口 | `RestaurantAgentRunController` 的 preview→confirm | 菜品页面上的入口（前端） |

**判据：交接里写「❌ 只活在对话里」时，先去 controller 目录数一遍，再决定要不要新建。**
造第二处定义的代价比缺一个入口高 —— 而且新造的那个多半会绕过老的那套约束
（⑤ 就会绕过 preview→confirm）。

### B 类归宿完全缺失（G1 暴露的独立缺陷，未修）
15 个代表性问句里 **B=0** —— 系统**从不**说「这项数据没采集，缺的是 X 表」，
一律用反问顶替。已知该落 B 的至少三项（`agg_supplier_price` 0 行 /
员工表 0 行 / `actual_receive` 全空）现在都答不出也说不清。
**修法**：在意图识别之后、反问之前加一道「这个意图依赖的表有没有数据」的检查，
命中就直接产出 B 文案（点名缺哪张表 + 客户要做什么）。
⚠️ 这道检查必须在**确定性**层做，不能交给 LLM 判断 —— 它不知道哪张表是空的。

---

## 📌 本轮踩出来的判据

1. **规则写完先拿真数据跑一遍看它出几条。**「阈值看起来合理」≠「在真客户数据上
   说得出话」。低毛利规则在 prod 出 0 条就是这么发现的。
2. **数承载点时把测试数进去。**「采购退役」有三处承载，第三处是一条会红的断言 ——
   只清代码不清断言，下一个人照样被拉回错的口径。
3. **半成品若在确定性路径上新增 LLM 调用，宁可撤回。** 判据不是「改进了多少」，
   是「有没有违反架构红线」。
4. **`git commit -- <paths>` 认不了未 tracked 的新文件**，必须先 `git add`。
   （与 memory 里「重命名时删除不会被带进去」是同一类坑。）
5. **手工装配的单测会被构造签名变化打穿。** 抽共用件时别只看主代码编译过 ——
   `@InjectMocks` 遇到没 mock 的协作者会给 null。修法是 `@Spy` 装**真实**实现
   （不是 mock：那些断言测的正是被抽走的那段逻辑）。
6. **prod 的 Python 是 3.11.13**（`/www/wwwroot/cretas/code/backend/python/venv-current/bin/python`），
   系统 `python3` 是 3.6.8 —— 别拿系统解释器判兼容性。
7. **发布锁判活要看 PID 不看年龄**。本轮的锁 11 小时未动、PID 61920 已死，
   脚本自动清理了。`Get-Process -Id <pid>` 是判据。
8. **校验器只管它管的那一样。** `GroundedNumberValidator` 把「编数字」堵死了，
   于是很容易以为 LLM 产出已经安全 —— 而它对**归因**完全沉默。上线后实测才
   看到「排查罗氏虾变质原因」这种数字全对、结论全错的建议。
   判据：**问「这道闸不管什么」，不只是「这道闸管什么」。**
9. **先问「这件事已经有承载了吗」。** ② 差点被做成第二套岗位入口 —— 而餐饮的
   5 个部门看板就是它的承载，且已可达。造第二处定义的代价比缺一个入口高。
10. **上线不是终点，是新一轮观察的起点。** 本轮 4 个 PR 里有 **2 个**（#2362 份数
    渲染成 float、#2364 归因错）是**上线之后打真环境才看到的**，本地测试全绿。
    判据：**部署完立刻用真账号走一遍并读输出的每一个字**，别只看 HTTP 200。
11. **`mvn` 偶发 `Could not initialize plugin: MockMaker`** 是 byte-buddy agent
    挂载失败的**环境态**，重跑即绿。特征：只有用 Mockito 的类全挂，纯 POJO 测试
    不受影响。别当代码缺陷去追。
12. **链式 `gh pr merge && git checkout && deploy` 期间读到的文件内容不可信** ——
    checkout 瞬间的快照会显示旧内容。判据是 `git log` 与 `grep` 盘上文件，
    不是编辑器/linter 的即时读数。
13. 🔴 **构建期间不要碰 worktree（哪怕只改一个 md）。** 本轮第 3 次发布挂在
    `ERROR: worktree changed during release build; manifest not written` ——
    我在 4 分钟的构建窗口里编辑了交接文件。发布脚本有一道闸保证制品对应一个
    **已知干净的树**，工作树一动就拒绝写 manifest。
    ⚠️ 而 harness 报的是 **exit code 0**，脚本自己的输出才是 ERROR（第 N 次印证
    「通知里的退出码不可信」）。
14. **Monitor 的过滤必须覆盖所有终态，不只是成功。** 上一条那次失败，我的 grep
    只有 `RELEASE_FINAL_STATUS|结果 SUCCESS|BUILD FAILURE`，`worktree changed`
    不在里面 —— Monitor 一路沉默，看起来和「还在跑」一模一样。
    **沉默不等于成功。**
15. **蓝绿槽位一天变了三次**：10010 → 10020 → 10010。任何写死端口的探针都会
    在下一次发布后给出假阴性。判据永远是 `ss -lntp | grep -E ':10010|:10020'`。
16. 🔴 **`gh` 的时间戳是 UTC，本机是 CST(+8)，不要直接相减。** 本轮我据此断定
    「CI 停摆 9 小时」并写进交接和两个 PR 描述 —— 实际只差 1 小时，CI 一直正常。
    一个时区减法错误让我在**四个 PR 上都跳过了看 CI 这一步**。
    判据：先 `date -u`，再比。
17. 🔴 **`failure` 不等于测试红 —— 先看是不是 `cancelled`。** 本轮 PR 上那三个
    「失败」全是 `concurrency: cancel-in-progress` 造成的取消（push 与
    pull_request 在同一 ref 各触发一次，互相取消），而 `gh pr checks` 把取消
    也显示成失败。判据：`gh run view <id> --json jobs` 看 job 的 conclusion。
18. **判「某个闸还在不在」要看文件，不要看 `gh workflow list`。** 那个列表里
    至今还有 `JPA repository query gate (PR)`，而它的 yml 早在 `e6d1fffe75`
    就被删了 —— GitHub 会一直列着已删工作流的历史条目。

---

## 可复用的 prod 查询

```bash
ssh root@47.100.235.168
ss -lntp | grep -E ':10010|:10020'     # 蓝绿槽位会变(本轮活跃槽是 10010)

TOK=$(curl -s -X POST 'http://127.0.0.1:10010/api/mobile/auth/unified-login' \
  -H 'Content-Type: application/json' \
  -d '{"username":"mock_ops","password":"123456","deviceInfo":{"deviceId":"probe","deviceModel":"probe","platform":"web","osVersion":"1"}}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')

# 发现层主动出口
curl -s "http://127.0.0.1:10010/api/mobile/MOCK_REST/findings?domain=restaurant" -H "Authorization: Bearer $TOK"
# 毛利口径(新规则的数据源)
curl -s 'http://127.0.0.1:8083/api/smartbi/restaurant-ops/gross-margin?days=30' -H "Authorization: Bearer $TOK"
# 新增: 毛利发现规则
curl -s 'http://127.0.0.1:8083/api/smartbi/gold/restaurant-margin-findings?factory_id=MOCK_REST&rule=puzzle_dishes' -H "Authorization: Bearer $TOK"
# 问答链路(测反问/答案)
curl -s -X POST 'http://127.0.0.1:8083/api/smartbi/gold/restaurant/tiered-answer' \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -d '{"factory_id":"MOCK_REST","query":"最近30天总营收是多少"}'
```

⚠️ 打 Python 只带 `Authorization`，别同时带 `X-Internal-Secret`（后者不设 role，
金额会被全员脱敏，看起来像「数据坏了」）。

---

## ⛔ ⑤⑥ 已完成但卡在 GitHub 账号停用（2026-08-07）

**代码写完、测试全绿、本地 commit `8a16cf8989`，但推不上去也部不了。**

```
remote: Your account is suspended. Please visit https://support.github.com
fatal: unable to access 'https://github.com/Stevenjxie/cretas.git/': 403
HTTP 403: Sorry. Your account was suspended (https://api.github.com/graphql)
```

账号级停用（不是 token 失效：`git push` 与 GraphQL API 都被拒）。

🔑 **为什么不绕过**：铁律是「任何通道都必须推上 origin/main 后才可部署」。从 feature
分支直接部 prod 正是 2026-05-30 事故的成因（共享 jar 路径 last-write-wins，
RBAC 修复被并发 session 覆盖，总营收回归 ¥0）。**账号恢复前，⑤⑥ 不部署。**

### 账号恢复后的动作（按序）

1. `cd ../cretas-rest-ai && git push -u origin codex/claude-finding-next-steps`
2. 开 PR → 合并 main（碰 Java + web-admin，**必须走 PR 轨**，CI 的 JPA 闸挂在 PR 上）
3. `git checkout main && git pull` → `./scripts/deploy/release-cretas.sh --phase deploy ...`
   （Java + web-admin 两侧都要）
4. **G5 实测**：以 `mock_ops` 打 `GET /api/mobile/MOCK_REST/findings`，确认响应含
   `nextSteps` 且每条有 `target`/`module`；再开落地页确认按钮渲染并能跳转。
   ⚠️ 判据是**日志里的真实调用记录**，不是「代码在那儿」。

### ⑤⑥ 交付内容（已完成部分）

| 项 | 状态 |
|---|---|
| `FindingNavigation`（code→落点唯一承载） | ✅ 新建 |
| `FindingController` 透出 `nextSteps` | ✅ |
| `RestaurantAgentActionProposalMapper.NAVIGATION_TARGET` 改为引用常量 | ✅ 消除第二份字面量 |
| 前端两道守卫（`canAccess` + 路由可解析） | ✅ |
| Java 闸 7 条（含变异验证：点名 `WASTAGE_SHARE_SPIKE` 红） | ✅ 74 passed |
| Web 闸 6 条（含变异验证：点名不存在落点红） | ✅ 236 passed / 29 files |
| `vue-tsc -b --force` | ✅ rc=0 / 0 error |
| **prod 部署 + G5 实测** | ❌ **被账号停用阻断** |

📌 **⑤ 的形状纠正（比代码更重要的一条）**：「对话改价改品」是错的描述。唯一的动作提案
是 `READ_ONLY_PROPOSAL`，**从不写数据**，批准后给的是一个跳转目标。所以这块永远不需要
新的写接口——需要的是让发现自己带上落点。

---

# 📦 交付物（G5 实测补齐，2026-08-07 晚）

## 1. 基线 vs 改善对照表

⚠️ **先说口径**：`g0g1b.sh` 与 `g0g1c.sh` md5 相同 = **基线脚本**（短问法）。
我此前报的 A=12 出自 `g1r.sh`，那是我自己给 13 条问句加了「最近30天」前缀的**改写版**。
**两者不是同一批问句。**

| 问句集 | A 有答案 | B 诚实缺数据 | C 不在范围 | D 反问(禁止) |
|---|---|---|---|---|
| **基线**（短问法，改动前） | 2 | 0 | 1 | **12** |
| **同一批短问法**（今天实测） | **5** | 0 | 1 | 9 |
| 加「最近30天」前缀（改写版） | 12 | 0 | 1 | 2 |

🔑 **同一套代码、同一批意图，只因为问句加了时间前缀，A 就从 5 变成 12。**
差额几乎全是「用户没说时间 → 反问」。判据：**报改善必须与基线用逐字相同的问句集。**

延迟/token（短问法实测，token 取自 `smart_bi_llm_usage` 增量）：

| 指标 | 基线 | 今天 |
|---|---|---|
| D 类烧掉的 token | 34,113（占总量 77%） | 约 23k（9 条 × ~3.2k） |
| D 类 LLM 调用 | 14 次（占 82%） | 9 次 |
| 单问最慢 | 10,228ms | 12,230ms（食材成本，已修但未部署） |
| 零 LLM 命中（T1 关键词） | — | 5 条 0 token 0 次（总营收/哪个菜/损耗/库存/天气） |

## 2. 问题 × 归宿表（短问法，2026-08-07 prod 实测）

| 问句 | 耗时 | token | LLM | 归宿 |
|---|---|---|---|---|
| 最近30天总营收是多少 | 1150ms | 0 | 0 | **A** `SALES_SUMMARY` |
| 外卖和堂食各占多少 | 4394ms | 3258 | 1 | **A** `CHANNEL_MIX` |
| 哪个时段生意最好 | 4632ms | 3251 | 1 | **A** `DAYPART_PERFORMANCE`（本轮新建） |
| 库存有什么要注意的 | 27ms | 0 | 0 | **A** `INVENTORY_WARNING` |
| 员工人效怎么样 | 5150ms | 3258 | 1 | **A** `STAFFING_ADVICE` |
| 明天天气怎么样 | 21ms | 0 | 0 | **C** `OUT_OF_DOMAIN` |
| 加权毛利率是多少 | 4808ms | 0 | 0 | D ← 缺时间窗（已修·待部署） |
| 哪个菜卖得最好 | 21ms | 0 | 0 | D ← 缺时间窗（已修·待部署） |
| 毛利最低的菜品有哪些 | 4738ms | 3256 | 1 | D ← 缺时间窗 |
| 食材成本占营收多少 | 12230ms | 3249 | 1 | D ← 缺时间窗 |
| 最近损耗情况怎么样 | 4222ms | 0 | 0 | D ← 缺时间窗 |
| 营收趋势怎么样 | 4741ms | 1215 | 1 | D ← 缺时间窗 |
| 折扣力度多大 | 4531ms | 3265 | 1 | D ← 缺时间窗 |
| 哪个供应商报价最贵 | 4959ms | 3213 | 1 | D（B 文案是好的，T3 路由不稳定） |
| 各门店对比如何 | 4470ms | 3252 | 1 | D（答案契约覆盖不足，**未修**） |

## 3. 六块可达性表（G5，mock_ops @ prod）

| 块 | 页面 / API | 实测 | 日志中的真实调用 |
|---|---|---|---|
| ① 顺带提示 | `GET /api/mobile/MOCK_REST/findings` | ✅ `totalCount=2 checked=2 skipped=1 failed=0 complete=true`；首条是**毛利谜题**（非损耗口径）→ **G2 达成** | ✅ **6 次**「发现层查询」 |
| ② 岗位入口 | web-admin `139.196.165.140:/www/wwwroot/web-admin` | ✅ 今日 13:25 构建；`今日营运台` 17 个 bundle、`人力调度台` 17 个、`restaurant_purchaser` 70 个 | ✅ 在部署产物中 |
| ③ AI 价值汇总 | `/dashboard/ai-value` | ✅ `ai-value` 命中 **37 个 bundle** | ✅ 在部署产物中 |
| ④ 策划案生成 | `GET /findings/action-plan` | 🔴 **4/4 次 409**，从未产出建议 → **实际不可用**。根因已定位并修复，**待部署** | ✅ **18 行**，全部 `ungrounded=[52]` |
| ⑤ 非对话动作入口 | `nextSteps` 字段 | ❌ 实测响应**无该字段** = 未部署 | — |
| ⑥ 动态办公室 | 落地页「今天先做」 | ❌ 同上 | — |

⚠️ **我差点把 ②③ 误报成「一个月没上线」**：先量的是 `47.100.235.168:/www/wwwroot/web-admin`
（Jul 6 构建、新入口零命中），而 web-admin prod 实际在 **139.196.165.140**，47.100 上那个
是遗留目录。**判据：量之前先确认部署脚本的目标主机。**

## 4. G3 阴性对照证据

- **架构层**：15 条问句里 **5 条 0 token / 0 次 LLM**（总营收、哪个菜、损耗、库存、天气）
  —— 走 T1 关键词直达 resolver，LLM 全程没参与，数字仍然正确。
- **闸层**：`GroundedNumberValidator` 在 prod **真的拦下过**（④ 的 18 次 409）。它不是
  摆设——这也正是它暴露了 ④ 的口径缺陷。
- **测试层**：`ActionPlanPromptIsSelfConsistentTest` 含阴性对照（旧接线仍会被拒），
  `FindingNavigationTest` 与 `findingNextSteps.spec.ts` 都做了变异验证并点名红。

## 5. 仍然欠的

| 项 | 状态 |
|---|---|
| ⑤⑥ 部署 + G5 | ⛔ GitHub 账号停用，push/PR 全 403 |
| ④ 修复部署 | ⛔ 同上（本地 `c5aab1f680`） |
| 时间窗默认部署 | ⛔ 同上 |
| `各门店对比` 答案契约 | ❌ 未修，需查 `required_elements` 与 resolver 实际产出的差集 |
| `供应商报价` 路由稳定性 | ❌ 未修，需让「已知缺口」判定早于 T3 路由 |

---

## ⛔ 上线通道穷尽验证（不是我保守，是机械上不通）

已逐条试过，四条路全断：

| 通道 | 结果 |
|---|---|
| `git push` HTTPS → origin | `remote: Your account is suspended` / 403 |
| `ssh -T git@github.com` | `Permission denied (publickey)` —— 无 key |
| `gh` CLI（PR/API） | `HTTP 403: Sorry. Your account was suspended` |
| `git fetch origin main` | 同 403 —— **连读都读不到** |

🔑 **发布脚本自己就先拒了**：`release-cretas.sh:191-193` 要求
`HEAD == origin/main`，而 `git fetch` 失败意味着本地 `origin/main` ref 是过期的、
且我的 4 个 commit 不在它上面 → 闸会直接
`ERROR: deployment requires clean exact origin/main` 退出。
**所以「先部署、回头补 push」在这条流水线上根本执行不了**，不存在绕过与否的选择。

⛔ **四个部署入口全都有同一道闸**（不是只有统一入口）：

| 入口 | 闸 |
|---|---|
| `release-cretas.sh:191` | `HEAD != origin/main` → ERROR 退出 |
| `deploy-backend.sh:267` | 落后 origin/main 或脏树 → **ABORT**（原先只 WARN，后来收紧） |
| `deploy-smartbi-python.sh:194` | 同上 |
| `deploy-web-admin.sh:206` | 同上（它从本地工作树 build Vite dist，落后就会 ship stale code） |

所以**连「换个入口部署」这个选项都不存在**。要绕就得改部署脚本自己的安全闸 ——
那既违反铁律、又属于 fastlane 明令强制 PR 的高风险路径、还正是 5/30 事故的形状。
**没有第二条路可选，只有账号恢复这一条。**

仓里另有一个 `server-mirror`（`ssh://root@47.100.235.168/.../code/.git`），但**不能拿它顶替**：
铁律里 origin/main 是多 session 的**唯一汇合点**。从一个别的分叉点部署 prod，正是
2026-05-30 事故的形状（共享 jar 路径 last-write-wins，另一个 session 一部署就把我的
改动静默覆盖，总营收回归 ¥0）。goal 自己的红线也写着「worktree 隔离且只从 main 部署 prod」。

### 待部署的四个本地 commit（按序）

| commit | 内容 |
|---|---|
| `8a16cf8989` | ⑤⑥ 发现自带「下一步去哪」+ 工作台随数据变化 |
| `c5aab1f680` | ④ 确定性 409 修复 + 缺时间窗就反问 |
| `83764906e6` | planner 自造指标要求 + 缺口判定提到路由之前 |
| `59245e2c31` / 本条 | 交接与交付物 |

账号恢复后：`git push -u origin codex/claude-finding-next-steps` → PR → 合 main →
`git checkout main && git pull` → `release-cretas.sh --phase deploy` （Java + Python +
web-admin 三侧）→ 按上面「G5 实测」那节逐条回验。
