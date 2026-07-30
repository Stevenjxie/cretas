"""V20261101_03 死信表的静态契约测试。

这条 migration 会跑在 prod 上, 且 deploy-smartbi-python.sh Step 3.5 失败即
ABORT 整个 Python 部署 —— 一个拼写错误就是一次失败的发布。下面守的都是
"写错了会炸线上 / 会让运维看到假象"的点。
"""
import pathlib
import re

MIG = (pathlib.Path(__file__).resolve().parents[1]
       / "smartbi" / "database" / "migrations"
       / "V20261101_03__platform_ingest_dead_letter.sql")

SQL = MIG.read_text(encoding="utf-8")


def test_migration存在且是单事务():
    assert SQL.count("BEGIN;") == 1
    assert SQL.count("COMMIT;") == 1


def test_列类型是合法的PG关键字():
    """拼错一个类型名(BIGGSERIAL/TEXTT/JSONBB)runner 直接失败, 整个部署 ABORT。

    只认白名单里的类型 —— 比事后在 prod 上发现便宜得多。
    """
    allowed = {
        "BIGSERIAL", "SERIAL", "INTEGER", "BIGINT", "TEXT", "JSONB",
        "TIMESTAMPTZ", "BOOLEAN", "NUMERIC", "DATE",
    }
    # 抓 "列名 类型" 里的类型 token(VARCHAR(n) 单独放行)
    body = SQL.split("CREATE TABLE", 1)[1].split("(", 1)[1].split(");", 1)[0]
    bad = []
    for line in body.splitlines():
        line = line.strip()
        if not line or line.startswith("--") or line.startswith("UNIQUE"):
            continue
        m = re.match(r"^\w+\s+([A-Z][A-Z_]*)(\(|\s|,|$)", line)
        if m and m.group(1) not in allowed and not line.upper().startswith("VARCHAR"):
            if m.group(1) != "VARCHAR":
                bad.append(line)
    assert not bad, f"可疑/拼错的列类型:\n" + "\n".join(bad)


def test_开启并强制RLS():
    assert "ENABLE ROW LEVEL SECURITY" in SQL
    assert "FORCE ROW LEVEL SECURITY" in SQL


def test_四条policy齐全含DELETE():
    for op in ("SELECT", "INSERT", "UPDATE", "DELETE"):
        assert f"FOR {op}" in SQL, f"缺 {op} policy"


def test_RLS必须带internal逃生门():
    """死信表是**运维要读的表**。

    没有逃生门的话, 内部工具用 __internal__ 上下文查询会拿到假 0 行 ——
    看起来「没有坏记录」而实际有。本仓 fact_pos_* 老表就没有逃生门,
    **不能照抄那批**。
    """
    # 四条 policy 各自都要有, 不能只在 SELECT 上有
    assert SQL.count("'__internal__'") >= 5, (
        "逃生门数量不足 —— 应当每条 policy 的每个子句都有"
    )


def test_按单据幂等():
    """同一条坏单据被反复拉到时只应更新一行, 不能刷屏。"""
    assert "UNIQUE (factory_id, platform, kind, source_ref)" in SQL


def test_保留原始报文与人读原因():
    """没有 payload 就无法人工核对与重放; 没有 reason 就只能猜为什么坏。"""
    assert "payload" in SQL and "JSONB" in SQL
    assert "reason" in SQL


def test_不删行而是标记已处理():
    """保留审计痕迹 —— 隔离过什么、什么时候处理的, 事后要能查。"""
    assert "resolved_at" in SQL


def test_不在既有大表上加索引():
    """本 migration 只应动新表。在 fact_pos_* 上建索引会锁表。"""
    for stmt in re.findall(r"CREATE INDEX.*?;", SQL, re.S | re.I):
        assert "platform_ingest_dead_letter" in stmt, f"索引建到别的表上了: {stmt}"
