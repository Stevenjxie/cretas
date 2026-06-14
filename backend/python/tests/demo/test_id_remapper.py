from scripts.demo.id_remapper import IdRemapper

def test_bigint_offset_is_deterministic_and_reversible_via_map():
    r = IdRemapper(offset=1_000_000_000, shortcode="DR")
    new = r.new_bigint("users", 42)
    assert new == 1_000_000_042
    assert r.lookup("users", 42) == 1_000_000_042  # stored in map

def test_bigint_same_old_id_same_new_id():
    r = IdRemapper(offset=500_000_000, shortcode="DF")
    assert r.new_bigint("sales_order_items", 7) == r.new_bigint("sales_order_items", 7)

def test_varchar_pk_new_id_fits_length_and_is_stable():
    r = IdRemapper(offset=0, shortcode="DR")
    a = r.new_varchar("suppliers", "RES_3101_009-SUP-0001")
    b = r.new_varchar("suppliers", "RES_3101_009-SUP-0001")
    assert a == b                      # deterministic per run
    assert a.startswith("DR_")         # target-coded prefix
    assert len(a) <= 64                # fits the tightest varchar PK (factory_warehouses)

def test_varchar_distinct_old_ids_get_distinct_new_ids():
    r = IdRemapper(offset=0, shortcode="DR")
    assert r.new_varchar("suppliers", "A") != r.new_varchar("suppliers", "B")

def test_lookup_unknown_returns_none():
    r = IdRemapper(offset=0, shortcode="DR")
    assert r.lookup("suppliers", "never-seen") is None
