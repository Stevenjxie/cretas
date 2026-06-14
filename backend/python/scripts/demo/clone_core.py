"""Core: clone one table's rows source->target with id remap, fk rewrite, factory rewrite, masking."""
from __future__ import annotations  # Python 3.8 (server venv38): defer `list[str]` etc. annotations
from scripts.demo.clone_config import MASK_REGISTRY


async def fetch_columns(conn, table: str) -> list[str]:
    rows = await conn.fetch(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_name = $1 ORDER BY ordinal_position", table)
    return [r["column_name"] for r in rows]


async def fetch_not_null_cols(conn, table: str) -> set:
    rows = await conn.fetch(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_name=$1 AND is_nullable='NO'", table)
    return {r["column_name"] for r in rows}


def _transform_row(row: dict, entry: dict, remapper, masker, target_factory: str, columns: list[str],
                   default_user_id=None) -> dict:
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
            if mapped is None and parent == "users" and default_user_id is not None:
                mapped = default_user_id  # cross-factory/platform user not cloned -> demo admin (Gap 1)
            out[col] = mapped  # None if parent not cloned (FK-optional columns)
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


async def clone_table(src_conn, dst_conn, entry, remapper, masker, source_factory, target_factory,
                      default_user_id=None):
    table = entry["table"]
    columns = await fetch_columns(src_conn, table)
    fcol = entry.get("factory_col") or "factory_id"
    if fcol in columns:
        # table is tenant-scoped -> filter by factory_id
        src_rows = await src_conn.fetch(f"SELECT * FROM {table} WHERE {fcol} = $1", source_factory)
    elif entry.get("parent_filter"):
        # child table with no factory_id (e.g. sales_order_items) -> only clone children of
        # already-cloned parents, else we'd grab every tenant's rows (Gap 2).
        fk_col, parent = entry["parent_filter"]
        parent_old_ids = remapper.cloned_old_ids(parent)
        if not parent_old_ids:
            return 0
        src_rows = await src_conn.fetch(f"SELECT * FROM {table} WHERE {fk_col} = ANY($1)", parent_old_ids)
    else:
        src_rows = await src_conn.fetch(f"SELECT * FROM {table}")
    if not src_rows:
        return 0
    transformed = [_transform_row(dict(r), entry, remapper, masker, target_factory, columns, default_user_id)
                   for r in src_rows]
    # Drop orphan rows: a NOT-NULL FK to a cloned parent that didn't resolve means the row points to
    # a parent outside this tenant (legacy/dangling data) — keeping it would violate NOT NULL. User-FKs
    # already fell back to the demo admin in _transform_row; this catches non-user required FKs.
    not_null = await fetch_not_null_cols(src_conn, table)
    required_fk = [c for c in entry.get("fk", {}) if c in not_null]
    if required_fk:
        pk = entry["pk"]
        kept = []
        for src, tr in zip(src_rows, transformed):
            if all(tr.get(c) is not None for c in required_fk):
                kept.append(tr)
            else:
                remapper.drop(table, src[pk])  # drop mapping so this row's children also drop (cascade)
        if len(kept) != len(transformed):
            print(f"    [drop] {table}: {len(transformed) - len(kept)} orphan rows (unresolved required FK {required_fk})")
        transformed = kept
    if not transformed:
        return 0
    # Bulk insert via executemany on explicit column list
    cols = list(transformed[0].keys())
    placeholders = ", ".join(f"${i+1}" for i in range(len(cols)))
    sql = f"INSERT INTO {table} ({', '.join(cols)}) VALUES ({placeholders})"
    await dst_conn.executemany(sql, [[row[c] for c in cols] for row in transformed])
    return len(transformed)
