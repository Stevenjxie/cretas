"""V20261101_01 的静态契约测试。

这条 migration 会跑在 prod 上, 且 deploy-smartbi-python.sh Step 3.5 失败即
ABORT 整个 Python 部署。下面几条断言守的都是"写错了会炸线上"的点, 不是风格。
"""
import pathlib
import re

MIG = (pathlib.Path(__file__).resolve().parents[1]
       / "smartbi" / "database" / "migrations"
       / "V20261101_01__platform_sync_and_mock_tenant.sql")

SQL = MIG.read_text(encoding="utf-8")


def test_migration存在且是单事务():
    assert SQL.count("BEGIN;") == 1
    assert SQL.count("COMMIT;") == 1


def test_两张新表都开启并强制RLS():
    for table in ("platform_sync_cursor", "platform_store_map"):
        assert f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY" in SQL, f"{table} 未开 RLS"
        assert f"ALTER TABLE {table} FORCE ROW LEVEL SECURITY" in SQL, f"{table} 未强制 RLS"


def test_两张新表各有四条policy含DELETE():
    for table in ("platform_sync_cursor", "platform_store_map"):
        for cmd in ("select", "insert", "update", "delete"):
            assert f"CREATE POLICY {table}_{cmd} ON {table}" in SQL, f"{table} 缺 {cmd} policy"


def test_不得在既有大表上新建索引():
    """幂等用 fact_pos_transaction 上**现成的** uq_fact_pos_txn 唯一约束
    (factory_id, source_type, store_id, source_bill_no), 不新建索引。

    该表 1,382,267 行, 加索引有真实代价; 而现成约束已经够用。

    踩坑留痕: 起初以为无唯一约束, 依据是 (factory_id, source_type,
    source_bill_no) 上有 151,978 组"重复" —— 那个统计漏了 store_id,
    真实唯一键含 store_id, 同一单号跨门店本来合法。误判来自查 \\d 时
    head 截断切掉了 Indexes 段。这条测试防止那个多余索引被重新加回来。
    """
    assert not re.search(
        r"CREATE\s+(UNIQUE\s+)?INDEX[^;]*?ON\s+fact_pos_transaction", SQL, re.S | re.I
    ), "不要在 fact_pos_transaction 上新建索引, 用现成的 uq_fact_pos_txn"


def test_不得改dim_store结构():
    """dim_store 被 23 处外键引用, 不为这个功能改它的结构。

    门店映射走新建的 platform_store_map。
    """
    assert "ALTER TABLE dim_store" not in SQL, "不许改 dim_store 结构"
    assert "platform_store_map" in SQL, "门店映射必须建在 platform_store_map"


def test_门店code与模拟端一一对应():
    codes = set(re.findall(r"'(MK\d{2})'", SQL))
    assert codes == {f"MK{i:02d}" for i in range(1, 11)}, (
        f"门店 code 必须是 MK01..MK10, 实际 {sorted(codes)}"
    )


def test_门店名与模拟端seed逐字一致():
    """migration 按 (factory_id, name) 唯一约束定位门店, name 对不上就 JOIN 不到,
    平台映射会静默少行 —— 所有订单随之落不进来。
    """
    seed = (pathlib.Path(__file__).resolve().parents[3]
            / "mock-platform" / "mock_platform" / "world" / "seed.py")
    seed_names = set(re.findall(r'\("MK\d{2}", "([^"]+)"', seed.read_text(encoding="utf-8")))
    assert len(seed_names) == 10, f"模拟端应有 10 个门店名, 解析到 {len(seed_names)}"
    for name in seed_names:
        assert name in SQL, f"migration 缺门店 {name!r}(与模拟端 seed.py 不一致)"


def test_支付渠道覆盖模拟端全部支付方式():
    """fact_pos_payment.channel_id 是 NOT NULL 外键, 渠道没种全会让 writer
    在运行期抛"渠道映射失败", 整批订单写不进来。

    ⚠️ 这条测试必须有**跨文件判别力**: 从模拟端 generator.py 真解析出
    支付方式集合, 再要求 migration 的映射注释与种子行覆盖它。
    早先版本只是把四个中文名硬编码在测试里跟 migration 自比对 —— 那样
    generator.py 新增一种支付方式时测试不会红, 等于没测。
    """
    gen = (pathlib.Path(__file__).resolve().parents[3]
           / "mock-platform" / "mock_platform" / "world" / "generator.py")
    gen_src = gen.read_text(encoding="utf-8")

    block = re.search(r"_PAY_BY_CHANNEL\s*=\s*\{(.*?)\n\}", gen_src, re.S)
    assert block, "模拟端 generator.py 里找不到 _PAY_BY_CHANNEL"
    methods = set(re.findall(r'"(\w+)"', block.group(1)))
    # 键是渠道(dine_in/takeaway/groupon), 值才是支付方式 —— 去掉键
    methods -= {"dine_in", "takeaway", "groupon"}
    assert methods, "解析不出任何支付方式, 正则要跟着 generator.py 改"

    # migration 里的 method→中文名映射注释, 是两端唯一的对照表
    mapping = dict(re.findall(r"(\w+)→(\S+)", SQL))
    missing = methods - set(mapping)
    assert not missing, (
        f"模拟端有支付方式 {sorted(missing)} 未在 migration 的映射注释里声明 —— "
        f"writer 会在运行期抛'渠道映射失败'"
    )
    for method in methods:
        name = mapping[method]
        assert f"'{name}'" in SQL, f"支付方式 {method} 对应的渠道 {name!r} 没被种进去"


def test_种的渠道数与模拟端支付方式数一致():
    """防止 migration 多种或少种渠道 —— 两边数量必须对齐。"""
    gen = (pathlib.Path(__file__).resolve().parents[3]
           / "mock-platform" / "mock_platform" / "world" / "generator.py")
    block = re.search(r"_PAY_BY_CHANNEL\s*=\s*\{(.*?)\n\}", gen.read_text(encoding="utf-8"), re.S)
    methods = set(re.findall(r'"(\w+)"', block.group(1))) - {"dine_in", "takeaway", "groupon"}
    seeded = re.search(
        r"INSERT INTO dim_payment_channel[^;]*?VALUES(.*?);", SQL, re.S
    )
    assert seeded, "找不到 dim_payment_channel 的种子语句"
    seeded_rows = re.findall(r"\('MOCK_REST',", seeded.group(1))
    assert len(seeded_rows) == len(methods), (
        f"migration 种了 {len(seeded_rows)} 个渠道, 模拟端有 {len(methods)} 种支付方式"
    )


def test_有落地自检不容许静默空租户():
    """ON CONFLICT DO NOTHING 会把"被拒"伪装成"已存在", 静默产出空租户。
    migration 里必须有显式行数断言, 不满足就回滚。
    """
    assert "RAISE EXCEPTION" in SQL, "缺少种子落地自检"
    assert "v_stores <> 10" in SQL, "缺少门店行数断言"
    assert "v_maps <> 10" in SQL, "缺少门店映射行数断言"
    assert "v_channels <> 4" in SQL, "缺少支付渠道行数断言"
