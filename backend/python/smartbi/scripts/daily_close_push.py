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


def _write(rows) -> None:
    """产出**永远**落盘, 哪怕是空的。

    🔴 2026-08-13 实测踩到: 名单为空时我在这里直接 return 2, 没写文件 ——
       cron 的 `[ -r ... ]` 看到的是**上一次**的 json, 于是台账里出现
       `{"rc": 2, "factories": 1, "sections_computed": 1}`:
       rc 是这次的, 计数是上次的。**一行里混着两次运行的读数**, 而且不报错。
    """
    with open(OUT, "w", encoding="utf-8", newline="") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)


async def main() -> int:
    if not _FACTORIES:
        _write([])
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
            # 🔴 「有数可说」按**值**数, 不按 `missing_columns` 数。
            #    2026-08-13 实测踩过: 当天三段是「— / 0 / —」而按 missing_columns
            #    数出来是 3/3 —— 仪器把「schema 在」当成了「有数」, rc=0 通过。
            computed = [s for s in screen["sections"] if s["value"] is not None]
            rows.append({
                "factory_id": factory_id,
                "date": screen["date"],
                "status": screen["status"],
                "provenance": screen["provenance"],
                "sections_total": len(screen["sections"]),
                "sections_computed": len(computed),
                "notified": notify["notified"],
                "skipped": notify["skipped"],
                "failed": notify["failed"],
                # ⚠️ 正文长度而不是正文本身 —— 台账不该变成日志(那会把经营数字
                #    抄一份到日志文件里, 而日志的权限比库松)。
                "answer_chars": len(screen["answer_text"]),
                # 4b: 逐日覆盖率。有了它才说得出「你今天补了 2 个百分点」。
                # ⛔ 只追加字段, 不动既有的任何一个。
                "coverage_ratio": screen.get("coverage_ratio"),
            })
        except Exception as e:  # noqa: BLE001 — 一个租户炸不该带走其余租户
            rows.append({"factory_id": factory_id, "error": f"{type(e).__name__}: {e}"})

    _write(rows)

    for r in rows:
        print(json.dumps(r, ensure_ascii=False))

    errored = [r for r in rows if r.get("error")]
    failed = [r for r in rows if r.get("failed")]
    # 🔴 `no_data` = 连订单数都算不出来 → **执行链没跑通**, 是仪器问题。
    #    `no_business` = 订单数 0 → 今天没营业, 正常, 不推也不告警。
    #    ⛔ 这两个在计数上都是 notified=0, 合成一个就再也分不出来了。
    no_data = [r for r in rows if r.get("status") == "no_data"]

    if errored or no_data:
        for r in no_data:
            print(f"INSTRUMENT: {r['factory_id']} 连订单数都没算出来 —— "
                  f"要么执行链没跑通, 要么 ETL 今天没落数")
        return 2 if no_data and not errored else 1
    if failed:
        return 1
    return 0


if __name__ == "__main__":
    print(f"=== {datetime.datetime.now():%F %T} daily-close push "
          f"factories={_FACTORIES} ===")
    sys.exit(asyncio.run(main()))
