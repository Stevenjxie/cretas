"""时间词语料人工晋升 CLI —— Task 3 (`.superpowers/sdd/2026-08-16-时间词语料与晋升/`)。

## 这个 CLI 不做什么(承重, 见 `--help` 里那句)

`_resolve_sales_date_range` 是 237 行 / 38 个 `if` 分支的手写解析器,
模块里没有可追加的时间词常量表。「晋升」= **人手写一个分支 + 补测试 + 发版**。

⇒ 本工具只做两件事: 告诉你该处理哪些词 (`--list`), 记下你已经处理了哪些
(`--mark-promoted`)。它**不会**、也**不能**让任何词自动生效 —— 一个让人
误以为「跑一下就生效了」的工具, 比没有工具更糟。

`--counts` 是第三个动作, 给跑批脚本
`scripts/cron/time-phrase-backlog-daily.sh` 读, 不是给人读的。

## 连接方式(照抄既有约定, 不新发明)

`_get_pool()` 与 `smartbi/scripts/load_void_demo_rest.py` 的 `_get_pool(dsn)`
同一形状: 默认用 `smartbi.config.get_pg_pool()`(与线上服务共用的那个 asyncpg
池, 读 `POSTGRES_*` 环境变量), `--dsn` 可显式覆盖。CLI 参数形状(list-pending
式动作 + argparse)照抄 `smartbi/scripts/confirm_store_alias.py`。

`ai_time_phrase_corpus` 本身**没有 RLS**(见迁移文件的注释), 不需要
`set_config('app.factory_id', …)` 这一步。
"""
from __future__ import annotations

import argparse
import asyncio
import sys
from typing import Any, Dict, List, Optional

#: `--help` 的硬要求文案(设计卡逐字给定, ⛔ 不要改写/精简)。
#: M1 变异对照: 删掉这段里的「本工具不会修改任何解析代码」⇒ 测试预期红。
HELP_EPILOG = (
    "⛔ 本工具不会修改任何解析代码。`_resolve_sales_date_range` 是 237 行 /\n"
    "   38 个 if 分支的手写解析器, 没有可追加的词表 —— 「晋升」是**人手写一个\n"
    "   分支 + 补测试 + 发版**。本工具只做两件事: 告诉你该处理哪些词,\n"
    "   以及记下你已经处理了哪些。"
)


def format_counts_line(domain: str, counts: Dict[str, int]) -> str:
    """`--counts` 的输出契约。

    🔴 裁定 B(形态 D): `scripts/cron/time-phrase-backlog-daily.sh` 用
    `grep -oP 'total=\\K[0-9]+'` / `grep -oP 'unpromoted=\\K[0-9]+'` 解析
    这一行的输出。改这里的格式(哪怕只是 `total=` 改成 `total:`)必须同时
    改那两条 grep 模式 —— `tests/test_time_phrase_backlog_gate.py` 直接从
    `.sh` 文件里读出这两条模式来解析这个函数的输出, 一份断言钉两边,
    不许各写各的期待值。
    """
    return f"domain={domain} total={counts['total']} unpromoted={counts['unpromoted']}"


def format_unpromoted_row(row: Dict[str, Any]) -> str:
    return (
        f"  [hit={row.get('hit_count')}] {row.get('normalized_phrase')!r}"
        f"  factory={row.get('factory_id')}  last_seen={row.get('last_seen_at')}\n"
        f"      raw_query={row.get('raw_query')!r}"
        f"  llm_phrase={row.get('llm_phrase')!r}"
        f"  llm_time_range={row.get('llm_time_range')!r}"
    )


async def _get_pool(dsn: Optional[str]):
    """默认走线上服务同一个池(`get_pg_pool()`, 读 `POSTGRES_*` 环境变量)。
    `--dsn` 显式覆盖 —— 与 `load_void_demo_rest.py:_get_pool` 同一形状。
    """
    if dsn:
        import asyncpg
        return await asyncpg.create_pool(dsn, min_size=1, max_size=2, timeout=10)
    from smartbi.config import get_pg_pool
    return await get_pg_pool()


async def cmd_list(pool, *, domain: str, limit: int) -> int:
    from smartbi.gold.restaurant.time_phrase_corpus import list_unpromoted
    rows: List[Dict[str, Any]] = await list_unpromoted(pool, domain=domain, limit=limit)
    if not rows:
        print(f"domain={domain} 没有待晋升的语料。")
        return 0
    print(f"domain={domain} 待晋升语料 {len(rows)} 条(最近命中排前面):")
    for row in rows:
        print(format_unpromoted_row(row))
    return 0


async def cmd_mark_promoted(pool, *, domain: str, phrase: str, by: str, note: str) -> int:
    from smartbi.gold.restaurant.time_phrase_corpus import mark_promoted
    ok = await mark_promoted(
        pool, domain=domain, normalized_phrase=phrase, reviewed_by=by, note=note,
    )
    if not ok:
        print(
            f"没有更新到任何行: domain={domain} normalized_phrase={phrase!r} "
            f"—— 短语不存在, 或已经晋升过了(用 --list 核对)。",
            file=sys.stderr,
        )
        return 1
    print(f"已记录晋升: domain={domain} phrase={phrase!r} by={by!r} note={note!r}")
    print(
        "⛔ 提醒: 这条记录只是留痕, 不会让 _resolve_sales_date_range 认得这个词 —— "
        "对应的分支还需要单独写代码 + 补测试 + 发版。"
    )
    return 0


async def cmd_counts(pool, *, domain: str) -> int:
    from smartbi.gold.restaurant.time_phrase_corpus import corpus_counts
    counts = await corpus_counts(pool, domain=domain)
    print(format_counts_line(domain, counts))
    return 0


async def main_async(args: argparse.Namespace, *, pool=None) -> int:
    """`pool` 可由调用方(测试)直接注入 —— 绕开 `_get_pool()`/DSN, 照
    `tests/test_time_phrase_corpus.py` 的 `_FakePool`/`_FakeConn` 用法接假池,
    ⛔ 不连真库。
    """
    chosen = [name for name, flag in (
        ("list", args.list),
        ("mark_promoted", bool(args.mark_promoted)),
        ("counts", args.counts),
    ) if flag]
    if len(chosen) != 1:
        print(
            "需恰好指定 --list / --mark-promoted / --counts 之一, 见 --help。",
            file=sys.stderr,
        )
        return 2
    if chosen[0] == "mark_promoted" and (not args.by or not args.note):
        print(
            "--mark-promoted 需要同时给 --by 和 --note —— 登记是留痕, 不是打勾。",
            file=sys.stderr,
        )
        return 2

    owns_pool = pool is None
    if owns_pool:
        try:
            pool = await _get_pool(args.dsn)
        except Exception as exc:  # noqa: BLE001 — 连接失败一律 rc=2, 让跑批读到「没量到」
            print(f"拿不到数据库连接池: {exc}", file=sys.stderr)
            return 2
        if pool is None:
            print(
                "拿不到数据库连接池 —— 检查 POSTGRES_* 环境变量, 或显式传 --dsn。",
                file=sys.stderr,
            )
            return 2

    try:
        if chosen[0] == "list":
            return await cmd_list(pool, domain=args.domain, limit=args.limit)
        if chosen[0] == "mark_promoted":
            return await cmd_mark_promoted(
                pool, domain=args.domain, phrase=args.mark_promoted,
                by=args.by, note=args.note,
            )
        return await cmd_counts(pool, domain=args.domain)
    except Exception as exc:  # noqa: BLE001 — 查询失败(库/表不可达等)一律 rc=2
        print(f"查询失败: {exc}", file=sys.stderr)
        return 2
    finally:
        if owns_pool:
            try:
                await pool.close()
            except Exception:  # noqa: BLE001 — 收尾失败不该盖掉真正的返回码
                pass


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m smartbi.scripts.time_phrase_corpus_cli",
        description=(
            "时间词语料人工晋升 CLI —— 列出规则解不出、LLM 认出的时间说法, "
            "记下哪些已经人工处理过。"
        ),
        epilog=HELP_EPILOG,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--domain", default="restaurant",
        help="语料所属领域, 默认 restaurant(目前唯一在用的值)。",
    )
    parser.add_argument(
        "--list", action="store_true",
        help="列出还没人工晋升的语料(最近命中的排前面)。",
    )
    parser.add_argument(
        "--mark-promoted", metavar="NORMALIZED_PHRASE", default=None,
        help="登记该规范化短语已经人工晋升(需同时给 --by 和 --note)。",
    )
    parser.add_argument(
        "--by", default=None,
        help="--mark-promoted 时: 谁做的这次晋升。",
    )
    parser.add_argument(
        "--note", default=None,
        help="--mark-promoted 时: 加了哪个分支 / 为什么这么处理。",
    )
    parser.add_argument(
        "--counts", action="store_true",
        help="打印 total=/unpromoted= 计数(机器可读, 供跑批脚本 grep 解析, 不是给人看的)。",
    )
    parser.add_argument(
        "--limit", type=int, default=50,
        help="--list 最多列多少条, 默认 50。",
    )
    parser.add_argument(
        "--dsn", default=None,
        help="显式 Postgres DSN, 覆盖基于 POSTGRES_* 环境变量的连接池。",
    )
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    sys.exit(asyncio.run(main_async(args)))


if __name__ == "__main__":
    main()
