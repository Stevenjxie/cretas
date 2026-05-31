"""
AI Consultant for Factory Onboarding Wizard

Multi-turn conversational AI that acts as a senior implementation consultant,
guiding factories through requirement gathering for the Cretas AI Agent.
"""
import json
import logging
import re
from typing import Any, Dict, List

from pydantic import BaseModel

logger = logging.getLogger(__name__)


# ============================================================================
# Data Models
# ============================================================================

class ChatResponse(BaseModel):
    message: str
    suggested_replies: List[str] = []
    module_complete: bool = False
    module_summary: str = ""
    topics_covered: List[str] = []
    topics_remaining: List[str] = []


class AssessmentItem(BaseModel):
    section: str
    rowIndex: int
    fieldName: str
    applicable: bool
    confidence: float
    inputMode: str = "manual"
    reasoning: str = ""


class AssessmentResult(BaseModel):
    summary: str
    confidence: float
    items: List[AssessmentItem]
    moduleInsights: Dict[str, str] = {}
    formSchemas: Dict[str, Any] = {}
    moduleConfigs: Dict[str, Any] = {}
    stageTemplates: List[Dict[str, Any]] = []
    alertThresholds: List[Dict[str, Any]] = []
    factoryMetadata: Dict[str, str] = {}


class FactoryMetadata(BaseModel):
    industryCode: str
    regionCode: str


# ============================================================================
# Module Definitions (topics to cover per module)
# ============================================================================

MODULE_TOPICS = {
    "m0": {
        "name": "人效数据采集",
        "topics": ["工人身份识别方式", "工位分配方式", "产出计量方式", "班次安排"],
    },
    "m1": {
        "name": "基础数据管理",
        "topics": ["原材料管理", "产品目录", "供应商管理", "客户管理", "设备台账"],
    },
    "m2": {
        "name": "生产管理",
        "topics": ["生产工序流程", "报工方式和字段", "领人/签到方式", "工时统计维度", "设备数据采集", "质检流程", "分析需求和指标", "预警需求"],
    },
    "m3": {
        "name": "人员管理",
        "topics": ["考勤方式", "工时统计", "薪酬计算", "排班管理"],
    },
    "m4": {
        "name": "仓储物流",
        "topics": ["入库流程", "出库流程", "库存管理", "溯源标签", "温控需求"],
    },
    "m5": {
        "name": "其他管理",
        "topics": ["废弃处置", "环保监控", "能耗管理"],
    },
}

# Industry mapping for metadata inference
INDUSTRY_CODES = {
    "肉类加工": "MEAT",
    "水产加工": "SEAFOOD",
    "乳制品": "DAIRY",
    "烘焙食品": "BAKERY",
    "果蔬加工": "PRODUCE",
    "饮料": "BEVERAGE",
    "调味品": "CONDIMENT",
    "冷冻食品": "FROZEN",
    "休闲食品": "SNACK",
    "粮油加工": "GRAIN",
    "其他": "OTHER",
}


# ============================================================================
# LLM Helper
# ============================================================================

async def _call_llm(
    messages: List[Dict[str, str]],
    max_tokens: int = 800,
    temperature: float = 0.5
) -> str:
    """Call LLM via免费 fallback 链 (call_chain SLOT.CHAT) with full message list."""
    from common.llm_router import call_chain, SLOT
    from common.llm_metrics import llm_caller_context

    payload = {
        # model 由 call_chain(SLOT.CHAT) 按免费链注入
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "enable_thinking": False,
    }

    with llm_caller_context("ai_consultant"):
        data = await call_chain(SLOT.CHAT, payload, timeout=60.0)
    return data["choices"][0]["message"]["content"]


def _extract_json(text: str) -> Any:
    """Extract JSON from LLM response, handling markdown code blocks."""
    stripped = text.strip()
    # Try to find JSON within code blocks
    match = re.search(r'```(?:json)?\s*\n?(.*?)\n?```', stripped, re.DOTALL)
    if match:
        stripped = match.group(1).strip()
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        # Try to find the first { or [ and parse from there
        for i, ch in enumerate(stripped):
            if ch in ('{', '['):
                try:
                    return json.loads(stripped[i:])
                except json.JSONDecodeError:
                    continue
        raise ValueError(f"Could not parse JSON from LLM response: {stripped[:200]}")


# ============================================================================
# Fallback Questions (when LLM is unavailable)
# ============================================================================

FALLBACK_QUESTIONS = {
    "m0": [
        {"q": "工人是否有工号或工牌？", "replies": ["有工号", "有工牌", "没有"]},
        {"q": "产出数据如何计量？", "replies": ["称重", "计件", "按批次"]},
    ],
    "m1": [
        {"q": "原材料采购时是否记录供应商和批次？", "replies": ["是", "只记录供应商", "不记录"]},
        {"q": "是否有产品目录管理？", "replies": ["有完整目录", "有简单分类", "没有"]},
    ],
    "m2": [
        {"q": "生产批次如何记录？", "replies": ["纸质记录", "ERP系统", "扫码记录"]},
        {"q": "每批记录哪些数据？", "replies": ["产量+工时", "产量+良品+工时", "只记产量"]},
        {"q": "是否有设备运行数据采集？", "replies": ["有系统化采集", "手动记录", "没有"]},
        {"q": "质检方式是怎样的？", "replies": ["每批次检验", "抽检", "没有质检流程"]},
        {"q": "最关注的分析指标是？", "replies": ["良品率", "人工成本", "设备效率", "全部都要"]},
    ],
    "m3": [
        {"q": "考勤方式是什么？", "replies": ["打卡机", "人脸识别", "纸质签到"]},
        {"q": "工时如何统计？", "replies": ["按班次汇总", "按人按天", "不统计"]},
    ],
    "m4": [
        {"q": "入库流程是怎样的？", "replies": ["手工登记", "扫码入库", "ERP系统"]},
        {"q": "是否需要温控监控？", "replies": ["需要冷链温控", "需要常温监控", "不需要"]},
        {"q": "出库流程是怎样的？", "replies": ["手工记录", "扫码出库", "系统管理"]},
    ],
    "m5": [
        {"q": "是否有废弃物处理记录需求？", "replies": ["是", "否"]},
        {"q": "是否需要能耗监控？", "replies": ["是", "否"]},
    ],
}


# ============================================================================
# AIConsultant Class
# ============================================================================

class AIConsultant:
    """AI conversational factory requirement consultant."""

    def _build_system_prompt(
        self,
        module_id: str,
        basic_info: dict,
        selected_modules: list,
        prior_summaries: dict,
    ) -> str:
        """Build system prompt for the conversation."""
        module_info = MODULE_TOPICS.get(module_id, {"name": module_id, "topics": []})
        topics_list = "\n".join(f"  - {t}" for t in module_info["topics"])

        prior_context = ""
        if prior_summaries:
            prior_context = "\n\n已了解的其他模块情况：\n"
            for mid, summary in prior_summaries.items():
                mname = MODULE_TOPICS.get(mid, {}).get("name", mid)
                prior_context += f"- {mname}: {summary}\n"

        return f"""你是白垩纪AI Agent的资深实施顾问。你正在通过对话了解一家工厂对"{module_info['name']}"模块的具体需求。

工厂基本信息：
- 名称：{basic_info.get('factoryName', '未知')}
- 行业：{basic_info.get('industry', '未知')}
- 规模：{basic_info.get('scale', '未知')}
- 主要产品：{basic_info.get('products', '未知')}
- 简介：{basic_info.get('description', '')}

选择的功能模块：{', '.join(selected_modules)}
{prior_context}

当前对话模块：{module_info['name']}

需要了解的话题：
{topics_list}

对话准则：
1. 每次只问一个主题，不要同时问太多
2. 根据用户的回答追问细节（比如用户说"有质检"，追问频率、指标、方式）
3. 用户说"没有"或"不需要"时不要强推，记录下来继续下一个话题
4. 用日常用语交流，不用技术术语
5. 适当结合工厂的行业特点提问（如肉类加工关注温控、微生物检测）

你的每次回复必须严格按照以下 JSON 格式返回（不要包含其他内容）：
{{
  "message": "你的回复文本",
  "suggestedReplies": ["选项1", "选项2", "选项3"],
  "moduleComplete": false,
  "moduleSummary": "",
  "topicsCovered": ["已覆盖的话题1"],
  "topicsRemaining": ["未覆盖的话题1"]
}}

当以下话题都已充分覆盖时，设置 moduleComplete 为 true，并在 moduleSummary 中总结该模块的关键发现：
{topics_list}

注意：moduleSummary 应包含具体信息（如"纸质报工，记录产量+工时+良品，无设备数据"），而非泛泛而谈。"""

    async def start_module_chat(
        self,
        session_id: str,
        module_id: str,
        basic_info: dict,
        selected_modules: list,
        prior_modules_summary: dict,
    ) -> ChatResponse:
        """Start conversation for a module. AI sends the first message."""
        system_prompt = self._build_system_prompt(
            module_id, basic_info, selected_modules, prior_modules_summary
        )

        module_info = MODULE_TOPICS.get(module_id, {"name": module_id, "topics": []})

        try:
            messages = [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": f"请开始了解我们工厂在{module_info['name']}方面的情况。"},
            ]
            raw = await _call_llm(messages, max_tokens=800, temperature=0.5)
            parsed = _extract_json(raw)
            return ChatResponse(
                message=parsed.get("message", ""),
                suggested_replies=parsed.get("suggestedReplies", []),
                module_complete=parsed.get("moduleComplete", False),
                module_summary=parsed.get("moduleSummary", ""),
                topics_covered=parsed.get("topicsCovered", []),
                topics_remaining=parsed.get("topicsRemaining", module_info["topics"]),
            )
        except Exception as e:
            logger.warning(f"LLM failed for start_module_chat: {e}, falling back")
            return self._fallback_first_question(module_id)

    async def chat(
        self,
        session_id: str,
        module_id: str,
        user_message: str,
        conversation_history: list,
        basic_info: dict,
        selected_modules: list,
        prior_modules_summary: dict,
    ) -> ChatResponse:
        """Multi-turn conversation core."""
        system_prompt = self._build_system_prompt(
            module_id, basic_info, selected_modules, prior_modules_summary
        )

        # Build message list: system + conversation history + new user message
        messages = [{"role": "system", "content": system_prompt}]

        # Truncate history to last 20 turns to stay within token limits
        history = conversation_history[-20:] if len(conversation_history) > 20 else conversation_history
        for msg in history:
            messages.append({"role": msg["role"], "content": msg["content"]})
        messages.append({"role": "user", "content": user_message})

        try:
            raw = await _call_llm(messages, max_tokens=800, temperature=0.5)
            parsed = _extract_json(raw)
            return ChatResponse(
                message=parsed.get("message", ""),
                suggested_replies=parsed.get("suggestedReplies", []),
                module_complete=parsed.get("moduleComplete", False),
                module_summary=parsed.get("moduleSummary", ""),
                topics_covered=parsed.get("topicsCovered", []),
                topics_remaining=parsed.get("topicsRemaining", []),
            )
        except Exception as e:
            logger.warning(f"LLM failed for chat: {e}, falling back")
            return self._fallback_next_question(module_id, len(conversation_history))

    async def assess_all_fields(
        self,
        basic_info: dict,
        selected_modules: list,
        all_conversations: dict,
    ) -> AssessmentResult:
        """Generate full assessment based on all conversations."""
        # Build conversation summaries for context
        conv_text = ""
        for module_id, history in all_conversations.items():
            module_name = MODULE_TOPICS.get(module_id, {}).get("name", module_id)
            conv_text += f"\n\n=== {module_name} ({module_id}) 对话记录 ===\n"
            for msg in history:
                role = "AI" if msg["role"] == "assistant" else "工厂"
                conv_text += f"{role}: {msg['content']}\n"

        system_prompt = """你是白垩纪AI Agent的AI配置专家。基于与工厂的对话记录，你需要生成完整的工厂配置方案。

你必须返回严格的 JSON 格式（不要包含其他内容），包含以下字段：

{
  "summary": "一句话总结",
  "confidence": 0.85,
  "items": [
    {
      "section": "模块section ID",
      "rowIndex": 0,
      "fieldName": "字段名",
      "applicable": true,
      "confidence": 0.9,
      "inputMode": "manual|auto|scan|disabled",
      "reasoning": "理由"
    }
  ],
  "moduleInsights": {
    "m2": "该模块的关键发现总结"
  },
  "formSchemas": {
    "PROCESSING_BATCH": {
      "type": "object",
      "properties": {
        "actualQuantity": {"type": "number", "title": "产出数量", "required": true, "x-component": "NumberInput"},
        "goodQuantity": {"type": "number", "title": "良品数量", "x-component": "NumberInput"}
      }
    }
  },
  "moduleConfigs": {
    "m2": {
      "analysisDimensions": ["yield_rate", "production_cost"],
      "disabledDimensions": ["oee"],
      "benchmarks": {
        "yield_rate": {"excellent": 98, "qualified": 95, "warning": 90}
      },
      "quickActions": ["BatchList", "ScanReport"],
      "quickActionOrder": ["BatchList", "ScanReport"],
      "disabledScreens": ["EquipmentMonitoring"],
      "disabledReports": ["equipment"],
      "priority": 8
    }
  },
  "stageTemplates": [
    {"stageName": "RECEIVING", "displayName": "收货", "order": 1, "isKey": false}
  ],
  "alertThresholds": [
    {"metric": "YIELD_DROP", "threshold": 95, "severity": "WARNING"}
  ],
  "factoryMetadata": {"industryCode": "MEAT", "regionCode": "3101"}
}

重要规则：
1. items 数组应包含所有 243 个字段的评估（适用/不适用）
2. formSchemas 为每个需要动态表单的 entity type 生成 Formily JSON Schema
3. moduleConfigs 为每个启用的模块生成运行时配置
4. stageTemplates 如果对话中提到了生产工序，按顺序列出
5. alertThresholds 根据对话中提到的预警需求生成
6. factoryMetadata 从行业和地区推断代码
7. 对话中工厂说"不需要"或"没有"的功能，对应字段 applicable=false
8. 对话中未明确提及的字段，根据行业常识推断（confidence 较低）
9. priority: 1-10 的数字，表示该模块对工厂的重要程度。根据对话中的使用频率和重视程度推断
10. quickActionOrder: 按对话中提到的使用频率排列快捷操作
11. disabledScreens 必须使用以下标准名称之一：EquipmentMonitoring, TempMonitoring, AlertHandling, OutboundManagement, InboundManagement, AttendanceManagement, WhitelistManagement, NewHireTracking, QualityInspection, QualityAnalysis, ScheduleManagement, BatchManagement, WorkerManagement, AISchedule, AIAnalysis, SmartBI, Reports, ProductionPlanning, PersonnelManagement"""  # noqa: E501

        user_prompt = f"""工厂基本信息：
- 名称：{basic_info.get('factoryName', '未知')}
- 行业：{basic_info.get('industry', '未知')}
- 规模：{basic_info.get('scale', '未知')}
- 主要产品：{basic_info.get('products', '未知')}
- 描述：{basic_info.get('description', '')}

选择的功能模块：{', '.join(selected_modules)}

{conv_text}

请基于以上对话记录，生成完整的配置方案。"""

        try:
            messages = [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ]
            raw = await _call_llm(messages, max_tokens=4000, temperature=0.2)
            parsed = _extract_json(raw)

            return AssessmentResult(
                summary=parsed.get("summary", ""),
                confidence=parsed.get("confidence", 0.7),
                items=[AssessmentItem(**item) for item in parsed.get("items", [])],
                moduleInsights=parsed.get("moduleInsights", {}),
                formSchemas=parsed.get("formSchemas", {}),
                moduleConfigs=parsed.get("moduleConfigs", {}),
                stageTemplates=parsed.get("stageTemplates", []),
                alertThresholds=parsed.get("alertThresholds", []),
                factoryMetadata=parsed.get("factoryMetadata", {}),
            )
        except Exception as e:
            logger.error(f"Assessment LLM failed: {e}")
            return self._fallback_assessment(basic_info, selected_modules)

    async def infer_factory_metadata(self, basic_info: dict) -> FactoryMetadata:
        """Infer industryCode and regionCode from basic info."""
        industry = basic_info.get("industry", "其他")
        industry_code = INDUSTRY_CODES.get(industry, "OTHER")
        # Default to Shanghai
        region_code = "3101"
        return FactoryMetadata(industryCode=industry_code, regionCode=region_code)

    # ========== Fallback methods (when LLM is unavailable) ==========

    def _fallback_first_question(self, module_id: str) -> ChatResponse:
        """Return first structured question when LLM is down."""
        questions = FALLBACK_QUESTIONS.get(module_id, [])
        if not questions:
            return ChatResponse(
                message="请描述一下贵工厂在这个方面的基本情况。",
                suggested_replies=["有完善流程", "有基本流程", "暂时没有"],
                module_complete=False,
                topics_remaining=MODULE_TOPICS.get(module_id, {}).get("topics", []),
            )
        q = questions[0]
        return ChatResponse(
            message=q["q"],
            suggested_replies=q["replies"],
            module_complete=False,
            topics_remaining=MODULE_TOPICS.get(module_id, {}).get("topics", []),
        )

    def _fallback_next_question(self, module_id: str, turn_count: int) -> ChatResponse:
        """Return next structured question based on turn count."""
        questions = FALLBACK_QUESTIONS.get(module_id, [])
        idx = turn_count // 2  # Each turn = user + assistant
        if idx >= len(questions):
            topics = MODULE_TOPICS.get(module_id, {}).get("topics", [])
            return ChatResponse(
                message="好的，关于这个模块我已经有了基本了解。",
                suggested_replies=[],
                module_complete=True,
                module_summary="通过结构化问卷收集（LLM不可用时的备选方案）",
                topics_covered=topics,
                topics_remaining=[],
            )
        q = questions[idx]
        return ChatResponse(
            message=q["q"],
            suggested_replies=q["replies"],
            module_complete=False,
        )

    def _fallback_assessment(
        self, basic_info: dict, selected_modules: list
    ) -> AssessmentResult:
        """Generate basic assessment without LLM."""
        industry = basic_info.get("industry", "其他")
        industry_code = INDUSTRY_CODES.get(industry, "OTHER")

        return AssessmentResult(
            summary=f"{basic_info.get('factoryName', '未知工厂')}的基础配置方案（自动生成）",
            confidence=0.5,
            items=[],
            moduleInsights={mid: "自动生成（LLM不可用）" for mid in selected_modules},
            formSchemas={
                "PROCESSING_BATCH": {
                    "type": "object",
                    "properties": {
                        "actualQuantity": {"type": "number", "title": "产出数量", "required": True, "x-component": "NumberInput"},  # noqa: E501
                        "goodQuantity": {"type": "number", "title": "良品数量", "x-component": "NumberInput"},
                        "defectQuantity": {"type": "number", "title": "次品数量", "x-component": "NumberInput"},
                        "notes": {"type": "string", "title": "备注", "x-component": "Input.TextArea"},
                    },
                }
            },
            moduleConfigs={},
            stageTemplates=[],
            alertThresholds=[],
            factoryMetadata={"industryCode": industry_code, "regionCode": "3101"},
        )
