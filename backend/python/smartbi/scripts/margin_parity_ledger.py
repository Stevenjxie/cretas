"""把一次毛利对账压成台账里的一行。

⛔ 独立模块而不是 cron 里的 heredoc(硬约束 6)。
"""
import json
import os
import sys

SRC = os.environ.get("PARITY_OUT", "/tmp/margin_parity.json")

with open(SRC, encoding="utf-8") as f:
    row = json.load(f)

print(json.dumps({
    "date": row.get("date"),
    "rc": int(os.environ.get("PARITY_RC", "-1")),
    "factory_id": row.get("factory_id"),
    "executor": row.get("executor_gross_profit"),
    "resolver": row.get("resolver_gross_profit"),
    "diff": row.get("diff"),
}, ensure_ascii=False), file=sys.stdout)
