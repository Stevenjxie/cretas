from __future__ import annotations

import asyncio
import json
from datetime import datetime
from io import BytesIO
from types import SimpleNamespace

import pytest
from fastapi import BackgroundTasks, HTTPException, UploadFile
from starlette.requests import Request

from smartbi.api import excel_async


def _request(factory_id: str | None, query: bytes = b"", headers=None) -> Request:
    state = {} if factory_id is None else {"factory_id": factory_id}
    return Request({
        "type": "http",
        "method": "POST",
        "path": "/api/smartbi/excel/auto-parse-async",
        "query_string": query,
        "headers": headers or [],
        "state": state,
    })


class _UploadDb:
    def __init__(self):
        self.added = None

    def add(self, value):
        self.added = value

    def commit(self):
        return None

    def refresh(self, value):
        value.id = 73

    def rollback(self):
        return None

    def close(self):
        return None


class _StatusQuery:
    def __init__(self, upload):
        self.upload = upload
        self.filters = None

    def filter_by(self, **filters):
        self.filters = filters
        return self

    def first(self):
        if self.filters == {"id": self.upload.id, "factory_id": self.upload.factory_id}:
            return self.upload
        return None


class _StatusDb:
    def __init__(self, upload):
        self.query_obj = _StatusQuery(upload)

    def query(self, _model):
        return self.query_obj

    def close(self):
        return None


@pytest.mark.parametrize(
    ("request_factory", "form_factory", "status"),
    [(None, "REST-1", 401), ("REST-1", "REST-2", 403)],
)
def test_async_upload_rejects_missing_or_cross_tenant_before_filesystem(
    monkeypatch, request_factory, form_factory, status,
):
    monkeypatch.setattr(
        excel_async.os,
        "makedirs",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("filesystem must not be touched before authentication")
        ),
    )

    with pytest.raises(HTTPException) as exc:
        asyncio.run(excel_async.auto_parse_async(
            background_tasks=BackgroundTasks(),
            request=_request(request_factory),
            file=UploadFile(filename="orders.xlsx", file=BytesIO(b"xlsx")),
            factory_id=form_factory,
            factoryId=None,
        ))

    assert exc.value.status_code == status


def test_async_upload_accepts_matching_state_and_persists_same_tenant(monkeypatch, tmp_path):
    db = _UploadDb()
    monkeypatch.setattr(excel_async, "SessionLocal", lambda: db)
    monkeypatch.setattr(excel_async, "ASYNC_TMP_DIR", str(tmp_path))

    response = asyncio.run(excel_async.auto_parse_async(
        background_tasks=BackgroundTasks(),
        request=_request("REST-1"),
        file=UploadFile(filename="orders.xlsx", file=BytesIO(b"xlsx")),
        factory_id="REST-1",
        factoryId=None,
        sheet_index=None,
        sheetIndex=None,
        max_rows=500000,
        selected_region_start=None,
        selected_region_end=None,
    ))

    assert response.status_code == 202
    assert json.loads(response.body)["uploadId"] == 73
    assert db.added.factory_id == "REST-1"


def test_status_query_or_headers_cannot_impersonate_tenant_before_db(monkeypatch):
    monkeypatch.setattr(
        excel_async,
        "SessionLocal",
        lambda: (_ for _ in ()).throw(
            AssertionError("DB must not be opened before authentication")
        ),
    )
    forged_headers = [
        (b"x-internal-secret", b"forged"),
        (b"x-factory-id", b"REST-1"),
    ]

    with pytest.raises(HTTPException) as exc:
        asyncio.run(excel_async.auto_parse_status(
            upload_id=91,
            request=_request(None, query=b"factory_id=REST-1", headers=forged_headers),
        ))

    assert exc.value.status_code == 401


def test_status_uses_exact_authenticated_tenant_in_db_filter(monkeypatch):
    upload = SimpleNamespace(
        id=91,
        factory_id="REST-1",
        upload_status="PENDING",
        file_name="orders.xlsx",
        row_count=None,
        column_count=None,
        created_at=datetime(2026, 7, 19, 1, 2, 3),
        updated_at=None,
    )
    db = _StatusDb(upload)
    monkeypatch.setattr(excel_async, "SessionLocal", lambda: db)

    result = asyncio.run(excel_async.auto_parse_status(
        upload_id=91,
        request=_request("REST-1"),
    ))

    assert result["success"] is True
    assert result["factoryId"] == "REST-1"
    assert db.query_obj.filters == {"id": 91, "factory_id": "REST-1"}


def test_status_cross_tenant_is_indistinguishable_from_missing(monkeypatch):
    upload = SimpleNamespace(id=91, factory_id="REST-1")
    db = _StatusDb(upload)
    monkeypatch.setattr(excel_async, "SessionLocal", lambda: db)

    with pytest.raises(HTTPException) as exc:
        asyncio.run(excel_async.auto_parse_status(
            upload_id=91,
            request=_request("REST-2"),
        ))

    assert exc.value.status_code == 404
    assert db.query_obj.filters == {"id": 91, "factory_id": "REST-2"}
