"""
视频人效分析服务
使用阿里云 Qwen VL 模型分析工厂视频，提取人效相关数据

支持识别:
- 工人数量和身份
- 在岗/离岗状态
- 动作计数（完成件数）
- 工序类型
- 安全装备穿戴情况
"""

import os
import base64
import json
import subprocess
import tempfile
from pathlib import Path
from typing import Dict, List, Optional, Any
from dataclasses import dataclass, field
from datetime import datetime
import httpx


def load_api_key():
    """从 .env 文件或环境变量加载 API Key"""
    env_paths = [
        Path(__file__).parent.parent / ".env",
        Path(__file__).parent.parent.parent / "smartbi" / ".env",
        Path.cwd() / ".env"
    ]

    # Bug #18 fix (Apr 17 2026): setdefault, don't clobber existing env vars.
    for env_path in env_paths:
        if env_path.exists():
            with open(env_path, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith('#') and '=' in line:
                        key, value = line.split('=', 1)
                        os.environ.setdefault(key.strip(), value.strip())

    return os.getenv("LLM_API_KEY", os.getenv("DASHSCOPE_API_KEY", ""))


# 配置
API_KEY = load_api_key()
BASE_URL = os.getenv("LLM_VL_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
VL_MODEL = os.getenv("LLM_VL_MODEL", "qwen3-vl-plus-2025-12-19")


@dataclass
class WorkerDetection:
    """工人检测结果"""
    worker_id: Optional[str] = None
    position: str = ""
    status: str = ""
    action: str = ""
    safety_gear: Dict[str, bool] = field(default_factory=dict)
    confidence: float = 0.0


@dataclass
class EfficiencySnapshot:
    """效率快照"""
    timestamp: str = ""
    worker_count: int = 0
    active_workers: int = 0
    idle_workers: int = 0
    completed_actions: int = 0
    process_stage: str = ""
    scene_description: str = ""
    workers: List[WorkerDetection] = field(default_factory=list)
    safety_issues: List[str] = field(default_factory=list)
    efficiency_score: float = 0.0
    notes: str = ""


@dataclass
class VideoAnalysisResult:
    """视频分析结果"""
    success: bool = False
    video_path: str = ""
    duration_seconds: float = 0.0
    frame_count: int = 0
    snapshots: List[EfficiencySnapshot] = field(default_factory=list)
    summary: Dict[str, Any] = field(default_factory=dict)
    error: Optional[str] = None


class VideoEfficiencyAnalyzer:
    """视频人效分析器"""

    def __init__(self, api_key: str = None, model: str = None):
        self.api_key = api_key or API_KEY
        self.base_url = BASE_URL
        self.model = model or VL_MODEL
        self.client = httpx.Client(timeout=120.0)

    def extract_frames(self, video_path: str, interval_seconds: float = 5.0, max_frames: int = 10) -> List[str]:
        """从视频中提取帧"""
        frames = []
        temp_dir = tempfile.mkdtemp()

        ffmpeg_paths = [
            r"C:\Users\Steve\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.0.1-full_build\bin\ffmpeg.exe",  # noqa: E501
            r"C:\Users\Steve\AppData\Local\Microsoft\WinGet\Links\ffmpeg.exe",
            r"C:\ffmpeg\bin\ffmpeg.exe",
            r"C:\Program Files\ffmpeg\bin\ffmpeg.exe",
            "ffmpeg",
        ]
        ffmpeg_cmd = "ffmpeg"
        for p in ffmpeg_paths:
            if os.path.exists(p) or p == "ffmpeg":
                ffmpeg_cmd = p
                break

        try:
            output_pattern = os.path.join(temp_dir, "frame_%04d.jpg")
            cmd = [
                ffmpeg_cmd, "-i", video_path,
                "-vf", f"fps=1/{interval_seconds}",
                "-frames:v", str(max_frames),
                "-q:v", "2",
                output_pattern,
                "-y"
            ]

            result = subprocess.run(cmd, capture_output=True, text=True)
            if result.returncode != 0:
                print(f"FFmpeg error: {result.stderr}")
                return frames

            for i in range(1, max_frames + 1):
                frame_path = os.path.join(temp_dir, f"frame_{i:04d}.jpg")
                if os.path.exists(frame_path):
                    with open(frame_path, "rb") as f:
                        frame_data = base64.b64encode(f.read()).decode()
                        frames.append(frame_data)

        except Exception as e:
            print(f"Frame extraction error: {e}")
        finally:
            import shutil
            shutil.rmtree(temp_dir, ignore_errors=True)

        return frames

    def analyze_frame(self, frame_base64: str, frame_index: int, context: Dict = None) -> EfficiencySnapshot:
        """分析单帧图片"""
        context = context or {}
        factory_type = context.get("factory_type", "食品加工")
        process_hint = context.get("process_hint", "")

        prompt = f"""你是一个工厂人效分析专家。请仔细分析这张工厂车间图片，提取人效相关数据。

工厂类型: {factory_type}
{f'可能的工序: {process_hint}' if process_hint else ''}

请以 JSON 格式返回分析结果：
{{
    "worker_count": 工人总数,
    "active_workers": 正在工作的工人数（手上有动作、正在操作产品的工人）,
    "idle_workers": 空闲/等待的工人数（站着不动、聊天、看手机的工人）,
    "completed_actions": 画面中可见的已完成动作数（如已放到传送带的产品数、已封好的箱子数）,
    "process_stage": "工序类型（如切割、包装、分拣、搬运、灌装、化料等）",

    "efficiency_metrics": {{
        "products_visible": 画面中可见的产品/物料数量,
        "products_in_process": 正在被加工的产品数量,
        "products_completed": 已完成放置/传递的产品数量,
        "conveyor_load": "传送带负载程度(empty/light/normal/heavy/full)",
        "work_rhythm": "工作节奏(slow/normal/fast)",
        "bottleneck_detected": true/false,
        "bottleneck_location": "瓶颈位置（如有）"
    }},

    "workers": [
        {{
            "position": "工人位置描述（如左侧工位、传送带旁等）",
            "status": "working/idle/moving/waiting",
            "action": "当前动作描述（如装箱、封箱、搬运、等待物料等）",
            "action_phase": "动作阶段(picking/processing/placing/waiting)",
            "hands_occupied": true/false,
            "facing_workstation": true/false,
            "safety_gear": {{
                "work_clothes": true/false,
                "gloves": true/false,
                "mask": true/false,
                "cap": true/false,
                "apron": true/false
            }},
            "confidence": 0.0-1.0
        }}
    ],

    "time_estimation": {{
        "estimated_cycle_time_seconds": 估计的单件处理周期（秒）,
        "estimated_pieces_per_hour": 估计每小时处理件数,
        "utilization_rate": 工位利用率百分比(0-100)
    }},

    "safety_issues": ["安全问题列表，如未戴手套、未穿工服等"],
    "efficiency_score": 效率评分 0-100,
    "scene_description": "场景简述（50字以内）",
    "notes": "其他观察（如设备状态、环境问题等）"
}}

分析要点：
1. 仔细数清工人数量，区分工人和非工作人员
2. 判断每个工人的工作状态（正在操作 vs 等待/休息）
3. 识别正在进行的工序类型
4. 检查安全装备穿戴情况
5. 估算整体效率（活跃工人比例、动作节奏等）

仅返回 JSON，不要包含其他文字。"""

        # TODO BYPASS-UNFIXED: sync context, needs async refactor. analyze_frame
        # uses a sync httpx.Client and is called by sync chains (analyze_video
        # below + efficiency_recognition routes/sampler, outside this lane).
        # Routing through call_chain(SLOT.VL) requires making this async and
        # awaiting it up the whole chain. Left direct for now; efficiency VL
        # (offline video analysis) can be migrated to call_chain separately.
        try:
            response = self.client.post(
                f"{self.base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json"
                },
                json={
                    "model": self.model,
                    "messages": [
                        {
                            "role": "user",
                            "content": [
                                {
                                    "type": "image_url",
                                    "image_url": {
                                        "url": f"data:image/jpeg;base64,{frame_base64}"
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
                    "temperature": 0.3,
                    "enable_thinking": False
                }
            )

            result = response.json()

            if "error" in result:
                print(f"API Error: {result['error']}")
                return EfficiencySnapshot(notes=f"API Error: {result['error']}")

            content = result["choices"][0]["message"]["content"]

            import re
            json_match = re.search(r'\{[\s\S]*\}', content)
            if not json_match:
                return EfficiencySnapshot(notes="无法解析响应")

            data = json.loads(json_match.group())

            workers = []
            for w in data.get("workers", []):
                workers.append(WorkerDetection(
                    position=w.get("position", ""),
                    status=w.get("status", ""),
                    action=w.get("action", ""),
                    safety_gear=w.get("safety_gear", {}),
                    confidence=w.get("confidence", 0.0)
                ))

            return EfficiencySnapshot(
                timestamp=datetime.now().isoformat(),
                worker_count=data.get("worker_count", 0),
                active_workers=data.get("active_workers", 0),
                idle_workers=data.get("idle_workers", 0),
                completed_actions=data.get("completed_actions", 0),
                process_stage=data.get("process_stage", ""),
                scene_description=data.get("scene_description", ""),
                workers=workers,
                safety_issues=data.get("safety_issues", []),
                efficiency_score=data.get("efficiency_score", 0.0),
                notes=data.get("notes", "")
            )

        except Exception as e:
            print(f"Frame analysis error: {e}")
            return EfficiencySnapshot(notes=f"分析错误: {str(e)}")

    def analyze_video(self, video_path: str, interval_seconds: float = 5.0,
                      max_frames: int = 10, context: Dict = None) -> VideoAnalysisResult:
        """分析完整视频"""
        if not os.path.exists(video_path):
            return VideoAnalysisResult(
                success=False,
                video_path=video_path,
                error=f"视频文件不存在: {video_path}"
            )

        print(f"开始分析视频: {video_path}")

        frames = self.extract_frames(video_path, interval_seconds, max_frames)
        if not frames:
            return VideoAnalysisResult(
                success=False,
                video_path=video_path,
                error="无法提取视频帧，请确保已安装 ffmpeg"
            )

        print(f"提取了 {len(frames)} 帧，开始分析...")

        snapshots = []
        for i, frame in enumerate(frames):
            print(f"分析帧 {i + 1}/{len(frames)}...")
            snapshot = self.analyze_frame(frame, i, context)
            snapshot.timestamp = f"frame_{i + 1}"
            snapshots.append(snapshot)

        summary = self._generate_summary(snapshots)

        return VideoAnalysisResult(
            success=True,
            video_path=video_path,
            frame_count=len(frames),
            snapshots=snapshots,
            summary=summary
        )

    def _generate_summary(self, snapshots: List[EfficiencySnapshot]) -> Dict[str, Any]:
        """生成分析汇总"""
        if not snapshots:
            return {}

        total_workers = sum(s.worker_count for s in snapshots)
        total_active = sum(s.active_workers for s in snapshots)
        total_actions = sum(s.completed_actions for s in snapshots)
        avg_efficiency = sum(s.efficiency_score for s in snapshots) / len(snapshots)

        all_safety_issues = []
        for s in snapshots:
            all_safety_issues.extend(s.safety_issues)
        unique_safety_issues = list(set(all_safety_issues))

        process_stages = [s.process_stage for s in snapshots if s.process_stage]

        return {
            "total_frames_analyzed": len(snapshots),
            "avg_worker_count": total_workers / len(snapshots) if snapshots else 0,
            "avg_active_workers": total_active / len(snapshots) if snapshots else 0,
            "total_completed_actions": total_actions,
            "avg_efficiency_score": round(avg_efficiency, 1),
            "activity_rate": round((total_active / total_workers * 100) if total_workers > 0 else 0, 1),
            "identified_processes": list(set(process_stages)),
            "safety_issues": unique_safety_issues,
            "safety_compliance": len(unique_safety_issues) == 0
        }

    def isAvailable(self) -> bool:
        """检查 VL 服务是否可用"""
        return bool(self.api_key)

    def analyze_ocr(self, image_base64: str) -> Dict[str, Any]:
        """
        OCR 标签识别分析

        识别内容:
        - 批次号 (batch_number)
        - 产品名称 (product_name)
        - 生产日期 (production_date)
        - 保质期/有效期 (expiry_date)
        - 重量/规格 (weight)
        - 条形码 (barcode)
        - 二维码内容 (qr_content)
        """
        prompt = """你是一个标签识别专家。请仔细分析图片中的产品标签，提取以下信息。

请以 JSON 格式返回识别结果：
{
    "readable": true/false,  // 标签是否可读
    "print_quality": "GOOD/ACCEPTABLE/POOR",  // 打印质量
    "recognized_text": {
        "batch_number": "批次号（如 BN20260128001）",
        "product_name": "产品名称",
        "production_date": "生产日期（如 2026-01-28）",
        "expiry_date": "保质期/有效期",
        "weight": "重量/规格（如 500g）",
        "barcode": "条形码数字",
        "qr_content": "二维码内容（如果可见）",
        "manufacturer": "生产商（如果可见）",
        "storage_instructions": "储存条件（如果可见）",
        "ingredients": "配料表（如果可见）"
    },
    "quality_issues": ["问题列表，如：字迹模糊、部分遮挡、标签破损等"],
    "overall_score": 0-100,  // 标签整体质量评分
    "recommendation": "PASS/REVIEW/REJECT",  // 建议操作
    "notes": "其他观察"
}

分析要点：
1. 仔细识别所有可见文字
2. 注意日期格式的正确性
3. 检查条码/二维码的完整性
4. 评估打印质量（清晰度、对比度）
5. 如果某些信息不可见或无法识别，对应字段返回 null

仅返回 JSON，不要包含其他文字。"""

        # TODO BYPASS-UNFIXED: sync context, needs async refactor. analyze_ocr
        # uses a sync httpx.Client called by sync chains outside this lane.
        # Migrate to call_chain(SLOT.VL) when efficiency VL goes async.
        try:
            response = self.client.post(
                f"{self.base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json"
                },
                json={
                    "model": self.model,
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
                    "max_tokens": 1500,
                    "temperature": 0.2,
                    "enable_thinking": False
                }
            )

            result = response.json()

            if "error" in result:
                return {
                    "readable": False,
                    "error": f"API Error: {result['error']}",
                    "recognized_text": {},
                    "quality_issues": ["API调用失败"],
                    "overall_score": 0,
                    "recommendation": "REVIEW"
                }

            content = result["choices"][0]["message"]["content"]

            import re
            json_match = re.search(r'\{[\s\S]*\}', content)
            if json_match:
                return json.loads(json_match.group())

            return {
                "readable": False,
                "error": "无法解析响应",
                "recognized_text": {},
                "quality_issues": ["响应格式错误"],
                "overall_score": 0,
                "recommendation": "REVIEW"
            }

        except Exception as e:
            return {
                "readable": False,
                "error": str(e),
                "recognized_text": {},
                "quality_issues": [f"分析错误: {str(e)}"],
                "overall_score": 0,
                "recommendation": "REVIEW"
            }

    def analyze_counting(self, image_base64: str) -> Dict[str, Any]:
        """
        货品计数分析

        识别内容:
        - 货品数量
        - 货品类型
        - 货品状态
        - 置信度
        """
        prompt = """你是一个货品计数专家。请仔细分析图片中的货品，进行计数和分类。

请以 JSON 格式返回计数结果：
{
    "total_count": 总数量,
    "products": [
        {
            "product_type": "货品类型/名称",
            "count": 该类型数量,
            "status": "完整/破损/部分可见",
            "location": "位置描述（如左侧、中央、传送带上等）",
            "confidence": 0.0-1.0
        }
    ],
    "counting_method": "逐个计数/估算/部分遮挡估算",
    "visibility": "全部可见/部分遮挡/大量遮挡",
    "arrangement": "整齐排列/散乱/堆叠",
    "notes": "其他观察（如有重叠难以准确计数等）"
}

计数要点：
1. 尽可能准确地数清每一件货品
2. 如果有遮挡，标注为"部分可见"并估算
3. 区分不同类型的货品分别计数
4. 注意堆叠情况，可能有看不见的货品
5. 评估计数的可信度

仅返回 JSON，不要包含其他文字。"""

        # TODO BYPASS-UNFIXED: sync context, needs async refactor. analyze_counting
        # uses a sync httpx.Client called by sync chains outside this lane.
        # Migrate to call_chain(SLOT.VL) when efficiency VL goes async.
        try:
            response = self.client.post(
                f"{self.base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json"
                },
                json={
                    "model": self.model,
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
                    "max_tokens": 1000,
                    "temperature": 0.2,
                    "enable_thinking": False
                }
            )

            result = response.json()

            if "error" in result:
                return {
                    "total_count": 0,
                    "error": f"API Error: {result['error']}",
                    "products": [],
                    "notes": "API调用失败"
                }

            content = result["choices"][0]["message"]["content"]

            import re
            json_match = re.search(r'\{[\s\S]*\}', content)
            if json_match:
                return json.loads(json_match.group())

            return {
                "total_count": 0,
                "error": "无法解析响应",
                "products": [],
                "notes": "响应格式错误"
            }

        except Exception as e:
            return {
                "total_count": 0,
                "error": str(e),
                "products": [],
                "notes": f"分析错误: {str(e)}"
            }
