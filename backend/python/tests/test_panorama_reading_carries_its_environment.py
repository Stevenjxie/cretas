"""全景的每条读数要带「这一条问句期间 LLM 抖了没」。

设计卡：`docs/decisions/2026-08-18-全景读数自带LLM抖动记录-设计卡.md`

## 为什么（📏 全部实测，⛔ 无一条来自读代码）

同一命令、同一天、生产形态跑三次：

```
39/48（日志里 LLM 故障  8）
40/48（                9）
38/48（               16）
```

方向一致 ⇒「38 vs 40」看起来像产品退步 —— **我自己就据此写过一次「未达基线」**。
而逐条对应之后因果被否掉：

```
答上 20 条，其中期间有 LLM 故障的占 50.0%
拒答  4 条，其中期间有 LLM 故障的占 25.0%   ← 拒答侧反而更低
```

⇒ 抖动与拒答无关。但查清这件事花了三轮探针，成本全在**事后**手工对日志。
▎**报数字要带口径，而「那一条问句期间抖了没」就是口径的一部分。**

## ⛔ 它只报，不改判定

不用它过滤读数（「有故障就不计入」）——那会把「没量到」偷偷折叠进「没问题」。
下面有一条断言钉住这件事。
"""
from __future__ import annotations

import ast
import inspect
import logging

from smartbi.scripts.restaurant_panorama_probe import FlapCollector

# 🔴 上面那个 import 有副作用：探针模块在**模块顶层**就调 `bootstrap_probe()`，
#    它会 `set_factory_id()` —— 一个全局 ContextVar，污染发生在 collection 时，
#    autouse fixture 还没上场（PR #2818 在另一个探针上栽过同样一次）。
# ⛔ 不用 `set_factory_id(None)`：它把 None 翻译成 `INTERNAL_SENTINEL`，不是「未设置」。
from smartbi import tenant_ctx as _tenant_ctx  # noqa: E402

_tenant_ctx.current_factory_id.set(None)


def _rec(msg: str, level: int = logging.WARNING) -> logging.LogRecord:
    return logging.LogRecord("x", level, __file__, 1, msg, None, None)


# ── 承重：对**真实格式**的告警有效 ─────────────────────────────────────────

def test_it_catches_a_real_router_warning():
    """🔴 ⛔ 不拿我编的例句测 —— 这两条是 prod 日志里逐字抄下来的。"""
    c = FlapCollector()
    for msg in (
        "[llm_router] aistore/DeepSeek-V4-Flash-A timeout (cb_fails=0/2)",
        "[llm_router] aistore/DeepSeek-V4-Flash-A exception (cb_fails=0/2): ",
        "[llm_router] slot=review aistore/DeepSeek-V4-Flash-A output invalid "
        "(empty) — falling back",
        "[llm_router] All providers exhausted for review: aistore: empty_api_key",
    ):
        c.emit(_rec(msg))
    assert len(c.bucket) == 4, c.bucket


def test_it_truncates_long_messages():
    """⛔ 一条读数不该被一段 500 字的堆栈撑爆。"""
    c = FlapCollector()
    c.emit(_rec("[llm_router] timeout " + "x" * 500))
    assert len(c.bucket[0]) <= 90, len(c.bucket[0])


# ── 阴性对照：⛔ 不许把任何 warning 都算成抖动 ─────────────────────────────

def test_it_ignores_router_logs_that_are_not_failures():
    """`[llm_router]` 也打普通日志 —— 算进去会让读数虚高。"""
    c = FlapCollector()
    c.emit(_rec("[llm_router] slot=chat aistore/DeepSeek-V4-Flash-A ok in 812ms"))
    c.emit(_rec("[llm_router] chain=aistore,deepseek,zhipu"))
    assert c.bucket == [], c.bucket


def test_it_ignores_warnings_from_elsewhere():
    """阴性对照：别的模块的告警不是 LLM 抖动。

    ⚠️ 这一条特意用一个**含 `timeout` 字样**的非 router 告警 ——
       只按关键词收会把它算进来。
    """
    c = FlapCollector()
    c.emit(_rec("[restaurant-intent] T3 LLM parse failed/timed out: timeout"))
    c.emit(_rec("[deploy] ssh timeout"))
    assert c.bucket == [], c.bucket


def test_a_broken_collector_reports_nothing():
    """🔴 变异对照（设计卡里写死的那个）：匹配条件永不命中时必须读出 0。

    ⚠️ 它证明这组断言**不是恒真式** —— 计数确实跟着匹配条件走。
    """
    import smartbi.scripts.restaurant_panorama_probe as probe

    saved = probe._FLAP_MARKS
    try:
        probe._FLAP_MARKS = ()
        c = FlapCollector()
        c.emit(_rec("[llm_router] aistore/DeepSeek-V4-Flash-A timeout (cb_fails=0/2)"))
        assert c.bucket == [], "匹配条件清空了却仍然收到东西"
    finally:
        probe._FLAP_MARKS = saved

    # 恢复之后必须又能收到 —— ⛔ 否则上面那条「读出 0」可能只是收集器坏了
    c2 = FlapCollector()
    c2.emit(_rec("[llm_router] aistore/DeepSeek-V4-Flash-A timeout (cb_fails=0/2)"))
    assert c2.bucket, "恢复后收不到 —— 这组断言在守空气"


def test_the_collector_never_breaks_the_run():
    """收集器自己绝不能把跑批弄挂 —— 它是仪器，不是被测对象。"""
    c = FlapCollector()

    class _Bad(logging.LogRecord):
        def getMessage(self):
            raise RuntimeError("boom")

    c.emit(_Bad("x", logging.WARNING, __file__, 1, "m", None, None))
    assert c.bucket == []


# ── 接线 + 「只报不判」 ────────────────────────────────────────────────────

def test_the_probe_actually_installs_the_collector():
    """接线：`main` 必须真的把它挂上 root logger，并在结束时摘掉。

    ⛔ 用 AST 数方法名，不 grep（注释里就写着 addHandler）。
    """
    import smartbi.scripts.restaurant_panorama_probe as probe

    tree = ast.parse(inspect.getsource(probe.main))
    called = {
        node.func.attr
        for node in ast.walk(tree)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
    }
    assert "addHandler" in called, "没挂上收集器 —— 每条 flap 都会是 0"
    assert "removeHandler" in called, (
        "跑完没摘掉 —— handler 会留在 root logger 上污染同进程的后续调用"
    )


def test_flap_does_not_feed_the_exit_code():
    """🔴 ⛔ 它只**报**，不改判定。

    用它过滤读数（「有故障就不计入」）会把「没量到」折叠进「没问题」，
    而三态的价值全在第三态。
    ⇒ 钉住：`main` 里 `return 2` / `return 1` 的判定条件里不出现 flap 相关的名字。
    """
    import smartbi.scripts.restaurant_panorama_probe as probe

    src = inspect.getsource(probe.main)
    tree = ast.parse(src)
    for node in ast.walk(tree):
        if not isinstance(node, ast.If):
            continue
        returns = [n for n in ast.walk(node)
                   if isinstance(n, ast.Return)
                   and isinstance(n.value, ast.Constant)
                   and n.value.value in (1, 2)]
        if not returns:
            continue
        names = {n.id for n in ast.walk(node.test) if isinstance(n, ast.Name)}
        names |= {n.attr for n in ast.walk(node.test) if isinstance(n, ast.Attribute)}
        bad = {x for x in names if "flap" in x.lower()}
        assert not bad, (
            f"退出码判定里用上了抖动计数 {bad} —— 它只报不判"
        )


# ── 两个口径 ──────────────────────────────────────────────────────────────

def test_it_reports_both_answered_metrics():
    """🔴 `A-有答案` 和 `kind==answer` 是**两个口径**，都要报。

    📏 2026-08-18 实测：`A-有答案 40` / `B-诚实缺数据 1` / `D 7`，
    而 `kind==answer` = **41**。差的那一条是一份 **948 字、`contract=True`**
    的经营诊断答案 —— `verdict()` 的第一条
    （`"还没有数据" in text ⇒ B`）把它降级出了 A。

    ▎而「答得上」的定义是**产品给出答案，而不是「我没敢算」**。

    ⛔ **不改 `verdict()`** —— 改分类器等于改仪器，前后读数立刻不可比。
       ⇒ 只把第二个口径也打出来，并列出差在哪几条。
    """
    import ast
    import inspect

    from smartbi.scripts import restaurant_panorama_probe as probe

    src = inspect.getsource(probe.main)
    tree = ast.parse(src)
    literals = {
        n.value for n in ast.walk(tree)
        if isinstance(n, ast.Constant) and isinstance(n.value, str)
    }
    assert any("kind==answer" in s for s in literals), (
        "没有报第二个口径 —— 只报 A 会漏掉「给了答案但被归到 B」的那些"
    )
    # 阳性对照：原来那个口径还在（⛔ 不许用新口径把旧的挤掉）
    assert any("全景小计" in s for s in literals), sorted(literals)[:6]


def test_the_classifier_itself_is_untouched():
    """⛔ 钉住 `verdict()` 没被改动 —— 改它 = 改仪器 = 前后不可比。

    ⚠️ 它的第一条正是把那份 948 字答案降级的那一条；
       想让数字好看最简单的办法就是删掉它。这条断言拦的就是那个。
    """
    import inspect

    from smartbi.scripts import restaurant_panorama_probe as probe

    src = inspect.getsource(probe.verdict)
    assert '"还没有数据" in (text or "")' in src, (
        "verdict() 的降级判据被动过了 —— 前后读数不再可比"
    )
    assert 'return "B-诚实缺数据"' in src
    assert 'return "A-有答案"' in src
