import pytest

from mock_platform.config import ConfigError, get_settings


def test_缺必需环境变量直接报错不静默降级(monkeypatch):
    get_settings.cache_clear()
    monkeypatch.delenv("MOCK_KERUYUN_APP_KEY", raising=False)
    monkeypatch.setenv("MOCK_KERUYUN_APP_SECRET", "s")
    monkeypatch.setenv("MOCK_CALLBACK_SECRET", "c")
    with pytest.raises(ConfigError, match="MOCK_KERUYUN_APP_KEY"):
        get_settings()
    get_settings.cache_clear()
