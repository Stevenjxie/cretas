"""
人效识别 API 路由

提供 REST API 接口供外部调用:
- routes: 基础效率分析路由
- stream_routes: 多摄像头流管理路由
- photo_routes: 手动照片分析路由
- tracking_routes: 跨摄像头追踪路由
- cost_routes: 成本监控路由 (Phase 8.5)
- recording_routes: NVR 录像分析路由 (Phase 3)
- scene_routes: LLM 动态场景理解路由 (Phase 7)
- whisper_routes: ASR 转写路由 (Sprint 6 W2-C-2)
"""

from .routes import router
from .stream_routes import router as stream_router
from .photo_routes import router as photo_router
from .tracking_routes import router as tracking_router
from .cost_routes import router as cost_router
from .recording_routes import router as recording_router
from .scene_routes import router as scene_router
from .whisper_routes import router as whisper_router

__all__ = ["router", "stream_router", "photo_router", "tracking_router", "cost_router", "recording_router", "scene_router", "whisper_router"]  # noqa: E501
