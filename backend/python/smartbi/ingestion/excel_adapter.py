"""Excel/CSV adapter — streams a file into RawEvents.

Reuses the proven streaming-read pattern from
smartbi/api/excel_async.py (chunksize=5000, probe-for-header). The worker
there currently does parse + field-map + DB-write in one long function;
this adapter extracts only the parse step so it can be reused by API
adapters and unit-tested without a DB.

Supports .csv, .xlsx, .xls. Header detection is intentionally naive
(same as excel_async.py) — Bronze's job is "get rows out", not "decide
what they mean". The Silver normalizer handles semantic correctness.
"""
from __future__ import annotations

import asyncio
import logging
from pathlib import Path
from typing import Any, AsyncIterator, Dict, List, Optional

import pandas as pd

from smartbi.ingestion.base import BronzeAdapter, RawEvent, SourceMeta

logger = logging.getLogger(__name__)


_CSV_SUFFIXES = {".csv"}
_EXCEL_SUFFIXES = {".xlsx", ".xls"}


class ExcelAdapter(BronzeAdapter):
    """Adapter for file uploads (csv/xlsx/xls).

    Reads in chunks, yielding one RawEvent per row. Detects the header
    row by skipping up to 5 lines of 'title-like' content at the top
    (unnamed columns, mostly-null rows) — same heuristic as excel_async.py.
    """

    def __init__(
        self,
        file_path: str,
        factory_id: str,
        source_id: str,
        source_version: str = "excel_v1",
        chunk_size: int = 5000,
        max_rows: Optional[int] = None,
    ):
        self.file_path = file_path
        self.factory_id = factory_id
        self.source_meta = SourceMeta(
            source_type="excel",
            source_version=source_version,
            source_id=source_id,
        )
        self.chunk_size = chunk_size
        self.max_rows = max_rows
        # Populated during ingest for describe()
        self._detected_encoding: Optional[str] = None
        self._detected_skiprows: int = 0
        self._detected_columns: List[str] = []

    async def ingest(self) -> AsyncIterator[RawEvent]:
        path = Path(self.file_path)
        if not path.exists():
            raise FileNotFoundError(self.file_path)
        suffix = path.suffix.lower()
        if suffix in _CSV_SUFFIXES:
            async for evt in self._ingest_csv():
                yield evt
        elif suffix in _EXCEL_SUFFIXES:
            async for evt in self._ingest_excel():
                yield evt
        else:
            raise ValueError(f"Unsupported file type: {suffix}")

    def describe(self) -> Dict[str, Any]:
        return {
            "adapter": "ExcelAdapter",
            "file_path": self.file_path,
            "factory_id": self.factory_id,
            "source_meta": {
                "source_type": self.source_meta.source_type,
                "source_version": self.source_meta.source_version,
                "source_id": self.source_meta.source_id,
            },
            "chunk_size": self.chunk_size,
            "max_rows": self.max_rows,
            "detected_encoding": self._detected_encoding,
            "detected_skiprows": self._detected_skiprows,
            "column_count": len(self._detected_columns),
        }

    # ── CSV path ─────────────────────────────────────────────

    async def _ingest_csv(self) -> AsyncIterator[RawEvent]:
        # Probe encoding + header
        loop = asyncio.get_event_loop()
        probe_result = await loop.run_in_executor(None, self._probe_csv)
        self._detected_encoding = probe_result["encoding"]
        self._detected_skiprows = probe_result["skiprows"]
        self._detected_columns = probe_result["columns"]

        # Stream in chunks
        def reader_factory(): return pd.read_csv(  # noqa: E731
            self.file_path,
            encoding=self._detected_encoding,
            skiprows=self._detected_skiprows,
            chunksize=self.chunk_size,
            dtype=str,
            keep_default_na=False,
            na_values=[""],
        )
        row_idx = 0
        reader = await loop.run_in_executor(None, reader_factory)
        try:
            while True:
                chunk_df = await loop.run_in_executor(None, lambda: next(reader, None))
                if chunk_df is None:
                    break
                for _, row in chunk_df.iterrows():
                    if self.max_rows is not None and row_idx >= self.max_rows:
                        return
                    raw: Dict[str, Any] = {}
                    err: Optional[str] = None
                    for col, val in row.items():
                        try:
                            raw[str(col)] = None if pd.isna(val) else val
                        except Exception as e:
                            err = f"cell parse error col={col}: {e}"
                    yield RawEvent(
                        row_index=row_idx,
                        raw_data=raw,
                        source_meta=self.source_meta,
                        factory_id=self.factory_id,
                        parse_error=err,
                    )
                    row_idx += 1
        finally:
            # pandas chunk reader doesn't expose a close() explicitly;
            # iterator exhaustion frees the file handle.
            pass

    def _probe_csv(self) -> Dict[str, Any]:
        """Detect encoding + how many title rows to skip. Sync helper.

        Order:
        1. Try utf-8. If decode fails, fall back to gbk/gb18030.
        2. Read first 30 rows; skip leading rows where
             all cells are null OR >=80% of cells are 'Unnamed: N'.
        """
        encodings = ["utf-8", "utf-8-sig", "gb18030", "gbk"]
        last_err = None
        for enc in encodings:
            try:
                probe_df = pd.read_csv(
                    self.file_path,
                    encoding=enc,
                    nrows=30,
                    dtype=str,
                    keep_default_na=False,
                    na_values=[""],
                )
                break
            except UnicodeDecodeError as e:
                last_err = e
                continue
        else:
            raise RuntimeError(f"Cannot decode {self.file_path}: {last_err}")

        skiprows = 0
        for i in range(min(5, len(probe_df))):
            columns = [str(c) for c in probe_df.columns]
            unnamed_frac = sum(
                1 for c in columns if c.lower().startswith("unnamed")
            ) / max(len(columns), 1)
            if unnamed_frac >= 0.8:
                skiprows += 1
                # Re-read with one more skipped row
                probe_df = pd.read_csv(
                    self.file_path,
                    encoding=enc,
                    nrows=30,
                    skiprows=skiprows,
                    dtype=str,
                    keep_default_na=False,
                    na_values=[""],
                )
            else:
                break

        return {
            "encoding": enc,
            "skiprows": skiprows,
            "columns": [str(c) for c in probe_df.columns],
        }

    # ── XLSX/XLS path ────────────────────────────────────────

    async def _ingest_excel(self) -> AsyncIterator[RawEvent]:
        """Excel formats don't support chunked reads in pandas directly,
        but openpyxl's read_only mode streams rows without loading the
        full sheet. For v1 we keep it simple: load once, stream via
        iterrows(). Large .xlsx files (>100MB) should be CSV-exported
        upstream — acceptable constraint for now.
        """
        loop = asyncio.get_event_loop()
        df = await loop.run_in_executor(
            None,
            lambda: pd.read_excel(
                self.file_path,
                dtype=str,
                keep_default_na=False,
                na_values=[""],
            ),
        )
        self._detected_encoding = "utf-8"  # xlsx is always unicode
        self._detected_skiprows = 0
        self._detected_columns = [str(c) for c in df.columns]

        row_idx = 0
        for _, row in df.iterrows():
            if self.max_rows is not None and row_idx >= self.max_rows:
                return
            raw: Dict[str, Any] = {}
            err: Optional[str] = None
            for col, val in row.items():
                try:
                    raw[str(col)] = None if pd.isna(val) else val
                except Exception as e:
                    err = f"cell parse error col={col}: {e}"
            yield RawEvent(
                row_index=row_idx,
                raw_data=raw,
                source_meta=self.source_meta,
                factory_id=self.factory_id,
                parse_error=err,
            )
            row_idx += 1
