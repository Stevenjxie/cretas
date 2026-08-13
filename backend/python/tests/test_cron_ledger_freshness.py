"""台账不许把「上一次的计数」配上「这一次的 rc」。

## 被守的行为

跑批脚本的产出文件是台账的唯一数据源。只要有**一条**退出路径不写这个文件，
读者(cron)就会读到上一次留下的那份 —— 于是台账里出现一行:

    {"date": "2026-08-13", "rc": 2, "factories": 1, "sections_computed": 1}

rc 是这次的(名单为空 → 2), 计数是上次的(名单为空不可能有 1 个租户)。
**格式合法、字段齐全、不报错。** 2026-08-13 prod 实测出现过一次。

⛔ 这是台账最该防的失效: 我们靠台账做盖章决定, 一行脏读会让所有基于它的
   判断打折 —— 比没有台账更糟, 因为它看起来是有据可查的。

## 两道, 缺一不可

1. **生产者兜底**: 每条 `return` 之前都写过产出文件(本模块 AST 闸)
2. **消费者清场**: cron 跑之前 `rm -f` 上一次的产出(本模块文本闸)

只补一道不够 —— 任何一条新增的早退路径都会让脏读复活。
"""
import ast
import io
from pathlib import Path

import pytest

_PY_ROOT = Path(__file__).resolve().parents[1]
_REPO_ROOT = _PY_ROOT.parents[1]

#: (跑批模块, 写产出文件的函数名, 入口函数名)
#: ⚠️ 新增跑批脚本要加进来。⛔ 不自动发现: 自动发现漏掉一个不会报错,
#:    而漏掉的表现正是「台账悄悄开始脏读」。
RUNNERS = [
    ("smartbi/scripts/replay_equivalence_probe.py", "_write_probe_out", "main"),
    ("smartbi/scripts/daily_close_push.py", "_write", "main"),
]

#: (cron 脚本, 它读的产出文件)
CRONS = [
    ("scripts/cron/replay-equivalence-daily.sh", "/tmp/replay_equivalence.json"),
    ("scripts/cron/daily-close-push.sh", "/tmp/daily_close_push.json"),
]


def _returns_before_first_write(source: str, write_fn: str, entry: str):
    """入口函数里, 排在**所有**产出写入之前的 `return` 行号。

    ⚠️ 用行号近似「支配关系」: 一条 `return` 只要排在某次写入之后, 就认为
       那条路径上产出已经落盘。这会漏掉「写在 if 分支里、return 在 else」
       这种形状 —— 但它抓得住我们实际踩到的那个(早退在最前面, 写在最后面),
       而且不会误报。**代理判据, 标出来。**
    """
    tree = ast.parse(source)
    fn = next((n for n in ast.walk(tree)
               if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
               and n.name == entry), None)
    assert fn is not None, f"找不到入口函数 {entry}"

    writes = [n.lineno for n in ast.walk(fn)
              if isinstance(n, ast.Call)
              and isinstance(n.func, ast.Name) and n.func.id == write_fn]
    if not writes:
        return ["<入口里一次产出都没写>"]
    first = min(writes)
    return [n.lineno for n in ast.walk(fn)
            if isinstance(n, ast.Return) and n.lineno < first]


@pytest.mark.parametrize("rel,write_fn,entry", RUNNERS)
def test_every_early_return_writes_the_output_file(rel, write_fn, entry):
    src = io.open(_PY_ROOT / rel, encoding="utf-8", newline="").read()
    bad = _returns_before_first_write(src, write_fn, entry)
    assert not bad, (
        f"{rel}: 第 {bad} 行的 return 排在所有 {write_fn}() 之前 —— "
        f"这条路径不写产出文件, 读者会读到上一次的那份, "
        f"台账就会出现「这次的 rc + 上次的计数」")


def test_the_gate_catches_a_return_that_skips_the_write():
    """🔴 变异对照: 把早退那条的写入拿掉, 闸必须红。

    ⛔ 没有这条, 上面那两条可能只是因为 `_returns_before_first_write`
       永远返回空(比如函数名写错了导致 writes 为空又被当成没问题)。
    """
    mutated = (
        "def main():\n"
        "    if not rows:\n"
        "        return 2\n"          # ← 早退, 不写
        "    _write([])\n"
        "    return 0\n"
    )
    assert _returns_before_first_write(mutated, "_write", "main") == [3]

    # 阳性对照: 补上写入之后同一个闸必须转绿
    fixed = (
        "def main():\n"
        "    if not rows:\n"
        "        _write([])\n"
        "        return 2\n"
        "    _write([])\n"
        "    return 0\n"
    )
    assert _returns_before_first_write(fixed, "_write", "main") == []


@pytest.mark.parametrize("rel,artifact", CRONS)
def test_cron_clears_the_previous_artifact_before_running(rel, artifact):
    """消费者侧: 跑之前必须删掉上一次的产出。

    ⛔ 只靠生产者兜底不够 —— 脚本崩在写入之前(OOM / 被 kill / 解释器起不来)
       同样会留下旧文件, 而那时 python 侧一行都没执行。
    """
    src = io.open(_REPO_ROOT / rel, encoding="utf-8", newline="").read()
    assert artifact in src, f"{rel} 没有引用 {artifact}, 这条参数化过期了"

    rm_at = src.find(f"rm -f {artifact}")
    assert rm_at >= 0, f"{rel}: 跑之前没有 `rm -f {artifact}`"

    # ⚠️ 顺序也要对: 删必须在**读产出**之前, 否则等于没删。
    read_at = src.find(f"[ -r {artifact} ]")
    assert read_at < 0 or rm_at < read_at, f"{rel}: `rm -f` 排在读取之后"
