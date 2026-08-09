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


def classify_probe_result(status: int, body_text: str, content: str) -> str:
    """把一次探针调用归成 ok / quota / empty / error 四类之一。"""
    if 200 <= status < 300:
        return "ok" if content.strip() else "empty"
    if r._is_quota_exhausted(status, body_text):
        return "quota"
    return "error"


def _slots_for(pair: Tuple[str, str]) -> List[r.SLOT]:
    """该条目出现在哪些槽的池里; 都没出现就用 REVIEW 档探一次。"""
    slots = [s for s, pool in r._SLOT_POOLS.items() if pair in pool]
    return slots or [r.SLOT.REVIEW]


async def _probe(client: httpx.AsyncClient, account: str, model: str,
                 slot: r.SLOT) -> Tuple[str, str]:
    base, key = r._provider_config(account)
    payload = r._apply_slot_params(slot, account, model, {
        "model": model,
        "messages": [{"role": "user", "content": _PROMPT}],
        "max_tokens": 200,
    })
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

    dead = sorted(f"{a}/{m}  ({v[0]} {v[1]})"
                  for (a, m), v in results.items() if v[0] != "ok")
    soon = sorted(f"{a}/{m}  {r._expiry_of(a, m)}"
                  for (a, m) in r._SAFE_MODELS
                  if (r._expiry_of(a, m) - today).days <= 7)

    print(f"[probe] {today} 共探 {len(results)} 个 (账号,模型)")
    print(f"\n⚠ 注册表说活、实测不可用 ({len(dead)}):")
    for line in dead:
        print(f"   {line}")
    print(f"\n⚠ 7 天内到期 ({len(soon)}):")
    for line in soon:
        print(f"   {line}")
    print("\nℹ 「实测可用但未登记」需先核对控制台余量再登记 —— 探针 200 但控制台")
    print("  无余量说明「用完即停」没覆盖它, 那个 200 可能是真在计费。本脚本")
    print("  不主动枚举未登记模型, 避免把可能计费的条目做成一键加入的清单。")

    # 只有「dead」(注册表说活、实测不可用)才翻转退出码。「soon」(7 天内到期)
    # 连续多天都非空——08-13 那批到期时一次性 14 条——若也计入退出码, cron
    # 告警会连续多天必炸, 炸到没人再读(正是这整件事的病根: 飞轮日报静默坏
    # 5 天、回归电池连红 4 天没人处理都是"天天炸=没人看"的同一种死法)。
    # soon 仍然打印, 只是不需要今天就动手, 不该占用"需要立刻处理"的信号位。
    return 1 if dead else 0


if __name__ == "__main__":
    raise SystemExit(main())
