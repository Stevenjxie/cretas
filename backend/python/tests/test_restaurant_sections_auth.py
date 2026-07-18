from __future__ import annotations

from pathlib import Path

import pytest
from fastapi import HTTPException
from starlette.requests import Request

from smartbi.api import restaurant_sections
from smartbi.api.restaurant_sections import SectionRequestBody
from smartbi.services.restaurant.sections.base import SectionResponse, SectionStatus


def _request(factory_id: str | None, method: str = "POST") -> Request:
    state = {} if factory_id is None else {"factory_id": factory_id}
    return Request({
        "type": "http",
        "method": method,
        "path": "/api/smartbi/restaurant/sections/test_section",
        "headers": [],
        "state": state,
    })


def _body(factory_id: str = "REST-1") -> SectionRequestBody:
    return SectionRequestBody(factory_id=factory_id, params={"pos_df": [1]})


class _BombHandlers(dict):
    def get(self, *_args, **_kwargs):
        raise AssertionError("handler registry must not be read before tenant authentication")


class _FakeHandler:
    def cache_key(self, request):
        return f"test:{request.factory_id}"

    def compute(self, request, context):
        return SectionResponse(
            section_name="test_section",
            status=SectionStatus.OK,
            data={"factory": request.factory_id},
            cache_key=f"test:{request.factory_id}",
        )


class _FakeCache:
    def get(self, _key):
        return None

    def set(self, _key, _value):
        return None


@pytest.mark.parametrize(
    ("request_factory", "body_factory", "status"),
    [(None, "REST-1", 401), ("REST-1", "REST-2", 403)],
)
def test_compute_rejects_missing_or_cross_tenant_before_handler_lookup(
    monkeypatch, request_factory, body_factory, status,
):
    monkeypatch.setattr(restaurant_sections, "HANDLERS", _BombHandlers())

    with pytest.raises(HTTPException) as exc:
        restaurant_sections.compute_section(
            request=_request(request_factory),
            section_name="test_section",
            body=_body(body_factory),
        )

    assert exc.value.status_code == status


def test_compute_accepts_matching_authenticated_tenant(monkeypatch):
    monkeypatch.setattr(restaurant_sections, "HANDLERS", {"test_section": _FakeHandler()})
    monkeypatch.setattr(restaurant_sections, "_cache", _FakeCache())

    result = restaurant_sections.compute_section(
        request=_request("REST-1"),
        section_name="test_section",
        body=_body("REST-1"),
    )

    assert result["success"] is True
    assert result["data"] == {"factory": "REST-1"}


def test_ppt_download_rejects_cross_tenant_before_path_or_exists(monkeypatch):
    def bomb_path(*_args, **_kwargs):
        raise AssertionError("filesystem path must not be built before tenant authentication")

    monkeypatch.setattr(restaurant_sections, "FsPath", bomb_path)

    with pytest.raises(HTTPException) as exc:
        restaurant_sections.download_monthly_ppt(
            factory_id="REST-2",
            period="2026-07",
            request=_request("REST-1", method="GET"),
        )

    assert exc.value.status_code == 403


def test_ppt_download_accepts_matching_authenticated_tenant(monkeypatch, tmp_path):
    output_dir = tmp_path / "smartbi_ppt"
    output_dir.mkdir()
    output_file = output_dir / "monthly_REST-1_2026-07.pptx"
    output_file.write_bytes(b"pptx")
    monkeypatch.setattr(restaurant_sections.tempfile, "gettempdir", lambda: str(tmp_path))

    response = restaurant_sections.download_monthly_ppt(
        factory_id="REST-1",
        period="2026-07",
        request=_request("REST-1", method="GET"),
    )

    assert Path(response.path) == output_file
