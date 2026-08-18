"""「你问的这项算不出来」——按 §9.9 拒答模板作答，清单**算出来**不写死。

## 被替换掉的东西（2026-08-12 prod 实测）

问「这月挣了多少」/「本月全部门店翻台率怎么样」，店长收到的都是：

    当前可以可靠分析：订单集中程度、排班人效、菜品销量、已覆盖销售的毛利。
    当前不能可靠分析：净利润（缺少费用、税费及其他收支）。…

前半句是 `_unsupported_requirement_question` 里的**硬编码兜底**：

```python
available = [label for metric, label in available_labels.items()
             if metric in requested_metrics]
if not available:
    available = ["订单集中程度", "排班人效", "菜品销量", "已覆盖销售的毛利"]
```

🔴 **那段列表推导是死代码，兜底恒定触发。** 证明（三处调用点全查过）：
三处都带同一个守卫 `unsupported_requirements and not supported_requested_metrics`
—— 传进去的每一项都必然是「不支持」的；而 `available_labels` 的 7 个键与
`_UNSUPPORTED_REQUIREMENTS` 的 7 项**交集为空**。所以 `available` 恒为 `[]`。

**于是那句「现在能算的：X」恰恰在 X 与本问题完全无关时才出现。**
不是「它没给数」，是**它声称能给，而那个声称与问句无关** —— 一句会被当真的话。

## 这里怎么做

`registry ∩ schema ∩ 本租户真有数据` —— 三层都查，一层都不假设：

| 层 | 查什么 | 复用 |
|---|---|---|
| registry | 指标登记了吗、它 `requires` 哪些 `表.列` | `metric_registry.METRICS/DERIVED` |
| schema | 那些列在库里真的存在吗 | `generic_executor.existing_columns`（查 information_schema） |
| 租户 | 这个租户在**每一个所需列**上有非空值吗 | 与 `data_gaps._row_count` 同法 |

⚠️ 租户层数的是**列的非空值**不是表的行数。表有行 ≠ 那一列有值 ——
   prod 实测 MOCK_REST 的 `tax_amount`/`actual_receive` 填充率 0,
   按行数算会把「税额」「实收」写进「我这儿有的是」, 而那是一句假承诺。

⛔ **不再维护第二张「我们支持什么」的手写表** —— 那正是被替换掉的东西。
   手写表错了没有任何东西会红；这里错了，闸会红（见
   `test_capability_list_is_computed_not_constant`：抽掉租户覆盖来源，清单必须变）。

⚠️ **查不动时返回空清单，不猜。** 「我这儿有的是…」说错比不说更糟：
   它是一句承诺。空清单时模板会省掉第 ③ 段，只说算不了和缺什么。
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Sequence, Tuple

logger = logging.getLogger(__name__)


def _source_tables(requires: Sequence[str]) -> Tuple[str, ...]:
    """`表.列` → 去重后的表名。"""
    seen: Dict[str, None] = {}
    for item in requires:
        seen.setdefault(str(item).split(".", 1)[0], None)
    return tuple(seen)


def computable_labels(
    schema_columns: set,
    tenant_value_counts: Dict[str, int],
    *,
    metrics: Optional[Dict[str, Any]] = None,
    unsupported: Sequence[str] = (),
) -> Tuple[str, ...]:
    """这个租户**现在真的算得出来**的指标标签。纯函数 —— 三层输入全部由调用方查好。

    抽成纯函数是为了让闸能红：`schema_columns` / `tenant_value_counts` 都能在测试里
    抽掉，从而验证「清单是算出来的」而不是一个常量
    （断言「输出里有这几项」是恒真式，测不出这件事）。

    ⚠️ 三层判定**只有一份**，在 `computable_metric_keys` 里；这里只负责翻标签
       （形态 D：同一个判定两份一定会漂，而漂的方向恰好是「一边说能算、
       另一边说不能」）。这里传 `derived={}` —— 「我这儿有的是」说的是基础
       能力边界，派生量由基础量推出来，不重复念一遍。
    """
    if metrics is None:
        from smartbi.gold.restaurant.metric_registry import METRICS
        metrics = METRICS

    # 🔴 2026-08-12 prod 实测钉住的租户层判据: 数的是**每一列的非空值**,
    #    ⛔ 不是「源表有没有行」。第一版按行数算, 于是「税额」「实收」
    #    「平台抽佣」全被写进「我这儿有的是」—— 而 MOCK_REST 这几列填充率 0。
    #    表有行 ≠ 那一列有值, 而这一段恰恰是最不能说错的一段。
    ok = computable_metric_keys(
        schema_columns, tenant_value_counts,
        metrics=metrics, derived={}, unsupported=unsupported)

    out: List[str] = []
    for key, metric in metrics.items():
        if key not in ok:
            continue
        label = str(getattr(metric, "label", "") or key)
        if label not in out:
            out.append(label)
    return tuple(out)


def computable_categories(
    schema_columns: set,
    tenant_value_counts: Dict[str, int],
    *,
    metrics: Optional[Dict[str, Any]] = None,
    unsupported: Sequence[str] = (),
) -> Tuple[Tuple[str, str], ...]:
    """`(大类, 锚点标签)` —— 拒答那句「我这儿有的是…」按类说，每类给一个例子。

    🔴 owner 2026-08-12 裁定：11 项一口气念出来太长，收敛成大类；
       而**类别必须是 registry 上的字段**，不能是手写映射表 ——
       手写映射一旦落地，新登记的指标会悄悄落在所有类别之外、
       从「我这儿有的」里**消失**，而消失是不报错的。

    ⛔ **类的顺序按 `CATEGORIES` 走，不按数据量排** —— 数据量不预测
       「对店长有没有用」。填充量**只用来在类内挑锚点**（同一类里挑本租户
       数据最全的那个当例子），不作主轴。
    ⛔ 也不取前 N：截断是静默丢信息，而登记顺序是实现细节不是产品语义。
    """
    from smartbi.gold.restaurant.metric_registry import CATEGORIES
    if metrics is None:
        from smartbi.gold.restaurant.metric_registry import METRICS
        metrics = METRICS
    banned = set(unsupported)

    # 每类收 (填充量, 标签)。填充量取该指标所有必需列里**最小**的那个 ——
    # 最紧的那一列决定它算不算得出来。
    per_cat: Dict[str, List[Tuple[int, int, str]]] = {}
    for order, (key, metric) in enumerate((metrics or {}).items()):
        if key in banned:
            continue
        requires = tuple(getattr(metric, "requires", ()) or ())
        category = str(getattr(metric, "category", "") or "")
        if not requires or not category:
            continue
        if not all(col in schema_columns for col in requires):
            continue
        counts = [tenant_value_counts.get(col, 0) for col in requires]
        if min(counts) <= 0:
            continue
        per_cat.setdefault(category, []).append(
            (min(counts), order, str(getattr(metric, "label", "") or key)))

    out: List[Tuple[str, str]] = []
    for category in CATEGORIES:
        entries = per_cat.get(category)
        if not entries:
            continue
        # 类内按填充量降序; **打平时按登记顺序**。
        #
        # 🔴 2026-08-12 真租户实测改的: 同一张表的列填充量几乎总是打平,
        #    第一版拿标签当 tiebreak, 于是按码点排 —— 「营收和折扣」这一类的锚点
        #    选成了「平台抽佣」(平 U+5E73 < 营 U+8425), 对店长几乎没用。
        #    ⛔ 打平时的顺序不能交给码点。登记顺序是**人写的**(与 `_SLOT_POOLS`
        #    同一条纪律: 人写的顺序 = 人审过的优先级), 且可推导、不是手写映射表。
        entries.sort(key=lambda item: (-item[0], item[1]))
        out.append((category, entries[0][2]))
    return tuple(out)


def computable_metric_keys(
    schema_columns: set,
    tenant_value_counts: Dict[str, int],
    *,
    metrics: Optional[Dict[str, Any]] = None,
    derived: Optional[Dict[str, Any]] = None,
    unsupported: Sequence[str] = (),
) -> frozenset:
    """这个租户现在真的算得出来的**指标 key**（含派生量）。纯函数。

    ⚠️ 与 `computable_labels` 是同一件事的两个出口：那个给人看的标签，
       这个给机器用的 key。⛔ 不是复制粘贴一份三层判定 —— 三层判定只在
       本函数里，`computable_labels` 调它再翻标签
       （形态 D：同一个东西两份一定会漂，而漂的方向恰好是「一边说能算、
       另一边说不能」）。

    派生量（毛利 = 营收 − 食材成本）**左右两侧都算得出来才算得出来**，
    跑到不动点 —— 毛利率依赖毛利，毛利再依赖两个基础指标，深度不止一层。
    """
    if metrics is None:
        from smartbi.gold.restaurant.metric_registry import METRICS
        metrics = METRICS
    if derived is None:
        from smartbi.gold.restaurant.metric_registry import DERIVED
        derived = DERIVED
    banned = set(unsupported)

    ok = set()
    for key, metric in (metrics or {}).items():
        if key in banned:
            continue
        requires = tuple(getattr(metric, "requires", ()) or ())
        if not requires:
            continue
        # schema 层：登记的列必须真的在库里
        if not all(col in schema_columns for col in requires):
            continue
        # 租户层：每一个所需列上都得有非空值（⛔ 不是「表有几行」）
        if not all(tenant_value_counts.get(col, 0) > 0 for col in requires):
            continue
        ok.add(key)

    changed = True
    while changed:
        changed = False
        for key, item in (derived or {}).items():
            if key in ok or key in banned:
                continue
            left, right = getattr(item, "left", None), getattr(item, "right", None)
            if left in ok and right in ok:
                ok.add(key)
                changed = True
    return frozenset(ok)


def nearest_alternatives(
    unsupported: Sequence[str],
    computable: Sequence[str],
    *,
    metrics: Optional[Dict[str, Any]] = None,
    derived: Optional[Dict[str, Any]] = None,
    neighbours: Optional[Dict[str, Sequence[str]]] = None,
) -> Tuple[Tuple[str, str], ...]:
    """「眼下最接近的是 X」——⛔ 只返回**本租户实测算得出来**的那些。

    返回 `((标签, 限定语短形), …)`，按 `unsupported` 的顺序，去重。

    ## 承重的那一句

    ▎排在最前面的那个替代，老板照着去问，**如果答不上来就是一条误发的提示**。

    所以这里有两层，缺一层都不成立：

    | 层 | 回答什么 | 来源 |
    |---|---|---|
    | 构成关系 | 这个算不出来的量由哪几个已登记指标构成 | `_UNSUPPORTED_REQUIREMENT_NEIGHBOURS`（定义式，⛔ 不是联想） |
    | 能不能算 | 这个租户今天真的算得出来吗 | `computable`（查库：registry ∩ schema ∩ 列非空值） |

    ⛔ **一个近邻都没有 → 返回空**，不退而求其次去找「同一大类里数据最全的那个」：
       同类不等于接近（销量与退菜率同属「客流和销量」，而它跟退菜一个字都不沾）。
       宁可这一类先不提示。

    ⚠️ 限定语（`caveat_short`）**必须跟着标签一起出去**。不带限定语地拿毛利
       顶净利润，正是系统承诺不做的「相邻指标顶替」—— 只是换到了建议这一侧。
    """
    if metrics is None:
        from smartbi.gold.restaurant.metric_registry import METRICS
        metrics = METRICS
    if derived is None:
        from smartbi.gold.restaurant.metric_registry import DERIVED
        derived = DERIVED
    if neighbours is None:
        from smartbi.gold.restaurant.restaurant_intent import (
            _UNSUPPORTED_REQUIREMENT_NEIGHBOURS,
        )
        neighbours = _UNSUPPORTED_REQUIREMENT_NEIGHBOURS

    available = set(computable or ())
    out: List[Tuple[str, str]] = []
    seen = set()
    for code in (unsupported or ()):
        for key in (neighbours.get(code) or ()):
            if key not in available:
                continue
            item = (metrics or {}).get(key) or (derived or {}).get(key)
            if item is None:
                continue
            label = str(getattr(item, "label", "") or key)
            if label in seen:
                break
            seen.add(label)
            out.append((label, str(getattr(item, "caveat_short", "") or "")))
            # 一个算不出来的量只给**一个**替代 —— 声明顺序就是优先级
            # （人写的顺序 = 人审过的优先级，与 `computable_categories` 同纪律）。
            break
    return tuple(out)


class TenantCapability(tuple):
    """`(大类, 锚点)` 序列 **+ 挂在它身上的 `alternatives`**。

    🔴 为什么是 tuple 子类而不是加一个参数：调用方那两行
    （`restaurant_intent_service` 里 `tenant_capability(...)` → `render_capability_refusal(...)`）
    是另一条线正在改的文件，本轮不碰它。让**查库那一步**把替代一起算好、
    挂在它已经在传的那个值上，是唯一能「不改调用点也真的接上」的做法。

    ⚠️ 形态 B（机制在、没接上）在这里的具体长相：给
       `render_capability_refusal` 加一个 `alternatives=` 参数、而没有任何
       生产调用点传它 —— 那样单测会全绿，线上一个字都不会变。
       钉住它的是 `test_nearest_alternative.py::test_两行调用点原样接得上`：
       它按调用点的**原样两行**跑一遍。
    """

    # ⛔ 不能写 `__slots__` —— tuple 子类不支持非空 slots（当场 TypeError）。

    def __new__(cls, groups=(), alternatives=()):
        obj = super().__new__(cls, tuple(groups))
        obj.alternatives = tuple(alternatives)
        return obj


def render_capability_refusal(
    missing_labels: Sequence[str],
    available_groups: Sequence[Any],
    alternatives: Optional[Sequence[Any]] = None,
) -> str:
    """§9.9 拒答模板的 ①②③ 段。**④ 不在正文里** —— 它是按钮，由调用方放进 followups。

    ⛔ 不附加「顺带 N 件事」块（§9.9）：拒答带上一堆发现，读起来像是回答了，
       人会以为拿到了东西。那两条发现是真算出来的，所以**降级成按钮**而不是删掉。

    ⛔ 第 ③ 段的措辞是「我这儿有的是」，不是「现在能算的」——
       后者读起来像「针对你这个问题我能算这些」，而它其实是能力边界。
       被替换掉的那句正是栽在这个口径上。

    ⚠️ `available_labels` 为空时**整段省掉**，不写「暂时什么都算不了」之类的话 ——
       空清单的成因是「查不动」，那时说任何一侧都是猜。

    ## 「眼下最接近的是 X」（2026-08-18 加，缺口清单第 21 项）

    `alternatives` 不传时**从 `available_groups` 身上取**（见 `TenantCapability`）——
    调用点那两行本轮不碰，而「加了参数没人传」就是形态 B 本身。

    ⛔ 有替代时**不再打那句通用的「想看哪一样…」** —— 两句「你可以说个名字」
       并排出现，老板要先读懂它们是不是同一件事。动作只留一个，且是更具体的
       那一个。
    """
    if alternatives is None:
        alternatives = getattr(available_groups, "alternatives", ())
    missing = "、".join(missing_labels) or "你问的这项"
    lines = [
        f"**{missing}现在算不出来。**",
        "",
        f"缺的是：{missing}",
    ]
    if alternatives:
        # 限定语跟着标签走: 「毛利（未扣人工、房租、水电）」。
        # ⛔ 不许只在开头说一次 —— 那是「先甩数再解释」的同一个坑换个位置。
        named = "、".join(
            f"{label}（{caveat}）" if caveat else label
            for label, caveat in alternatives)
        lines += [
            "",
            f"眼下最接近的是{named}。想看的话，说「{alternatives[0][0]}」就行。",
        ]
    if available_groups:
        rendered = "、".join(
            f"{category}（比如{anchor}）" for category, anchor in available_groups)
        lines += ["", f"我这儿有的是：{rendered}。"]
        # 🔴 2026-08-18 加：**他要干什么**（交付定义⑤ 的第三件事）。
        #
        # 📏 逐条读 prod 拒答实测（MOCK_REST，24 问句，1 轮）：
        #    「翻台率怎么样」→ 129 字。缺什么说了（「缺的是：翻台率（缺少桌台、
        #    开台/结账时间、就餐轮次和可用桌数）」），我们有什么也说了，
        #    **但没有第三件** —— 老板知道我们有什么，却不知道下一步该打什么字。
        #
        # ⛔ **不给可照抄的引号问句** —— 本轮刚撤掉那一类
        #    （📏 实测 4/4 兑现不了，见 `_dimension_gap_advice` 与 PR #2818）。
        #    这里只描述**动作**，而且动作指向的是**已经验证过有数据**的那几类：
        #    `available_groups` 是查库算出来的（每一列的非空值），不是手写表。
        # ⚠️ 与「有默认值就压掉澄清」无关：这不是替他选，是告诉他怎么选。
        # ⛔ 已经给了具体替代时不再打这一句（见 docstring）—— 动作只留一个。
        if not alternatives:
            lines += [
                "",
                "想看哪一样，直接说它的名字就行"
                f"（例如「{available_groups[0][1]}」）。",
            ]
    return "\n".join(lines)


def missing_capability_labels(unsupported: Sequence[str]) -> List[str]:
    """把「算不出来」的能力码翻成店长看得懂的标签。翻不出来的**丢掉**，不透传裸码。

    ⛔ 抽成纯函数是为了让「什么时候该用拒答模板」这个判断可测：
       内联在那个要发 HTTP、要连库的 async 分支里时，它没有任何办法被单测覆盖，
       而「本该只在能力缺口时接管，结果把缺时间/缺门店的澄清也顶掉了」正是
       这种改动最容易犯的错 —— 那会把「再说清楚点」说成「我做不到」。
    """
    from smartbi.gold.restaurant.restaurant_intent import (
        _UNSUPPORTED_REQUIREMENT_LABELS,
    )
    return [
        _UNSUPPORTED_REQUIREMENT_LABELS[item]
        for item in (unsupported or ())
        if item in _UNSUPPORTED_REQUIREMENT_LABELS
    ]


def should_use_capability_refusal(unsupported: Sequence[str]) -> bool:
    """这次澄清该不该换成 §9.9 拒答模板。

    ⚠️ **只在确实有「算不出来的能力」时**接管。缺时间、缺门店、说不清要看什么 ——
       那些都不是能力缺口，用拒答模板会把「再说清楚点」说成「我做不到」，
       而后者是一句关门的话。
    """
    return bool(missing_capability_labels(unsupported))


#: 毛利口径的限定语。⛔ 它必须**贴着数字**出现，不能只写在开头。
#:
#: 🔴 owner 2026-08-12：「先甩 ¥ 数再解释，用户读到的就是『赚了这么多』——
#:    那是『相邻指标顶替』换个位置重演。」
#:    所以顺序是硬性的：先说给不了，再给数；而限定语跟数字**同一行**出现
#:    （放在小标题里），读到数字时不可能没读到它。
MARGIN_NOT_PROFIT = "毛利口径，不含人工、房租、水电这些费用，不能当利润看"


def partial_coverage_answer(
    missing_label: str,
    missing_reason: str,
    facts: Sequence[Any],
) -> Optional[str]:
    """§9.2 第二档：**给能算的 + 明说另一个为什么算不出**。

    `facts` 是 resolver 已经算好的 KPI（`{label, value, unit}`）——
    ⛔ **数字只从这里来，绝不复用叙述文本**。被契约驳回的那份 `answer_text`
       有一部分是 LLM 叙述出来的；原样留用等于把 LLM 产的数字重新放行，
       而且是在一个专门声明「我不拿别的数据凑」的答案里，比今天更糟。

    ## 顺序是硬性的（owner 2026-08-12）

    ```
    ① 你问的那个给不了 + 为什么      ← 必须是第一句
    ② 能算的是（<限定语>）：…数字…    ← 限定语与数字同一处
    ```

    ⛔ 反过来写（先甩数再解释）用户读到的就是「赚了这么多」——
       **那是「相邻指标顶替」换个位置重演**，而系统对用户有不顶替的承诺。

    ⚠️ 没有可给的数 → 返回 `None`，让调用方走整份拒答。
       「明说算不出来」本身是合规的（契约 §4），但**空着的「能算的是：」不是** ——
       那是一句看起来给了东西、实际什么都没有的话。
    """
    lines = [f"**{missing_label}算不出来**：{missing_reason}。"]
    rendered = [
        f"- {f['label']}：{f['value']}{f.get('unit') or ''}"
        for f in (facts or [])
        if isinstance(f, dict) and f.get("label") is not None
        and f.get("value") not in (None, "", "—", "***", "暂无")
    ]
    if not rendered:
        return None
    lines += ["", f"能算的是（{MARGIN_NOT_PROFIT}）：", *rendered]
    return "\n".join(lines)


async def tenant_capability(pool, factory_id: str, unsupported: Sequence[str]):
    """查三层，返回 `(大类, 锚点标签)` 序列 **+ 挂在它身上的 `alternatives`**。
    查不动 → 空元组（不猜，且 `getattr(..., "alternatives", ())` 也拿到空）。

    ⚠️ 「眼下最接近的是 X」与「我这儿有的是」**同一次查库、同一份 counts** ——
       ⛔ 不为替代再打一次库，也就不可能出现「一段说能算、另一段说不能」。
    """
    try:
        from smartbi.gold.queries import tenant_conn
        from smartbi.gold.restaurant.generic_executor import existing_columns
        from smartbi.gold.restaurant.metric_registry import METRICS

        needed: Dict[str, List[str]] = {}
        for key, metric in METRICS.items():
            if key in set(unsupported):
                continue
            for col in (getattr(metric, "requires", ()) or ()):
                needed.setdefault(str(col).split(".", 1)[0], []).append(str(col))

        async with tenant_conn(pool, factory_id) as conn:
            schema_columns = await existing_columns(conn)
            counts: Dict[str, int] = {}
            # ⛔ 数的是**每一列的非空值个数**, 不是表的行数 —— 表有行 ≠ 那一列有值。
            #    (prod 实测: MOCK_REST 的 tax_amount / actual_receive 填充率 0,
            #     按行数算会把「税额」「实收」写进「我这儿有的是」, 那是假承诺。)
            #    一次查一张表, 所有需要的列放在同一个 SELECT 里, 不逐列打一次库。
            for table, cols in needed.items():
                live = [c for c in cols if c in schema_columns]
                if not live:
                    for c in cols:
                        counts[c] = 0
                    continue
                # table/列名均来自 registry 的固定字面量, 不接受外部输入。
                exprs = ", ".join(
                    f'count({c.split(".", 1)[1]})::int AS "{c}"' for c in live)
                row = await conn.fetchrow(
                    f"SELECT {exprs} FROM {table} WHERE factory_id = $1", factory_id)
                for c in cols:
                    counts[c] = int((row or {}).get(c) or 0) if c in live else 0
    except Exception as exc:  # noqa: BLE001
        # ⛔ 查不动就返回空 —— 「我这儿有的是…」是承诺, 猜错比不说更糟。
        logger.warning("[capability] 无法确认本租户能算什么, 省掉那一段: %s", exc)
        return ()

    return TenantCapability(
        computable_categories(schema_columns, counts, unsupported=unsupported),
        nearest_alternatives(
            unsupported,
            computable_metric_keys(schema_columns, counts, unsupported=unsupported)),
    )
