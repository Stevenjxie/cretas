"""隔离铁律：模拟端必须是一个外部系统，不是本系统的模块。

一旦这条挂掉，模拟器就退化成「我们自己写给自己看的假数据」，失去全部验证价值。
"""
import pathlib
import re

FORBIDDEN = re.compile(r"\b(smartbi|psycopg|asyncpg|smartbi_prod_db|cretas_prod_db)\b")
PKG_ROOT = pathlib.Path(__file__).resolve().parent.parent / "mock_platform"


def test_模拟端不得引用本系统任何东西():
    offenders = []
    for path in PKG_ROOT.rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        for lineno, line in enumerate(text.splitlines(), 1):
            if FORBIDDEN.search(line):
                offenders.append(f"{path.name}:{lineno}: {line.strip()}")
    assert offenders == [], "模拟端泄漏了本系统依赖:\n" + "\n".join(offenders)


def test_模拟端不得声明数据库驱动依赖():
    req = (PKG_ROOT.parent / "requirements.txt").read_text(encoding="utf-8")
    for banned in ("psycopg", "asyncpg", "sqlalchemy"):
        assert banned not in req.lower(), f"requirements.txt 不该有 {banned}"
