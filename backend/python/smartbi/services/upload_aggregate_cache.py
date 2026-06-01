"""Two-tier cache + compute helper for upload-level aggregates.

L1: in-memory TTL dict (fast, per-process).
L2: smart_bi_pg_upload_aggregate_cache table (survives restarts).

Avoids re-running 15+ JSONB full scans on every AI chat query against the same
upload. Uploads are immutable after ingest, so a 1-hour TTL is safe on L1;
L2 never expires (explicit invalidate required — currently only if the upload
itself is reclassified).

Invalidate via `invalidate(upload_id)` when a new upload writes to the same id
(currently upload_id is auto-incremented so collisions don't happen, but the
API is there for future streaming/overwrite paths).

Cache key: upload_id (int)
Cache value: dict with keys:
  - field_meta: List[dict]          # possibly mutated by Bug #25 fallback
  - measures: List[str]              # measure column names
  - dims: List[str]                  # dimension column names
  - real_total_rows: int
  - agg_lines: List[str]             # text lines for LLM prompt (pre-entity)
  - top5_by_dim: Dict[str, List]     # structured top-5 for chart rendering
  - primary_measure: Optional[str]
  - compute_time_s: float            # SQL wall time (for observability)
"""
from __future__ import annotations

import json as _json
import logging
import re
import threading
import time
from typing import Any, Dict, List, Optional


# Intensive-measure patterns (rating/score/rate/per-unit) that must aggregate by AVG,
# not SUM. Kept INLINE (not imported from materialized_analytics.schema_helpers) on
# purpose: that import pulls smartbi.services.__init__ → excel_parser → bare `services`,
# which only resolves under the app's special sys.path setup and raised
# ModuleNotFoundError('services') at runtime here — silently falling back to SUM so the
# fix never fired. Mirror of schema_helpers._AVG_MEASURE_PATTERNS (#3 chat-path fix).
_INTENSIVE_MEASURE_PATTERNS = (
    '星级', '评分', '分数', '打分', '得分', '评价分', '环境分', '服务分', '口味分',
    '率', '比率', '占比', '百分比',
    '客单价', '人均', '单价', '均价',
)


def _measure_is_avg(col: Optional[str]) -> bool:
    """True if `col` is an intensive measure (星级分/评分/率/客单价) that must aggregate
    by AVG, not SUM. A SUM of star points ('总星级分 57974') is meaningless and, fed to
    the LLM, gets narrated as a headline. (#3 chat-path fix.)"""
    if not col:
        return False
    return any(p in col for p in _INTENSIVE_MEASURE_PATTERNS)


# Apr 26 2026 phase 5 (UX): mirror _is_id_like from materializer.py.
# Without filtering, qhj 4216 卡号 / 手机 (numeric is_measure=True) leaked
# into LLM aggregation context as "实收 总计 = 1,643,645,749,770" garbage
# (sum of card-IDs treated as money). qhj-24 / 06 / 29 LLM tail showed
# "Top5发卡门店按卡号分布: 青花椒新世界新丸中心店(497,533,214,596)" —
# user reads 497B as revenue. Trust 破坏.
_ID_LIKE_PATTERNS = (
    '卡号', '会员号', '会员编号', '卡编号', '手机', '电话',
    '订单号', '账单号', 'ID', 'id', '编号', '工号',
)


def _is_id_like(name: str) -> bool:
    return any(p in name for p in _ID_LIKE_PATTERNS)


logger = logging.getLogger(__name__)

_DEFAULT_TTL = 3600  # 1 hour


class _UploadAggregateCache:
    def __init__(self, ttl_seconds: int = _DEFAULT_TTL):
        self._store: dict[int, tuple[float, Any]] = {}
        self._lock = threading.Lock()
        self._ttl = ttl_seconds

    def get(self, upload_id: int) -> Optional[Any]:
        with self._lock:
            entry = self._store.get(upload_id)
            if entry is None:
                return None
            expires_at, value = entry
            if time.time() > expires_at:
                del self._store[upload_id]
                return None
            return value

    def set(self, upload_id: int, value: Any) -> None:
        with self._lock:
            self._store[upload_id] = (time.time() + self._ttl, value)

    def invalidate(self, upload_id: int) -> bool:
        with self._lock:
            return self._store.pop(upload_id, None) is not None

    def clear(self) -> int:
        with self._lock:
            n = len(self._store)
            self._store.clear()
            return n

    def stats(self) -> dict:
        with self._lock:
            now = time.time()
            live = sum(1 for (exp, _) in self._store.values() if exp > now)
            return {"total": len(self._store), "live": live}


_cache_instance = _UploadAggregateCache()


def get_cache() -> _UploadAggregateCache:
    return _cache_instance


_UNNAMED_PAT = re.compile(r'^(Unnamed:?\s*\d+|Column_?\d+)', re.IGNORECASE)
_PRIORITY_MEASURE_KW = ['销售金额', '销售额', '营业额', '营业收入', '实收', '主营业务收入', '金额']


async def compute_upload_aggregates(
    conn,
    pool,
    upload_id: int,
    field_meta: List[Dict[str, Any]],
    sample_size: int,
) -> Dict[str, Any]:
    """Compute authoritative aggregates over ALL rows of an upload.

    Heavy: ~19 full JSONB scans on smart_bi_dynamic_data (200K+ rows).
    Callers should wrap with get_cache() to amortize across repeat queries.

    Args:
      conn: asyncpg connection (used for the initial key-check queries).
      pool: asyncpg pool (used for parallel fan-out of the 15+4+4 aggregate SQLs).
      upload_id: the upload to aggregate.
      field_meta: field definitions from smart_bi_pg_field_definitions.
                  May be mutated (Bug #25 fallback) — caller receives the mutated list back.
      sample_size: number of sample rows already loaded (just for the text label).

    Returns a dict; see module docstring for keys.
    """
    import asyncio

    t_start = asyncio.get_event_loop().time()

    # ── Bug #25 fallback: multi-level Excel may produce field_defs with clean
    # names (门店名称, 销售金额) whose original_name does NOT match the row_data
    # JSON keys (which stay as "Unnamed: X_level_0_..."). If most semantic names
    # are missing from row_data keys, drop them and keep only matching entries. ──
    actual_keys_row = await conn.fetch(
        """SELECT jsonb_object_keys(row_data) AS k
           FROM smart_bi_dynamic_data WHERE upload_id = $1 LIMIT 100""",
        upload_id
    )
    actual_keys = {r['k'] for r in (actual_keys_row or [])}
    semantic_defs = [f for f in field_meta if not _UNNAMED_PAT.match(str(f['original_name'] or ''))]
    semantic_matched = sum(1 for f in semantic_defs if f['original_name'] in actual_keys)
    if semantic_defs and semantic_matched / max(len(semantic_defs), 1) < 0.2:
        logger.warning(
            f"[agg] Bug #25 fallback: only {semantic_matched}/{len(semantic_defs)} "
            f"semantic names match row_data; keeping only row_data-matching entries"
        )
        field_meta = [f for f in field_meta if f['original_name'] in actual_keys]

    # Phase 5 (UX): exclude ID-like columns from BOTH measures and dims so
    # LLM prompt context doesn't show "Top5发卡门店按卡号分布: 青花椒新丸
    # 中心店(497,533,214,596)" garbage that confuses user about real revenue.
    measures = [
        f['original_name'] for f in field_meta
        if f.get('is_measure') and not _is_id_like(f['original_name'])
    ]
    dims = [
        f['original_name'] for f in field_meta
        if f.get('is_dimension') and not _is_id_like(f['original_name'])
    ]

    # ── Total row count ──
    agg_row = await conn.fetchrow(
        "SELECT COUNT(*) AS total FROM smart_bi_dynamic_data WHERE upload_id = $1",
        upload_id
    )
    real_total_rows = agg_row['total'] if agg_row else 0
    agg_lines: List[str] = [
        f"- 全量数据行数: {real_total_rows} (样本 {sample_size} 行仅用于字段示意)"
    ]

    # Bug #21: exclude 合计/小计/Total meta-rows from SUM/MAX/MIN.
    # Check first dimension (typically 门店名称 / 产品名 / 项目) for meta labels.
    meta_dim = dims[0] if dims else None
    meta_where = ""
    meta_args_tail: List[Any] = []
    if meta_dim:
        meta_where = (
            " AND (row_data->>$3 IS NULL OR row_data->>$3 NOT IN "
            "('合计','总计','Total','TOTAL','小计','汇总','总额','Sum','sum'))"
        )
        meta_args_tail = [meta_dim]

    # ── Parallel per-measure totals + per-dim distinct counts ──
    # 15 measures + 4 dims fan-out via pool.acquire. Pool must have ≥19 conns.
    measure_sql = (
        f"""SELECT SUM((row_data->>$1)::numeric) AS s,
                   AVG((row_data->>$1)::numeric) AS a,
                   MIN((row_data->>$1)::numeric) AS mn,
                   MAX((row_data->>$1)::numeric) AS mx,
                   COUNT((row_data->>$1)::numeric) AS c
           FROM smart_bi_dynamic_data
           WHERE upload_id = $2 AND row_data->>$1 ~ '^-?[0-9.,]+$'
           {meta_where}"""
    )
    dim_distinct_sql = (
        """SELECT COUNT(DISTINCT row_data->>$1) AS c
           FROM smart_bi_dynamic_data
           WHERE upload_id = $2
             AND row_data->>$1 IS NOT NULL
             AND row_data->>$1 NOT IN ('合计','总计','Total','TOTAL','小计')"""
    )

    async def _run_measure(col):
        async with pool.acquire() as c2:
            return col, await c2.fetchrow(measure_sql, col, upload_id, *meta_args_tail)

    async def _run_dim_distinct(col):
        async with pool.acquire() as c2:
            return col, await c2.fetchrow(dim_distinct_sql, col, upload_id)

    measure_tasks = [_run_measure(m) for m in measures[:15]]
    dim_tasks = [_run_dim_distinct(d) for d in dims[:4]]
    measure_res, dim_res = await asyncio.gather(
        asyncio.gather(*measure_tasks, return_exceptions=True) if measure_tasks else asyncio.sleep(0, result=[]),
        asyncio.gather(*dim_tasks, return_exceptions=True) if dim_tasks else asyncio.sleep(0, result=[]),
    )
    # #3 chat-path fix: intensive measures (星级分/评分/率) report 均值 not 总计 — a
    # SUM of star points ('总星级分 57974') is meaningless and gets narrated by the
    # LLM as a headline. See module-level _measure_is_avg.
    _is_avg = _measure_is_avg

    for item in measure_res:
        if isinstance(item, Exception) or not item:
            continue
        m_col, r = item
        if r and r['c']:
            if _is_avg(m_col):
                # 评分/率类: 只报均值 + 范围, 不报无意义的总计 (避免 LLM 把 SUM 当头条)
                agg_lines.append(
                    f"- {m_col} (全量): 均值={r['a'] or 0:,.2f} (评分/率类，求和无意义), "
                    f"最大={r['mx'] or 0:,.2f}, 最小={r['mn'] or 0:,.2f}, "
                    f"有效行数={r['c']}"
                )
            else:
                agg_lines.append(
                    f"- {m_col} (全量): 总计={r['s'] or 0:,.2f}, "
                    f"均值={r['a'] or 0:,.2f}, "
                    f"最大={r['mx'] or 0:,.2f}, 最小={r['mn'] or 0:,.2f}, "
                    f"有效行数={r['c']}"
                )
    for item in dim_res:
        if isinstance(item, Exception) or not item:
            continue
        d_col, dc = item
        if dc and dc['c'] is not None:
            agg_lines.append(f"- {d_col} 不同值总数: {dc['c']}")

    # ── Pick revenue-like primary measure ──
    primary_measure: Optional[str] = None
    for kw in _PRIORITY_MEASURE_KW:
        for m in measures:
            if kw in m:
                primary_measure = m
                break
        if primary_measure:
            break
    if not primary_measure and measures:
        primary_measure = measures[0]

    # AVG for intensive primary measure (星级分/评分/率), SUM otherwise. The per-dim
    # AVG (上海市=4.83) is meaningful; the per-dim SUM (上海市=57974) is not.
    _agg_fn = "AVG" if (primary_measure and _is_avg(primary_measure)) else "SUM"
    _agg_word = "平均" if _agg_fn == "AVG" else "合计"

    # ── Top-5 per (dim × primary_measure) for top dims ──
    top5_by_dim: Dict[str, List[Dict[str, Any]]] = {}
    if primary_measure:
        top5_sql = (
            f"""SELECT row_data->>$1 AS label,
                      {_agg_fn}((row_data->>$2)::numeric) AS total
               FROM smart_bi_dynamic_data
               WHERE upload_id = $3
                 AND row_data->>$2 ~ '^-?[0-9.,]+$'
                 AND row_data->>$1 IS NOT NULL
                 AND row_data->>$1 NOT IN ('合计', '总计', 'Total', 'TOTAL', '小计')
               GROUP BY row_data->>$1
               ORDER BY total DESC NULLS LAST
               LIMIT 5"""
        )

        async def _run_top5(dim):
            async with pool.acquire() as c2:
                return dim, await c2.fetch(top5_sql, dim, primary_measure, upload_id)

        top5_res = await asyncio.gather(
            *[_run_top5(d) for d in dims[:4]], return_exceptions=True
        )
        for item in top5_res:
            if isinstance(item, Exception) or not item:
                continue
            dim, top_rows = item
            if top_rows:
                top5_by_dim[dim] = [
                    {"label": tr['label'], "total": float(tr['total'] or 0)}
                    for tr in top_rows
                ]
                top_str = ", ".join(
                    f"{tr['label']}={tr['total'] or 0:,.2f}"
                    for tr in top_rows
                )
                agg_lines.append(f"- Top5 by {dim} (按 {primary_measure} {_agg_word}): {top_str}")

    compute_time_s = asyncio.get_event_loop().time() - t_start
    logger.info(
        f"[agg] compute_upload_aggregates upload={upload_id}: "
        f"{len(measure_tasks)} measures + {len(dim_tasks)} dim_distincts + "
        f"{len(dims[:4]) if primary_measure else 0} top5 in {compute_time_s:.1f}s"
    )

    return {
        "field_meta": field_meta,
        "measures": measures,
        "dims": dims,
        "real_total_rows": real_total_rows,
        "agg_lines": agg_lines,
        "top5_by_dim": top5_by_dim,
        "primary_measure": primary_measure,
        "compute_time_s": compute_time_s,
    }


def compute_aggregates_from_polars(
    backend: Any,
    field_meta: List[Dict[str, Any]],
    sample_size: int = 0,
) -> Dict[str, Any]:
    """Polars-backed fast path: compute the same bundle shape as
    compute_upload_aggregates() but from an already-built polars DataFrame,
    not from JSONB scans.

    Reuses the DataFrame that materialize_upload constructs anyway, so this
    runs in ~0.5-2s instead of the 30-70s the SQL path takes on 200K rows.

    Callers that don't already have a polars DataFrame should stick with
    compute_upload_aggregates() — importing polars is expensive if the caller
    doesn't need it.
    """
    import polars as pl

    t_start = time.time()
    df = backend._df  # type: ignore[attr-defined]
    # Phase 5 (UX): same _is_id_like filter as the SQL path above.
    measures = [
        f["original_name"] for f in field_meta
        if f.get("is_measure") and not _is_id_like(f["original_name"])
    ]
    dims = [
        f["original_name"] for f in field_meta
        if f.get("is_dimension") and not _is_id_like(f["original_name"])
    ]

    real_total_rows = df.height
    agg_lines: List[str] = [
        f"- 全量数据行数: {real_total_rows} (样本 {sample_size} 行仅用于字段示意)"
    ]

    # Meta-row filter (same as SQL path): exclude 合计/总计/小计/汇总 in first dim
    meta_dim = dims[0] if dims else None
    meta_values = {"合计", "总计", "Total", "TOTAL", "小计", "汇总", "总额", "Sum", "sum"}
    filtered_df = df
    if meta_dim and meta_dim in df.columns:
        filtered_df = df.filter(
            pl.col(meta_dim).cast(pl.Utf8, strict=False).fill_null("").is_in(list(meta_values)).not_()
        )

    # Per-measure totals. Polars cast-with-strict-false treats non-numeric
    # values as null, matching the SQL regex filter ~ '^-?[0-9.,]+$'.
    for m in measures[:15]:
        if m not in filtered_df.columns:
            continue
        try:
            col = filtered_df.select(
                pl.col(m).cast(pl.Float64, strict=False)
            ).get_column(m)
            cnt = col.drop_nulls().len()
            if cnt == 0:
                continue
            s = col.sum()
            a = col.mean()
            mn = col.min()
            mx = col.max()
            agg_lines.append(
                f"- {m} (全量): 总计={float(s or 0):,.2f}, "
                f"均值={float(a or 0):,.2f}, "
                f"最大={float(mx or 0):,.2f}, 最小={float(mn or 0):,.2f}, "
                f"有效行数={cnt}"
            )
        except Exception as e:
            logger.debug(f"[agg-polars] measure {m} failed: {e}")

    # Per-dim distinct counts
    for d in dims[:4]:
        if d not in filtered_df.columns:
            continue
        try:
            distinct = (
                filtered_df.select(pl.col(d))
                .drop_nulls()
                .filter(pl.col(d).cast(pl.Utf8, strict=False).is_in(list(meta_values)).not_())
                .unique()
                .height
            )
            agg_lines.append(f"- {d} 不同值总数: {distinct}")
        except Exception as e:
            logger.debug(f"[agg-polars] dim distinct {d} failed: {e}")

    # Primary measure pick (same priority keywords as the SQL path)
    primary_measure: Optional[str] = None
    for kw in _PRIORITY_MEASURE_KW:
        for m in measures:
            if kw in m:
                primary_measure = m
                break
        if primary_measure:
            break
    if not primary_measure and measures:
        primary_measure = measures[0]

    # Top-5 per dim × primary_measure (AVG for 星级分/评分/率, SUM otherwise)
    top5_by_dim: Dict[str, List[Dict[str, Any]]] = {}
    _p_is_avg = _measure_is_avg(primary_measure)
    _agg_word2 = "平均" if _p_is_avg else "合计"
    if primary_measure and primary_measure in filtered_df.columns:
        for d in dims[:4]:
            if d not in filtered_df.columns:
                continue
            try:
                _measure_expr = pl.col(primary_measure).cast(pl.Float64, strict=False)
                _agg_expr = (_measure_expr.mean() if _p_is_avg else _measure_expr.sum()).alias("total")
                top = (
                    filtered_df
                    .filter(pl.col(d).is_not_null())
                    .filter(pl.col(d).cast(pl.Utf8, strict=False).is_in(list(meta_values)).not_())
                    .group_by(d)
                    .agg(_agg_expr)
                    .sort("total", descending=True, nulls_last=True)
                    .head(5)
                )
                top_rows = top.to_dicts()
                if top_rows:
                    top5_by_dim[d] = [
                        {"label": str(r[d]) if r[d] is not None else "", "total": float(r["total"] or 0)}
                        for r in top_rows
                    ]
                    top_str = ", ".join(
                        f"{r['label']}={r['total']:,.2f}" for r in top5_by_dim[d]
                    )
                    agg_lines.append(f"- Top5 by {d} (按 {primary_measure} {_agg_word2}): {top_str}")
            except Exception as e:
                logger.debug(f"[agg-polars] top5 {d} failed: {e}")

    compute_time_s = time.time() - t_start
    logger.info(
        f"[agg-polars] computed {len(measures[:15])} measures + "
        f"{len(dims[:4])} dim_distincts + {len(top5_by_dim)} top5 "
        f"in {compute_time_s:.2f}s (polars in-memory)"
    )

    return {
        "field_meta": field_meta,
        "measures": measures,
        "dims": dims,
        "real_total_rows": real_total_rows,
        "agg_lines": agg_lines,
        "top5_by_dim": top5_by_dim,
        "primary_measure": primary_measure,
        "compute_time_s": compute_time_s,
    }


# ── L2 persistence (smart_bi_pg_upload_aggregate_cache) ──

_TABLE = "smart_bi_pg_upload_aggregate_cache"


async def load_bundle_from_db(pool, upload_id: int) -> Optional[Dict[str, Any]]:
    """Try to read a cached bundle from the DB table.

    Returns None if the row doesn't exist (first query for this upload after
    a deploy, or upload hasn't been pre-warmed yet). Returns the bundle dict
    otherwise — shape matches compute_upload_aggregates() output.
    """
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                f"SELECT payload, compute_time_s FROM {_TABLE} WHERE upload_id = $1",
                upload_id,
            )
    except Exception as e:
        # Table missing (migration not run) or DB unreachable — fall back to
        # compute. Don't crash the chat endpoint on cache infrastructure issues.
        logger.warning(f"[agg-cache] L2 load failed for upload={upload_id}: {e}")
        return None
    if row is None:
        return None
    raw = row["payload"]
    # asyncpg returns JSONB as str on some driver versions; decode if so.
    payload = _json.loads(raw) if isinstance(raw, str) else raw
    if not isinstance(payload, dict):
        return None
    # Preserve observability field from the column, since payload may pre-date it.
    if row["compute_time_s"] is not None:
        payload["compute_time_s"] = float(row["compute_time_s"])
    return payload


async def save_bundle_to_db(
    pool, upload_id: int, bundle: Dict[str, Any], factory_id: Optional[str] = None,
) -> bool:
    """Upsert the bundle into the L2 cache table.

    Returns True on success, False on failure (e.g., migration not yet run).
    Failure is non-fatal: L1 still serves the caller for this process.
    """
    try:
        payload_json = _json.dumps(bundle, ensure_ascii=False, default=str)
        compute_time = float(bundle.get("compute_time_s") or 0.0)
        async with pool.acquire() as conn:
            await conn.execute(
                f"""INSERT INTO {_TABLE} (upload_id, factory_id, payload, compute_time_s, updated_at)
                    VALUES ($1, $2, $3::jsonb, $4, NOW())
                    ON CONFLICT (upload_id) DO UPDATE
                       SET factory_id = EXCLUDED.factory_id,
                           payload = EXCLUDED.payload,
                           compute_time_s = EXCLUDED.compute_time_s,
                           updated_at = NOW()""",
                upload_id, factory_id, payload_json, compute_time,
            )
        return True
    except Exception as e:
        logger.warning(f"[agg-cache] L2 save failed for upload={upload_id}: {e}")
        return False


async def invalidate_bundle_in_db(pool, upload_id: int) -> bool:
    """Remove the L2 cache row for an upload. Used when field_defs change
    (e.g., after reclassify changes measure/dimension roles)."""
    try:
        async with pool.acquire() as conn:
            result = await conn.execute(
                f"DELETE FROM {_TABLE} WHERE upload_id = $1", upload_id
            )
        return result.upper().startswith("DELETE")
    except Exception as e:
        logger.warning(f"[agg-cache] L2 invalidate failed for upload={upload_id}: {e}")
        return False


async def get_or_compute(
    pool,
    conn,
    upload_id: int,
    field_meta: List[Dict[str, Any]],
    sample_size: int,
    factory_id: Optional[str] = None,
    on_heartbeat=None,
) -> Dict[str, Any]:
    """Two-tier get-or-compute.

    Order:
      1. L1 in-memory cache (fast, ~microseconds).
      2. L2 DB cache (~ms, survives restarts).
      3. Cold compute (30-70s on 200K-row upload).

    Args:
      pool: asyncpg pool for L2 operations + compute fan-out.
      conn: asyncpg connection the caller already holds (reused for the
        compute's initial key-check queries to avoid double-acquire).
      upload_id: target upload.
      field_meta: field definitions — may be mutated by compute (Bug #25 fallback).
      sample_size: for the "样本 N 行" label only.
      factory_id: stored in L2 for cross-tenant audit/cleanup.
      on_heartbeat: optional async callable invoked on the cold-compute path to
        emit progress. Not invoked on L1/L2 hit. Caller owns the semantics.

    Returns the bundle dict.
    """
    cache = get_cache()
    bundle = cache.get(upload_id)
    if bundle is not None:
        logger.info(f"[agg-cache] L1 hit upload={upload_id}")
        return bundle
    bundle = await load_bundle_from_db(pool, upload_id)
    if bundle is not None:
        logger.info(
            f"[agg-cache] L2 hit upload={upload_id} "
            f"(precomputed in {bundle.get('compute_time_s', 0):.1f}s)"
        )
        cache.set(upload_id, bundle)
        return bundle
    # Cold path — compute + populate both tiers.
    logger.info(f"[agg-cache] miss upload={upload_id}, cold compute starting")
    if on_heartbeat is not None:
        try:
            await on_heartbeat("cold_start")
        except Exception:
            pass  # heartbeat failures shouldn't block compute
    bundle = await compute_upload_aggregates(
        conn, pool, upload_id, field_meta, sample_size
    )
    cache.set(upload_id, bundle)
    await save_bundle_to_db(pool, upload_id, bundle, factory_id=factory_id)
    return bundle
