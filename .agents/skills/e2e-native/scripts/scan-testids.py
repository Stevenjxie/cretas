#!/usr/bin/env python3
"""
scan-testids.py — 扫描 React Native .tsx 文件中的 testID 覆盖率

用法:
    python3 scan-testids.py <src_dir> [--fix] [--csv output.csv]

扫描 6+ 种可交互组件类型，统计 testID 覆盖率。
"""

import os
import re
import sys
import csv
from pathlib import Path
from collections import defaultdict

# 需要 testID 的组件类型及其正则模式
COMPONENT_PATTERNS = {
    "TouchableOpacity": re.compile(r"<TouchableOpacity\b"),
    "TextInput": re.compile(r"<TextInput\b"),
    "Pressable": re.compile(r"<Pressable\b"),
    "ScrollView": re.compile(r"<ScrollView\b"),
    "NeoButton": re.compile(r"<NeoButton\b"),
    "Button(Paper)": re.compile(r"<Button\b(?!\.|\w)"),
    "IconButton": re.compile(r"<IconButton\b"),
    "FAB": re.compile(r"<FAB\b"),
    "FlatList": re.compile(r"<FlatList\b"),
    "SectionList": re.compile(r"<SectionList\b"),
}

# testID 属性匹配（检查当前标签是否已有 testID）
TESTID_PATTERN = re.compile(r"\btestID\s*=")
ACCESSIBILITY_LABEL_PATTERN = re.compile(r"\baccessibilityLabel\s*=")


def find_tsx_files(src_dir):
    """递归查找所有 .tsx 文件（排除 __tests__、node_modules）"""
    src_path = Path(src_dir)
    for f in sorted(src_path.rglob("*.tsx")):
        rel = str(f.relative_to(src_path))
        if "__tests__" in rel or "node_modules" in rel or ".test." in rel:
            continue
        yield f


def scan_file(filepath):
    """扫描单个文件，返回组件列表及其 testID 状态"""
    results = []
    try:
        content = filepath.read_text(encoding="utf-8")
        lines = content.split("\n")
    except Exception:
        return results

    for line_num, line in enumerate(lines, 1):
        for comp_type, pattern in COMPONENT_PATTERNS.items():
            if pattern.search(line):
                # 获取当前标签的完整内容（可能跨多行）
                tag_content = extract_tag(lines, line_num - 1)
                has_testid = bool(TESTID_PATTERN.search(tag_content))
                has_a11y_label = bool(ACCESSIBILITY_LABEL_PATTERN.search(tag_content))

                # 基于文件名和上下文推断建议的 testID
                suggested_name = suggest_testid(filepath, comp_type, line_num, tag_content)

                results.append({
                    "line": line_num,
                    "component": comp_type,
                    "has_testid": has_testid,
                    "has_a11y_label": has_a11y_label,
                    "suggested_testid": suggested_name,
                })
    return results


def extract_tag(lines, start_idx, max_lines=10):
    """提取从 start_idx 开始的 JSX 标签内容（最多 max_lines 行）"""
    tag_lines = []
    depth = 0
    for i in range(start_idx, min(start_idx + max_lines, len(lines))):
        tag_lines.append(lines[i])
        depth += lines[i].count("<") - lines[i].count("/>") - lines[i].count("</")
        if ">" in lines[i] and ("/>") in lines[i] or lines[i].strip().endswith(">"):
            break
    return "\n".join(tag_lines)


def suggest_testid(filepath, comp_type, line_num, tag_content):
    """基于文件名、组件类型和上下文推断 testID"""
    # 从文件路径提取屏幕名
    stem = filepath.stem.replace("Screen", "").replace("Enhanced", "")

    # 屏幕前缀缩写映射
    prefix_map = {
        "Login": "login",
        "FAHome": "fa-home",
        "DSHome": "ds-home",
        "WHHome": "wh-home",
        "QIHome": "qi-home",
        "SmartBIHome": "smartbi-home",
    }
    screen_prefix = prefix_map.get(stem, stem.lower().replace("_", "-"))

    # 组件类型缩写
    type_map = {
        "TouchableOpacity": "btn",
        "TextInput": "input",
        "Pressable": "btn",
        "ScrollView": "scroll",
        "NeoButton": "btn",
        "Button(Paper)": "btn",
        "IconButton": "icon-btn",
        "FAB": "fab",
        "FlatList": "list",
        "SectionList": "section-list",
    }
    type_suffix = type_map.get(comp_type, "elem")

    # 尝试从 onPress/placeholder/label 推断语义
    semantic = extract_semantic(tag_content)
    if semantic:
        return f"{screen_prefix}-{semantic}-{type_suffix}"
    else:
        return f"{screen_prefix}-line{line_num}-{type_suffix}"


def extract_semantic(tag_content):
    """从标签属性中推断语义名称"""
    # 尝试从 onPress handler 名称推断
    m = re.search(r"onPress\s*=\s*\{?\s*(\w+)", tag_content)
    if m:
        handler = m.group(1)
        # 移除常见前缀
        for prefix in ["handle", "on", "do"]:
            if handler.lower().startswith(prefix):
                handler = handler[len(prefix):]
        if handler:
            return camel_to_kebab(handler)

    # 尝试从 placeholder 推断
    m = re.search(r"placeholder\s*=\s*[{\"']([^\"'{}]+)", tag_content)
    if m:
        return m.group(1).lower().replace(" ", "-")[:20]

    return None


def camel_to_kebab(name):
    """camelCase → kebab-case"""
    s = re.sub(r"([A-Z])", r"-\1", name).lower().strip("-")
    return s[:30]  # 限制长度


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 scan-testids.py <src_dir> [--fix] [--csv output.csv]")
        sys.exit(1)

    src_dir = sys.argv[1]
    fix_mode = "--fix" in sys.argv
    csv_output = None
    if "--csv" in sys.argv:
        csv_idx = sys.argv.index("--csv")
        if csv_idx + 1 < len(sys.argv):
            csv_output = sys.argv[csv_idx + 1]

    if not os.path.isdir(src_dir):
        print(f"Error: Directory not found: {src_dir}")
        sys.exit(1)

    # 统计数据
    total_files = 0
    total_elements = 0
    total_with_testid = 0
    total_with_a11y = 0
    by_component = defaultdict(lambda: {"total": 0, "with_testid": 0})
    file_results = []

    for filepath in find_tsx_files(src_dir):
        total_files += 1
        results = scan_file(filepath)
        if not results:
            continue

        rel_path = str(filepath.relative_to(src_dir))
        file_elements = len(results)
        file_testids = sum(1 for r in results if r["has_testid"])

        total_elements += file_elements
        total_with_testid += file_testids
        total_with_a11y += sum(1 for r in results if r["has_a11y_label"])

        for r in results:
            by_component[r["component"]]["total"] += 1
            if r["has_testid"]:
                by_component[r["component"]]["with_testid"] += 1

        coverage = f"{file_testids}/{file_elements}"
        pct = f"{file_testids/file_elements*100:.0f}%" if file_elements > 0 else "N/A"
        file_results.append({
            "file": rel_path,
            "elements": file_elements,
            "testids": file_testids,
            "coverage": pct,
            "details": results,
        })

    # 输出结果
    overall_pct = f"{total_with_testid/total_elements*100:.1f}%" if total_elements > 0 else "N/A"

    print(f"## testID 覆盖率报告\n")
    print(f"扫描目录: {src_dir}")
    print(f"扫描文件: {total_files}")
    print(f"可交互元素总数: {total_elements}")
    print(f"已有 testID: {total_with_testid}")
    print(f"已有 accessibilityLabel: {total_with_a11y}")
    print(f"覆盖率: {overall_pct}\n")

    # 按组件类型统计
    print("### 按组件类型\n")
    print("| 组件类型 | 总数 | 已有 testID | 覆盖率 |")
    print("|---------|------|------------|--------|")
    for comp in sorted(by_component.keys()):
        stats = by_component[comp]
        pct = f"{stats['with_testid']/stats['total']*100:.0f}%" if stats["total"] > 0 else "N/A"
        print(f"| {comp} | {stats['total']} | {stats['with_testid']} | {pct} |")

    # 按文件统计（仅显示有元素的文件，按覆盖率排序）
    print("\n### 按文件（有可交互元素的文件）\n")
    print("| 文件 | 可交互元素 | 已有 testID | 覆盖率 |")
    print("|------|-----------|------------|--------|")
    for fr in sorted(file_results, key=lambda x: x["testids"] / max(x["elements"], 1)):
        print(f"| {fr['file']} | {fr['elements']} | {fr['testids']} | {fr['coverage']} |")

    # --fix 模式：输出建议的 testID
    if fix_mode:
        print("\n### 建议的 testID（--fix 模式）\n")
        print("| 文件 | 行号 | 组件 | 建议 testID |")
        print("|------|------|------|------------|")
        for fr in file_results:
            for detail in fr["details"]:
                if not detail["has_testid"]:
                    print(f"| {fr['file']} | {detail['line']} | {detail['component']} | `{detail['suggested_testid']}` |")

    # CSV 输出
    if csv_output:
        with open(csv_output, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["file", "line", "component", "has_testid", "has_a11y_label", "suggested_testid"])
            for fr in file_results:
                for detail in fr["details"]:
                    writer.writerow([
                        fr["file"],
                        detail["line"],
                        detail["component"],
                        detail["has_testid"],
                        detail["has_a11y_label"],
                        detail["suggested_testid"],
                    ])
        print(f"\nCSV 输出: {csv_output}")


if __name__ == "__main__":
    main()
