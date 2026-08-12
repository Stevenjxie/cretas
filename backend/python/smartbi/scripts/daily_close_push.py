"""打烊触发 —— 跑一次日结那一屏, 走现有通知链推给店长。

## 判据 3(owner 2026-08-13)

> 打烊触发真的发出来了 —— 不是「代码写了」, 是**运行中的东西里能看到它发过**。

所以这个脚本的产出不是 stdout, 是**台账文件 + 防重表里的行**: 两处都是
跑完之后还在的东西, 可以事后去查。

## 三态退出码(硬约束 4)

    0 = 至少有一个租户推成功(或全部已推过 —— 幂等跳过也算这一天有人管)
    1 = 有租户推失败
    2 = **仪器问题**: 一个租户都没轮到, 或那一屏一段都没算出来

⛔ rc=2 必须单独告警。「一个都没推」和「都推过了」在计数上都是 notified=0,
   但前者是这次没量到东西, 后者是正常。混在一起报, 静默失效会看起来像正常。

## 租户从哪来

`DAILY_CLOSE_FACTORIES`(逗号分隔)。**没有默认值** —— 少这个环境变量直接 rc=2,
不去猜一个租户。⛔ 猜错的方向是「把别人家的经营数据推给另一家店长」。
"""
import asyncio
import datetime
import json
import os
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_FACTORIES = [f.strip() for f in os.environ.get("DAILY_CLOSE_FACTORIES", "").split(",")
              if f.strip()]

ctx = bootstrap_probe(_FACTORIES[0] if _FACTORIES else "MOCK_REST")

from smartbi.gold.restaurant.daily_close import push_daily_close  # noqa: E402

OUT = os.environ.get("DAILY_CLOSE_OUT", "/tmp/daily_close_push.json")


async def main() -> int:
    if not _FACTORIES:
        print("INSTRUMENT: DAILY_CLOSE_FACTORIES 为空 —— 一个租户都没轮到, "
              "这不是「今天没什么可推的」, 是这次根本没跑")
        return 2

    pool = await ctx.pool()
    rows = []
    for factory_id in _FACTORIES:
        try:
            out = await push_daily_close(pool, factory_id=factory_id)
            screen = out["screen"]
            notify = out["notify"]
            # 一段都没算出来 = 这一屏是空的。推不推都不对, 记成仪器问题。
            computed = [s for s in screen["sections"] if not s["missing_columns"]]
            rows.append({
                "factory_id": factory_id,
                "date": screen["date"],
                "provenance": screen["provenance"],
                "sections_total": len(screen["sections"]),
                "sections_computed": len(computed),
                "notified": notify["notified"],
                "skipped": notify["skipped"],
                "failed": notify["failed"],
                # ⚠️ 正文长度而不是正文本身 —— 台账不该变成日志(那会把经营数字
                #    抄一份到日志文件里, 而日志的权限比库松)。
                "answer_chars": len(screen["answer_text"]),
            })
        except Exception as e:  # noqa: BLE001 — 一个租户炸不该带走其余租户
            rows.append({"factory_id": factory_id, "error": f"{type(e).__name__}: {e}"})

    with open(OUT, "w", encoding="utf-8", newline="") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)

    for r in rows:
        print(json.dumps(r, ensure_ascii=False))

    errored = [r for r in rows if r.get("error")]
    failed = [r for r in rows if r.get("failed")]
    # 阳性对照: 至少有一个租户真的算出了东西。全 0 = 这次没量到, 不是「没什么可推」。
    any_computed = any(r.get("sections_computed", 0) > 0 for r in rows)

    if not any_computed:
        print("INSTRUMENT: 没有任何租户算出哪怕一段 —— 本次读数作废")
        return 2
    if errored or failed:
        return 1
    return 0


if __name__ == "__main__":
    print(f"=== {datetime.datetime.now():%F %T} daily-close push "
          f"factories={_FACTORIES} ===")
    sys.exit(asyncio.run(main()))
