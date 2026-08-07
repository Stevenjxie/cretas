# 交接：餐饮 AI「问什么答什么」第一轮（2026-08-07）

> 起点：`HANDOFF-2026-08-06-restaurant-ai-and-departments.md`（小蓝店长六块）
> 本轮 goal：LLM 只做「自然语言 → 意图+槽位」，取数/算数/判定/成文全部确定性代码。

---

## ⚠️ 先读这条：CI 全仓停摆

**GitHub Actions 已约 9 小时零个 run 被创建**（最后一次 `2026-08-06T16:54Z`），
而这期间 main 合入了 PR#2357 / #2358。`actions/permissions` 是 `enabled: true`，
路径触发条件也匹配 —— 多半是私有仓额度耗尽。

**含义**：最近几次进 main 的改动**同样没跑过 CI**，不只是本轮。接手第一件事
先确认它恢复了没有：

```bash
gh api "repos/Stevenjxie/cretas/actions/runs?per_page=3" \
  --jq '.workflow_runs[] | .created_at + "  " + .name + "  " + .conclusion'
```

📌 顺带纠正一条仓库规则里的错话：`.claude/rules/worktree-and-main-only-deploy.md`
写「CI `JPA repository query startup gate` 挂在 PR 上」，实测
`jpa-gate-main.yml` 的触发是 `push: branches: [main]` —— **PR 上根本不跑**。
PR 上真正会跑的是 `python-gate` / `web-admin-gate`（都按 paths 过滤）。

---

## 一句话

本轮**没有**做完六块。做完的是两件由 prod 实测驱动的事，外加一个把最大缺口
量出来但**主动撤回**的半成品。撤回那条比做完的两条更值得读。

---

## 已上线（PR #2361 → `e6f52e826d`；PR #2362 → `8fa379812a`）

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

## ⛔ 还欠的（六块里没做的四块）

### ② 岗位入口
`/workdesk/*` 七个工作台全是工厂角色，餐饮五部门一个都没接。

### ④ 策划案生成 —— 比交接写的更深
不只是「没有 REST 出口」。`FindingActionPlanTool:43` 的
**`DOMAIN = "inventory"` 是硬编码的**，第 112 行
`findingService.detectInline(factoryId, DOMAIN)` —— 餐饮租户走这个 Tool 拿到的是
**库存域**的发现（`LowStockFindingProvider`），与餐饮的毛利/损耗规则完全不搭。
所以对餐饮是**双重坏**：域接错 + 没有非对话出口。

### ⑤ 对话改价改品
功能本来就有，缺的是让店长在菜品页面直接做。未调研。

### ⑥ 动态办公室
前端没做，须过 `ux-flow` 闸。未开始。

### G1 / G3 未完成
问题清单 × A/B/C 归宿表只做了抽样（见上面那张反问表），没有系统枚举。
G3 阴性对照（把 LLM 换成 echo stub 验数字仍正确）**没跑**。

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
