"""
JWT Authentication Middleware for Python Services

Verifies JWT tokens issued by the Java backend (HS256).
Extracts factoryId and injects it into request.state for downstream endpoints.

Whitelist paths (no auth required):
- /health, /docs, /redoc, /openapi.json, / (root)
- /api/public/* (public endpoints)
- /api/chart/*, /api/smartbi/chart/* (stateless data processing)
- /api/insight/* (stateless LLM analysis)

Protected paths:
- /api/smartbi/excel/*, /api/forecast/*,
  /api/statistical/*, /api/chat/*, /api/analysis/*,
  /api/ml/*, /api/linucb/*, /api/finance/*, /api/food-kb/*
"""
from __future__ import annotations

import hmac
import json
import logging
import os
import re
from typing import Optional, Set

import jwt as pyjwt
from fastapi import Request
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from smartbi_compat._auth_envelope import (
    build_unauthorized_body,
    is_smartbi_java_envelope_path,
)

logger = logging.getLogger(__name__)

# Paths that do NOT require authentication
PUBLIC_PATHS: Set[str] = {
    "/",
    "/health",
    "/docs",
    "/redoc",
    "/openapi.json",
    "/metrics",  # v4 E (Apr 26 2026): Prometheus scraper endpoint
}

# Path prefixes that do NOT require authentication
PUBLIC_PREFIXES = (
    "/api/public/",
    "/api/ai/",          # AI proxy — called by Java backend internally
    "/api/classifier/",  # Classifier — called by Java backend internally
    "/api/client-requirement/",  # Client requirement wizard (public)
    "/api/chart/",       # Chart building — stateless data processing, no user context needed
    "/api/smartbi/chart/",  # SmartBI chart endpoints (same reason)
    "/api/insight/",     # Insight endpoints — stateless LLM analysis, no user context needed
    "/api/excel/",       # Excel parsing — called by Java backend internally (no JWT forwarded)
    "/api/smartbi/excel/",  # SmartBI Excel endpoints — called by Java backend internally
    "/api/smartbi/analysis-cache/",  # SmartBI analysis cache — browser direct calls
    "/api/smartbi/restaurant/sections/",  # Restaurant section handlers — called by Java backend internally
    "/api/smartbi/cross-sheet",  # Cross-sheet analysis
    "/api/smartbi/yoy-",  # YoY comparison
    "/api/smartbi/benchmark",  # Industry benchmarks
    "/api/smartbi/layouts",  # Dashboard layout persistence — frontend direct calls
    # "/api/smartbi/restaurant-analytics/" — removed: requires JWT auth (IDOR fix)
    "/api/statistical/",  # Statistical analysis
    "/api/analysis/",  # Analysis endpoints
    "/api/forecast/",  # Forecast endpoints
    "/api/efficiency/",  # Efficiency recognition — called by Java backend internally
    "/api/food-kb/manual-chat",  # Operation manual chat — public (HTML page)
    # "/api/smartbi/financial-dashboard/" — removed: requires JWT auth (IDOR fix)
    "/api/llm/",              # LLM router — called by Java backend internally (no JWT forwarded)
    # OTA (self-hosted Expo Updates v1 server). All public paths exposed to
    # the `expo-updates` client running on customer devices, which does NOT
    # send JWTs (the protocol is JWT-agnostic; signed manifests are the auth
    # mechanism). The `/api/ota/admin/*` subset has its own OTA_ADMIN_TOKEN
    # bearer gate via `ota.api.endpoints._require_admin` (hmac.compare_digest),
    # so dropping the JWT layer here does NOT broaden the admin attack
    # surface — both the JWT path and the OTA_ADMIN_TOKEN path use 32-byte
    # random secrets. Decision rationale: see PR #<P0 fix> body §2.
    "/api/ota/",
)

# Exact routes nested below a public prefix that are nevertheless internal-only.
# Keep this list exact: sibling restaurant section routes intentionally retain
# their existing public/demo behavior until they are migrated independently.
INTERNAL_ONLY_PATHS = frozenset({
    "/api/smartbi/restaurant/sections/owner-action-chat",
})
INTERNAL_ONLY_PREFIXES = (
    "/api/internal/smartbi/agent/runs",
)
_INTERNAL_IDENTITY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


def _bounded_internal_identity(headers: dict[str, str], name: str) -> Optional[str]:
    value = headers.get(name)
    if value is None or not _INTERNAL_IDENTITY.fullmatch(value):
        return None
    return value

# Demo tenants (mirror Java `cretas.demo.factory-ids`). The public /mobile-ai/rest/
# page hands one of these JWTs (role factory_super_admin) to ANY anonymous visitor.
# That token is otherwise valid against the whole Python service, where some
# endpoints trust a client-supplied factory_id (cross-tenant IDOR) and Java's
# DemoReadOnlyInterceptor has no effect. Contain the blast radius at this single
# chokepoint: (a) reject any request whose query carries a factory_id != the
# token's own; (b) read-only except an explicit read-analysis allowlist.
DEMO_FACTORY_IDS = frozenset({"DEMO_REST", "DEMO_FACTORY2"})
DEMO_WRITE_ALLOW_PREFIXES = (
    "/api/smartbi/synthesis/",   # the mobile Q&A page (POST, factory from token)
    "/api/chat/",                # general-analysis / drill-down (read-analysis POSTs)
    "/api/smartbi/analysis",     # analysis POSTs
    "/api/smartbi/cross-sheet",
    "/api/smartbi/yoy",
    "/api/smartbi/benchmark",
    "/api/statistical/",
    "/api/analysis/",
    "/api/forecast/",
    "/api/insight/",
    "/api/smartbi/chart/",
    "/api/classifier/",
)
# Read-analysis POSTs whose route carries the tenant id in the MIDDLE of the path
# (`/api/smartbi/{factory_id}/revenue-report/...`), so a static startswith prefix
# can't match. These compute + return/stream a report from the tenant's own data.
# Their only persistent write is a best-effort audit-log row, which is skipped for
# demo tenants (_revenue_report_helpers._log_audit) so the demo path is a genuine
# read-only compute and an anonymous visitor can't grow the audit table. `/upload`
# (real ingestion write) is deliberately NOT here and stays blocked. Exact suffix.
DEMO_WRITE_ALLOW_SUFFIXES = (
    "/revenue-report/prepare",    # LLM-tool path: metadata + download_url
    "/revenue-report/generate",   # web-UI path: streams xlsx
)


class JWTAuthMiddleware:
    """
    Pure ASGI middleware that validates JWT Bearer tokens on protected endpoints.

    Compatible with Java JwtUtil.java:
    - Algorithm: HS256
    - Claims: userId, factoryId, username, role
    - Secret: raw UTF-8 bytes (>=32 bytes for HS256)
    """

    def __init__(self, app: ASGIApp, jwt_secret: str, enabled: bool = True):
        self.app = app
        self.enabled = enabled
        # Match Java's key derivation: UTF-8 bytes, pad to 32 if shorter
        key_bytes = jwt_secret.encode("utf-8")
        if len(key_bytes) < 32:
            key_bytes = key_bytes + b"\x00" * (32 - len(key_bytes))
        self.jwt_secret = key_bytes
        logger.info(f"JWTAuthMiddleware initialized, enabled={enabled}")

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return

        path = scope.get("path", "")

        # Extract headers from scope (needed by both internal-secret + JWT branches)
        headers = dict(
            (k.decode("latin-1").lower(), v.decode("latin-1"))
            for k, v in scope.get("headers", [])
        )

        # Allow legacy X-Internal-Secret for Java->Python internal calls.
        # Apr 22 2026: also propagate X-Factory-Id header so RLS still
        # scopes properly for per-tenant internal jobs. Absent header →
        # INTERNAL sentinel (RLS will return 0 rows; caller must BYPASSRLS
        # or pass X-Factory-Id explicitly).
        # May 1 2026: moved BEFORE PUBLIC_PREFIXES skip so paths under
        # /api/ai/, /api/efficiency/, etc. (which are PUBLIC_PREFIXES so
        # browser/RN can call them without JWT) can still surface
        # auth_method=internal when called by Java with the header. Some
        # handlers (e.g. ai/api.py:154) require auth_method=="internal" and
        # used to fail with 401 because the secret check was skipped first.
        # 2026-07-08 security fix (fail-closed): NO hardcoded fallback secret.
        # The old `os.environ.get(...) or "cretas-internal-2026"` meant that if
        # INTERNAL_API_SECRET was ever unset on the Python process, the public
        # fallback (this repo is public on GitHub) became live — any external
        # caller reaching Python via the gateway could send that known constant
        # + arbitrary X-Factory-Id / X-User-Role and get cross-tenant,
        # RBAC-bypassing access (the internal branch below skips
        # require_factory_match entirely, see line ~326). Now: env unset/blank →
        # expected_secret is falsy → this whole internal branch is unreachable
        # and the request falls through to the JWT / public-prefix checks.
        # hmac.compare_digest = constant-time compare (mirrors the OTA
        # admin-token gate). prod + test .env.prod both set INTERNAL_API_SECRET
        # (verified 2026-07-08), so fail-closed breaks no live Java→Python call;
        # a missing secret is a misconfiguration that MUST fail closed, never
        # silently accept a public constant.
        internal_secret = headers.get("x-internal-secret", "")
        expected_secret = os.environ.get("INTERNAL_API_SECRET") or ""
        if (
            expected_secret
            and internal_secret
            and hmac.compare_digest(
                internal_secret.encode("utf-8"),
                expected_secret.encode("utf-8"),
            )
        ):
            if "state" not in scope:
                scope["state"] = {}
            internal_factory = _bounded_internal_identity(headers, "x-factory-id")
            scope["state"]["factory_id"] = internal_factory
            scope["state"]["user_id"] = _bounded_internal_identity(
                headers, "x-user-id"
            )
            scope["state"]["auth_method"] = "internal"
            scope["state"]["business_type"] = _bounded_internal_identity(
                headers, "x-business-type"
            )
            # May 29 2026: forward the originating user's role so RBAC money-strip
            # (_apply_rbac_strip) respects price-view permission on internal
            # Java→Python calls. Absent header → None → money stripped (safe
            # default). Java GoldFinanceClient sets X-User-Role from the request
            # SecurityContext. Fixes 总营收 ¥0 on restaurant dashboards (the Java
            # dashboard build called finance-summary with no role → all money nulled).
            scope["state"]["role"] = _bounded_internal_identity(
                headers, "x-user-role"
            )
            try:
                from smartbi.tenant_ctx import set_factory_id, reset_factory_id
                tenant_token = set_factory_id(internal_factory)
            except Exception:
                tenant_token = None
            try:
                await self.app(scope, receive, send)
            finally:
                if tenant_token is not None:
                    try:
                        reset_factory_id(tenant_token)
                    except Exception:
                        pass
            return

        # An internal-only route must not fall through into PUBLIC_PREFIXES when
        # the secret is missing, blank, incorrect, or the server is misconfigured
        # without INTERNAL_API_SECRET. Return one indistinguishable response so
        # callers cannot use the status/body as a secret-validity oracle.
        if path in INTERNAL_ONLY_PATHS or any(
            path == prefix or path.startswith(f"{prefix}/")
            for prefix in INTERNAL_ONLY_PREFIXES
        ):
            await self._send_json_response(send, 401, {
                "success": False,
                "message": "Internal authentication required",
                "code": "INTERNAL_AUTH_REQUIRED",
            })
            return

        # Disabling general JWT auth must never disable the exact internal-only
        # route gate above. All other routes retain the historical disabled-mode
        # behavior.
        if not self.enabled:
            await self.app(scope, receive, send)
            return

        # Skip public paths after the internal identity branch so a legitimate
        # Java call still receives tenant context even on a public endpoint.
        if path in PUBLIC_PATHS:
            await self.app(scope, receive, send)
            return

        # Skip public prefixes (no JWT required, no internal-secret either)
        if any(path.startswith(prefix) for prefix in PUBLIC_PREFIXES):
            await self.app(scope, receive, send)
            return

        # Extract Bearer token
        auth_header = headers.get("authorization", "")
        if not auth_header.startswith("Bearer "):
            if is_smartbi_java_envelope_path(path):
                # Issue #530: 3 SmartBI analysis endpoints emit Java-mirrored
                # 188B envelope so customer frontend axios interceptor sees a
                # uniform shape across Python and Java upstreams.
                await self._send_smartbi_unauthorized(send)
            else:
                await self._send_json_response(send, 401, {
                    "success": False,
                    "message": "Missing or invalid Authorization header",
                    "code": "UNAUTHORIZED",
                })
            return

        token = auth_header[7:]  # Strip "Bearer "

        # Verify JWT
        claims = self._verify_token(token)
        if claims is None:
            if is_smartbi_java_envelope_path(path):
                await self._send_smartbi_unauthorized(send)
            else:
                await self._send_json_response(send, 401, {
                    "success": False,
                    "message": "Invalid or expired token",
                    "code": "TOKEN_INVALID",
                })
            return

        # Inject claims into scope state for downstream access via request.state
        if "state" not in scope:
            scope["state"] = {}
        factory_id = claims.get("factoryId")
        scope["state"]["factory_id"] = factory_id
        scope["state"]["user_id"] = claims.get("userId")
        scope["state"]["username"] = claims.get("sub")
        scope["state"]["role"] = claims.get("role")
        scope["state"]["auth_method"] = "jwt"

        # ---- Demo-tenant containment (defense-in-depth chokepoint) ----------
        # See DEMO_FACTORY_IDS note. Only JWT-authenticated demo tokens reach
        # here (internal-secret Java calls returned earlier and are trusted).
        if factory_id in DEMO_FACTORY_IDS:
            # (a) no cross-tenant: a factory_id/factoryId in the query differing
            #     from the token's own factory -> 403.
            try:
                from urllib.parse import parse_qs
                _qs = parse_qs(scope.get("query_string", b"").decode("latin-1"))
                _foreign = any(
                    v and v != factory_id
                    for k in ("factory_id", "factoryId")
                    for v in _qs.get(k, [])
                )
            except Exception:
                _foreign = False
            if _foreign:
                await self._send_json_response(send, 403, {
                    "success": False, "code": "DEMO_TENANT_SCOPE",
                    "message": "演示账号只能访问本演示租户的数据",
                })
                return
            # (b) read-only except the read-analysis allowlist (blocks the
            #     ETL-trigger / sku-form / monthly-purchase write IDORs).
            _method = scope.get("method", "GET")
            if _method not in ("GET", "HEAD", "OPTIONS") and not any(
                path.startswith(p) for p in DEMO_WRITE_ALLOW_PREFIXES
            ) and not any(
                path.endswith(s) for s in DEMO_WRITE_ALLOW_SUFFIXES
            ):
                await self._send_json_response(send, 403, {
                    "success": False, "code": "DEMO_READ_ONLY",
                    "message": "演示账号为只读，无法执行该写操作",
                })
                return

        # Propagate factory_id to contextvars:
        #  (1) llm_metrics _llm_factory — LLM usage rows tagged with factory
        #  (2) tenant_ctx current_factory_id — asyncpg pool setup callback
        #      reads this to SET app.factory_id on every borrowed connection
        #      (RLS policies key off this; see smartbi/tenant_ctx.py)
        try:
            from common.llm_metrics import _llm_factory
            llm_token = _llm_factory.set(factory_id)
        except Exception:
            llm_token = None
        try:
            from smartbi.tenant_ctx import set_factory_id, reset_factory_id
            tenant_token = set_factory_id(factory_id)
        except Exception:
            tenant_token = None
        try:
            await self.app(scope, receive, send)
        finally:
            if llm_token is not None:
                try:
                    from common.llm_metrics import _llm_factory
                    _llm_factory.reset(llm_token)
                except Exception:
                    pass
            if tenant_token is not None:
                try:
                    from smartbi.tenant_ctx import reset_factory_id
                    reset_factory_id(tenant_token)
                except Exception:
                    pass
        return

    def _verify_token(self, token: str) -> Optional[dict]:
        """Verify JWT token and return claims, or None if invalid."""
        try:
            payload = pyjwt.decode(
                token,
                self.jwt_secret,
                algorithms=["HS256"],
                options={"verify_exp": True},
            )
            return payload
        except pyjwt.ExpiredSignatureError:
            logger.warning("JWT token expired")
            return None
        except pyjwt.InvalidTokenError as e:
            logger.warning(f"JWT verification failed: {e}")
            return None

    @staticmethod
    async def _send_json_response(send: Send, status_code: int, body: dict):
        """Send a JSON error response directly via ASGI."""
        body_bytes = json.dumps(body).encode("utf-8")
        await send({
            "type": "http.response.start",
            "status": status_code,
            "headers": [
                [b"content-type", b"application/json"],
                [b"access-control-allow-origin", b"*"],
            ],
        })
        await send({
            "type": "http.response.body",
            "body": body_bytes,
        })

    @staticmethod
    async def _send_smartbi_unauthorized(send: Send):
        """Send the Java-mirrored 188-byte 401 envelope (issue #530).

        Compact separators + ensure_ascii=False so the on-wire bytes match
        Jackson's default output (no inter-token spaces, raw UTF-8 Chinese).
        Content-type advertises UTF-8 charset for clients that key off it.
        """
        body_bytes = json.dumps(
            build_unauthorized_body(),
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode("utf-8")
        await send({
            "type": "http.response.start",
            "status": 401,
            "headers": [
                [b"content-type", b"application/json; charset=utf-8"],
                [b"access-control-allow-origin", b"*"],
            ],
        })
        await send({
            "type": "http.response.body",
            "body": body_bytes,
        })


def get_factory_id_from_request(request: Request) -> Optional[str]:
    """Helper to extract factory_id from request state (set by middleware)."""
    return getattr(request.state, "factory_id", None)


def require_factory_match(request: Request, factory_id: str) -> Optional[JSONResponse]:
    """
    Verify that the authenticated user's factoryId matches the requested factory_id.
    Returns a 403 JSONResponse if mismatch, or None if OK.

    Usage in endpoint:
        error = require_factory_match(request, body.factory_id)
        if error:
            return error
    """
    auth_method = getattr(request.state, "auth_method", None)

    # Internal calls (Java->Python) bypass factory check
    if auth_method == "internal":
        return None

    token_factory_id = getattr(request.state, "factory_id", None)

    # If token has no factoryId (e.g. platform_admin), allow all
    if not token_factory_id:
        return None

    # If request doesn't specify factory_id, allow (endpoint handles its own logic)
    if not factory_id:
        return None

    if token_factory_id != factory_id:
        logger.warning(
            f"Factory ID mismatch: token={token_factory_id}, request={factory_id}"
        )
        return JSONResponse(
            status_code=403,
            content={
                "success": False,
                "message": f"Access denied: you belong to {token_factory_id}, not {factory_id}",
                "code": "FACTORY_MISMATCH",
            },
        )

    return None
