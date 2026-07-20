"""Fail-closed validation for persisted AgentOps payloads."""

from __future__ import annotations

import hashlib
import json
import re
import uuid
from decimal import Decimal, InvalidOperation
from typing import Any, Mapping, Sequence

from smartbi.agent.runtime.run_contracts import GrossMarginDeclineRequest, RouteCode

MAX_CASES = 100
MAX_RUNTIME_SHADOW_CASES = 20
MAX_CASE_BYTES = 32_768
MAX_SNAPSHOT_BYTES = 64_000
MAX_AGENT_OPS_PAYLOAD_BYTES = 4 * 1024 * 1024
MAX_TOOLS = 10
MAX_NUMERIC_REFS = 100
_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 _.-]{0,95}$")
_EVALUATOR = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_FORBIDDEN_KEYS = re.compile(
    r"(?:prompt|raw[_-]?prompt|secret|token|password|authorization|cookie|api[_-]?key|credential|factory[_-]?id|tenant[_-]?id|user[_-]?id)",
    re.IGNORECASE,
)
_SAFE_SNAPSHOT_DIGEST_KEYS = {
    "promptSnapshotDigest", "modelSnapshotDigest", "toolSnapshotDigest",
}
_RUNTIME_CASE_EXTRA_KEYS = {"inputSnapshot", "sourceRunId", "evidenceDigests"}
_RUNTIME_EVIDENCE_DIGEST_KEYS = {
    "inputDigest",
    "trajectoryDigest",
    "numericTruthDigest",
    "evidenceDigest",
    "sourceRunDigest",
}


class AgentOpsValidationError(ValueError):
    pass


def canonical_digest(value: Any) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def validate_name(value: str) -> str:
    value = value.strip()
    if not _NAME.fullmatch(value):
        raise AgentOpsValidationError("INVALID_EVAL_SET_NAME")
    return value


def validate_evaluator(value: str) -> str:
    value = value.strip()
    if not _EVALUATOR.fullmatch(value):
        raise AgentOpsValidationError("INVALID_EVALUATOR_VERSION")
    return value


def validate_request_id(value: str) -> str:
    if not isinstance(value, str):
        raise AgentOpsValidationError("INVALID_REQUEST_ID")
    try:
        return str(uuid.UUID(value))
    except (ValueError, AttributeError) as exc:
        raise AgentOpsValidationError("INVALID_REQUEST_ID") from exc


def validate_cases(cases: Sequence[Mapping[str, Any]]) -> tuple[dict[str, Any], ...]:
    if not 1 <= len(cases) <= MAX_CASES:
        raise AgentOpsValidationError("EVAL_CASE_COUNT_OUT_OF_BOUNDS")
    normalized = tuple(validate_case(case) for case in cases)
    ids = [case["caseId"] for case in normalized]
    if len(ids) != len(set(ids)):
        raise AgentOpsValidationError("DUPLICATE_CASE_ID")
    return normalized


def validate_case(case: Mapping[str, Any]) -> dict[str, Any]:
    expected_keys = {
        "caseId", "expectedRoute", "requiredTools", "numericTruthRefs",
        "maxRounds", "maxToolCalls",
    }
    if not isinstance(case, Mapping) or set(case) not in (
        expected_keys,
        expected_keys | _RUNTIME_CASE_EXTRA_KEYS,
    ):
        raise AgentOpsValidationError("INVALID_EVAL_CASE_SCHEMA")
    case_id = _identifier(case["caseId"], "INVALID_CASE_ID")
    if case["expectedRoute"] != RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION.value:
        raise AgentOpsValidationError("UNSUPPORTED_EXPECTED_ROUTE")
    tools = _tools(case["requiredTools"])
    refs = _numeric_refs(case["numericTruthRefs"])
    max_rounds = _bounded_int(case["maxRounds"], 1, 2, "INVALID_MAX_ROUNDS")
    max_calls = _bounded_int(case["maxToolCalls"], 1, MAX_TOOLS, "INVALID_MAX_TOOL_CALLS")
    normalized = {
        "caseId": case_id,
        "expectedRoute": case["expectedRoute"],
        "requiredTools": tools,
        "numericTruthRefs": refs,
        "maxRounds": max_rounds,
        "maxToolCalls": max_calls,
    }
    if _RUNTIME_CASE_EXTRA_KEYS.issubset(case):
        normalized.update({
            "inputSnapshot": validate_runtime_input_snapshot(case["inputSnapshot"]),
            "sourceRunId": validate_request_id(case["sourceRunId"]),
            "evidenceDigests": _runtime_evidence_digests(case["evidenceDigests"]),
        })
    _bounded_json(normalized, MAX_CASE_BYTES, "EVAL_CASE_TOO_LARGE")
    return normalized


def validate_runtime_shadow_cases(
    cases: Sequence[Mapping[str, Any]],
) -> tuple[dict[str, Any], ...]:
    if not 1 <= len(cases) <= MAX_RUNTIME_SHADOW_CASES:
        raise AgentOpsValidationError("RUNTIME_SHADOW_CASE_COUNT_OUT_OF_BOUNDS")
    normalized = validate_cases(cases)
    if any(not _RUNTIME_CASE_EXTRA_KEYS.issubset(case) for case in normalized):
        raise AgentOpsValidationError("RUNTIME_SHADOW_CASE_REQUIRED")
    return normalized


def validate_runtime_input_snapshot(value: Mapping[str, Any]) -> dict[str, Any]:
    expected = {"startDate", "endDate", "storeTopN", "dishTopN"}
    if not isinstance(value, Mapping) or set(value) != expected:
        raise AgentOpsValidationError("INVALID_RUNTIME_INPUT_SNAPSHOT")
    try:
        request = GrossMarginDeclineRequest(
            start_date=value["startDate"],
            end_date=value["endDate"],
            store_top_n=value["storeTopN"],
            dish_top_n=value["dishTopN"],
        )
    except (TypeError, ValueError, KeyError) as exc:
        raise AgentOpsValidationError("INVALID_RUNTIME_INPUT_SNAPSHOT") from exc
    return {
        "startDate": request.start_date,
        "endDate": request.end_date,
        "storeTopN": request.store_top_n,
        "dishTopN": request.dish_top_n,
    }


def validate_actual_snapshot(value: Mapping[str, Any]) -> dict[str, Any]:
    keys = {"routeCode", "tools", "numericTruthRefs", "roundsUsed", "toolCallsUsed"}
    if not isinstance(value, Mapping) or set(value) != keys:
        raise AgentOpsValidationError("INVALID_ACTUAL_SNAPSHOT_SCHEMA")
    route = value["routeCode"]
    if route != RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION.value:
        # Other controlled strings are admitted so route mismatch can be measured.
        route = _identifier(route, "INVALID_ACTUAL_ROUTE")
    tools = _tools(value["tools"])
    rounds_used = _bounded_int(value["roundsUsed"], 0, 2, "INVALID_ROUNDS_USED")
    tool_calls_used = _bounded_int(
        value["toolCallsUsed"], 0, MAX_TOOLS, "INVALID_TOOL_CALLS_USED"
    )
    if tool_calls_used < len(tools) or (tools and rounds_used == 0):
        raise AgentOpsValidationError("INCONSISTENT_ACTUAL_COUNTERS")
    normalized = {
        "routeCode": route,
        "tools": tools,
        "numericTruthRefs": _numeric_refs(value["numericTruthRefs"]),
        "roundsUsed": rounds_used,
        "toolCallsUsed": tool_calls_used,
    }
    _bounded_json(normalized, MAX_SNAPSHOT_BYTES, "ACTUAL_SNAPSHOT_TOO_LARGE")
    return normalized


def validate_actual_snapshots(
    value: Mapping[str, Mapping[str, Any]],
) -> dict[str, dict[str, Any]]:
    if not isinstance(value, Mapping) or not 1 <= len(value) <= MAX_CASES:
        raise AgentOpsValidationError("ACTUAL_SNAPSHOT_COUNT_OUT_OF_BOUNDS")
    normalized: dict[str, dict[str, Any]] = {}
    for raw_case_id in sorted(value):
        case_id = _identifier(raw_case_id, "INVALID_CASE_ID")
        normalized[case_id] = validate_actual_snapshot(value[raw_case_id])
    _bounded_json(
        normalized,
        MAX_AGENT_OPS_PAYLOAD_BYTES,
        "ACTUAL_SNAPSHOTS_PAYLOAD_TOO_LARGE",
    )
    return normalized


def validate_config_snapshot(value: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(value, Mapping) or set(value) != _SAFE_SNAPSHOT_DIGEST_KEYS:
        raise AgentOpsValidationError("INVALID_CONFIG_SNAPSHOT")
    _reject_forbidden(value)
    try:
        normalized = json.loads(json.dumps(value, ensure_ascii=False, allow_nan=False))
    except (TypeError, ValueError) as exc:
        raise AgentOpsValidationError("CONFIG_VALUE_NOT_JSON_SAFE") from exc
    missing = _SAFE_SNAPSHOT_DIGEST_KEYS - set(normalized)
    if missing:
        raise AgentOpsValidationError("REQUIRED_SNAPSHOT_DIGEST_MISSING")
    for key in _SAFE_SNAPSHOT_DIGEST_KEYS:
        digest = normalized[key]
        if not isinstance(digest, str) or not _SHA256.fullmatch(digest):
            raise AgentOpsValidationError("INVALID_SNAPSHOT_DIGEST")
    _bounded_json(normalized, MAX_SNAPSHOT_BYTES, "CONFIG_SNAPSHOT_TOO_LARGE")
    return normalized


def validate_runner_bounds_snapshot(value: Mapping[str, Any]) -> dict[str, Any]:
    expected = {"maxCases", "maxConcurrency", "perCaseTimeoutMs"}
    if not isinstance(value, Mapping) or set(value) != expected:
        raise AgentOpsValidationError("INVALID_RUNNER_BOUNDS_SCHEMA")
    return {
        "maxCases": _bounded_int(value["maxCases"], 1, MAX_CASES, "INVALID_MAX_CASES"),
        "maxConcurrency": _bounded_int(
            value["maxConcurrency"], 1, 4, "INVALID_MAX_CONCURRENCY"
        ),
        "perCaseTimeoutMs": _bounded_int(
            value["perCaseTimeoutMs"], 50, 75_000, "INVALID_CASE_TIMEOUT"
        ),
    }


def validate_runtime_shadow_bounds_snapshot(value: Mapping[str, Any]) -> dict[str, Any]:
    expected = {"maxCases", "maxConcurrency", "perCaseTimeoutMs"}
    if not isinstance(value, Mapping) or set(value) != expected:
        raise AgentOpsValidationError("INVALID_RUNNER_BOUNDS_SCHEMA")
    return {
        "maxCases": _bounded_int(
            value["maxCases"], 1, MAX_RUNTIME_SHADOW_CASES, "INVALID_MAX_CASES"
        ),
        "maxConcurrency": _bounded_int(
            value["maxConcurrency"], 1, 2, "INVALID_MAX_CONCURRENCY"
        ),
        "perCaseTimeoutMs": _bounded_int(
            value["perCaseTimeoutMs"], 1_000, 75_000, "INVALID_CASE_TIMEOUT"
        ),
    }


def ensure_payload_budget(value: Any, code: str = "AGENT_OPS_PAYLOAD_TOO_LARGE") -> None:
    _bounded_json(value, MAX_AGENT_OPS_PAYLOAD_BYTES, code)


def _reject_forbidden(value: Any) -> None:
    if isinstance(value, Mapping):
        for key, item in value.items():
            if not isinstance(key, str) or (
                key not in _SAFE_SNAPSHOT_DIGEST_KEYS and _FORBIDDEN_KEYS.search(key)
            ):
                raise AgentOpsValidationError("FORBIDDEN_CONFIG_KEY")
            _reject_forbidden(item)
    elif isinstance(value, list):
        if len(value) > 100:
            raise AgentOpsValidationError("CONFIG_COLLECTION_TOO_LARGE")
        for item in value:
            _reject_forbidden(item)
    elif value is not None and not isinstance(value, (str, int, float, bool)):
        raise AgentOpsValidationError("CONFIG_VALUE_NOT_JSON_SAFE")


def _tools(value: Any) -> list[str]:
    if not isinstance(value, list) or len(value) > MAX_TOOLS:
        raise AgentOpsValidationError("INVALID_TOOL_TRAJECTORY")
    return [_identifier(item, "INVALID_TOOL_NAME") for item in value]


def _numeric_refs(value: Any) -> dict[str, str]:
    if not isinstance(value, Mapping) or len(value) > MAX_NUMERIC_REFS:
        raise AgentOpsValidationError("INVALID_NUMERIC_TRUTH_REFS")
    result: dict[str, str] = {}
    for ref, raw in value.items():
        key = _identifier(ref, "INVALID_NUMERIC_TRUTH_REF")
        if not isinstance(raw, str) or len(raw) > 96:
            raise AgentOpsValidationError("INVALID_NUMERIC_TRUTH_VALUE")
        try:
            parsed = Decimal(raw)
        except InvalidOperation as exc:
            raise AgentOpsValidationError("INVALID_NUMERIC_TRUTH_VALUE") from exc
        if not parsed.is_finite():
            raise AgentOpsValidationError("INVALID_NUMERIC_TRUTH_VALUE")
        rendered = format(parsed, "f")
        if "." in rendered:
            rendered = rendered.rstrip("0").rstrip(".")
        result[key] = rendered or "0"
    return result


def _runtime_evidence_digests(value: Any) -> dict[str, str]:
    if not isinstance(value, Mapping) or set(value) != _RUNTIME_EVIDENCE_DIGEST_KEYS:
        raise AgentOpsValidationError("INVALID_RUNTIME_EVIDENCE_DIGESTS")
    normalized: dict[str, str] = {}
    for key in sorted(_RUNTIME_EVIDENCE_DIGEST_KEYS):
        digest = value[key]
        if not isinstance(digest, str) or not _SHA256.fullmatch(digest):
            raise AgentOpsValidationError("INVALID_RUNTIME_EVIDENCE_DIGESTS")
        normalized[key] = digest
    return normalized


def _identifier(value: Any, code: str) -> str:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise AgentOpsValidationError(code)
    return value


def _bounded_int(value: Any, low: int, high: int, code: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise AgentOpsValidationError(code)
    return value


def _bounded_json(value: Any, maximum: int, code: str) -> None:
    try:
        encoded = json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
        ).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise AgentOpsValidationError(code) from exc
    if len(encoded) > maximum:
        raise AgentOpsValidationError(code)
