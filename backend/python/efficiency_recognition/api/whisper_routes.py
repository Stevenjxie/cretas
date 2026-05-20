"""
Whisper / ASR 转写 API 路由 (Sprint 6 W2-C-2)

提供通话录音转写接口供 Java backend `WhisperTranscribeClient` 调用.

API 端点:
- POST /api/efficiency/whisper-transcribe - 给 OSS audio URL, 返回转写文本

调用模式 (Java side):
    POST /api/efficiency/whisper-transcribe
    {
        "audio_url": "https://...mp3",
        "factory_id": "F006",
        "call_record_id": 123,
        "language": "zh"
    }
    →
    {
        "success": true,
        "data": { "transcript": "..." },
        "message": "转写成功"
    }

Deps:
- 当前 stub 实现: 返回 placeholder 文本 (Sprint 6 ship phase, 实际 Whisper backend 后续 wire).
- TODO Phase B: 接 OpenAI Whisper / 阿里云语音 / 讯飞 ASR.
  优先 Aliyun ASR (账号 B 已配, 见 `.claude/rules/aliyun-credentials.md`).
- Graceful degrade: 任何失败返 500 + 明确 message, Java side 兜底标 transcript_status=FAILED.
"""

import os
import logging
from typing import Optional

from fastapi import APIRouter, Header, HTTPException, Request
from pydantic import BaseModel, Field

router = APIRouter(tags=["Whisper ASR"])

logger = logging.getLogger(__name__)

# Internal secret (mirrors Java config — header X-Internal-Secret check)
INTERNAL_SECRET = os.getenv("CRETAS_INTERNAL_SECRET", "")

# Toggle real ASR vs stub (default stub during Sprint 6 ship phase)
WHISPER_BACKEND = os.getenv("WHISPER_BACKEND", "stub").lower()  # stub | aliyun | openai


class WhisperTranscribeRequest(BaseModel):
    """转写请求 — Java WhisperTranscribeClient 发起."""
    audio_url: str = Field(..., description="OSS 音频文件 URL (公开可下载或 pre-signed)")
    factory_id: Optional[str] = Field(None, description="工厂 ID (audit trail)")
    call_record_id: Optional[int] = Field(None, description="CallRecord ID (for log 关联)")
    language: str = Field("zh", description="语言代码 (zh / en / auto)")


class WhisperTranscribeResponse(BaseModel):
    """转写响应."""
    success: bool
    data: Optional[dict] = None
    message: str = ""


def _verify_internal_secret(secret_header: Optional[str]) -> bool:
    """Verify X-Internal-Secret header matches env var. Empty config = no check."""
    if not INTERNAL_SECRET:
        # No secret configured → allow (test env / dev)
        return True
    return secret_header == INTERNAL_SECRET


@router.post("/whisper-transcribe", response_model=WhisperTranscribeResponse)
async def transcribe_audio(
    body: WhisperTranscribeRequest,
    request: Request,
    x_internal_secret: Optional[str] = Header(None, alias="X-Internal-Secret"),
    x_factory_id: Optional[str] = Header(None, alias="X-Factory-Id"),
):
    """
    Transcribe OSS audio URL to text.

    Sprint 6 W2-C-2 STUB: returns placeholder transcript with metadata. Java side
    treats placeholder as successful transcription and stores it; UI shows the
    placeholder banner so users know real ASR backend is pending.

    Phase B (post Sprint 6): wire actual Aliyun ASR or OpenAI Whisper.
    """
    if not _verify_internal_secret(x_internal_secret):
        logger.warning("whisper-transcribe: invalid X-Internal-Secret header")
        raise HTTPException(status_code=401, detail="invalid internal secret")

    if not body.audio_url or not body.audio_url.strip():
        return WhisperTranscribeResponse(
            success=False,
            data=None,
            message="audio_url 不能为空",
        )

    factory_id = body.factory_id or x_factory_id
    logger.info(
        "whisper-transcribe: factory=%s callRecordId=%s lang=%s url=%s backend=%s",
        factory_id, body.call_record_id, body.language, body.audio_url, WHISPER_BACKEND,
    )

    # === Backend dispatch ===
    if WHISPER_BACKEND == "stub":
        transcript = _stub_transcribe(body)
    elif WHISPER_BACKEND == "aliyun":
        transcript = _aliyun_transcribe(body)
    elif WHISPER_BACKEND == "openai":
        transcript = _openai_transcribe(body)
    else:
        logger.warning("whisper-transcribe: unknown backend=%s, fallback to stub", WHISPER_BACKEND)
        transcript = _stub_transcribe(body)

    if transcript is None:
        return WhisperTranscribeResponse(
            success=False,
            data=None,
            message=f"转写失败 (backend={WHISPER_BACKEND})",
        )

    return WhisperTranscribeResponse(
        success=True,
        data={
            "transcript": transcript,
            "language": body.language,
            "backend": WHISPER_BACKEND,
            "call_record_id": body.call_record_id,
        },
        message="转写成功",
    )


def _stub_transcribe(body: WhisperTranscribeRequest) -> Optional[str]:
    """
    Sprint 6 ship-phase stub. Returns a placeholder transcript so Java side
    flow E2E completes (transcript_status=SUCCESS). UI shows the banner so
    users know real ASR backend is pending Phase B.
    """
    placeholder = (
        "[Sprint 6 W2-C-2 转写 stub] 录音已上传至 OSS, 等待 Phase B 接入真实 ASR (Aliyun / Whisper). "
        f"音频 URL: {body.audio_url}. "
        "当前阶段, 请业务员在「备注」字段手工补录通话要点 (例: 客户反馈 / 报价确认 / 下次跟进时间)."
    )
    return placeholder


def _aliyun_transcribe(body: WhisperTranscribeRequest) -> Optional[str]:
    """
    Phase B: 阿里云 NLS / ASR backend. 当前 stub, Sprint 7+ 实现.

    依赖: aliyun-python-sdk-core + nls-sdk-python. Wire 时需:
    1. pip install alibabacloud_nls20180712
    2. 设置 ALIYUN_NLS_APP_KEY / ALIBABA_ACCESSKEY_ID (账号 B, per CREDENTIAL-MANAGEMENT.md)
    3. 实现 audio URL → bytes 下载 → NLS recognize_short_audio call
    """
    logger.warning("aliyun_transcribe: not yet implemented (Phase B), fallback to stub")
    return _stub_transcribe(body)


def _openai_transcribe(body: WhisperTranscribeRequest) -> Optional[str]:
    """
    Phase B alt: OpenAI Whisper API. 需 OPENAI_API_KEY 环境变量 + 海外网络.
    """
    logger.warning("openai_transcribe: not yet implemented (Phase B), fallback to stub")
    return _stub_transcribe(body)
