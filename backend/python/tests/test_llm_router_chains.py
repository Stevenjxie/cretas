"""
_build_chain 产出的闸。

⛔ 明确不写 assert chain == sorted(chain, key=_expiry_of) —— 链就是用这个
   key 造出来的, 左右同源, 恒真式, 一次都红不了。承重的是下面这张人审冻结的
   golden 快照: 它的另一端是人, 不是代码。
"""
from pathlib import Path

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
        assert floors, f"{slot.value} 没有永不过期的地板条目, 全部 aliyun 过期那天会整槽变空"
        assert chain[-1] in floors, f"{slot.value} 末位不是地板: {chain[-1]}"


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
