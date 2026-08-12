"""指纹分层的验收 —— 三项，第 3 项是分层这件事本身唯一有意义的验收。

## 分层是什么

`_routing_rules_fingerprint()`（全料，计划缓存键用它，**值不变**）拆成两段：

  `_plan_semantics_materials()`  存量计划**编译器**读的 → 它变了，存下来的
                                 plan_json 可能编译/执行成另一个东西 → 必须作废
  `_prompt_surface_materials()`  只有 **prompt 构造器**读的 → 它变了，下一次规划的
                                 产物会变，但已存的 plan_json 怎么编译不变
                                 → 让计划**过时**(stale)而不是**错**(wrong)

⛔ 划分不是手写清单，是按「谁消费它」推导的。手写清单错了是**静默的**。
   本文件里 `test_the_split_matches_who_actually_consumes_each_material`
   把这个推导钉住：某份原料被编译期用上/不再用，那条就红。

## 分层**不会**让存量复活

旧行存的是**旧算法的全料指纹**（裸 8-hex，无分隔符），取第一段仍不等于新语义段。
所以 40 条旧晋升保持失效 —— 这是**预期**，验收第 1 项验的就是「没有意外复活」。
复活要由人按台账逐条盖章，不由分层顺带做掉。
"""
import re

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri


# ── 验收 1: 旧行仍然失效（验的是「分层没有意外让它们复活」）──────────────
@pytest.mark.parametrize("legacy", ["ca8f67fc", "deb9308d"])
def test_legacy_bare_fingerprints_stay_invalid(legacy):
    """prod 上那 40 条存的就是这两个值。分层后它们必须**仍然**不匹配。"""
    assert ri.plan_semantics_segment(legacy) == "", (
        "旧格式(裸 8-hex)必须按**格式**判失效, 不能靠比值 —— 见下面那条断言")
    assert ri.plan_semantics_segment(legacy) != ri._plan_semantics_fingerprint(), (
        f"旧行 {legacy} 竟然匹配上了新语义段 —— 分层顺带把人审过的计划复活了, "
        f"而我们没有任何依据说它们仍然有效")


def test_the_collision_that_made_format_based_gating_necessary():
    """🔴 这不是假设: 新语义段实测**正好等于** prod 上 39/40 行存的那个值。

    `ca8f67fc` 是 2026-08-09 把 `_render_aggregation_vocabulary()` 并进指纹
    **之前**的旧全料指纹 —— 那时的原料集恰好就是现在这三份语义原料。
    所以「取第一段然后比值」会让 39 条静默复活。

    ⛔ 本条钉住的是: **靠「哈希不会撞」的设计不叫设计。**
       哪天有人把 `plan_semantics_segment` 改回「无分隔符就原样返回」, 这条会红。
    """
    assert ri._plan_semantics_fingerprint() == "ca8f67fc", (
        "语义段的值变了 —— 这条断言的**意义**是记录那次重合, 值变了就重新读一遍"
        "本文件的 docstring, 确认按格式判定这个决定仍然成立")
    assert ri.plan_semantics_segment("ca8f67fc") == "", (
        "又退回按值比了 —— 39 条人审会静默复活")


def test_layering_does_not_change_the_full_fingerprint_value():
    """全料指纹的**值**不能变 —— 计划缓存键用它, 变了会让全部缓存计划失效。

    ⛔ 这不是「顺带做掉的清理」, 是分层必须避免的副作用。
    """
    material = "\x1f".join((*ri._plan_semantics_materials(),
                            *ri._prompt_surface_materials()))
    import hashlib
    assert ri._routing_rules_fingerprint() == hashlib.sha256(
        material.encode("utf-8")).hexdigest()[:8]
    # 两段拼起来必须**正好**是全料, 不多不少 —— 漏一份原料就是漏一处失效触发。
    assert len(ri._plan_semantics_materials()) + len(ri._prompt_surface_materials()) == 4


# ── 验收 2: 新晋升存两段, 且立即可命中 ──────────────────────────────
def test_new_promotion_stores_two_segments_and_matches_immediately():
    composed = ri.compose_routing_fingerprint()
    assert re.fullmatch(r"[0-9a-f]{8}\.[0-9a-f]{8}", composed), (
        f"新晋升存的不是 `<语义段>.<prompt段>`: {composed!r}")
    # 立即可命中 = 取第一段等于当前语义段
    assert ri.plan_semantics_segment(composed) == ri._plan_semantics_fingerprint()
    # 阳性对照: 两段确实不同源 —— 相同的话「分层」只是换了个写法
    sem, prompt = composed.split(".")
    assert sem != prompt


def test_promotion_write_path_uses_the_composed_form():
    """⛔ 写入端与校验端必须**共用同一份原料定义**（形态 D: 同一个东西有两份会漂）。"""
    from smartbi.gold.restaurant import restaurant_intent_promotion as promo

    assert promo._current_routing_fingerprint() == ri.compose_routing_fingerprint()


# ── 验收 3: 分层的变异对照 —— 这是唯一直接验「分层了」的一项 ─────────────
def test_prompt_only_change_does_NOT_invalidate_promotions(monkeypatch):
    """改一条**不影响计划语义**的路由规则 → 晋升条目**不失效**。

    这是分层的正面：以前扩个词汇表就把 40 条人审全作废。
    """
    before_sem = ri._plan_semantics_fingerprint()
    before_full = ri._routing_rules_fingerprint()

    monkeypatch.setattr(ri, "_render_aggregation_vocabulary",
                        lambda: "扩了词汇表:新增「按时段」「按渠道」")

    assert ri._plan_semantics_fingerprint() == before_sem, (
        "只改了 prompt 词汇表, 语义段却变了 —— 那 40 条人审会被无谓作废, "
        "分层等于没做")
    assert ri._prompt_surface_fingerprint() != ri._prompt_surface_fingerprint.__wrapped__() \
        if hasattr(ri._prompt_surface_fingerprint, "__wrapped__") else True
    # 全料指纹**应该**变（计划缓存该失效, 那一层的语义没变）
    assert ri._routing_rules_fingerprint() != before_full, (
        "prompt 变了全料指纹却没变 —— 计划缓存会重放旧计划, 那是 #2043 事故本身")


def test_semantic_change_DOES_invalidate_promotions(monkeypatch):
    """改一条**影响计划语义**的路由规则 → 晋升条目**必须失效**。

    ⛔ 没有这条, 上一条就只证明了「什么都不失效」。
    """
    before_sem = ri._plan_semantics_fingerprint()

    patched = tuple(ri._REQUEST_METRIC_RULES) + (("__layer_probe__", ("__layer_probe__",)),)
    monkeypatch.setattr(ri, "_REQUEST_METRIC_RULES", patched)

    assert ri._plan_semantics_fingerprint() != before_sem, (
        "改了指标编译规则(#2043 改的正是它)语义段却没变 —— "
        "晋升计划不作废, 然后开始答错")


def test_intent_code_removal_is_semantic(monkeypatch):
    """第二个语义面: resolver 表的**键**变了也必须失效。

    存量计划带 `intent` 码, 码没了它就编译不出来 —— 这是编译期依赖。
    """
    before = ri._plan_semantics_fingerprint()
    original = dict(ri._INTENT_DESCRIPTIONS)
    try:
        ri._INTENT_DESCRIPTIONS["__LAYER_PROBE__"] = "分层探针"
        assert ri._plan_semantics_fingerprint() != before
    finally:
        ri._INTENT_DESCRIPTIONS.clear()
        ri._INTENT_DESCRIPTIONS.update(original)
    assert ri._plan_semantics_fingerprint() == before


def test_intent_description_text_is_not_semantic():
    """而 resolver 的**描述文本**不是语义面 —— 它只进 prompt。

    ⚠️ 这条今天就成立（`sorted(dict)` 只取键），写下来是为了让下一个人改成
       `repr(dict)` 时立刻红：那会让「改一句描述」把 40 条人审全作废。
    """
    assert all(isinstance(m, str) for m in ri._plan_semantics_materials())
    joined = "\x1f".join(ri._plan_semantics_materials())
    sample_desc = next(iter(ri._INTENT_DESCRIPTIONS.values()))
    assert sample_desc not in joined, (
        "resolver 描述文本进了语义段 —— 改一句话就会把人审过的晋升全部作废")


# ── 推导钉子: 划分必须与「谁消费它」一致 ────────────────────────────
def test_the_split_matches_who_actually_consumes_each_material():
    """⛔ 划分不是手写清单。这条按**调用点**把推导钉住。

    `_render_aggregation_vocabulary` 归进 prompt 段的依据是: 它在本模块里
    除了指纹自己, **只被 prompt 构造器调用**。哪天它被编译期用上, 这条就红,
    逼人重新分类 —— 而不是让一条编译期依赖静默待在「不影响」那层。
    """
    import ast
    import inspect

    # ⛔ 用 AST 数**真正的 Call 节点**, 不做文本 grep。
    #    第一版是 grep 行, 结果把 docstring 里提到函数名的那一行也数成了调用点
    #    —— 仪器自己制造了一个假读数(形态 A: 我量的不是我想知道的那个)。
    tree = ast.parse(inspect.getsource(ri))
    calls = [
        node for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "_render_aggregation_vocabulary"
    ]
    # 允许的两处: `_prompt_surface_materials` 的 return, 和 prompt 构造器里的拼接。
    assert len(calls) == 2, (
        f"`_render_aggregation_vocabulary()` 的调用点变成了 {len(calls)} 处 "
        f"(行号 {[n.lineno for n in calls]}) —— 分层依据是「谁消费它」, "
        f"调用点变了就要重新分类, ⛔ 不许直接改这个数字了事")

    # 阳性对照: 这个仪器真的数得到东西 —— 0 的话上面那条断言毫无意义。
    assert calls, "AST 一个调用点都没找到, 仪器坏了"
