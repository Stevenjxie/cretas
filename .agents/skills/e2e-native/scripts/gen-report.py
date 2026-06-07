#!/usr/bin/env python3
"""
gen-report.py — 聚合 Maestro 测试结果生成 Markdown 报告

用法:
    python3 gen-report.py <reports_dir> [--output report.md]

读取 reports/ 目录下的 JSON 文件，生成结构化的测试报告。
"""

import json
import os
import sys
from datetime import datetime
from pathlib import Path
from collections import defaultdict


def load_reports(reports_dir):
    """加载所有 JSON 报告文件"""
    reports = []
    reports_path = Path(reports_dir)

    for f in sorted(reports_path.glob("report_*.json"), reverse=True):
        try:
            with open(f, "r", encoding="utf-8") as fh:
                data = json.load(fh)
                data["_file"] = str(f)
                reports.append(data)
        except (json.JSONDecodeError, IOError) as e:
            print(f"Warning: 无法读取 {f}: {e}", file=sys.stderr)

    return reports


def generate_report(reports, output_file=None):
    """生成 Markdown 报告"""
    if not reports:
        print("## E2E Native 测试报告\n")
        print("**状态**: 暂无测试结果\n")
        print("请先运行 `/e2e-native run {role}` 执行测试。")
        return

    # 使用最新一批报告（相同 timestamp 前缀的）
    latest = reports[0]
    latest_ts = latest.get("timestamp", "unknown")

    # 收集同批次的所有报告
    batch_reports = [r for r in reports if r.get("timestamp", "").startswith(latest_ts[:8])]
    if not batch_reports:
        batch_reports = [latest]

    # 汇总统计
    total_flows = sum(r.get("total", 0) for r in batch_reports)
    total_passed = sum(r.get("passed", 0) for r in batch_reports)
    total_failed = sum(r.get("failed", 0) for r in batch_reports)
    total_duration = 0

    all_results = []
    by_role = defaultdict(lambda: {"total": 0, "passed": 0, "failed": 0, "duration": 0})

    for report in batch_reports:
        role = report.get("role", "unknown")
        for result in report.get("results", []):
            duration = result.get("duration", 0)
            total_duration += duration
            status = result.get("status", "unknown")

            by_role[role]["total"] += 1
            by_role[role]["duration"] += duration
            if status == "pass":
                by_role[role]["passed"] += 1
            else:
                by_role[role]["failed"] += 1

            all_results.append({
                "role": role,
                "flow": result.get("flow", "unknown"),
                "status": status,
                "duration": duration,
            })

    # 生成报告
    lines = []
    lines.append("## E2E Native 测试报告\n")
    lines.append(f"**日期**: {datetime.now().strftime('%Y-%m-%d %H:%M')}")
    lines.append(f"**设备**: {latest.get('device', 'unknown')}")
    lines.append(f"**Maestro**: {latest.get('maestro_version', 'unknown')}")
    lines.append("")

    # 总览
    lines.append("### 总览\n")
    lines.append("| 指标 | 值 |")
    lines.append("|------|-----|")
    lines.append(f"| 总流程数 | {total_flows} |")
    lines.append(f"| 通过 | {total_passed} |")
    lines.append(f"| 失败 | {total_failed} |")
    lines.append(f"| 通过率 | {total_passed/total_flows*100:.0f}% |" if total_flows > 0 else "| 通过率 | N/A |")
    lines.append(f"| 总耗时 | {total_duration}s |")
    lines.append("")

    # 按角色
    lines.append("### 按角色\n")
    lines.append("| 角色 | 流程 | 通过 | 失败 | 耗时 |")
    lines.append("|------|------|------|------|------|")
    for role in sorted(by_role.keys()):
        stats = by_role[role]
        status_emoji = "✅" if stats["failed"] == 0 else "❌"
        lines.append(f"| {role} {status_emoji} | {stats['total']} | {stats['passed']} | {stats['failed']} | {stats['duration']}s |")
    lines.append("")

    # 所有流程详情
    lines.append("### 流程详情\n")
    lines.append("| 角色 | Flow | 状态 | 耗时 |")
    lines.append("|------|------|------|------|")
    for r in all_results:
        status_str = "PASS ✅" if r["status"] == "pass" else "FAIL ❌"
        lines.append(f"| {r['role']} | {r['flow']} | {status_str} | {r['duration']}s |")
    lines.append("")

    # 失败详情
    failed_results = [r for r in all_results if r["status"] != "pass"]
    if failed_results:
        lines.append("### 失败详情\n")
        lines.append("| Flow | 角色 | 耗时 | 可能原因 |")
        lines.append("|------|------|------|---------|")
        for r in failed_results:
            lines.append(f"| {r['flow']} | {r['role']} | {r['duration']}s | 检查 screenshots/ 目录 |")
        lines.append("")
        lines.append("> 排查建议:")
        lines.append("> 1. 查看截图: `tests/e2e-native/screenshots/`")
        lines.append("> 2. 检查 testID: `/e2e-native scan`")
        lines.append("> 3. 查看 View 树: `maestro hierarchy`")
        lines.append("> 4. 手动验证: `maestro studio`")
        lines.append("")

    # 历史趋势（如果有多次运行）
    if len(reports) > 1:
        lines.append("### 历史趋势\n")
        lines.append("| 日期 | 角色 | 通过/总数 | 通过率 |")
        lines.append("|------|------|----------|--------|")
        for report in reports[:10]:  # 最近 10 次
            ts = report.get("timestamp", "unknown")
            role = report.get("role", "unknown")
            total = report.get("total", 0)
            passed = report.get("passed", 0)
            pct = f"{passed/total*100:.0f}%" if total > 0 else "N/A"
            lines.append(f"| {ts} | {role} | {passed}/{total} | {pct} |")
        lines.append("")

    report_text = "\n".join(lines)

    # 输出
    print(report_text)

    if output_file:
        with open(output_file, "w", encoding="utf-8") as f:
            f.write(report_text)
        print(f"\n报告已保存: {output_file}")


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 gen-report.py <reports_dir> [--output report.md]")
        sys.exit(1)

    reports_dir = sys.argv[1]
    output_file = None
    if "--output" in sys.argv:
        idx = sys.argv.index("--output")
        if idx + 1 < len(sys.argv):
            output_file = sys.argv[idx + 1]

    if not os.path.isdir(reports_dir):
        print(f"Error: Directory not found: {reports_dir}")
        sys.exit(1)

    reports = load_reports(reports_dir)
    generate_report(reports, output_file)


if __name__ == "__main__":
    main()
