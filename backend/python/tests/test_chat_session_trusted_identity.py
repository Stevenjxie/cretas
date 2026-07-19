from types import SimpleNamespace

import pytest

from smartbi.services.chat_session_service import (
    POSTGRES_BIGINT_MAX,
    parse_trusted_user_id,
)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (1, 1),
        (POSTGRES_BIGINT_MAX, POSTGRES_BIGINT_MAX),
        ("42", 42),
        ("00042", 42),
        (None, None),
        (True, None),
        (False, None),
        (1.0, None),
        (1.9, None),
        (0, None),
        (-1, None),
        (POSTGRES_BIGINT_MAX + 1, None),
        ("", None),
        (" 42", None),
        ("42 ", None),
        ("+42", None),
        ("-42", None),
        ("42.0", None),
        ("٤٢", None),
        ({"id": 42}, None),
    ],
)
def test_parse_trusted_user_id_is_strict(raw, expected):
    assert parse_trusted_user_id(raw) == expected


def _request(raw_user_id):
    return SimpleNamespace(
        state=SimpleNamespace(
            factory_id="FACTORY_A",
            user_id=raw_user_id,
            role="factory_super_admin",
        )
    )


@pytest.mark.parametrize("raw", [True, False, 1.0, 1.9, "+1", " 1", "1.0"])
def test_synthesis_and_general_chat_identity_fail_closed_on_coercible_values(raw):
    from smartbi.api import chat, synthesis

    assert synthesis._trusted_user_id(_request(raw)) is None
    _role, user_key, session_user_id, _price_view = chat._trusted_chat_identity(
        _request(raw)
    )
    assert user_key == "__NO_TRUSTED_USER__"
    assert session_user_id is None


@pytest.mark.asyncio
@pytest.mark.parametrize("raw", [True, False, 1.0, 1.9, "+1", " 1", "1.0"])
async def test_legacy_three_path_helper_fails_closed_without_touching_db(raw):
    from smartbi.api import chat

    parent, factory_id, user_id = await chat._v2_conv_lookup(
        _request(raw),
        "shared-session",
    )

    assert parent is None
    assert factory_id == "FACTORY_A"
    assert user_id is None


@pytest.mark.asyncio
async def test_legacy_helper_accepts_valid_ascii_decimal_string():
    from smartbi.api import chat

    parent, factory_id, user_id = await chat._v2_conv_lookup(
        _request("42"),
        None,
    )

    assert parent is None
    assert factory_id == "FACTORY_A"
    assert user_id == 42
