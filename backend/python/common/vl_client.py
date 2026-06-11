"""
公共 VL 模型客户端封装
支持多种阿里云视觉语言模型，根据任务类型自动选择最优模型

模型说明:
- qwen3-vl-flash: 快速响应，适合实时分析
- qwen-vl-plus: 成本优先，适合批量处理
- qwen-vl-max: 高精度，适合 OCR 和特征提取
- qwen3-vl-plus: 深度推理，适合场景理解
"""

import os
import json
import re
import httpx
from pathlib import Path
from typing import Dict, Optional
from dataclasses import dataclass
from enum import Enum

# Cost guard: this sync httpx lane bypasses call_chain (and thus its denylist
# guard). Import the single source of truth so a PAID model can never be billed
# from this direct path. Fall back to a local replica if the import path is
# unavailable (defensive — both must stay in sync).
try:
    from common.llm_router import _PAID_MODEL_DENYLIST
except Exception:  # pragma: no cover - defensive only
    _PAID_MODEL_DENYLIST = frozenset({
        "qwen3-max",
        "qwen3-max-2026-01-23",
        "qwen-max",
        "qwen-plus",
        "qwen3.5-122b-a10b",
    })


def load_env():
    """从 .env 文件加载环境变量"""
    env_paths = [
        Path(__file__).parent.parent / ".env",
        Path(__file__).parent.parent / "smartbi" / ".env",
        Path.cwd() / ".env",
    ]
    for env_path in env_paths:
        if env_path.exists():
            with open(env_path, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith('#') and '=' in line:
                        key, value = line.split('=', 1)
                        os.environ.setdefault(key.strip(), value.strip())


load_env()


class TaskType(Enum):
    """任务类型枚举"""
    EFFICIENCY = "efficiency"           # 效率分析（实时）
    OCR = "ocr"                         # 标签 OCR
    COUNTING = "counting"               # 货品计数
    TRACKING = "tracking"               # 工人追踪
    SCENE_UNDERSTANDING = "scene_understanding"  # 场景理解
    SCENE_CHANGE = "scene_change"       # 变化检测
    BATCH_VIDEO = "batch_video"         # 批量视频
    MIXED = "mixed"                     # 混合分析


class ModelConfig:
    """业务场景对应的模型配置"""

    # 实时分析（高频、低延迟）— free quota (was: qwen-vl-plus)
    REALTIME_ANALYSIS = os.getenv("VL_MODEL_REALTIME", "qwen3-vl-plus-2025-12-19")

    # 批量分析（成本优先）— free quota (was: qwen-vl-plus)
    BATCH_ANALYSIS = os.getenv("VL_MODEL_BATCH", "qwen3-vl-plus-2025-12-19")

    # 高精度任务（OCR、特征提取）— free quota (was: qwen-vl-max)
    HIGH_PRECISION = os.getenv("VL_MODEL_HIGH_PRECISION", "qwen3-vl-plus-2025-12-19")

    # 深度推理（场景理解、变化检测）— free quota (was: qwen-vl-max)
    DEEP_REASONING = os.getenv("VL_MODEL_DEEP_REASONING", "qwen3-vl-plus-2025-12-19")

    # 文本生成（报告、摘要）— free (qwen3-max-2026-01-23 was PAID — caused 2026-06-11 bill)
    TEXT_GENERATION = os.getenv("TEXT_MODEL", "qwen3.7-max-2026-06-08")

    @classmethod
    def get_model_for_task(cls, task_type: str) -> str:
        """根据任务类型获取推荐模型"""
        mapping = {
            TaskType.EFFICIENCY.value: cls.REALTIME_ANALYSIS,
            TaskType.OCR.value: cls.HIGH_PRECISION,
            TaskType.COUNTING.value: cls.REALTIME_ANALYSIS,
            TaskType.TRACKING.value: cls.HIGH_PRECISION,
            TaskType.SCENE_UNDERSTANDING.value: cls.DEEP_REASONING,
            TaskType.SCENE_CHANGE.value: cls.DEEP_REASONING,
            TaskType.BATCH_VIDEO.value: cls.BATCH_ANALYSIS,
            TaskType.MIXED.value: cls.REALTIME_ANALYSIS,
        }
        return mapping.get(task_type, cls.REALTIME_ANALYSIS)


@dataclass
class VLResponse:
    """VL 模型响应"""
    success: bool
    data: Optional[Dict] = None
    raw_content: str = ""
    error: Optional[str] = None
    model_used: str = ""
    tokens_used: int = 0


class QwenVLClient:
    """阿里云 Qwen VL 模型客户端"""

    def __init__(
        self,
        api_key: str = None,
        base_url: str = None,
        default_model: str = None,
        timeout: float = 120.0
    ):
        self.api_key = api_key or os.getenv("LLM_API_KEY", os.getenv("DASHSCOPE_API_KEY", ""))
        self.base_url = base_url or os.getenv(
            "LLM_VL_BASE_URL",
            "https://dashscope.aliyuncs.com/compatible-mode/v1"
        )
        self.default_model = default_model or os.getenv("LLM_VL_MODEL", "qwen3-vl-plus-2025-12-19")
        self.client = httpx.Client(timeout=timeout)

    def analyze(
        self,
        image_base64: str,
        prompt: str,
        task_type: str = None,
        model: str = None,
        temperature: float = 0.3,
        max_tokens: int = 2000
    ) -> VLResponse:
        """
        分析单张图片

        Args:
            image_base64: Base64 编码的图片
            prompt: 分析提示词
            task_type: 任务类型（用于自动选择模型）
            model: 指定模型（优先级高于 task_type）
            temperature: 温度参数
            max_tokens: 最大输出 token 数

        Returns:
            VLResponse 对象
        """
        # 确定使用的模型
        if model:
            use_model = model
        elif task_type:
            use_model = ModelConfig.get_model_for_task(task_type)
        else:
            use_model = self.default_model

        # TODO BYPASS-UNFIXED: sync context, needs async refactor. QwenVLClient
        # is a synchronous httpx.Client used by deep sync call chains
        # (efficiency_recognition photo/recording/routes + multi_stream_sampler,
        # all outside this lane). Routing through call_chain(SLOT.VL) requires
        # making analyze()/analyze_with_context() async and awaiting them up the
        # whole chain — too risky to change here. Left direct for now; efficiency
        # VL (offline video analysis) can be migrated to call_chain separately.
        #
        # Cost guard (fail loud, never silently bill): this direct lane bypasses
        # call_chain's denylist guard, so enforce it here too.
        if use_model in _PAID_MODEL_DENYLIST:
            raise RuntimeError(
                f"vl_client refusing PAID model {use_model} — set TEXT_MODEL/LLM_VL_MODEL to a free model"
            )
        try:
            response = self.client.post(
                f"{self.base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json"
                },
                json={
                    "model": use_model,
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
                    "max_tokens": max_tokens,
                    "temperature": temperature,
                    "enable_thinking": False
                }
            )

            result = response.json()

            if "error" in result:
                return VLResponse(
                    success=False,
                    error=f"API Error: {result['error']}",
                    model_used=use_model
                )

            content = result["choices"][0]["message"]["content"]
            tokens_used = result.get("usage", {}).get("total_tokens", 0)

            # 尝试解析 JSON
            parsed_data = self._parse_json_response(content)

            return VLResponse(
                success=True,
                data=parsed_data,
                raw_content=content,
                model_used=use_model,
                tokens_used=tokens_used
            )

        except Exception as e:
            return VLResponse(
                success=False,
                error=str(e),
                model_used=use_model
            )

    def analyze_with_context(
        self,
        current_image: str,
        reference_image: str,
        prompt: str,
        task_type: str = None,
        model: str = None
    ) -> VLResponse:
        """
        带参考图片的对比分析（用于场景变化检测）

        Args:
            current_image: 当前图片 Base64
            reference_image: 参考图片 Base64
            prompt: 分析提示词
            task_type: 任务类型
            model: 指定模型
        """
        if model:
            use_model = model
        elif task_type:
            use_model = ModelConfig.get_model_for_task(task_type)
        else:
            use_model = ModelConfig.DEEP_REASONING

        # TODO BYPASS-UNFIXED: sync context, needs async refactor. Same as
        # analyze() above — QwenVLClient sync httpx.Client called by deep sync
        # chains outside this lane. Migrate to call_chain(SLOT.VL) when the
        # efficiency VL path is converted to async end-to-end.
        #
        # Cost guard (fail loud, never silently bill): this direct lane bypasses
        # call_chain's denylist guard, so enforce it here too.
        if use_model in _PAID_MODEL_DENYLIST:
            raise RuntimeError(
                f"vl_client refusing PAID model {use_model} — set TEXT_MODEL/LLM_VL_MODEL to a free model"
            )
        try:
            response = self.client.post(
                f"{self.base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json"
                },
                json={
                    "model": use_model,
                    "messages": [
                        {
                            "role": "user",
                            "content": [
                                {
                                    "type": "text",
                                    "text": "参考图片（之前的场景）:"
                                },
                                {
                                    "type": "image_url",
                                    "image_url": {
                                        "url": f"data:image/jpeg;base64,{reference_image}"
                                    }
                                },
                                {
                                    "type": "text",
                                    "text": "当前图片（现在的场景）:"
                                },
                                {
                                    "type": "image_url",
                                    "image_url": {
                                        "url": f"data:image/jpeg;base64,{current_image}"
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
                    "temperature": 0.3,
                    "enable_thinking": False
                }
            )

            result = response.json()

            if "error" in result:
                return VLResponse(
                    success=False,
                    error=f"API Error: {result['error']}",
                    model_used=use_model
                )

            content = result["choices"][0]["message"]["content"]
            tokens_used = result.get("usage", {}).get("total_tokens", 0)

            parsed_data = self._parse_json_response(content)

            return VLResponse(
                success=True,
                data=parsed_data,
                raw_content=content,
                model_used=use_model,
                tokens_used=tokens_used
            )

        except Exception as e:
            return VLResponse(
                success=False,
                error=str(e),
                model_used=use_model
            )

    def _parse_json_response(self, content: str) -> Optional[Dict]:
        """解析 VL 模型返回的 JSON 内容"""
        try:
            # 尝试直接解析
            return json.loads(content)
        except json.JSONDecodeError:
            pass

        # 尝试从 markdown 代码块中提取
        json_match = re.search(r'```(?:json)?\s*([\s\S]*?)```', content)
        if json_match:
            try:
                return json.loads(json_match.group(1).strip())
            except json.JSONDecodeError:
                pass

        # 尝试找到 JSON 对象
        json_match = re.search(r'\{[\s\S]*\}', content)
        if json_match:
            try:
                return json.loads(json_match.group())
            except json.JSONDecodeError:
                pass

        return None

    def is_available(self) -> bool:
        """检查 VL 服务是否可用"""
        return bool(self.api_key)


# 全局客户端实例（懒加载）
_vl_client: Optional[QwenVLClient] = None


def get_vl_client() -> QwenVLClient:
    """获取全局 VL 客户端实例"""
    global _vl_client
    if _vl_client is None:
        _vl_client = QwenVLClient()
    return _vl_client
