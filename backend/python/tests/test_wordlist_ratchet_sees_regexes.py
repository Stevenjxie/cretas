"""词表棘轮的盲区：**正则**。闸建起来了，却挡不住触发它的形状。

## 缺陷（2026-08-18 实测，`docs/decisions/2026-08-18-词表棘轮盲区-正则-登记.md`）

有人在修「有几家店是亏钱的」这一类问句时，写了一个正则去判断这句话是什么意思，
并据此改系统行为。**那道棘轮一个字都没说** —— 它的判据 `_cjk_seq` 只认
「中文字符串的**序列字面量**」，而正则是**一个字符串**。

```
① _FOO = ("有几家店", "门店数量", "几个门店")      看得见 ✅
② _FOO = re.compile(r"有几家店|门店数量|几个门店")  看不见 🔴
③ if any(t in q for t in ("有几家店", "门店数量")) 看得见 ✅
```

▎那次靠的是自觉 —— **而仓里明写过「决心不构成约束力」**。

## 判据扩成什么

> 凡是【拿用户问句去匹配】的新增点，无论写成词表、正则、还是内联 if，
> 都要在 `REGISTRY` 里登记它属于哪一类。

⛔ **不能见正则就报**。判产品自己输出的、SQL/日志解析的、单位日期归一的都不算
—— 它们通过**登记成 LEXICAL** 出列，⛔ 不通过脚本自动猜（第一版分类器就是
想自动判「跟谁比」而漏判三个）。

## 🔴 载体名：为什么必须是常量名或函数名

第一版实测点出 18 个**局部变量名**当登记键（`body` / `match` / `raw` / `values`…）。
那种键**登记不了** —— `body` 在多个文件里都有，登记一个等于豁免全部同名的。
▎登记键必须能被唯一指认，否则「登记」本身就是个漏洞。
"""
from __future__ import annotations

import ast

import pytest

from smartbi.scripts.audit_restaurant_wordlists import (
    LEXICAL,
    MAX_UNREGISTERED,
    REGISTRY,
    _CarrierScan,
    _cjk_membership,
    _regex_cjk_terms,
    census,
)


def _carriers(src: str) -> dict:
    scan = _CarrierScan("t.py")
    scan.visit(ast.parse(src))
    return scan.found


# ── 🔴 承重：那三种形状都要看得见 ─────────────────────────────────────────

def test_the_named_regex_is_seen():
    """② 具名正则 —— owner 卡里的原始样本。"""
    got = _carriers('import re\n_F = re.compile(r"有几家店|门店数量|几个门店")\n')
    assert "_F" in got, f"具名正则没被看见: {got}"


def test_the_inline_dynamic_regex_is_seen():
    """🔴 **这条最重要** —— 它是那次撤掉的实例的真实长相。

    那个正则不是具名常量，是**内联在函数里、模式还是动态拼的**：

        re.search(r"(?:%s)[^。？?]{0,4}(?:门店|店)" % "|".join(...), text)

    只认具名常量的话，闸照样挡不住它。⇒ 载体名取**所在函数名**。
    """
    got = _carriers(
        'import re\n'
        'def asks_about_store_distribution(q):\n'
        '    return bool(re.search(r"(?:%s)[^。？?]{0,4}(?:门店|店)"\n'
        '                          % "|".join(("哪", "几")), q))\n'
    )
    assert "asks_about_store_distribution" in got, (
        f"内联动态拼接的正则没被看见 —— 盲区还在: {got}"
    )


def test_the_single_string_membership_is_seen():
    """`"有几家店" in query` / `query.startswith("哪家")`。"""
    got = _carriers('def f(query):\n    return "有几家店" in query\n')
    assert "f" in got, f"单串比较没被看见: {got}"
    got2 = _carriers('def g(query):\n    return query.startswith("哪家")\n')
    assert "g" in got2, f"startswith 没被看见: {got2}"


# ── 🔴 阴性对照：⛔ 不能见正则就报 ───────────────────────────────────────

def test_an_ascii_only_regex_is_not_flagged():
    """SQL / 日志 / 文件名解析 —— 与用户语义无关。"""
    assert _carriers('import re\n_S = re.compile(r"^SELECT\\s+(\\w+)")\n') == {}


def test_a_single_cjk_run_is_not_flagged():
    """单个中文片段多半是词法（`r"最近(\\d+)天"`）。

    ⚠️ 这是**门槛**不是语义判断：它回答「多小不算词表」，
       ⛔ 不回答「它在判什么」——后者走显式 REGISTRY。
    """
    assert _carriers('import re\n_T = re.compile(r"最近(\\d+)")\n') == {}


def test_looking_something_up_in_a_table_is_not_flagged():
    """`x in (…)` 是**查表** —— 那张表自己会被序列字面量那条判据数到。

    ⛔ 重复点名会让同一件事被数两次，棘轮的数就不再是「有多少个地方在猜」。
    """
    assert _carriers('def f(t):\n    return t in ("阈值", "覆盖", "口径")\n') == {}


def test_the_criterion_did_not_become_a_blanket():
    """🔴 判据整体没变成恒真式 —— 一段与用户输入无关的代码不该被点名。"""
    got = _carriers(
        'import re\n'
        'def render(rows):\n'
        '    total = sum(r["amount"] for r in rows)\n'
        '    return f"合计 {total}"\n'
    )
    assert got == {}, f"一段纯产出代码被点名了: {got}"


# ── 🔴 载体名不许是局部变量（第一版实测的 18 个坏读数）────────────────────

def test_a_local_variable_never_becomes_the_registry_key():
    """`body = re.sub(r"…中文…", "", t)` 的载体是**函数**，不是 `body`。

    ⚠️ `body` / `match` / `raw` / `values` 这种名字在多个文件里都有，
       拿它当登记键 = 登记一个豁免全部同名的。
    """
    got = _carriers(
        'import re\n'
        'def strip_lead(t):\n'
        '    body = re.sub(r"^请问|^帮我看看", "", t)\n'
        '    return body\n'
    )
    assert "strip_lead" in got, f"没归给函数: {got}"
    assert "body" not in got, f"局部变量名成了登记键: {got}"


def test_module_level_constants_keep_their_own_name():
    """阳性对照：模块级的具名常量 ⛔ 不该被顶替成别的名字。"""
    got = _carriers('import re\n_TOP = re.compile(r"排行|排名|榜单")\n')
    assert "_TOP" in got and len(got) == 1, got


# ── 冻结值与登记边界 ──────────────────────────────────────────────────────

@pytest.fixture(scope="module")
def counted():
    return census()


def test_the_frozen_limit_matches_the_measurement(counted):
    """⛔ 上限不许高于实测值 —— 留出的余量就是悄悄新增的空间。

    ⚠️ 既有的 `test_the_limit_is_not_slack` 用容差 3 守同一件事；
       这条钉的是**扩到正则之后**那个数确实被重新量过。
    """
    named, _inline = counted
    unregistered = [n for n in named if n not in REGISTRY]
    assert len(unregistered) <= MAX_UNREGISTERED
    assert MAX_UNREGISTERED - len(unregistered) <= 3, (
        f"上限 {MAX_UNREGISTERED} 比实际 {len(unregistered)} 宽出太多"
    )


def test_the_carve_outs_are_registered_not_hardcoded(counted):
    """owner 划的两类边界通过**登记**出列，⛔ 不是在脚本里写死跳过。

    ⚠️ 写死跳过的话，下一个人把一张真的判用户的表塞进那个文件就自动豁免了。
    """
    for name in ("_comparison_present", "_request_coverage_present",
                 "_DATETIME_RE", "_GENERIC_ACTIONS", "DISCOUNT_CLOSING",
                 "_ABS_DATE_RANGE_RE", "_EXPLICIT_TIME_RE"):
        assert REGISTRY.get(name) == LEXICAL, (
            f"{name} 应登记为 LEXICAL（判产品输出 / 日期词法），"
            f"实际 {REGISTRY.get(name)}"
        )


def test_the_two_deliberate_non_carve_outs_stay_in_the_target(counted):
    """🔴 两个**名字像该出列、实际不该**的，必须仍在靶子里。

    📏 逐条读过定义:
        `_FALLBACK_OUTPUT_CLAUSE_RE` 匹配的是**用户问句里**的
            「…, 如果无法绘图就…」从句 ⇒ 判用户
        `_DATE_BACKREF_RE` 是**追问指代**（「那上个月呢」）⇒ 与 ANAPHORA 同类

    ⚠️ 「登记是留痕不是豁免」的反面：**不登记也要留痕**，
       否则下一个人会以为是漏了，顺手把它们登记掉。
    """
    for name in ("_FALLBACK_OUTPUT_CLAUSE_RE", "_DATE_BACKREF_RE"):
        assert name not in REGISTRY, (
            f"{name} 被登记出列了 —— 它判的是用户输入，该留在靶子里"
        )


def test_the_census_still_sees_the_old_shape(counted):
    """🔴 阳性对照：扩面 ⛔ 不许把原来看得见的弄丢。"""
    named, inline = counted
    for known in ("_DISH_GENERIC_TOKENS", "_CALENDAR_PERIOD_TOKENS",
                  "_READ_ONLY_MUTATION_TOKENS"):
        assert known in named, f"扩面之后反而看不见 {known} 了"
    assert len(inline) > 100, f"内联序列字面量只剩 {len(inline)} 个 —— 仪器坏了"


def test_the_regex_shape_is_actually_present_in_the_tree(counted):
    """🔴 阳性对照：真代码树里**确实**有正则形态被数到。

    ⛔ 少了这条，一个永远返回空的 `_CarrierScan` 会让上面全绿，
       而它一条正则都没看过（本仓记过「跑批天天绿但一个样本没看」）。
    """
    named, _inline = counted
    for regex_carrier in ("_DISH_QUERY_RE", "_STORE_MENTION_RE",
                          "extract_store_mentions"):
        assert regex_carrier in named, (
            f"真代码树里的 {regex_carrier} 没被数到 —— 扩面没生效"
        )


# ── 判据本身的单元 ────────────────────────────────────────────────────────

def test_regex_terms_counts_runs_not_characters():
    """量的是**片段数**，⛔ 不是字符数。"""
    node = ast.parse('__import__("re").compile(r"有几家店|门店数量")').body[0].value
    assert _regex_cjk_terms(node) == ["有几家店", "门店数量"]


def test_membership_only_when_the_cjk_side_is_the_needle():
    """`"哪家" in q` 算；`q in _TOKENS` ⛔ 不算（那是查表）。"""
    yes = ast.parse('"哪家" in q').body[0].value
    no = ast.parse('q in _TOKENS').body[0].value
    assert _cjk_membership(yes) == ["哪家"]
    assert _cjk_membership(no) is None
