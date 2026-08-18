"""探针缺 LLM key 时，读数长得**和产品缺陷一模一样** —— 这一组钉住它被认出来。

## 缺陷（2026-08-18 实测，我自己踩的）

跑 prod 探针时忘了从活服务进程 export `LLM_*`。于是 `parse_restaurant_query`
走 fail-closed 分支，产品**完全正确地**回一句 40 字：

    「我现在暂时无法完整理解这句话，本次没有按关键词猜测，也没有执行查询。请稍后重试。」

▎它和「这个问句把产品问倒了」**长得一模一样**。
我拿它当成了 PR #2812 的渲染回归，去查一个不存在的缺陷。
救回来的是阳性对照（第一问也是同一句 ⇒ 整条链根本没到执行）。

这与 `_probe_bootstrap` 已经处理的「餐饮执行链暂时不可用」是**同一个形状**：
探针自己的环境缺件 → 用户可见症状 = 真缺陷。所以它属于同一个模块。

## ⛔ 判据用结构，不用文案

那句拒答在仓里已经有**三份**（产品 `restaurant_intent`、
`restaurant_ai_eval._CANNOT_UNDERSTAND`、`_is_infrastructure_failure`）。
`restaurant_ai_eval` 自己的注释记着同型前科：4 条排除项因为文案改过而恒真，
「**一次都不可能红**」。所以这里锚 `planner_authority`，
并且**由下面那条闸钉住这个值在产品里真的存在**。
"""
from __future__ import annotations

import ast
import inspect
import os

import pytest

from smartbi.scripts._probe_bootstrap import (
    LLM_UNAVAILABLE_AUTHORITY,
    _format_llm_health,
    assert_not_an_llm_artifact,
    llm_key_health,
)


@pytest.fixture(autouse=True)
def _restore_tenant_contextvar():
    """🔴 `bootstrap_probe` 会 `set_factory_id()` —— 那是个**全局 ContextVar**。

    本文件是第一个在单测里调 `bootstrap_probe` 的，于是它把 `MOCK_REST`
    泄漏给了后面的 `test_tenant_ctx_plumbing.py::test_contextvar_propagates_across_awaits`
    （那条断言 `get_factory_id() is None`）。CI 红，而**本地绿** —— 只是执行顺序不同。

    ⚠️ 「本地绿」在这里不是证据：跨测试的全局状态污染，是否暴露取决于收集顺序。

    ⛔ **不能用 `set_factory_id(before)` 恢复** —— 第一版就是那么写的，照样红。
       `set_factory_id(None)` 不是「恢复成未设置」，它设成 `INTERNAL_SENTINEL`
       （`"__internal__"`）。⇒ 又一次「兜底的默认值把『没设』翻译成了别的东西」
       （形态 A¹⁰）。要恢复只能用 ContextVar 自己的 Token。
    """
    from smartbi import tenant_ctx

    token = tenant_ctx.current_factory_id.set(tenant_ctx.current_factory_id.get())
    try:
        yield
    finally:
        tenant_ctx.current_factory_id.reset(token)


class _Spec:
    """只带判据要读的那个字段 —— ⛔ 不桩整个 spec。"""

    def __init__(self, authority):
        self.planner_authority = authority


# ── 承重：这个判据在产品里真的能触发 ────────────────────────────────────────

def test_the_marker_is_a_value_the_product_actually_sets():
    """🔴 最重要的一条：`llm_unavailable` 必须是产品**真的会写**的值。

    否则这整组用例就是 `_CANNOT_UNDERSTAND` 那 4 条排除项的重演 ——
    写在断言位置上的一句注释，一次都不可能红。

    ⛔ 用 AST 数 `planner_authority=<常量>` 的关键字实参，
       ⛔ 不用 grep（形态 C⁸：注释和 docstring 里提到它也会被数进去）。
    """
    from smartbi.gold.restaurant import restaurant_intent

    tree = ast.parse(inspect.getsource(restaurant_intent))
    written = {
        kw.value.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        for kw in node.keywords
        if kw.arg == "planner_authority"
        and isinstance(kw.value, ast.Constant)
        and isinstance(kw.value.value, str)
    }

    # 阳性对照：扫描本身得能扫出东西，且不止一个值 —— 否则「找到了」没有意义
    assert len(written) >= 2, (
        f"只扫到 {written} —— AST 扫描多半没在工作，这条断言没有意义"
    )
    assert LLM_UNAVAILABLE_AUTHORITY in written, (
        f"产品不再写 {LLM_UNAVAILABLE_AUTHORITY!r}（现有: {sorted(written)}）"
        f" —— 这个判据已经静默失效，探针从此认不出「自己没 key」"
    )


# ── 承重：存在 ≠ 有值 ───────────────────────────────────────────────────────

def test_a_key_that_exists_but_is_empty_counts_as_dead(monkeypatch):
    """🔴 `LLM_DEEPSEEK_API_KEY` 存在但为空，在生产上活了三周没人发现。

    ⚠️ 2026-08-18 我量它时用的是 `sed 's/=.*/=<set>/'` —— **对空值也打 `<set>`**。
       我的仪器犯了 `llm_router._note_empty_key` 那条日志正在警告的错。
    """
    from common import llm_router

    monkeypatch.setattr(
        llm_router, "_provider_config",
        lambda account: ("https://x", "   " if account.startswith("a") else "k-real"))

    health = llm_key_health()
    assert health, "SLOT_MODELS 是空的 —— 读数作废"
    for slot, (live, empty) in health.items():
        assert all(not a.startswith("a") for a in live), (
            f"{slot}: 只有空白的 key 被算成了「有 key」: {live}"
        )


def test_all_keys_present_means_no_dead_slot(monkeypatch):
    """阴性对照：key 都有值时 ⛔ 不许报任何一个槽是死的。

    ▎反目标里最重的一条：一条误发的提示，烧掉的是「这东西说的话能信」。
    """
    from common import llm_router

    monkeypatch.setattr(llm_router, "_provider_config",
                        lambda account: ("https://x", "k-real"))

    _summary, dead = _format_llm_health(llm_key_health())
    assert dead == (), f"每个账号都有 key，却报了死槽: {dead}"


def test_every_configured_slot_is_named_when_all_keys_are_empty(monkeypatch):
    """承重：全空时，**配了账号的**槽要逐个点名 —— ⛔ 不能只说「仪器没活着」。

    ⚠️ 这条断言最初写的是 `set(dead) == set(health)`，那是在
       「两态」假设下写的；`vl` 槽没配账号，它**不该**出现在死槽里。
       ⇒ 守的东西从「集合相等」抬到「配了账号的一个都不漏」（形态 C‴）。
    """
    from common import llm_router

    monkeypatch.setattr(llm_router, "_provider_config",
                        lambda account: ("https://x", ""))

    health = llm_key_health()
    summary, dead = _format_llm_health(health)
    configured = {s for s, (live, empty) in health.items() if live or empty}
    assert configured, "一个配了账号的槽都没有 —— 读数作废（阳性对照）"
    assert set(dead) == configured, f"漏了槽: {configured - set(dead)}"
    assert "0/" in summary, summary


def test_a_slot_with_no_accounts_is_not_a_dead_slot():
    """🔴 「没配账号」和「配了但一个 key 都没有」是**两态**，⛔ 不许折叠。

    本函数第一版就是两态，被这条用例当场抓出来：实测 `vl` 槽是 `0/0`
    —— 它**根本没配账号**，把它算成死槽就是一条**永远为真**的告警。

    ▎形态 E：一条天天误报的提示最终会被默认忽略，那时它真正该拦的那次也拦不住。
    ▎形态 A¹¹：算「缺了多少」之前，先问这里的空是不是合法状态。
    """
    summary, dead = _format_llm_health({
        "review": (("aistore",), ("deepseek",)),   # 有活的 → 不死
        "chat": ((), ("zhipu", "ark")),            # 配了但全空 → 死
        "vl": ((), ()),                            # 没配账号 → ⛔ 不算死
    })
    assert dead == ("chat",), f"三态被折叠了: {dead}"
    assert "未配账号" in summary, (
        "`0/0` 和 `0/3` 在自检行里长得一样 —— 读的人分不出来\n" + summary
    )


def test_health_survives_a_broken_router(monkeypatch):
    """`_provider_config` 自己抛异常时，⛔ 不许当成「有 key」。

    ⚠️ 形态 A¹⁰：兜底的默认值会把「我不知道」翻译成「没问题」。
    """
    from common import llm_router

    def _boom(account):
        raise RuntimeError("registry stale")

    monkeypatch.setattr(llm_router, "_provider_config", _boom)

    _summary, dead = _format_llm_health(llm_key_health())
    assert dead, "取不到 key 却报「每个槽都活着」—— 那正是把「不知道」读成「没问题」"


# ── 承重：拿探针造出来的拒答去写报告，要被拦住 ──────────────────────────────

def test_it_refuses_to_let_me_report_my_own_missing_key():
    """🔴 这就是 2026-08-18 那次我差一步做的事。"""
    specs = [_Spec("llm"), _Spec(LLM_UNAVAILABLE_AUTHORITY), _Spec("promoted_exact")]
    with pytest.raises(AssertionError) as exc:
        assert_not_an_llm_artifact(specs, llm_dead_slots=("review", "chat"))
    assert "探针问题" in str(exc.value)
    assert "review" in str(exc.value), "没点名是哪个槽 —— 那还得再查一轮"


def test_a_healthy_process_points_at_the_provider_instead():
    """槽都有活账号时，出路完全不同 —— 该去查供应商侧，⛔ 不是去 export key。

    ⚠️ 「一个原因解释两个症状」时要逐个验证机制成立（形态 A⁵）：
       `llm_unavailable` 有两种成因，它们的下一步动作是相反的。
    """
    with pytest.raises(AssertionError) as exc:
        assert_not_an_llm_artifact([_Spec(LLM_UNAVAILABLE_AUTHORITY)],
                                   llm_dead_slots=())
    assert "供应商" in str(exc.value)
    assert "探针问题" not in str(exc.value), "把两种相反的成因说成了同一种"


def test_clean_specs_raise_nothing():
    """阴性对照：没有 `llm_unavailable` 时 ⛔ 一个字都不许说。"""
    assert_not_an_llm_artifact([_Spec("llm"), _Spec("promoted_exact")],
                               llm_dead_slots=("review",))
    assert_not_an_llm_artifact([], llm_dead_slots=("review",))
    assert_not_an_llm_artifact(None, llm_dead_slots=("review",))


def test_plain_strings_work_too():
    """调用方手上只有 authority 字符串时也要能用 —— ⛔ 不逼它造个假 spec。"""
    with pytest.raises(AssertionError):
        assert_not_an_llm_artifact([LLM_UNAVAILABLE_AUTHORITY],
                                   llm_dead_slots=("review",))


# ── 接线：bootstrap 每次都要报，不是只在坏的时候报 ──────────────────────────

def test_bootstrap_always_prints_the_self_check(capsys, monkeypatch):
    """⛔ 这一行**每次都打** —— 与「每条读数带来源标记」同一条纪律。

    2026-08-18 那次，如果日志里有这一行，诊断会短掉十几分钟。
    """
    from smartbi.scripts import _probe_bootstrap

    monkeypatch.setenv("POSTGRES_DB", "smartbi_prod_db")
    _probe_bootstrap.bootstrap_probe("MOCK_REST")
    err = capsys.readouterr().err
    assert "[探针自检] LLM" in err, (
        "bootstrap 没打自检行 —— 那它就退回成「靠记得」了\n" + err
    )


def test_bootstrap_records_dead_slots_on_the_context(monkeypatch):
    """接线：死槽要落在 ctx 上，供 `restaurant_panorama_probe` 的 rc=2 读。

    ⚠️ 变异 C4 那次的教训：只测 helper 不测接线，把调用点拆了也不红。
    """
    from common import llm_router
    from smartbi.scripts import _probe_bootstrap

    monkeypatch.setenv("POSTGRES_DB", "smartbi_prod_db")
    monkeypatch.setattr(llm_router, "_provider_config",
                        lambda account: ("https://x", ""))
    ctx = _probe_bootstrap.bootstrap_probe("MOCK_REST")
    assert ctx.llm_dead_slots, "ctx 上没记死槽 —— 主线探针的 rc=2 读不到它"

    monkeypatch.setattr(llm_router, "_provider_config",
                        lambda account: ("https://x", "k-real"))
    ctx_ok = _probe_bootstrap.bootstrap_probe("MOCK_REST")
    assert ctx_ok.llm_dead_slots == (), "阴性对照：都有 key 却记了死槽"


def test_the_panorama_probe_reads_that_marker():
    """接线：主线探针必须**读** `planner_authority` 并据此判 rc=2。

    ⛔ 用 AST 确认那个名字真的被读到 row 里，而不是 grep 到一句注释
       （形态 C⁸：本仓栽过三次，前两次都是「把正则收窄一点」，第三次又长出来）。
    """
    src = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "smartbi", "scripts", "restaurant_panorama_probe.py")
    with open(src, "r", encoding="utf-8", newline="") as fh:
        tree = ast.parse(fh.read())

    reads_authority = any(
        isinstance(node, ast.Attribute) and node.attr == "planner_authority"
        for node in ast.walk(tree)
    )
    assert reads_authority, "主线探针没读 planner_authority —— rc=2 那段判不了"

    uses_constant = any(
        isinstance(node, ast.Name) and node.id == "LLM_UNAVAILABLE_AUTHORITY"
        for node in ast.walk(tree)
    )
    assert uses_constant, (
        "主线探针没用那个常量 —— 多半是自己写了第二份字面串（形态 D）"
    )
