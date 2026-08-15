"""T3 · aistore `thinking:disabled` 是否被照办 —— 一次 A/B 探针，⛔ 不是采样。

## 假设

DeepSeek V4 Flash **默认开启 thinking**，必须显式传
`{"thinking": {"type": "disabled"}}` 才关。

🔴 但代码**已经在发**这个字段，⛔ 别去「补」它 —— 三个条件全真：

    llm_router.py:1388  _AISTORE_THINKING_OBJECT_MODELS ∋ "DeepSeek-V4-Flash-A"
    llm_router.py:1431  account=="aistore" ∧ model∈该集合 ∧ prof["enable_thinking"] is False
                        ⇒ p["thinking"] = {"type": "disabled"}
    llm_router.py:1298  SLOT.REVIEW: {"enable_thinking": False}

⇒ 假设从「忘了发」变成「**发了但没被照办**」。

## 做法

同一个 **真 T3 prompt**，发两次，**唯一差别是带不带那个字段**：
两侧都先过 `_apply_slot_params(SLOT.REVIEW, "aistore", model, payload)`，
B 侧再把 `thinking` 键**删掉** —— 这样其余参数逐字相同，⛔ 不手拼 payload。

## 判读表（owner 定，⛔ 不要现场改）

    两次都 1–3s 且 reasoning_tokens≈0   ⇒ 字段被照办，thinking 不是 9% 的成因
    带的 1–3s、不带的 8s+               ⇒ 字段被照办且机制被证明，9% 另有成因
    两次都慢 / reasoning_tokens 非 0    ⇒ 字段没被照办，owner 假设成立且是成因

## 硬约束 9 —— 主读数是阴性的（`reasoning_tokens ≈ 0`），必须配阳性对照

    PC1 两侧 HTTP 200 且 content 非空 ⇒ 调用真的产出了东西，
        `reasoning_tokens=0` 才读作「没思考」而不是「没跑」。
    PC2 两侧 completion_tokens > 0   ⇒ 同上，且证明 usage 块真的在填。
    ⛔ PC 不过 ⇒ 主读数作废。

⛔ 只读：不改路由代码、不动链顺序、不碰 _SAFE_MODELS。
"""
import asyncio
import json
import sys
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FACTORY = "MOCK_REST"
ACCOUNT = "aistore"
MODEL = "DeepSeek-V4-Flash-A"

ctx = bootstrap_probe(FACTORY)


def usage_of(body):
    u = (body or {}).get("usage") or {}
    details = u.get("completion_tokens_details") or {}
    return {
        "prompt_tokens": u.get("prompt_tokens"),
        "completion_tokens": u.get("completion_tokens"),
        "reasoning_tokens": details.get("reasoning_tokens"),
        "completion_tokens_details": details,
    }


async def main():
    from common.llm_router import (
        SLOT,
        _apply_slot_params,
        _normalize_payload_for_provider,
        _provider_config,
        get_llm_http_client,
    )
    from smartbi.gold.restaurant.restaurant_intent import (
        _SEMANTIC_MAX_TOKENS,
        _build_t3_prompt,
    )

    base_url, api_key = _provider_config(ACCOUNT)
    if not api_key:
        print(f"⛔ 拿不到 {ACCOUNT} 的 api_key —— 本轮没有读数，⛔ 不猜。")
        return 2
    print(f"base_url = {base_url}")

    prompt = _build_t3_prompt("本月订单数多少", None, None, (), None)
    base_payload = {
        "messages": [
            {"role": "system",
             "content": "你只输出JSON格式的意图解析结果，不输出任何其他文字。"},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0,
        "max_tokens": _SEMANTIC_MAX_TOKENS,
    }

    # ⛔ 两侧都走生产那条构造路径, 只在最后删掉 thinking —— 保证唯一变量
    with_thinking = _apply_slot_params(
        SLOT.REVIEW, ACCOUNT, MODEL,
        _normalize_payload_for_provider({**base_payload, "model": MODEL}, ACCOUNT),
    )
    without_thinking = dict(with_thinking)
    removed = without_thinking.pop("thinking", None)

    print(f"\nA 侧(生产实际发的) thinking = {with_thinking.get('thinking')!r}")
    print(f"B 侧(对照)        thinking = {without_thinking.get('thinking', '<不存在>')!r}")
    if removed is None:
        print("⛔ A 侧压根没有 thinking 字段 —— 与 1431 行的三条件矛盾，"
              "本探针的前提不成立，⛔ 不要继续解读。")
        return 2
    only_diff = (set(with_thinking) - set(without_thinking)) == {"thinking"} and all(
        with_thinking[k] == without_thinking[k]
        for k in without_thinking
    )
    print(f"唯一差别只有 thinking 这一个键: {only_diff}")
    if not only_diff:
        print("⛔ 两侧不止差一个键 —— A/B 不成立，作废。")
        return 2

    client = get_llm_http_client()
    results = {}
    for label, payload in (("A 带 thinking:disabled", with_thinking),
                           ("B 不带(对照)", without_thinking)):
        t0 = time.monotonic()
        resp = await client.post(
            f"{base_url}/chat/completions",
            headers={"Authorization": f"Bearer {api_key}",
                     "Content-Type": "application/json"},
            json=payload,
            timeout=60.0,          # ⛔ 探针给足时间: 要量真实耗时, 不是量预算
        )
        elapsed = time.monotonic() - t0
        try:
            body = resp.json()
        except Exception:
            body = None
        content = ""
        if body:
            try:
                content = (body["choices"][0]["message"]["content"] or "")
            except Exception:
                content = ""
        u = usage_of(body)
        results[label] = {"elapsed": elapsed, "status": resp.status_code,
                          "content_len": len(content), "usage": u}
        print(f"\n### [{ACCOUNT}/{MODEL}] {label}")
        print(f"  HTTP                {resp.status_code}")
        print(f"  耗时                {elapsed:.2f}s")
        print(f"  content 字符数      {len(content)}")
        print(f"  usage               {json.dumps(u, ensure_ascii=False)}")
        print(f"  content 前 160 字   {content[:160]!r}")

    print("\n" + "=" * 72)
    a, b = results["A 带 thinking:disabled"], results["B 不带(对照)"]
    pc1 = (a["status"] == 200 and b["status"] == 200
           and a["content_len"] > 0 and b["content_len"] > 0)
    pc2 = ((a["usage"]["completion_tokens"] or 0) > 0
           and (b["usage"]["completion_tokens"] or 0) > 0)
    print(f"PC1 两侧 200 且 content 非空       : {pc1}")
    print(f"PC2 两侧 completion_tokens > 0     : {pc2}")
    if not (pc1 and pc2):
        print("⛔ 阳性对照不过 —— 主读数作废，⛔ 不许据此下结论。")
        return 2
    print(f"A 耗时 {a['elapsed']:.2f}s  reasoning_tokens={a['usage']['reasoning_tokens']!r}")
    print(f"B 耗时 {b['elapsed']:.2f}s  reasoning_tokens={b['usage']['reasoning_tokens']!r}")
    print("=" * 72)
    print("⇒ 判读按 owner 的表，⛔ 探针不替它下结论。")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
