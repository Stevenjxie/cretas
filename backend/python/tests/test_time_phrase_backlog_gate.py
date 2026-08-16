"""Task 3 (`.superpowers/sdd/2026-08-16-时间词语料与晋升/task-3-brief.md`): CLI +
跑批的契约测试, ⛔ 不连真库。

## 两层, 两种夹具

1. **CLI 动作的接线**(`--list` / `--mark-promoted` / `--counts` 有没有正确
   调用 `list_unpromoted` / `mark_promoted` / `corpus_counts`): 用
   `_FakePool`/`_FakeConn`, 照 `test_time_phrase_corpus.py` 里已有的做法,
   直接把假池注进 `main_async(args, pool=...)`。

2. **跑批脚本自己的三态分支逻辑**(`scripts/cron/time-phrase-backlog-daily.sh`
   的 rc 判断 / grep 解析 / 措辞): 这一层与 DB 完全无关, 只与
   「CLI 打印了什么、退出码是什么」有关。⇒ 用一个与生产 CLI 同名
   (`smartbi.scripts.time_phrase_corpus_cli`, 通过同样的 `python -m` 调用方式)
   的**桩替身**, 由环境变量控制它打印什么/退出什么 —— **真的跑 bash 脚本
   子进程**, 不是把 `.sh` 里的分支逻辑抄成 Python 再测一遍(那样脚本自己的
   分支改了, 测试可能一个字都不会跟着变, 见 rules 形态 D)。

裁定 A(2026-08-16, 控制端): 跑批脚本的 `CODE_DIR`/`VENV_ACTIVATE`/`ALERTS`/
`THRESHOLD` 全部可通过环境变量覆盖(生产默认值不变) —— 这里的桩测试就是
靠这四个覆盖点接进去的, 不用碰任何真实路径。
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict

import pytest

from smartbi.scripts.time_phrase_corpus_cli import (
    build_parser,
    format_counts_line,
    main_async,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
CRON_SCRIPT = REPO_ROOT / "scripts" / "cron" / "time-phrase-backlog-daily.sh"

#: ⚠️ Windows 坑(2026-08-16 实测): 裸 `"bash"` 传给 `subprocess.run` 时, 原生
#: Win32 `CreateProcess` 会命中 `C:\Windows\System32\bash.exe`(WSL relay 桩,
#: `shutil.which` 不会告诉你这个 —— 它按 PATH 顺序算, 结果与 CreateProcess
#: 实际命中的不是同一个)。解法: 显式解析成绝对路径再传给 subprocess。
_BASH = shutil.which("bash") or "bash"


# ══════════════════════════════════════════════════════════════════
# 假池 —— 照 test_time_phrase_corpus.py 的 _FakeConn/_FakePool 做法。
# ══════════════════════════════════════════════════════════════════

class _FakeConn:
    def __init__(self):
        self.calls = []
        self.rows = []
        self.fetchrow_result: Dict[str, Any] | None = None
        self.execute_result = "UPDATE 1"

    async def fetch(self, sql, *args):
        self.calls.append(("fetch", sql, args))
        return self.rows

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, args))
        return self.fetchrow_result

    async def execute(self, sql, *args):
        self.calls.append(("execute", sql, args))
        return self.execute_result


class _FakePool:
    def __init__(self, conn: _FakeConn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *_a):
                return False

        return _Ctx()


def _args(**overrides):
    """从真实 parser 的默认值出发覆盖几个字段 —— CLI 默认值改了, 这里跟着改,
    不在测试里另抄一份默认值(形态 D)。"""
    ns = build_parser().parse_args([])
    for k, v in overrides.items():
        setattr(ns, k, v)
    return ns


# ══════════════════════════════════════════════════════════════════
# CLI 动作接线 —— --list / --mark-promoted / --counts
# ══════════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_counts_action_calls_corpus_counts_and_prints_it(capsys):
    conn = _FakeConn()
    conn.fetchrow_result = {"total": 12, "unpromoted": 4}
    rc = await main_async(_args(counts=True), pool=_FakePool(conn))
    assert rc == 0
    assert conn.calls and conn.calls[0][0] == "fetchrow", conn.calls
    out = capsys.readouterr().out
    assert "total=12" in out and "unpromoted=4" in out, out


@pytest.mark.asyncio
async def test_list_action_calls_list_unpromoted_and_prints_rows(capsys):
    conn = _FakeConn()
    conn.rows = [{
        "normalized_phrase": "最近损耗怎么样", "factory_id": "MOCK_REST",
        "raw_query": "最近损耗怎么样", "llm_phrase": "最近30天",
        "llm_time_range": {"type": "relative", "unit": "day", "count": 30},
        "hit_count": 3, "last_seen_at": "2026-08-16",
    }]
    rc = await main_async(_args(list=True), pool=_FakePool(conn))
    assert rc == 0
    assert conn.calls and conn.calls[0][0] == "fetch", conn.calls
    out = capsys.readouterr().out
    assert "最近损耗怎么样" in out, out
    assert "最近30天" in out, out


@pytest.mark.asyncio
async def test_list_action_with_no_rows_says_so_not_silently_empty(capsys):
    """⛔ 空列表不许什么都不打印 —— 人要能分清「没有待办」和「命令没跑」。"""
    conn = _FakeConn()
    conn.rows = []
    rc = await main_async(_args(list=True), pool=_FakePool(conn))
    assert rc == 0
    out = capsys.readouterr().out
    assert "没有待晋升" in out, out


@pytest.mark.asyncio
async def test_mark_promoted_requires_by_and_note():
    """⛔ 登记是留痕, 不是打勾 —— 少给 --by/--note 直接拒绝, 不静默用空值写库。"""
    rc = await main_async(
        _args(mark_promoted="x", by=None, note=None), pool=_FakePool(_FakeConn()),
    )
    assert rc == 2


@pytest.mark.asyncio
async def test_mark_promoted_writes_who_and_why(capsys):
    conn = _FakeConn()
    conn.execute_result = "UPDATE 1"
    rc = await main_async(
        _args(mark_promoted="最近损耗怎么样", by="steve", note="加了「最近」分支"),
        pool=_FakePool(conn),
    )
    assert rc == 0
    sql, args = conn.calls[0][1].upper(), conn.calls[0][2]
    assert "PROMOTED_AT" in sql and "REVIEWED_BY" in sql and "PROMOTED_NOTE" in sql, sql
    assert "steve" in args and "加了「最近」分支" in args, args
    out = capsys.readouterr().out
    assert "已记录晋升" in out, out
    # 承重: CLI 必须提醒这条记录不会让规则自动生效(与 --help 的免责声明同一件事)。
    assert "不会让" in out or "还需要" in out, out


@pytest.mark.asyncio
async def test_mark_promoted_not_found_returns_1_and_says_so(capsys):
    conn = _FakeConn()
    conn.execute_result = "UPDATE 0"
    rc = await main_async(
        _args(mark_promoted="不存在的短语", by="steve", note="x"), pool=_FakePool(conn),
    )
    assert rc == 1
    err = capsys.readouterr().err
    assert "不存在的短语" in err, err


@pytest.mark.asyncio
async def test_no_action_flag_is_a_usage_error():
    rc = await main_async(_args(), pool=_FakePool(_FakeConn()))
    assert rc == 2


@pytest.mark.asyncio
async def test_two_action_flags_at_once_is_a_usage_error():
    rc = await main_async(
        _args(list=True, counts=True), pool=_FakePool(_FakeConn()),
    )
    assert rc == 2


def test_help_contains_the_required_disclaimer():
    """M1 变异靶子: 删掉「本工具不会修改任何解析代码」这句 ⇒ 本测试预期红。"""
    help_text = build_parser().format_help()
    assert "本工具不会修改任何解析代码" in help_text, help_text
    assert "_resolve_sales_date_range" in help_text, help_text
    assert "人手写一个" in help_text and "分支 + 补测试 + 发版" in help_text, help_text


# ══════════════════════════════════════════════════════════════════
# 裁定 B(形态 D): CLI --counts 的输出格式 与 跑批脚本的 grep 解析,
# 由同一条断言钉住 —— 不许两边各写各的期待值。
# ══════════════════════════════════════════════════════════════════

def _apply_k_pattern(pcre_pattern: str, text: str) -> str:
    """`grep -oP 'PREFIX\\K[0-9]+'` 的等价物 —— 这个仓库里对应的写法只有
    这一种形状(`\\K` 之前是字面前缀, 之后是要取的数字类), 所以不做通用 PCRE
    翻译器, 只覆盖这一种。

    ⚠️ 2026-08-16 实测: `subprocess.run(["grep", "-oP", ...])` 在这台 Windows
    开发机上通过 argv 列表(非 shell)调用时, PCRE 模式**静默不匹配**
    (rc=1, 无报错) —— 同一个模式经 `shell=True`/在 git-bash 里直接跑却正常;
    这是 MSYS grep.exe 在原生 Win32 匿名管道下的已知怪癖, 不是本仓代码的
    问题。⇒ 改用纯 Python 翻译, 两个平台(Windows 开发机 / Linux CI)行为
    一致, 且仍然**从 .sh 源码里读出模式**, 不在测试里另写一份数字契约。
    """
    assert r"\K" in pcre_pattern, f"仅支持含 \\K 的模式: {pcre_pattern!r}"
    prefix, tail = pcre_pattern.split(r"\K", 1)
    match = re.search(f"(?<={re.escape(prefix)}){tail}", text)
    return match.group(0) if match else ""


def test_counts_output_is_parseable_by_the_cron_scripts_own_grep_patterns():
    """M3 变异靶子: 把 `format_counts_line` 的 `total=` 改成 `total:`
    ⇒ 本测试预期红(模式解不出数字, `_apply_k_pattern(...)` 返回空串)。

    模式**从 `.sh` 源码里读出来**, ⛔ 不在测试里另写一份 'total=(\\d+)' —— 那样
    脚本改了 grep 写法, 这条测试完全不会跟着变, 起不到钉住契约的作用。
    """
    sh_text = CRON_SCRIPT.read_text(encoding="utf-8")
    patterns = re.findall(r"grep -oP '([^']+)'", sh_text)
    assert len(patterns) == 2, f"预期两条 grep -oP 模式(total / unpromoted): {patterns}"
    total_pattern, unpromoted_pattern = patterns
    assert "total" in total_pattern and "unpromoted" in unpromoted_pattern, patterns

    line = format_counts_line("restaurant", {"total": 7, "unpromoted": 3})

    assert _apply_k_pattern(total_pattern, line) == "7", line
    assert _apply_k_pattern(unpromoted_pattern, line) == "3", line

    # 阴性对照: 换一组不同的数字, 解出来也要跟着变 —— 防止巧合命中恒真。
    line2 = format_counts_line("restaurant", {"total": 105, "unpromoted": 0})
    assert _apply_k_pattern(total_pattern, line2) == "105", line2
    assert _apply_k_pattern(unpromoted_pattern, line2) == "0", line2


# ══════════════════════════════════════════════════════════════════
# 跑批脚本的三态分支逻辑 —— 真跑 bash 子进程, 桩替身 CLI 由环境变量驱动。
# ══════════════════════════════════════════════════════════════════

_STUB_CLI_SOURCE = '''\
"""桩替身 —— 与生产 CLI 同名(smartbi.scripts.time_phrase_corpus_cli), 由环境
变量控制打印什么/退出什么。只测跑批脚本自己的分支逻辑, 不连真库。"""
import argparse
import os
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--counts", action="store_true")
    args = parser.parse_args()
    if os.environ.get("STUB_FORCE_FAIL"):
        print("stub: forced failure (simulates DB unreachable)", file=sys.stderr)
        sys.exit(3)
    if not args.counts:
        sys.exit(2)
    if os.environ.get("STUB_GARBAGE_OUTPUT"):
        print("no numbers here")
        sys.exit(0)
    total = os.environ.get("STUB_TOTAL", "0")
    unpromoted = os.environ.get("STUB_UNPROMOTED", "0")
    if os.environ.get("STUB_MULTILINE_TOTAL"):
        # M2 变异靶子: 往 stderr 里塞一条**也含** `total=` 的诊断行。跑批脚本
        # 用 `OUT=$(... 2>&1)` 合并 stdout/stderr, 于是 grep -oP 会命中两行,
        # `$total` 变成一个内嵌换行的多行字符串, 不是空串。
        print("retry-log: total=999 (stale, ignored)", file=sys.stderr)
    print(f"domain=restaurant total={total} unpromoted={unpromoted}")
    sys.exit(0)


if __name__ == "__main__":
    main()
'''


def _write_stub_cli_tree(root: Path) -> Path:
    code_dir = root / "code_dir"
    scripts_pkg = code_dir / "smartbi" / "scripts"
    scripts_pkg.mkdir(parents=True)
    (code_dir / "smartbi" / "__init__.py").write_text("", encoding="utf-8", newline="")
    (scripts_pkg / "__init__.py").write_text("", encoding="utf-8", newline="")
    (scripts_pkg / "time_phrase_corpus_cli.py").write_text(
        _STUB_CLI_SOURCE, encoding="utf-8", newline="",
    )
    return code_dir


def _run_cron_script(tmp_path: Path, *, extra_env: Dict[str, str], threshold: str = "20"):
    code_dir = _write_stub_cli_tree(tmp_path)
    noop_activate = tmp_path / "noop_activate.sh"
    noop_activate.write_text("", encoding="utf-8", newline="")
    alerts = tmp_path / "alerts.log"

    env = dict(os.environ)
    env.update({
        "TIME_PHRASE_CODE_DIR": str(code_dir),
        "TIME_PHRASE_VENV_ACTIVATE": str(noop_activate),
        "TIME_PHRASE_ALERTS": str(alerts),
        "TIME_PHRASE_THRESHOLD": threshold,
    })
    env.update(extra_env)

    result = subprocess.run(
        [_BASH, str(CRON_SCRIPT)], env=env, capture_output=True, text=True, timeout=30,
    )
    alerts_text = alerts.read_text(encoding="utf-8") if alerts.exists() else ""
    return result, alerts_text


def test_state_normal_exits_0_and_stays_silent(tmp_path):
    """态①正常: total>0 且 unpromoted<=阈值 —— rc=0, 不写告警(裁定 C: 不喊)。"""
    result, alerts_text = _run_cron_script(
        tmp_path, extra_env={"STUB_TOTAL": "5", "STUB_UNPROMOTED": "3"},
    )
    assert result.returncode == 0, (result.stdout, result.stderr)
    assert alerts_text == "", alerts_text


def test_state_backlog_exits_1_with_backlog_wording(tmp_path):
    """态②有积压: unpromoted>阈值 —— rc=1, 措辞含 BACKLOG。"""
    result, alerts_text = _run_cron_script(
        tmp_path, extra_env={"STUB_TOTAL": "30", "STUB_UNPROMOTED": "25"},
    )
    assert result.returncode == 1, (result.stdout, result.stderr)
    assert "XXX BACKLOG" in alerts_text, alerts_text
    assert "未晋升 25 条" in alerts_text, alerts_text
    assert "阈值 20" in alerts_text, alerts_text


def test_state_backlog_via_threshold_env_override(tmp_path):
    """裁定 A: 阈值可用环境变量压到 -1 来构造积压, 不用真的喂 21 条数据。"""
    result, alerts_text = _run_cron_script(
        tmp_path, extra_env={"STUB_TOTAL": "3", "STUB_UNPROMOTED": "1"}, threshold="-1",
    )
    assert result.returncode == 1, (result.stdout, result.stderr)
    assert "XXX BACKLOG" in alerts_text, alerts_text


def test_state_instrument_dead_on_cli_nonzero_exit(tmp_path):
    """态③没量到(CLI 本身失败): rc=2, 措辞含 INSTRUMENT DEAD, 且原始输出附在后面。"""
    result, alerts_text = _run_cron_script(
        tmp_path, extra_env={"STUB_FORCE_FAIL": "1"},
    )
    assert result.returncode == 2, (result.stdout, result.stderr)
    assert "XXX INSTRUMENT DEAD" in alerts_text, alerts_text
    assert "语料表读不到" in alerts_text, alerts_text
    assert "stub: forced failure" in alerts_text, alerts_text


def test_state_instrument_dead_on_unparseable_output(tmp_path):
    """态③没量到(输出解析失败): CLI 退出 0 但格式不对 —— ⛔ 不当成 0 条处理。"""
    result, alerts_text = _run_cron_script(
        tmp_path, extra_env={"STUB_GARBAGE_OUTPUT": "1"},
    )
    assert result.returncode == 2, (result.stdout, result.stderr)
    assert "XXX INSTRUMENT DEAD" in alerts_text, alerts_text
    assert "读数解析失败" in alerts_text, alerts_text


def test_state_instrument_dead_on_multiline_total(tmp_path):
    """态③没量到, M2 的场景: `OUT=$(... 2>&1)` 把 stdout/stderr 合并, 任何一行
    stderr 诊断文本只要含 `total=` 子串, `grep -oP 'total=\\K[0-9]+'` 就会
    命中两行, `$total` 变成一个内嵌换行的多行字符串 —— 既不是空串(旧版
    `-z` 判据放过它), 也不是纯数字。

    修复前(`-z` 判据): $total 非空 ⇒ 跳过「解析失败」分支; 随后
    `[ "$total" -eq 0 ]` 对多行字符串报 `integer expression expected`、
    `if` 读成 false, 积压比较同样报错读成 false, 脚本一路滑到 `exit 0` ——
    **一次畸形读数被汇报成「健康」**, 正是三态设计要防的那种假绿。
    修复后(`[[ "$total" =~ ^[0-9]+$ ]]`): 多行字符串不匹配纯数字整体锚定,
    落入 INSTRUMENT DEAD、rc=2。
    """
    result, alerts_text = _run_cron_script(
        tmp_path,
        extra_env={
            "STUB_MULTILINE_TOTAL": "1", "STUB_TOTAL": "7", "STUB_UNPROMOTED": "2",
        },
    )
    assert result.returncode == 2, (result.stdout, result.stderr, alerts_text)
    assert "XXX INSTRUMENT DEAD" in alerts_text, alerts_text
    assert "读数解析失败" in alerts_text, alerts_text


def test_state_instrument_dead_when_code_dir_unreachable(tmp_path):
    """态③没量到, 另一种构造方式(brief 建议的「CLI 指到不存在的东西」):
    CODE_DIR 指到不存在的目录 —— cd 失败, rc!=0, 同样落到 INSTRUMENT DEAD。"""
    result, alerts_text = _run_cron_script(
        tmp_path, extra_env={"TIME_PHRASE_CODE_DIR": str(tmp_path / "does-not-exist")},
    )
    assert result.returncode == 2, (result.stdout, result.stderr)
    assert "XXX INSTRUMENT DEAD" in alerts_text, alerts_text


def test_state_zero_total_has_its_own_distinct_wording(tmp_path):
    """🔴 裁定 C 承重: 「至今 0 条」rc=0, 但**必须**写出一行与「无积压」不同措辞
    的记录 —— 这张表没有任何别的日志, 它自己就是唯一的仪器, 「0」必须能被
    读懂, 不能和「没有积压」长得一样。

    M2 变异靶子: 把跑批脚本里这一行的措辞改成与「无积压」相同(即不写任何
    告警) ⇒ 本测试预期红(alerts_text 会变成空串, 断言的具体文案找不到)。
    """
    result, alerts_text = _run_cron_script(
        tmp_path, extra_env={"STUB_TOTAL": "0", "STUB_UNPROMOTED": "0"},
    )
    assert result.returncode == 0, (result.stdout, result.stderr)
    assert "时间词语料至今 0 条" in alerts_text, alerts_text
    assert "BACKLOG" not in alerts_text
    assert "INSTRUMENT DEAD" not in alerts_text


def test_zero_total_wording_differs_from_every_other_state(tmp_path):
    """裁定 C 的直接判据: 四种真正会写字的场景(0 条/积压/两种没量到), 落进告警
    文件的**整行文本**必须两两不同 —— 不比 .sh 源码里的字符串常量(那只是文本,
    不保证真的被执行到; 见 rules「闸把自己的文档也测了进去」的反例), 比真正
    跑出来的产出。"""
    cases = {
        "zero_total": {"STUB_TOTAL": "0", "STUB_UNPROMOTED": "0"},
        "backlog": {"STUB_TOTAL": "30", "STUB_UNPROMOTED": "25"},
        "dead_nonzero_exit": {"STUB_FORCE_FAIL": "1"},
        "dead_unparseable": {"STUB_GARBAGE_OUTPUT": "1"},
    }
    first_lines: Dict[str, str] = {}
    for name, env in cases.items():
        _, alerts_text = _run_cron_script(tmp_path / name, extra_env=env)
        assert alerts_text.strip(), f"{name}: 期望写出告警但文件是空的"
        first_lines[name] = alerts_text.splitlines()[0]

    assert len(set(first_lines.values())) == len(first_lines), first_lines
