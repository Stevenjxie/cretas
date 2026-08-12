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
| 租户 | 这个租户在源表里有行吗 | 与 `data_gaps._row_count` 同法 |

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
    tenant_row_counts: Dict[str, int],
    *,
    metrics: Optional[Dict[str, Any]] = None,
    unsupported: Sequence[str] = (),
) -> Tuple[str, ...]:
    """这个租户**现在真的算得出来**的指标标签。纯函数 —— 三层输入全部由调用方查好。

    抽成纯函数是为了让闸能红：`schema_columns` / `tenant_row_counts` 都能在测试里
    抽掉，从而验证「清单是算出来的」而不是一个常量
    （断言「输出里有这几项」是恒真式，测不出这件事）。
    """
    if metrics is None:
        from smartbi.gold.restaurant.metric_registry import METRICS
        metrics = METRICS
    banned = set(unsupported)

    out: List[str] = []
    for key, metric in metrics.items():
        if key in banned:
            continue
        requires = tuple(getattr(metric, "requires", ()) or ())
        if not requires:
            continue
        # schema 层：登记的列必须真的在库里
        if not all(col in schema_columns for col in requires):
            continue
        # 租户层：源表里得有这个租户的行
        tables = _source_tables(requires)
        if not tables or not all(tenant_row_counts.get(t, 0) > 0 for t in tables):
            continue
        label = str(getattr(metric, "label", "") or key)
        if label not in out:
            out.append(label)
    return tuple(out)


def render_capability_refusal(
    missing_labels: Sequence[str],
    available_labels: Sequence[str],
) -> str:
    """§9.9 拒答模板的 ①②③ 段。**④ 不在正文里** —— 它是按钮，由调用方放进 followups。

    ⛔ 不附加「顺带 N 件事」块（§9.9）：拒答带上一堆发现，读起来像是回答了，
       人会以为拿到了东西。那两条发现是真算出来的，所以**降级成按钮**而不是删掉。

    ⛔ 第 ③ 段的措辞是「我这儿有的是」，不是「现在能算的」——
       后者读起来像「针对你这个问题我能算这些」，而它其实是能力边界。
       被替换掉的那句正是栽在这个口径上。

    ⚠️ `available_labels` 为空时**整段省掉**，不写「暂时什么都算不了」之类的话 ——
       空清单的成因是「查不动」，那时说任何一侧都是猜。
    """
    missing = "、".join(missing_labels) or "你问的这项"
    lines = [
        f"**{missing}现在算不出来。**",
        "",
        f"缺的是：{missing}",
    ]
    if available_labels:
        lines += ["", f"我这儿有的是：{'、'.join(available_labels)}。"]
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


async def tenant_capability(pool, factory_id: str, unsupported: Sequence[str]):
    """查三层，返回本租户算得出来的指标标签。查不动 → 空元组（不猜）。"""
    try:
        from smartbi.gold.queries import tenant_conn
        from smartbi.gold.restaurant.generic_executor import existing_columns
        from smartbi.gold.restaurant.metric_registry import METRICS

        needed: Dict[str, None] = {}
        for key, metric in METRICS.items():
            if key in set(unsupported):
                continue
            for table in _source_tables(getattr(metric, "requires", ()) or ()):
                needed.setdefault(table, None)

        async with tenant_conn(pool, factory_id) as conn:
            schema_columns = await existing_columns(conn)
            counts: Dict[str, int] = {}
            for table in needed:
                if not any(col.startswith(table + ".") for col in schema_columns):
                    counts[table] = 0
                    continue
                # table 来自 registry 的固定字面量, 不接受外部输入。
                row = await conn.fetchrow(
                    f"SELECT count(*)::int AS n FROM {table} WHERE factory_id = $1",
                    factory_id,
                )
                counts[table] = int((row or {}).get("n") or 0)
    except Exception as exc:  # noqa: BLE001
        # ⛔ 查不动就返回空 —— 「我这儿有的是…」是承诺, 猜错比不说更糟。
        logger.warning("[capability] 无法确认本租户能算什么, 省掉那一段: %s", exc)
        return ()

    return computable_labels(schema_columns, counts, unsupported=unsupported)
