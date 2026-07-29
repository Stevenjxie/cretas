"""配置全部来自环境变量。没有默认密钥——缺了就报错，不静默用弱值。"""
from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache


class ConfigError(RuntimeError):
    """必需配置缺失。启动期直接失败，不带着空密钥跑。"""


def _required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ConfigError(f"缺少必需环境变量 {name}")
    return value


@dataclass(frozen=True)
class Settings:
    db_path: str
    keruyun_app_key: str
    keruyun_app_secret: str
    callback_url: str
    callback_secret: str
    store_count: int
    orders_per_store_per_day: int


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings(
        db_path=os.getenv("MOCK_DB_PATH", "/www/wwwroot/mock-platform/data.db"),
        keruyun_app_key=_required("MOCK_KERUYUN_APP_KEY"),
        keruyun_app_secret=_required("MOCK_KERUYUN_APP_SECRET"),
        callback_url=os.getenv("MOCK_CALLBACK_URL", ""),
        callback_secret=_required("MOCK_CALLBACK_SECRET"),
        store_count=int(os.getenv("MOCK_STORE_COUNT", "10")),
        orders_per_store_per_day=int(os.getenv("MOCK_ORDERS_PER_STORE", "200")),
    )
