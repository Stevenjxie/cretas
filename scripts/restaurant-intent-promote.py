"""餐饮意图飞轮晋升 CLI: 列候选(连库) / 人审后 --apply(写晋升表, 连库)。

用法:
  python scripts/restaurant-intent-promote.py --list
      连 smartbi 库聚合 T3(LLM 层)候选, 打印评审表(不写任何东西)。

  python scripts/restaurant-intent-promote.py --apply reviewed.json
      reviewed.json = 人工从 --list 输出里挑出的条目, 形如:
        [
          {"query": "这两个月生意咋样，挣着钱没", "code": "RESTAURANT_OPS_SALES_SUMMARY"},
          {"query": "这周比上周差在哪", "plan": { ...完整 planner 计划 JSON... }}
        ]
      写入 smartbi 库的 ai_promoted_routes 表 (2026-07-28 飞轮出口回接):
      生产餐饮问答 (parse_restaurant_query semantic_first) 在调 LLM 之前整句
      相等查这张表, 命中即零 token 回放计划。写表而不是写代码文件, 是因为这
      是「执行授权」, 必须两个 uvicorn worker 都立刻可见, 且带完整计划而不只
      是一个 code。

      【硬性】plan 里的时间必须是相对描述 (time_range: null 或相对短语), 绝不能是
      具体日期 —— 存了具体日期就会在第二天被原样回放。CLI 会在写入前编译一遍
      计划, 编不出可执行契约的条目直接拒收。

  python scripts/restaurant-intent-promote.py --list-routes
      连库列出当前 ai_promoted_routes 里已晋升的短语 (只读)。

  python scripts/restaurant-intent-promote.py --apply-ledger reviewed.json
      旧行为: 只写 backend/python/smartbi/data/promoted_restaurant_intent_samples.json
      (向量索引的样例语料, 不是执行授权), 不连库。两者用途不同, 见
      smartbi/gold/restaurant/restaurant_intent_promotion.py 模块 docstring。

绝不静默自动毕业: 没有 --apply 就永远不写任何东西；--apply 只接受人已经审过的
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

from smartbi.gold.restaurant.restaurant_intent_promotion import (  # noqa: E402
    aggregate_candidates,
    aggregate_misses,
    apply_promotions,
    apply_route_promotions,
    list_route_promotions,
)


async def _open_pool():
    """Shared DB entry for every connected subcommand. Returns None (after
    printing the tunnel hint) instead of raising, mirroring _list_candidates."""
    from smartbi.config import get_pg_pool

    try:
        pool = await get_pg_pool()
    except Exception as exc:
        print(
            f"ERROR: 连不上 smartbi DB ({exc})。"
            " 需要 SSH 隧道 + POSTGRES_* 环境变量, 见本脚本 docstring。"
        )
        return None
    if pool is None:
        print(
            "ERROR: smartbi DB 未配置 (postgres_url 为空)。"
            " 需要 SSH 隧道 + POSTGRES_* 环境变量, 见本脚本 docstring。"
        )
        return None
    return pool


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


async def _list_misses(limit: int, factory_id: str) -> None:
    from smartbi.config import get_pg_pool

    try:
        pool = await get_pg_pool()
    except Exception as exc:
        print(f"ERROR: 连不上 smartbi DB ({exc})。prod 库需先开 SSH 隧道 -- 见本文件顶部说明。")
        return
    if pool is None:
        print("ERROR: 连不上 smartbi DB (postgres 未配置/密码错/网络不通)。")
        return

    misses = await aggregate_misses(pool, limit=limit, factory_id=factory_id)
    if not misses:
        print("无 miss 记录 (delegate:false 捕获自 2026-07-23 起才有数据)")
        return

    print(f"{'query':<42}{'次数':<6}{'原因':<24}{'spec解析到':<28}family")
    print("-" * 110)
    for m in misses:
        q_display = m["query"] if len(m["query"]) <= 40 else m["query"][:39] + "…"
        print(
            f"{q_display:<42}{m['occurrence_count']:<6}"
            f"{','.join(m['reasons']):<24}{','.join(m['spec_intents']) or '-':<28}{m['family']}"
        )
    print("-" * 110)
    print(
        f"共 {len(misses)} 组 miss。复盘指南: spec解析到有值 = 解析对但路由拒"
        " (resolver 缺口/例外规则); prefilter+query 族 = 前置滤过严或缺 T1 规则;"
        " write 族 = 本该 Java 工具接, 属正常。"
    )


def _read_review_file(json_path: str, flag: str):
    path = Path(json_path)
    if not path.exists():
        print(f"ERROR: 文件不存在: {json_path}")
        return None
    try:
        entries = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        print(f"ERROR: 无法解析 JSON: {exc}")
        return None
    if not isinstance(entries, list):
        print(f'ERROR: {flag} 的 JSON 必须是 list, 形如 [{{"query": ..., "code": ...}}, ...]')
        return None
    return entries


async def _apply_routes(json_path: str, scope: str, source: str,
                        reviewed_by: str) -> None:
    """人审后写 ai_promoted_routes —— 生产零 token 回放的执行授权表。"""
    entries = _read_review_file(json_path, "--apply")
    if entries is None:
        return
    pool = await _open_pool()
    if pool is None:
        return
    try:
        result = await apply_route_promotions(
            pool, entries, scope=scope, source=source, reviewed_by=reviewed_by,
        )
    except Exception as exc:
        print(f"ERROR: 写 ai_promoted_routes 失败, 未晋升任何条目: {exc}")
        return

    print(f"晋升表: ai_promoted_routes (domain={result['domain']}, scope={result['scope']}, source={result['source']})")
    if result["written"]:
        print(f"写入 {len(result['written'])} 条:")
        for e in result["written"]:
            print(f"  + [{e['intent']}] {e['normalized_phrase']}  (原句: {e['query']})")
    else:
        print("写入 0 条")
    if result["skipped"]:
        print(f"跳过 {len(result['skipped'])} 条:")
        for e in result["skipped"]:
            print(f"  - {e.get('query')!r} ({e.get('reason')})")
    if result["written"]:
        print(
            "下一步: 无需重启 —— 两个 uvicorn worker 会在各自的晋升表缓存 TTL"
            " (60s) 内自动读到新行。核对: 日志 grep"
            " 'zero-token promoted-route hit'。"
        )


async def _list_routes(factory_id: str) -> None:
    pool = await _open_pool()
    if pool is None:
        return
    try:
        rows = await list_route_promotions(pool)
    except Exception as exc:
        print(f"ERROR: 读 ai_promoted_routes 失败: {exc}")
        return
    if not rows:
        print("ai_promoted_routes 为空 (domain=restaurant)。")
        return
    print(f"{'短语':<24} {'intent':<36} {'scope':<12} {'source':<12} {'hits':>6}  reviewed_by")
    for r in rows:
        print(
            f"{str(r['normalized_phrase']):<24} {str(r['intent']):<36} "
            f"{str(r['scope']):<12} {str(r['source']):<12} "
            f"{r['hit_count']:>6}  {r['reviewed_by'] or '-'}"
        )
    print(f"共 {len(rows)} 条已晋升短语。")


def _apply(json_path: str) -> None:
    """旧路径: 只写向量样例账本 (--apply-ledger)。不是执行授权。"""
    entries = _read_review_file(json_path, "--apply-ledger")
    if entries is None:
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
    ap.add_argument("--misses", action="store_true",
                    help="连库列出 delegate:false miss 复盘 (tiered 没接住的问法)")
    ap.add_argument("--list-routes", action="store_true", dest="list_routes",
                    help="连库列出 ai_promoted_routes 已晋升短语 (只读)")
    ap.add_argument(
        "--apply", metavar="JSON_FILE", default=None,
        help="人审后的 [{query, code|plan}] JSON -> 写入 ai_promoted_routes 晋升表 (连库)",
    )
    ap.add_argument(
        "--apply-ledger", metavar="JSON_FILE", default=None, dest="apply_ledger",
        help="人审后的 [{query, code}] JSON -> 写向量样例账本文件 (不连库, 非执行授权)",
    )
    ap.add_argument(
        "--scope", default="global",
        help="晋升可见范围: global (默认, 全租户) 或某个 factory_id",
    )
    ap.add_argument(
        "--source", default="manual_seed", choices=("manual_seed", "flywheel"),
        help="晋升来源标记 (写入 ai_promoted_routes.source)",
    )
    ap.add_argument(
        "--reviewed-by", default=None, dest="reviewed_by",
        help="人审签名, 写入 ai_promoted_routes.reviewed_by",
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
        asyncio.run(_apply_routes(
            args.apply, args.scope, args.source, args.reviewed_by))
    elif args.apply_ledger:
        _apply(args.apply_ledger)
    elif args.list_routes:
        asyncio.run(_list_routes(args.factory_id))
    elif args.list:
        asyncio.run(_list_candidates(
            args.min_confidence, args.min_count, args.limit, args.factory_id))
    elif args.misses:
        asyncio.run(_list_misses(args.limit, args.factory_id))
    else:
        ap.print_help()
