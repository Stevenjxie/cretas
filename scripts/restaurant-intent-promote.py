"""餐饮意图飞轮晋升 CLI: 列候选(连库) / 人审后 --apply(不连库, 只写账本)。

用法:
  python scripts/restaurant-intent-promote.py --list
      连 smartbi 库聚合 T3(LLM 层)候选, 打印评审表(不写任何东西)。

  python scripts/restaurant-intent-promote.py --apply reviewed.json
      reviewed.json = 人工从 --list 输出里挑出的条目, 形如:
        [
          {"query": "这两个月生意咋样，挣着钱没", "code": "RESTAURANT_OPS_SALES_SUMMARY"},
          {"query": "这周比上周差在哪", "code": "RESTAURANT_OPS_TREND_ANALYSIS"}
        ]
      只写 backend/python/smartbi/data/promoted_restaurant_intent_samples.json，
      不连库、不自动挑选 —— 挑哪些进这个文件是人的判断，不是本脚本的判断。

绝不静默自动毕业: 没有 --apply 就永远不写文件；--apply 只接受人已经审过的
JSON 文件，脚本本身不会替你"自动通过"任何候选。

连 prod DB (--list 需要):
  prod smartbi 库不对公网开放 (server-operations.md 安全组规则)。用 SSH 隧道:
    ssh -L 5432:localhost:5432 root@47.100.235.168
  然后在另一个终端设置环境变量指向本地隧道端口 (密码见本地 .env /
  .claude/rules/db-credentials.md，本脚本不读取也不打印任何密码):
    (PowerShell)
      $env:POSTGRES_HOST = "localhost"
      $env:POSTGRES_PORT = "5432"
      $env:POSTGRES_DB = "smartbi_prod_db"
      $env:POSTGRES_USER = "smartbi_user"
      $env:POSTGRES_PASSWORD = "<从 db-credentials.md 或 .env 取, 不要硬编码/不要提交>"
      python scripts/restaurant-intent-promote.py --list
  本地/测试库不需要隧道，直接指向 smartbi_db 即可 (Settings 默认值)。
"""
import argparse
import asyncio
import json
import sys
from pathlib import Path

_BACKEND_PY = Path(__file__).resolve().parents[1] / "backend" / "python"
sys.path.insert(0, str(_BACKEND_PY))
# smartbi/services/__init__.py eagerly imports excel_parser -> bare `from services...`,
# only resolvable with backend/python/smartbi on sys.path (mirrors promote_learnings.py).
sys.path.insert(0, str(_BACKEND_PY / "smartbi"))

from smartbi.gold.restaurant_intent_promotion import (  # noqa: E402
    aggregate_candidates,
    apply_promotions,
)


async def _list_candidates(min_confidence: float, min_count: int, limit: int,
                           factory_id: str) -> None:
    from smartbi.config import get_pg_pool

    try:
        pool = await get_pg_pool()
    except Exception as exc:
        print(
            f"ERROR: 连不上 smartbi DB ({exc})。"
            "prod 库需先开 SSH 隧道 -- 见本文件顶部说明。"
        )
        return
    if pool is None:
        print(
            "ERROR: 连不上 smartbi DB (postgres 未配置/密码错/网络不通)。"
            "prod 库需先开 SSH 隧道 -- 见本文件顶部说明。"
        )
        return

    candidates = await aggregate_candidates(
        pool, min_confidence=min_confidence, min_count=min_count, limit=limit,
        factory_id=factory_id,
    )
    if not candidates:
        print("无候选 (要么 log 里没有满足 tier=llm+contract_pass+served 的查询, 要么全部已在 SAMPLE_QUERIES/账本中)")
        return

    print(f"{'query':<42}{'code':<28}{'次数':<6}{'置信':<8}{'冲突':<6}判定")
    print("-" * 100)
    n_recommended = 0
    for c in candidates:
        if c["recommended"]:
            mark = "推荐晋升"
            n_recommended += 1
        elif c["conflict"]:
            mark = f"冲突(候选code: {','.join(c['codes'])})"
        else:
            mark = "未达标(次数<2 且 置信<0.85)"
        q_display = c["query"] if len(c["query"]) <= 40 else c["query"][:39] + "…"
        print(
            f"{q_display:<42}{c['code']:<28}{c['occurrence_count']:<6}"
            f"{c['max_confidence']:<8.2f}{str(c['conflict']):<6}{mark}"
        )
    print("-" * 100)
    print(f"共 {len(candidates)} 候选, {n_recommended} 条达标推荐晋升 (次数>=2 或 置信>=0.85 且无 code 冲突)。")
    print(
        "人审后把要晋升的条目整理成 JSON list ([{\"query\":..., \"code\":...}, ...])，"
        "再跑: python scripts/restaurant-intent-promote.py --apply reviewed.json"
    )


def _apply(json_path: str) -> None:
    path = Path(json_path)
    if not path.exists():
        print(f"ERROR: 文件不存在: {json_path}")
        return
    try:
        entries = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        print(f"ERROR: 无法解析 JSON: {exc}")
        return
    if not isinstance(entries, list):
        print('ERROR: --apply 的 JSON 必须是 list, 形如 [{"query": ..., "code": ...}, ...]')
        return

    result = apply_promotions(entries)

    print(f"账本: {result['ledger_path']}")
    if result["added"]:
        print(f"新增 {len(result['added'])} 条:")
        for e in result["added"]:
            print(f"  + [{e['code']}] {e['query']}")
    else:
        print("新增 0 条 (未写文件)")
    if result["skipped"]:
        print(f"跳过 {len(result['skipped'])} 条 (已存在于账本 / code 不合法或 query 为空):")
        for e in result["skipped"]:
            print(f"  - [{e.get('code')}] {e.get('query')!r} ({e.get('reason')})")
    print(f"账本当前共 {result['ledger_size']} 条样例。")
    if result["added"]:
        print("下一步: git diff 确认改动 -> commit -> 部署 Python (populate_restaurant_ops 会重嵌新样例)。")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--list", action="store_true", help="连库列出晋升候选 (dry-run, 不写任何东西)")
    ap.add_argument(
        "--apply", metavar="JSON_FILE", default=None,
        help="人审后的 [{query, code}] JSON 文件路径 -> 写入账本 (不连库)",
    )
    ap.add_argument("--min-confidence", type=float, default=0.75, dest="min_confidence")
    ap.add_argument("--min-count", type=int, default=1, dest="min_count")
    ap.add_argument("--limit", type=int, default=200)
    ap.add_argument(
        "--factory", default="DEMO_REST", dest="factory_id",
        help="租户 (RLS GUC app.factory_id): fallback log 带 FORCE RLS, 不设则假性 0 行",
    )
    args = ap.parse_args()

    if args.apply:
        _apply(args.apply)
    elif args.list:
        asyncio.run(_list_candidates(
            args.min_confidence, args.min_count, args.limit, args.factory_id))
    else:
        ap.print_help()
