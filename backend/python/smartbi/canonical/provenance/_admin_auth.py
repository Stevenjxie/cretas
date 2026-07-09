"""Shared admin RBAC helper for the Sub-Project C provenance API surface.

P2-6 (post-D27 review extract): previously duplicated 30 lines between
``provenance_audit.py`` (Day 26) and ``factory_provenance_config.py``
(Day 27), with 1 line of error-message drift. Both call sites now route
here.

Auth model
----------
JWT auth populates ``request.state.{role,factory_id,auth_method}`` via
``backend/python/auth_middleware.py``. Admin-tier roles
(platform_admin / factory_super_admin / permission_admin) are allowed
through. Internal Java→Python calls (``auth_method=='internal'``) bypass
the role check but still benefit from the factory_id RLS filter
downstream.

Status code policy
------------------
- ``401`` when the request carries no auth state at all (middleware
  should have caught it; this is defense-in-depth).
- ``403`` when the role is present but not admin-tier.

Both messages are Chinese (P1-3 i18n consistency); the ``action_name``
parameter lets each caller customize which feature is being gated.
"""
from __future__ import annotations

from fastapi import HTTPException, Request

# Admin tier mirrors the FE router meta.roles guard at
# ``web-admin/src/router/index.ts`` for the matching Vue routes. Any
# change here MUST be reflected there too.
ADMIN_ROLES = frozenset({
    "platform_admin",
    "factory_super_admin",
    "permission_admin",
})


def require_admin(request: Request, *, action_name: str) -> str:
    """Return the JWT factory_id, or raise on insufficient permission.

    Args:
        request: FastAPI request with ``state.role`` populated by JWT
            middleware.
        action_name: Chinese short label for the action being protected
            (e.g. ``"字段血统审计"``, ``"Provenance 配置"``). Surfaced
            in the 403 detail message.

    Returns:
        The JWT's ``factory_id`` (empty string when ``auth_method ==
        'internal'`` and no factory_id was attached — caller may treat
        empty string as "skip belt-and-suspenders match").

    Raises:
        HTTPException(401): no auth state at all.
        HTTPException(403): role is not admin-tier.
    """
    role = getattr(request.state, "role", None)
    auth_method = getattr(request.state, "auth_method", None)
    factory_id = getattr(request.state, "factory_id", None)

    if auth_method == "internal":
        return factory_id or ""

    if role is None:
        raise HTTPException(status_code=401, detail="未登录或会话已过期")

    if role not in ADMIN_ROLES:
        raise HTTPException(
            status_code=403,
            detail=f"{action_name}需要管理员权限 (当前角色 {role!r} 无权访问)",
        )

    return factory_id or ""


def require_factory_scope(
    request: Request,
    client_factory_id: str | None,
    *,
    field_name: str = "factoryId",
) -> None:
    """Reject client-supplied factory IDs outside the caller's JWT tenant."""
    if not client_factory_id:
        return
    if getattr(request.state, "auth_method", None) == "internal":
        return
    role = getattr(request.state, "role", None)
    jwt_factory_id = getattr(request.state, "factory_id", None)
    if role != "platform_admin" and jwt_factory_id and client_factory_id != jwt_factory_id:
        raise HTTPException(
            status_code=403,
            detail=f"非 platform_admin 仅可访问自己工厂 (当前工厂 {jwt_factory_id!r}, {field_name}={client_factory_id!r})",
        )
