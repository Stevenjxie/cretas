"""Phase 4: populate the SmartBI meta/tool tables for a factory demo tenant so the
内部工具 pages render data (Excel上传 / AI追问日志 / 查询模板). 数据完整度 / 分析概览 /
知识库反馈 compute from existing data and need no extra rows.

These are in smartbi_prod_db (FORCE RLS on some) → run with a BYPASSRLS smartbi DSN.
  PYTHONPATH=. CLONE_SMARTBI_SUPER_DSN=... python -m scripts.demo.gen_factory_phase4 --factory DEMO_FACTORY2
"""
from __future__ import annotations
import argparse, asyncio, datetime
import asyncpg
from scripts.demo import clone_config as cfg

TEMPLATES = [
    ("本月各产品产量排行", "生产分析", "按产量降序展示本月已完工产品", "查询本月各产品的生产数量并按产量从高到低排序"),
    ("出成率低于阈值的工序", "生产分析", "识别出成率异常工序", "列出出成率低于标准值的工序及对应产品"),
    ("客户应收账款账龄分析", "财务分析", "按账龄区间汇总各客户应收账款余额", "按账龄 30/60/90 天汇总各客户应收账款余额"),
    ("各产品毛利率对比", "财务分析", "产品盈利能力分析", "计算各产品的毛利率并对比"),
    ("供应商采购金额TOP10", "自定义", "采购集中度分析", "按采购金额列出前10大供应商"),
    ("原料损耗率趋势", "自定义", "损耗监控", "按月统计原料损耗率变化趋势"),
]
# (query, answer, feedback 1/-1/None, hours_ago)
FALLBACK = [
    ("上个月猪舌产量是多少", "上月轻卤门腔(猪舌)120g 累计产量约 1.05 万盒, 是产量最高的产品。", 1, 2),
    ("哪个产品毛利最高", "按当前成本测算, 椒麻掌中宝 120g 毛利率最高。", 1, 5),
    ("卤猪蹄合格率最近怎么样", "卤猪蹄(去大骨)200g 近期合格率 91%, 低于阈值, 已触发预警。", None, 24),
    ("哪些客户应收逾期了", "当前有 2 笔应收接近账龄上限, 建议优先跟进。", 1, 48),
    ("气调包装线效率为什么低", "OEE 68% 低于阈值, 主因换型停机偏多, 建议优化排产批量。", -1, 72),
]
# (file_name, sheet, type, rows, cols, days_ago)
UPLOADS = [
    ("2026年5月财务报表.xlsx", "利润表", "FINANCE", 186, 12, 10),
    ("2026年5月生产日报汇总.xlsx", "日报", "PRODUCTION", 312, 9, 10),
    ("供应商采购明细_Q2.xlsx", "采购明细", "PURCHASE", 98, 11, 6),
    ("客户应收对账单_6月.xlsx", "应收", "FINANCE", 64, 8, 2),
]


async def run(factory_id: str):
    s = await asyncpg.connect(cfg.SMARTBI_SUPER_DSN)
    now = datetime.datetime.now()
    try:
        await s.execute("DELETE FROM smart_bi_query_templates WHERE factory_id=$1", factory_id)
        for name, cat, desc, q in TEMPLATES:
            await s.execute(
                "INSERT INTO smart_bi_query_templates (factory_id, name, category, description, query_template, "
                "created_at, updated_at) VALUES ($1,$2,$3,$4,$5,$6,$6)", factory_id, name, cat, desc, q, now)

        await s.execute("DELETE FROM smart_bi_llm_fallback_log WHERE factory_id=$1", factory_id)
        for q, ans, fb, hrs in FALLBACK:
            await s.execute(
                "INSERT INTO smart_bi_llm_fallback_log (factory_id, query, answer, source, total_wall_ms, "
                "llm_wall_ms, user_feedback, created_at) VALUES ($1,$2,$3,'LLM_FALLBACK',$4,$5,$6,$7)",
                factory_id, q, ans, 1800 + hrs, 1400 + hrs, fb, now - datetime.timedelta(hours=hrs))

        await s.execute("DELETE FROM smart_bi_pg_excel_uploads WHERE factory_id=$1", factory_id)
        for fn, sheet, tt, rc, cc, days in UPLOADS:
            await s.execute(
                "INSERT INTO smart_bi_pg_excel_uploads (factory_id, file_name, sheet_name, detected_table_type, "
                "row_count, column_count, upload_status, created_at, updated_at) "
                "VALUES ($1,$2,$3,$4,$5,$6,'SUCCESS',$7,$8)",
                factory_id, fn, sheet, tt, rc, cc, now - datetime.timedelta(days=days), now)

        t = await s.fetchval("SELECT count(*) FROM smart_bi_query_templates WHERE factory_id=$1", factory_id)
        f = await s.fetchval("SELECT count(*) FROM smart_bi_llm_fallback_log WHERE factory_id=$1", factory_id)
        u = await s.fetchval("SELECT count(*) FROM smart_bi_pg_excel_uploads WHERE factory_id=$1", factory_id)
        print(f"[phase4] {factory_id}: query_templates={t} fallback_log={f} excel_uploads={u}")
    finally:
        await s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--factory", required=True)
    a = ap.parse_args()
    asyncio.run(run(a.factory))


if __name__ == "__main__":
    main()
