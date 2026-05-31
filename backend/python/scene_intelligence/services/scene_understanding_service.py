"""
LLM 动态场景理解服务
基于 LLM 自动理解工厂车间场景，检测变化，并提供适应建议

功能:
- 场景自动理解（设备、工位、区域识别）
- 场景变化检测（对比分析）
- 自动适应建议
"""

import os
import json
import uuid
from datetime import datetime
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Any
import logging

logger = logging.getLogger(__name__)


@dataclass
class Equipment:
    """识别到的设备"""
    name: str
    location: str
    status: str = "unknown"
    equipment_type: str = ""


@dataclass
class Workstation:
    """识别到的工位"""
    id: str
    workstation_type: str
    occupied: bool = False
    location: str = ""


@dataclass
class Zone:
    """识别到的区域"""
    name: str
    zone_type: str
    boundaries: str = ""


@dataclass
class SceneUnderstanding:
    """场景理解结果"""
    camera_id: str
    timestamp: datetime
    scene_description: str = ""
    equipment: List[Equipment] = field(default_factory=list)
    workstations: List[Workstation] = field(default_factory=list)
    zones: List[Zone] = field(default_factory=list)
    workflow_understanding: str = ""
    reference_frame_base64: Optional[str] = None


@dataclass
class SceneChange:
    """场景变化"""
    change_id: str
    camera_id: str
    timestamp: datetime
    has_changes: bool = False
    change_summary: str = ""
    change_details: Dict[str, Any] = field(default_factory=dict)
    impact_assessment: str = ""
    suggested_actions: List[Dict] = field(default_factory=list)
    confidence: float = 0.0
    applied: bool = False


class SceneUnderstandingService:
    """基于 LLM 的动态场景理解服务"""

    def __init__(self):
        # 内存存储（生产环境应使用数据库）
        self.scene_cache: Dict[str, SceneUnderstanding] = {}
        self.change_history: Dict[str, SceneChange] = {}

        # API 配置
        self.api_key = os.getenv("LLM_API_KEY", os.getenv("DASHSCOPE_API_KEY", ""))
        self.base_url = os.getenv("LLM_VL_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
        self.model = os.getenv("VL_MODEL_DEEP_REASONING", os.getenv("LLM_VL_MODEL", "qwen3-vl-plus-2025-12-19"))

    async def _call_vl_model(
        self,
        images: List[Dict[str, str]],
        prompt: str,
        max_tokens: int = 3000
    ) -> Optional[Dict]:
        """调用 VL 模型

        Routes through the free-tier VL fallback chain (call_chain) instead of a
        direct DashScope httpx connection — gets circuit-breaker + multi-account
        free fallback. payload drops `model` (call_chain injects the SLOT.VL
        model per provider); vision messages preserved.
        """
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        try:
            content = []
            for img in images:
                if img.get("label"):
                    content.append({
                        "type": "text",
                        "text": img["label"]
                    })
                content.append({
                    "type": "image_url",
                    "image_url": {
                        "url": f"data:image/jpeg;base64,{img['base64']}"
                    }
                })

            content.append({
                "type": "text",
                "text": prompt
            })

            payload = {
                "messages": [
                    {
                        "role": "user",
                        "content": content
                    }
                ],
                "max_tokens": max_tokens,
                "temperature": 0.3,
                "enable_thinking": False,
            }
            with llm_caller_context("scene_intelligence"):
                result = await call_chain(SLOT.VL, payload)

            if "error" in result:
                logger.error(f"VL API error: {result['error']}")
                return None

            content_text = result["choices"][0]["message"]["content"]

            import re
            json_match = re.search(r'\{[\s\S]*\}', content_text)
            if json_match:
                return json.loads(json_match.group())

            return None

        except Exception as e:
            logger.error(f"VL model call error: {e}")
            return None

    async def understand_scene(
        self,
        image_base64: str,
        camera_id: str
    ) -> SceneUnderstanding:
        """
        让 LLM 动态理解当前场景

        Args:
            image_base64: Base64 编码的图片
            camera_id: 摄像头ID

        Returns:
            场景理解结果
        """
        prompt = """你是一个工厂产线分析专家。请分析这张工厂车间图片，动态识别：

请以 JSON 格式返回：
{
    "scene_description": "用一段话描述你看到的整体场景（50-100字）",

    "equipment": [
        {
            "name": "设备名称/类型",
            "location": "大致位置（画面的哪个区域）",
            "status": "运行中/停止/未知",
            "equipment_type": "设备类型（如包装机、传送带、灌装机等）"
        }
    ],

    "workstations": [
        {
            "id": "工位名称/编号（如果可见，否则用 WS01、WS02 等）",
            "type": "工位类型（加工/包装/检验/分拣等）",
            "occupied": true/false,
            "location": "位置描述"
        }
    ],

    "zones": [
        {
            "name": "区域名称",
            "type": "区域类型（生产区/通道区/暂存区/原料区/成品区等）",
            "boundaries": "边界描述"
        }
    ],

    "workflow_understanding": "基于设备和工位布局，推测的工作流程描述（如：原料从左侧进入，经过中央包装，右侧出料）",

    "notes": "其他观察"
}

分析要点：
1. 全面扫描画面中的所有设备
2. 识别工作区域和工位
3. 推断物料/产品的流动方向
4. 注意区域之间的空间关系

仅返回 JSON，不要包含其他文字。"""

        result = await self._call_vl_model(
            images=[{"base64": image_base64}],
            prompt=prompt
        )

        if result is None:
            return SceneUnderstanding(
                camera_id=camera_id,
                timestamp=datetime.now(),
                scene_description="场景分析失败"
            )

        # 解析结果
        equipment_list = [
            Equipment(
                name=e.get("name", ""),
                location=e.get("location", ""),
                status=e.get("status", "unknown"),
                equipment_type=e.get("equipment_type", "")
            )
            for e in result.get("equipment", [])
        ]

        workstation_list = [
            Workstation(
                id=w.get("id", f"WS{i+1}"),
                workstation_type=w.get("type", ""),
                occupied=w.get("occupied", False),
                location=w.get("location", "")
            )
            for i, w in enumerate(result.get("workstations", []))
        ]

        zone_list = [
            Zone(
                name=z.get("name", ""),
                zone_type=z.get("type", ""),
                boundaries=z.get("boundaries", "")
            )
            for z in result.get("zones", [])
        ]

        understanding = SceneUnderstanding(
            camera_id=camera_id,
            timestamp=datetime.now(),
            scene_description=result.get("scene_description", ""),
            equipment=equipment_list,
            workstations=workstation_list,
            zones=zone_list,
            workflow_understanding=result.get("workflow_understanding", ""),
            reference_frame_base64=image_base64
        )

        # 缓存结果
        self.scene_cache[camera_id] = understanding

        return understanding

    async def detect_changes(
        self,
        camera_id: str,
        current_frame_base64: str
    ) -> SceneChange:
        """
        让 LLM 对比分析场景变化

        Args:
            camera_id: 摄像头ID
            current_frame_base64: 当前帧 Base64

        Returns:
            变化检测结果
        """
        # 获取上次的场景理解
        previous = self.scene_cache.get(camera_id)

        if previous is None or previous.reference_frame_base64 is None:
            # 首次分析
            understanding = await self.understand_scene(current_frame_base64, camera_id)  # noqa: F841
            return SceneChange(
                change_id=f"change_{uuid.uuid4().hex[:12]}",
                camera_id=camera_id,
                timestamp=datetime.now(),
                has_changes=False,
                change_summary="首次场景分析，无历史对比",
                confidence=100
            )

        # LLM 对比分析
        prompt = f"""你是一个工厂产线变更分析专家。

**上次的场景理解**（{previous.timestamp.strftime('%Y-%m-%d %H:%M')}）：
{previous.scene_description}

设备：{json.dumps([{"name": e.name, "location": e.location, "status": e.status} for e in previous.equipment], ensure_ascii=False)}  # noqa: E501
工位：{json.dumps([{"id": w.id, "type": w.workstation_type, "location": w.location} for w in previous.workstations], ensure_ascii=False)}  # noqa: E501
区域：{json.dumps([{"name": z.name, "type": z.zone_type} for z in previous.zones], ensure_ascii=False)}

**现在请分析当前图片**，对比参考图片，回答：

请以 JSON 格式返回：
{{
    "has_changes": true/false,
    "change_summary": "一句话描述主要变化（如果无变化则写'无明显变化'）",

    "change_details": {{
        "equipment_changes": [
            {{"type": "added/removed/moved/status_changed", "equipment": "设备名", "description": "变化描述"}}
        ],
        "workstation_changes": [
            {{"type": "added/removed/relocated", "workstation": "工位ID", "description": "变化描述"}}
        ],
        "zone_changes": [
            {{"type": "expanded/shrunk/added/removed", "zone": "区域名", "description": "变化描述"}}
        ]
    }},

    "impact_assessment": "这些变化对生产监控有什么影响",

    "suggested_actions": [
        {{
            "type": "update_detection_zone/add_workstation/update_counting_rule/recalibrate/none",
            "description": "操作描述",
            "priority": "high/medium/low"
        }}
    ],

    "confidence": 0-100
}}

如果场景基本没变化，has_changes 返回 false，其他字段可以简化。

仅返回 JSON，不要包含其他文字。"""

        result = await self._call_vl_model(
            images=[
                {"base64": previous.reference_frame_base64, "label": "参考图片（之前的场景）:"},
                {"base64": current_frame_base64, "label": "当前图片（现在的场景）:"}
            ],
            prompt=prompt
        )

        if result is None:
            return SceneChange(
                change_id=f"change_{uuid.uuid4().hex[:12]}",
                camera_id=camera_id,
                timestamp=datetime.now(),
                has_changes=False,
                change_summary="变化检测分析失败",
                confidence=0
            )

        change = SceneChange(
            change_id=f"change_{uuid.uuid4().hex[:12]}",
            camera_id=camera_id,
            timestamp=datetime.now(),
            has_changes=result.get("has_changes", False),
            change_summary=result.get("change_summary", ""),
            change_details=result.get("change_details", {}),
            impact_assessment=result.get("impact_assessment", ""),
            suggested_actions=result.get("suggested_actions", []),
            confidence=result.get("confidence", 0)
        )

        # 保存变化历史
        self.change_history[change.change_id] = change

        # 如果有变化且置信度高，更新场景理解
        if change.has_changes and change.confidence > 60:
            await self.understand_scene(current_frame_base64, camera_id)

        return change

    def get_current_understanding(self, camera_id: str) -> Optional[Dict]:
        """获取当前场景理解"""
        understanding = self.scene_cache.get(camera_id)
        if understanding is None:
            return None

        return {
            "camera_id": understanding.camera_id,
            "timestamp": understanding.timestamp.isoformat(),
            "scene_description": understanding.scene_description,
            "equipment": [
                {
                    "name": e.name,
                    "location": e.location,
                    "status": e.status,
                    "equipment_type": e.equipment_type
                }
                for e in understanding.equipment
            ],
            "workstations": [
                {
                    "id": w.id,
                    "type": w.workstation_type,
                    "occupied": w.occupied,
                    "location": w.location
                }
                for w in understanding.workstations
            ],
            "zones": [
                {
                    "name": z.name,
                    "type": z.zone_type,
                    "boundaries": z.boundaries
                }
                for z in understanding.zones
            ],
            "workflow_understanding": understanding.workflow_understanding
        }

    def get_change_history(
        self,
        camera_id: Optional[str] = None,
        limit: int = 20
    ) -> List[Dict]:
        """获取变化历史"""
        changes = list(self.change_history.values())

        if camera_id:
            changes = [c for c in changes if c.camera_id == camera_id]

        # 按时间倒序
        changes.sort(key=lambda x: x.timestamp, reverse=True)

        return [
            {
                "change_id": c.change_id,
                "camera_id": c.camera_id,
                "timestamp": c.timestamp.isoformat(),
                "has_changes": c.has_changes,
                "change_summary": c.change_summary,
                "confidence": c.confidence,
                "applied": c.applied
            }
            for c in changes[:limit]
        ]

    def get_change(self, change_id: str) -> Optional[Dict]:
        """获取变化详情"""
        change = self.change_history.get(change_id)
        if change is None:
            return None

        return {
            "change_id": change.change_id,
            "camera_id": change.camera_id,
            "timestamp": change.timestamp.isoformat(),
            "has_changes": change.has_changes,
            "change_summary": change.change_summary,
            "change_details": change.change_details,
            "impact_assessment": change.impact_assessment,
            "suggested_actions": change.suggested_actions,
            "confidence": change.confidence,
            "applied": change.applied
        }

    def mark_change_applied(self, change_id: str) -> bool:
        """标记变化已应用"""
        if change_id not in self.change_history:
            return False

        self.change_history[change_id].applied = True
        return True

    def get_all_cameras_understanding(self) -> List[Dict]:
        """获取所有摄像头的场景理解摘要"""
        return [
            {
                "camera_id": understanding.camera_id,
                "timestamp": understanding.timestamp.isoformat(),
                "scene_summary": understanding.scene_description[:100] if understanding.scene_description else "",
                "equipment_count": len(understanding.equipment),
                "workstation_count": len(understanding.workstations),
                "zone_count": len(understanding.zones)
            }
            for understanding in self.scene_cache.values()
        ]


# 全局实例
_scene_service: Optional[SceneUnderstandingService] = None


def get_scene_service() -> SceneUnderstandingService:
    """获取全局场景理解服务实例"""
    global _scene_service
    if _scene_service is None:
        _scene_service = SceneUnderstandingService()
    return _scene_service
