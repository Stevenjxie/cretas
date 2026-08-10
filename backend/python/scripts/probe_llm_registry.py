#!/usr/bin/env python
"""
每日全量探针 —— 把 _SAFE_MODELS 与现实对账。

⚠️ 必须放在 backend/python/ 下。仓根 scripts/ 不在部署同步范围
   (2026-08-01 飞轮日报因此静默坏了 5 天)。

⚠️ 判据两条, 缺一不可:
   1. 走 router 自己的 _apply_slot_params —— 不能自己拼 payload。
      zhipu 关思考要 thinking:{type:disabled} 不是 enable_thinking;
      _THINKING_ONLY 模型根本不该收到 enable_thinking。手搓必然误判。
   2. 判「非空 content」不判 HTTP 200。200 + 空 content 长得像成功。

用法: python -m scripts.probe_llm_registry
退出码: 0 = 无差异; 1 = 有差异(供 cron 告警)
"""
import asyncio
import json
import sys
from typing import Dict, List, Tuple

import httpx

sys.path.insert(0, ".")

from common import llm_router as r  # noqa: E402

_PROMPT = "用一句话说明什么是库存周转率。"

# CHART/MAPPER 的 profile 带 json=True: 生产在这两个槽会给
# response_format={"type":"json_object"} + 从 prompt 里找 "json" 这个词
# (`_payload_mentions_json`, DashScope 硬性要求 —— 缺了直接 400 "messages must
# contain the word json")。旧探针 prompt 里没有这个词, 于是这条分支从未被走过,
# CHART/MAPPER 探的其实是"当作纯文本槽会不会 200", 与生产真正发的请求形状不同。
_PROMPT_JSON = "请用 JSON 格式回复, 包含字段 summary, 一句话说明什么是库存周转率。"


def _prompt_for(slot: r.SLOT) -> str:
    """slot 的 profile 要求 json 时用带"json"关键词的 prompt, 触发真实生产会
    走的 response_format 分支; 否则用普通文本 prompt。"""
    profile = r._SLOT_PARAMS.get(slot) or {}
    return _PROMPT_JSON if profile.get("json") else _PROMPT


def classify_probe_result(status: int, body_text: str, content: str) -> str:
    """把一次探针调用归成 ok / quota / empty / error 四类之一。"""
    if 200 <= status < 300:
        return "ok" if content.strip() else "empty"
    if r._is_quota_exhausted(status, body_text):
        return "quota"
    return "error"


# 出现在 _TEXT_TAIL 里的条目是**每个非 VL 槽共用的地板** —— _build_chain 把它们
# 追加到每一个非 VL 槽末尾(见 _NO_TEXT_TAIL_SLOTS)。但它们不在任何 _SLOT_POOLS
# 里, 所以只看 _SLOT_POOLS 会漏探: tencent/minimax-m2.7 是 CHAT/CHART/MAPPER/
# INSIGHTS/REVIEW/REASONING 六个槽的最后一跳, 旧版 `_slots_for` 只会把它探成
# REVIEW 一个槽, 探不到它在 CHART/MAPPER 下(json_object + `_TOKENHUB_MIN_MAX_TOKENS`
# 在 json 分支弹出 max_tokens 之后又强制补回 1600 的地板)这条只在这两个槽出现的
# 参数交互。
_TEXT_TAIL_SET = frozenset(r._TEXT_TAIL)
_TEXT_TAIL_SLOTS: List[r.SLOT] = [s for s in r.SLOT if s not in r._NO_TEXT_TAIL_SLOTS]


def _slots_for(pair: Tuple[str, str]) -> List[r.SLOT]:
    """该条目出现在哪些槽的池里, 外加(若是地板成员)_build_chain 实际追加它的
    每一个槽; 两者都没有就用 REVIEW 档探一次。"""
    slots = [s for s, pool in r._SLOT_POOLS.items() if pair in pool]
    if pair in _TEXT_TAIL_SET:
        slots += [s for s in _TEXT_TAIL_SLOTS if s not in slots]
    return slots or [r.SLOT.REVIEW]


async def _probe(client: httpx.AsyncClient, account: str, model: str,
                 slot: r.SLOT) -> Tuple[str, str]:
    base, key = r._provider_config(account)
    # 走跟生产完全相同的两步管线(normalize → apply_slot_params), 顺序也一致
    # (见 llm_router.call_chain 的 req_payload 构造) —— 今天 normalize 是纯
    # passthrough, 这里只是前瞻性对齐; 一旦它长出真正的逻辑, 探针不会因为
    # "忘了接线"而悄悄和生产分岔。
    normalized = r._normalize_payload_for_provider({
        "model": model,
        "messages": [{"role": "user", "content": _prompt_for(slot)}],
        "max_tokens": 200,
    }, account)
    payload = r._apply_slot_params(slot, account, model, normalized)
    try:
        resp = await client.post(
            base.rstrip("/") + "/chat/completions",
            json=payload,
            headers={"Authorization": f"Bearer {key}"},
            timeout=90.0,
        )
    except Exception as exc:  # noqa: BLE001 — 网络层任何异常都算 error
        return "error", type(exc).__name__
    body = resp.text
    content = ""
    if 200 <= resp.status_code < 300:
        try:
            content = (json.loads(body)["choices"][0]["message"].get("content") or "")
        except Exception:  # noqa: BLE001
            content = ""
    return classify_probe_result(resp.status_code, body, content), f"{resp.status_code}"


def _aggregate_verdicts(verdicts: List[Tuple[str, str]]) -> Tuple[str, str]:
    """归并同一 (account, model) 在多个槽下的判定。

    任一槽拿到非空内容即算可用。全不可用时**不取第一个判定**——槽的遍历顺序
    不该决定打印出来的原因: 同一模型在不同槽下表现不同(比如快槽偶发网络
    超时报 error, 而推理槽是真的 403 额度耗尽)本身就是该被看见的信号,
    取第一条会让顺序早的那个偶然值盖掉真正的病因(2026-08-09 复审判据)。
    """
    ok = next((v for v in verdicts if v[0] == "ok"), None)
    if ok:
        return ok
    labels = sorted({v[0] for v in verdicts})
    details = sorted({v[1] for v in verdicts})
    return "+".join(labels), "; ".join(details)


async def _run() -> Dict[Tuple[str, str], Tuple[str, str]]:
    sem = asyncio.Semaphore(8)
    results: Dict[Tuple[str, str], Tuple[str, str]] = {}

    async with httpx.AsyncClient() as client:
        async def one(pair: Tuple[str, str]) -> None:
            async with sem:
                verdicts = [await _probe(client, pair[0], pair[1], s)
                            for s in _slots_for(pair)]
            results[pair] = _aggregate_verdicts(verdicts)

        await asyncio.gather(*(one(p) for p in r._SAFE_MODELS))
    return results


def main() -> int:
    results = asyncio.run(_run())
    today = r._today()

    # 「dead」只收「注册表说活、实测也应该活, 但探针失败」的条目 —— 真正的
    # 现实漂移。到期日已过的条目探针失败是**设计内**的(_refuse_reason 在同一
    # 时刻已经把它们从链里硬拒了), 单独归入「已过期, 待清理」桶且不进 dead:
    # 否则 2026-08-13 那批 14 条一次性到期后, main() 会从那天起**每天**返回 1,
    # 直到有人手工把它们从 _SAFE_MODELS 删掉 —— 正是 spec §5.5 要防的告警疲劳
    # (与飞轮日报静默坏 5 天、回归电池连红 4 天没人处理是同一种"天天炸=没人看"
    # 的死法)。dead 只保留"注册表还没过期但探针说它已经不行了"这类真实信号。
    dead: List[str] = []
    expired: List[str] = []
    for (a, m), v in sorted(results.items()):
        if v[0] == "ok":
            continue
        line = f"{a}/{m}  ({v[0]} {v[1]})"
        if r._refuse_reason(a, m, today) == "expired":
            expired.append(line)
        else:
            dead.append(line)

    # 7 天内到期: 必须严格未来 (0 < delta <= 7)。原判据 `<= 7` 对负数同样为真,
    # 于是已经过期的条目(delta 为负)永远符合 `<= 7`, 会在这节下永久出现,
    # 与「已过期, 待清理」那节表达的是同一件事却混进了"即将"的语气。
    soon = sorted(
        f"{a}/{m}  {r._expiry_of(a, m)}"
        for (a, m) in r._SAFE_MODELS
        if 0 < (r._expiry_of(a, m) - today).days <= 7
    )

    print(f"[probe] {today} 共探 {len(results)} 个 (账号,模型)")
    print(f"\n⚠ 注册表说活、实测不可用 ({len(dead)}):")
    for line in dead:
        print(f"   {line}")
    print(f"\nℹ 已过期, 待清理 ({len(expired)}):")
    print("  (到期日已过, _refuse_reason 当天起已硬拒 —— 探针失败是预期内的,")
    print("   不计入退出码; 出现在这里只是提醒该把它们从 _SAFE_MODELS 删掉)")
    for line in expired:
        print(f"   {line}")
    print(f"\n⚠ 7 天内到期 ({len(soon)}):")
    for line in soon:
        print(f"   {line}")
    print("\nℹ 「实测可用但未登记」需先核对控制台余量再登记 —— 探针 200 但控制台")
    print("  无余量说明「用完即停」没覆盖它, 那个 200 可能是真在计费。本脚本")
    print("  不主动枚举未登记模型, 避免把可能计费的条目做成一键加入的清单。")

    # 只有「dead」(注册表说活、实测不可用, 且尚未到期)才翻转退出码。「soon」
    # (7 天内到期)连续多天都非空——08-13 那批到期时一次性 14 条——若也计入
    # 退出码, cron 告警会连续多天必炸, 炸到没人再读(正是这整件事的病根:
    # 飞轮日报静默坏 5 天、回归电池连红 4 天没人处理都是"天天炸=没人看"的同一
    # 种死法)。「已过期, 待清理」同理不计入 —— 它是"已知且预期"的到期,
    # 不是需要立刻处理的现实漂移。soon/expired 仍然打印, 只是不占用
    # "需要立刻处理"的信号位。
    return 1 if dead else 0


if __name__ == "__main__":
    raise SystemExit(main())
