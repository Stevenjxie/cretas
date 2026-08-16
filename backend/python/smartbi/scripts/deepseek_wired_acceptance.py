"""DeepSeek key 接进生产的验收 —— **走路由器真发一次**。

## 为什么这一条不能省

这个洞（生产进程里 `LLM_DEEPSEEK_API_KEY` 存在但**值为空**）活到今天，
正是因为上一次部署核对的四样**全是纯函数检查**：

    md5 一致 ✅ · 进程重启 ✅ · 链里有 deepseek ✅ · 推 today 后链首变了 ✅

**一样都不碰 key。** `call_chain` 里 `if not api_key: continue` 会**完全静默**
地跳过它 —— 不报错、不留日志，`_refuse_reason` 也看不见（它查白名单和到期日，
⛔ 不查 key）。

▎**「找到标记 ≠ 它能跑」。**

## ⛔ 为什么不能直打 api.deepseek.com

那只证明「key 对」，证明不了 **`_provider_config("deepseek")` 这一层通了** ——
而空 key 的故障恰恰就在这一层。⇒ 必须走 `call_chain`。

## 三条 + 一条阴性对照

1. 链钉死 `["deepseek"]` 真发一次，`served` 必须是 deepseek
2. 悬崖模拟：把 `_today` 推到 2026-09-14（aistore 到期后），
   五个槽的链首**真能应答** —— ⛔ 不是「链里有它」
3. 阴性对照：把 key 临时清空重跑第 1 条，**必须失败**
   ⛔ 不做这一步就分不清「它通了」和「我的断言恒真」
"""
import asyncio
import datetime
import os
import sys

import common.llm_router as R

#: ⚠️ `call_chain` 的第二个参数是 **payload dict**, ⛔ 不是 messages 列表 ——
#:    第一版直接传了列表, 拿到 `TypeError: 'list' object is not a mapping`。
#:    形状抄真实调用方 `agent/orchestrator.py:449`。
#:
#: 🔴 **每个槽要用满足它自己契约的 payload**, ⛔ 不能一句「只回答两个字」通吃。
#:    第二版就是那么写的, 结果:
#:      CHART / MAPPER   -> invalid_bad_json   (那两个槽要 JSON, 我给了「可以」)
#:      INSIGHTS / REVIEW-> invalid_too_short  (那两个槽要长文, 我给了 2 个字)
#:    ⚠️ 而 **zhipu 报的错一模一样** —— 那是内置的对照: 若 deepseek 真有问题,
#:       zhipu 应当成功。同样的错 ⇒ 病在我的 prompt 不在 provider。
#:    这正是仓里那条「玩具 prompt 下同一套验收是恒真式」。
_TEXT_PAYLOAD = {
    "messages": [{"role": "user", "content": "用一句完整的话说明今天适合做什么，不少于三十个字。"}],
    "temperature": 0.0,
    "max_tokens": 200,
}
_JSON_PAYLOAD = {
    "messages": [{"role": "user",
                  "content": '只输出一个 JSON 对象，不要任何解释：{"ok": true, "n": 1}'}],
    "temperature": 0.0,
    "max_tokens": 64,
}


def payload_for(slot):
    """按槽给合格 payload —— JSON 槽给 JSON 题, 文本槽给长文题。"""
    return _JSON_PAYLOAD if slot.name in ("CHART", "MAPPER") else _TEXT_PAYLOAD


PROMPT = _TEXT_PAYLOAD
CLIFF = datetime.date(2026, 9, 14)


async def _try(slot, chain):
    try:
        r = await R.call_chain(slot, payload_for(slot), chain=chain)
    except Exception as exc:                        # noqa: BLE001
        return None, f"{type(exc).__name__}: {exc}"
    # 返回体是 OpenAI 形状; 路由器把实际服务方挂在这些键之一
    served = r.get("served") or r.get("_served") or r.get("model") or r.get("provider")
    try:
        content = (r.get("choices", [{}])[0].get("message", {}).get("content") or "").strip()
    except (AttributeError, IndexError, TypeError):
        content = ""
    return {"served": served, "len": len(content)}, None


async def main() -> int:
    bad = []

    def check(name, ok, detail=""):
        print(f"{'✅' if ok else '🔴'} {name}" + (f"  {detail}" if detail else ""))
        if not ok:
            bad.append(name)

    # ── 1 走路由器真发, 链钉死 deepseek ──────────────────────────────
    got, err = await _try(R.SLOT.CHAT, ["deepseek"])
    print(f"── 1 chain=['deepseek'] → {got or err}")
    served_ok = bool(got) and "deepseek" in str(got["served"]).lower()
    check("1 走路由器真发, served 是 deepseek 且有内容",
          served_ok and got["len"] > 0, f"served={got['served'] if got else err}")

    # ── 2 悬崖模拟: 推时钟, 链首真能应答 ────────────────────────────
    original = R._today
    try:
        R._today = lambda: CLIFF
        assert R._today() == CLIFF, "时钟没推动 ⇒ 下面读数无意义"
        print(f"\n── 2 悬崖模拟 today={CLIFF}（aistore 已到期）")
        for slot in (R.SLOT.CHAT, R.SLOT.INSIGHTS, R.SLOT.CHART,
                     R.SLOT.MAPPER, R.SLOT.REVIEW):
            g, e = await _try(slot, None)
            head = str(g["served"]).lower() if g else ""
            ok = bool(g) and g["len"] > 0 and "aistore" not in head
            print(f"   {slot.name:<10} served={g['served'] if g else e}")
            check(f"2 {slot.name} 到期后真能应答且不是 aistore", ok)
    finally:
        R._today = original

    # ── 3 阴性对照: 清空 key 必须失败 ──────────────────────────────
    print("\n── 3 阴性对照: 临时清空 LLM_DEEPSEEK_API_KEY 重跑第 1 条")
    saved = os.environ.get("LLM_DEEPSEEK_API_KEY", "")
    try:
        os.environ["LLM_DEEPSEEK_API_KEY"] = ""
        g2, e2 = await _try(R.SLOT.CHAT, ["deepseek"])
        fell_through = (g2 is None) or ("deepseek" not in str(g2["served"]).lower())
        print(f"   清空后 → {g2 or e2}")
        check("3 清空 key 后第 1 条**失败**（证明它真的在读那个变量）", fell_through)
    finally:
        os.environ["LLM_DEEPSEEK_API_KEY"] = saved

    print("\n" + "=" * 74)
    print(f"[主断言] 不通过 {len(bad)} 条")
    if bad:
        print(f"🔴 {bad}")
        return 1
    print("✅ DeepSeek 已真正接进生产（⛔ 不是「配置里有」）")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
