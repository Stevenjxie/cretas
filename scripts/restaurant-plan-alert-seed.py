"""餐饮预警规则 seed CLI (spec §3.1 卡 C1 — 预警计划化 P1).

预警是**会反复触发的长期承诺**: 一条配错的规则会持续给老板发短信。所以规则
来源与 `ai_promoted_routes` 的 `manual_seed` 保持同一姿态 —— 人工审核后显式
`--apply` 才落表, 没有任何自动抽取/自动毕业路径。web-admin 配置页是 P3。

用法:
  python scripts/restaurant-plan-alert-seed.py --list --factory-id RES_3101_009
      只读列出该租户已配置的预警规则。

  python scripts/restaurant-plan-alert-seed.py --apply rules.json --factory-id RES_3101_009
      rules.json 形如:
        [
          {
            "rule_code": "monthly_revenue_drop",
            "rule_name": "营收环比下滑预警",
            "query_text": "这个月营收比上个月怎么样",
            "code": "RESTAURANT_OPS_SALES_SUMMARY",
            "metric_path": "comparison.revenue_change_pct",
            "threshold_op": "lt",
            "threshold_value": -15,
            "severity": "warning"
          }
        ]
      给 "code" 时自动生成最小完整计划 (default_seed_plan); 也可以直接给
      "plan" = 完整 planner 计划 JSON。

  python scripts/restaurant-plan-alert-seed.py --disable monthly_revenue_drop --factory-id RES_...
  python scripts/restaurant-plan-alert-seed.py --enable  monthly_revenue_drop --factory-id RES_...
      停用 / 启用一条规则 (不删行, 保留审计痕迹)。

【硬性约束, 写入前逐条校验, 不通过就拒收并打印原因】
  1. plan 里的时间必须是**相对描述** (time_range: null 或相对短语), 绝不能是
     具体日期 —— 存了具体日期会在第二天被原样回放, 对着旧窗口发预警。
  2. plan 必须编译得出可执行契约 (走和运行时同一个 _replay_plan_spec)。
  3. P1 只支持 RESTAURANT_OPS_SALES_SUMMARY (其余 intent 的 resolver 没有稳定
     数值出口 —— 见 plan_alert.py 模块 docstring 的 kpis 六重不稳定说明)。
  4. metric_path 必须在 P1 白名单内 (comparison.*)。
  5. severity 只能是 critical / warning ('info' 在 Java 桥接里会被跳过, 存一条
     永远不触发的规则等于骗人)。

连 prod DB: prod smartbi 库不对公网开放, 用 SSH 隧道 (同
scripts/restaurant-intent-promote.py 的说明), 本脚本不读取也不打印任何密码。
"""
import argparse
import asyncio
import json
import sys
from pathlib import Path

_BACKEND_PY = Path(__file__).resolve().parents[1] / "backend" / "python"
sys.path.insert(0, str(_BACKEND_PY))

from smartbi.gold.restaurant.plan_alert import (  # noqa: E402
    RuleUnavailable,
    compile_rule_spec,
)

_INTERNAL_SENTINEL = "__internal__"
_VALID_OPS = ("lt", "lte", "gt", "gte")
_VALID_SEVERITY = ("critical", "warning")


async def _open_pool():
    from smartbi.config import get_pg_pool

    try:
        pool = await get_pg_pool()
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR: 连不上 smartbi DB ({exc})。需要 SSH 隧道 + POSTGRES_* 环境变量。")
        return None
    if pool is None:
        print("ERROR: smartbi DB 未配置 (postgres_url 为空)。")
        return None
    return pool


def _validate(entry: dict, factory_id: str) -> tuple[dict | None, str | None]:
    """返回 (规范化后的行, 拒收原因)。两者恰有一个非 None。"""
    from smartbi.gold.restaurant.restaurant_intent_promotion import default_seed_plan

    rule_code = str(entry.get("rule_code") or "").strip()
    if not rule_code:
        return None, "缺少 rule_code"

    plan = entry.get("plan")
    code = str(entry.get("code") or "").strip()
    if plan is None and code:
        plan = default_seed_plan(code)
    if not isinstance(plan, dict):
        return None, "缺少 plan 或 code"

    # 硬约束 1: 绝不存具体日期 (与 _plan_rejection_reason 同判据)
    if '"date_range"' in json.dumps(plan, ensure_ascii=False):
        return None, "plan 含已解析日期 (date_range), 只允许相对时间"

    query_text = str(entry.get("query_text") or "").strip()
    if not query_text:
        return None, "缺少 query_text (resolver 需要问句原文上下文)"

    metric_path = str(entry.get("metric_path") or "").strip()
    op = str(entry.get("threshold_op") or "").strip()
    severity = str(entry.get("severity") or "warning").strip()
    if op not in _VALID_OPS:
        return None, f"threshold_op 必须是 {_VALID_OPS} 之一, 得到 {op!r}"
    if severity not in _VALID_SEVERITY:
        return None, f"severity 必须是 {_VALID_SEVERITY} 之一, 得到 {severity!r}"
    try:
        threshold_value = float(entry["threshold_value"])
    except (KeyError, TypeError, ValueError):
        return None, "threshold_value 缺失或不是数值"

    row = {
        "factory_id": factory_id,
        "rule_code": rule_code,
        "rule_name": str(entry.get("rule_name") or rule_code),
        "query_text": query_text,
        "plan_json": plan,
        "plan_version": "restaurant-query-plan-v2",
        "metric_path": metric_path,
        "threshold_op": op,
        "threshold_value": threshold_value,
        "severity": severity,
    }

    # 硬约束 2/3/4: 走运行时同一条编译+白名单路径, 编不出来就别写进去
    try:
        compile_rule_spec(row)
    except RuleUnavailable as exc:
        return None, exc.reason
    except Exception as exc:  # noqa: BLE001
        return None, f"计划编译异常: {exc}"

    from smartbi.gold.restaurant.plan_alert import _ALLOWED_METRIC_PATHS

    if metric_path not in _ALLOWED_METRIC_PATHS:
        return None, (
            f"metric_path 不在 P1 白名单内: {metric_path!r}; "
            f"可用: {sorted(_ALLOWED_METRIC_PATHS)}"
        )
    return row, None


async def _apply(path: str, factory_id: str) -> None:
    entries = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(entries, list):
        print("ERROR: rules.json 顶层必须是数组")
        return

    accepted, skipped = [], []
    for entry in entries:
        row, reason = _validate(entry if isinstance(entry, dict) else {}, factory_id)
        if reason:
            skipped.append({"entry": entry, "reason": reason})
        else:
            accepted.append(row)

    for s in skipped:
        code = (s["entry"] or {}).get("rule_code", "<no rule_code>")
        print(f"  SKIP {code}: {s['reason']}")

    if not accepted:
        print(f"没有可写入的规则 (拒收 {len(skipped)} 条)。")
        return

    pool = await _open_pool()
    if pool is None:
        return

    async with pool.acquire() as conn:
        async with conn.transaction():
            # RLS: 规则是 per-tenant 对象, 钉到目标租户即可 (不需要 __internal__)。
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, true)", factory_id
            )
            for row in accepted:
                await conn.execute(
                    """
                    INSERT INTO restaurant_plan_alert_rules
                        (factory_id, rule_code, rule_name, query_text, plan_json,
                         plan_version, metric_path, threshold_op, threshold_value,
                         severity, source, reviewed_by)
                    VALUES ($1,$2,$3,$4,$5::jsonb,$6,$7,$8,$9,$10,'manual_seed',$11)
                    ON CONFLICT (factory_id, rule_code) DO UPDATE
                       SET rule_name       = EXCLUDED.rule_name,
                           query_text      = EXCLUDED.query_text,
                           plan_json       = EXCLUDED.plan_json,
                           plan_version    = EXCLUDED.plan_version,
                           metric_path     = EXCLUDED.metric_path,
                           threshold_op    = EXCLUDED.threshold_op,
                           threshold_value = EXCLUDED.threshold_value,
                           severity        = EXCLUDED.severity,
                           reviewed_by     = EXCLUDED.reviewed_by,
                           updated_at      = now()
                    """,
                    row["factory_id"], row["rule_code"], row["rule_name"],
                    row["query_text"],
                    json.dumps(row["plan_json"], ensure_ascii=False, sort_keys=True),
                    row["plan_version"], row["metric_path"], row["threshold_op"],
                    row["threshold_value"], row["severity"], "seed-cli",
                )
                print(f"  OK   {row['rule_code']}  {row['metric_path']} "
                      f"{row['threshold_op']} {row['threshold_value']} [{row['severity']}]")
    print(f"写入 {len(accepted)} 条, 拒收 {len(skipped)} 条。")


async def _list(factory_id: str) -> None:
    pool = await _open_pool()
    if pool is None:
        return
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, true)", factory_id
            )
            rows = await conn.fetch(
                """
                SELECT rule_code, rule_name, metric_path, threshold_op,
                       threshold_value, severity, enabled, plan_version,
                       plan_json->>'intent' AS intent, reviewed_by, updated_at
                  FROM restaurant_plan_alert_rules
                 WHERE factory_id = $1
                 ORDER BY rule_code
                """,
                factory_id,
            )
    if not rows:
        print(f"{factory_id} 还没有配置任何预警规则。")
        return
    print(f"{factory_id} 共 {len(rows)} 条预警规则:")
    for r in rows:
        state = "启用" if r["enabled"] else "停用"
        print(
            f"  [{state}] {r['rule_code']:<28} {r['intent']:<32} "
            f"{r['metric_path']} {r['threshold_op']} {r['threshold_value']} "
            f"[{r['severity']}] v={r['plan_version']} by={r['reviewed_by']}"
        )


async def _toggle(rule_code: str, factory_id: str, enabled: bool) -> None:
    pool = await _open_pool()
    if pool is None:
        return
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, true)", factory_id
            )
            result = await conn.execute(
                """
                UPDATE restaurant_plan_alert_rules
                   SET enabled = $3, updated_at = now()
                 WHERE factory_id = $1 AND rule_code = $2
                """,
                factory_id, rule_code, enabled,
            )
    print(f"{'启用' if enabled else '停用'} {rule_code}: {result}")


def main() -> None:
    ap = argparse.ArgumentParser(description="餐饮预警规则 seed CLI (人工审核后 --apply)")
    ap.add_argument("--factory-id", dest="factory_id", required=True,
                    help="目标租户 factoryId (预警规则永远是 per-tenant)")
    ap.add_argument("--list", action="store_true", help="只读列出该租户的规则")
    ap.add_argument("--apply", metavar="RULES_JSON",
                    help="人审过的规则 JSON 文件, 写入 restaurant_plan_alert_rules")
    ap.add_argument("--enable", metavar="RULE_CODE", help="启用一条规则")
    ap.add_argument("--disable", metavar="RULE_CODE", help="停用一条规则")
    args = ap.parse_args()

    if args.factory_id == _INTERNAL_SENTINEL:
        print("ERROR: 不允许用 __internal__ 作为 factory_id 写规则 "
              "(那是跨租户万能钥匙, 见迁移文件的警告块)。")
        sys.exit(2)

    if args.apply:
        asyncio.run(_apply(args.apply, args.factory_id))
    elif args.enable:
        asyncio.run(_toggle(args.enable, args.factory_id, True))
    elif args.disable:
        asyncio.run(_toggle(args.disable, args.factory_id, False))
    elif args.list:
        asyncio.run(_list(args.factory_id))
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
