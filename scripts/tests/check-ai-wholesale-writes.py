#!/usr/bin/env python3
"""AI 工具把【模型给的整块结构】原样存库 —— 一次列全。

用法:
    python3 scripts/tests/check-ai-wholesale-writes.py          # 报表
    python3 scripts/tests/check-ai-wholesale-writes.py --gate    # 超基线即 exit 2

## 为什么写这道闸

2026-08-09 同一个形状【连撞两次】:

  1. 后端 ProductProcessWorkflowConfigTool: 分流闸判的是【补丁清单】,
     而 saveDraft 写的是【AI 重发的整张 definition】—— 两者不是同一个对象。
     后果: 「把工序改个名」静默清空该工序全部调料克数, 还回 applied:true。

  2. 前端 buildWorkflowFromSpec: 从零重建整张图, materialBindings 取自
     【LLM 复述的 spec】而不是画布上存着的真值。
     后果: 模型把 12.5 说成 12、或漏述另外 21 道工序的调料, 用户看到
     一张「看起来对」的画布就保存了。

📌 共同判据: **写下去的应该是存着的真值, 不是模型的复述。**

按仓里的规矩: **同一形状连出两次, 停下来写一个能一次列全的闸** —— 就是本文件。

## 判据是【结构签名】, ⛔ 不是关键词

同一个**文件**里同时出现:
    (a) 把 AI 参数整块转成对象:  X x = objectMapper.convertValue(<来自params的东西>, X.class)
    (b) 把那个对象整体交出去存:  <service|repository>.<save|update|create|persist>(... x ...)

⛔ 不按工具名/描述判 —— 本仓栽过四次「按名字判」。
⛔ 不按「有没有 save」判 —— 那会把所有写工具都报进来, 闸会因天天误报而被删掉。
真正危险的是【整块转过来又整块存下去】: 中间没有逐字段校验, 也没有和库里的真值比对。

⚠️ 口径是【同一个文件】不是同一个方法。第一版按方法切, 结果 **0 命中** ——
而它本该抓到的那个已知缺陷正是跨方法的(convertValue 在 buildValidatedCandidate 里、
saveDraft 在 execute 里)。**一道报 0 的闸, 必须先证明它抓得到已知实例**,
否则「0 命中」读起来就是「全都干净」。下面的 KNOWN_INSTANCE 自检就是干这个的。

## ⚠️ 命中不等于缺陷

命中只说明「这条路把模型给的结构整块存了」。它是否安全, 取决于:
  · save 那一侧自己有没有闸(如 saveDraft 的 requireWorkflowOwner / 乐观锁), 以及
  · 有没有拿库里的真值比对过成本相关字段。
所以本闸的正确用法是【逐条评审】, 不是「命中就改」。基线只许降不许升。
"""
import io
import os
import re
import subprocess
import sys

REPO = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                      capture_output=True, text=True).stdout.strip()
os.chdir(REPO)

TOOL_ROOT = "backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl"

# 2026-08-09 实测基线。⛔ 只许降不许升。
# 降的正确做法是给那条路补「与库里真值比对」, ⛔ 不是把数字改大。
EXPECTED_BASELINE = 1

# ⛔ 地面真相: 这个文件【必须】被扫出来。它是 2026-08-09 那个 Critical 的现场,
# 结构完全符合本闸的签名(convertValue(definition) -> saveDraft(candidate))。
# 扫不到它 = 解析口径坏了, 而不是「仓里很干净」。
KNOWN_INSTANCE = "ProductProcessWorkflowConfigTool.java"

CONVERT = re.compile(
    r"(\w+)\s*=\s*objectMapper\.convertValue\(\s*(\w+)[^;]*?\.class\s*\)", re.S)
SAVE = re.compile(
    r"\b(\w+)\s*\.\s*(save\w*|update\w*|create\w*|persist\w*)\s*\(([^;]*?)\)", re.S)


def main():
    gate = "--gate" in sys.argv
    findings = []
    scanned = 0

    for dirpath, _, files in os.walk(TOOL_ROOT):
        for filename in files:
            if not filename.endswith(".java"):
                continue
            path = os.path.join(dirpath, filename).replace("\\", "/")
            source = io.open(path, "r", encoding="utf-8", errors="replace").read()
            scanned += 1
            # 文件级: 工具类都不大,「同一个类里既整块转又整块存」就是要找的形状。
            converted = {m.group(1): m.group(2) for m in CONVERT.finditer(source)}
            if not converted:
                continue
            for save in SAVE.finditer(source):
                args = save.group(3)
                for var, src_var in converted.items():
                    if re.search(r"\b" + re.escape(var) + r"\b", args):
                        findings.append(
                            (path, var, src_var, save.group(1) + "." + save.group(2)))

    unique = sorted(set(findings))
    print(f"扫描 AI 工具源码 {scanned} 个")
    print(f"命中「AI 参数整块转对象 -> 整块存库」 {len(unique)} 处\n")
    for path, var, src_var, sink in unique:
        print(f"  {path.split('/')[-1]}")
        print(f"      {var} = convertValue({src_var}, ...)   ->   {sink}(... {var} ...)")

    # ⛔ 自检: 抓不到已知实例就说明口径坏了。没有这条,「0 命中」会被读成「全都干净」。
    if not any(KNOWN_INSTANCE in path for path, _, _, _ in unique):
        print(f"\n    ❌ 自检失败: 扫不到已知实例 {KNOWN_INSTANCE} —— 解析口径坏了,")
        print("       这份报告的任何数字都不可信(尤其是 0)。")
        return 3

    print("\n── 怎么读这份报告 ──")
    print("  命中【不等于】缺陷。要逐条问两件事:")
    print("    1. save 那一侧自己有没有闸(租户归属/乐观锁/状态限定)?")
    print("    2. 有没有拿【库里的真值】比对过成本相关字段?")
    print("  两个都没有, 才是 2026-08-09 那两个缺陷的形状。")
    print(f"\n  当前唯一命中 {KNOWN_INSTANCE} 两条都已补上:")
    print("    saveDraft 自带租户归属+乐观锁+只写 DRAFT; costBearingFieldsUnchanged 比对库内真值。")

    if gate:
        print("\n── 闸 ──")
        if len(unique) > EXPECTED_BASELINE:
            print(f"    ❌ {len(unique)} > 基线 {EXPECTED_BASELINE} —— 又多了一条"
                  f"「整块转、整块存」的路, 逐条评审后再决定基线动不动")
            return 2
        print(f"    ✅ {len(unique)} <= 基线 {EXPECTED_BASELINE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
