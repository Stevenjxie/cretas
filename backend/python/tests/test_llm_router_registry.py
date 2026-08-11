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
    ("aliyun_a", "qwen3.8-max"): datetime.date(2026, 11, 1),
    ("aliyun_a", "deepseek-v4-flash-0731"): datetime.date(2026, 10, 31),
    ("aliyun_a", "qwen3.7-flash"): datetime.date(2026, 10, 23),
    ("aliyun_a", "qwen3.7-flash-2026-07-15"): datetime.date(2026, 10, 23),
    ("aliyun_a", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_a", "kimi-k2.7-code"): datetime.date(2026, 9, 14),
    ("aliyun_a", "qwen3.7-max-2026-05-17"): datetime.date(2026, 8, 24),
    ("aliyun_a", "qwen3.7-max-preview"): datetime.date(2026, 8, 24),

    ("aliyun_b", "qwen3.8-max"): datetime.date(2026, 11, 1),
    ("aliyun_b", "deepseek-v4-flash-0731"): datetime.date(2026, 10, 31),
    ("aliyun_b", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_b", "kimi-k2.7-code"): datetime.date(2026, 9, 14),
    ("aliyun_b", "qwen3.7-max-2026-05-17"): datetime.date(2026, 8, 24),

    ("aliyun_c", "qwen3.8-max"): datetime.date(2026, 11, 1),
    ("aliyun_c", "kimi-k2.7-code"): datetime.date(2026, 9, 14),
    ("aliyun_c", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_c", "qwen3.7-max-2026-05-17"): datetime.date(2026, 8, 24),
    ("aliyun_c", "qwen3.7-max-preview"): datetime.date(2026, 8, 24),
    ("aliyun_c", "qwen3.7-max-2026-05-20"): datetime.date(2026, 8, 20),
    ("aliyun_c", "deepseek-v3.2-exp"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3.6-plus-2026-04-02"): datetime.date(2026, 8, 13),
    # 2026-08-12 新增: owner 控制台余量截图 ∩ 生产同源探针(SLOT.REVIEW)连过两轮。
    # 带日期的快照 403 而不带日期的别名 OK —— 控制台上是两行、两份独立免费额度。
    ("aliyun_c", "qwen3.6-plus"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3.6-max-preview"): datetime.date(2026, 8, 13),
    ("aliyun_c", "kimi-k2-thinking"): datetime.date(2026, 8, 13),
    ("aliyun_c", "deepseek-r1"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3-235b-a22b-thinking-2507"): datetime.date(2026, 8, 13),
    ("aliyun_c", "deepseek-r1-0528"): datetime.date(2026, 8, 13),
    ("aliyun_c", "MiniMax-M2.5"): datetime.date(2026, 8, 13),
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
        ("tencent", "minimax-m2.7"): None,
        ("tencent", "deepseek-v4-flash-202605"): None,
        ("tencent", "hy3"): None,
        ("ark", "deepseek-v4-flash-ga-260731"): None,
        ("zhipu", "glm-4.5-air"): None,
    }


def test_fast_non_dashscope_floor_precedes_the_slow_one():
    """快地板必须排在 minimax-m2.7(6.7s) 和 zhipu 之前。

    _TEXT_TAIL 三条新条目到期日都是 None, `_build_chain` 的稳定排序会原样保留
    书写顺序 —— 所以顺序**由 _TEXT_TAIL 那几行决定**, 这条断言守的就是那个顺序。
    判据来自 2026-08-09 的生产事故: 链是串行且共享同一个总预算, 把慢的排在前面
    会让后面本来 1s 就能答的候选因为分不到时间而跟着超时、连续 2 次即被熔断。
    """
    tail = llm_router._TEXT_TAIL
    idx = {pair: i for i, pair in enumerate(tail)}
    slow_floor = idx[("tencent", "minimax-m2.7")]
    for fast in (("ark", "deepseek-v4-flash-ga-260731"),
                 ("tencent", "hy3"),
                 ("tencent", "deepseek-v4-flash-202605")):
        assert idx[fast] < slow_floor, (
            f"{fast} 必须排在 minimax-m2.7 之前, 否则 6.7s 会把它们的预算吃掉"
        )
    assert idx[("zhipu", "glm-4.5-air")] == len(tail) - 1, "zhipu 必须留在最后一位"


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
