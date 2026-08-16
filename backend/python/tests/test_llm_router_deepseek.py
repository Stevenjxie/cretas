"""DeepSeek 官方接入的闸 (T7)。⛔ NOT_DEPLOYED —— 合并需 Steve 对两个白名单的 yes。

## 为什么这条线存在

`aistore` 三条 **2026-09-13 硬到期**, 而 `aistore/DeepSeek-V4-Flash-A` 是
CHAT / INSIGHTS / CHART / MAPPER / REVIEW **五个槽的链首**。

而 fail-safe 集合 `_MINIMAL_SAFE_SET` 自己也不健康 (2026-08-15 实测, 见
`smartbi/scripts/minimal_safe_set_liveness.py`): 8 条里 2 条死于配额、3 条中位
超单跳预算; 扣掉 9-13 过期的 aistore 三条后, 既活着又在 6.0s 内的只剩 zhipu
一条, 而它被 08-09 事故钉在 `_TEXT_TAIL` 末位。

## ⛔ 两个日期, 语义不同(最容易读错的地方)

    2026-09-04  `_REGISTRY_AUDIT_DATE + _REGISTRY_MAX_AGE_DAYS` ⇒ registry 超龄,
                收缩到 `_MINIMAL_SAFE_SET`
    2026-09-13  aistore 三条在该集合内因 expired 被硬丢

⇒ 只加 `_SAFE_MODELS` 的候选在 **09-04** 就死了, 比它要接的悬崖**早 9 天**。
  下面 `test_deepseek_survives_the_registry_staleness_cliff` 钉的就是这一条。
"""
from __future__ import annotations

import datetime

import pytest

from common import llm_router
from common.llm_router import SLOT

REVIEW_DATE = datetime.date(2026, 11, 15)
DEEPSEEK_SKUS = ("deepseek-v4-flash", "deepseek-v4-pro")
FIVE_SLOTS = (SLOT.CHAT, SLOT.INSIGHTS, SLOT.CHART, SLOT.MAPPER, SLOT.REVIEW)


# ── T7-a 账号 ────────────────────────────────────────────────────────────
def test_deepseek_key_never_falls_back_to_an_unrelated_secret(monkeypatch):
    """⛔ 与 aistore 同一条纪律: 把不相干的凭证发给这个 provider 是计费事故。

    照 `test_aistore_key_never_falls_back_to_an_unrelated_secret` 的形状。
    """
    monkeypatch.delenv("LLM_DEEPSEEK_API_KEY", raising=False)
    monkeypatch.setenv("LLM_API_KEY", "must-not-be-reused")
    base_url, api_key = llm_router._provider_config("deepseek")
    assert base_url == "https://api.deepseek.com/v1"
    assert api_key == ""


def test_deepseek_base_url_is_overridable_but_defaults_to_official(monkeypatch):
    monkeypatch.setenv("LLM_DEEPSEEK_BASE_URL", "https://example.invalid/v1")
    assert llm_router._provider_config("deepseek")[0] == "https://example.invalid/v1"


# ── T7-b 复审日 ─────────────────────────────────────────────────────────
@pytest.mark.parametrize("model", DEEPSEEK_SKUS)
def test_deepseek_is_allowlisted_with_a_review_date(model):
    assert llm_router._SAFE_MODELS[("deepseek", model)] == REVIEW_DATE


def test_the_review_date_is_documented_as_a_review_point_not_a_real_expiry():
    """⚠️ 与 aistore 那三条 `_d(2026,9,13)` 语义不同 —— 那是真额度到期,
    这是我们自己设的复审闸。不写清, 下一个人会以为 DeepSeek 也会那天断。
    """
    import inspect
    src = inspect.getsource(llm_router)
    idx = src.find('("deepseek", "deepseek-v4-flash"): _d(2026, 11, 15)')
    assert idx > 0, "找不到复审日条目 —— 先查闸本身"
    context = src[max(0, idx - 1200):idx]
    assert "复审" in context, "复审日的语义没有写进注释"


# ── T7-b2 registry 超龄悬崖 ─────────────────────────────────────────────
@pytest.mark.parametrize("model", DEEPSEEK_SKUS)
def test_deepseek_survives_the_registry_staleness_cliff(model):
    """🔴 只加 `_SAFE_MODELS` 是不够的 —— 09-04 起会被判 registry_stale。

    ⛔ 这条不能用「monkeypatch 到期日」来测: 那只触发 expired 一道闸,
       `_registry_stale` 仍按真实今天算 ⇒ 会一路绿到 9-13。**推 today 才对。**
    """
    stale_day = datetime.date(2026, 9, 5)      # 只 stale, aistore 还没过期
    cliff_day = datetime.date(2026, 9, 14)     # stale + aistore expired
    assert llm_router._registry_stale(stale_day) is True
    for day in (stale_day, cliff_day):
        assert llm_router._refuse_reason("deepseek", model, day) is None, (
            f"{model} 在 {day} 被拒 —— 它在最需要它的那天是死的")


def test_the_staleness_cliff_is_real_for_a_model_outside_the_minimal_set(monkeypatch):
    """阳性对照: 证明 registry_stale 这道闸**真的会咬人**。

    ⛔ 没有这条, 上面那条「deepseek 没被拒」可能只是因为闸根本不开火。

    🔴 2026-08-15 (A2) 订正: 原来是**去两张表里找**一个「在 _SAFE_MODELS 但不在
       _MINIMAL_SAFE_SET」的条目当对照。A2 把两张表收敛成同样的 6 条之后,
       **这样的条目不存在了**, 对照当场失去对象。
       ⇒ 改成**构造**一个: 对照的主体应该由测试自己决定, ⛔ 不该依赖生产表
         碰巧有一个多余条目 —— 那是「阳性对照只在某个样本上成立」的老毛病。

    ⚠️ 顺带记一个事实: A2 之后 `_SAFE_MODELS == _MINIMAL_SAFE_SET`(都是 6 条),
       所以 `registry_stale` 对**当前**这三条链是 no-op。它仍然必须留着 ——
       将来任何一条新条目只要漏登记 _MINIMAL_SAFE_SET, 就会在 2026-09-04 之后
       被它拒掉(那正是 deepseek 差点踩的那个坑)。
    """
    ghost = ("aistore", "__not-in-minimal-set__")
    monkeypatch.setitem(llm_router._SAFE_MODELS, ghost, datetime.date(2099, 1, 1))
    assert ghost not in llm_router._MINIMAL_SAFE_SET
    stale = datetime.date(2026, 9, 5)
    assert llm_router._registry_stale(stale) is True
    assert llm_router._refuse_reason(*ghost, stale) == "registry_stale"
    # 阴性: 同一条目在 registry 未超龄时不该被这道闸拒
    assert llm_router._refuse_reason(*ghost, datetime.date(2026, 8, 15)) is None


# ── T7-c thinking 注入 ──────────────────────────────────────────────────
def test_every_deepseek_model_on_a_chain_gets_the_thinking_switch():
    """⛔ 判据来自 SLOT_MODELS(真正会上线的链), **不是**手写型号名单。

    写成 `for model in ("deepseek-v4-flash", ...)` 正是 organizer 这轮在
    test_llm_router_aistore.py 上修掉的反模式: 型号改一个字母或新 SKU 漏登记时,
    `model in frozenset` 静默为 False, 而只认字面量的闸照绿。

    🔴 2026-08-15 订正两次, 记全过程 —— 因为中间那一版看起来完全正确:

      ① 原版: 断言「**任何**链上的 deepseek 都拿到开关」。
         A1 把 deepseek 加进 `SLOT.REASONING`(当时 profile 是 `{}`)后它红了。
      ② 第一次订正: 抬成「拿不拿开关**由槽的 profile 决定**」, 并加 `assert on`
         守「reasoning 不许被误关思考」。**这一版是对的**, 对当时的需求而言。
      ③ 第二次订正(本版): 裁定把 REASONING 改成显式 `enable_thinking: False`
         —— `{}` 从来不是「必须开思考」, 而是给 `_REASONING_ONLY`(关思考会 400)
         留的位, 那一类随 A2 一起删了; 而思考开着时 deepseek/zhipu **返回空正文**
         (A6 实测 `invalid_empty`)。⇒ `assert on` 随之变成恒假, 改成 `assert not on`。

    ⚠️ ②→③ 不是「② 写错了」, 是**需求变了**(形态 C‴)。判据仍是同一条:
       **拿不拿开关由槽的 profile 决定** —— 变的是那些 profile 的内容。
    """
    base = {"messages": [{"role": "user", "content": "return json"}]}
    off, on = [], []
    for slot, chain in llm_router.SLOT_MODELS.items():
        for account, model in chain:
            if account != "deepseek":
                continue
            wants_off = (llm_router._SLOT_PARAMS.get(slot) or {}).get(
                "enable_thinking") is False
            out = llm_router._apply_slot_params(slot, account, model, base)
            got = out.get("thinking") == {"type": "disabled"}
            assert got == wants_off, (
                f"{slot.value}/{model}: profile 要求关思考={wants_off}, 实际注入={got}。"
                f"实测不关思考时 ct=800 rt=800 **content=0** finish=length "
                f"—— ⚠️ 要害是**返回空正文**, 不是慢(7.26s 在 30s 预算内)。")
            # DashScope 的参数不该出现在 DeepSeek 的 payload 里
            assert "enable_thinking" not in out
            (off if wants_off else on).append(slot.value)
    assert off, ("一个「该关思考」的 deepseek 条目都没扫到 —— 闸空转了。"
                 "要么池没加上, 要么 SLOT_MODELS 的形状变了, 先查闸本身。")
    # 🔴 2026-08-15 二次订正: 原来还断言 `assert on`(必须存在「该开思考」的一侧),
    #    那是上一次订正时为守「reasoning 不许被误关思考」加的。
    #    **裁定把那条需求反过来了** —— REASONING 现在显式 enable_thinking: False
    #    (理由见 _SLOT_PARAMS 里那段: `{}` 是为 _REASONING_ONLY 留的位, 而那一类
    #    随 A2 一起删了; 不关思考时 deepseek/zhipu 返回**空正文**)。
    #    ⇒ 仓里已经没有「该开思考」的槽, `assert on` 变成恒假。
    #    ⛔ 这不是「断言没用」, 是**需求变了**(形态 C‴): 它红得理直气壮,
    #      最容易被读成「我改错了」然后把裁定回退掉。
    assert not on, (
        f"出现了「该开思考」的槽 {sorted(set(on))} —— 裁定之后不该有。"
        "若确实新增了这样的槽, 要连同 _REASONING_ONLY 的存活性一起重议。")


def test_deepseek_thinking_switch_is_keyed_by_the_exact_model_string():
    """阳性对照: 型号名差一个字母, 开关就**静默**失效 —— 这就是上面那道闸的理由。"""
    base = {"messages": [{"role": "user", "content": "return json"}]}
    real = llm_router._apply_slot_params(
        SLOT.REVIEW, "deepseek", "deepseek-v4-flash", base)
    assert real["thinking"] == {"type": "disabled"}
    typo = llm_router._apply_slot_params(
        SLOT.REVIEW, "deepseek", "deepseek-v4-flash-x", base)
    assert "thinking" not in typo


# ── T7-e 池与派生链 ─────────────────────────────────────────────────────
@pytest.mark.parametrize("slot", FIVE_SLOTS)
def test_deepseek_is_on_each_of_the_five_aistore_slots(slot):
    """aistore 覆盖哪五个槽, deepseek 就要覆盖哪五个 —— 否则 9-13 之后有槽没接住。"""
    chain = [m for a, m in llm_router.SLOT_MODELS[slot] if a == "deepseek"]
    assert chain, f"{slot.value} 槽里没有 deepseek —— 9-13 之后这个槽没有快候选"


@pytest.mark.parametrize("slot", FIVE_SLOTS)
def test_deepseek_outranks_the_undated_free_tail(slot):
    """⚠️ 派生顺序的事实记录, ⛔ 不是手工排的。

    `_build_chain` 按**到期日升序**排; deepseek 带 2026-11-15 复审日, 而
    tencent/ark/zhipu 无到期日(_FAR_FUTURE) ⇒ deepseek 排在那三个**免费**候选
    前面。⇒ **合并当天起** aistore 每失败一次就会立刻走一次付费调用。
    这条断言把这个成本事实钉住, 免得它悄悄变化。

    ── 2026-08-16: 成本事实变了, 而且是**变好** ────────────────────────
    owner 定「zhipu 优先」, zhipu 由结构键 `_ABSOLUTE_FIRST` 置顶。
    zhipu 是**免费且不过期**的, 于是付费的 deepseek 现在排在它**后面** ——
    要 zhipu 和 aistore **都**失败才会发生付费调用, 比原来多了一道免费拦截。

    ⛔ 所以这条闸不是删掉, 是**换成钉新的成本事实**:
       「付费候选前面必须至少有一个免费候选」。
    ⚠️ 原来那句「deepseek 在 tencent 之前」保留 —— 那部分口径没变。
    """
    order = [f"{a}/{m}" for a, m in llm_router.SLOT_MODELS[slot]]
    ds = order.index("deepseek/deepseek-v4-flash")

    # 🔑 承重的那句: 付费的 deepseek 前面必须有免费拦截, 否则成本模型要重新裁
    free_before = [
        p for p in llm_router.SLOT_MODELS[slot][:ds]
        if llm_router._expiry_of(*p) == llm_router._FAR_FUTURE
        or p[0] == "aistore"
    ]
    assert free_before, (
        f"{slot.value}: 付费的 deepseek 排在了所有免费候选前面 —— "
        "每一次调用都会直接计费, 成本模型要重新裁")
    assert order[0] == "zhipu/glm-4.5-air", (
        f"{slot.value}: 链头不是免费且不过期的 zhipu, 实际是 {order[0]}")

    # 这一段口径没变: deepseek 仍排在其余无到期日的免费尾巴之前
    for free in ("tencent/deepseek-v4-flash-202605",):
        if free in order:
            assert ds < order.index(free), "派生顺序变了 —— 成本模型跟着变, 要重新裁"


def test_aistore_still_heads_every_slot_until_it_expires():
    """aistore 的配置**一个字没动**, 但它现在是第二位 —— zhipu 由 owner 置顶。

    ── 2026-08-16 ────────────────────────────────────────────────────
    owner:「用 zhipu 的优先, 其他的不用动」。「不用动」我照做了:
    aistore 的 pool 条目、到期日、能力表**全部原样**, 只是前面多了一个 zhipu。

    ⚠️ 代价如实记, 这是本次改动唯一的实际损失:
       aistore 的免费额度 **2026-09-13 到期, 用不完就作废**。
       它从链首挪到第二位 ⇒ 那份 use-it-or-lose-it 的额度会用得更少。
       ⛔ 这一点值不值, 是 owner 的取舍, 不是这条闸能判的。

    ⇒ 断言换成「aistore 仍排在**付费的** deepseek 之前」——
      那才是它当初排在前面真正在守的东西(免费优先于付费),
      而不是「它必须是第 0 位」这个位置本身。
    """
    for slot in FIVE_SLOTS:
        chain = llm_router.SLOT_MODELS[slot]
        accounts = [a for a, _m in chain]
        assert "aistore" in accounts, f"{slot.value}: aistore 被整个移出了链 —— 那不是「不用动」"
        assert accounts[0] == "zhipu", f"{slot.value}: 链头不是 zhipu, 实际 {accounts[0]}"
        if "deepseek" in accounts:
            assert accounts.index("aistore") < accounts.index("deepseek"), (
                f"{slot.value}: 免费的 aistore 掉到付费的 deepseek 后面了")


# ── 记账真的接上了吗(形态 B: 机制在、没接上) ────────────────────────────
def test_budget_recording_is_actually_wired(monkeypatch):
    """🔴 `llm_budget` 整个模块曾经是**死代码**: `record_local` 与
    `deepseek_over_budget` 零生产调用点, 而它正是 $19.49/12 天那次事故之后建的。

    ⛔ 这条不能只断言「函数存在」—— 那正是它坏掉时的样子。要断言**它被调用**,
       且拿到的是从 usage 里解析出来的真数。
    """
    seen = []
    monkeypatch.setattr(llm_router, "record_local",
                        lambda *a: seen.append(a))
    llm_router._log_cache_and_record_budget(
        "review", "deepseek", "deepseek-v4-flash",
        {"usage": {"prompt_tokens": 4000, "completion_tokens": 120,
                   "prompt_tokens_details": {"cached_tokens": 3000}}})
    assert seen == [("deepseek-v4-flash", 4000, 120, 3000)], (
        f"记账没被调用或参数不对: {seen} —— "
        "函数名写着 record_budget 而函数体只有 log, 就是它坏掉时的样子")


def test_budget_recording_is_not_swallowed_when_the_module_is_missing():
    """⛔ import 必须在模块级: 放函数里的话 import 失败会被那个
    `except Exception: logger.debug(...)` 吞掉, 记账**静默停摆**而无任何信号。
    """
    import inspect
    src = inspect.getsource(llm_router._log_cache_and_record_budget)
    assert "import" not in src, (
        "记账的 import 又被挪回函数体里了 —— 它会被 except 吞掉")
    assert hasattr(llm_router, "record_local"), "模块级没有 record_local"
