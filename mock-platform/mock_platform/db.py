"""SQLite 连接。模拟端的全部持久化只有这一个文件，不连任何外部数据库。"""
from __future__ import annotations

import pathlib
import sqlite3

_SCHEMA = pathlib.Path(__file__).parent / "world" / "schema.sql"


def connect(db_path: str) -> sqlite3.Connection:
    pathlib.Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.executescript(_SCHEMA.read_text(encoding="utf-8"))
    return conn
