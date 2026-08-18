"""⛔ 不许把「我这次没拉」说成「你没提供」—— 而那一维**有数据**。

## 缺陷（📏 MOCK_REST prod 2026-08-18）

`narrative_cache` 里一条 8-17 16:04 UTC 写入、TTL 24h 的答案（948 字）说：

```
「你给的摘要里只有营业额排名前5的门店，没有后5名的数据，所以没法判断哪家垫底」
「需要你提供后5家店的营业额、订单数、就餐人数数据，才能算出哪家店真的垫底」
```

而那 10 家店的**营业额 / 订单数 / 就餐人数全在库里**。
老板照着去「提供数据」会一无所获 —— 反目标里最重的那一条：
**一条误发的提示，烧掉的是「这东西说的话能信」。**

⚠️ 它是 `kind=answer`，**任何「答上率」都把它数成成功**。

## 📏 为什么闸不能按措辞抓（形态 E）

扫本租户 28 条缓存，**8 条**含「让用户去弄数据」的措辞：

| 类 | 条数 | 判断 |
|---|---|---|
| 导出预算表 / 租金 / 会员名单 / 领料明细 | 6 | **正当** —— 那些确实不在库里 |
| 「需要你提供后5家店的营业额…」 | 1 | **冤枉** —— 门店数据就在库里 |
| 「目前没有菜品销售明细和成本数据」 | 1 | **同源可疑** —— 那次 plan 没拉 sales，对老板是假话 |

⇒ 按措辞抓的闸误报率 **6/8**，必然被加 `noqa` 或被注掉。
▎判据必须是「**那一维有没有数据**」——**查**，不是**猜**。

## 修法：给既有的 grounding 闸加一条**反向**规则

`_narrative_grounding_violations` 已经在查「缺失维度被当作事实」
（`missing_dimensions` + `_MISSING_DIMENSION_TERMS`）。
本 PR 加它的镜像：**available 维度被说成「你没提供」**。

同一张词表、同一个 clause 循环，⛔ 不新造第二套判据。

⚠️ 只对 `status == "available"` 报违规。`partial`（部分覆盖）时说
「需要你补 X」可能是正当的 —— 宁可窄而可信（形态 E）。
⚠️ 同时出现在 available 和 missing 两侧时按 **missing** 算（⇒ 不报）。

## ⚠️ 失败模式是安全的

违规命中后走 `_deterministic_fallback_response` —— **确定性事实汇总**，不是空答案。
⇒ 最坏情况是老板拿到确定性数字而不是一条让他做无用功的建议。
"""
from __future__ import annotations

import pytest

from smartbi.agent.factbook import FactBook
from smartbi.agent.synthesis_engine import _narrative_grounding_violations


def _fb(available=(), missing=()):
    return FactBook(
        available_dimensions=[
            {"code": c, "status": s} for c, s in available
        ],
        missing_dimensions=[{"code": c} for c in missing],
    )


def _violations(answer, **kw):
    return _narrative_grounding_violations(answer, _fb(**kw))


def _blaming(vs):
    return [v for v in vs if "说成用户没提供" in v]


# ── 承重 ────────────────────────────────────────────────────────────────

def test_the_prod_sentence_is_rejected():
    """📏 prod 那条 948 字答案里的原句。"""
    answer = "需要你提供后5家店的营业额、订单数、就餐人数数据，才能算出哪家店真的垫底。"
    vs = _violations(answer,
                     available=(("revenue", "available"),
                                ("guest_traffic", "available")))
    assert _blaming(vs), f"没拦住：{vs}"


def test_the_summary_complaint_is_rejected():
    """同一条答案的开头那句。"""
    answer = "你给的摘要里只有营业额排名前5的门店，没有后5名的数据。"
    vs = _violations(answer, available=(("revenue", "available"),))
    assert _blaming(vs), f"没拦住：{vs}"


# ── 阴性对照：⛔ 不许把正当的求助也拦掉 ──────────────────────────────────

def test_asking_for_genuinely_missing_data_is_allowed():
    """📏 6/8 是这一类：评价数据**确实**没接入，让老板上传是正当的。"""
    answer = "评价数据还没接入，请上传大众点评/美团评价导出，补充后可判断口碑。"
    vs = _violations(answer, available=(("revenue", "available"),),
                     missing=("review",))
    assert not _blaming(vs), f"把正当的求助拦掉了：{vs}"


def test_partial_coverage_may_still_ask_for_more():
    """`partial` = 部分覆盖，说「需要你补」可能正当 ⇒ ⛔ 不报（宁可窄而可信）。"""
    answer = "需要你提供更完整的营业额数据。"
    vs = _violations(answer, available=(("revenue", "partial"),))
    assert not _blaming(vs), f"partial 也被拦了：{vs}"


def test_a_dimension_in_both_lists_counts_as_missing():
    """同时出现在两侧时按 missing 算 —— ⛔ 不报。"""
    answer = "需要你提供营业额数据。"
    vs = _violations(answer, available=(("revenue", "available"),),
                     missing=("revenue",))
    assert not _blaming(vs), f"两侧都有时报了：{vs}"


def test_no_ask_phrase_no_violation():
    """阴性对照：只提到营业额、没让用户去弄数据 ⇒ ⛔ 不报。"""
    answer = "本期营业额 ¥2,076 万，订单数 57,792 单。"
    vs = _violations(answer, available=(("revenue", "available"),))
    assert not _blaming(vs), vs


def test_asking_for_something_not_in_the_dimension_table_is_allowed():
    """「导出预算表 / 房租合同」不是任何一个 dimension code ⇒ ⛔ 不报。

    📏 那 8 条里的 6 条正当求助多数是这一类。
    """
    answer = "需要你提供该店的房租合同和员工工资表。"
    vs = _violations(answer, available=(("revenue", "available"),))
    assert not _blaming(vs), vs


# ── 同源：⛔ 不新造第二套词表 ────────────────────────────────────────────

def test_it_reuses_the_existing_dimension_term_table():
    """两条规则（缺失 / 反向）读**同一张**词表，⛔ 不许新造第二套。

    ⚠️ 第一版我用 `src.count("_MISSING_DIMENSION_TERMS")`，读出 3 次而我期望 2 ——
    第三次是**我自己写的那行注释**里提到了它。
    ▎数文本会把注释里「提到」的也算成「引用」（本仓记过的同一形态）。
    ⇒ 改用 AST 数真正的 `Name` 节点。
    ⚠️ 而那个「2」我原本是**凭记忆**写的，没数过。
    """
    import ast
    import inspect

    from smartbi.agent import synthesis_engine as se

    # ⚠️ 2026-08-18: 判定逻辑搬进了 `_grounding_findings`，
    #    `_narrative_grounding_violations` 只剩一个**薄视图**（取描述那一半）。
    #    ⇒ 锚点跟着搬。这**不是**「断言守旧行为」，是我搬了家而锚点没跟着搬
    #      （形态 D 的近亲：同一件事的两个名字，改一个数不到另一个）。
    tree = ast.parse(inspect.getsource(se._grounding_findings))
    refs = [
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.Name) and node.id == "_MISSING_DIMENSION_TERMS"
    ]
    assert len(refs) == 2, (
        f"真正引用了 {len(refs)} 次 —— 期望 2 次"
        f"（缺失那条 + 反向那条）。⛔ 不许新造第二张词表"
    )

    # 🔴 阴性对照：薄视图里**一条判定都不许有** —— 否则就是两份判定（形态 D）。
    thin = ast.parse(inspect.getsource(se._narrative_grounding_violations))
    thin_refs = [
        node for node in ast.walk(thin)
        if isinstance(node, ast.Name) and node.id == "_MISSING_DIMENSION_TERMS"
    ]
    assert not thin_refs, (
        "薄视图里出现了判定逻辑 —— 同一件事长出了第二份"
    )


def test_the_ask_phrase_alone_is_not_a_violation():
    """🔴 承重：触发词**单独命中不构成违规**。

    📏 这是整条设计的关键 —— 扫 28 条缓存里 8 条含这类措辞而 6 条是正当的。
    只按措辞抓 ⇒ 误报率 6/8 ⇒ 闸必然被关掉（形态 E）。
    """
    answer = "需要你提供房租合同。"
    assert not _blaming(_violations(answer, available=(("revenue", "available"),)))
    # 阳性前提：触发词确实在这句里，否则上面那条断言毫无意义
    from smartbi.agent.synthesis_engine import _ASK_USER_TO_SUPPLY_RE

    assert _ASK_USER_TO_SUPPLY_RE.search(answer), "触发词没命中 —— 上一条断言恒真"


@pytest.mark.parametrize("code,term", [
    ("revenue", "营业额"),
    ("revenue", "订单数"),
    ("guest_traffic", "就餐人数"),
    ("review", "评价"),
])
def test_the_terms_this_gate_depends_on_are_present(code, term):
    """📏 钉住判据依赖的那几个词 —— ⛔ 不靠记忆。"""
    from smartbi.agent.synthesis_engine import _MISSING_DIMENSION_TERMS

    assert term in _MISSING_DIMENSION_TERMS[code]
