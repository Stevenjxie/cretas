"""叙事接地闸：合规写法必须能过 —— 否则「按闸的要求措辞」这条路走不通。

## 为什么有这个文件（设计卡登记的盲区 ②）

2026-08-17 prod 实测：老板追问「它的问题出在哪」，产品**把归因和行动方案
完整算出来了**（具体门店名 / 4.504→4.7 星 / 下架 5 个菜 / 验收方式），
被这道闸整份驳回，五条违例**全是措辞纪律**，没有一条是「数字错了」。

设计卡（`docs/decisions/2026-08-17-追问被叙事接地闸毙掉-设计卡.md`）
裁定 **A（让生成侧按闸的要求措辞）+ C（先止血）**，⛔ 不做 D（把闸降级）。

▎**但 A 有一个前提：闸的逃生口真的走得通。**
▎如果它对**合规**文案也开火，那「按要求措辞」永远做不完 —— A 是死路。

⇒ 这个文件就是量那件事：**按逃生口的规格造合规文案，断言 0 违例。**

## 每条都配阴性对照

⛔ 只验「合规的过了」不够 —— 闸整个失效时它也过。
所以每条都配一句**去掉逃生口**的版本，断言它**必须**被抓住。
少了这一半，这些断言在「闸被注释掉」时全部照绿。
"""
import pytest

from smartbi.agent.synthesis_engine import (
    FactBook,
    _narrative_grounding_violations,
)


@pytest.fixture
def empty_factbook():
    """没有缺失维度、没有确定性归因的空 FactBook。

    ⚠️ 故意留空：这样「因果断言」只能靠**对冲措辞**过关，
    ⛔ 不会被 `grounded_attribution` 那条豁免顺带放行 ——
    否则这条用例就成了恒真式（它验的其实是另一条路）。
    """
    return FactBook()


def _violations(text, fb, question=""):
    return _narrative_grounding_violations(text, fb, question)


# ── 规则一 · 因果断言 ────────────────────────────────────────────────
def test_hedged_causal_claim_passes(empty_factbook):
    """带对冲的因果说法必须能过（`_CAUSAL_HEDGE_RE`）。"""
    ok = "客单价下降**可能**是主因，相关不等于因果，这一条待验证。"
    assert _violations(ok, empty_factbook) == [], _violations(ok, empty_factbook)


def test_unhedged_causal_claim_is_caught(empty_factbook):
    """阴性对照：去掉对冲，必须被抓住 —— 否则上面那条在守空气。"""
    bad = "客单价下降是主因，它撑起了整个营业额。"
    got = _violations(bad, empty_factbook)
    assert any("无保留因果断言" in v for v in got), got


# ── 规则三 · 预算/目标 ──────────────────────────────────────────────
def test_budget_labelled_as_assumption_passes(empty_factbook):
    """标注为假设/待确认的预算必须能过（`_PRESCRIBED_NUMBER_ASSUMPTION_RE`）。"""
    ok = "建议试点预算 5000 元（建议值，需老板确认后再执行）。"
    assert _violations(ok, empty_factbook) == [], _violations(ok, empty_factbook)


def test_bare_budget_number_is_caught(empty_factbook):
    """阴性对照：不标注就必须被抓住。"""
    bad = "把营销预算提升到 5000 元。"
    got = _violations(bad, empty_factbook)
    assert any("未标注为假设的预算或目标" in v for v in got), got


# ── 规则四 · 高影响动作 ─────────────────────────────────────────────
def test_high_impact_action_with_safety_frame_passes(empty_factbook):
    """带试点/确认/可回滚框架的动作必须能过（`_HIGH_IMPACT_ACTION_SAFETY_RE`）。"""
    ok = "建议先小范围试点下架这 5 个菜，确认后再全店执行，可回滚。"
    assert _violations(ok, empty_factbook) == [], _violations(ok, empty_factbook)


def test_bare_high_impact_action_is_caught(empty_factbook):
    """阴性对照：光秃秃的动作必须被抓住。"""
    bad = "下架这 5 个菜。"
    got = _violations(bad, empty_factbook)
    assert any("未经验证或确认的高影响动作" in v for v in got), got


# ── 三条合在一段里（真实答案的长相）─────────────────────────────────
def test_a_full_compliant_paragraph_passes(empty_factbook):
    """🔴 承重：把三种说法写进**同一段**，必须 0 违例。

    这才是 A 要产出的东西 —— 单句合规不等于整段合规
    （对冲是**句级**的、预算假设也是句级的，跨句写就会漏）。
    """
    ok = (
        "这家店毛利偏低，**可能**主要来自客单价下降，相关不等于因果，待验证。"
        "如果要试，建议试点预算 5000 元（建议值，需老板确认）。"
        "动作上建议先小范围试点下架毛利最低的 5 个菜，确认后再推开，可回滚。"
    )
    got = _violations(ok, empty_factbook)
    assert got == [], f"合规段落被拦了 {len(got)} 条：{got}"


def test_the_same_paragraph_without_hedges_is_caught(empty_factbook):
    """阴性对照：同一段去掉全部逃生口，必须**多条**违例。

    ⛔ 少了它，上面那条在「闸整个失效」时也是绿的。
    """
    bad = (
        "这家店毛利偏低，主要来自客单价下降。"
        "把营销预算提升到 5000 元。"
        "下架毛利最低的 5 个菜。"
    )
    got = _violations(bad, empty_factbook)
    assert len(got) >= 3, f"只抓到 {len(got)} 条，期望 ≥3：{got}"


# ── 规则三 × 规则四 的冲突（2026-08-17 实测抓到的误报）──────────────
def test_safety_framed_action_is_not_read_as_an_unlabelled_budget(empty_factbook):
    """🔴 承重：规则四要求的安全框架，⛔ 不许被规则三判成「未标注的预算」。

    实测原形：`_PRESCRIBED_NUMBER_RE` 的触发词里有「试点」，32 字内有数字就命中，
    于是「先小范围试点下架毛利最低的 **5** 个菜」被判「未标注为假设的预算或目标」——
    而「5 个菜」是**观察到的条数**，不是预算也不是 KPI。

    ▎**满足一条规则的写法触发了另一条** ⇒ 「按闸的要求措辞」这条路会走不通，
    ▎而那正是设计卡裁定的 A（治本）所依赖的前提。
    """
    ok = "建议先小范围试点下架毛利最低的 5 个菜，确认后再推开，可回滚。"
    got = _violations(ok, empty_factbook)
    assert not any("未标注为假设的预算或目标" in v for v in got), got


def test_a_real_budget_without_any_frame_is_still_caught(empty_factbook):
    """🔴 阴性对照：**没有**安全框架的裸预算必须照样被抓住。

    ⛔ 少了它，上面那条豁免会把规则三整个掏空 ——
    「加个逃生口」最容易滑成「把闸关掉」。
    """
    bad = "把营销预算提升到 5000 元，日均订单提高到 110 单。"
    got = _violations(bad, empty_factbook)
    assert any("未标注为假设的预算或目标" in v for v in got), got


def test_the_exemption_needs_the_safety_words_not_just_any_action(empty_factbook):
    """🔴 阴性对照二：只提动作、不带安全词，⛔ 不给豁免。

    「下架 5 个菜」没有「试点/确认后/可回滚」⇒ 规则四要抓它，
    而规则三的豁免也不该生效。
    """
    bad = "下架毛利最低的 5 个菜。"
    got = _violations(bad, empty_factbook)
    assert any("未经验证或确认的高影响动作" in v for v in got), got


# ── 跨问句普查（盲区①）抓到的第二个同形缺口 ────────────────────────
def test_bare_pilot_word_counts_as_a_safety_frame(empty_factbook):
    """🔴「试点」本身就是安全框架，⛔ 不该只认「先试点／小范围试点」。

    跨问句普查（8 条归因/建议型问句、23 条违例）里的实际长相：

        建议在鲜行者打浦桥日月光店、B、C 这三家高营业额店**试点**雨天外卖满减活动

    被判「未经验证或确认的高影响动作」—— 因为
    `_HIGH_IMPACT_ACTION_SAFETY_RE` 要的是「**先**试点」或「**小范围**试点」，
    光写「试点」不认。

    ▎「试点」的语义**就是**限定范围地试 —— 那正是这条规则要的东西。
    ▎与上一条修复同形：**自然的合规写法不被识别**，于是 A 走不通。
    """
    ok = "建议在三家高营业额店试点雨天外卖满减活动。"
    got = _violations(ok, empty_factbook)
    assert not any("未经验证或确认的高影响动作" in v for v in got), got


def test_an_action_with_no_pilot_or_confirmation_is_still_caught(empty_factbook):
    """🔴 阴性对照：既没试点也没确认的动作，必须照样被抓住。

    ⛔ 少了它，「把试点加进安全词」会滑成「把规则四关掉」。
    """
    bad = "在三家高营业额店上线雨天外卖满减活动。"
    got = _violations(bad, empty_factbook)
    assert any("未经验证或确认的高影响动作" in v for v in got), got
