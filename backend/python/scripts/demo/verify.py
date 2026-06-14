"""Verification gates for a cloned demo tenant. Exit non-zero on any failure."""
import argparse, asyncio, sys
import asyncpg
from scripts.demo import clone_config as cfg

# Real tokens that MUST NOT appear in the demo tenant's IDENTITY fields (extend with sampled real
# names/phones). NOTE: 青花椒 is both the client brand AND a common Sichuan spice — so it is allowed
# in cuisine-vocabulary columns (dish/ingredient/menu names), exempted below.
FORBIDDEN_TOKENS = ["青花椒"]

# (table, column) pairs where a brand token == generic cuisine vocabulary, not client identity.
DEIDENT_EXEMPT = {
    ("product_types", "name"),
    ("dim_product", "name"),
    ("dim_product", "normalized_name"),
    ("dim_ingredient", "name"),
    ("dim_ingredient", "normalized_name"),
    ("raw_material_types", "name"),         # 青花椒 = a raw spice ingredient
    ("suppliers", "supplied_materials"),    # list of supplied ingredients
    ("purchase_order_items", "material_name"),
    ("fact_pos_item", "source_item_raw"),
}


def _target_scope(entry, ref):
    """SQL fragment ($1 = target factory) scoping a table's rows to the demo tenant, prefixing
    columns with `ref` (an alias or the table name). Tables with factory_id -> direct;
    child tables without factory_id (parent_filter) -> via the cloned parent's rows."""
    if entry.get("parent_filter"):
        fk_col, parent = entry["parent_filter"]
        pe = next((x for x in cfg.TABLE_REGISTRY if x["table"] == parent), None)
        return f"{ref}.{fk_col} IN (SELECT {pe['pk']} FROM {parent} WHERE factory_id=$1)"
    return f"{ref}.factory_id=$1"


async def gate_parity(conns, source, target):
    bad = []
    for e in cfg.TABLE_REGISTRY:
        cn = conns[e["db"]]
        cols = [r["column_name"] for r in await cn.fetch(
            "SELECT column_name FROM information_schema.columns WHERE table_name=$1", e["table"])]
        fcol = e.get("factory_col") or ("factory_id" if "factory_id" in cols else None)
        if not fcol:
            continue
        s = await cn.fetchval(f"SELECT count(*) FROM {e['table']} WHERE {fcol}=$1", source)
        t = await cn.fetchval(f"SELECT count(*) FROM {e['table']} WHERE {fcol}=$1", target)
        # users gets +1: the provisioned demo-login account (not cloned from source).
        expected_t = s + 1 if e["table"] == "users" else s
        if t != expected_t:
            bad.append(f"{e['table']}: src={s} tgt={t}")
    return bad


async def gate_fk_integrity(conns, target):
    bad = []
    for e in cfg.TABLE_REGISTRY:
        cn = conns[e["db"]]
        scope = _target_scope(e, "c")
        for col, parent in e.get("fk", {}).items():
            pe = next((x for x in cfg.TABLE_REGISTRY if x["table"] == parent), None)
            if not pe:
                continue
            dangling = await cn.fetchval(f"""
                SELECT count(*) FROM {e['table']} c
                WHERE {scope} AND c.{col} IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM {parent} p WHERE p.{pe['pk']}=c.{col})
            """, target)
            if dangling:
                bad.append(f"{e['table']}.{col}->{parent}: {dangling} dangling")
    return bad


async def gate_deidentify(conns, target):
    """grep every text column of the demo tenant for forbidden real tokens -> must be 0."""
    hits = []
    for e in cfg.TABLE_REGISTRY:
        cn = conns[e["db"]]
        textcols = [r["column_name"] for r in await cn.fetch(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_name=$1 AND data_type IN ('text','character varying')", e["table"])]
        scope = _target_scope(e, e["table"])
        for col in textcols:
            if (e["table"], col) in DEIDENT_EXEMPT:
                continue  # cuisine vocabulary (e.g. 青花椒鱼) — not client identity
            for tok in FORBIDDEN_TOKENS:
                n = await cn.fetchval(
                    f"SELECT count(*) FROM {e['table']} WHERE {scope} AND {e['table']}.{col} LIKE $2",
                    target, f"%{tok}%")
                if n:
                    hits.append(f"{e['table']}.{col}: {n} rows contain '{tok}'")
    return hits


async def run(tenant_key, target_override):
    t = cfg.TENANTS[tenant_key]
    source, target = t["source"], (target_override or t["target"])
    conns = {"cretas": await asyncpg.connect(cfg.CRETAS_DSN),
             "smartbi": await asyncpg.connect(cfg.SMARTBI_SUPER_DSN)}
    try:
        parity = await gate_parity(conns, source, target)
        fk = await gate_fk_integrity(conns, target)
        deid = await gate_deidentify(conns, target)
        print("PARITY:", "OK" if not parity else parity)
        print("FK    :", "OK" if not fk else fk)
        print("DEIDENT:", "OK" if not deid else deid)
        if parity or fk or deid:
            sys.exit(1)
        print("ALL GATES PASS")
    finally:
        for c in conns.values():
            await c.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--tenant", required=True, choices=list(cfg.TENANTS))
    ap.add_argument("--target-override")
    a = ap.parse_args()
    asyncio.run(run(a.tenant, a.target_override))
