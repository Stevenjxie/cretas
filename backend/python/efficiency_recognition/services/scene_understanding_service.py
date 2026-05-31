"""
LLM 动态场景理解服务 (Phase 7)

使用 LLM 动态理解工厂场景，自动适应产线变化。

核心功能:
- 场景描述和设备识别
- 工位和区域自动划分
- 工作流程推断
- 变化检测和影响评估
- 自动适应建议

优势（对比传统 SIFT 等方法）:
- 理解"变化了什么"而不只是"是否变化"
- 自动推断工作流程
- 自然语言描述变化原因
- 可解释的变化分析

数据持久化:
- 支持 PostgreSQL 数据库存储
- 无数据库时自动降级为内存存储
"""

import json
import uuid
from datetime import datetime
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Any
from enum import Enum
import logging

logger = logging.getLogger(__name__)

# 尝试导入数据库仓库
try:
    from ..database.repository import get_repository, EfficiencyRepository  # noqa: F401,E501
    DATABASE_AVAILABLE = True
except ImportError:
    DATABASE_AVAILABLE = False
    logger.warning("Database repository not available for scene understanding")


class ChangeType(str, Enum):
    """变化类型"""
    EQUIPMENT_ADDED = "equipment_added"
    EQUIPMENT_REMOVED = "equipment_removed"
    EQUIPMENT_MOVED = "equipment_moved"
    WORKSTATION_ADDED = "workstation_added"
    WORKSTATION_REMOVED = "workstation_removed"
    WORKSTATION_MODIFIED = "workstation_modified"
    ZONE_MODIFIED = "zone_modified"
    LAYOUT_CHANGED = "layout_changed"
    WORKFLOW_CHANGED = "workflow_changed"


class ChangeImpact(str, Enum):
    """变化影响级别"""
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


@dataclass
class Equipment:
    """设备信息"""
    name: str
    equipment_type: str = ""
    location: str = ""
    status: str = "unknown"  # running / stopped / unknown
    confidence: float = 0.0


@dataclass
class Workstation:
    """工位信息"""
    station_id: str
    station_type: str = ""  # 加工 / 包装 / 检验 / 等
    location: str = ""
    occupied: bool = False
    worker_count: int = 0
    confidence: float = 0.0


@dataclass
class Zone:
    """区域信息"""
    name: str
    zone_type: str = ""  # 生产区 / 通道区 / 暂存区 / 其他
    boundaries: str = ""
    description: str = ""


@dataclass
class SceneUnderstanding:
    """场景理解结果"""
    scene_id: str
    camera_id: str
    scene_description: str = ""
    equipment: List[Equipment] = field(default_factory=list)
    workstations: List[Workstation] = field(default_factory=list)
    zones: List[Zone] = field(default_factory=list)
    workflow_understanding: str = ""
    captured_at: datetime = field(default_factory=datetime.now)
    reference_frame_base64: Optional[str] = None
    confidence: float = 0.0
    notes: str = ""


@dataclass
class ChangeDetail:
    """变化详情"""
    change_type: ChangeType
    description: str
    old_value: Optional[str] = None
    new_value: Optional[str] = None
    location: str = ""
    confidence: float = 0.0


@dataclass
class SceneChangeResult:
    """场景变化检测结果"""
    change_id: str
    camera_id: str
    has_changes: bool = False
    is_first_scan: bool = False
    change_summary: str = ""
    change_details: List[ChangeDetail] = field(default_factory=list)
    impact_assessment: str = ""
    impact_level: ChangeImpact = ChangeImpact.LOW
    suggested_actions: List[Dict[str, Any]] = field(default_factory=list)
    confidence: float = 0.0
    detected_at: datetime = field(default_factory=datetime.now)
    applied: bool = False


@dataclass
class AdaptationResult:
    """适应结果"""
    success: bool
    applied_actions: List[Dict[str, Any]] = field(default_factory=list)
    skipped_actions: List[Dict[str, Any]] = field(default_factory=list)
    error_message: Optional[str] = None


class SceneUnderstandingService:
    """基于 LLM 的动态场景理解服务"""

    def __init__(self, vl_client=None, factory_id: str = "F001"):
        """
        初始化场景理解服务

        Args:
            vl_client: VL 模型客户端（可选）
            factory_id: 工厂ID（用于数据库存储）
        """
        self._vl_client = vl_client
        self.factory_id = factory_id

        # 获取数据库仓库
        self._repository = None
        if DATABASE_AVAILABLE:
            try:
                self._repository = get_repository()
                logger.info(f"Scene service using database: {self._repository.is_database_enabled}")
            except Exception as e:
                logger.warning(f"Failed to initialize repository: {e}")

        # 内存缓存（有数据库时作为热缓存，无数据库时作为主存储）
        self.scene_understandings: Dict[str, SceneUnderstanding] = {}  # camera_id -> understanding
        self.change_history: Dict[str, List[SceneChangeResult]] = {}   # camera_id -> changes

        # 配置
        self.change_confidence_threshold = 60  # 变化置信度阈值
        self.auto_adapt_threshold = 80         # 自动适应阈值

    @property
    def repository(self):
        """获取仓库实例"""
        if self._repository is None and DATABASE_AVAILABLE:
            self._repository = get_repository()
        return self._repository

    @property
    def use_database(self) -> bool:
        """是否使用数据库"""
        return self.repository is not None and self.repository.is_database_enabled

    async def understand_scene(
        self,
        image_base64: str,
        camera_id: str,
        save_reference: bool = True
    ) -> SceneUnderstanding:
        """
        让 LLM 动态理解当前场景

        Args:
            image_base64: Base64 编码的图片
            camera_id: 摄像头ID
            save_reference: 是否保存参考帧

        Returns:
            场景理解结果
        """
        prompt = """你是一个工厂产线分析专家。请分析这张工厂车间图片，动态识别：

返回以下 JSON 格式的分析结果：
{
    "scene_description": "用一段话描述你看到的整体场景（100-200字）",
    "equipment": [
        {
            "name": "设备名称/类型",
            "equipment_type": "设备类型（如：包装机/传送带/冷藏柜等）",
            "location": "大致位置（画面左侧/中央/右侧 + 前方/后方）",
            "status": "运行中/停止/未知",
            "confidence": 0.0-1.0
        }
    ],
    "workstations": [
        {
            "station_id": "工位编号（如可见，否则自动编号 WS01、WS02）",
            "station_type": "工位类型（加工/包装/检验/分拣/暂存等）",
            "location": "位置描述",
            "occupied": true/false,
            "worker_count": 0,
            "confidence": 0.0-1.0
        }
    ],
    "zones": [
        {
            "name": "区域名称",
            "zone_type": "生产区/通道区/暂存区/原料区/成品区/其他",
            "boundaries": "边界描述（如：画面左侧1/3区域）",
            "description": "区域功能描述"
        }
    ],
    "workflow_understanding": "基于设备和工位布局，推测工作流程（如：原料从左侧进入 → 中央加工 → 右侧包装 → 出库）",
    "confidence": 整体分析置信度 0.0-1.0,
    "notes": "其他观察或备注"
}

分析要点：
1. 仔细观察所有可见设备及其状态
2. 识别工作区域和工位
3. 观察人员分布和活动
4. 推断物料流动方向
5. 注意安全设施和通道

仅返回 JSON，不要包含其他文字。"""

        try:
            # Route through the free-tier VL fallback chain (call_chain) instead
            # of a direct DashScope httpx connection — gets circuit-breaker +
            # multi-account free fallback. payload drops `model` (call_chain
            # injects the SLOT.VL model per provider); vision messages preserved.
            from common.llm_router import call_chain, SLOT
            from common.llm_metrics import llm_caller_context

            payload = {
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": f"data:image/jpeg;base64,{image_base64}"
                                }
                            },
                            {
                                "type": "text",
                                "text": prompt
                            }
                        ]
                    }
                ],
                "max_tokens": 3000,
                "temperature": 0.2,
                "enable_thinking": False,
            }
            with llm_caller_context("scene_understanding"):
                result = await call_chain(SLOT.VL, payload)

            if "error" in result:
                logger.error(f"VL API error: {result['error']}")
                return self._create_empty_understanding(camera_id)

            content = result["choices"][0]["message"]["content"]

            # 提取 JSON
            import re
            json_match = re.search(r'\{[\s\S]*\}', content)
            if not json_match:
                logger.warning("No JSON found in VL response")
                return self._create_empty_understanding(camera_id)

            data = json.loads(json_match.group())

            # 构建场景理解结果
            understanding = SceneUnderstanding(
                scene_id=f"scene_{uuid.uuid4().hex[:12]}",
                camera_id=camera_id,
                scene_description=data.get("scene_description", ""),
                equipment=[
                    Equipment(
                        name=e.get("name", ""),
                        equipment_type=e.get("equipment_type", ""),
                        location=e.get("location", ""),
                        status=e.get("status", "unknown"),
                        confidence=e.get("confidence", 0.0)
                    )
                    for e in data.get("equipment", [])
                ],
                workstations=[
                    Workstation(
                        station_id=w.get("station_id", f"WS{i+1:02d}"),
                        station_type=w.get("station_type", ""),
                        location=w.get("location", ""),
                        occupied=w.get("occupied", False),
                        worker_count=w.get("worker_count", 0),
                        confidence=w.get("confidence", 0.0)
                    )
                    for i, w in enumerate(data.get("workstations", []))
                ],
                zones=[
                    Zone(
                        name=z.get("name", ""),
                        zone_type=z.get("zone_type", ""),
                        boundaries=z.get("boundaries", ""),
                        description=z.get("description", "")
                    )
                    for z in data.get("zones", [])
                ],
                workflow_understanding=data.get("workflow_understanding", ""),
                captured_at=datetime.now(),
                reference_frame_base64=image_base64 if save_reference else None,
                confidence=data.get("confidence", 0.0),
                notes=data.get("notes", "")
            )

            # 保存到数据库
            if self.use_database:
                self.repository.save_scene_understanding(
                    camera_id=camera_id,
                    factory_id=self.factory_id,
                    scene_description=understanding.scene_description,
                    equipment=[
                        {
                            "name": e.name,
                            "equipment_type": e.equipment_type,
                            "location": e.location,
                            "status": e.status,
                            "confidence": e.confidence
                        }
                        for e in understanding.equipment
                    ],
                    workstations=[
                        {
                            "station_id": w.station_id,
                            "station_type": w.station_type,
                            "location": w.location,
                            "occupied": w.occupied,
                            "worker_count": w.worker_count,
                            "confidence": w.confidence
                        }
                        for w in understanding.workstations
                    ],
                    zones=[
                        {
                            "name": z.name,
                            "zone_type": z.zone_type,
                            "boundaries": z.boundaries,
                            "description": z.description
                        }
                        for z in understanding.zones
                    ],
                    workflow=understanding.workflow_understanding,
                    confidence=understanding.confidence,
                    captured_at=understanding.captured_at
                )

            # 同时保存到内存缓存
            self.scene_understandings[camera_id] = understanding

            logger.info(f"Scene understanding completed for camera {camera_id}: "
                        f"{len(understanding.equipment)} equipment, "
                        f"{len(understanding.workstations)} workstations, "
                        f"{len(understanding.zones)} zones, "
                        f"database={self.use_database}")

            return understanding

        except Exception as e:
            logger.error(f"Scene understanding error: {e}", exc_info=True)
            return self._create_empty_understanding(camera_id)

    async def detect_changes(
        self,
        camera_id: str,
        current_frame_base64: str
    ) -> SceneChangeResult:
        """
        让 LLM 对比分析场景变化

        Args:
            camera_id: 摄像头ID
            current_frame_base64: 当前帧 Base64

        Returns:
            场景变化检测结果
        """
        # 获取上次的场景理解
        previous = self.scene_understandings.get(camera_id)

        if not previous:
            # 首次分析
            understanding = await self.understand_scene(current_frame_base64, camera_id)
            return SceneChangeResult(
                change_id=f"change_{uuid.uuid4().hex[:12]}",
                camera_id=camera_id,
                has_changes=False,
                is_first_scan=True,
                change_summary="首次场景扫描，已建立基准",
                confidence=understanding.confidence
            )

        # 构建对比分析 prompt
        previous_summary = self._summarize_understanding(previous)

        prompt = f"""你是一个工厂产线变更分析专家。

**上次的场景理解**（{previous.captured_at.strftime('%Y-%m-%d %H:%M')}）：
{previous_summary}

**现在请分析当前图片**，并与上次对比，回答以下问题：

返回 JSON 格式：
{{
    "has_changes": true/false,
    "change_summary": "一句话描述主要变化（如无变化则说明）",
    "change_details": [
        {{
            "change_type": "equipment_added/equipment_removed/equipment_moved/workstation_added/workstation_removed/workstation_modified/zone_modified/layout_changed/workflow_changed",  # noqa: E501
            "description": "变化描述",
            "old_value": "原来的状态（如适用）",
            "new_value": "现在的状态（如适用）",
            "location": "位置",
            "confidence": 0.0-1.0
        }}
    ],
    "impact_assessment": "这些变化对生产监控有什么影响",
    "impact_level": "low/medium/high/critical",
    "suggested_actions": [
        {{
            "type": "update_detection_zone/add_workstation/remove_workstation/update_counting_rule/update_workflow/alert_admin",  # noqa: E501
            "description": "建议的操作",
            "params": {{}}
        }}
    ],
    "confidence": 变化判断的置信度 0-100
}}

分析要点：
1. 仔细对比设备位置和数量变化
2. 注意工位的增减或调整
3. 观察区域划分是否改变
4. 评估对监控分析的影响
5. 提出可行的调整建议

仅返回 JSON，不要包含其他文字。"""

        try:
            messages = [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:image/jpeg;base64,{current_frame_base64}"
                            }
                        },
                        {
                            "type": "text",
                            "text": prompt
                        }
                    ]
                }
            ]

            # 如果有参考帧，添加到消息中
            if previous.reference_frame_base64:
                messages[0]["content"].insert(0, {
                    "type": "image_url",
                    "image_url": {
                        "url": f"data:image/jpeg;base64,{previous.reference_frame_base64}"
                    }
                })
                messages[0]["content"][-1]["text"] = "第一张图是上次的场景，第二张图是当前场景。\n\n" + prompt

            # Route through the free-tier VL fallback chain (call_chain) instead
            # of a direct DashScope httpx connection — gets circuit-breaker +
            # multi-account free fallback. payload drops `model` (call_chain
            # injects the SLOT.VL model per provider); vision messages preserved.
            from common.llm_router import call_chain, SLOT
            from common.llm_metrics import llm_caller_context

            payload = {
                "messages": messages,
                "max_tokens": 3000,
                "temperature": 0.2,
                "enable_thinking": False,
            }
            with llm_caller_context("scene_change_detection"):
                result = await call_chain(SLOT.VL, payload)

            if "error" in result:
                logger.error(f"VL API error: {result['error']}")
                return self._create_no_change_result(camera_id)

            content = result["choices"][0]["message"]["content"]

            # 提取 JSON
            import re
            json_match = re.search(r'\{[\s\S]*\}', content)
            if not json_match:
                logger.warning("No JSON found in change detection response")
                return self._create_no_change_result(camera_id)

            data = json.loads(json_match.group())

            # 构建变化结果
            change_result = SceneChangeResult(
                change_id=f"change_{uuid.uuid4().hex[:12]}",
                camera_id=camera_id,
                has_changes=data.get("has_changes", False),
                is_first_scan=False,
                change_summary=data.get("change_summary", ""),
                change_details=[
                    ChangeDetail(
                        change_type=ChangeType(d.get("change_type", "layout_changed")),
                        description=d.get("description", ""),
                        old_value=d.get("old_value"),
                        new_value=d.get("new_value"),
                        location=d.get("location", ""),
                        confidence=d.get("confidence", 0.0)
                    )
                    for d in data.get("change_details", [])
                ],
                impact_assessment=data.get("impact_assessment", ""),
                impact_level=ChangeImpact(data.get("impact_level", "low")),
                suggested_actions=data.get("suggested_actions", []),
                confidence=data.get("confidence", 0),
                detected_at=datetime.now()
            )

            # 如果检测到变化且置信度高，更新场景理解
            if change_result.has_changes and change_result.confidence >= self.change_confidence_threshold:
                # 保存变化历史到数据库
                if self.use_database:
                    self.repository.save_scene_change(
                        camera_id=camera_id,
                        factory_id=self.factory_id,
                        change_summary=change_result.change_summary,
                        change_details={
                            "details": [
                                {
                                    "change_type": d.change_type.value,
                                    "description": d.description,
                                    "old_value": d.old_value,
                                    "new_value": d.new_value,
                                    "location": d.location,
                                    "confidence": d.confidence
                                }
                                for d in change_result.change_details
                            ]
                        },
                        impact_assessment=change_result.impact_assessment,
                        impact_level=change_result.impact_level.value,
                        suggested_actions=change_result.suggested_actions,
                        confidence=change_result.confidence,
                        detected_at=change_result.detected_at
                    )

                # 同时保存到内存缓存
                if camera_id not in self.change_history:
                    self.change_history[camera_id] = []
                self.change_history[camera_id].append(change_result)

                # 更新场景理解
                new_understanding = await self.understand_scene(current_frame_base64, camera_id)  # noqa: F841
                logger.info(f"Scene updated for camera {camera_id} due to detected changes")

            logger.info(f"Change detection for camera {camera_id}: "
                        f"has_changes={change_result.has_changes}, "
                        f"confidence={change_result.confidence}")

            return change_result

        except Exception as e:
            logger.error(f"Change detection error: {e}", exc_info=True)
            return self._create_no_change_result(camera_id)

    async def auto_adapt(self, change_id: str) -> AdaptationResult:
        """
        根据 LLM 建议自动适应变化

        Args:
            change_id: 变化ID

        Returns:
            适应结果
        """
        # 查找变化记录
        change = None
        for camera_id, changes in self.change_history.items():
            for c in changes:
                if c.change_id == change_id:
                    change = c
                    break
            if change:
                break

        if not change:
            return AdaptationResult(
                success=False,
                error_message=f"Change not found: {change_id}"
            )

        if change.applied:
            return AdaptationResult(
                success=False,
                error_message=f"Change already applied: {change_id}"
            )

        applied_actions = []
        skipped_actions = []

        for action in change.suggested_actions:
            action_type = action.get("type", "")
            params = action.get("params", {})

            try:
                if action_type == "update_detection_zone":
                    # 更新检测区域（实际实现需要与其他服务集成）
                    logger.info(f"Would update detection zone: {params}")
                    applied_actions.append(action)

                elif action_type == "add_workstation":
                    # 添加工位（实际实现需要与数据库集成）
                    logger.info(f"Would add workstation: {params}")
                    applied_actions.append(action)

                elif action_type == "remove_workstation":
                    logger.info(f"Would remove workstation: {params}")
                    applied_actions.append(action)

                elif action_type == "update_counting_rule":
                    logger.info(f"Would update counting rule: {params}")
                    applied_actions.append(action)

                elif action_type == "update_workflow":
                    logger.info(f"Would update workflow: {params}")
                    applied_actions.append(action)

                elif action_type == "alert_admin":
                    # 发送管理员通知（实际实现需要与通知服务集成）
                    logger.info(f"Would alert admin: {action.get('description', '')}")
                    applied_actions.append(action)

                else:
                    logger.warning(f"Unknown action type: {action_type}")
                    skipped_actions.append(action)

            except Exception as e:
                logger.error(f"Failed to apply action {action_type}: {e}")
                skipped_actions.append(action)

        # 标记变化已处理
        change.applied = True

        return AdaptationResult(
            success=len(applied_actions) > 0,
            applied_actions=applied_actions,
            skipped_actions=skipped_actions
        )

    def get_current_understanding(self, camera_id: str) -> Optional[Dict]:
        """获取当前场景理解"""
        # 优先从数据库获取
        if self.use_database:
            db_record = self.repository.get_current_scene_understanding(camera_id)
            if db_record:
                return db_record

        # 降级：从内存获取
        understanding = self.scene_understandings.get(camera_id)
        if not understanding:
            return None

        return {
            "scene_id": understanding.scene_id,
            "camera_id": understanding.camera_id,
            "scene_description": understanding.scene_description,
            "equipment": [
                {
                    "name": e.name,
                    "equipment_type": e.equipment_type,
                    "location": e.location,
                    "status": e.status,
                    "confidence": e.confidence
                }
                for e in understanding.equipment
            ],
            "workstations": [
                {
                    "station_id": w.station_id,
                    "station_type": w.station_type,
                    "location": w.location,
                    "occupied": w.occupied,
                    "worker_count": w.worker_count,
                    "confidence": w.confidence
                }
                for w in understanding.workstations
            ],
            "zones": [
                {
                    "name": z.name,
                    "zone_type": z.zone_type,
                    "boundaries": z.boundaries,
                    "description": z.description
                }
                for z in understanding.zones
            ],
            "workflow_understanding": understanding.workflow_understanding,
            "captured_at": understanding.captured_at.isoformat(),
            "confidence": understanding.confidence,
            "notes": understanding.notes
        }

    def get_change_history(
        self,
        camera_id: str,
        limit: int = 50,
        only_applied: Optional[bool] = None
    ) -> List[Dict]:
        """获取变化历史"""
        # 优先从数据库获取
        if self.use_database:
            return self.repository.get_scene_change_history(camera_id, limit, only_applied)

        # 降级：从内存获取
        changes = self.change_history.get(camera_id, [])

        if only_applied is not None:
            changes = [c for c in changes if c.applied == only_applied]

        # 按时间倒序
        changes = sorted(changes, key=lambda c: c.detected_at, reverse=True)[:limit]

        return [
            {
                "change_id": c.change_id,
                "has_changes": c.has_changes,
                "change_summary": c.change_summary,
                "change_details": [
                    {
                        "change_type": d.change_type.value,
                        "description": d.description,
                        "location": d.location,
                        "confidence": d.confidence
                    }
                    for d in c.change_details
                ],
                "impact_assessment": c.impact_assessment,
                "impact_level": c.impact_level.value,
                "suggested_actions": c.suggested_actions,
                "confidence": c.confidence,
                "detected_at": c.detected_at.isoformat(),
                "applied": c.applied
            }
            for c in changes
        ]

    def get_all_camera_scenes(self) -> List[Dict]:
        """获取所有摄像头的场景摘要"""
        return [
            {
                "camera_id": camera_id,
                "scene_id": u.scene_id,
                "scene_description": u.scene_description[:100] + "..." if len(u.scene_description) > 100 else u.scene_description,  # noqa: E501
                "equipment_count": len(u.equipment),
                "workstation_count": len(u.workstations),
                "zone_count": len(u.zones),
                "captured_at": u.captured_at.isoformat(),
                "confidence": u.confidence
            }
            for camera_id, u in self.scene_understandings.items()
        ]

    def get_statistics(self) -> Dict[str, Any]:
        """获取场景理解统计"""
        total_cameras = len(self.scene_understandings)
        total_changes = sum(len(changes) for changes in self.change_history.values())
        applied_changes = sum(
            sum(1 for c in changes if c.applied)
            for changes in self.change_history.values()
        )

        # 设备统计
        total_equipment = sum(len(u.equipment) for u in self.scene_understandings.values())
        total_workstations = sum(len(u.workstations) for u in self.scene_understandings.values())
        total_zones = sum(len(u.zones) for u in self.scene_understandings.values())

        return {
            "total_cameras_analyzed": total_cameras,
            "total_equipment_detected": total_equipment,
            "total_workstations_detected": total_workstations,
            "total_zones_detected": total_zones,
            "total_changes_detected": total_changes,
            "applied_changes": applied_changes,
            "pending_changes": total_changes - applied_changes,
            "database_enabled": self.use_database
        }

    def _summarize_understanding(self, understanding: SceneUnderstanding) -> str:
        """生成场景理解摘要"""
        lines = [
            f"场景描述: {understanding.scene_description}",
            "",
            f"设备 ({len(understanding.equipment)} 个):"
        ]

        for e in understanding.equipment:
            lines.append(f"  - {e.name} ({e.equipment_type}) 在 {e.location}, 状态: {e.status}")

        lines.append(f"\n工位 ({len(understanding.workstations)} 个):")
        for w in understanding.workstations:
            status = "有人工作" if w.occupied else "空闲"
            lines.append(f"  - {w.station_id}: {w.station_type} 在 {w.location}, {status}")

        lines.append(f"\n区域 ({len(understanding.zones)} 个):")
        for z in understanding.zones:
            lines.append(f"  - {z.name} ({z.zone_type}): {z.boundaries}")

        lines.append(f"\n工作流程理解: {understanding.workflow_understanding}")

        return "\n".join(lines)

    def _create_empty_understanding(self, camera_id: str) -> SceneUnderstanding:
        """创建空的场景理解"""
        return SceneUnderstanding(
            scene_id=f"scene_{uuid.uuid4().hex[:12]}",
            camera_id=camera_id,
            scene_description="场景分析失败",
            captured_at=datetime.now(),
            confidence=0.0
        )

    def _create_no_change_result(self, camera_id: str) -> SceneChangeResult:
        """创建无变化结果"""
        return SceneChangeResult(
            change_id=f"change_{uuid.uuid4().hex[:12]}",
            camera_id=camera_id,
            has_changes=False,
            change_summary="分析失败或无法确定变化",
            confidence=0
        )


# 全局实例
_scene_service: Optional[SceneUnderstandingService] = None


def get_scene_service() -> SceneUnderstandingService:
    """获取全局场景理解服务实例"""
    global _scene_service
    if _scene_service is None:
        _scene_service = SceneUnderstandingService()
    return _scene_service
