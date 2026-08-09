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


async def _run() -> Dict[Tuple[str, str], Tuple[str, str]]:
    sem = asyncio.Semaphore(8)
    results: Dict[Tuple[str, str], Tuple[str, str]] = {}

    async with httpx.AsyncClient() as client:
        async def one(pair: Tuple[str, str]) -> None:
            async with sem:
                verdicts = [await _probe(client, pair[0], pair[1], s)
                            for s in _slots_for(pair)]
            # 任一槽拿到非空内容即算可用; 全不可用时取第一个判定作为原因。
            ok = next((v for v in verdicts if v[0] == "ok"), None)
            results[pair] = ok or verdicts[0]

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

    return 1 if (dead or soon) else 0


if __name__ == "__main__":
    raise SystemExit(main())
