"""Clone a source tenant into a fresh de-identified demo tenant.

Usage:
  python -m scripts.demo.clone_tenant --tenant rest [--target-override DEMO_REST_SCRATCH] [--reset] [--dry-run]
"""
import argparse, asyncio
import asyncpg
from scripts.demo import clone_config as cfg
from scripts.demo.clone_core import clone_table, fetch_columns
from scripts.demo.id_remapper import IdRemapper
from scripts.demo.masker import Masker
from scripts.demo import provision


async def _reset(conns, target):
    # delete child->parent (reverse registry order)
    for entry in reversed(cfg.TABLE_REGISTRY):
        conn = conns[entry["db"]]
        cols = await fetch_columns(conn, entry["table"])
        fcol = entry.get("factory_col") or ("factory_id" if "factory_id" in cols else None)
        if fcol and fcol in cols:
            await conn.execute(f"DELETE FROM {entry['table']} WHERE {fcol} = $1", target)
    c = conns["cretas"]
    await c.execute("DELETE FROM users WHERE factory_id=$1", target)
    await c.execute("DELETE FROM factory_settings WHERE factory_id=$1", target)
    await c.execute("DELETE FROM factories WHERE id=$1", target)


async def run(tenant_key, target_override, reset, dry_run):
    t = cfg.TENANTS[tenant_key]
    source, target = t["source"], (target_override or t["target"])
    shortcode = cfg.TARGET_SHORTCODE.get(target, "DX")
    conns = {
        "cretas": await asyncpg.connect(cfg.CRETAS_DSN),
        "smartbi": await asyncpg.connect(cfg.SMARTBI_SUPER_DSN),  # BYPASSRLS role
    }
    try:
        if reset:
            print(f"[reset] deleting target {target}")
            await _reset(conns, target)
        if dry_run:
            print(f"[dry-run] would clone {source} -> {target} ({t['name']}, {t['type']})")
            for e in cfg.TABLE_REGISTRY:
                cn = conns[e["db"]]; cols = await fetch_columns(cn, e["table"])
                fcol = e.get("factory_col") or ("factory_id" if "factory_id" in cols else None)
                n = (await cn.fetchval(f"SELECT count(*) FROM {e['table']} WHERE {fcol}=$1", source)) if fcol else 0
                print(f"  {e['table']}: {n} rows")
            return
        # 1. provision target factory + settings + demo-login user (its id = fallback owner).
        #    username derived from target (not tenant_key) so a scratch target like DEMO_FACTORY2
        #    gets its own demo_factory2 login instead of colliding with DEMO_FACTORY's demo_factory.
        await provision.provision_factory(conns["cretas"], target, t["name"], t["type"])
        demo_username = "demo_" + target.replace("DEMO_", "").lower()
        demo_user_id = await provision.provision_demo_user(conns["cretas"], target, demo_username)
        # 2. clone tables in order
        remapper = IdRemapper(offset=0, shortcode=shortcode)  # offset set per-db below
        masker = Masker()
        for e in cfg.TABLE_REGISTRY:
            remapper.offset = cfg.OFFSET_SMARTBI if e["db"] == "smartbi" else cfg.OFFSET_CRETAS
            n = await clone_table(conns[e["db"]], conns[e["db"]], e, remapper, masker, source, target, demo_user_id)
            print(f"  cloned {e['table']}: {n}")
        print(f"[done] {source} -> {target}. Now run gold ETL (Task 8) + verify (Task 9).")
    finally:
        for c in conns.values():
            await c.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tenant", required=True, choices=list(cfg.TENANTS))
    ap.add_argument("--target-override")
    ap.add_argument("--reset", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    asyncio.run(run(a.tenant, a.target_override, a.reset, a.dry_run))


if __name__ == "__main__":
    main()
