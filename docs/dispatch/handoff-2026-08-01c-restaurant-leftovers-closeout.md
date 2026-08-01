# 交接：餐饮遗留四条卡 —— 收尾（2026-08-01 第三轮）

**起点**：`handoff-2026-08-01b-restaurant-leftovers.md`
**PR**：#2133 已合 main = `b838186126`
**进度**：条目 1 ✅ / 条目 2 ✅ / 条目 3 ✅ / 条目 4 ✅（证伪）
**部署**：Python 已从 main 部 prod，健康 200，已核过运行中的模块

---

## ⚠️ 接手先读这条

上一份交接的三条自检我全跑过，**结论都成立**，只有一处要更正：

> §四 写「全部已合 main：… #2131(待查) …」——**#2131 当时并没有合，现在仍是 OPEN**。

其余（Gold 表必须带租户 GUC、要放阳性对照）本轮都实测复现过，照做。

本轮新增一条自检：**别用 `grep` 判断一个 Python 数据结构里有没有某个值**。我核对部署结果
时用字符串切片查 `SAMPLE_QUERIES`，报 `True`——实际是命中了我自己写的**注释文本**。
改成 `import` 模块直接看列表才拿到真值（`False`）。判据：核对数据结构就 import 它。

---

## 一、条目 3 ✅ 门禁排除清单 34 → 29

**「event loop 5 条」不是文件坏，是顺序依赖。**

这 5 个文件用 `asyncio.get_event_loop().run_until_complete(coro)`，而 pytest-asyncio 1.3.0
在每个 async 用例之后清掉本线程的 loop。于是这些**同步**用例只要**排在任何一个 async 用例
之后**就 `RuntimeError: There is no current event loop`。

所以它们**单独跑全绿（96 passed），进套件才炸**——排除清单原注「新版 pytest-asyncio 不再
提供隐式 event loop」方向是对的，但漏了「必须有 async 用例先跑」这个触发条件，因此
**照它单跑复现不出来**。

修法：11 处全部改成 `asyncio.run(coro)`（都是一次性协程 + 假 pool/AsyncMock，无跨调用
loop 绑定态）。

判据（同一条命令，改前红改后绿）：

```bash
pytest smartbi/services/restaurant/tests/ \
       smartbi/gold/tests/test_clean_display_name.py \
       smartbi/gold/tests/test_gold_reads_restaurant.py \
       smartbi/gold/tests/test_review_connected_contract.py
# 改前 41 failed / 406 passed   →   改后 447 passed
```

**CI py3.10 实证**（这点重要——上一轮有过「本机 py 差异」的判断被 CI 证伪的先例）：
run 30680450257 日志里 `排除 29 个文件` + `6187 passed`。

---

## 二、条目 2 ✅ 「毛利最低的菜品」定向 GROSS_MARGIN

分两步，两步都有实测支撑。

### 第一步：样例表定向（残留，非设计选择）

拿 `match_restaurant_ops` 把 `SAMPLE_QUERIES` **全表 62 条**跑了一遍：

| 结果 | 条数 | 性质 |
|---|---|---|
| 判给自己登记的 code | 56 | 正常 |
| 返回 `None` | 5 | **良性** —— 关键词层没意见，回落 T2 向量 / T3 LLM，属设计内 |
| **判给另一个 code** | **1** | 就是「毛利最低的菜品」（登记 RECIPE_COST / 实判 GROSS_MARGIN） |

判定为残留而非设计的依据：RECIPE_COST 的 group-1 要求出现
`食材成本|配方成本|菜品成本|食材费用`，这句**一个都不含**——它**从来匹配不到**
RECIPE_COST；而该模式注释自己写着「食材成本 only —— 毛利 moved to gross_margin」。

### 第二步：补毛利侧缺的那道守卫（真分歧，上一份交接没有）

审计原句「全部门店最近30天毛利最低的菜品有哪些」实测被判 **STORE_MARGIN**——因为
STORE_MARGIN 的 group-1 含「门店」且**排在 GROSS_MARGIN 之前**，一进模式循环就被抓走。
但「全部门店」是**聚合范围**，不是把问题变成门店榜。

⚠️ **`match_restaurant_ops` 早已不是它 docstring 说的 early-return 快路径**（docstring
是过期的），而是喂给 T3 planner 的 **0.95 candidate_hint**（源码注释：「a hint, not an
execution permit」）。所以它判错不会直接出错答案，而是**带偏 planner** —— 这正是审计
那条一直「报错轴或假绿」的机制。

根因形状是**不对称而非新原则**：`dish_ranking_direction` 的 docstring 早就写着
「Store words are scope, not a competing metric」，销量侧「全部门店销量最高的5道菜」
一直判 GROSS_MARGIN，**毛利侧只是漏了同一道守卫**。

🔴 **探针纠正了一个会弄坏东西的假设**：我本以为「点名具体门店 + 菜品」会被前面的
`store_dish_split_dish` 提前拦下，**实测它对「东城店毛利最低的菜品」返回 `None`**，
这类问句会一路走到模式循环。守卫必须自己排除「点名了具体店」，否则会把店×菜粒度
问句一并改判掉。**先跑探针拿基线，避免了这个回归。**

### prod 验收（条目 2 的验收标准）

`scripts/restaurant_department_audit.py --factory MOCK_REST` 连跑 **3 轮**：

```
[OK] 财务  全部门店最近30天毛利最低的菜品有哪些   intent=GROSS_MARGIN  advice=Y
合计 能答 15/18            ← 3 轮完全一致
```

部署后直接 import 线上模块核对，5 条全对：

| 问句 | 线上实判 |
|---|---|
| 毛利最低的菜品 | GROSS_MARGIN ✅ |
| 全部门店最近30天毛利最低的菜品有哪些 | GROSS_MARGIN ✅ |
| 哪家店毛利最好 | STORE_MARGIN ✅（未被顺手改判） |
| 东城店毛利最低的菜品 | STORE_MARGIN ✅（店×菜粒度保住） |
| 全部门店最近30天哪些菜的食材成本最高 | RECIPE_COST ✅ |

---

## 三、条目 4 ✅ **证伪** —— 排序键与 limit 的取法是对的

交接标 [未验] 的两条现象，都不是缺陷：

- 喂 `pos_rows` 的 SQL（`FROM fact_pos_item … GROUP BY p.product_id`）**没有 LIMIT**，
  取回窗口内全部菜品；`ORDER BY total_revenue DESC` 只是取数顺序，Python 侧随后按
  `total_qty` 重排，并**在排序之后**才 `[:limit]` 切片。
- 「最差前 5 里第 5 名(76,773)比第 1–4 名(63k)都高」——**升序本来就长这样**，最差前 5
  里第 5 名就是这 5 个中最大的那个。
- 「同一道菜同时出现在两榜」只在 `可排菜品数 < 2×limit` 时发生，而答案里已如实披露
  「仅统计窗口内有销售记录的 N 道菜品」。

已钉成两条用例，其中一条**实证过分辨力**（不是「写完跑绿就算」）：
`test_worst_ranking_takes_sort_key_and_limit_in_the_right_order` 的夹具让营收与销量
**反相关**——喂全量 → PASS；喂「先按营收 DESC 截断 5 行」→ FAIL，且最差榜整榜变成
销量 500+ 的高营收菜，**正好复现交接描述的那个现象**。

---

## 四、🔴 仍未修的三项（下一轮的真工作）

### 1. `gold/queries.py` 租户上下文靠调用方记得（上一份交接 §一，**仍未修**）

未动。`finance_summary` 等 31 个 async 函数里只有 3 处自己 `set_config('app.factory_id')`，
其余靠调用方碰巧在那条连接上设过 → 非确定性返空。修法方向不变：让助手自己保证租户
上下文，而不是靠调用约定。

### 2. MOCK_REST 菜品成本 0 行 —— **路由修好了，答案仍然是空的**

本轮 prod 实拍那一问的答案正文：

```
全部销售营收 ¥34,959,425.00；其中可计算毛利的营收 ¥0.00，营收覆盖率 0.0%
> 0/10 个销售菜品有完整成本数据。
毛利前 0 名菜品（按绝对毛利）: 暂无成本完整、可计算毛利的菜品。
```

**阳性对照**（同一问句换到有成本数据的 RES_3101_009）：真排出榜了 ——
`95/487 个销售菜品有完整成本数据`、加权毛利率 85.1%、低毛利菜品逐条列出。
→ **resolver 本身是好的，MOCK_REST 纯粹是数据缺口**，与上一份交接条目 1
「补数据的方向从『接 POS』变成『补成本』」完全吻合。

### 3. ⚠️ 审计的 `classify()` 把「诚实说没数据」计为 OK

上面那条 MOCK_REST 的答案实质是空的，审计仍判 `[OK]`——因为 `classify()` 只看
「intent 对不对 / 非空 / kind=='answer'」。这与上一份交接 §一末尾的警告是同一件事
（**50-57% 那个基线是高估的**），但现在有了具体实例。

想让审计反映真实能力，得再加一层「答案里有没有真的排出东西」的判据。**本轮刻意没动**
——改审计口径会让历史分数不可比，应当先决定再改。

---

## 五、🔴 判据类教训（本轮新增）

1. **「单独跑绿」不等于「没问题」** —— event loop 那 5 条单跑 96 passed，进套件 41 failed。
   顺序依赖的缺陷，复现命令必须**带上让它挂的那个前置**。
2. **核对数据结构就 import 它，别 grep** —— 我 grep 出的 `True` 命中的是自己写的注释。
3. **改路由前先跑探针拿全量基线** —— 「点名具体店会被前面拦下」这个假设是错的
   （`store_dish_split_dish` 返回 `None`），基线表当场证伪，避免了把店×菜问句一起改判。
4. **测试要证明它能分辨** —— 不只是「写完跑绿」，而是主动喂一个缺陷版本看它变红。
   条目 4 那条用例就是这么验的。
5. **`sed -i` 会把 CRLF 文件整档 normalize** —— `test_health_check_metrics.py` diff 显示
   1270 行而我只改了 1 行，靠「diff 远大于预期就停手」抓到，revert 后用精确 edit 重做。
   （同 [[feedback_dryrun_must_mirror_deploy_moment]] 里那条 `io.open(p,'w')` 的坑。）
6. **交接写的是「我知道的剩余项」，不是仓库真实状态** —— #2131 的状态就与交接不符。
   接手先自己跑一遍 `gh pr list --state open`。

---

## 六、验证汇总

| 项 | 结果 |
|---|---|
| `tests/test_restaurant_ops_router.py` | 372 passed |
| 本地门禁全量 | 6198 passed / 1 skipped，exit 0 |
| CI (PR #2133) | java-build-test ✓ / pytest ✓ / tracked-secret-scan ✓ |
| CI 排除清单 | `排除 29 个文件`（原 34） |
| prod 部署 | Python 8083 健康 200，运行模块已核 |
| prod 审计 | MOCK_REST 3 轮一致，15/18，目标那条 GROSS_MARGIN/[OK] |
