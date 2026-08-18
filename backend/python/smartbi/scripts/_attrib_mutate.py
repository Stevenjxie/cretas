# -*- coding: utf-8 -*-
"""变异对照 —— 每条先证明**行为变了**，再看断言红不红。

⚠️ 硬约束 5: 文本模式 + newline=''，⛔ 不用 bytes 字面量。
⚠️ 形态 C‴: assert 的是「行为变了」，⛔ 不是「字符串替换成功了」。
⚠️ 一格一件事: 每条变异跑一个独立子进程，⛔ 不共用解释器（缓存/常量会串）。
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / "agent" / "synthesis_engine.py"
TEST = str(Path(__file__).resolve().parents[1] / "agent" / "tests"
            / "test_yes_no_decision_attribution.py")

#: 变异到达探针：把这几句问句的 (mode, auto_expand, attribution) 打出来。
#: ⛔ 不看「替换成功了几处」——那只证明文本被改了。
PROBE_SNIPPET = (
    "import json;"
    "from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine as E,"
    "_HIGH_IMPACT_ACTION_RE as R;"
    "e=E.__new__(E);"
    "out={q:[e.plan_dimensions(q)['analysis_mode'],"
    "e.plan_dimensions(q)['auto_expand'],"
    "e.plan_dimensions(q)['attribution']] for q in "
    "['我要不要关掉最差的那家店 最近30天','要不要看一下昨天营收',"
    "'能不能给我看看各店营收','哪家店客流拖后腿']};"
    "out['GATE:关掉它省不了多少成本']=bool(R.search('关掉它省不了多少成本'));"
    "print('PROBE_JSON='+json.dumps(out,ensure_ascii=False))"
)

MUTATIONS = [
    (
        "M1 拆掉接线：decision 分支不再认是非决策",
        "if any(k in ql for k in decision_cues) or _is_yes_no_decision:",
        "if any(k in ql for k in decision_cues):",
    ),
    (
        "M2 放宽判据：去掉门店/落后线索这一半",
        "and (_wants_store or _wants_lag)",
        "and True",
    ),
    (
        "M3 把「能不能」收进是非词表",
        '"有没有必要", "是不是该", "是否应该", "是否值得",',
        '"有没有必要", "是不是该", "是否应该", "是否值得", "能不能",',
    ),
    (
        "M4 把「关掉|关店」加进 _HIGH_IMPACT_ACTION_RE",
        "(?:下架|停售|停用|调价|涨价|降价|打折|满减|投流|发券|",
        "(?:下架|停售|停用|关掉|关店|调价|涨价|降价|打折|满减|投流|发券|",
    ),
    (
        "M5 decision 模式不再 auto_expand",
        '            plan["analysis_mode"] = "decision"\n            plan["auto_expand"] = True',
        '            plan["analysis_mode"] = "decision"\n            plan["auto_expand"] = False',
    ),
]


def read() -> str:
    with open(SRC, "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def write(text: str) -> None:
    with open(SRC, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def probe() -> dict:
    # ⚠️ 第二条路径 `smartbi` 才是关键 —— `services` 在它下面。
    #    第一版漏了它, 探针每次都返回同一个 ImportError dict,
    #    于是五条变异全部读成「行为没变」—— 仪器坏了, 不是变异没到达。
    import os
    here = str(Path(__file__).resolve().parents[2])
    env = dict(os.environ)
    env["PYTHONPATH"] = os.pathsep.join([here, os.path.join(here, "smartbi")])
    out = subprocess.run([sys.executable, "-c", PROBE_SNIPPET],
                         capture_output=True, text=True, encoding="utf-8", env=env)
    for line in (out.stdout or "").splitlines():
        if line.startswith("PROBE_JSON="):
            return json.loads(line[len("PROBE_JSON="):])
    return {"<探针起不来>": (out.stderr or "")[-400:]}


def run_tests() -> tuple:
    out = subprocess.run(
        [sys.executable, "-m", "pytest", TEST, "-q", "--tb=no", "-p", "no:cacheprovider"],
        capture_output=True, text=True, encoding="utf-8")
    tail = [ln for ln in (out.stdout or "").splitlines() if "passed" in ln or "failed" in ln]
    fails = [ln.split("::", 1)[-1] for ln in (out.stdout or "").splitlines()
             if ln.startswith("FAILED")]
    return out.returncode, (tail[-1] if tail else "<无结论行>"), fails


def main() -> int:
    original = read()
    base_probe = probe()
    rc, summary, _ = run_tests()
    print("=" * 78)
    print("BASELINE (未变异)")
    print("  行为读数:", json.dumps(base_probe, ensure_ascii=False))
    print(f"  测试: rc={rc} {summary}")
    if rc != 0:
        print("rc=2 基线就不是绿的 —— 后面每条变异读数都无意义")
        return 2

    for name, old, new in MUTATIONS:
        print("=" * 78)
        print(name)
        if original.count(old) != 1:
            print(f"  ⛔ 锚点命中 {original.count(old)} 次(应为 1) —— 这条变异作废")
            continue
        write(original.replace(old, new, 1))
        try:
            mp = probe()
            changed = mp != base_probe
            print(f"  变异是否到达: {'是' if changed else '否 —— 行为没变, 下面的红/绿都不作数'}")
            if changed:
                for k in sorted(set(base_probe) | set(mp)):
                    if base_probe.get(k) != mp.get(k):
                        print(f"    {k}: {base_probe.get(k)}  ->  {mp.get(k)}")
            rc, summary, fails = run_tests()
            print(f"  测试: rc={rc} {summary}")
            for f in fails:
                print(f"    RED: {f}")
            if rc == 0:
                print("  ⚠️ 没红 —— 若上面「变异到达=是」, 那才是「断言在守空气」")
        finally:
            write(original)
    print("=" * 78)
    rc, summary, _ = run_tests()
    print(f"恢复后复核: rc={rc} {summary}")
    return 0 if rc == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
