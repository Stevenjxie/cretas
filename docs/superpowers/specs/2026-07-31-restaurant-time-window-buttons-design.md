# 餐饮 AI 时间范围切换按钮 — 设计

**日期**: 2026-07-31
**前置**: PR #2076（损耗按请求的时间窗取数）已上线 prod 并验证
**状态**: 设计已确认，待实现

---

## 1. 背景与动机

答案末尾的追问按钮已有三类框架（`_suggested_followups`）：话题追问、换门店范围、澄清选项。
缺的是**换时间范围** —— 用户想「同一个问题看上个月」只能自己重新打一遍问句。

这件事**必须排在 #2076 之后**。在 #2076 之前做，按钮会放大缺陷：用户点「看上个月」，
拿到一个标着「近 30 天」的七月数据。#2076 修完之后，「resolver 会诚实反映请求的窗口」
这个前提才成立，按钮才有意义。

### 关键背景：闸不能用维度表

交接明确排除了 `_RESOLVER_DIMENSIONS['time']`，实测也证伪了它 —— **两个方向都判错**：

| resolver | 维度表说 `time` | 签名有 `date_range` | 真相 |
|---|---|---|---|
| `WASTAGE_TOP` | ✗ 没有 | ✓ 有 | 真能换窗口 — 维度表**误拒** |
| `STAFFING_ADVICE` | ✓ 有 | ✗ 没有 | 换不了 — 维度表**误放**（更危险） |

那张表里的 `time` 意思是「能不能**按**时间拆」（把结果按时间分组），
不是「能不能**换**时间窗」（换一个查询区间）。两件事无关。

---

## 2. 设计

### 2.1 能力闸 — 复用 dispatcher 的同一个机制

在 `restaurant_ops_router.py` 新增公开函数，不让 `restaurant_intent_service` 去掏私有的
`_RESOLVERS`：

```python
def resolver_supports_explicit_window(code: Optional[str]) -> bool:
    """这个 intent 的 resolver 会不会真正按请求的时间窗取数。

    判据就是 `resolve_by_code` 过滤 kwargs 用的那一条: 它只把 resolver 签名里
    声明过的参数传下去, 没声明的 **静默丢弃**(不报错, 只是悄悄退回滚动窗口)。
    所以「签名里有没有 date_range」既决定窗口能不能传到, 也就决定了按钮承诺的
    东西成不成立 —— 两者不可能漂。
    """
    resolver = _RESOLVERS.get(code or "")
    return bool(resolver) and "date_range" in inspect.signature(resolver).parameters
```

**当前命中 6 个**：`CHANNEL_MIX`、`GROSS_MARGIN`、`SALES_SUMMARY`、`STORE_MARGIN`、
`TREND_ANALYSIS`、`WASTAGE_TOP`。

**为什么不用手维护白名单**：白名单是第二张表，新增/修改 resolver 时要有人记得同步；
漂了的表现就是「按钮点了报错」或「该给的没给」。签名判据自动跟着代码走。

**已知局限（刻意接受）**：声明了 `date_range` 不等于用对了。这是**必要条件**而非充分条件。
充分性由 resolver 自己的测试保证（如 #2076 给 `WASTAGE_TOP` 补的那组）。
用一个会漂的第二张表去追求充分性，代价高于收益。

### 2.2 候选窗口

复用 `_clarification_followups` 里已有的那一组，单一来源：

```python
("本月", "上个月", "最近7天", "最近30天")
```

剔除当前 `window_label`，取前 2 个（与 `_topic_followups` 的 `[:2]` 一致；
`_suggested_followups` 总盖仍是 4）。

### 2.3 拼问句 — 换掉时间前缀，其余原样

```
全部门店 | 上个月 | 损耗金额最高的食材      ← question_seed
全部门店 | 本月   | 损耗金额最高的食材      ← 按钮发出的问句
   ↑保留    ↑换掉    ↑不动
```

**门店前缀必须保留**。把 `_strip_store_scope` 重构成返回两段：

```python
def _split_store_scope(seed, store_names=()) -> Tuple[str, str]:
    """→ (开头的门店范围前缀, 余下部分)。前缀可能是空串。"""

def _strip_store_scope(seed, store_names=()) -> str:
    """薄封装, 保持 #2070 的行为与测试不变。"""
    return _split_store_scope(seed, store_names)[1]
```

**时间前缀剥离**（`_split_time_scope`，同样只动开头）：

- 候选窗口词本身：`本月`/`上个月`/`最近7天`/`最近30天`
- 常见变体：`这个月`/`本周`/`上周`/`今天`/`昨天`/`前天`/`今年`/`去年`/`最近三十天`/`最近七天`
- 绝对月份正则：`^\d{4}年\d{1,2}月份?`

只剥**开头**，句中绝不动 —— 与 `_strip_store_scope` 同一条纪律（「哪个月」这种词出现在
句中是问题的一部分）。剥不出任何东西也没关系：直接在前面加新窗口词即可。

**拼不出问句就不给按钮** —— `question_seed` 为空时返回 `[]`。宁可不给，也不给一个点了会出错的。

### 2.4 旧的时间按钮一并合流

`_topic_followups` 里 `topic_kind in ("dish_ranking", "store_ranking")` 的分支现在发的是
**写死的泛问句**：

```python
noun = "哪个菜卖得最好" if topic_kind == "dish_ranking" else "哪家店业绩最好"
{"label": f"看{window}", "question": f"{window}{noun}？"}
```

问「六月毛利最低的三道菜」，点「看本月」→ 问「本月哪个菜卖得最好？」。
**换了窗口也换了问题**，而标签「看本月」读起来是「同一个问题、换个月」。

删掉该分支，让时间按钮只有一条路径。覆盖面不减：这两类话题由
`GROSS_MARGIN` / `STORE_MARGIN` / `SALES_SUMMARY` 承接，三个都声明了 `date_range`。

### 2.5 顺序与合流

`_suggested_followups` 的拼装顺序改为：**topic → time → store**。
换时间比换门店常用，换门店最少。已有的「按 `question` 去重 + 封顶 4」照旧。

---

## 3. 数据流

```
tiered_answer
  └→ _structured_context(spec, result_meta)     # 已有 intent / window_label / question_seed
       └→ _suggested_followups(context)
            ├→ _topic_followups(context)                    # 删掉时间分支
            ├→ _time_window_switch_followups(context)       # 新增
            │    ├ 闸: resolver_supports_explicit_window(intent)
            │    ├ 拆: _split_store_scope → _split_time_scope
            │    └ 拼: store_prefix + new_window + rest
            └→ _store_scope_switch_followups(context)        # 不动
```

`context` 已经带齐所需字段（`intent`、`window_label`、`question_seed`、`store_options`），
**无需新增 context 字段**。

---

## 4. 错误处理

| 情况 | 行为 |
|---|---|
| resolver 不支持显式窗口 | 不给按钮（闸拦下） |
| `question_seed` 为空 | 不给按钮 |
| 剥不出时间前缀 | 正常给 —— 直接在开头加窗口词 |
| `window_label` 为空 | 正常给，取候选前 2 个 |
| intent 不认识 | `_RESOLVERS.get()` 返回 None → 不给按钮 |

一律 **fail-closed**：拿不准就不给按钮。一个不出现的按钮是小遗憾，
一个点了报错的按钮是今天已经修过三次的东西。

---

## 5. 测试

### 5.1 单元测试

1. **闸的方向性**（最重要）：
   - `WASTAGE_TOP` **给**按钮 —— 维度表会误拒它
   - `STAFFING_ADVICE` **不给** —— 维度表会误放它
   - 显式断言两张表在这两个 intent 上**判断相反**，让测试证明这个选择有意义
     （否则换回维度表测试照样绿）
2. **拼句**：`全部门店上个月损耗金额最高的食材` + 点「本月」
   → `全部门店本月损耗金额最高的食材`（店前缀保留、时间不重复、问题不变）
3. **绝对月份**：`2026年6月各门店营收` → 剥掉 `2026年6月`
4. **无时间前缀**：`损耗金额最高的食材` → `本月损耗金额最高的食材`
5. **去重与封顶**：与 topic / store 合流后 ≤ 4，无重复 `question`
6. **`_strip_store_scope` 行为不变**：#2070 的既有测试必须继续绿

### 5.2 端到端（不可省）

⛔ **上线前必须真点一次**。今天三次修同一个按钮（#2049 / #2069 / #2070），
共同原因都是没真点；#2049 的单测断言 `question == "B店"` 全绿 —— 而那正是坏契约本身。

- 在 prod 上问「全部门店上个月损耗金额最高的食材」，取回按钮
- 把按钮返回的 `question` **原样**再走一遍同一条链路
- 「看本月」应答出 `¥215,561.26`；反向问「看上个月」应答出 `¥10,071.77`
- **反向验一次**：挑一个 `STAFFING_ADVICE` 类问题，确认它确实**不给**时间按钮

---

## 6. 明确不做

- 不放宽 `_require_current_anchored_window`（agent-tool 侧的窗口限制），那是另一条链路
- 不恢复 `reporting/template.py` 的损耗章节 —— 复原条件虽已满足，但要连排版和
  Answer Contract 一起验，不搭在这次改动里
- 不给不支持显式窗口的 resolver「补上 `date_range`」—— 那是逐个 resolver 的独立工作
