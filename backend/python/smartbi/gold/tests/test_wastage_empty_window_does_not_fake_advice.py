"""窗口内零损耗时，⛔ 不许发那三条预设有数据的建议动作。

## 缺陷（2026-08-17 prod 实测，RES_3101_009）

```
- 总损耗 0 次, 0.00 单位, 损失 ¥0.00
- 损耗类型分布: 无数据
(最近30天无损耗记录)
建议动作:
1. 先把**损耗金额最高的类型**拆到门店和班次…   ← 那个类型不存在
2. 对**损耗靠前的食材**设一周复盘线…           ← 没有靠前的食材
3. 对水产、肉类等高价值食材优先复核收货净重…    ← 通用话术
```

三条全都预设有数据。读起来像分析，一个字都不成立。
▎本仓 anti-goal：一条会误发的提示，烧掉的是「这东西说的话能信」这件事本身。

## ⛔ 但零损耗是**合法状态**，不许断言「没人录」

实测该租户：最后一条损耗 2026-06-08（70 天前），而 POS 数据到 2026-08-16 ——
店在正常营业。**这两件事摆出来老板一眼就知道该问谁；替他下结论反而会错。**
（本仓形态 A¹¹：算「缺了多少」之前先问「这里的空是不是一种合法状态」。）

## 与正上方 `cost_axis_unavailable` 同一条纪律

那条守「不拿数量排名顶金额排名」，这条守「不拿通用建议顶没有的数据」。
"""
import re

import pytest

from smartbi.gold.restaurant import restaurant_ops_router as router

ADVICE_MARKERS = ("建议动作", "损耗金额最高的类型", "损耗靠前的食材", "收货净重")


class TestEmptyWindowSaysWhatIsMissing:
    def test_the_advice_block_presupposes_data(self):
        """先钉住前提：那三条建议**确实**预设了有数据。

        ⛔ 少了这条，下面「空窗口不发建议」的断言就没有理由 ——
           如果建议本来就是通用的、与数据无关，那删掉它反而是损失。
        """
        src = router._markdown_table.__module__  # noqa: F841  (仅确保模块可导入)
        import inspect
        body = inspect.getsource(router)
        assert "损耗金额最高的类型" in body
        assert "对损耗靠前的食材设一周复盘线" in body
        # 这两句的主语都是「排在前面的那个」——没有数据时它们指向不存在的东西。

    @pytest.mark.parametrize("marker", ADVICE_MARKERS)
    def test_empty_window_branch_does_not_emit_advice(self, marker):
        """空窗口分支拼出来的文案里不许出现建议动作的任何一句。

        ⛔ 用**源码结构**判：空窗口分支是 `if window_has_nothing:` 下面那一支，
           它拼的 `answer` 只有总览 + next_step 两段。
        """
        import ast
        import inspect
        tree = ast.parse(inspect.getsource(router.resolve_wastage_top))
        # 找到 `if window_has_nothing:` 这个分支
        branch = None
        for node in ast.walk(tree):
            if (isinstance(node, ast.If)
                    and isinstance(node.test, ast.Name)
                    and node.test.id == "window_has_nothing"):
                branch = node
                break
        assert branch is not None, "空窗口分支不见了 —— 那三条建议又会无条件发出去"
        emitted = " ".join(
            n.value for n in ast.walk(ast.Module(body=branch.body, type_ignores=[]))
            if isinstance(n, ast.Constant) and isinstance(n.value, str)
        )
        assert marker not in emitted, (
            f"空窗口分支里出现了 {marker!r} —— 它预设有数据，而这一支正是没有数据"
        )

    def test_empty_window_branch_states_the_last_record_date(self):
        """摆事实：必须告诉老板最后一次录损耗是哪天、距今多久。"""
        import ast
        import inspect
        src = inspect.getsource(router.resolve_wastage_top)
        assert "last_wastage_date" in src, "没有去查最后一条损耗的日期"
        assert "gap_days" in src, "没有算距今多少天 —— 只给日期老板还得自己减"
        tree = ast.parse(src)
        assert any(
            isinstance(n, ast.Constant) and isinstance(n.value, str)
            and "两种可能" in n.value
            for n in ast.walk(tree)
        ), "没有说清「真没损耗 / 没人录」两种可能 —— ⛔ 也不许替老板断言是哪一种"

    def test_it_never_asserts_nobody_recorded(self):
        """⛔ 零损耗是合法状态，产品不许断言「没人录」。"""
        import inspect
        src = inspect.getsource(router.resolve_wastage_top)
        for claim in ("说明没人录", "肯定没人录", "一定是没人录"):
            assert claim not in src, f"替老板下了结论: {claim!r}"


class TestTheNormalPathIsUntouched:
    def test_advice_still_exists_for_the_non_empty_case(self):
        """阳性对照：有数据时那三条建议**必须还在**。

        少了这条，「空窗口不发建议」可能只是因为建议被整体删掉了。
        """
        import inspect
        src = inspect.getsource(router.resolve_wastage_top)
        assert re.search(r"建议动作:\\n", src), "有数据的那一支也没有建议动作了"


# ⛔ 上面那些断言扫的都是**源码结构**。本仓形态 B 要求「至少一条断言跑在产品
#    真实入口上」—— 下面这一组真调 `resolve_wastage_top`，只桩掉数据库。
#
# ⚠️ 桩的形状必须是**真实上游产得出的**（形态 B‴）:
#    · `fetch` 空结果 → `[]`             ✅ 窗口内没有行时就是这样
#    · totals 那条 SQL 全是 `COALESCE(..., 0)` ⇒ 空集上返回**零值的一行**,
#      ⛔ 不是 None —— 喂 None 就是造一个真实 SQL 永不产出的形状
class _FakeConn:
    def __init__(self, last_wastage_date):
        self._last = last_wastage_date

    async def execute(self, *a, **kw):
        return "SET"

    async def fetch(self, *a, **kw):
        return []

    async def fetchrow(self, *a, **kw):
        return {"total_qty": 0.0, "total_cost": 0.0, "total_count": 0}

    async def fetchval(self, *a, **kw):
        return self._last


class _FakePool:
    def __init__(self, last_wastage_date):
        self._conn = _FakeConn(last_wastage_date)

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


@pytest.mark.asyncio
class TestTheRealResolverOnAnEmptyWindow:
    @pytest.mark.parametrize("marker", ADVICE_MARKERS)
    async def test_answer_text_carries_no_fabricated_advice(self, marker):
        from datetime import date as _date
        answer = await router.resolve_wastage_top(
            _FakePool(_date(2026, 6, 8)), "RES_3101_009", days=30)
        text = str(getattr(answer, "answer", None) or answer)
        assert marker not in text, (
            f"零损耗的答案里还印着 {marker!r}：\n{text}"
        )

    async def test_answer_text_states_the_last_record_and_the_gap(self):
        from datetime import date as _date
        last = _date(2026, 6, 8)
        answer = await router.resolve_wastage_top(
            _FakePool(last), "RES_3101_009", days=30)
        text = str(getattr(answer, "answer", None) or answer)
        assert "2026-06-08" in text, f"没告诉老板最后一次录损耗是哪天：\n{text}"
        assert "两种可能" in text, f"没说清「真没损耗 / 没人录」两种可能：\n{text}"
        gap = (_date.today() - last).days
        assert str(gap) in text, f"没算距今多少天（应含 {gap}）：\n{text}"

    async def test_a_tenant_that_never_recorded_gets_a_different_sentence(self):
        """从来没录过 vs 录过但停了 —— 老板要做的事不一样，话就得不一样。"""
        answer = await router.resolve_wastage_top(
            _FakePool(None), "R_GML_DEMO", days=30)
        text = str(getattr(answer, "answer", None) or answer)
        assert "从来没有过损耗记录" in text, text
        assert "两种可能" not in text, (
            "从来没录过的租户不该说「两种可能」—— 没有「停了」这一种"
        )
