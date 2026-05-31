"""
跨摄像头工人追踪服务
基于全身多维度特征的追踪（无人脸识别）

追踪维度:
- 工牌编号（如可见）
- 衣服颜色和样式
- 体型特征
- 安全装备
- 位置轨迹

数据流:
摄像头帧 -> VL特征提取 -> 特征匹配 -> 追踪ID关联 -> 轨迹记录

数据持久化:
- 支持 PostgreSQL 数据库存储
- 无数据库时自动降级为内存存储
"""

import json
import uuid
from datetime import datetime, timedelta
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Any
import logging

logger = logging.getLogger(__name__)

# 尝试导入数据库仓库
try:
    from ..database.repository import get_repository, EfficiencyRepository  # noqa: F401,E501
    DATABASE_AVAILABLE = True
except ImportError:
    DATABASE_AVAILABLE = False
    logger.warning("Database repository not available, using in-memory storage only")


@dataclass
class WorkerFeatures:
    """工人全身特征"""
    badge_number: Optional[str] = None      # 工牌编号
    clothing_upper: str = ""                 # 上衣描述
    clothing_lower: str = ""                 # 下装描述
    body_type: str = ""                      # 体型: THIN/MEDIUM/HEAVY
    height_estimate: str = ""                # 身高: SHORT/MEDIUM/TALL
    safety_gear: Dict[str, Any] = field(default_factory=dict)  # 安全装备
    position_in_frame: str = ""              # 画面位置
    action: str = ""                         # 当前动作
    confidence: float = 0.0                  # 特征置信度


@dataclass
class TrackingRecord:
    """追踪记录"""
    tracking_id: str
    worker_id: Optional[int] = None          # 关联的系统工人ID
    features: WorkerFeatures = field(default_factory=WorkerFeatures)
    last_camera_id: str = ""
    last_seen_time: datetime = field(default_factory=datetime.now)
    first_seen_time: datetime = field(default_factory=datetime.now)
    total_sightings: int = 0
    match_confidence: float = 0.0


@dataclass
class TrajectoryPoint:
    """轨迹点"""
    camera_id: str
    timestamp: datetime
    position: str
    action: str
    snapshot_base64: Optional[str] = None
    confidence: float = 0.0


@dataclass
class CameraTopology:
    """摄像头拓扑关系"""
    camera_a_id: str
    camera_b_id: str
    transition_time_seconds: int = 30        # 两摄像头之间的典型移动时间
    direction: str = "BIDIRECTIONAL"         # A_TO_B / B_TO_A / BIDIRECTIONAL


@dataclass
class WorkerIdentification:
    """工人识别结果"""
    tracking_id: str
    worker_id: Optional[int] = None
    features: WorkerFeatures = field(default_factory=WorkerFeatures)
    confidence: float = 0.0
    is_new: bool = False
    match_reason: str = ""


class WorkerTrackingService:
    """基于全身多维度特征的跨摄像头追踪服务"""

    def __init__(self, vl_client=None, factory_id: str = "F001"):
        """
        初始化追踪服务

        Args:
            vl_client: VL 模型客户端
            factory_id: 工厂ID（用于数据库存储）
        """
        self._vl_client = vl_client
        self.factory_id = factory_id

        # 获取数据库仓库（支持自动降级到内存存储）
        self._repository = None
        if DATABASE_AVAILABLE:
            try:
                self._repository = get_repository()
                logger.info(f"Tracking service using database: {self._repository.is_database_enabled}")
            except Exception as e:
                logger.warning(f"Failed to initialize repository: {e}")

        # 内存缓存（仅在没有数据库时作为主存储，有数据库时作为热缓存）
        self.tracking_records: Dict[str, TrackingRecord] = {}
        self.trajectories: Dict[str, List[TrajectoryPoint]] = {}
        self.camera_topology: Dict[str, CameraTopology] = {}

        # 匹配参数
        self.match_threshold = 0.6           # 匹配阈值
        self.time_window_seconds = 300       # 时间窗口（5分钟）

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

    @property
    def vl_client(self):
        """懒加载 VL 客户端"""
        if self._vl_client is None:
            from .video_analyzer import VideoEfficiencyAnalyzer
            self._vl_client = VideoEfficiencyAnalyzer()
        return self._vl_client

    async def extract_worker_features(
        self,
        image_base64: str
    ) -> List[WorkerFeatures]:
        """
        使用 VL 模型提取工人全身特征

        Args:
            image_base64: Base64 编码的图片

        Returns:
            工人特征列表
        """
        prompt = """你是一个工厂工人识别专家。请分析图片中的每个工人，提取以下特征（不要使用人脸识别）。

对于图片中的每个工人返回以下信息：
{
    "workers": [
        {
            "badge_number": "工牌/臂章上的编号（如可见，否则为 null）",
            "clothing_upper": "上衣描述（颜色 + 类型，如'蓝色工作服'、'白色T恤'）",
            "clothing_lower": "下装描述（颜色 + 类型，如'深蓝色长裤'、'黑色工装裤'）",
            "body_type": "体型（THIN/MEDIUM/HEAVY）",
            "height_estimate": "相对身高（SHORT/MEDIUM/TALL，基于周围参照物）",
            "safety_gear": {
                "hat_color": "帽子颜色（如有）",
                "has_mask": true/false,
                "has_gloves": true/false,
                "has_apron": true/false,
                "apron_color": "围裙颜色（如有）",
                "other_gear": ["其他装备"]
            },
            "position_in_frame": "在画面中的位置（左侧/中央/右侧 + 前方/后方）",
            "action": "当前动作描述",
            "confidence": 0.0-1.0
        }
    ],
    "total_workers": 工人总数,
    "notes": "其他观察"
}

分析要点：
1. 仔细观察每个工人的衣着特征
2. 注意工牌或臂章上的编号
3. 评估体型和相对身高
4. 记录所有可见的安全装备及其颜色
5. 描述在画面中的位置便于追踪

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
                "max_tokens": 2000,
                "temperature": 0.2,
                "enable_thinking": False,
            }
            with llm_caller_context("worker_tracking"):
                result = await call_chain(SLOT.VL, payload)

            if "error" in result:
                logger.error(f"VL API error: {result['error']}")
                return []

            content = result["choices"][0]["message"]["content"]

            import re
            json_match = re.search(r'\{[\s\S]*\}', content)
            if not json_match:
                return []

            data = json.loads(json_match.group())
            workers = []

            for w in data.get("workers", []):
                features = WorkerFeatures(
                    badge_number=w.get("badge_number"),
                    clothing_upper=w.get("clothing_upper", ""),
                    clothing_lower=w.get("clothing_lower", ""),
                    body_type=w.get("body_type", ""),
                    height_estimate=w.get("height_estimate", ""),
                    safety_gear=w.get("safety_gear", {}),
                    position_in_frame=w.get("position_in_frame", ""),
                    action=w.get("action", ""),
                    confidence=w.get("confidence", 0.0)
                )
                workers.append(features)

            return workers

        except Exception as e:
            logger.error(f"Feature extraction error: {e}")
            return []

    async def identify_workers(
        self,
        image_base64: str,
        camera_id: str,
        timestamp: datetime = None
    ) -> List[WorkerIdentification]:
        """
        识别画面中的工人并匹配已有追踪记录

        Args:
            image_base64: Base64 编码的图片
            camera_id: 摄像头ID
            timestamp: 时间戳

        Returns:
            工人识别结果列表
        """
        timestamp = timestamp or datetime.now()

        # 提取当前帧所有工人的特征
        current_workers = await self.extract_worker_features(image_base64)

        results = []
        for worker in current_workers:
            # 查找匹配的追踪记录
            match = await self._find_matching_track(worker, camera_id, timestamp)

            if match:
                # 更新已有追踪
                await self._update_track(match.tracking_id, worker, camera_id, timestamp)
                results.append(WorkerIdentification(
                    tracking_id=match.tracking_id,
                    worker_id=match.worker_id,
                    features=worker,
                    confidence=match.match_confidence,
                    is_new=False,
                    match_reason=f"Matched by {self._get_match_reason(worker, match.features)}"
                ))
            else:
                # 创建新追踪
                tracking_id = await self._create_new_track(worker, camera_id, timestamp)
                results.append(WorkerIdentification(
                    tracking_id=tracking_id,
                    worker_id=None,
                    features=worker,
                    confidence=worker.confidence,
                    is_new=True,
                    match_reason="New tracking created"
                ))

        return results

    async def _find_matching_track(
        self,
        worker: WorkerFeatures,
        camera_id: str,
        timestamp: datetime
    ) -> Optional[TrackingRecord]:
        """基于多维度特征匹配已有追踪"""
        candidates = self._get_candidates(camera_id, timestamp)

        best_match = None
        best_score = 0.0

        for candidate in candidates:
            score = self._calculate_match_score(worker, candidate.features)
            if score > best_score and score >= self.match_threshold:
                best_match = candidate
                best_score = score

        if best_match:
            best_match.match_confidence = best_score

        return best_match

    def _get_candidates(
        self,
        camera_id: str,
        timestamp: datetime
    ) -> List[TrackingRecord]:
        """获取候选追踪记录"""
        cutoff_time = timestamp - timedelta(seconds=self.time_window_seconds)
        candidates = []

        # 优先从数据库获取
        if self.use_database:
            db_records = self.repository.get_recent_tracking_candidates(
                camera_id=camera_id,
                time_window_seconds=self.time_window_seconds,
                factory_id=self.factory_id
            )
            for db_record in db_records:
                # 转换为 TrackingRecord
                features = WorkerFeatures(
                    badge_number=db_record.get("badgeNumber"),
                    clothing_upper=db_record.get("clothingUpper", ""),
                    clothing_lower=db_record.get("clothingLower", ""),
                    body_type=db_record.get("bodyType", ""),
                    height_estimate=db_record.get("heightEstimate", ""),
                    safety_gear=db_record.get("safetyGear", {})
                )
                last_seen = db_record.get("lastSeenTime")
                if isinstance(last_seen, str):
                    last_seen = datetime.fromisoformat(last_seen.replace("Z", "+00:00"))

                record = TrackingRecord(
                    tracking_id=db_record.get("trackingId"),
                    worker_id=db_record.get("workerId"),
                    features=features,
                    last_camera_id=db_record.get("lastSeenCamera", ""),
                    last_seen_time=last_seen or datetime.now(),
                    total_sightings=db_record.get("totalSightings", 0)
                )
                if self._can_transition(record.last_camera_id, camera_id, timestamp, record.last_seen_time):
                    candidates.append(record)
            return candidates

        # 降级：从内存获取
        for tracking_id, record in self.tracking_records.items():
            # 时间窗口内的追踪
            if record.last_seen_time >= cutoff_time:
                # 考虑摄像头拓扑（如果配置了）
                if self._can_transition(record.last_camera_id, camera_id, timestamp, record.last_seen_time):
                    candidates.append(record)

        return candidates

    def _can_transition(
        self,
        from_camera: str,
        to_camera: str,
        current_time: datetime,
        last_time: datetime
    ) -> bool:
        """检查从一个摄像头到另一个摄像头的转换是否合理"""
        if from_camera == to_camera:
            return True

        # 查找拓扑关系
        topology_key = f"{from_camera}_{to_camera}"
        reverse_key = f"{to_camera}_{from_camera}"

        topology = self.camera_topology.get(topology_key) or self.camera_topology.get(reverse_key)

        if topology is None:
            # 没有配置拓扑，允许转换
            return True

        # 检查时间是否合理
        time_diff = (current_time - last_time).total_seconds()
        max_time = topology.transition_time_seconds * 3  # 给予3倍容差

        return time_diff <= max_time

    def _calculate_match_score(
        self,
        worker: WorkerFeatures,
        candidate: WorkerFeatures
    ) -> float:
        """计算特征匹配得分"""
        score = 0.0

        # 工牌编号匹配（权重最高）
        if worker.badge_number and worker.badge_number == candidate.badge_number:
            score += 0.5

        # 衣服颜色匹配
        if worker.clothing_upper and worker.clothing_upper == candidate.clothing_upper:
            score += 0.15
        elif worker.clothing_upper and candidate.clothing_upper:
            # 部分匹配（颜色相同）
            if self._extract_color(worker.clothing_upper) == self._extract_color(candidate.clothing_upper):
                score += 0.08

        if worker.clothing_lower and worker.clothing_lower == candidate.clothing_lower:
            score += 0.1
        elif worker.clothing_lower and candidate.clothing_lower:
            if self._extract_color(worker.clothing_lower) == self._extract_color(candidate.clothing_lower):
                score += 0.05

        # 体型匹配
        if worker.body_type and worker.body_type == candidate.body_type:
            score += 0.1

        # 身高匹配
        if worker.height_estimate and worker.height_estimate == candidate.height_estimate:
            score += 0.1

        # 安全装备匹配
        gear_match = self._compare_safety_gear(worker.safety_gear, candidate.safety_gear)
        score += gear_match * 0.05

        return min(score, 1.0)

    def _extract_color(self, description: str) -> str:
        """从描述中提取颜色"""
        colors = ["蓝", "红", "绿", "黄", "白", "黑", "灰", "橙", "紫", "粉"]
        for color in colors:
            if color in description:
                return color
        return ""

    def _compare_safety_gear(
        self,
        gear1: Dict[str, Any],
        gear2: Dict[str, Any]
    ) -> float:
        """比较安全装备"""
        if not gear1 or not gear2:
            return 0.0

        matches = 0
        total = 0

        # 比较帽子颜色
        if gear1.get("hat_color") or gear2.get("hat_color"):
            total += 1
            if gear1.get("hat_color") == gear2.get("hat_color"):
                matches += 1

        # 比较围裙颜色
        if gear1.get("apron_color") or gear2.get("apron_color"):
            total += 1
            if gear1.get("apron_color") == gear2.get("apron_color"):
                matches += 1

        # 比较其他装备
        for key in ["has_mask", "has_gloves", "has_apron"]:
            if key in gear1 or key in gear2:
                total += 1
                if gear1.get(key) == gear2.get(key):
                    matches += 1

        return matches / total if total > 0 else 0.0

    def _get_match_reason(
        self,
        worker: WorkerFeatures,
        candidate: WorkerFeatures
    ) -> str:
        """获取匹配原因描述"""
        reasons = []

        if worker.badge_number and worker.badge_number == candidate.badge_number:
            reasons.append(f"badge({worker.badge_number})")

        if worker.clothing_upper == candidate.clothing_upper:
            reasons.append("upper_clothing")

        if worker.body_type == candidate.body_type:
            reasons.append("body_type")

        return ", ".join(reasons) if reasons else "similarity"

    async def _update_track(
        self,
        tracking_id: str,
        worker: WorkerFeatures,
        camera_id: str,
        timestamp: datetime
    ):
        """更新追踪记录"""
        # 转换特征为字典
        features_dict = {
            "badge_number": worker.badge_number,
            "clothing_upper": worker.clothing_upper,
            "clothing_lower": worker.clothing_lower,
            "body_type": worker.body_type,
            "height_estimate": worker.height_estimate,
            "safety_gear": worker.safety_gear
        }

        # 保存到数据库
        if self.use_database:
            self.repository.save_tracking_feature(
                tracking_id=tracking_id,
                factory_id=self.factory_id,
                features=features_dict,
                camera_id=camera_id,
                timestamp=timestamp,
                confidence=worker.confidence
            )
            self.repository.save_trajectory_point(
                tracking_id=tracking_id,
                factory_id=self.factory_id,
                camera_id=camera_id,
                timestamp=timestamp,
                position=worker.position_in_frame,
                action=worker.action,
                confidence=worker.confidence
            )

        # 同时更新内存缓存
        if tracking_id in self.tracking_records:
            record = self.tracking_records[tracking_id]
            record.features = worker
            record.last_camera_id = camera_id
            record.last_seen_time = timestamp
            record.total_sightings += 1

        # 添加轨迹点到缓存
        if tracking_id not in self.trajectories:
            self.trajectories[tracking_id] = []

        self.trajectories[tracking_id].append(TrajectoryPoint(
            camera_id=camera_id,
            timestamp=timestamp,
            position=worker.position_in_frame,
            action=worker.action,
            confidence=worker.confidence
        ))

    async def _create_new_track(
        self,
        worker: WorkerFeatures,
        camera_id: str,
        timestamp: datetime
    ) -> str:
        """创建新追踪"""
        tracking_id = f"track_{uuid.uuid4().hex[:12]}"

        # 转换特征为字典
        features_dict = {
            "badge_number": worker.badge_number,
            "clothing_upper": worker.clothing_upper,
            "clothing_lower": worker.clothing_lower,
            "body_type": worker.body_type,
            "height_estimate": worker.height_estimate,
            "safety_gear": worker.safety_gear
        }

        # 保存到数据库
        if self.use_database:
            self.repository.save_tracking_feature(
                tracking_id=tracking_id,
                factory_id=self.factory_id,
                features=features_dict,
                camera_id=camera_id,
                timestamp=timestamp,
                confidence=worker.confidence
            )
            self.repository.save_trajectory_point(
                tracking_id=tracking_id,
                factory_id=self.factory_id,
                camera_id=camera_id,
                timestamp=timestamp,
                position=worker.position_in_frame,
                action=worker.action,
                confidence=worker.confidence
            )

        # 创建内存记录
        record = TrackingRecord(
            tracking_id=tracking_id,
            features=worker,
            last_camera_id=camera_id,
            last_seen_time=timestamp,
            first_seen_time=timestamp,
            total_sightings=1,
            match_confidence=worker.confidence
        )

        self.tracking_records[tracking_id] = record
        self.trajectories[tracking_id] = [TrajectoryPoint(
            camera_id=camera_id,
            timestamp=timestamp,
            position=worker.position_in_frame,
            action=worker.action,
            confidence=worker.confidence
        )]

        logger.info(f"Created new tracking: {tracking_id}, database={self.use_database}")
        return tracking_id

    def get_trajectory(self, tracking_id: str) -> Optional[List[Dict]]:
        """获取工人轨迹"""
        # 优先从数据库获取
        if self.use_database:
            db_points = self.repository.get_trajectory(tracking_id)
            if db_points:
                return db_points

        # 降级：从内存获取
        if tracking_id not in self.trajectories:
            return None

        return [
            {
                "camera_id": point.camera_id,
                "timestamp": point.timestamp.isoformat(),
                "position": point.position,
                "action": point.action,
                "confidence": point.confidence
            }
            for point in self.trajectories[tracking_id]
        ]

    def get_tracking_record(self, tracking_id: str) -> Optional[Dict]:
        """获取追踪记录详情"""
        # 优先从数据库获取
        if self.use_database:
            db_record = self.repository.get_tracking_record(tracking_id)
            if db_record:
                return {
                    "tracking_id": db_record.get("trackingId"),
                    "worker_id": db_record.get("workerId"),
                    "features": {
                        "badge_number": db_record.get("badgeNumber"),
                        "clothing_upper": db_record.get("clothingUpper"),
                        "clothing_lower": db_record.get("clothingLower"),
                        "body_type": db_record.get("bodyType"),
                        "height_estimate": db_record.get("heightEstimate"),
                        "safety_gear": db_record.get("safetyGear", {})
                    },
                    "last_camera_id": db_record.get("lastSeenCamera"),
                    "last_seen_time": db_record.get("lastSeenTime"),
                    "first_seen_time": db_record.get("firstSeenTime"),
                    "total_sightings": db_record.get("totalSightings", 0),
                    "match_confidence": db_record.get("featureConfidence", 0)
                }

        # 降级：从内存获取
        if tracking_id not in self.tracking_records:
            return None

        record = self.tracking_records[tracking_id]
        return {
            "tracking_id": record.tracking_id,
            "worker_id": record.worker_id,
            "features": {
                "badge_number": record.features.badge_number,
                "clothing_upper": record.features.clothing_upper,
                "clothing_lower": record.features.clothing_lower,
                "body_type": record.features.body_type,
                "height_estimate": record.features.height_estimate,
                "safety_gear": record.features.safety_gear
            },
            "last_camera_id": record.last_camera_id,
            "last_seen_time": record.last_seen_time.isoformat(),
            "first_seen_time": record.first_seen_time.isoformat(),
            "total_sightings": record.total_sightings,
            "match_confidence": record.match_confidence
        }

    def set_camera_topology(
        self,
        camera_a_id: str,
        camera_b_id: str,
        transition_time_seconds: int = 30,
        direction: str = "BIDIRECTIONAL"
    ):
        """设置摄像头拓扑关系"""
        # 保存到数据库
        if self.use_database:
            self.repository.save_camera_topology(
                factory_id=self.factory_id,
                camera_a_id=camera_a_id,
                camera_b_id=camera_b_id,
                transition_time_seconds=transition_time_seconds,
                direction=direction
            )

        # 同时保存到内存缓存
        key = f"{camera_a_id}_{camera_b_id}"
        self.camera_topology[key] = CameraTopology(
            camera_a_id=camera_a_id,
            camera_b_id=camera_b_id,
            transition_time_seconds=transition_time_seconds,
            direction=direction
        )

    def get_camera_topology(self) -> List[Dict]:
        """获取所有摄像头拓扑关系"""
        # 优先从数据库获取
        if self.use_database:
            return self.repository.get_camera_topology(self.factory_id)

        # 降级：从内存获取
        return [
            {
                "camera_a_id": t.camera_a_id,
                "camera_b_id": t.camera_b_id,
                "transition_time_seconds": t.transition_time_seconds,
                "direction": t.direction
            }
            for t in self.camera_topology.values()
        ]

    def link_to_worker(self, tracking_id: str, worker_id: int) -> bool:
        """将追踪ID关联到系统工人"""
        # 保存到数据库
        if self.use_database:
            result = self.repository.link_tracking_to_worker(tracking_id, worker_id)
            if result:
                # 同步更新内存缓存
                if tracking_id in self.tracking_records:
                    self.tracking_records[tracking_id].worker_id = worker_id
                return True
            return False

        # 降级：仅内存操作
        if tracking_id not in self.tracking_records:
            return False

        self.tracking_records[tracking_id].worker_id = worker_id
        return True

    def get_all_tracks(self) -> List[Dict]:
        """获取所有追踪记录摘要"""
        # 优先从数据库获取
        if self.use_database:
            db_records = self.repository.get_all_tracking_records(self.factory_id)
            return [
                {
                    "tracking_id": r.get("trackingId"),
                    "worker_id": r.get("workerId"),
                    "badge_number": r.get("badgeNumber"),
                    "last_camera_id": r.get("lastSeenCamera"),
                    "last_seen_time": r.get("lastSeenTime"),
                    "total_sightings": r.get("totalSightings", 0)
                }
                for r in db_records
            ]

        # 降级：从内存获取
        return [
            {
                "tracking_id": record.tracking_id,
                "worker_id": record.worker_id,
                "badge_number": record.features.badge_number,
                "last_camera_id": record.last_camera_id,
                "last_seen_time": record.last_seen_time.isoformat(),
                "total_sightings": record.total_sightings
            }
            for record in self.tracking_records.values()
        ]

    def get_statistics(self) -> Dict[str, Any]:
        """获取追踪统计信息"""
        # 优先从数据库获取
        if self.use_database:
            stats = self.repository.get_tracking_statistics(self.factory_id)
            topology = self.repository.get_camera_topology(self.factory_id)
            stats["topology_relations"] = len(topology)
            return stats

        # 降级：从内存计算
        now = datetime.now()
        active_threshold = now - timedelta(minutes=30)

        total_tracks = len(self.tracking_records)
        active_tracks = sum(
            1 for r in self.tracking_records.values()
            if r.last_seen_time >= active_threshold
        )
        linked_tracks = sum(
            1 for r in self.tracking_records.values()
            if r.worker_id is not None
        )

        # 按摄像头统计
        camera_stats = {}
        for record in self.tracking_records.values():
            camera_id = record.last_camera_id
            if camera_id not in camera_stats:
                camera_stats[camera_id] = {"total": 0, "active": 0}
            camera_stats[camera_id]["total"] += 1
            if record.last_seen_time >= active_threshold:
                camera_stats[camera_id]["active"] += 1

        # 特征统计
        badge_identified = sum(
            1 for r in self.tracking_records.values()
            if r.features.badge_number
        )

        return {
            "total_tracks": total_tracks,
            "active_tracks": active_tracks,
            "linked_to_workers": linked_tracks,
            "unlinked_tracks": total_tracks - linked_tracks,
            "badge_identified": badge_identified,
            "camera_stats": camera_stats,
            "topology_relations": len(self.camera_topology),
            "total_trajectory_points": sum(len(t) for t in self.trajectories.values()),
            "database_enabled": False
        }

    def cleanup_old_tracks(
        self,
        max_age_hours: int = 24,
        keep_linked: bool = True
    ) -> Dict[str, int]:
        """清理旧的追踪记录"""
        cutoff_time = datetime.now() - timedelta(hours=max_age_hours)

        tracks_to_remove = []
        for tracking_id, record in self.tracking_records.items():
            if record.last_seen_time < cutoff_time:
                if keep_linked and record.worker_id is not None:
                    continue
                tracks_to_remove.append(tracking_id)

        removed_count = 0
        for tracking_id in tracks_to_remove:
            del self.tracking_records[tracking_id]
            if tracking_id in self.trajectories:
                del self.trajectories[tracking_id]
            removed_count += 1

        return {
            "removed_tracks": removed_count,
            "remaining_tracks": len(self.tracking_records)
        }

    def get_tracks_by_camera(self, camera_id: str) -> List[Dict]:
        """获取指定摄像头的追踪记录"""
        return [
            {
                "tracking_id": record.tracking_id,
                "worker_id": record.worker_id,
                "badge_number": record.features.badge_number,
                "last_seen_time": record.last_seen_time.isoformat(),
                "total_sightings": record.total_sightings,
                "features": {
                    "clothing_upper": record.features.clothing_upper,
                    "clothing_lower": record.features.clothing_lower,
                    "body_type": record.features.body_type
                }
            }
            for record in self.tracking_records.values()
            if record.last_camera_id == camera_id
        ]

    def search_by_badge(self, badge_number: str) -> List[Dict]:
        """按工牌号搜索追踪记录"""
        return [
            {
                "tracking_id": record.tracking_id,
                "worker_id": record.worker_id,
                "badge_number": record.features.badge_number,
                "last_camera_id": record.last_camera_id,
                "last_seen_time": record.last_seen_time.isoformat(),
                "total_sightings": record.total_sightings
            }
            for record in self.tracking_records.values()
            if record.features.badge_number and badge_number.lower() in record.features.badge_number.lower()
        ]

    def search_by_clothing(self, color: str) -> List[Dict]:
        """按衣着颜色搜索追踪记录"""
        color_lower = color.lower()
        return [
            {
                "tracking_id": record.tracking_id,
                "worker_id": record.worker_id,
                "badge_number": record.features.badge_number,
                "clothing_upper": record.features.clothing_upper,
                "clothing_lower": record.features.clothing_lower,
                "last_camera_id": record.last_camera_id,
                "last_seen_time": record.last_seen_time.isoformat()
            }
            for record in self.tracking_records.values()
            if color_lower in record.features.clothing_upper.lower() or
            color_lower in record.features.clothing_lower.lower()
        ]

    def get_worker_movement_summary(self, tracking_id: str) -> Optional[Dict]:
        """获取工人移动摘要"""
        if tracking_id not in self.tracking_records:
            return None

        record = self.tracking_records[tracking_id]
        trajectory = self.trajectories.get(tracking_id, [])

        if not trajectory:
            return None

        # 计算移动摘要
        camera_visits = {}
        for point in trajectory:
            if point.camera_id not in camera_visits:
                camera_visits[point.camera_id] = {
                    "visits": 0,
                    "first_visit": point.timestamp,
                    "last_visit": point.timestamp
                }
            camera_visits[point.camera_id]["visits"] += 1
            camera_visits[point.camera_id]["last_visit"] = point.timestamp

        # 计算总活动时间
        if len(trajectory) >= 2:
            total_duration = (trajectory[-1].timestamp - trajectory[0].timestamp).total_seconds()
        else:
            total_duration = 0

        return {
            "tracking_id": tracking_id,
            "worker_id": record.worker_id,
            "badge_number": record.features.badge_number,
            "total_duration_seconds": total_duration,
            "total_sightings": record.total_sightings,
            "cameras_visited": len(camera_visits),
            "camera_visits": {
                cam_id: {
                    "visits": data["visits"],
                    "first_visit": data["first_visit"].isoformat(),
                    "last_visit": data["last_visit"].isoformat()
                }
                for cam_id, data in camera_visits.items()
            },
            "first_seen": record.first_seen_time.isoformat(),
            "last_seen": record.last_seen_time.isoformat()
        }


# 全局实例
_tracking_service: Optional[WorkerTrackingService] = None


def get_tracking_service() -> WorkerTrackingService:
    """获取全局追踪服务实例"""
    global _tracking_service
    if _tracking_service is None:
        _tracking_service = WorkerTrackingService()
    return _tracking_service
