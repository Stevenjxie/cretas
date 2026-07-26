"""Manifest builder: turns a bundle dir on disk into the Expo Updates v1 manifest dict.

Per spec §3.1/§3.2 + §6.2. Eight tests covering each manifest field's derivation:
- id = sha256_hex(metadata.json bytes), sliced into UUID shape
- createdAt = bundle file mtime ISO 8601 with Z suffix
- launchAsset.contentType = "application/javascript"
- launchAsset.fileExtension = ".bundle"
- asset.hash = base64url(sha256(bytes))
- asset.key = md5 hex
- asset.url format = {hostname}/api/ota/assets?asset=<rel>&runtimeVersion=&platform=
- extra.expoClient = parsed expoConfig.json
"""
from __future__ import annotations

import base64
import hashlib
import re
from pathlib import Path

from ota.services import manifest_builder


def test_id_is_sha256_hex_sliced_to_uuid_shape(populated_bundle_dir: Path, ota_root: Path):
    metadata_bytes = (populated_bundle_dir / "metadata.json").read_bytes()
    expected_sha = hashlib.sha256(metadata_bytes).hexdigest()
    expected_id = (
        f"{expected_sha[0:8]}-{expected_sha[8:12]}-{expected_sha[12:16]}-"
        f"{expected_sha[16:20]}-{expected_sha[20:32]}"
    )

    manifest = manifest_builder.build_manifest(
        bundle_dir=populated_bundle_dir,
        ota_root=ota_root,
        runtime_version="1.0.0",
        platform="android",
        hostname="http://test.local",
    )

    assert manifest["id"] == expected_id
    # And it's UUID-shaped (8-4-4-4-12 hex chars).
    assert re.fullmatch(
        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        manifest["id"],
    )


def test_created_at_is_iso8601_z_suffix(populated_bundle_dir: Path, ota_root: Path):
    manifest = manifest_builder.build_manifest(
        bundle_dir=populated_bundle_dir,
        ota_root=ota_root,
        runtime_version="1.0.0",
        platform="android",
        hostname="http://test.local",
    )

    assert manifest["createdAt"].endswith("Z")
    assert re.match(
        r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$", manifest["createdAt"]
    )


def test_launch_asset_has_application_javascript_content_type(
    populated_bundle_dir: Path, ota_root: Path
):
    manifest = manifest_builder.build_manifest(
        bundle_dir=populated_bundle_dir,
        ota_root=ota_root,
        runtime_version="1.0.0",
        platform="android",
        hostname="http://test.local",
    )

    assert manifest["launchAsset"]["contentType"] == "application/javascript"


def test_launch_asset_file_extension_is_dot_bundle(
    populated_bundle_dir: Path, ota_root: Path
):
    manifest = manifest_builder.build_manifest(
        bundle_dir=populated_bundle_dir,
        ota_root=ota_root,
        runtime_version="1.0.0",
        platform="android",
        hostname="http://test.local",
    )

    assert manifest["launchAsset"]["fileExtension"] == ".bundle"


def test_regular_asset_hash_is_base64url_sha256(
    populated_bundle_dir: Path, ota_root: Path
):
    """The .png asset's hash field must equal base64url(sha256(bytes))."""
    png_bytes = (populated_bundle_dir / "assets" / "img-1.png").read_bytes()
    expected_sha = hashlib.sha256(png_bytes).digest()
    expected_hash = (
        base64.urlsafe_b64encode(expected_sha).decode("ascii").rstrip("=")
    )

    manifest = manifest_builder.build_manifest(
        bundle_dir=populated_bundle_dir,
        ota_root=ota_root,
        runtime_version="1.0.0",
        platform="android",
        hostname="http://test.local",
    )

    png_asset = next(
        a for a in manifest["assets"] if a["fileExtension"] == ".png"
    )
    assert png_asset["hash"] == expected_hash
    # base64url: no +, no /, no = padding
    assert "+" not in png_asset["hash"]
    assert "/" not in png_asset["hash"]
    assert "=" not in png_asset["hash"]


def test_regular_asset_key_is_md5_hex(populated_bundle_dir: Path, ota_root: Path):
    """asset.key must equal md5_hex(bytes), per reference impl."""
    png_bytes = (populated_bundle_dir / "assets" / "img-1.png").read_bytes()
    expected_md5 = hashlib.md5(png_bytes).hexdigest()

    manifest = manifest_builder.build_manifest(
        bundle_dir=populated_bundle_dir,
        ota_root=ota_root,
        runtime_version="1.0.0",
        platform="android",
        hostname="http://test.local",
    )

    png_asset = next(
        a for a in manifest["assets"] if a["fileExtension"] == ".png"
    )
    assert png_asset["key"] == expected_md5
    # md5 hex is exactly 32 lowercase hex chars
    assert re.fullmatch(r"[0-9a-f]{32}", png_asset["key"])


def test_asset_url_format(populated_bundle_dir: Path, ota_root: Path):
    """URL = {hostname}/api/ota/assets?asset=<rel_to_ota_root>&runtimeVersion=&platform="""
    manifest = manifest_builder.build_manifest(
        bundle_dir=populated_bundle_dir,
        ota_root=ota_root,
        runtime_version="1.0.0",
        platform="android",
        hostname="http://test.local",
    )

    png_asset = next(
        a for a in manifest["assets"] if a["fileExtension"] == ".png"
    )
    url = png_asset["url"]

    assert url.startswith("http://test.local/api/ota/assets?")
    assert "asset=" in url
    assert "runtimeVersion=1.0.0" in url
    assert "platform=android" in url
    # The asset= value should be the path relative to ota_root, URL-encoded
    # (forward slashes can be encoded as %2F or left as / per RFC; either OK).
    assert "updates" in url and "production" in url and "img-1.png" in url


def test_extra_expoClient_is_parsed_expoConfig_json(
    populated_bundle_dir: Path, ota_root: Path
):
    manifest = manifest_builder.build_manifest(
        bundle_dir=populated_bundle_dir,
        ota_root=ota_root,
        runtime_version="1.0.0",
        platform="android",
        hostname="http://test.local",
    )

    # The expoClient field carries the snapshot of app.json the client should see.
    assert manifest["extra"]["expoClient"]["slug"] == "CretasFoodTrace"
    assert manifest["extra"]["expoClient"]["version"] == "1.0.0"


# --- chat2 audit Important D: defense-in-depth URL encoding ---


def test_asset_url_encodes_query_string_values_defense_in_depth():
    """_asset_url must encode runtime_version + platform even though the manifest
    endpoint already validates them via storage._validate_path_component. A
    future caller that bypasses validation should still produce a safe URL."""
    url = manifest_builder._asset_url(
        "http://x", Path("a/b.png"), "1+0+0", "an droid"
    )
    assert "runtimeVersion=1%2B0%2B0" in url
    assert "platform=an%20droid" in url


def test_asset_url_safe_values_remain_readable():
    """Unreserved characters (alphanumerics, dots, hyphens) stay un-encoded
    so generated URLs are still grep-friendly for ops."""
    url = manifest_builder._asset_url(
        "http://x", Path("a/b.png"), "1.0.0", "android"
    )
    assert "runtimeVersion=1.0.0" in url
    assert "platform=android" in url


def test_cdn_launch_asset_url_uses_per_update_prefix(
    populated_bundle_dir: Path, ota_root: Path
):
    manifest = manifest_builder.build_manifest(
        bundle_dir=populated_bundle_dir,
        ota_root=ota_root,
        runtime_version="1.0.0",
        platform="android",
        hostname="http://test.local",
        asset_base_url="https://dl.example.com/app-updates/updates",
        asset_store_base_url="https://dl.example.com/app-updates/assets-store",
    )

    assert manifest["launchAsset"]["url"] == (
        "https://dl.example.com/app-updates/updates/"
        "1.0.0/production/200/bundles/index-abc123.hbc"
    )


def test_cdn_regular_assets_use_shared_content_addressed_store(
    populated_bundle_dir: Path, ota_root: Path
):
    manifest = manifest_builder.build_manifest(
        bundle_dir=populated_bundle_dir,
        ota_root=ota_root,
        runtime_version="1.0.0",
        platform="android",
        hostname="http://test.local",
        asset_base_url="https://dl.example.com/app-updates/updates/",
        asset_store_base_url="https://dl.example.com/app-updates/assets-store/",
    )

    urls = {asset["url"] for asset in manifest["assets"]}
    assert urls == {
        "https://dl.example.com/app-updates/assets-store/img-1.png",
        "https://dl.example.com/app-updates/assets-store/font-roboto.ttf",
    }


def test_manifest_field_order_matches_reference(
    populated_bundle_dir: Path, ota_root: Path
):
    """Spec §4.2: key order must be id, createdAt, runtimeVersion, assets,
    launchAsset, metadata, extra. Tested via signing.canonicalize_for_signing
    so we know the byte-shape lines up with the Expo TypeScript reference.
    """
    from ota.services import signing

    manifest = manifest_builder.build_manifest(
        bundle_dir=populated_bundle_dir,
        ota_root=ota_root,
        runtime_version="1.0.0",
        platform="android",
        hostname="http://test.local",
    )
    out = signing.canonicalize_for_signing(manifest)

    assert out.index('"id"') < out.index('"createdAt"')
    assert out.index('"createdAt"') < out.index('"runtimeVersion"')
    assert out.index('"runtimeVersion"') < out.index('"assets"')
    assert out.index('"assets"') < out.index('"launchAsset"')
    assert out.index('"launchAsset"') < out.index('"metadata"')
    assert out.index('"metadata"') < out.index('"extra"')
