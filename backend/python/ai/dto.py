"""Pydantic DTOs for AI intent matching layer.

Byte-shape compatible with Java's Jackson default serialization. Field names,
types, and enum values mirror the Java source files exactly so JSON moving
between Java <-> Python deserializes identically on both sides.

Java sources (source of truth):
- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/intent/IntentMatchResult.java
  19 top-level fields + nested CandidateIntent, IntentMatch, MatchMethod (12)
- backend/java/cretas-api/src/main/java/com/cretas/aims/entity/config/AIIntentConfig.java
  30 instance fields (+ id from BaseEntity)
- backend/java/cretas-api/src/main/java/com/cretas/aims/config/IntentKnowledgeBase.java
  ActionType (6), QuestionType (4) enums
- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/ai/PreprocessedQuery.java
  Subset of fields ported (originalInput, normalizedText, finalQuery, qualityScore,
  llmRewriteTriggered) plus extras as needed for byte-shape parity
- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/intent/MultiIntentResult.java
  ExecutionStrategy enum (3 values)

Field naming: camelCase (matches Java; per project rule field-naming-convention.md
JSON layer uses camelCase regardless of Python convention).
"""
from __future__ import annotations

from enum import Enum
from typing import Any, Dict, Generic, List, Optional, TypeVar

from pydantic import BaseModel, ConfigDict, Field


# ======================================================================
# Enums (mirror Java enum values exactly, including order)
# ======================================================================


class MatchMethod(str, Enum):
    """Intent match method.

    Mirrors com.cretas.aims.dto.intent.IntentMatchResult.MatchMethod.
    All 12 values must round-trip through JSON identically with Java.
    """

    EXACT = "EXACT"
    PHRASE_MATCH = "PHRASE_MATCH"
    REGEX = "REGEX"
    KEYWORD = "KEYWORD"
    SEMANTIC = "SEMANTIC"
    CLASSIFIER = "CLASSIFIER"
    FUSION = "FUSION"
    SIMILAR = "SIMILAR"
    LLM = "LLM"
    DOMAIN_DEFAULT = "DOMAIN_DEFAULT"
    REJECTED = "REJECTED"
    NONE = "NONE"


class ActionType(str, Enum):
    """User action type detected from input.

    Mirrors com.cretas.aims.config.IntentKnowledgeBase.ActionType (6 values).
    """

    QUERY = "QUERY"
    CREATE = "CREATE"
    UPDATE = "UPDATE"
    DELETE = "DELETE"
    AMBIGUOUS = "AMBIGUOUS"
    UNKNOWN = "UNKNOWN"


class QuestionType(str, Enum):
    """First-layer question classification.

    Mirrors com.cretas.aims.config.IntentKnowledgeBase.QuestionType (4 values).
    """

    OPERATIONAL_COMMAND = "OPERATIONAL_COMMAND"
    GENERAL_QUESTION = "GENERAL_QUESTION"
    CONVERSATIONAL = "CONVERSATIONAL"
    UNDETERMINED = "UNDETERMINED"


class ExecutionStrategy(str, Enum):
    """Multi-intent execution strategy.

    Mirrors com.cretas.aims.dto.intent.MultiIntentResult.ExecutionStrategy
    (3 values).
    """

    PARALLEL = "PARALLEL"
    SEQUENTIAL = "SEQUENTIAL"
    USER_CONFIRM = "USER_CONFIRM"


# ======================================================================
# Base config: enforce camelCase field names + serialize-by-alias
# ======================================================================


class _CamelBase(BaseModel):
    """Base for all DTOs.

    - populate_by_name=True: allow constructing by either the field name
      or its alias (handy for tests/JSON ingest).
    - extra='ignore': tolerate Java sending fields we haven't ported yet.
    """

    model_config = ConfigDict(
        populate_by_name=True,
        extra="ignore",
    )


# ======================================================================
# AIIntentConfig DTO (mirrors entity/config/AIIntentConfig.java + BaseEntity.id)
# ======================================================================


class AIIntentConfigDto(_CamelBase):
    """Mirror of Java AIIntentConfig entity (excluding BaseEntity audit fields).

    Field count: 30 (matches Java instance fields). All `@Builder.Default`
    annotated fields use the same defaults here so byte-shape stays identical
    when Java emits a freshly-built config.

    Note: BaseEntity audit fields (createdAt/updatedAt/deletedAt) are NOT
    included here — Jackson serialization on read paths typically excludes
    them via @JsonIgnore patterns; if needed in future, add them with
    explicit Optional[datetime] fields.
    """

    id: Optional[str] = None

    # Factory & business scope
    factoryId: Optional[str] = None
    businessType: str = "COMMON"

    # Identity
    intentCode: str
    intentName: str
    intentCategory: Optional[str] = None

    # Sensitivity & access control
    sensitivityLevel: str = "LOW"
    requiredRoles: Optional[str] = None  # JSON array string

    # Quota & cache
    quotaCost: int = 1
    cacheTtlMinutes: int = 0

    # Approval
    requiresApproval: bool = False
    approvalChainId: Optional[str] = None

    # Keywords & matching
    keywords: Optional[str] = None  # JSON array string
    negativeKeywords: Optional[str] = None  # JSON array string
    negativeKeywordPenalty: int = 15
    regexPattern: Optional[str] = None

    # Reranking context
    description: Optional[str] = None
    exampleQueries: Optional[str] = None  # JSON array string
    negativeExamples: Optional[str] = None  # JSON array string

    # Routing (handler vs tool)
    handlerClass: Optional[str] = None  # @Deprecated in Java
    toolName: Optional[str] = None

    # LLM response config
    maxTokens: int = 2000
    responseTemplate: Optional[str] = None

    # Active flags
    isActive: bool = True
    priority: int = 0
    metadata: Optional[str] = None  # JSON

    # SmartBI specific
    chartType: Optional[str] = None
    requiredEntities: Optional[str] = None  # JSON array string
    # Java BigDecimal serializes as JSON number (not string); float matches.
    # Pydantic v2 emits Decimal as a JSON string by default which would break
    # byte-shape parity with Jackson on Java side. Float ensures the wire
    # value is a JSON number and round-trips identically with BigDecimal.
    confidenceBoost: float = 0.0

    # Versioning
    configVersion: int = 1
    previousSnapshot: Optional[str] = None  # JSON snapshot

    # Semantic layer
    semanticDomain: Optional[str] = None
    semanticAction: Optional[str] = None
    semanticObject: Optional[str] = None
    semanticPath: Optional[str] = None  # computed in Java; kept for read-back


# ======================================================================
# Nested intent DTOs
# ======================================================================


class CandidateIntentDto(_CamelBase):
    """Nested IntentMatchResult.CandidateIntent (8 fields)."""

    intentCode: Optional[str] = None
    intentName: Optional[str] = None
    intentCategory: Optional[str] = None
    confidence: Optional[float] = None
    matchScore: Optional[int] = None
    matchedKeywords: List[str] = Field(default_factory=list)
    matchMethod: Optional[MatchMethod] = None
    description: Optional[str] = None


class IntentMatchInner(_CamelBase):
    """Nested IntentMatchResult.IntentMatch (6 fields).

    Used inside additionalIntents on IntentMatchResult for multi-intent.
    Distinct from MultiIntentResult.SingleIntentMatch which is similar but lives
    in a different parent — kept separate to match Java's nested class hierarchy.
    """

    intentCode: Optional[str] = None
    intentName: Optional[str] = None
    confidence: float = 0.0
    extractedParams: Optional[Dict[str, Any]] = None
    executionOrder: int = 0
    reasoning: Optional[str] = None


# ======================================================================
# PreprocessedQuery DTO (subset — only fields commonly serialized over the wire)
# ======================================================================


class TimeNormalizationDto(_CamelBase):
    """Subset of TimeRange-equivalent fields.

    Java's TimeRange / extractedTimeRanges shape is complex; we keep this
    minimal placeholder (originalText + normalizedRange) to satisfy byte-shape
    when Java sends a TimeRange list. Spec §6.2 — this can be expanded later
    when the matcher actually consumes time ranges.
    """

    originalText: Optional[str] = None
    normalizedRange: Optional[str] = None


class PreprocessedQueryDto(_CamelBase):
    """Mirror of dto/ai/PreprocessedQuery.java — subset of frequently-used fields.

    Note: Java source has ~20 fields (originalInput, normalizedText,
    rewrittenText, finalQuery, extractedTimeRanges, primaryTimeRange,
    originalTimeExpressions, foundColloquials, standardizedExpressions,
    resolvedReferences, unresolvedReferences, qualityScore, qualityAssessment,
    llmRewriteTriggered, rewriteChanges, rewriteAssumptions, rewriteConfidence,
    processedAt, processingTimeMs, processingSteps).

    For Phase 2B-α we mirror the most commonly-serialized ones. Pydantic
    `extra='ignore'` lets us round-trip Java payloads with extra fields
    without crashing.
    """

    originalInput: Optional[str] = None
    normalizedText: Optional[str] = None
    rewrittenText: Optional[str] = None
    finalQuery: Optional[str] = None
    extractedTimeRanges: List[TimeNormalizationDto] = Field(default_factory=list)
    foundColloquials: List[str] = Field(default_factory=list)
    standardizedExpressions: List[str] = Field(default_factory=list)
    resolvedReferences: Dict[str, Any] = Field(default_factory=dict)
    unresolvedReferences: List[str] = Field(default_factory=list)
    qualityScore: float = 1.0
    llmRewriteTriggered: bool = False
    rewriteChanges: List[str] = Field(default_factory=list)
    rewriteAssumptions: List[str] = Field(default_factory=list)
    rewriteConfidence: Optional[float] = None
    processingTimeMs: Optional[int] = None


# ======================================================================
# IntentMatchResult — 19 top-level fields (matches Java exactly)
# ======================================================================


class IntentMatchResultDto(_CamelBase):
    """Mirror of dto/intent/IntentMatchResult.java.

    Field count: 19 (verified by grep on Java source).
    Order matches Java declaration order so JSON keys (when serialized via
    Pydantic's model_dump) ship in the same order as Jackson's default for
    the Java POJO — important for golden-file byte comparison.
    """

    # 1
    bestMatch: Optional[AIIntentConfigDto] = None
    # 2
    topCandidates: List[CandidateIntentDto] = Field(default_factory=list)
    # 3
    confidence: Optional[float] = None
    # 4
    matchMethod: Optional[MatchMethod] = None
    # 5
    matchedKeywords: List[str] = Field(default_factory=list)
    # 6
    isStrongSignal: Optional[bool] = None
    # 7
    requiresConfirmation: Optional[bool] = None
    # 8
    clarificationQuestion: Optional[str] = None
    # 9
    userInput: Optional[str] = None
    # 10
    actionType: Optional[ActionType] = None
    # 11
    questionType: Optional[QuestionType] = None
    # 12
    targetEntity: Optional[str] = None
    # 13
    sessionId: Optional[str] = None
    # 14
    conversationMessage: Optional[str] = None
    # 15
    isMultiIntent: Optional[bool] = None
    # 16
    additionalIntents: Optional[List[IntentMatchInner]] = None
    # 17
    executionStrategy: Optional[ExecutionStrategy] = None
    # 18 — Map<String, Long> in Java; Long → int in Python (JSON has no Long)
    timingMs: Optional[Dict[str, int]] = None
    # 19
    preprocessedQuery: Optional[PreprocessedQueryDto] = None

    # ----- Static factory methods (mirror Java IntentMatchResult.empty(...)) -----

    @classmethod
    def empty(
        cls,
        userInput: str,
        actionType: ActionType = ActionType.UNKNOWN,
        targetEntity: Optional[str] = None,
    ) -> "IntentMatchResultDto":
        """Mirror of Java IntentMatchResult.empty(userInput[, actionType[, targetEntity]]).

        Builds a "no match" result with sensible defaults that match Java's
        builder output exactly.
        """
        return cls(
            bestMatch=None,
            topCandidates=[],
            confidence=0.0,
            matchMethod=MatchMethod.NONE,
            matchedKeywords=[],
            isStrongSignal=False,
            requiresConfirmation=False,
            userInput=userInput,
            actionType=actionType if actionType is not None else ActionType.UNKNOWN,
            targetEntity=targetEntity,
        )

    # ----- Instance helpers (mirror Java instance methods) -----

    def has_match(self) -> bool:
        """Mirror of Java hasMatch(): true if bestMatch present and confidence > 0."""
        return self.bestMatch is not None and (self.confidence or 0) > 0

    def needs_llm_fallback(self) -> bool:
        """Mirror of Java needsLlmFallback(): no match or confidence < 0.3."""
        return self.bestMatch is None or (self.confidence or 0) < 0.3

    def needs_candidate_selection(self) -> bool:
        """Mirror of Java needsCandidateSelection(): top1-top2 confidence gap < 0.2."""
        if not self.topCandidates or len(self.topCandidates) < 2:
            return False
        c1 = self.topCandidates[0].confidence or 0
        c2 = self.topCandidates[1].confidence or 0
        return (c1 - c2) < 0.2


# ======================================================================
# Request / response envelopes
# ======================================================================


class IntentMatchOptions(_CamelBase):
    """Per-request options aligned with Java client / plan §5.3.

    The fields here are functional knobs the Java caller controls (timeout,
    LLM gating, config-version pinning). Matcher-internal tuning
    (enableSemantic / topK / etc) is Python's own concern and not exposed
    on the wire.
    """

    enableLlmFallback: bool = True
    timeoutMs: int = 30000
    minConfidence: float = 0.70
    intentConfigVersion: Optional[int] = None


class IntentMatchRequest(_CamelBase):
    """POST /api/ai/intent/match request DTO.

    Per plan §5.3: factoryId / userId / role / businessType / query are REQUIRED
    context so the matcher can scope to the correct factory + business type.
    `history` carries recent conversation turns for stage 8 LLM context.
    `options` holds optional knobs (defaults applied if absent).

    `username` is OPTIONAL — it is metadata used in stage 8 LLM prompt for
    logging/personalization only, not load-bearing. Java's
    `AIIntentServiceImpl.recognizeIntentWithConfidence` does not have access to
    a username argument and builds the request with `username=null`; making
    this Optional avoids a Pydantic 422 on every real wire call.

    Type alignment with Java:
    - userId: str (Java JWT claim is stringified per project standard,
      Java DTO declares String)
    """

    query: str
    factoryId: str
    userId: str
    username: Optional[str] = None
    role: str
    businessType: str
    history: List[Dict[str, str]] = Field(default_factory=list)
    options: IntentMatchOptions = Field(default_factory=IntentMatchOptions)
    # P1 读写分块 (2026-07-23): 目录过滤参数。mode=READ 剔除写意图;
    # OPERATE+userPermissions 剔除无权限写意图; None=老客户端不过滤。
    mode: Optional[str] = None
    userPermissions: Optional[List[str]] = None


T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    """Standard project envelope: { success, data, message, code? }.

    Mirrors the project's `.claude/rules/api-response-handling.md` shape so
    Java can deserialize Python responses with its existing ApiResponse<T>
    wrapper class.
    """

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    success: bool
    data: Optional[T] = None
    message: Optional[str] = None
    code: Optional[str] = None

    @classmethod
    def ok(cls, data: T, message: str = "操作成功") -> "ApiResponse[T]":
        return cls(success=True, data=data, message=message)

    @classmethod
    def err(
        cls, message: str, code: Optional[str] = None
    ) -> "ApiResponse[Any]":
        return cls(success=False, data=None, message=message, code=code)


__all__ = [
    "MatchMethod",
    "ActionType",
    "QuestionType",
    "ExecutionStrategy",
    "AIIntentConfigDto",
    "CandidateIntentDto",
    "IntentMatchInner",
    "TimeNormalizationDto",
    "PreprocessedQueryDto",
    "IntentMatchResultDto",
    "IntentMatchOptions",
    "IntentMatchRequest",
    "ApiResponse",
]
