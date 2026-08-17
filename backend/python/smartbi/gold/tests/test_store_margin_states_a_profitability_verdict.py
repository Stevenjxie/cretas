"""门店毛利对比要说一句「谁在亏钱」—— 那正是老板问的那件事。

## 缺陷（2026-08-17 MOCK_REST 冷启动实测）

老板问「哪几家店在拖后腿」：

```
契约 missing = ('profitability_verdict',)     实测 2/3 次拒答
```

而**答上的那一次内容是好的**：「门店毛利对比（最近30天，10 家店）」+ 排名表。

▎缺口在**生成侧**：给了对比、给了排名，却从没说一句「谁在亏钱」。
▎契约要的不是关键词，是**这个判断本身** —— 而它恰好也是老板问的那件事。

⚠️ 归因过程本身值得记：我先后错怪过**叙事接地闸**（实测 `gate+0`，
   分层路径根本不走 `synthesis_engine`）和**维度闸**（`执行前拦截` 日志 0 条）。
   真凶是答案契约，靠钩 `answer_contract.validate` 打出 `missing` 才定位到。
   ⇒ **拒答文案长得像哪道闸，不等于就是那道闸。**

## 三条不许犯的（都写成了断言）

1. `margin_rate is None` 的门店**单独一档**，⛔ 绝不并进「赚钱」——
   那是把「我不知道」说成「是正的」（形态 A¹⁰）。
2. 必须带覆盖率口径：这个毛利只算了有成本卡的那部分营收。
   不说这句，老板会拿它当「这家店真在亏」去关店。
3. 全是正的时**照样说**（「都还赚钱」）—— 只在有亏损时才出现的话，
   它就成了「只报坏消息」的警报，而他问的是判断。
"""
import ast
import inspect
import re

import pytest

from smartbi.gold.restaurant.answer_contract import _PROFIT_VERDICT_TOKENS


# prod 实测（MOCK_REST, 2026-08-17）那次答上时的抬头，作为真实形状锚点。
_REAL_HEADER = "**门店毛利对比（最近30天，10 家店）**"


def _resolver_src():
    from smartbi.gold.restaurant import restaurant_ops_router as router
    return inspect.getsource(router.resolve_store_margin)


def _dish_resolver_src():
    from smartbi.gold.restaurant import restaurant_ops_router as router
    return inspect.getsource(router.resolve_gross_margin)


def _dish_verdict_reaches_the_answer() -> int:
    """菜品那条路的判断句**被拼进 answer** 的次数。

    ⚠️ 2026-08-17: 门店那条修完之后, 「哪道菜不赚钱」仍然 3/3 被拒 ——
       **同一个缺口长在另一个载体上**(`resolve_gross_margin`)。
       修一处时要问一句: 这个形状还有没有别的载体。
    """
    tree = ast.parse(_dish_resolver_src())
    n = 0
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign) and any(
                getattr(t, "id", None) == "answer" for t in node.targets):
            for x in ast.walk(node.value):
                if isinstance(x, ast.Name) and x.id == "_dish_verdict":
                    n += 1
    return n


def _verdict_reaches_the_answer() -> int:
    """判断句**被拼进 answer** 的次数。

    🔴 这个函数存在的理由: 第一版断言 grep 源码里有没有「盈亏判断」这几个字,
       而把 `f"{_verdict}"` 从 answer 的 f-string 里删掉之后 **11 条断言全绿** ——
       构造判断句的代码还在, 只是没接上。那正是本仓最常犯的
       「测了 helper, 没测接线」。
    ⇒ 用 AST 问「answer 这个赋值的 f-string 里有没有引用 _verdict」。
    """
    tree = ast.parse(_resolver_src())
    n = 0
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign) and any(
                getattr(t, "id", None) == "answer" for t in node.targets):
            for x in ast.walk(node.value):
                if isinstance(x, ast.Name) and x.id == "_verdict":
                    n += 1
    return n


class TestTheVerdictIsPartOfTheAnswer:
    def test_the_verdict_is_actually_interpolated_into_the_answer(self):
        """🔴 承重: 判断句必须**被拼进** answer, 不只是被构造出来。"""
        assert _verdict_reaches_the_answer() == 1, (
            "answer 的 f-string 里没有引用 _verdict —— 判断句构造了但没接上, "
            "契约照旧会以 profitability_verdict 缺失拒答"
        )

    def test_the_verdict_uses_words_the_contract_recognises(self):
        """⛔ 不是为了骗过契约塞关键词 —— 这些词本来就是这个判断的说法。

        钉住**同源**: 契约认的词表变了这里要跟着变, ⛔ 不许两边各写一份。
        """
        hit = [t for t in _PROFIT_VERDICT_TOKENS if t in _resolver_src()]
        assert hit, f"判断句里一个契约认得的说法都没有（词表 {_PROFIT_VERDICT_TOKENS}）"

    def test_unknown_stores_are_not_counted_as_profitable(self):
        """`margin_rate is None` 的门店必须单独说, ⛔ 不许并进「赚钱」。"""
        src = _resolver_src()
        assert "算不出" in src and "没有并进上面任何一档" in src, (
            "成本数据不足的门店没有被单独说明 —— 那是把「我不知道」说成「是正的」"
        )

    def test_the_verdict_carries_the_coverage_caveat(self):
        """必须说清这是按**有成本卡那部分**算的, 不是整店真实盈亏。"""
        assert "不等于整店真实盈亏" in _resolver_src(), (
            "判断句没带覆盖率口径 —— 老板会拿部分覆盖的毛利当真实盈亏去关店"
        )

    def test_it_also_speaks_when_everything_is_profitable(self):
        """⛔ 不能只在有亏损时才出现 —— 那样它就成了只报坏消息的警报。"""
        assert "都还赚钱" in _resolver_src(), "全部盈利时没有判断句"

    def test_all_unknown_says_it_cannot_tell(self):
        """一家都算不出时要明说算不出, ⛔ 不拿营收高低顶替。"""
        assert "不拿营收高低顶替" in _resolver_src()


class TestTheDishPathHasTheSameVerdict:
    """同一个缺口的第二个载体 —— 菜品毛利。"""

    def test_dish_verdict_is_interpolated_into_the_answer(self):
        assert _dish_verdict_reaches_the_answer() == 1, (
            "菜品毛利的 answer 里没有拼进盈亏判断 —— 「哪道菜不赚钱」会继续被"
            "契约以 profitability_verdict 缺失拒答(实测 3/3)"
        )

    def test_dish_unknown_cost_is_not_counted_as_profitable(self):
        src = _dish_resolver_src()
        assert "没有成本卡，赚没赚**算不出**" in src, (
            "没有成本卡的菜没被单独说明 —— 那是把「我不知道」说成「是正的」"
        )

    def test_dish_verdict_speaks_when_nothing_loses_money(self):
        assert "没有卖一份亏一份的" in _dish_resolver_src(), (
            "一道都不亏时没有判断句 —— 老板问的是判断, 不是警报"
        )

    def test_dish_all_unknown_says_it_cannot_tell(self):
        assert "不拿销量高低顶替" in _dish_resolver_src()


class TestTheContractWouldNowAccept:
    @pytest.mark.parametrize("sample,should_pass", [
        ("- 盈亏判断：10 家店里 **2 家在亏钱**（A店、B店），其余 8 家赚钱。", True),
        ("- 盈亏判断：10 家店都还赚钱，差的是快慢不是盈亏。", True),
        ("- 盈亏判断：10 家店的成本数据都不足，**赚没赚钱这次算不出来**。", True),
        ("**门店毛利对比（最近30天，10 家店）** 毛利率 12.3%", False),
    ])
    def test_contract_verdict_detection(self, sample, should_pass):
        """阴性对照在最后一行：只有对比和毛利率、没有判断句的文本**必须**不过。

        少了它，上面三条可能只是因为检测函数对什么都返回 True。
        """
        from smartbi.gold.restaurant.answer_contract import (
            _profitability_verdict_present,
        )
        assert _profitability_verdict_present(sample) is should_pass, sample


class TestTheRealShapeIsStillTheHeader:
    def test_header_unchanged(self):
        """回归守卫：抬头是 prod 实测过的那一句，判断句是**加**在它下面。"""
        import inspect

        from smartbi.gold.restaurant import restaurant_ops_router as router
        src = inspect.getsource(router.resolve_store_margin)
        assert re.search(r"门店毛利对比（\{window_label\}，\{len\(store_list\)\} 家店）", src), (
            "抬头变了 —— 它是 prod 上验过的形状，改它要重新验一次"
        )
