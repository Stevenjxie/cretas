"""Core: clone one table's rows source->target with id remap, fk rewrite, factory rewrite, masking."""
from __future__ import annotations  # Python 3.8 (server venv38): defer `list[str]` etc. annotations
from scripts.demo.clone_config import MASK_REGISTRY


async def fetch_columns(conn, table: str) -> list[str]:
    rows = await conn.fetch(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_name = $1 ORDER BY ordinal_position", table)
    return [r["column_name"] for r in rows]


def _transform_row(row: dict, entry: dict, remapper, masker, target_factory: str, columns: list[str]) -> dict:
    out = dict(row)
    table = entry["table"]
    pk, pk_type = entry["pk"], entry["pk_type"]
    # 1. new PK
    if pk_type == "bigint":
        out[pk] = remapper.new_bigint(table, row[pk])
    else:
        out[pk] = remapper.new_varchar(table, row[pk])
    # 2. FK rewrite via parent maps
    for col, parent in entry.get("fk", {}).items():
        if col in out and out[col] is not None:
            mapped = remapper.lookup(parent, out[col])
            out[col] = mapped  # None if parent row not cloned -> see caveats; engine nulls FK-optional
    # 3. factory_id rewrite (if column exists)
    fcol = entry.get("factory_col")
    if fcol and fcol in out:
        out[fcol] = target_factory
    elif "factory_id" in columns:        # items with factory_col None but column present
        out["factory_id"] = target_factory
    # 4. username rename (global unique) handled here
    if entry.get("rename_username") and "username" in out and out["username"] is not None:
        out["username"] = f"{remapper.shortcode.lower()}_{out['username']}"[:255]
    # 5. masking (identity only; numbers untouched)
    for col, fn in MASK_REGISTRY.get(table, {}).items():
        if col in out:
            out[col] = getattr(masker, fn)(out[col])
    # 6. staff_id -> dim_staff not cloned -> null
    if table == "fact_pos_transaction" and "staff_id" in out:
        out["staff_id"] = None
    return out


async def clone_table(src_conn, dst_conn, entry, remapper, masker, source_factory, target_factory):
    table = entry["table"]
    columns = await fetch_columns(src_conn, table)
    fcol = entry.get("factory_col") or "factory_id"
    src_rows = await src_conn.fetch(f"SELECT * FROM {table} WHERE {fcol} = $1", source_factory) \
        if fcol in columns else await src_conn.fetch(f"SELECT * FROM {table}")
    if not src_rows:
        return 0
    transformed = [_transform_row(dict(r), entry, remapper, masker, target_factory, columns) for r in src_rows]
    # Bulk insert via executemany on explicit column list
    cols = list(transformed[0].keys())
    placeholders = ", ".join(f"${i+1}" for i in range(len(cols)))
    sql = f"INSERT INTO {table} ({', '.join(cols)}) VALUES ({placeholders})"
    await dst_conn.executemany(sql, [[row[c] for c in cols] for row in transformed])
    return len(transformed)
