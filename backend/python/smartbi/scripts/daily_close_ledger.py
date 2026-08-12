"""把一次打烊推送压成台账里的一行。

⛔ 独立模块而不是 cron 里的 heredoc(硬约束 6): heredoc 里的转义会被 shell 吃掉,
   而吃掉之后**不报错** —— 表现是台账里悄悄少一列, 或者 `\n` 变成真换行把
   JSONL 撑成两行。实测在别的脚本上踩过三次。
"""
import datetime
import json
import os
import sys

SRC = os.environ.get("DAILY_CLOSE_OUT", "/tmp/daily_close_push.json")

with open(SRC, encoding="utf-8") as f:
    rows = json.load(f)

notified = sum(len(r.get("notified") or ()) for r in rows)
skipped = sum(len(r.get("skipped") or ()) for r in rows)
failed = sum(len(r.get("failed") or ()) for r in rows)

print(json.dumps({
    "date": datetime.date.today().isoformat(),
    "rc": int(os.environ.get("DAILY_CLOSE_RC", "-1")),
    "factories": len(rows),
    "notified": notified,
    "skipped": skipped,
    "failed": failed,
    # 阳性对照放进台账: 全 0 说明那一屏一段都没算出来, 而那时 notified 也是 0 ——
    # 只看 notified 分不清「都推过了」和「这次没量到东西」。
    "sections_computed": sum(r.get("sections_computed", 0) for r in rows),
    "provenance": sorted({r["provenance"] for r in rows if r.get("provenance")}),
    "errors": [r["error"] for r in rows if r.get("error")],
}, ensure_ascii=False), file=sys.stdout)
