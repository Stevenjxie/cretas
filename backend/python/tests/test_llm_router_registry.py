"""
_SAFE_MODELS 注册表自身的闸。

与 test_llm_router_chains.py 的分工: 这里只管「注册表这张表对不对」,
链怎么拼、顺序对不对在 chains 那边。
"""
import datetime

from common import llm_router


def test_minimal_safe_set_is_subset_of_safe_models():
    """stale fail-safe 只能退守到真实存在于白名单里的条目。

    _refuse_reason 的顺序是: registry_stale 时只放行 _MINIMAL_SAFE_SET
    → 紧接着查 (account, model) in _SAFE_MODELS。所以最小集里任何不在
    _SAFE_MODELS 的条目在 stale 分支下会被判 not_allowlisted ——
    fail-safe 会 fail 成「没有地板」, 恰恰是它想防的那件事。
    """
    orphans = sorted(p for p in llm_router._MINIMAL_SAFE_SET
                     if p not in llm_router._SAFE_MODELS)
    assert orphans == [], (
        f"_MINIMAL_SAFE_SET 有 {len(orphans)} 个条目不在 _SAFE_MODELS 里, "
        f"stale 时会被 _refuse_reason 判 not_allowlisted: {orphans}"
    )


# 2026-08-09 三账号控制台截图 ∩ 生产探针(经 _apply_slot_params, 判据为非空 content)
# 的交集。单边证据不收: 控制台有余量但探针 403 的不收(aliyun_c/deepseek-v4-flash-0731);
# 探针 200 但控制台未列的更不收 —— 那说明「用完即停」没覆盖它, 可能真在计费(glm-5.2)。
#
# 2026-08-10: 生产探针首次对本注册表整体实跑, 发现 3 条 08-09 当天还 OK 的条目
# 24 小时内变成 403 quota, 已按「移除只需探针证据」剔除 —— 见 llm_router.py
# `_SAFE_MODELS` 后面的 "2026-08-10 探针复审剔除" 段落:
#   aliyun_c/qwen3-max-2025-09-23, aliyun_c/qwen3-vl-32b-instruct,
#   aliyun_c/qwen3.7-max (与 qwen3.7-max-2026-05-17/-preview/-2026-05-20 不同模型)。
#: 人审冻结表。⛔ 改注册表必须同步改这张表, 而改这张表要求有当天的证据。
#:
#: 🔴 2026-08-15 owner 裁定收敛到 aistore / deepseek / zhipu 三家
#:    (原话:「暂时限制用 ai store 和 deepseek 还有 zhipu, 后面我有要求的时候
#:    我们再说」)。旧的 aliyun / tencent / ark 冻结表**直接替换, ⛔ 不留注释**:
#:    这张表不是历史记录, 是**对当前状态的断言**; 把旧值留在里面(哪怕注释掉)
#:    它就同时是「记录」又是「断言」—— 同一个东西有两份, 它一定会漂。
#:    留痕已有两份且都是权威位置: git 历史(完整旧表 + 那个 commit)、
#:    以及 llm_router.py 里 08-09 / 08-13 两轮人审结论的审计注释。
_FROZEN_REGISTRY = {
    # AI Store: owner 确认账号级自动停 + 生产同源探针; 到期日是**真额度到期**。
    ("aistore", "DeepSeek-V4-Flash-A"): datetime.date(2026, 9, 13),
    ("aistore", "Qwen3-235B-A22B"): datetime.date(2026, 9, 13),
    ("aistore", "Qwen3-32B"): datetime.date(2026, 9, 13),
    # DeepSeek 官方: ⚠️ 日期是我们自己设的**强制复审点**, 不是额度到期
    #    (按量付费本身没有到期日)。语义与上面三条不同。
    ("deepseek", "deepseek-v4-flash"): datetime.date(2026, 11, 15),
    ("deepseek", "deepseek-v4-pro"): datetime.date(2026, 11, 15),
    # zhipu: 文本地板, 无到期日。
    ("zhipu", "glm-4.5-air"): None,
}


def test_registry_matches_frozen_probe_result():
    """人审冻结表 vs 代码里的注册表。两边来源不同, 不是恒真式。"""
    assert llm_router._SAFE_MODELS == _FROZEN_REGISTRY


def test_minimal_safe_set_is_a_subset_of_the_registry():
    """⚠️ 两张表**当前内容相同是偶然, 不是设计**:
    一张是计费白名单, 一张是 registry 超龄后的最小可信集, 语义不同。

    ⇒ 这里只断言**子集**关系(那才是真不变式), ⛔ 不断言相等 —— 否则下一个人
      看到「两张表一样」会以为其中一张多余, 把它删掉。
    """
    assert llm_router._MINIMAL_SAFE_SET <= set(llm_router._SAFE_MODELS)
    assert llm_router._MINIMAL_SAFE_SET, "最小集空了 —— 超龄之后就没有任何可用候选"


def test_fast_non_dashscope_floor_precedes_the_slow_one():
    """慢地板不能排在快地板前面, 且 zhipu 必须是**链的最后一位**。

    判据来自 2026-08-09 的生产事故: 链是串行且共享同一个总预算, 把慢的排在前面
    会让后面本来 1s 就能答的候选因为分不到时间而跟着超时、连续 2 次即被熔断。

    🔴 2026-08-13 改了断言对象。旧版量的是 `_TEXT_TAIL` **这个列表的书写下标**,
       理由写的是「到期日都是 None, 稳定排序原样保留书写顺序」。那句话是错的 ——
       `_build_chain` 的排序键在到期日之前还有能力档和延迟档两位, 它们一旦不同,
       书写顺序就完全不起作用。当天实测: zhipu 被算到链的**第 2 位**, 而这条闸
       全绿。**它守的东西和真正决定行为的东西不是同一个。**
       现在断言 `SLOT_MODELS`(真正被 call_chain 遍历的那个), 并由
       `llm_router._ABSOLUTE_LAST` 这个结构键保证 zhipu 置底。
    """
    for slot, chain in llm_router.SLOT_MODELS.items():
        if not chain:
            continue                      # VL 是空链, 见 spec §9.1
        assert chain[-1] == ("zhipu", "glm-4.5-air"), (
            f"{slot.value}: zhipu 必须是链的最后一位, 实际链尾是 {chain[-1]}"
        )
        idx = {p: i for i, p in enumerate(chain)}
        slow = idx.get(("tencent", "minimax-m2.7"))
        if slow is None:
            continue
        for fast in (("tencent", "deepseek-v4-flash-202605"),
                     ("ark", "doubao-seed-2-0-code-preview-260215")):
            if fast in idx:
                assert idx[fast] < slow, (
                    f"{slot.value}: {fast} 必须排在 minimax-m2.7 之前"
                )


def test_registry_audit_date_is_not_stale():
    """审计日期过期(>21d) → router 收缩到 _MINIMAL_SAFE_SET。

    ⏰ 这是一道**故意的定时闸, 不是缺陷**。它会在 _REGISTRY_AUDIT_DATE + 21 天
    那一刻变红, 与任何人改了什么代码无关 —— 这正是它存在的意义。

    红了怎么办: 去三个控制台核对余量 + 跑 scripts/probe_llm_registry, 按判据
    (控制台有余量 ∩ 探针非空内容)更新 _SAFE_MODELS 与本文件的冻结表, 然后把
    _REGISTRY_AUDIT_DATE 推到复审当天。⛔ 不许只推日期不复审 —— 那等于把这道
    闸拆了。

    为什么必须是红灯而不是告警: 2026-08-09 之所以出事, 恰恰是因为没人盯这个
    日期(现值 07-26, 距 staleness 只剩 7 天), 而 prod 里同时有 23 个模型在
    额度退避、约 1800 万 token 可用额度 router 够不着 —— 告警在那 6 天里
    每天都发, 没人处理。红灯拦得住, 告警拦不住。
    """
    assert not llm_router._registry_stale(llm_router._today())
