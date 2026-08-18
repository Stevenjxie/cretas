#!/usr/bin/env python3
"""Java 全量测试的**名单棘轮** —— 已知红只许变少，⛔ 不许变多。

## 为什么需要它

2026-08-18 实测：`origin/main` 上有 **19 个红的测试方法**（8 Failures + 11 Errors），
分布在 8 个测试类里，全部集中在采购 / 销售发货 / 生产结算这几条写路径上。

它们能一直红着没人发现，是因为**没有任何自动触发会执行它们**：

```
ci.yml:121   mvn -B test      if: inputs.full_audit    ← 只有手动全量审计才跑
ci.yml:309   mvn -B verify    if: inputs.full_audit    ← 同上
ci.yml:183   mvn -B package -Dmaven.test.skip=true     ← push / PR 上跑的是这条
ci.yml:26    # full_audit stays manual -- the whole suite takes over an hour
```

⇒ **PR 上那个绿 = 编译并打包成功，⛔ 不是测试通过。**

## ⛔ 为什么是「名单」棘轮而不是「计数」棘轮

计数会掩盖：修好一个、同时弄坏一个，总数不变，闸全绿。
名单不会：新出现的那个名字不在基线里，立刻红。

## 三态退出码（与本仓硬约束 4 同源）

```
rc=0   失败集合 ⊆ 基线            没有新增红
rc=1   出现基线里没有的失败        有新增红（读数有效，且指向缺陷）
rc=2   这次没量到                 编译失败 / 一个测试都没跑 / 日志解析不出结果
```

⚠️ **`rc=2` 必须与 `rc=1` 用不同措辞告警** —— 否则「没量到」会被折叠进「没问题」，
一个连编译都过不去的跑批会安静地天天绿，而它一个测试都没看过。

## 用法

```bash
# 从 surefire 报告目录读结果, 与基线比对
python scripts/ci/java-test-ratchet.py \
    --reports backend/java/cretas-api/target/surefire-reports \
    --baseline backend/java/cretas-api/known-failing-tests.txt

# 重新生成基线（只在【确认新增的红都已修完】之后手工跑）
python scripts/ci/java-test-ratchet.py --reports ... --baseline ... --write-baseline
```
"""
from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET

RC_OK, RC_NEW_FAILURES, RC_NOT_MEASURED = 0, 1, 2


def collect(reports_dir: str) -> tuple[set[str], int]:
    """返回 (失败的 Class.method 集合, 总共跑了多少个测试)。

    ⚠️ 读 surefire 的 XML，⛔ 不解析 mvn 的控制台输出 ——
    控制台会被编码问题弄成乱码（本仓实测过 GBK 日志），XML 是结构化的。
    """
    failing: set[str] = set()
    total = 0
    if not os.path.isdir(reports_dir):
        return failing, 0
    for name in sorted(os.listdir(reports_dir)):
        if not (name.startswith("TEST-") and name.endswith(".xml")):
            continue
        path = os.path.join(reports_dir, name)
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        total += int(root.get("tests") or 0)
        for case in root.iter("testcase"):
            if case.find("failure") is None and case.find("error") is None:
                continue
            cls = case.get("classname") or ""
            method = case.get("name") or ""
            # 参数化测试的 name 带 [1] 之类的下标 —— 去掉，否则加一条用例就"新增红"
            method = method.split("[", 1)[0].strip()
            if cls and method:
                failing.add(f"{cls}.{method}")
    return failing, total


def read_baseline(path: str) -> set[str]:
    if not os.path.exists(path):
        return set()
    out = set()
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip()
            if line:
                out.add(line)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reports", required=True, help="surefire-reports 目录")
    ap.add_argument("--baseline", required=True, help="已知红名单文件")
    ap.add_argument("--write-baseline", action="store_true",
                    help="用本次结果覆盖基线（⛔ 只在确认新增红已修完后手工跑）")
    ap.add_argument("--min-tests", type=int, default=100,
                    help="低于这个数就判定「没量到」（rc=2）")
    args = ap.parse_args()

    failing, total = collect(args.reports)

    # ── rc=2：这次没量到 ────────────────────────────────────────────
    if total < args.min_tests:
        print(f"JAVA_RATCHET INSTRUMENT_DEAD —— 只解析到 {total} 个测试"
              f"（下限 {args.min_tests}）。编译失败 / 报告目录为空 / 解析不出结果。")
        print("⚠️ 本次读数【作废】，⛔ 不代表没有新增红。")
        return RC_NOT_MEASURED

    baseline_exists = os.path.exists(args.baseline)
    baseline = read_baseline(args.baseline)

    # ── 基线还没被【本 CI 环境】产出过 ──────────────────────────────
    #
    # 🔴 基线必须由**将来读它的那个仪器**产出。本地跑（没有 postgres service）
    #    和 CI 跑的失败集合必然不同 —— 拿本地的当基线，第一晚就会报出一堆
    #    根本不是新增的"新增红"，然后这道闸当天就被关掉（形态 E）。
    #
    # ⇒ 所以首次运行不红，只打印清单并告诉你怎么落基线。
    if not args.write_baseline and not baseline_exists:
        print(f"JAVA_RATCHET BASELINE_MISSING —— 基线文件不存在: {args.baseline}")
        print(f"本次共跑 {total} 个测试，其中 {len(failing)} 个红：")
        for name in sorted(failing):
            print(f"   {name}")
        print("\n⇒ 手动触发一次本 workflow 并把 write_baseline 设为 true，"
              "把产出的名单提交进仓库。")
        print("⛔ 不要用本地跑的结果当基线 —— 环境不同，失败集合必然不同。")
        return RC_OK

    if args.write_baseline:
        os.makedirs(os.path.dirname(args.baseline) or ".", exist_ok=True)
        with open(args.baseline, "w", encoding="utf-8", newline="") as fh:
            fh.write("# Java 全量测试的已知红名单（棘轮：只许变少）\n")
            fh.write("# ⛔ 不要手工往里加。修好一个就从这里删一行。\n")
            fh.write(f"# 本次共跑 {total} 个测试，其中 {len(failing)} 个红。\n")
            for name in sorted(failing):
                fh.write(name + "\n")
        print(f"JAVA_RATCHET BASELINE_WRITTEN {len(failing)} 条 / 共 {total} 个测试")
        return RC_OK

    new = sorted(failing - baseline)
    fixed = sorted(baseline - failing)

    print(f"JAVA_RATCHET 共跑 {total} 个测试 · 红 {len(failing)} · 基线 {len(baseline)}")
    if fixed:
        print(f"\n✅ 已修好 {len(fixed)} 条（可以从基线里删掉）：")
        for n in fixed:
            print(f"   {n}")
    if new:
        print(f"\n🔴 新增 {len(new)} 条基线里没有的红：")
        for n in new:
            print(f"   {n}")
        print("\n⛔ 这些是这次改动引入的。修掉它们，"
              "⛔ 不要把它们加进基线 —— 基线只许变少。")
        return RC_NEW_FAILURES

    print("\n没有新增红。")
    return RC_OK


if __name__ == "__main__":
    sys.exit(main())
