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
_FROZEN_ALIYUN_REGISTRY = {
    # 2026-08-13 全量重审: owner 三账号控制台余量截图 ∩ 生产探针非空 content。
    # 上一版 26 条 → 本版 9 条。删掉的 17 条**全部实测 403 FreeTierOnly**,
    # 不是"人审觉得不该留", 是它们当天真的调不动了。
    #
    # 🔴 被删的里面包括 qwen3.8-max(三账号各 100 万、到期 11/01)与
    #    qwen3.7-flash 系列(10/23) —— 全表跑道最长的那几条, 昨天还都是双证通过的。
    #    判据: **到期日只说「什么时候一定没」, 不说「今天还有没有」。**
    #
    # ⛔ aliyun_c/deepseek-v4-flash-0731 单独说明: 控制台写着剩 479,703、到期
    #    10/31, 而探针 403。单边证据不收 —— 控制台与运行时打架时**不是**"控制台
    #    更权威所以加上"。
    ("aliyun_a", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_a", "kimi-k2.7-code"): datetime.date(2026, 9, 14),
    ("aliyun_a", "qwen3.7-max-2026-05-17"): datetime.date(2026, 8, 24),
    ("aliyun_a", "qwen3.7-max-preview"): datetime.date(2026, 8, 24),

    ("aliyun_b", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_b", "kimi-k2.7-code"): datetime.date(2026, 9, 14),

    ("aliyun_c", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_c", "kimi-k2.7-code"): datetime.date(2026, 9, 14),
    ("aliyun_c", "qwen3.7-max-preview"): datetime.date(2026, 8, 24),
}


# ── 2026-08-10 探针淘汰 5 条 (全部 403 AllocationQuota.FreeTierOnly) ──────
#   aliyun_b/qwen3.7-max-preview
#   aliyun_c/deepseek-v3.2            ← 当天曾是 REVIEW/CHAT/MAPPER 三槽链头
#   aliyun_c/glm-4.6                  ← 淘汰前是 REVIEW 链头
#   aliyun_c/qwen3-next-80b-a3b-instruct
#   aliyun_c/qwen3-vl-flash-2026-01-22 ← 唯一 VL, 移除后 SLOT.VL 变空链(§9.1 已拍板)
# 移除只需探针证据(准入才要控制台余量 ∩ 探针双证) —— 移除永远不制造计费风险。
# 08-13 那批 aliyun_c 免费额度正在成批烧完 —— 同一天稍晚又淘汰 1 条:
#   aliyun_c/qwen3.5-plus-2026-02-15  ← 上一次淘汰后它接替成为 REVIEW 链头,
#                                        几小时内也烧完。今天第三个死在链头上的。
# 📌 这正是「按到期日升序」的直接后果: 链头位置本身是最大消耗源, 而排在链头的
#    恰恰是额度最少的那个 —— 排序策略在持续地把自己的头部烧掉。


def test_aliyun_registry_matches_frozen_probe_result():
    """人审冻结表 vs 代码里的注册表。两边来源不同, 不是恒真式。

    改注册表必须同步改这张表, 而改这张表要求有当天的控制台+探针证据。
    """
    actual = {k: v for k, v in llm_router._SAFE_MODELS.items()
              if k[0].startswith("aliyun_")}
    assert actual == _FROZEN_ALIYUN_REGISTRY


def test_non_aliyun_registry_matches_frozen_probe_result():
    """地板: 2026-08-12 起 ark 恢复 1 条、tencent 3 条、zhipu 1 条。

    08-09 那版是「tencent 收缩到 1 个, ark 清空(provider 配置保留)」。08-12
    owner 给出控制台余量 + 账号级计费开关确认(ark 安心体验模式 / tencent 用完即停),
    生产同源探针连过两轮, 按准入判据(控制台 ∩ 探针, 双证)加回。
    """
    actual = {k: v for k, v in llm_router._SAFE_MODELS.items()
              if not k[0].startswith("aliyun_")}
    assert actual == {
        # Shanghai Telecom AI Store: owner-confirmed account auto-stop + live
        # synthetic probes on 2026-08-13.  A conservative hard expiry prevents
        # the one-month grant from remaining callable indefinitely.
        ("aistore", "DeepSeek-V4-Flash-A"): datetime.date(2026, 9, 13),
        ("aistore", "Qwen3-235B-A22B"): datetime.date(2026, 9, 13),
        ("aistore", "Qwen3-32B"): datetime.date(2026, 9, 13),
        # 🔒 DeepSeek 官方 (2026-08-15, T7) —— **等 Steve 对白名单的明确 yes**,
        #    在那之前 ⛔ 不许合并。
        # 🔴 这两条的日期**不是**额度到期, 是我们自己设的**强制复审点**
        #    (按量付费本身没有到期日)。与上面 aistore 那三条语义不同。
        # 加它的理由是 aistore 三条 2026-09-13 硬到期, 而它们是五个槽的链首;
        # 同日实测 _MINIMAL_SAFE_SET 8 条里 2 死 3 超预算, 扣掉 aistore 后
        # 既活着又在 6.0s 内的只剩 zhipu 一条。
        ("deepseek", "deepseek-v4-flash"): datetime.date(2026, 11, 15),
        ("deepseek", "deepseek-v4-pro"): datetime.date(2026, 11, 15),
        # tencent: owner 2026-08-13 控制台 14 个服务 ID ∩ 探针产出正文(len≥8)= 9 个。
        # ⚠️ GET /models 返回 **102** 个而控制台只有 14 个 —— **接口目录 ≠ 账号权益**,
        #    清单只认控制台。
        ("tencent", "deepseek-v4-flash-202605"): None,
        ("tencent", "kimi-k2.7-code-highspeed"): None,
        ("tencent", "kimi-k2.7-code"): None,
        ("tencent", "minimax-m2.7"): None,
        ("tencent", "mimo-v2.5-pro"): None,
        # 特化 SKU(hy-mt2=机器翻译 / hy-role、hunyuan-role=角色扮演)。
        # 在白名单里 = 计费安全且 owner 确认; **不在任何池里** = 没跑过契约。
        ("tencent", "hunyuan-role-latest"): None,
        ("tencent", "hy-mt2-lite"): None,
        ("tencent", "hy-role"): None,
        ("tencent", "hy-mt2-plus"): None,
        # ark: owner 给的 18 个官方 Model ID 逐条实打, **只有 3 个可调**;
        # 3 个里 2 个不合契约(见 _ARK_CONTRACT_REJECTED / 角色扮演 SKU)。
        ("ark", "doubao-seed-2-0-code-preview-260215"): None,
        ("zhipu", "glm-4.5-air"): None,
    }


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
