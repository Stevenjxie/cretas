# GOAL SETTING —— 小蓝店长六块「落到用户面前」（确定性体系驱动，非 LLM 工具调用驱动）

> 日期：2026-08-07
> 前序：`HANDOFF-2026-08-06-restaurant-ai-and-departments.md`（16 PR 已上线）、memory `project_2026_08_05_ai_six_blocks_and_usage_truth.md`
> 决策权：**执行方全权决策**，判据见 §6。不要为可自行判断的事回来问。

---

## 1. 目标（一句话）

**把小蓝店长六块从「代码在 jar 里」推进到「店长真的看得到、且不用提问就能看到」，产出内容的主体是我们自己的确定性体系，不是 LLM 调工具。**

LLM 只负责把结构化事实说成人话，**不负责决定去查什么，更不负责算数**。

---

## 2. 为什么是这个目标：六块的代码都在，但一半够不着用户

prod 实测（2026-08-07，运行中 jar `aims-0.0.1-SNAPSHOT.jar`，8-06 23:30 构建）：

| 块 | 代码在 jar | 够得着用户 | 缺口 |
|---|---|---|---|
| **① 顺带提示** | ✅ | ✅ **两个出口都通** | 规则口径要换（见 §4） |
| **② 岗位入口** | ✅ `WorkdeskRole` | ⚠️ 半通 | `/workdesk/*` 七个工作台全是**工厂角色**，餐饮五部门一个都没接 |
| **③ AI 价值汇总** | ✅ | ✅ 有独立路由页 | — |
| **④ 策划案生成** | ✅ `FindingActionPlanTool` | ❌ | 只是 AI Tool，controller / web-admin **零 REST 出口** |
| **⑤ 对话改价改品** | ✅ | ❌ | 只活在对话里 |
| **⑥ 动态办公室** | 后端被③覆盖 | ❌ | 前端没做，须过 `ux-flow` 闸 |

**判定「够不够得着」的唯一判据**（memory 实测，近 30 天）：

| | F006 | 六膳门 |
|---|---|---|
| 物料批次 | 243 | 54 |
| 生产批次 / 报工 | 12 / 3 | 21 / 16 |
| **AI 会话** | **0** | **0** |

`conversation_sessions` 近 30 天共 12 个，**全部来自演示租户，真实工厂 0**。
→ **不是「没有用户」，是「有每天在用的用户，而 AI 那一层对他们不存在」。**
→ **只挂在 AI 对话里的功能 = 没做。** 这是本轮全部工作的出发点。

① 已经趟通了这条路（`FindingController` 主动出口 + `DashboardRestaurant.vue` 今日营运台卡片），**②④⑤⑥ 照抄这条路即可，不用另发明**。

---

## 3. 验收判据（可测，不接受"看起来能用"）

### G1 可达性：每一块都有非对话出口

六块逐一，在 prod 上以 `mock_ops`（店长）登录、**零提问**状态下走一遍。判据：

- 每块都能指出**具体的页面路径 / API 路径**，并附实测响应
- **日志里能看到它被真实调用过**（调用次数 0 = 没做，不接受"代码在那儿"）
- ④⑤ 必须有 REST 出口，不能只是 `@Tool`

### G2 主动性：不提问也有内容

落地页在零提问状态下至少一条**非损耗口径**的发现；同一条发现在提问任意问题时也作为顺带提示出现（复用同一份 `FindingService.Result`，**不得两处各算一遍**）。

### G3 LLM 用量：可证明的低 —— 用阴性对照测，不是数次数

⚠️ **数调用次数测不出来**（次数少也可能是它一次就编完了）。判据是：

> **把 LLM 整个断开（换成 echo stub），所有数字仍应正确产出，只是话说得难听。**

做不到就说明数字是 LLM 编的。多轮 agentic tool-calling 视为**设计失败**，回去补确定性路径。
每个数字必须能在日志里追到产生它的那条 SQL / resolver。

### G4 三态纪律不塌

`checkedRules` / `skippedRules` / `failedRules` 三个桶端到端保持可区分：
「真的没有」/「数据没采集到」/「查询失败」必须是三句不同的话。压成"有/没有"判失败。

---

## 4. 每块的具体缺口与做法

### ① 顺带提示 —— 换口径，不换骨架

骨架不动（`FindingProvider` SPI → `FindingServiceImpl` 三态 → `FindingTextRenderer` 纯模板 → 两个出口）。

**要换的是规则口径**：现在跑的两条都是损耗，而 **Steve 已明确「损耗意义不大」**——工厂按批次追损耗是因为投入产出一一对应，餐饮的浪费最后都体现在毛利被吃掉。
→ 换成**毛利/成本口径**。⛔ **不要继续加损耗规则。**

🔴 **本轮已实测踩过一次**：一版「低单份毛利菜」规则在 prod 产出 **0 条**（低毛利的米饭/酸梅汤恰好都在销量中位数以下被闸挡掉；去掉闸则出米饭+酸梅汤 = 噪音，店长早知道米饭不赚钱）。真正有信息量的是**谜题象限**（高单份毛利、卖不动）——实测唯一命中的是罗氏虾：每份赚 ¥78.57 全店最高（中位 ¥27.51），销量却在最低档。

**两条损耗规则的处置**：排序天然会把它们挤下去（`rankScore = severity×100 + actionability`，`inline-max=2`），可以先留着当兜底。若决定退役，**必须在代码层退役**（`@Deprecated` + javadoc 写判据 + 一道会红的断言闸）——只在配置/数据层停用会被下一个 session 原样加回来（厨师长就是这么被反复加回来的）。

### ② 岗位入口 —— 接餐饮五部门

`/workdesk/*` 现有七个工作台全是工厂角色。餐饮五部门（运营/市场/财务/人事/采购）需要各自的工作台入口。

🔴 **开工第一件事：先定采购到底是不是第五个部门。**两处口径打架：
- `web-admin/src/components/layout/menuConfig.ts:341` 注释：「2026-08-06 Steve 拍板：采购职责并入市场(`sales_manager`)，厨师长/餐饮采购退役」
- 同日交接 + `V20261029_63__restaurant_five_department_matrix.sql`：采购是第五个部门，载体 `restaurant_purchaser`

两者不可能都对。定完再动，**并把败的那一侧在代码层清掉**，不要留着让下一个人再撞一次。

⚠️ 部门清单有**五处**承载：`departmentConfig.ts`(权威) / `permission.ts` / `menuConfig.ts` / `router` / `departmentSingleSource.spec`。加维度前数清楚，按**所有调用点**写断言。

### ③ AI 价值汇总 —— 已通，只做回归

已有 `AiValueSummary.vue` 独立路由页。本轮只需确认它对餐饮租户也出数。

**已钉死的两条口径别破**：
1. **不报编出来的金额**：`costInYuan` 恒为 null + 可解释的 `costUnavailableReason`。仓里**没有 token 单价配置**，报「花了 ¥X」必须自己编费率。
2. **不报「省了多少钱」**：缺反事实 + 缺因果。改用告警三段计数（触发/确认/解决），每条可逐行核对。可追溯的是**事件**，不是**金额**。

### ④ 策划案生成 —— 补 REST 出口

`FindingActionPlanTool` + `GroundedNumberValidator` 都在 jar 里，但只是 AI Tool。
→ 加 controller 出口，让「今日营运台」的每条发现可以直接展开成行动方案，**不经过对话**。
→ `GroundedNumberValidator` 是这块的价值所在（数字必须能溯源），别绕过它。

### ⑤ 对话改价改品 —— 补非对话入口

功能本来就有。缺的是让店长在**菜品页面**上直接做，而不是必须去对话里说。

### ⑥ 动态办公室 —— 前端

后端已被③覆盖，再写一份会违反「一个指标只能有一个定义」。剩前端。
⛔ **必须过 `ux-flow` 闸**（CLAUDE.md 的 UX Flow Gate 硬要求）。

---

## 5. 已知地形（本轮实测，别再重新发现一遍）

### 骨架

```
两个出口共用一层：
  主动  FindingController          GET /api/mobile/{fid}/findings
        → web-admin/src/api/restaurant.ts → components/dashboard/DashboardRestaurant.vue
  顺带  RestaurantFindingHintAppender
            ↓ 都调
        FindingService#detectInline(factoryId, domain)
            ↓ 收集 domain 匹配的 @Component
        FindingProvider (SPI)
            ↓ 渲染
        FindingTextRenderer   ← 纯模板，零 LLM
```

- **挂载点唯一**：`IntentExecutionOrchestrator#tryRestaurantTieredDelegate` 有 7 个调用点但全部汇入一处。餐饮提问在到达 Java Tool **之前**就被 tiered 路由委派走了——**挂在 Tool 上等于挂在没人走的路上**（8-06 实测日志 0 次调用）。
- **澄清态不挂提示**：反问「你想看哪家门店」下面接一条发现，店长不知道先答哪个。

### 数据事实（已核）

- **活跃餐饮租户只有 1 个：`MOCK_REST`**。七账号口令统一 `123456`：`mock_rest`(超管) / `mock_ops`(店长) / `mock_market` / `mock_purchase` / `mock_finance` / `mock_hr` / `mock_owner`。
- **🔴 MOCK_REST 是假数据**，租户名自己写着「假 POS 数据接入验证」。实测：30 天营收 **¥7,500 万**（≈¥250 万/天，单店不可能）；10 道菜销量呈**双峰** 143k / 175k，中间是空的（真实餐厅是长尾）；10 道菜 **100% 有配方**，加权毛利率 **67.69%**。
  → **所有阈值必须是相对量**（中位数/分位数/份额），**禁止任何绝对金额或绝对销量常数**。在假数据上调绝对阈值 = 对小说调参。
  → 同时把「样本太少 / 分布退化」写成**诚实跳过**，而不是硬出结论。
- **成本非时变**：`agg_restaurant_product_cost` 建表注释原话 `NOT time-varying (unit prices change over time; we snapshot "current")`。→ **任何「本期毛利率 vs 上期」的结论都是假话**，动的只有销量结构和售价。
- **成本口径已有 5 处承载**：`restaurant_cost_card.py:87` / `restaurant_ops_gold.py:433` / `restaurant_targets_p1.py:302` / `restaurant_finance_etl.py:403` / `resolve_gross_margin`。⛔ **禁止第 6 处**（`FindingProvider` 接口注释明写「禁止新写口径 SQL」）。要用先抽共用函数——`/gross-margin` 端点自己的注释就承认它是第二份（`For cleanliness: extend resolver to return structured rows`）。
- **渠道在 `order_type`**（堂食/外卖/团购），**不在 `channel_origin`——那个字段全 NULL，是死字段**。
- **已知空数据**（只能诚实说缺，不许硬凑）：`agg_supplier_price` 0 行；员工表 0 行 / 排班 4 行 / `staff_id` 在 21.5 万笔账单里填了 0 笔；`actual_receive` 全空。
- **`RESTAURANT_WASTAGE_ANOMALY` 意图指向不存在的 Skill** `restaurant-wastage-anomaly` → 永远落 no-tool 分支。8-06 绕开了，意图本身没修。

### 业务口径（Steve 已拍板，别自己发挥）

- 重点是**加权毛利（按营收贡献加权）、总成本、总营收、总毛利、食品消耗量**。
- **总成本必须分两档呈现**：不含 optional（食材层，现在能算）/ 含 optional（+财务，缺数据）。**不许把「食材毛利」说成「毛利」。**
- **渠道要分开 + 也要总的。**
- **成本卡是 optional**，客户提供不了不能堵塞计算。
- **Cretas 餐饮和 Cretas 工厂是两个产品**，不是一个产品带模块。**重点是算成本，不是溯源**（仓库名「食品溯源系统」会误导）。
- ⚠️ 扫呗/小蓝店长的公开信息**只有一篇软文**（中华网 2026-07-30），WEIBOT 公网查无实质痕迹 → **别把竞品营销当市场证据**。

---

## 6. 决策权与判据

**所有产品/技术决策由执行方自行拍板**，不要回来逐条确认。判据按优先级：

1. **长期正确 > 本轮省事**。会在三个月后变成技术债的捷径，现在就不要走。
2. **少一处定义 > 快一点上线**。宁可先抽共用函数再做功能。
3. **诚实缺数据 > 硬凑一个数**。凑出来的数会被当真，代价比空白高。
4. **删除前先确认没人在用**；**退役必须在代码层完成**（`@Deprecated` + javadoc 判据 + 会红的断言闸）。
5. 只有一种情况回来问：**继续做下去会产生不可逆后果，且两种走法结果差别很大**（删数据、改已上线的对外契约）。

---

## 7. 硬约束

1. **禁止降级处理**：不返回假数据。「查失败」「数据不足」「真的没有」必须是三句不同的话。
2. **一个指标一处定义**。加维度前数清有几处承载。
3. **worktree 隔离 + 只从 main 部署 prod**（`.claude/rules/worktree-and-main-only-deploy.md`）。
4. **Flyway 版本号**：改号前查 **prod 的 `flyway_schema_history`**（仓库文件名不够）；**合并之后再查一次**（并发的人会挑到同一个号）。仓里已有 `FlywayVersionUniquenessTest`。
5. **commit 用 `git commit -- <paths>`** 或 `scripts/safe-commit.sh`。
6. **本机直连不到 47 的业务端口**（安全组只放行网关 139），健康检查要 **ssh 上去打 127.0.0.1**，本地 curl 得 `000` 不代表服务挂了。
7. **打 Python 服务只带 `Authorization`**，别同时带 `X-Internal-Secret`——后者不设 role，金额会被全员脱敏，看起来像"数据坏了"。
8. **前端渲染用服务端成品文案**（`digestLines`），不要拿 `facts` 自己拼：`PriceFieldResponseAdvice` 会把含 `cost` 的数字标量置 null，前端自拼会渲染出空的「¥ 」。
9. **蓝绿槽位会变**（8-07 实测活跃槽是 **10010**，与 8-06 交接写的 10020 已经不同）。核对前先 `ss -lntp`。

---

## 8. 反模式（这些会被判失败）

- ❌ **让 LLM 去决定查什么 / 算什么**。它只负责说话。
- ❌ **挂在没人走的路上**。改完必须去日志确认它**真的被调用了**。
- ❌ **在假数据上调绝对阈值**，然后在真客户那里一条都不出（本轮已实测：一版规则在 prod 产出 0 条）。
- ❌ **「合并了」当成「上线了」**。判据是运行中的 jar / 服务器文件哈希，不是 PR 状态。
- ❌ **闸绿是因为它没跑**。`-DfailIfNoTests=false`、`|| true`、`contains` 子串断言，三样都会把「没测到」伪装成「测过了」。
- ❌ **看到反常读数直接判缺陷**。先问「写它的人怎么说」——8-06 三次误判里两次是探针自己造的假象。
- ❌ **只验证正向**。判「放行了」必须有阴性对照（同 token 打别的模块 → 403、跨租户 → 403、无 token → 401，三个都拒才证明那个 200 是真放行）。
- ❌ **接手把交接结论当既成事实**。本轮就核出 memory 里「②③④未部署」已过期、交接里「菜单工程没页面」需要细看才成立。

---

## 9. 交付物

1. **六块 × 可达性表**——每块标出具体页面/API 路径 + 实测响应 + **日志里的真实调用记录**。
2. **代码**：②④⑤⑥ 的非对话出口；①的毛利口径规则。
3. **G3 阴性对照结果**——LLM 断开后数字仍正确的证据。
4. **prod 实测记录**：`mock_ops` 登录，零提问落地页文本 + 若干提问的完整回答。
5. **一份交接**，写清还欠什么 + 本轮踩出来的新判据。

---

## 10. 起手动作（照做，别跳）

```bash
git fetch origin main
git log --oneline -20 origin/main
git ls-tree -r --name-only origin/main backend/java/cretas-api/src/main/resources/db/flyway/ \
  | sed 's#.*/##' | grep -oE '^V[0-9_]+__' | sed 's/__$//' | sort | uniq -d   # 非空 = 后端启动即炸
git worktree add -b codex/claude-<task> ../cretas-<task> origin/main          # 永远 off origin/main
```

prod 取数模板：

```bash
ssh root@47.100.235.168
ss -lntp | grep -E ':10010|:10020'     # 蓝绿槽位会变，别假设
TOK=$(curl -s -X POST 'http://127.0.0.1:<活跃端口>/api/mobile/auth/unified-login' \
  -H 'Content-Type: application/json' \
  -d '{"username":"mock_ops","password":"123456","deviceInfo":{"deviceId":"probe","deviceModel":"probe","platform":"web","osVersion":"1"}}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')

curl -s "http://127.0.0.1:<活跃端口>/api/mobile/MOCK_REST/findings?domain=restaurant" -H "Authorization: Bearer $TOK"
curl -s 'http://127.0.0.1:8083/api/smartbi/restaurant-ops/gross-margin?days=30' -H "Authorization: Bearer $TOK"
```
