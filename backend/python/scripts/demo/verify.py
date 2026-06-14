"""Verification gates for a cloned demo tenant. Exit non-zero on any failure."""
import argparse, asyncio, sys
import asyncpg
from scripts.demo import clone_config as cfg

# Real tokens that MUST NOT appear in the demo tenant (extend with sampled real names/phones).
FORBIDDEN_TOKENS = ["青花椒"]


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
        if s != t:
            bad.append(f"{e['table']}: src={s} tgt={t}")
    return bad


async def gate_fk_integrity(conns, target):
    bad = []
    for e in cfg.TABLE_REGISTRY:
        cn = conns[e["db"]]
        for col, parent in e.get("fk", {}).items():
            pe = next((x for x in cfg.TABLE_REGISTRY if x["table"] == parent), None)
            if not pe:
                continue
            dangling = await cn.fetchval(f"""
                SELECT count(*) FROM {e['table']} c
                WHERE c.factory_id=$1 AND c.{col} IS NOT NULL
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
        for col in textcols:
            for tok in FORBIDDEN_TOKENS:
                n = await cn.fetchval(
                    f"SELECT count(*) FROM {e['table']} WHERE factory_id=$1 AND {col} LIKE $2",
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
