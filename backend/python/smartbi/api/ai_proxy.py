from __future__ import annotations
"""
AI Proxy Stub Routes

Provides stub endpoints for /api/ai/* that Java backend calls.
These endpoints use DashScope LLM to handle:
- Rule parsing
- State machine parsing
- Intent classification/clarification
- Form schema generation
- Factory batch initialization

Java backend calls these via RestTemplate at http://localhost:8083/api/ai/...
"""
import hashlib
import logging
import time
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field

from smartbi.config import get_settings

logger = logging.getLogger(__name__)
router = APIRouter(tags=["AI Proxy"])


# ============================================================================
# Application-level Result Cache (LRU + TTL)
# ============================================================================

class AiProxyCache:
    """
    LRU + TTL application-level result cache for AI proxy endpoints.

    Caches LLM response content keyed by MD5(endpoint_name + prompt_text).
    This supplements (not replaces) DashScope's prompt-level ephemeral cache.
    """

    def __init__(self, ttl_seconds: int = 1800, max_entries: int = 500):
        self._cache: Dict[str, Dict[str, Any]] = {}
        self._ttl_seconds = ttl_seconds
        self._max_entries = max_entries

    @staticmethod
    def make_key(*parts: str) -> str:
        """Generate cache key from arbitrary string parts."""
        raw = "".join(parts)
        return hashlib.md5(raw.encode("utf-8")).hexdigest()[:20]

    def get(self, key: str) -> Optional[str]:
        """Return cached LLM content if present and not expired, else None."""
        entry = self._cache.get(key)
        if entry is None:
            return None

        if time.time() - entry["created_at"] > self._ttl_seconds:
            del self._cache[key]
            return None

        # LRU touch
        entry["accessed_at"] = time.time()
        entry["access_count"] += 1
        logger.info("[AiProxyCache] HIT  key=%s  hits=%d", key, entry["access_count"])
        return entry["content"]

    def set(self, key: str, content: str) -> None:
        """Store LLM content in cache, evicting LRU entries if needed."""
        now = time.time()
        self._cache[key] = {
            "content": content,
            "created_at": now,
            "accessed_at": now,
            "access_count": 1,
        }

        if len(self._cache) > self._max_entries:
            self._evict()

    def _evict(self) -> None:
        """Remove oldest-accessed 10 % of entries."""
        if len(self._cache) <= self._max_entries:
            return
        sorted_keys = sorted(
            self._cache.keys(),
            key=lambda k: self._cache[k]["accessed_at"],
        )
        to_remove = max(1, len(sorted_keys) // 10)
        for k in sorted_keys[:to_remove]:
            del self._cache[k]
        logger.debug("[AiProxyCache] evicted %d entries", to_remove)

    def get_stats(self) -> Dict[str, Any]:
        now = time.time()
        active = [e for e in self._cache.values()
                  if now - e["created_at"] < self._ttl_seconds]
        return {
            "total_entries": len(self._cache),
            "active_entries": len(active),
            "total_hits": sum(e["access_count"] - 1 for e in self._cache.values()),
            "ttl_seconds": self._ttl_seconds,
            "max_entries": self._max_entries,
        }


# Module-level singleton
_ai_proxy_cache = AiProxyCache(ttl_seconds=1800, max_entries=500)


# ============================================================================
# Request/Response Models
# ============================================================================

class RuleParseRequest(BaseModel):
    rule_text: str = Field(..., description="Natural language rule description")
    rule_type: Optional[str] = Field(None, description="Rule type hint")
    context: Optional[Dict[str, Any]] = Field(default=None)


class StateMachineParseRequest(BaseModel):
    description: str = Field(..., description="State machine description")
    states: Optional[List[str]] = Field(default=None)
    context: Optional[Dict[str, Any]] = Field(default=None)


class IntentClassifyRequest(BaseModel):
    text: str = Field(..., description="User input text")
    candidates: Optional[List[str]] = Field(default=None, description="Candidate intents")
    context: Optional[Dict[str, Any]] = Field(default=None)


class IntentClarifyRequest(BaseModel):
    text: str = Field(..., description="User input text")
    current_intent: Optional[str] = Field(None)
    ambiguous_intents: Optional[List[str]] = Field(default=None)
    context: Optional[Dict[str, Any]] = Field(default=None)


class DataOperationParseRequest(BaseModel):
    text: str = Field(..., description="User input describing data operation")
    available_entities: Optional[List[str]] = Field(default=None)
    context: Optional[Dict[str, Any]] = Field(default=None)


class FormSchemaRequest(BaseModel):
    description: str = Field(..., description="Form description")
    entity_type: Optional[str] = Field(None)
    fields_hint: Optional[List[str]] = Field(default=None)
    context: Optional[Dict[str, Any]] = Field(default=None)


class FactoryInitRequest(BaseModel):
    factory_description: str = Field(..., description="Factory description")
    factory_name: Optional[str] = Field(None)
    industry_hint: Optional[str] = Field(None)
    include_business_data: bool = Field(default=True)


# ============================================================================
# LLM Helper
# ============================================================================

async def _call_llm(
    system_prompt: str,
    user_prompt: str,
    max_tokens: int = 2000,
    cache_key: Optional[str] = None,
    model: Optional[str] = None,
) -> str:
    """Call DashScope LLM API with optional application-level result cache.

    Args:
        system_prompt: System message for the LLM.
        user_prompt: User message for the LLM.
        max_tokens: Maximum tokens in the response.
        cache_key: If provided, check/store result in AiProxyCache.
        model: Model name to use. Defaults to settings.llm_model if not provided.
    """
    # --- Application-level result cache lookup ---
    if cache_key is not None:
        cached_content = _ai_proxy_cache.get(cache_key)
        if cached_content is not None:
            return cached_content

    # 走 call_chain(SLOT.CHAT): 免费 fallback 链 + 熔断。model 由 router 按 slot 注入,
    # 此处不再用 settings.llm_model / fast_model (调用方传入的 model 参数已忽略)。
    from common.llm_router import call_chain, SLOT
    from common.llm_metrics import llm_caller_context

    payload = {
        # model 由 call_chain(SLOT.CHAT) 按免费链注入
        "messages": [
            {
                "role": "system",
                "content": [
                    {
                        "type": "text",
                        "text": system_prompt,
                        "cache_control": {"type": "ephemeral"}
                    }
                ]
            },
            {"role": "user", "content": user_prompt}
        ],
        "temperature": 0.3,
        "max_tokens": max_tokens,
        "enable_thinking": False
    }

    with llm_caller_context("ai_proxy"):
        data = await call_chain(SLOT.CHAT, payload, timeout=30.0)

    # Log context cache stats
    usage = data.get("usage", {})
    cached = (usage.get("prompt_tokens_details") or {}).get("cached_tokens", 0)
    if cached > 0:
        logger.info(f"[ContextCache] HIT cached={cached}/{usage.get('prompt_tokens', 0)} tokens")

    content = data["choices"][0]["message"]["content"]

    # --- Store in application-level result cache ---
    if cache_key is not None:
        _ai_proxy_cache.set(cache_key, content)
        logger.info("[AiProxyCache] STORE key=%s len=%d", cache_key, len(content))

    return content


# ============================================================================
# Endpoints
# ============================================================================

@router.post("/rule/parse")
async def parse_rule(request: RuleParseRequest):
    """Parse natural language rule into structured format"""
    start = time.time()
    try:
        settings = get_settings()
        ck = AiProxyCache.make_key("rule_parse", request.rule_text)
        result = await _call_llm(
            system_prompt="你是规则解析引擎。将自然语言业务规则转换为结构化的 JSON 条件-动作规则。输出纯JSON。",
            user_prompt=f"请将以下业务规则解析为结构化JSON格式：\n\n{request.rule_text}\n\n规则类型提示：{request.rule_type or '自动检测'}",
            cache_key=ck,
            model=settings.llm_model,
        )
        return {
            "success": True,
            "data": {"parsed_rule": result, "rule_type": request.rule_type},
            "elapsed_ms": int((time.time() - start) * 1000)
        }
    except Exception as e:
        logger.error(f"Rule parse error: {e}", exc_info=True)
        return {"success": False, "message": "处理失败，请稍后重试", "data": None}


@router.post("/state-machine/parse")
async def parse_state_machine(request: StateMachineParseRequest):
    """Parse state machine description into structured format"""
    start = time.time()
    try:
        settings = get_settings()
        ck = AiProxyCache.make_key("state_machine", request.description)
        result = await _call_llm(
            system_prompt="你是状态机设计专家。将自然语言描述转换为状态机定义（状态、转换、条件）。输出纯JSON。",
            user_prompt=f"请将以下描述解析为状态机JSON格式：\n\n{request.description}\n\n已知状态：{request.states or '自动检测'}",
            cache_key=ck,
            model=settings.llm_model,
        )
        return {
            "success": True,
            "data": {"state_machine": result},
            "elapsed_ms": int((time.time() - start) * 1000)
        }
    except Exception as e:
        logger.error(f"State machine parse error: {e}", exc_info=True)
        return {"success": False, "message": "处理失败，请稍后重试", "data": None}


@router.get("/rule/health")
async def rule_health():
    """Health check for rule parsing service"""
    settings = get_settings()
    return {
        "status": "healthy" if settings.llm_api_key else "degraded",
        "service": "ai-rule",
        "llm_configured": bool(settings.llm_api_key),
        "model": settings.llm_model
    }


@router.post("/intent/classify")
async def classify_intent(raw_request: Request):
    """Classify user intent using LLM. Accepts both {text} and {user_input} formats."""
    start = time.time()
    try:
        body = await raw_request.json()
        # Map Java field names: user_input → text, available_intents → candidates
        text = body.get("text") or body.get("user_input") or ""
        if not text:
            return {"success": False, "message": "text or user_input is required", "data": None}
        candidates = body.get("candidates")
        if not candidates and body.get("available_intents"):
            candidates = [
                f"{i.get('intent_code', '')}: {i.get('intent_name', '')} - {i.get('description', '')}"
                for i in body["available_intents"]
            ]
        candidates_str = "\n".join(f"- {c}" for c in candidates) if candidates else "自动检测所有可能意图"
        settings = get_settings()
        ck = AiProxyCache.make_key("intent_classify", text, candidates_str)
        result = await _call_llm(
            system_prompt="你是意图分类器。根据用户输入判断最匹配的意图。返回JSON：{\"intent\": \"意图代码\", \"confidence\": 0.95, \"reasoning\": \"理由\"}",  # noqa: E501
            user_prompt=f"用户输入：{text}\n\n候选意图：\n{candidates_str}",
            cache_key=ck,
            model=settings.llm_fast_model,
        )
        return {
            "success": True,
            "data": {"classification": result},
            "elapsed_ms": int((time.time() - start) * 1000)
        }
    except Exception as e:
        logger.error(f"Intent classify error: {e}", exc_info=True)
        return {"success": False, "message": "处理失败，请稍后重试", "data": None}


@router.post("/intent/clarify")
async def clarify_intent(request: IntentClarifyRequest):
    """Generate clarification question for ambiguous intent"""
    start = time.time()
    try:
        ambiguous_str = str(request.ambiguous_intents or [])
        settings = get_settings()
        ck = AiProxyCache.make_key("intent_clarify", request.text, ambiguous_str)
        result = await _call_llm(
            system_prompt="你是对话助手。当用户意图不明确时，生成一个澄清问题帮助确认意图。返回JSON：{\"question\": \"澄清问题\", \"options\": [\"选项1\", \"选项2\"]}",  # noqa: E501
            user_prompt=f"用户输入：{request.text}\n\n当前识别意图：{request.current_intent or '未识别'}\n模糊意图列表：{request.ambiguous_intents or []}",  # noqa: E501
            cache_key=ck,
            model=settings.llm_fast_model,
        )
        return {
            "success": True,
            "data": {"clarification": result},
            "elapsed_ms": int((time.time() - start) * 1000)
        }
    except Exception as e:
        logger.error(f"Intent clarify error: {e}", exc_info=True)
        return {"success": False, "message": "处理失败，请稍后重试", "data": None}


@router.get("/intent/health")
async def intent_health():
    """Health check for intent classification service"""
    settings = get_settings()
    return {
        "status": "healthy" if settings.llm_api_key else "degraded",
        "service": "ai-intent",
        "llm_configured": bool(settings.llm_api_key),
        "model": settings.llm_model
    }


@router.post("/intent/parse-data-operation")
async def parse_data_operation(request: DataOperationParseRequest):
    """Parse user input into data operation intent"""
    start = time.time()
    try:
        entities_str = ", ".join(request.available_entities) if request.available_entities else "自动检测"
        settings = get_settings()
        ck = AiProxyCache.make_key("data_op", request.text)
        result = await _call_llm(
            system_prompt="你是数据操作解析器。将用户的自然语言描述转换为数据操作指令。返回JSON：{\"operation\": \"QUERY/CREATE/UPDATE/DELETE\", \"entity\": \"实体名\", \"conditions\": {}, \"fields\": []}",  # noqa: E501
            user_prompt=f"用户输入：{request.text}\n\n可用实体：{entities_str}",
            cache_key=ck,
            model=settings.llm_fast_model,
        )
        return {
            "success": True,
            "data": {"operation": result},
            "elapsed_ms": int((time.time() - start) * 1000)
        }
    except Exception as e:
        logger.error(f"Data operation parse error: {e}", exc_info=True)
        return {"success": False, "message": "处理失败，请稍后重试", "data": None}


@router.post("/form/generate-schema")
async def generate_form_schema(request: FormSchemaRequest):
    """Generate form schema from description"""
    start = time.time()
    try:
        settings = get_settings()
        ck = AiProxyCache.make_key("form_schema", request.description)
        result = await _call_llm(
            system_prompt="你是表单设计专家。根据描述生成表单Schema（字段名、类型、验证规则、标签）。返回JSON格式的表单定义。",
            user_prompt=f"请为以下场景生成表单Schema：\n\n{request.description}\n\n实体类型：{request.entity_type or '自动检测'}\n字段提示：{request.fields_hint or '自动生成'}",  # noqa: E501
            max_tokens=3000,
            cache_key=ck,
            model=settings.llm_model,
        )
        return {
            "success": True,
            "data": {"schema": result},
            "elapsed_ms": int((time.time() - start) * 1000)
        }
    except Exception as e:
        logger.error(f"Form schema generate error: {e}", exc_info=True)
        return {"success": False, "message": "处理失败，请稍后重试", "data": None}


@router.post("/factory/batch-initialize")
async def batch_initialize_factory(request: FactoryInitRequest):
    """AI-powered factory configuration initialization"""
    start = time.time()
    try:
        settings = get_settings()
        ck = AiProxyCache.make_key("factory_init", request.factory_description)
        result = await _call_llm(
            system_prompt=(
                "你是食品加工工厂配置专家。根据工厂描述生成完整的初始化配置，包括：\n"
                "1. 生产线配置\n2. 质检标准\n3. 仓储分区\n4. 人员角色\n5. 业务流程\n"
                "返回结构化JSON配置。"
            ),
            user_prompt=(
                f"工厂名称：{request.factory_name or '未知'}\n"
                f"行业提示：{request.industry_hint or '食品加工'}\n"
                f"工厂描述：{request.factory_description}\n"
                f"是否包含业务数据：{request.include_business_data}"
            ),
            max_tokens=4000,
            cache_key=ck,
            model=settings.llm_model,
        )
        return {
            "success": True,
            "data": {"configuration": result, "factory_name": request.factory_name},
            "elapsed_ms": int((time.time() - start) * 1000)
        }
    except Exception as e:
        logger.error(f"Factory batch initialize error: {e}", exc_info=True)
        return {"success": False, "message": "处理失败，请稍后重试", "data": None}
