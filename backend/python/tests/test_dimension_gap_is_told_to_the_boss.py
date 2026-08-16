"""维度对不上时，拒答必须说清**差在哪一层**，而不是一句通用反问。

## 为什么有这个文件（2026-08-17 冷启动实测）

以老板身份走真实入口跑 39 句，5 句撞在维度闸上，拿到的都是同一句：

    「我不确定你要看的是哪一层的数，所以这次我没敢算。
      你是想看某道菜、某家门店，还是全店合计？说一个就行，我不会拿别的数据凑。」

而服务端**那一刻就知道差在哪**——同一条路径的 warning 日志写着：

    目标 resolver 只服务 ['channel'], 服务不了本计划的 ['channel','dish']

差是算出来的，只是没投影到用户侧 ⇒ **形态 B 第 7 例（投影丢失）**。
老板看到通用反问，**无从知道该改哪个字**；而北极星是「让他自己做一个决定」。

⛔ 修法**不是**去掉那一层硬答（那是「拿别的数据凑」，本仓明令禁止），
   只是把**拒答**说清楚：哪一层能算 / 哪一层不能 / 换什么问法能拿到。

## 这个文件的两层断言

1. **行为**：`_dimension_gap_advice` 说得出具体的层名（取登记表 `Dimension.label`）
2. **接线**：拒答文案那处**真的调用了它** —— 用 AST 数 `Call` 节点，
   ⛔ 不用字符串计数（本仓形态 C⁸：字符串量的是文本，AST 量的是结构）

⚠️ 第 2 层是**兜底**，它证明「有人调它」，不证明「生产上走到那一行」。
   走真实入口的证据在部署后的 prod 实测里（见台账）。
"""
import ast
import inspect

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as svc


class _Spec:
    """只带这条判据需要的字段 —— ⛔ 不构造整个 RestaurantQuerySpec。"""

    def __init__(self, dimensions):
        self.dimensions = tuple(dimensions)


def _plan_serving(*dimension_keys):
    """造一个「只服务这些分组」的计划。

    ⚠️ 直接改 `_RESOLVER_DIMENSIONS` 会污染别的用例，所以用一个真实存在的
       resolver code 不可行 —— 这里用 monkeypatch 在用例里注入。
    """
    return ("__PROBE_RESOLVER__",)


@pytest.fixture
def probe_plan(monkeypatch):
    """注入一个能力已知的 resolver，返回一个 setter。"""
    def _set(*serves):
        table = dict(svc._RESOLVER_DIMENSIONS)
        table["__PROBE_RESOLVER__"] = frozenset(serves)
        monkeypatch.setattr(svc, "_RESOLVER_DIMENSIONS", table)
        # 变异到达性：确认注入真的生效，否则后面的断言无论红不红都没意义
        assert svc._supported_dimensions(("__PROBE_RESOLVER__",)) >= set(
            svc._canonical_dimensions(sorted(serves))), "桩没注入进去"
        return ("__PROBE_RESOLVER__",)
    return _set


def test_names_the_layer_it_can_do_and_the_one_it_cannot(probe_plan):
    """🔴 承重：能算的那层和不能算的那层，都要点名。

    实测原形：「外卖和堂食哪个更赚钱」→ 计划要 channel+dish，
    而 CHANNEL_MIX 的 resolver 只服务 channel。
    """
    plan = probe_plan("channel")
    text = svc._dimension_gap_advice(_Spec(("channel", "product")), plan)

    assert text, "说不出差在哪 —— 那就退化回了通用反问"
    # 用登记表自己的中文名，⛔ 不在断言里另写一套
    assert svc.DIMENSIONS["channel"].label in text, text
    assert svc.DIMENSIONS["product"].label in text, text
    # 必须告诉他下一步怎么问，否则仍然做不了决定
    assert "分开问" in text or "换个问法" in text, text


def test_says_nothing_when_every_asked_layer_is_supported(probe_plan):
    """阴性对照：能力够时**必须**返回空串。

    ⛔ 少了它，「说得出差」在「对任何问句都说一通」时也成立 ——
    那会让本来能正常作答的问句也被塞进一段莫名其妙的解释。
    """
    plan = probe_plan("channel", "product")
    assert svc._dimension_gap_advice(_Spec(("channel",)), plan) == ""
    assert svc._dimension_gap_advice(_Spec(("channel", "product")), plan) == ""


def test_falls_back_to_empty_string_rather_than_inventing_a_layer_name(probe_plan):
    """维度键不在登记表里时，⛔ 不能崩，也不能编一个中文名。"""
    plan = probe_plan("channel")
    text = svc._dimension_gap_advice(_Spec(("channel", "__not_a_real_dim__")), plan)
    assert text, text
    assert "__not_a_real_dim__" in text, "不认识的键应原样透出，⛔ 不臆造中文名"


def test_the_refusal_message_actually_calls_the_advice(probe_plan):
    """🔴 接线：拒答文案那处真的调用了 `_dimension_gap_advice`。

    ⛔ 用 AST 数 `Call` 节点，不用字符串计数 —— 后者会把 docstring 里
       **提到**这个名字的行也数进去（本仓形态 C⁸，同形已第三次）。

    ⚠️ 这条只证明「有人调它」。「生产上真的走到那一行」由部署后的
       prod 实测负责（台账里那条读数）。
    """
    src = inspect.getsource(svc.tiered_answer)
    calls = [
        n for n in ast.walk(ast.parse(src))
        if isinstance(n, ast.Call)
        and getattr(n.func, "id", None) == "_dimension_gap_advice"
    ]
    assert len(calls) == 1, (
        f"拒答路径上调用 _dimension_gap_advice 的次数 = {len(calls)}，期望 1。"
        "0 = 接线断了(文案退回通用反问)；>1 = 同一件事算了两遍。")


#: 建闸当天 `_RESOLVER_DIMENSIONS.get(...)` 的存量处数。⛔ 只许变小。
_RESOLVER_DIMENSIONS_READS_BASELINE = 4


def test_resolver_dimensions_reads_do_not_grow():
    """🔴 棘轮：读 `_RESOLVER_DIMENSIONS` 的地方只许变少。

    ## 为什么是棘轮而不是「必须只有 1 处」

    我第一版就是断言「只有 1 处」，**当场红**：实测有 4 处 ——
    `_supported_dimensions`(归一化后的并集，判据与文案共用) 之外，
    `_drop_unanswerable_mislabeled_dimensions` 和执行回执各自还算了一份
    **未归一化**的并集，另有一处是 `"store" not in ...get(intent)` 的单点查询。

    那 3 处**不是同一个问题**（少了 `canonical_dimensions` 那一层），
    直接替换会改行为 ⇒ ⛔ 不在本轮动它们。但也⛔ 不能装作不存在：
    同一张能力表有多份推导，正是「闸说不支持、文案却说支持」那类
    不报错的不一致的来源。

    ⇒ 冻结存量、只禁增长（本仓既有做法）。想减到 1 处要单独立项，
      把那两处的归一化差异先量出来再动。
    """
    src = inspect.getsource(svc)
    reads = [
        n for n in ast.walk(ast.parse(src))
        if isinstance(n, ast.Call)
        and getattr(n.func, "attr", None) == "get"
        and getattr(getattr(n.func, "value", None), "id", None)
        == "_RESOLVER_DIMENSIONS"
    ]
    assert len(reads) <= _RESOLVER_DIMENSIONS_READS_BASELINE, (
        f"`_RESOLVER_DIMENSIONS.get(...)` 增到 {len(reads)} 处 "
        f"(基线 {_RESOLVER_DIMENSIONS_READS_BASELINE})。"
        "新增一处 = 又造了一份能力推导，先接 `_supported_dimensions`。")


def test_every_dimension_in_the_registry_has_a_boss_facing_name():
    """🔴 反向映射必须覆盖登记表 —— 否则文案会把英文键漏给店长。

    这条是**实测抓出来的**：第一版直接 `DIMENSIONS[k].label`，
    而 spec 里是管线名(`dish`)、登记表是登记键(`product`)，
    查不到就原样吐出「还要再按 dish 拆一层」。
    """
    for key, dim in svc.DIMENSIONS.items():
        canon = svc._canonical_dimensions((key,))[0]
        assert canon in svc._DIMENSION_LABEL, (
            f"登记表维度 {key!r} 的管线名 {canon!r} 不在中文名表里 —— "
            "文案会把英文键漏给老板")
        assert svc._DIMENSION_LABEL[canon] == dim.label
