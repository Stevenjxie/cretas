"""用到的每个 pytest marker 都必须在 pytest.ini 里注册。

## 为什么需要这条闸

python-gate.yml 用 `-k "not e2e and not integration"` 筛掉需要真服务/真库的用例。
`-k` 匹配的是**名字与 marker**, 所以一个**没注册**的 marker 仍然能被 `-k` 筛到 ——
它只是发一条 `PytestUnknownMarkWarning`, 而 warning 没人看。

风险有两个方向, 都是静默的:

  · 拼错(`e2e` 写成 `e2ee`) → `-k "not e2e"` 筛不到 → 那些用例在没有服务的环境里
    **红成一片看起来像契约缺陷**(2026-08-10 实测: tests/test_smartbi_api.py 就是
    这样被整文件挂进 ci-gate-excludes.txt 的, 表现是 404 / KeyError / 200≠400)。
  · 将来若加 `--strict-markers`, 未注册的 marker 直接变成收集错误, 而收集错误会让
    **整轮中止**(门禁命令没有 --continue-on-collection-errors)。

判据: **「靠名字筛」的机制, 要么给被筛对象一个注册过的显式 marker, 要么它迟早漏掉。**

⚠️ 2026-08-10 我自己就漏了一次: 给 test_smartbi_api.py 补了 `pytest.mark.e2e`
   解决了挂账, 却**没在 pytest.ini 注册它** —— 筛选碰巧仍然生效, 所以没人发现,
   直到这次普查跑 `--co` 看到 PytestUnknownMarkWarning。
"""
from __future__ import annotations


import configparser
import re
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[1]          # backend/python

#: pytest / 常用插件自带的 marker, 不需要在 pytest.ini 里声明。
_BUILTIN = {
    "parametrize", "skip", "skipif", "xfail", "usefixtures", "filterwarnings",
    "asyncio", "timeout", "order", "dependency", "flaky", "benchmark",
}


def _registered() -> set[str]:
    cfg = configparser.ConfigParser()
    cfg.read(_ROOT / "pytest.ini", encoding="utf-8")
    raw = cfg.get("pytest", "markers", fallback="")
    return {
        line.strip().split(":")[0].split("(")[0].strip()
        for line in raw.splitlines() if line.strip()
    }


def _used() -> dict[str, list[str]]:
    """扫测试源码里所有 `pytest.mark.<name>` 的用法。"""
    used: dict[str, list[str]] = {}
    pattern = re.compile(r"pytest\.mark\.([a-zA-Z_]\w*)")
    for path in _ROOT.rglob("test_*.py"):
        if "__pycache__" in str(path) or "/venv" in str(path).replace("\\", "/"):
            continue
        try:
            src = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for name in set(pattern.findall(src)):
            used.setdefault(name, []).append(path.relative_to(_ROOT).as_posix())
    return used


def test_every_used_marker_is_registered():
    registered = _registered()
    assert registered, "pytest.ini 里一个 marker 都没读到 —— 解析坏了, 下面会恒绿"

    unregistered = {
        name: files for name, files in sorted(_used().items())
        if name not in registered and name not in _BUILTIN
    }
    detail = "\n".join(f"    {n}: {', '.join(f[:3])}" for n, f in unregistered.items())
    assert not unregistered, (
        "以下 marker 被用了但没在 pytest.ini 注册:\n" + detail + "\n"
        "后果是静默的: `-k \"not <marker>\"` 这类按名字筛的机制, 遇到拼错的 marker\n"
        "会**筛不到**, 那些用例于是在错误的环境里红成一片、看起来像契约缺陷。")


def test_the_scanner_sees_real_markers():
    """阴性对照: 扫不到 marker 的话上面那条恒绿。"""
    used = _used()
    assert "parametrize" in used or "asyncio" in used, (
        f"只扫到 {sorted(used)[:8]}… —— 正则或路径坏了")
    assert len(used) >= 4, f"只扫到 {len(used)} 种 marker, 太少, 扫描器多半坏了"
