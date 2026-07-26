"""Build the Expo Updates v1 manifest dict from a bundle directory on disk.

Reads `metadata.json` (from `expo export`) + `expoConfig.json` (from `expo
config --json`) and produces the manifest dict that gets signed and emitted as
the first part of the multipart/mixed response.

Spec §3.1/§3.2. Field derivation mirrors the official TypeScript reference
implementation byte-for-byte; key ordering is locked to:

    id, createdAt, runtimeVersion, assets, launchAsset, metadata, extra
"""
from __future__ import annotations

import base64
import hashlib
import json
import mimetypes
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
from urllib.parse import quote


def _sha256_uuid(data: bytes) -> str:
    """sha256(data) hex digest sliced into UUID 8-4-4-4-12 shape."""
    h = hashlib.sha256(data).hexdigest()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


def _base64url_sha256(data: bytes) -> str:
    digest = hashlib.sha256(data).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def _md5_hex(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def _file_mtime_iso_z(path: Path) -> str:
    """Return ISO-8601 mtime with Z suffix (UTC), matching Jackson default."""
    ts = path.stat().st_mtime
    dt = datetime.fromtimestamp(ts, tz=timezone.utc)
    # Drop subseconds beyond ms to keep parity simple; clients tolerate either.
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def _asset_url(hostname: str, rel_path: Path, runtime_version: str, platform: str) -> str:
    """Build the asset-fetch URL. All query-string values are URL-encoded for
    defense in depth (the manifest endpoint already validates runtime_version
    and platform via storage._validate_path_component, but encoding here means
    a future caller that bypasses validation still produces a safe URL)."""
    encoded_asset = quote(rel_path.as_posix(), safe="")
    encoded_rv = quote(runtime_version, safe="")
    encoded_platform = quote(platform, safe="")
    return (
        f"{hostname}/api/ota/assets?asset={encoded_asset}"
        f"&runtimeVersion={encoded_rv}&platform={encoded_platform}"
    )


def _cdn_url(base_url: str, rel_path: Path) -> str:
    """Join a trusted CDN base URL with an encoded object key."""
    encoded_path = quote(rel_path.as_posix(), safe="/")
    return f"{base_url.rstrip('/')}/{encoded_path}"


def _build_asset_entry(
    bundle_dir: Path,
    ota_root: Path,
    asset_relative_path: str,
    ext: str,
    is_launch_asset: bool,
    runtime_version: str,
    platform: str,
    hostname: str,
    asset_base_url: Optional[str],
    asset_store_base_url: Optional[str],
) -> dict:
    """Construct one asset/launchAsset dict per Expo Updates v1 protocol."""
    abs_path = bundle_dir / asset_relative_path
    asset_bytes = abs_path.read_bytes()
    rel_to_root = abs_path.relative_to(ota_root)

    if is_launch_asset:
        file_extension = ".bundle"
        content_type = "application/javascript"
    else:
        file_extension = f".{ext}"
        # mimetypes returns None for unknown; the reference impl falls back to ext-derived.
        guessed, _ = mimetypes.guess_type(f"x.{ext}")
        content_type = guessed or "application/octet-stream"

    if not is_launch_asset and asset_store_base_url:
        url = _cdn_url(asset_store_base_url, Path(abs_path.name))
    elif asset_base_url:
        # OTA_ASSET_BASE_URL already points at app-updates/updates.
        url = _cdn_url(asset_base_url, rel_to_root.relative_to("updates"))
    else:
        url = _asset_url(hostname, rel_to_root, runtime_version, platform)

    return {
        "hash": _base64url_sha256(asset_bytes),
        "key": _md5_hex(asset_bytes),
        "fileExtension": file_extension,
        "contentType": content_type,
        "url": url,
    }


def build_manifest(
    *,
    bundle_dir: Path,
    ota_root: Path,
    runtime_version: str,
    platform: str,
    hostname: str,
    asset_base_url: Optional[str] = None,
    asset_store_base_url: Optional[str] = None,
) -> dict:
    """Construct the Expo Updates v1 manifest dict for the given bundle.

    Field order is preserved (Python 3.7+ dict insertion order). Caller is
    responsible for serializing via signing.canonicalize_for_signing for
    byte-shape parity.
    """
    metadata_path = bundle_dir / "metadata.json"
    expo_config_path = bundle_dir / "expoConfig.json"
    metadata_bytes = metadata_path.read_bytes()
    metadata = json.loads(metadata_bytes)
    expo_config = json.loads(expo_config_path.read_bytes())

    platform_meta = metadata["fileMetadata"][platform]

    assets = [
        _build_asset_entry(
            bundle_dir=bundle_dir,
            ota_root=ota_root,
            asset_relative_path=a["path"],
            ext=a["ext"],
            is_launch_asset=False,
            runtime_version=runtime_version,
            platform=platform,
            hostname=hostname,
            asset_base_url=asset_base_url,
            asset_store_base_url=asset_store_base_url,
        )
        for a in platform_meta["assets"]
    ]
    launch_asset = _build_asset_entry(
        bundle_dir=bundle_dir,
        ota_root=ota_root,
        asset_relative_path=platform_meta["bundle"],
        ext="",  # ignored for launch asset
        is_launch_asset=True,
        runtime_version=runtime_version,
        platform=platform,
        hostname=hostname,
        asset_base_url=asset_base_url,
        asset_store_base_url=asset_store_base_url,
    )

    return {
        "id": _sha256_uuid(metadata_bytes),
        "createdAt": _file_mtime_iso_z(metadata_path),
        "runtimeVersion": runtime_version,
        "assets": assets,
        "launchAsset": launch_asset,
        "metadata": {},
        "extra": {"expoClient": expo_config},
    }
