"""
_build_chain 产出的闸。

⛔ 明确不写 assert chain == sorted(chain, key=_expiry_of) —— 链就是用这个
   key 造出来的, 左右同源, 恒真式, 一次都红不了。承重的是下面这张人审冻结的
   golden 快照: 它的另一端是人, 不是代码。
"""
import datetime
from pathlib import Path
from unittest import mock

import pytest

from common import llm_router
from common.llm_router import SLOT

_GOLDEN = Path(__file__).parent / "golden" / "llm_router_chains.txt"


def _render_chains() -> str:
    lines = []
    for slot in SLOT:
        chain = llm_router.SLOT_MODELS[slot]
        lines.append(f"== {slot.value} (len={len(chain)})")
        for account, model in chain:
            lines.append(f"   {llm_router._expiry_of(account, model)}  {account}/{model}")
    return "\n".join(lines) + "\n"


def test_chains_match_human_reviewed_golden():
    """人审冻结的链快照 vs 代码算出的链。

    链顺序变了必须有人看一眼 diff 并主动更新这个文件 —— 这是本次唯一能
    抓住「排序规则被改坏」的闸。重新生成 (工作目录必须是 backend/python/,
    否则 tests.test_llm_router_chains / common.llm_router 都 import 不到):
        cd backend/python && python -c "import sys;sys.path.insert(0,'.');\\
        from tests.test_llm_router_chains import _render_chains;\\
        open('tests/golden/llm_router_chains.txt','w',encoding='utf-8',newline='\\n')\\
        .write(_render_chains())"
    ⚠️ 必须 newline='\\n' —— Windows 上默认写入会把整个文件转成 CRLF。

    ⛔ 这条本身管不住上面那句警告: `Path.read_text()` 做 universal-newline
    转换, 会把 CRLF 静默读成 LF, 所以"忘了 newline='\\n' 重新生成"这个具体
    错误在这道闸里**一次都不会红**, 只会在 git diff 里冒出一堆噪音行。下面
    单独断言字节层没有 \\r, 让这条警告自己长出牙齿。
    """
    assert _GOLDEN.exists(), f"golden 文件不存在: {_GOLDEN}"
    assert "\r" not in _GOLDEN.read_bytes().decode("utf-8"), (
        f"{_GOLDEN} 含 CRLF —— 重新生成时忘了 newline='\\n', "
        "见本函数 docstring 的重新生成命令"
    )
    expected = _GOLDEN.read_text(encoding="utf-8")
    assert _render_chains() == expected


def test_every_text_slot_has_a_floor():
    """每个文本槽末尾必须有一个永不过期的地板条目。

    没有这条, 某次注册表重写把 tencent/zhipu 删空后, 所有槽会在最后一个
    aliyun 条目过期的那天同时变成空链 —— 而 CI 全绿。

    VL 是唯一豁免项(spec §9.1: 业务不用, 明确报错优于文本模型瞎猜图片)。
    豁免名单硬编码在 _NO_TEXT_TAIL_SLOTS, 想再豁免一个槽必须改代码留下 diff。
    """
    for slot in SLOT:
        if slot in llm_router._NO_TEXT_TAIL_SLOTS:
            continue
        chain = llm_router.SLOT_MODELS[slot]
        assert chain, f"{slot.value} 链为空"
        floors = [p for p in chain if llm_router._expiry_of(*p) == llm_router._FAR_FUTURE]
        # 🔑 承重的是这一句 —— docstring 里那个事故("全部过期那天整槽变空")
        #    要的是「链里**有**一个永不过期的条目」, 与它排第几无关。
        assert floors, f"{slot.value} 没有永不过期的地板条目, 全部 aliyun 过期那天会整槽变空"
        # ⛔ 2026-08-16 改的是位置那一句, 不是上面那句。
        #    owner 定「zhipu 优先」⇒ 地板从末位挪到首位(见 _ABSOLUTE_FIRST 的实测)。
        #    ⚠️ 位置断言**没有删掉**, 只是换了一端 —— 删掉的话,
        #      「地板到底在不在一个确定的位置上」就没人守了。
        assert chain[0] in floors, f"{slot.value} 首位不是地板: {chain[0]}"


@pytest.mark.parametrize(
    "slot", [SLOT.CHAT, SLOT.CHART, SLOT.MAPPER, SLOT.REVIEW, SLOT.INSIGHTS]
)
def test_interactive_pools_exclude_slow_models(slot):
    """交互槽的候选池不能含实测慢模型。

    _SLOW_MODELS 是独立于池定义的人写实测名单, 两边来源不同, 不是恒真式。
    只约束 _SLOT_POOLS —— 地板由 _build_chain 单独追加, 慢于不答。
    """
    offenders = [p for p in llm_router._SLOT_POOLS[slot]
                 if p[1] in llm_router._SLOW_MODELS]
    assert offenders == [], (
        f"{slot.value} 池含慢模型 {offenders} —— 会把'答不出来'换成'等到超时'"
    )


def test_param_profile_constraints_are_respected():
    """关思考槽不能收 _REASONING_ONLY; REASONING 槽不能收 _THINKING_OFF_ONLY。

    2026-08-09 实测: MiniMax-M2.5 关思考直接 400; glm-4.6 开思考 44s、
    qwen3.5-plus-2026-02-15 开思考 21s。放错槽 = 稳定 400 或稳定超时。
    """
    for slot, pool in llm_router._SLOT_POOLS.items():
        profile = llm_router._SLOT_PARAMS.get(slot) or {}
        thinking_off = profile.get("enable_thinking") is False
        for account, model in pool:
            if thinking_off:
                assert model not in llm_router._REASONING_ONLY, (
                    f"{slot.value} 关思考, 但收了只能开思考的 {account}/{model} → 稳定 400"
                )
            else:
                assert model not in llm_router._THINKING_OFF_ONLY, (
                    f"{slot.value} 不关思考, 但收了开思考会空/极慢的 {account}/{model}"
                )


# ═══════════════════════════════════════════════════════════════════════════
# 2026-08-10「按能力排」(owner 拍板) 的两条不变式。
#
# golden 快照冻结的是**这一次**的具体顺序; 下面两条冻结的是**规则** ——
# 有人把 _build_chain 改回纯到期日排序时, golden 会红(顺序变了)但只会被
# "重新生成一下" 抹掉, 而这两条会指名道姓说出改坏了什么。
# ═══════════════════════════════════════════════════════════════════════════

def _failing_pairs() -> set:
    return {
        pair for pair, (rate, _p50) in llm_router._CAPABILITY.items()
        if rate < llm_router._CAPABILITY_PASS_FLOOR
    }


def test_measured_failures_never_precede_measured_passes(monkeypatch):
    """实测不达标的模型不许排在任何实测达标的模型前面。

    2026-08-10 实测的具体后果: aliyun_c/deepseek-v3.2 契约 0/6 全 403 quota,
    而按旧的纯到期日排序它(08-13, 最早到期)正好是 REVIEW/CHAT/MAPPER 三个槽
    的**链头** —— 每次调用都先在一个已耗尽的模型上撞一跳。

    ⛔ 地板(_TEXT_TAIL)豁免: 它必须留在结构性末位(见
       test_every_text_slot_has_a_floor), 那条约束优先于能力档。
    """
    # 2026-08-10: 实测不达标的那条(aliyun_c/deepseek-v3.2, 0/6 全 403)当天就被
    # 探针淘汰出 _SAFE_MODELS 了 —— 于是 _CAPABILITY 里一个不达标条目都不剩,
    # 这条断言变得**无法失败**。这正是本文件开头警告过的情形, 按当时写下的处置
    # 办: **造一个合成的不达标条目**, 让规则重新有东西可判。
    # ⛔ 合成条目必须真的进 _CAPABILITY(monkeypatch), 不能只在测试里假设它存在 ——
    #    否则测的是我脑子里的排序, 不是 _build_chain 的排序。
    real_pool = [p for p in llm_router._SLOT_POOLS[SLOT.REVIEW]
                 if p in llm_router._CAPABILITY]
    assert real_pool, "REVIEW 池里没有任何被测过的条目, 无法构造本用例"
    victim = real_pool[0]
    synthetic = dict(llm_router._CAPABILITY)
    synthetic[victim] = (0.0, 0.1)      # 合成: 判它不达标
    monkeypatch.setattr(llm_router, "_CAPABILITY", synthetic)

    failing = _failing_pairs()
    assert victim in failing, "合成的不达标条目没有被 _failing_pairs 识别"
    tail = set(llm_router._TEXT_TAIL)
    # ⚠️ 把"今天"钉在测量当天再重建链, **不读 import 期算好的 SLOT_MODELS**。
    #    否则这条闸会在 2026-08-31(测量日 + 21 天)那天因为能力表超龄、排序
    #    退回纯到期日而变红 —— 红得完全正确却与"排序规则被改坏"无关, 就是
    #    "天天炸=没人看"的又一个源头。它验的是**规则**, 规则只在能力档生效时
    #    存在; 能力表该不该重测由 llm_pool_health 的超龄告警负责。
    with mock.patch.object(llm_router, "_today",
                           lambda: llm_router._CAPABILITY_MEASURED_AT):
        chains = {slot: llm_router._build_chain(slot) for slot in SLOT}
    for slot, chain in chains.items():
        body = [p for p in chain if p not in tail]
        worst_pass = max(
            (i for i, p in enumerate(body)
             if p in llm_router._CAPABILITY and p not in failing),
            default=None,
        )
        if worst_pass is None:
            continue
        for i, pair in enumerate(body):
            if pair in failing:
                assert i > worst_pass, (
                    f"{slot.value}: 实测不达标的 {pair} 排在位置 {i}, "
                    f"而实测达标的模型最晚才排到 {worst_pass} —— "
                    f"排序键对「这个模型今天还活着吗」失聪了"
                )


def test_stale_capability_table_falls_back_to_expiry_order():
    """能力表超龄 → 退回纯到期日排序, 而不是默默拿陈旧读数硬排。

    阴性对照: 冻结一个远未来的"今天", 让 _capability_stale() 为真, 链必须
    与纯到期日排序逐条相等。若 _build_chain 忘了接这条分支, 两者会不等。
    """
    far = llm_router._CAPABILITY_MEASURED_AT + datetime.timedelta(
        days=llm_router._CAPABILITY_MAX_AGE_DAYS + 1)

    for slot in SLOT:
        entries = list(llm_router._SLOT_POOLS[slot])
        if slot not in llm_router._NO_TEXT_TAIL_SLOTS:
            entries += llm_router._TEXT_TAIL
        expiry_only = llm_router._dedup_chain(
            sorted(entries, key=lambda p: llm_router._expiry_of(*p)))

        with mock.patch.object(llm_router, "_today", lambda: far):
            assert llm_router._capability_stale() is True
            assert llm_router._build_chain(slot) == expiry_only, (
                f"{slot.value}: 能力表超龄后没有退回到期日排序"
            )

    # 反向: 测量当天不该是 stale (否则上面那条恒真, 什么都没验)
    with mock.patch.object(llm_router, "_today",
                           lambda: llm_router._CAPABILITY_MEASURED_AT):
        assert llm_router._capability_stale() is False


def test_schema_violators_never_precede_schema_clean_models(monkeypatch):
    """实测会**编造枚举值**的模型不许排在实测零越界的模型前面。

    golden 快照也能抓到顺序变化, 但它会被「重新生成一下」抹掉 —— 这条会指名
    道姓说出改坏了什么。

    2026-08-10 的具体后果: `qwen3.5-plus` 在「下周需要多少兼职」上编
    `time_range named="next_week"`(提示词只允许 today|this_week|this_month)。
    它当 REVIEW 链头的三轮电池里 [51] **三轮全挂**, 而上一版链头(glm-4.6)的
    三轮**一次没挂**。下游确定性代码只认枚举内的值, 编出来的那个到了下游要么
    被丢弃要么走错分支。
    """
    tail = set(llm_router._TEXT_TAIL)
    violators = {p for p, n in llm_router._PLAN_SCHEMA_VIOLATIONS.items() if n}
    clean = {p for p, n in llm_router._PLAN_SCHEMA_VIOLATIONS.items() if n == 0}
    assert violators and clean, (
        "越界表里缺了其中一档, 这条断言无法失败 —— 重测后若全员零越界, "
        "改成 monkeypatch 造一个合成越界条目再断言(见上一条的做法)。")

    with mock.patch.object(llm_router, "_today",
                           lambda: llm_router._PLAN_SCHEMA_MEASURED_AT):
        chains = {slot: llm_router._build_chain(slot) for slot in SLOT}

    for slot, chain in chains.items():
        body = [p for p in chain if p not in tail]
        # 只在**同存活档**的条目之间比 —— 存活档优先级更高。延迟档排在合法档
        # 之后, 所以不参与这里的分组: 一个零越界但慢的模型排在越界模型之后是
        # **错的**(合法性优先于快慢), 这条正是要抓它。
        def rank(pair):
            return (llm_router._capability_tier(*pair),)
        for i, bad in enumerate(body):
            if bad not in violators:
                continue
            for j, good in enumerate(body):
                if good in clean and j > i and rank(good) == rank(bad):
                    raise AssertionError(
                        f"{slot.value}: 实测越界的 {bad} 排在位置 {i}, "
                        f"而同档位、实测零越界的 {good} 却排在 {j} —— "
                        f"排序对「这个模型会不会编枚举值」失聪了")
