"""FastAPI router for /api/ota/*.

Endpoints (per spec §2):
- GET  /health
- GET  /manifest   — Expo Updates v1 manifest endpoint (header-driven, multipart out)
- GET  /assets     — binary asset fetch (path-traversal hardened)
- POST /admin/register — validate a bundle dir, returns 200 on success
- POST /admin/rollback — touch a `rollback` marker in a bundle dir
- GET  /admin/list      — list bundle timestamps for (runtimeVersion, channel)

Admin endpoints require Bearer-token auth via `_require_admin` dependency.
"""
from __future__ import annotations

import datetime
import hmac
import json
import logging
import mimetypes
import os
from typing import Optional

from fastapi import APIRouter, Depends, Header, HTTPException, Query
from fastapi.responses import JSONResponse, Response

from ota.config import OTASettings, get_settings
from ota.models import BundleRef
from ota.services import directives, manifest_builder, multipart, signing, storage

_logger = logging.getLogger(__name__)

router = APIRouter()


def _bad_request(msg: str) -> JSONResponse:
    return JSONResponse(status_code=400, content={"error": msg})


def _file_mtime_iso_z(path) -> str:
    """ISO-8601 mtime with millisecond precision and Z suffix."""
    ts = path.stat().st_mtime
    dt = datetime.datetime.fromtimestamp(ts, tz=datetime.timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


@router.get("/health")
def health(settings: OTASettings = Depends(get_settings)):
    """Liveness + readiness probe. Per chat2 audit Important F (formerly
    unnamed slot): the basePath filesystem string was previously echoed back
    to anonymous clients, which leaks server topology. Now only boolean
    health bits are exposed; ops can introspect basePath via admin/list or
    server-side logs.
    """
    has_key = (
        settings.private_key_path is not None
        and settings.private_key_path.is_file()
    )
    writable = (
        settings.base_path.exists() and os.access(settings.base_path, os.W_OK)
    )
    return {
        "status": "ok",
        "privateKeyLoaded": has_key,
        "writable": writable,
    }


@router.get("/manifest")
def manifest(
    expo_protocol_version: Optional[str] = Header(default=None),
    expo_platform: Optional[str] = Header(default=None),
    expo_runtime_version: Optional[str] = Header(default=None),
    expo_channel_name: Optional[str] = Header(default=None),
    expo_current_update_id: Optional[str] = Header(default=None),
    expo_embedded_update_id: Optional[str] = Header(default=None),
    expo_expect_signature: Optional[str] = Header(default=None),
    settings: OTASettings = Depends(get_settings),
):
    if expo_protocol_version != "1":
        return _bad_request("Unsupported protocol-version, expected 1")
    if expo_platform not in ("ios", "android"):
        return _bad_request("Unsupported platform, expected ios or android")
    if not expo_runtime_version:
        return _bad_request("No runtime-version provided")

    channel = expo_channel_name or settings.default_channel
    try:
        bundle_dir = storage.find_latest_bundle(
            settings.base_path, expo_runtime_version, channel
        )
    except storage.UnsafePathError:
        # Don't echo the malicious value back — generic message only (chat2 audit C1).
        return _bad_request("Invalid runtime-version or channel header")
    except storage.BundleNotFoundError as e:
        return JSONResponse(status_code=404, content={"error": str(e)})

    common_headers = {
        "expo-protocol-version": "1",
        "expo-sfv-version": "0",
        "cache-control": "private, max-age=0",
    }

    def _maybe_sign(content_str: str) -> Optional[str]:
        if not expo_expect_signature:
            return None
        # Per chat2 audit Important E (formerly unnamed slot): when the client
        # explicitly requests a signature via expo-expect-signature but the
        # server has no private key configured, we must fail loud (500) — not
        # silently return None, which would cause confusing client-side signature
        # verification failures on the device.
        if (
            settings.private_key_path is None
            or not settings.private_key_path.is_file()
        ):
            _logger.error(
                "OTA signature requested but private_key_path is missing/unreadable: %s",
                settings.private_key_path,
            )
            raise HTTPException(
                status_code=500,
                detail="Private key not loaded but signature requested",
            )
        # Per chat2 audit Important B: load_private_key_cached avoids re-parsing
        # the PEM on every request.
        private_key = signing.load_private_key_cached(str(settings.private_key_path))
        sig_b64 = signing.sign_with_loaded_key(content_str.encode("utf-8"), private_key)
        return signing.build_signature_header(sig_b64)

    if storage.is_rollback(bundle_dir):
        if not expo_embedded_update_id:
            return _bad_request("Rollback requires expo-embedded-update-id header")
        if expo_current_update_id == expo_embedded_update_id:
            directive = directives.no_update_available()
            d_str = signing.canonicalize_for_signing(directive)
            body, ct = multipart.build_directive_response(
                d_str, signature_header=_maybe_sign(d_str)
            )
            return Response(content=body, media_type=ct, headers=common_headers)
        commit_time = _file_mtime_iso_z(bundle_dir / "rollback")
        directive = directives.rollback_to_embedded(commit_time)
        d_str = signing.canonicalize_for_signing(directive)
        body, ct = multipart.build_directive_response(
            d_str, signature_header=_maybe_sign(d_str)
        )
        return Response(content=body, media_type=ct, headers=common_headers)

    try:
        manifest_dict = manifest_builder.build_manifest(
            bundle_dir=bundle_dir,
            ota_root=settings.base_path,
            runtime_version=expo_runtime_version,
            platform=expo_platform,
            hostname=settings.hostname,
            asset_base_url=settings.asset_base_url,
            asset_store_base_url=settings.asset_store_base_url,
        )
    except (json.JSONDecodeError, KeyError, ValueError, FileNotFoundError, OSError):
        # Per chat2 audit Important C: a corrupt / missing metadata.json /
        # expoConfig.json would bubble a stack trace; serve a generic 500 and
        # log details server-side only (filesystem paths NOT echoed to client).
        _logger.exception("OTA bundle metadata corrupted at %s", bundle_dir)
        return JSONResponse(
            status_code=500, content={"error": "Bundle metadata corrupted on server"}
        )

    if expo_current_update_id == manifest_dict["id"]:
        directive = directives.no_update_available()
        d_str = signing.canonicalize_for_signing(directive)
        body, ct = multipart.build_directive_response(
            d_str, signature_header=_maybe_sign(d_str)
        )
        return Response(content=body, media_type=ct, headers=common_headers)

    manifest_str = signing.canonicalize_for_signing(manifest_dict)
    extensions_str = signing.canonicalize_for_signing({"assetRequestHeaders": {}})
    body, ct = multipart.build_normal_update_response(
        manifest_json=manifest_str,
        extensions_json=extensions_str,
        manifest_signature_header=_maybe_sign(manifest_str),
    )
    return Response(content=body, media_type=ct, headers=common_headers)


@router.get("/assets")
def assets(
    asset: Optional[str] = Query(default=None),
    runtimeVersion: Optional[str] = Query(default=None),
    platform: Optional[str] = Query(default=None),
    settings: OTASettings = Depends(get_settings),
):
    if not asset:
        return _bad_request("No asset name provided")
    if platform not in ("ios", "android"):
        return _bad_request("Unsupported platform, expected ios or android")
    if not runtimeVersion:
        return _bad_request("No runtimeVersion provided")
    try:
        resolved = storage.resolve_asset_path(settings.base_path, asset)
    except storage.UnsafePathError:
        return _bad_request("Asset path is outside the OTA root")
    # Defensive: client-supplied asset paths must live under updates/ —
    # rejects ../keys/, ../logs/, etc. even though commonpath stayed inside root.
    updates_root = (settings.base_path / "updates").resolve()
    try:
        resolved.resolve().relative_to(updates_root)
    except ValueError:
        return _bad_request("Asset path must live under updates/")
    if not resolved.is_file():
        return JSONResponse(status_code=404, content={"error": "Asset not found"})

    is_launch = resolved.parent.name == "bundles"
    if is_launch:
        content_type = "application/javascript"
    else:
        guessed, _ = mimetypes.guess_type(resolved.name)
        content_type = guessed or "application/octet-stream"
    return Response(content=resolved.read_bytes(), media_type=content_type)


def _require_admin(
    authorization: Optional[str] = Header(default=None),
    settings: OTASettings = Depends(get_settings),
) -> None:
    if not settings.admin_token:
        raise HTTPException(status_code=500, detail="Admin token not configured")
    expected = f"Bearer {settings.admin_token}"
    # Per chat2 audit Important A: hmac.compare_digest is timing-safe; plain `!=`
    # is vulnerable to side-channel timing attacks against the admin token.
    if authorization is None or not hmac.compare_digest(authorization, expected):
        raise HTTPException(status_code=401, detail="Invalid admin token")


@router.post("/admin/register", dependencies=[Depends(_require_admin)])
def admin_register(ref: BundleRef, settings: OTASettings = Depends(get_settings)):
    bundle_dir = (
        settings.base_path
        / "updates"
        / ref.runtimeVersion
        / ref.channel
        / ref.timestamp
    )
    if not bundle_dir.is_dir():
        raise HTTPException(status_code=404, detail="Bundle directory not found")
    if not (bundle_dir / "metadata.json").is_file():
        raise HTTPException(status_code=400, detail="metadata.json missing")
    return {"status": "registered", "bundle": str(bundle_dir)}


@router.post("/admin/rollback", dependencies=[Depends(_require_admin)])
def admin_rollback(ref: BundleRef, settings: OTASettings = Depends(get_settings)):
    bundle_dir = (
        settings.base_path
        / "updates"
        / ref.runtimeVersion
        / ref.channel
        / ref.timestamp
    )
    if not bundle_dir.is_dir():
        raise HTTPException(status_code=404, detail="Bundle directory not found")
    (bundle_dir / "rollback").touch()
    return {"status": "rollback set", "bundle": str(bundle_dir)}


@router.get("/admin/list", dependencies=[Depends(_require_admin)])
def admin_list(
    runtimeVersion: str = Query(...),
    channel: str = Query(...),
    settings: OTASettings = Depends(get_settings),
):
    rv_channel = settings.base_path / "updates" / runtimeVersion / channel
    timestamps = storage.list_timestamps_descending(rv_channel)
    bundles = [
        {"timestamp": ts, "isRollback": storage.is_rollback(rv_channel / ts)}
        for ts in timestamps
    ]
    return {"bundles": bundles}
