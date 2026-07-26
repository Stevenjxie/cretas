"""OTA server runtime configuration loaded from environment variables.

Per spec §5. All envs are optional; sensible defaults match server 47 layout.
In tests, override via FastAPI dependency injection (`app.dependency_overrides`).
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


@dataclass
class OTASettings:
    base_path: Path
    private_key_path: Optional[Path] = None
    admin_token: Optional[str] = None
    hostname: str = "http://47.100.235.168:8083"
    default_channel: str = "production"
    asset_base_url: Optional[str] = None
    asset_store_base_url: Optional[str] = None


def get_settings() -> OTASettings:
    """Load settings from env. Override in tests via app.dependency_overrides."""
    return OTASettings(
        base_path=Path(os.environ.get("OTA_BASE_PATH", "/www/wwwroot/ota")),
        private_key_path=(
            Path(os.environ["OTA_PRIVATE_KEY_PATH"])
            if "OTA_PRIVATE_KEY_PATH" in os.environ
            else None
        ),
        admin_token=os.environ.get("OTA_ADMIN_TOKEN"),
        hostname=os.environ.get("OTA_HOSTNAME", "http://47.100.235.168:8083"),
        default_channel=os.environ.get("OTA_DEFAULT_CHANNEL", "production"),
        asset_base_url=os.environ.get("OTA_ASSET_BASE_URL") or None,
        asset_store_base_url=os.environ.get("OTA_ASSET_STORE_BASE_URL") or None,
    )
