"""
B MVP (Apr 20 2026, task #323): Async Excel upload pipeline.

Provides 2 endpoints:
- POST /api/smartbi/excel/auto-parse-async → 202 + uploadId immediately
- GET  /api/smartbi/excel/auto-parse-status/{upload_id} → poll status

Key design choices:
- Reuses existing smart_bi_pg_excel_uploads table (no migration). Status
  values: PENDING / PROCESSING / COMPLETED / FAILED. Cleanup of tmp file
  happens in the finally block of the BG worker.
- BG worker uses FastAPI BackgroundTasks (same process); for multi-worker
  uvicorn deployments each POST still runs in the worker that received it,
  and DB is the coordination mechanism.
- Parsing itself delegates to the existing /api/smartbi/excel/auto-parse
  endpoint via localhost httpx call — zero refactor to the 700-line
  sync parser in excel.py.

Scope NOT included (future iterations):
- DB schema progress_percent / progress_stage columns (spec optional)
- Resume on process restart (BG tasks are lost; caller needs to re-POST)
- Webhooks or SSE notification (polling-only for now)
- FE integration (handoff doc covers this; skipped this session)
"""

from __future__ import annotations

import logging
import os
import time
import uuid
from typing import Optional

from fastapi import APIRouter, BackgroundTasks, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse

from smartbi.database.connection import SessionLocal
from smartbi.database.models import SmartBiPgExcelUpload
from smartbi.canonical.sheet_merger import run_sheet_merge, sheet_merger_enabled
from smartbi.gold.dual_write import run_silver_dual_write, silver_dual_write_enabled
from smartbi.services.materialized_analytics.hooks import schedule_materialization

router = APIRouter(prefix="/api/smartbi/excel", tags=["Excel Async"])
logger = logging.getLogger(__name__)

ASYNC_TMP_DIR = "/tmp/smartbi_async_uploads"
# Self-call target; the running Python service accepts localhost without auth
# for /api/smartbi/excel/auto-parse per config.
_SYNC_PARSE_URL = "http://127.0.0.1:{port}/api/excel/auto-parse"


def _b_writers_enabled() -> bool:
    """Phase 3 wire-in opt-out switch. Default ON; flip to '0/false/no/off' to
    disable B-stage writer dispatch in case of regression.
    """
    val = os.environ.get("SMARTBI_ENABLE_B_WRITERS", "1").strip().lower()
    return val not in ("0", "false", "no", "off")


async def run_b_writers(factory_id: str, upload_id: int) -> None:
    """Phase 3 wire-in: invoke route_upload to dispatch B-stage Silver writers.

    Detects file shape (bill_flow / product_summary / review / finance /
    inventory / schedule / member / unknown) and dispatches to the
    corresponding B-stage writer. ``bill_flow`` uploads short-circuit to the
    legacy ``run_silver_dual_write`` path (already invoked elsewhere in this
    handler) — no double-write of fact_pos_*. Other shapes write to their
    respective Silver tables (agg_product_period, dim_review_summary,
    fact_finance_voucher, fact_inventory_snapshot, etc).

    Failures logged and swallowed — never block upload status. The legacy
    Silver+Gold dual-write still runs even if this raises.
    """
    if not _b_writers_enabled():
        return
    try:
        # Lazy imports — keep cold-start light + avoid circulars on module load.
        from smartbi.canonical.shape_router import route_upload
        from smartbi.canonical.entity_resolution import make_default_orchestrator
        from smartbi.config import get_settings
        from smartbi.tenant_ctx import (
            reset_factory_id,
            set_factory_id,
            set_pg_connection_tenant,
        )
        import asyncpg

        settings = get_settings()
        if not settings.postgres_url:
            logger.warning(
                "[b_writers] no postgres_url configured; skipping upload=%d",
                upload_id,
            )
            return

        token = set_factory_id(factory_id)
        try:
            pool = await asyncpg.create_pool(
                settings.postgres_url,
                min_size=1, max_size=2,
                setup=set_pg_connection_tenant,
                timeout=10,
            )
            try:
                orchestrator = make_default_orchestrator(pool)
                result = await route_upload(upload_id, factory_id, pool, orchestrator)
                logger.info(
                    "[b_writers] upload=%d factory=%s shape=%s routed_to=%s queued=%s",
                    upload_id, factory_id,
                    result.shape, result.routed_to, result.queued_for_admin,
                )
            finally:
                await pool.close()
        finally:
            reset_factory_id(token)
    except Exception:
        logger.exception(
            "[b_writers] failed for upload=%d factory=%s", upload_id, factory_id,
        )


@router.post("/auto-parse-async", status_code=202)
async def auto_parse_async(
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    factory_id: Optional[str] = Form(None),
    factoryId: Optional[str] = Form(None),  # alias
    sheet_index: Optional[int] = Form(None),
    sheetIndex: Optional[int] = Form(None),
    max_rows: int = Form(500000),
    selected_region_start: Optional[int] = Form(None),
    selected_region_end: Optional[int] = Form(None),
):
    """
    Start async Excel parsing. Returns uploadId immediately (202).

    Call GET /api/smartbi/excel/auto-parse-status/{uploadId} to poll status.
    """
    # Normalize aliases
    factory_id = factory_id or factoryId
    sheet_index = sheet_index if sheet_index is not None else sheetIndex
    if not factory_id:
        raise HTTPException(status_code=400, detail="factory_id is required")

    # Apr 26 2026 (S1 audit Bug B): fast-fail unsupported file extensions BEFORE
    # streaming the multipart body to disk. S1 test showed 4MB xlsx wasted 100s
    # of upload time before BG worker discovered the file was the wrong type.
    # Sync /upload-and-analyze (excel.py:677-684) already enforces this whitelist;
    # we mirror it here to give the user immediate 400 feedback.
    _filename_lower = (file.filename or "").lower()
    _allowed_exts = (".xlsx", ".xls", ".csv")
    if not _filename_lower.endswith(_allowed_exts):
        ext_actual = "." + _filename_lower.rsplit(".", 1)[-1] if "." in _filename_lower else "(no ext)"
        raise HTTPException(
            status_code=400,
            detail=f"不支持的文件类型: {ext_actual}; 仅支持 .xlsx, .xls, .csv",
        )

    if SessionLocal is None:
        raise HTTPException(status_code=503, detail="Database not available")

    os.makedirs(ASYNC_TMP_DIR, exist_ok=True)

    # 1. Save uploaded file to /tmp (stream to disk to avoid loading full
    #    content into memory for 500MB files).
    safe_suffix = os.path.basename(file.filename or "upload.xlsx")
    tmp_name = f"{uuid.uuid4().hex}_{safe_suffix}"
    tmp_path = os.path.join(ASYNC_TMP_DIR, tmp_name)
    total_bytes = 0
    try:
        with open(tmp_path, "wb") as tmp_f:
            while True:
                chunk = await file.read(1024 * 1024)  # 1 MB chunks
                if not chunk:
                    break
                tmp_f.write(chunk)
                total_bytes += len(chunk)
    except Exception as e:
        # Cleanup partial file on failure
        try:
            os.remove(tmp_path)
        except OSError:
            pass
        logger.exception("[async-upload] save failed")
        raise HTTPException(status_code=500, detail=f"File save failed: {e}")

    # 2. Create placeholder row (PENDING) to hand caller a trackable uploadId.
    db = SessionLocal()
    try:
        upload = SmartBiPgExcelUpload(
            factory_id=factory_id,
            file_name=safe_suffix,
            upload_status="PENDING",
        )
        db.add(upload)
        db.commit()
        db.refresh(upload)
        upload_id = upload.id
    except Exception as e:
        db.rollback()
        try:
            os.remove(tmp_path)
        except OSError:
            pass
        logger.exception("[async-upload] DB insert failed")
        raise HTTPException(status_code=500, detail=f"DB insert failed: {e}")
    finally:
        db.close()

    # 3. Schedule the BG task. httpx self-call runs in the worker's event loop.
    # FastAPI BackgroundTasks awaits async callables on the main event loop.
    # Registering _async_worker_impl directly (no asyncio.run wrapper) lets
    # the shared LLM httpx client (initialized at startup on that same loop)
    # be reused — otherwise we hit "got Future attached to a different loop".
    background_tasks.add_task(
        _async_worker_impl,
        upload_id=upload_id,
        tmp_path=tmp_path,
        factory_id=factory_id,
        sheet_index=sheet_index,
        max_rows=max_rows,
        selected_region_start=selected_region_start,
        selected_region_end=selected_region_end,
    )

    return JSONResponse(
        status_code=202,
        content={
            "success": True,
            "uploadId": upload_id,
            "status": "PENDING",
            "bytesReceived": total_bytes,
            "pollUrl": f"/api/smartbi/excel/auto-parse-status/{upload_id}",
            "message": "文件已接收,后台处理中。请轮询状态。",
        },
    )


@router.get("/auto-parse-status/{upload_id}")
async def auto_parse_status(upload_id: int, request: Request, factory_id: Optional[str] = None):
    """
    Poll status of async upload. Returns:
    - status (PENDING / PROCESSING / COMPLETED / FAILED)
    - fileName, rowCount, columnCount, detectedTableType (when COMPLETED)
    - error (when FAILED)

    Apr 26 2026 IDOR fix (P0 from security audit): endpoint was in
    PUBLIC_PREFIXES whitelist (/api/smartbi/excel/) so anyone could
    enumerate upload_ids and read other tenants' data including
    factoryId + 17 column names + 20 rows of preview business data.
    Now: explicit factory_id check.
    """
    if SessionLocal is None:
        raise HTTPException(status_code=503, detail="Database not available")
    db = SessionLocal()
    try:
        upload = (
            db.query(SmartBiPgExcelUpload).filter_by(id=upload_id).first()
        )
        if not upload:
            raise HTTPException(status_code=404, detail="Upload not found")

        # Security check: verify caller's factory_id matches upload's.
        # JWT path: request.state.factory_id populated by auth_middleware.
        # Internal Java path: X-Internal-Secret + X-Factory-Id header.
        # Public/anonymous: blocked.
        caller_factory_id = (
            getattr(request.state, "factory_id", None)
            if hasattr(request, "state") else None
        )
        # The endpoint is currently in PUBLIC_PREFIXES; auth_middleware doesn't
        # populate state. Re-extract from headers manually to enforce here.
        # Java internal poll: factory_id query param matches upload.factory_id.
        if not caller_factory_id and factory_id and factory_id == upload.factory_id:
            caller_factory_id = factory_id
        if not caller_factory_id:
            # Try X-Internal-Secret (Java internal)
            internal_secret = request.headers.get("x-internal-secret", "")
            expected = os.environ.get("INTERNAL_API_SECRET", "")
            if expected and internal_secret == expected:
                caller_factory_id = request.headers.get("x-factory-id") or "INTERNAL"
            else:
                # Try Bearer token (frontend) — must use pyjwt + match
                # auth_middleware's secret padding (UTF-8, pad to 32 bytes)
                # to align with Java JwtUtil HS256 key derivation.
                auth_header = request.headers.get("authorization", "")
                if auth_header.startswith("Bearer "):
                    token = auth_header[7:]
                    try:
                        import jwt as pyjwt
                        raw_secret = os.environ.get("JWT_SECRET", "default-secret")
                        key_bytes = raw_secret.encode("utf-8")
                        if len(key_bytes) < 32:
                            key_bytes = key_bytes + b"\x00" * (32 - len(key_bytes))
                        claims = pyjwt.decode(
                            token, key_bytes,
                            algorithms=["HS256"],
                            options={"verify_exp": True},
                        )
                        caller_factory_id = claims.get("factoryId")
                    except Exception as _e:
                        logger.warning(f"[auto-parse-status] JWT verify failed: {_e}")
                        caller_factory_id = None
        if not caller_factory_id:
            raise HTTPException(
                status_code=401,
                detail="Authentication required (Bearer token or X-Internal-Secret).",
            )
        # Internal callers can read any factory; JWT users restricted to own.
        if caller_factory_id != "INTERNAL" and caller_factory_id != upload.factory_id:
            raise HTTPException(
                status_code=403,
                detail=(
                    f"Access denied: you belong to {caller_factory_id}, "
                    f"upload {upload_id} belongs to {upload.factory_id}"
                ),
            )

        result = {
            "success": True,
            "uploadId": upload.id,
            "status": upload.upload_status,
            "fileName": upload.file_name,
            "factoryId": upload.factory_id,
            "rowCount": upload.row_count,
            "columnCount": upload.column_count,
            "createdAt": upload.created_at.isoformat() if upload.created_at else None,
            "updatedAt": upload.updated_at.isoformat() if upload.updated_at else None,
        }
        if upload.upload_status == "COMPLETED":
            result["detectedTableType"] = upload.detected_table_type
            result["fieldMappings"] = upload.field_mappings or []
            result["contextInfo"] = upload.context_info or {}
            # FE-compatibility: expose headers + first 20 rows so the
            # existing ExcelUpload.vue shape (parseResult.preview_data)
            # can be filled without a second round-trip. headers come
            # from context_info.headers (stored by stream-worker);
            # preview is a cheap LIMIT-20 query against dynamic_data.
            ctx = upload.context_info or {}
            result["headers"] = ctx.get("headers") or []
            try:
                from smartbi.database.models import SmartBiDynamicData
                preview_rows = (
                    db.query(SmartBiDynamicData.row_data)
                    .filter_by(upload_id=upload_id)
                    .order_by(SmartBiDynamicData.row_index.asc())
                    .limit(20)
                    .all()
                )
                result["previewData"] = [r[0] for r in preview_rows]
            except Exception as _pe:
                logger.warning(f"[status] preview fetch failed upload_id={upload_id}: {_pe}")
                result["previewData"] = []
        elif upload.upload_status == "FAILED":
            result["error"] = upload.error_message
        return result
    finally:
        db.close()


def _async_worker(
    upload_id: int,
    tmp_path: str,
    factory_id: str,
    sheet_index: Optional[int],
    max_rows: int,
    selected_region_start: Optional[int],
    selected_region_end: Optional[int],
):
    """
    BG task: calls the sync /auto-parse endpoint on localhost and mirrors
    the result back into the placeholder upload row. Designed to survive
    long-running parses (up to 900s total timeout).
    """
    import asyncio

    # FastAPI BackgroundTasks runs sync callables in thread pool. To make
    # httpx async calls we need our own event loop here.
    asyncio.run(_async_worker_impl(
        upload_id=upload_id,
        tmp_path=tmp_path,
        factory_id=factory_id,
        sheet_index=sheet_index,
        max_rows=max_rows,
        selected_region_start=selected_region_start,
        selected_region_end=selected_region_end,
    ))


async def _async_worker_impl(
    upload_id: int,
    tmp_path: str,
    factory_id: str,
    sheet_index: Optional[int],
    max_rows: int,
    selected_region_start: Optional[int],
    selected_region_end: Optional[int],
):
    """
    Step 2D (Apr 20 2026): streaming persist — bypasses Java entirely.

    Pipeline:
      1. Probe nrows=100 → detect title-row skip + real cols
      2. Run semantic_mapper on headers, persist field_definitions
      3. Stream pd.read_csv(chunksize=5000) FROM DISK (not bytes!)
      4. Each chunk: execute_values bulk insert into smart_bi_dynamic_data
      5. Update upload row status=COMPLETED

    Memory stays ~500MB regardless of file size because only one chunk lives
    in memory at a time and psycopg2 execute_values streams to DB.
    """
    start = time.time()
    db = SessionLocal()
    upload = None
    total_rows = 0
    try:
        upload = db.query(SmartBiPgExcelUpload).filter_by(id=upload_id).first()
        if not upload:
            logger.error(f"[stream-worker] upload_id={upload_id} not found, bailing")
            return
        upload.upload_status = "PROCESSING"
        db.commit()
        logger.info(f"[stream-worker] upload_id={upload_id} PROCESSING started ({tmp_path})")

        import pandas as pd
        import numpy as np
        import re as _re
        from psycopg2.extras import execute_values, Json

        unnamed_pat = _re.compile(r'^Unnamed:\s*\d+$')

        # Apr 26 2026 (S1 audit Bug A): detect file ext to branch CSV vs XLSX/XLS readers.
        # Pre-fix: _probe() blindly called pd.read_csv() on all files → xlsx binary
        # raises UnicodeDecodeError → outer except @ line 511 marks FAILED ("crashed
        # after 0 rows"). 8/8 xlsx in S1 test all hit this path. Sync /auto-parse
        # already branches by ext (excel.py:380-401, 1136-1141); we mirror that here.
        _filename_lower = (tmp_path or '').lower()
        if _filename_lower.endswith('.xlsx') or _filename_lower.endswith('.xls'):
            _file_kind = 'excel'
        else:
            _file_kind = 'csv'

        def _probe(skip):
            if _file_kind == 'excel':
                # pd.read_excel auto-detects xlsx vs xls via openpyxl/xlrd; no encoding arg.
                # sheet_name=0 when sheet_index is None preserves existing behaviour
                # (reads first sheet); non-None sheet_index reads the requested sheet.
                return pd.read_excel(
                    tmp_path, nrows=100, skiprows=skip,
                    sheet_name=(sheet_index if sheet_index is not None else 0),
                ), None
            # First try utf-8-sig + engine=python — handles 二维火 POS exports
            # (UTF-8 BOM + \r-only line endings); see spec §5 / Task B3 +
            # test_revenue_report_csv_encoding.py
            try:
                return pd.read_csv(
                    tmp_path, nrows=100, skiprows=skip,
                    encoding='utf-8-sig', engine='python',
                ), 'utf-8-sig'
            except UnicodeDecodeError:
                return pd.read_csv(
                    tmp_path, nrows=100, skiprows=skip,
                    encoding='gbk', engine='python',
                ), 'gbk'

        # --- Step 1: probe + title-row skip ---
        csv_skiprows = 0
        df_probe, encoding = _probe(csv_skiprows)

        def _looks_title(hdrs):
            hs = [str(h) for h in hdrs]
            if not hs:
                return False
            unnamed = sum(1 for h in hs if unnamed_pat.match(h))
            if unnamed >= 0.8 * len(hs):
                return True
            data_like = sum(1 for h in hs if _re.match(r'^[\d\s\-./年月日:]+$', h))
            return data_like >= 0.8 * len(hs)

        while _looks_title(df_probe.columns) and csv_skiprows < 5:
            csv_skiprows += 1
            df_probe, encoding = _probe(csv_skiprows)

        # Real cols: drop Unnamed: N cols that are all-NaN in probe
        real_cols_idx = [
            i for i, c in enumerate(df_probe.columns)
            if not (unnamed_pat.match(str(c)) and df_probe[c].isna().all())
        ]
        real_headers = [str(df_probe.columns[i]) for i in real_cols_idx]
        logger.info(
            f"[stream-worker] upload {upload_id}: probe skiprows={csv_skiprows}, "
            f"real_cols={len(real_headers)} headers={real_headers[:5]!r}"
        )

        # --- Step 2: semantic mapping + unified classifier write field_defs (γ-1b) ---
        from smartbi.services.semantic_mapper import SemanticMapper
        from smartbi.database.models import SmartBiPgFieldDefinition
        from smartbi.services.field_classifier import (
            classify_column, dedupe_column_names,
            find_time_column, find_category_column,
        )

        mapper = SemanticMapper()
        sample_slice = (
            df_probe.iloc[:3, real_cols_idx].where(df_probe.iloc[:3, real_cols_idx].notna(), None).values.tolist()
            if real_cols_idx else None
        )
        mapping_result = await mapper.map_fields(
            columns=real_headers, sample_data=sample_slice, factory_id=factory_id,
            table_context=(upload.file_name or ""),  # bug fix: was unset -> table_type always None
        )
        _template_key = SemanticMapper._compute_template_key(real_headers)

        # Wipe any stale field_defs for this upload, then bulk-insert new ones.
        db.query(SmartBiPgFieldDefinition).filter_by(upload_id=upload_id).delete()
        db.commit()

        # Dedup column names (Java parity; prevents UNIQUE (upload_id, original_name) violations
        # on sheets with repeated headers like "金额"×3)
        original_names = [m.original for m in mapping_result.field_mappings]
        deduped_names = dedupe_column_names(original_names)

        # Run unified classifier (single source of truth post-γ)
        classifications = []
        for m, deduped_name in zip(mapping_result.field_mappings, deduped_names):
            cls = classify_column(
                original_name=deduped_name,
                inferred_dtype=(m.data_type or "").upper() or None,
                category_hint=m.category,
            )
            classifications.append({**cls, "standard_name": m.standard or deduped_name,
                                    "field_type": m.data_type})

        field_def_rows = []
        for i, c in enumerate(classifications):
            field_def_rows.append(SmartBiPgFieldDefinition(
                upload_id=upload_id,
                original_name=c["original_name"],
                standard_name=c["standard_name"],
                field_type=c["field_type"],
                semantic_type=c["semantic_type"],
                is_measure=c["is_measure"],
                is_dimension=c["is_dimension"],
                is_time=c["is_time"],
                display_order=i,
            ))
        db.bulk_save_objects(field_def_rows)
        db.commit()

        # Phase 0: route columns the controlled-vocab mapper could not map
        # (method='review_needed') to the human review queue. Best-effort; never
        # blocks the upload. Table is FORCE RLS -> set app.factory_id in-txn.
        _review_rows = [
            (upload_id, factory_id, _template_key, m.original.strip().lower(),
             m.standard, float(m.confidence), m.method)
            for m in mapping_result.field_mappings
            if m.method == "review_needed"
        ]
        if _review_rows:
            try:
                from smartbi.config import get_pg_pool
                _rq_pool = await get_pg_pool()
                if _rq_pool is not None:
                    async with _rq_pool.acquire() as conn:
                        async with conn.transaction():
                            await conn.execute(
                                "SELECT set_config('app.factory_id', $1, true)", factory_id
                            )
                            await conn.executemany(
                                """INSERT INTO smart_bi_mapping_review_queue
                                     (upload_id, factory_id, template_key, column_name,
                                      detected_standard, detected_confidence, detected_method)
                                   VALUES ($1, $2, $3, $4, $5, $6, $7)
                                   ON CONFLICT (upload_id, column_name) DO NOTHING""",
                                _review_rows,
                            )
                    logger.info(
                        "[review-queue] queued %d columns for review (upload=%s)",
                        len(_review_rows), upload_id,
                    )
            except Exception as e:
                logger.warning("[review-queue] insert failed (ignored): %s", e)

        # Find the canonical time + category columns for denormalized row writes
        time_col = find_time_column(classifications)
        category_col = find_category_column(classifications)
        logger.info(
            f"[stream-worker] upload {upload_id}: wrote {len(field_def_rows)} field_defs "
            f"(time_col={time_col}, category_col={category_col})"
        )

        # --- Step 3: streaming chunks + bulk insert dynamic_data ---
        # Wipe any stale dynamic_data rows for this upload (idempotent retries).
        raw_conn = db.connection().connection
        with raw_conn.cursor() as cur:
            cur.execute("DELETE FROM smart_bi_dynamic_data WHERE upload_id = %s", (upload_id,))
        raw_conn.commit()

        CHUNK_SIZE = 5000
        # γ-1b: populate period/category denormalized columns (was NULL pre-γ,
        # causing WHERE period='...' queries to silently miss all async-path data).
        INSERT_SQL = (
            "INSERT INTO smart_bi_dynamic_data "
            "(factory_id, upload_id, sheet_name, row_index, row_data, period, category) "
            "VALUES %s"
        )
        row_index = 0

        def _fmt_period(v):
            """Truncate period values to fit VARCHAR(50); handle datetime/date."""
            if v is None:
                return None
            s = str(v)
            # Prefer ISO date (YYYY-MM-DD) if string is longer ISO timestamp
            if len(s) > 10 and s[4] == "-" and s[7] == "-":
                s = s[:10]
            return s[:50] if len(s) > 50 else s

        def _fmt_category(v):
            if v is None:
                return None
            s = str(v)
            return s[:100] if len(s) > 100 else s

        # Apr 26 2026 (S1 audit Bug A): xlsx/xls path. pd.read_excel does NOT
        # support chunksize, so we load full file once then yield in CHUNK_SIZE
        # slices. Async upload caps at 50MB (line 343 threshold) so peak RSS is
        # bounded — 9MB xlsx spike showed +961MB RSS, 50MB upper bound ~5GB
        # which still fits in 16GB host.
        if _file_kind == 'excel':
            full_df = pd.read_excel(
                tmp_path,
                skiprows=csv_skiprows,
                usecols=real_cols_idx,
                # Mirror _probe: same sheet_name so probe headers match full-read headers.
                # sheet_name=0 when sheet_index is None preserves existing behaviour.
                sheet_name=(sheet_index if sheet_index is not None else 0),
            )

            def _excel_chunks():
                for i in range(0, len(full_df), CHUNK_SIZE):
                    yield full_df.iloc[i:i + CHUNK_SIZE]

            chunks_iter = _excel_chunks()
        else:
            read_kwargs = dict(
                filepath_or_buffer=tmp_path,
                skiprows=csv_skiprows,
                usecols=real_cols_idx,
                chunksize=CHUNK_SIZE,
                # engine='python' to handle \r-only line endings (二维火); see Task B3.
                engine='python',
            )
            # Encoding picked by _probe() above: 'utf-8-sig' (二维火) or 'gbk' (legacy).
            # Default to utf-8-sig if probe gave nothing (handles BOM safely).
            read_kwargs["encoding"] = encoding or "utf-8-sig"

            try:
                chunks_iter = pd.read_csv(**read_kwargs)
            except UnicodeDecodeError:
                read_kwargs["encoding"] = "gbk"
                chunks_iter = pd.read_csv(**read_kwargs)

        for chunk_df in chunks_iter:
            # NaN → None so JSON serialization writes null not NaN
            chunk_df = chunk_df.replace({np.nan: None})

            # Build row records. Use itertuples(index=False) for speed —
            # 3-4× faster than iterrows on wide dfs.
            chunk_cols = list(chunk_df.columns)
            # Pre-compute col→index for period/category extraction (γ-1b)
            time_idx = chunk_cols.index(time_col) if time_col and time_col in chunk_cols else -1
            cat_idx = chunk_cols.index(category_col) if category_col and category_col in chunk_cols else -1
            records = []
            for tpl in chunk_df.itertuples(index=False, name=None):
                row_dict = {chunk_cols[j]: tpl[j] for j in range(len(chunk_cols))}
                period_val = _fmt_period(tpl[time_idx]) if time_idx >= 0 else None
                category_val = _fmt_category(tpl[cat_idx]) if cat_idx >= 0 else None
                records.append((
                    factory_id, upload_id, "Sheet1", row_index, Json(row_dict),
                    period_val, category_val,
                ))
                row_index += 1

            with raw_conn.cursor() as cur:
                execute_values(cur, INSERT_SQL, records, page_size=500)
            raw_conn.commit()
            total_rows += len(records)
            logger.info(f"[stream-worker] upload {upload_id}: {total_rows} rows persisted")

        # --- Step 4: finalize ---
        # Populate field_mappings JSONB (FE needs this shape for field review
        # step; mirrors AutoParseResponse.fieldMappings contract).
        upload.upload_status = "COMPLETED"
        upload.row_count = total_rows
        upload.column_count = len(real_headers)
        upload.field_mappings = {
            m.original: (m.standard or m.original)
            for m in mapping_result.field_mappings
        }
        # Phase 0: domain from mapped canonical fields (detected_table_type was
        # 94% 'general' in prod); fall back to the legacy classifier result.
        from smartbi.services.domain_inference import infer_domain
        _domain = infer_domain(mapping_result.field_mappings) or mapping_result.table_type
        upload.detected_table_type = _domain
        upload.context_info = {
            "streamPersist": True,
            "parsedInMs": int((time.time() - start) * 1000),
            "csvSkiprows": csv_skiprows,
            "encoding": encoding or "utf-8",
            "headers": real_headers,  # so status endpoint can return them
        }
        db.commit()
        try:
            from smartbi.database.models import SmartBiDynamicData
            from smartbi.services.timeseries_extractor import extract_timeseries
            from smartbi.services.timeseries_writer import write_timeseries

            parsed_rows = [
                row[0]
                for row in (
                    db.query(SmartBiDynamicData.row_data)
                    .filter_by(upload_id=upload_id)
                    .order_by(SmartBiDynamicData.row_index.asc())
                    .all()
                )
            ]
            field_mappings = {
                m.original: (m.standard or m.original)
                for m in mapping_result.field_mappings
            }
            period_mapping_col = next(
                (
                    m.original
                    for m in mapping_result.field_mappings
                    if m.standard == "period"
                ),
                None,
            )

            def _period_of(row):
                if time_col and time_col in row:
                    return _fmt_period(row.get(time_col))
                if period_mapping_col and period_mapping_col in row:
                    return _fmt_period(row.get(period_mapping_col))
                return None

            # Build field_kind from field_def_rows (is_measure/is_dimension/is_time flags).
            # Key: standard_name (the value side of field_mappings).
            # This correctly handles identity-mapped columns (standard_name == original_name,
            # e.g. "产出数量") that have no STANDARD_FIELDS entry but carry is_measure=True
            # in smart_bi_pg_field_definitions — previously they were silently dropped.
            _kind_map: dict = {}
            for fdr in field_def_rows:
                sname = fdr.standard_name
                if fdr.is_measure:
                    _kind_map[sname] = "measure"
                elif fdr.is_dimension:
                    _kind_map[sname] = "dimension"
                elif fdr.is_time:
                    _kind_map[sname] = "time"
                else:
                    _kind_map[sname] = "skip"

            def _field_kind(standard_name: str) -> str:
                return _kind_map.get(standard_name, "skip")

            ts_rows = extract_timeseries(
                parsed_rows,
                field_mappings,
                factory_id=factory_id,
                template_key=_template_key,
                domain=_domain,
                source_upload_id=upload_id,
                period_of=_period_of,
                field_kind=_field_kind,
            )
            written = await write_timeseries(
                factory_id,
                _template_key,
                ts_rows,
                upload_id,
            )
            logger.info(
                "[timeseries] upload=%d factory=%s extracted=%d written=%d",
                upload_id,
                factory_id,
                len(ts_rows),
                written,
            )
        except Exception:
            logger.warning(
                "[timeseries] extract/write failed for upload=%d factory=%s",
                upload_id,
                factory_id,
                exc_info=True,
            )
        # 数据织网 B Phase 3 — Sheet Merger.
        # Inference + merge decision must run AFTER field_definitions are
        # committed (priority 1 row_date_column needs the canonical 'date'
        # mapping) and BEFORE schedule_materialization (so capability /
        # materialized analytics see merge_status / period columns).
        # Failure swallowed — legacy upload status stays COMPLETED.
        if sheet_merger_enabled():
            try:
                await run_sheet_merge(factory_id=factory_id, upload_id=upload_id)
            except Exception:
                logger.exception(
                    f"[stream-worker] sheet_merge failed for upload={upload_id}; "
                    f"upload remains COMPLETED, period stays unset"
                )
        # 数据织网 B Phase 3 wire-in: route to B-stage Silver writers
        # (product_summary / review / finance / inventory). bill_flow shape
        # short-circuits to the legacy run_silver_dual_write path below to
        # avoid duplicate fact_pos_* writes. Failure swallowed — legacy
        # upload status stays COMPLETED.
        try:
            await run_b_writers(factory_id, upload_id)
        except Exception:
            logger.exception(
                "[stream-worker] run_b_writers failed for upload=%d", upload_id,
            )
        # Fire-and-forget: pre-warm materialized analytics cache.
        # field_defs are fully written above (db.commit() at line ~385) before
        # we reach this point, so the materializer will see all field metadata.
        # schedule_materialization() is a no-op if no event loop is running.
        # Pass factory_id explicitly: post V20260502 RLS sweep, the bg task's
        # asyncpg pool needs the GUC set or its first SELECT silently filters
        # to 0 rows under the __internal__ sentinel.
        schedule_materialization(upload_id, factory_id=factory_id)
        logger.info(
            f"[stream-worker] upload {upload_id} COMPLETED in "
            f"{int((time.time() - start) * 1000)}ms: {total_rows} rows × {len(real_headers)} cols"
        )
        # v1 Phase A Silver+Gold dual-write (behind SMARTBI_ENABLE_SILVER_DUAL_WRITE).
        # Fires AFTER legacy commit so upload=COMPLETED is already visible.
        # Failures are logged inside run_silver_dual_write and swallowed —
        # legacy status is never affected.
        if silver_dual_write_enabled():
            await run_silver_dual_write(
                factory_id=factory_id, upload_id=upload_id,
            )
        # Week 6: Invalidate Agent layer narrative cache so the next AI
        # insight query rebuilds from fresh data instead of serving
        # yesterday's answer (24h TTL would otherwise hide new uploads).
        # Fire-and-forget — failure must not affect upload status.
        try:
            from smartbi.agent.narrative_cache import NarrativeCacheService
            from smartbi.config import get_pg_pool
            pool = await get_pg_pool()
            deleted = await NarrativeCacheService(pool).invalidate_on_upload(factory_id)
            logger.info(
                f"[stream-worker] upload {upload_id}: invalidated {deleted} narrative_cache rows for factory={factory_id}"  # noqa: E501
            )
        except Exception:
            logger.exception(
                f"[stream-worker] narrative_cache invalidate failed for upload={upload_id}; "
                f"cache will naturally expire via TTL"
            )

        # 数据织网 A spec §3.2: invalidate capability cache so next /capability/{factory_id}
        # call sees newly-available canonical fields. Without this, customers wait up to
        # 5min (TTL) after upload to see new "解锁" cards. Fire-and-forget — failure
        # falls back to TTL expiry which is correct but slower UX.
        try:
            from smartbi.capability.api import _get_calculator
            calc = await _get_calculator()
            calc.invalidate(factory_id)
            logger.info(
                f"[stream-worker] upload {upload_id}: invalidated capability cache for factory={factory_id}"
            )
        except Exception:
            logger.exception(
                f"[stream-worker] capability invalidate failed for upload={upload_id}; "
                f"cache will naturally expire via 5min TTL"
            )
    except Exception as e:
        logger.exception(f"[stream-worker] upload_id={upload_id} crashed after {total_rows} rows")
        try:
            if upload is None:
                upload = db.query(SmartBiPgExcelUpload).filter_by(id=upload_id).first()
            if upload is not None:
                upload.upload_status = "FAILED"
                upload.error_message = f"{type(e).__name__}: {str(e)[:400]}"
                upload.row_count = total_rows  # record partial progress
                db.commit()
        except Exception:
            db.rollback()
    finally:
        db.close()
        try:
            os.remove(tmp_path)
        except OSError:
            pass
