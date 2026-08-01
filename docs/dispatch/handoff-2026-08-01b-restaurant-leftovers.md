# 交接：餐饮遗留四条卡（2026-08-01 第二轮）

**起点**：`handoff-2026-08-01-restaurant-ai-robustness.md` + 一张四条目工作卡
**分支**：`codex/claude-restaurant-leftovers-0801`（PR #2133 已开，含条目 1；条目 3 第一条已提交待推）
**进度**：条目 1 ✅ / 条目 3 部分 ✅ / 条目 2、4 ⬜ 未开始

---

## ⚠️ 接手先读这条

先跑三条自检再信下面任何一句：

```bash
git fetch origin && git log origin/main --oneline -5
gh pr list --state open
cd backend/python && python -m pytest smartbi/services/restaurant/tests/test_batch_regression_golden.py -q
```

⛔ **查 Gold 表必须带租户上下文**，否则**恒返回 0 行且长得就像「没数据」**：
```bash
# 直查用 superuser 绕过 RLS
ssh root@47.100.235.168 'su - postgres -c "psql -d smartbi_prod_db -X -c \"...\""'
# 用代码查则 pool 必须 init: SELECT set_config('app.factory_id', $1, false)
```
**并且要放一个阳性对照**（查一个你确定有值的数），对照没命中说明在查错地方。

---

## 一、条目 1 ✅ —— 我上一份交接的数据结论是错的

❌ 原写「MOCK_REST 最近 30 天没有 POS 流水」。**依据是 AI 自己的回答**
（「最近30天没有可用的营收和订单数据」）—— 把**系统行为当成了数据事实**，从没直查过库。

✅ [实测 2026-08-01，superuser + 带 GUC 双向确认]：

| 租户 | POS 总行 | 最近30天 POS | agg_daily 近30天 | 后厨领料 | 后厨损耗 | 菜品成本 |
|---|---|---|---|---|---|---|
| MOCK_REST | 100,862 (06-29→07-31) | **94,862** | 300 行 / ¥3416 万 | 4,290 | 5,978 | **0** |
| RES_3101_009 | 591,026 | 63,260 | 883 行 / ¥1050 万 | 3 | 10 | 136 |

「没有租户能验完整能力」**结论成立但原因不同**：缺的是**菜品成本**
（`agg_restaurant_product_cost` MOCK_REST 0 行），不是 POS。
→ 补数据的方向从「接 POS」变成「补成本」。

交接已更正（PR #2133）。

### 🔴 条目 1 顺带挖出一个未修的真缺陷

数据齐全而 AI 答「没有可用的营收和订单数据」。已排除 RLS（带 GUC 的阳性对照返回
300 行，与 superuser 一致）。根因方向：

```
restaurant_ops_router.py     14 处显式 set_config('app.factory_id', ...)
smartbi/gold/queries.py      31 个 async 函数, 只有 3 处设
                             —— finance_summary(营收/单量唯一来源)不在其中
```

**它靠调用方碰巧在那条连接上设过。** 实测非确定性：同一 `date_range` 两次跑，
一次 `total_revenue=34,160,545.84 / bill_count=94,862`，一次全 0，
而同一个池上的直查始终正确。

与 #2076（签名没声明就静默丢弃 `date_range`）、2026-08-01 RBAC 泄露（签名没 `role`
就拿不到角色）**同一族**：契约靠调用方记得，忘了不报错、只**静默返回空**。

⛔ **未修**（要改 resolver 并上 prod，按卡片 🔒 约束本轮只记账）。
修法方向：让 `gold/queries.py` 的助手自己保证租户上下文，而不是靠调用约定。

⚠️ **连带影响**：对抗性审计把这类回答归为「诚实说没数据」计入无数据而非失败
→ **50-57% 那个基线是高估的**。

---

## 二、条目 3（部分 ✅）—— golden 快照是过期的，不是缺陷

`test_batch_regression_golden.py` 守「重构前后逐字节一致」。之后有人**有意**加了
外部客流画像能力、并把文案改成面向低技术素养用户的口语，快照停在重构那一刻。

55 处差异全部落在：文案口语化重写 / 新增能力（`analysisDimensions` 9 vs 8、
`crossPlatformComparison` 6 vs 5）/ 新增字段（`demoActionScenarios`、
`packageDecision`、`trafficPersona`、`decisionFocus`）。**财务计算一格未变。**

→ 已重生成快照，`ci-gate-excludes.txt` **35 → 34**（自建立以来第一条被清掉的）。
门禁验证 5682 passed。

### ⚠️ 我第一版 diff 是被自己污染的

手写夹具用了 `factory_id="F_DX"` / `sub_sector="hotpot"`，而 fixture 里是
`F-DINGXIAN-YIWU` / `火锅`。日志第一行报 `sub_sector benchmark 不存在: hotpot.yaml`
→ 财务指标全 None/0 → diff 里出现「revenue 0.0 vs 731048.0」这种**看起来像真缺陷**
的东西。用对 fixture 后整片消失。

**判据：拿测试自己的 fixture，不要手抄输入。**

### 条目 3 剩下的 5 条（⬜ 未做，同一根因）

```
smartbi/services/restaurant/tests/test_health_check_metrics.py    # event loop
smartbi/services/restaurant/tests/test_health_check_void_rate.py  # event loop
smartbi/gold/tests/test_clean_display_name.py                     # event loop
smartbi/gold/tests/test_gold_reads_restaurant.py                  # event loop
smartbi/gold/tests/test_review_connected_contract.py              # event loop
```
新版 pytest-asyncio 不再提供隐式 event loop，5 条一次修掉。
⛔ 不许 `|| true`，清一条删一条。

---

## 三、条目 2、4 ⬜ 未开始

**条目 2 —— 「毛利最低的菜品」路由分歧**
- `restaurant_ops_router.py:193` 把该问句挂在 `RESTAURANT_OPS_RECIPE_COST`
- `scripts/restaurant_department_audit.py:49` 期望 `GROSS_MARGIN`
- LLM planner 实际路由到 `GROSS_MARGIN`
#2098 只修了「不许答成销量榜」，刻意没动路由。不定方向，部门审计会一直有一条
在报错轴或假绿。验收：定一个方向并让 `restaurant_department_audit.py --factory MOCK_REST`
对这条给出稳定结论。

**条目 4 —— 「卖得最差」排行可能有排序缺陷 [未验]**
#2098 修复前实测：「最差前 5」里第 5 名（76,773）比第 1–4 名（约 63k）都高，
且同一道菜同时出现在「卖得最好」榜里。#2098 后毛利问句不再走这条分支，但
「哪道菜卖得最差」仍走。验排序键与 limit 的取法。

---

## 四、本轮之前那 12 个 PR 的状态

全部已合 main：#2072 #2110 #2112 #2116 #2117 #2118 #2119 #2120 #2121 #2126 #2131(待查) #2122。
prod 部署 3 次并各自复验。已上线的关键项：
- 🔴 **越权泄露关闭** —— 后厨拿不到 ¥709 万采购总额（老板侧一格未变）
- **口语问句三轮变两轮** —— #2126 已合并已部署，线上文件含合成追问
- 盘点按金额答 + 符号归一；前端闸收敛；餐饮四部门收敛到一处定义

---

## 五、🔴 判据类教训（本轮新增，对下一个 chat 最有用）

1. **别把系统行为当数据事实** —— 我拿 AI 的「没有数据」当成了「库里没数据」，
   直查发现有 9.4 万行。**要下数据结论就直查库，并放阳性对照。**
2. **拿测试自己的 fixture，别手抄输入** —— 手抄错一个 `sub_sector` 就让 diff 里
   凭空出现「像真缺陷」的财务差异。
3. **查 Gold 表不带租户 GUC 恒返回 0 行**，长得就像没数据 —— 这个坑我和卡片作者
   都各踩过一次。
4. **同一族的三个缺陷**：#2076 丢 `date_range`、RBAC 泄露丢 `role`、
   `finance_summary` 丢租户上下文 —— 共同形状是**契约靠调用方记得，忘了不报错、
   只静默返回空/错**。下次看到「签名/上下文靠约定传递」就该警觉。
