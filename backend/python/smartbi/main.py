from __future__ import annotations
"""
SmartBI Service - FastAPI Entry Point

A Python-based analytics service for Excel parsing, field detection,
metrics calculation, forecasting, and AI-powered insights.
"""
import logging
from contextlib import asynccontextmanager
from datetime import datetime

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from config import get_settings
from api import excel, field, metrics, forecast, insight, chart, analysis, ml, linucb, chat, db_analysis, classifier
from smartbi.api import restaurant_sections

# 导入独立的人效识别模块
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))
from efficiency_recognition.api import router as efficiency_router  # noqa: E402
from efficiency_recognition.api import stream_router, photo_router, tracking_router, cost_router, recording_router, scene_router  # noqa: E402,E501

# 导入客户需求反馈模块
from client_requirement.api import router as client_requirement_router  # noqa: E402
from client_requirement.completeness_calculator import router as completeness_router  # noqa: E402

# Configure logging
logging.basicConfig(
    level=logging.DEBUG if get_settings().debug else logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan handler"""
    logger.info("SmartBI Service starting up...")
    logger.info(f"Debug mode: {get_settings().debug}")
    logger.info(f"LLM Model: {get_settings().llm_model}")

    # Check PostgreSQL connection if enabled
    if get_settings().postgres_enabled:
        try:
            from database.connection import test_connection, is_postgres_enabled
            if is_postgres_enabled() and test_connection():
                logger.info("PostgreSQL connection established")
            else:
                logger.warning("PostgreSQL enabled but connection failed")
        except Exception as e:
            logger.warning(f"PostgreSQL initialization error: {e}")
    else:
        logger.info("PostgreSQL is disabled")

    yield
    logger.info("SmartBI Service shutting down...")


# Create FastAPI application
app = FastAPI(
    title="SmartBI Service",
    description="AI-powered business intelligence analytics service",
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/docs",
    redoc_url="/redoc"
)

# Configure CORS
settings = get_settings()
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    # QHJ revenue report (Phase G2) custom response headers must be exposed
    # to the browser. Without this list, JS can read body but not headers.
    expose_headers=[
        "X-Cache-Hit",
        "X-Gold-Materialized-At",
        "X-Store-Count",
        "X-Is-Stale",
    ],
)

# Include API routers
app.include_router(excel.router, prefix="/api/excel", tags=["Excel"])
app.include_router(field.router, prefix="/api/field", tags=["Field Detection"])
app.include_router(metrics.router, prefix="/api/metrics", tags=["Metrics"])
app.include_router(forecast.router, prefix="/api/forecast", tags=["Forecast"])
app.include_router(insight.router, prefix="/api/insight", tags=["Insight"])
app.include_router(chart.router, prefix="/api/chart", tags=["Chart"])
app.include_router(analysis.router, prefix="/api/analysis", tags=["Analysis"])
app.include_router(ml.router, prefix="/api/ml", tags=["ML"])
app.include_router(linucb.router, prefix="/api/linucb", tags=["LinUCB"])
app.include_router(chat.router, prefix="/api/chat", tags=["Chat"])
app.include_router(classifier.router, prefix="/api/classifier", tags=["Intent Classifier"])

# Database analysis endpoints (PostgreSQL)
# Note: db_analysis.router already has /api/smartbi/analysis/db prefix
app.include_router(db_analysis.router, tags=["Database Analysis"])

# Restaurant section handlers — one endpoint per section, plus /list discovery.
# The router carries its own /api/smartbi/restaurant/sections prefix, so we
# include it without an additional prefix here.
app.include_router(restaurant_sections.router)

# Efficiency recognition endpoints (VL-based worker efficiency analysis)
# 独立模块，从 efficiency_recognition/ 导入
app.include_router(efficiency_router, prefix="/api/efficiency", tags=["人效识别"])
app.include_router(stream_router, prefix="/api/efficiency/streams", tags=["多摄像头流管理"])
app.include_router(photo_router, prefix="/api/efficiency/photo", tags=["手动照片分析"])
app.include_router(tracking_router, prefix="/api/efficiency/tracking", tags=["跨摄像头追踪"])
app.include_router(cost_router, prefix="/api/efficiency/cost", tags=["成本监控"])
app.include_router(recording_router, prefix="/api/efficiency/recording", tags=["NVR录像分析"])
app.include_router(scene_router, prefix="/api/efficiency/scene", tags=["LLM场景理解"])

# Client requirement feedback endpoints
app.include_router(client_requirement_router, prefix="/api/client-requirement", tags=["客户需求反馈"])
app.include_router(client_requirement_router, prefix="/api/public/client-requirement", tags=["客户需求反馈(公开)"])
app.include_router(completeness_router, prefix="/api/client-requirement", tags=["数据完整度"])
app.include_router(completeness_router, prefix="/api/public/client-requirement", tags=["数据完整度(公开)"])

# QHJ 收入管理报表 (Phase G — 青花椒 + R_*_REAL restaurant tenants)
# Router self-mounts at /api/smartbi/{factory_id}/revenue-report/* per spec §8.
from smartbi.api.revenue_report import router as revenue_report_router  # noqa: E402
app.include_router(revenue_report_router, tags=["收入管理报表"])

# Chart Auto-Insight router is registered on the TOP-LEVEL main.py (backend/python/main.py),
# NOT here. smartbi/main.py is NOT the live uvicorn entrypoint — it is only a sub-app/blueprint.
# Registering here would make the endpoint unreachable (404) in production.


@app.get("/health")
async def health_check():
    """Health check endpoint — used by Java backend for availability probing"""
    postgres_status = "disabled"

    if get_settings().postgres_enabled:
        try:
            from database.connection import test_connection, is_postgres_enabled
            if is_postgres_enabled() and test_connection():
                postgres_status = "connected"
            else:
                postgres_status = "disconnected"
        except Exception:
            postgres_status = "error"

    # Check LLM API key availability
    llm_configured = bool(get_settings().llm_api_key)

    return JSONResponse(
        content={
            "status": "ok",
            "service": "smartbi",
            "version": "1.0.0",
            "timestamp": datetime.now().isoformat(),
            "postgres": postgres_status,
            "llm_configured": llm_configured,
            "models_loaded": True,
            "capabilities": {
                "excel_parse": True,
                "chart_build": True,
                "ai_insight": llm_configured,
                "forecast": True
            }
        }
    )


@app.get("/api/smartbi/health")
async def smartbi_health_check():
    """SmartBI-specific health check (alias for /health, matched by Java client path)"""
    return await health_check()


@app.get("/")
async def root():
    """Root endpoint with service information"""
    return {
        "service": "SmartBI Service",
        "version": "1.0.0",
        "description": "AI-powered business intelligence analytics service",
        "endpoints": {
            "health": "/health",
            "docs": "/docs",
            "excel": "/api/excel",
            "field": "/api/field",
            "metrics": "/api/metrics",
            "forecast": "/api/forecast",
            "insight": "/api/insight",
            "chart": "/api/chart",
            "analysis": "/api/analysis",
            "ml": "/api/ml",
            "linucb": "/api/linucb",
            "chat": "/api/chat",
            "classifier": "/api/classifier",
            "db_analysis": "/api/smartbi/analysis/db",
            "efficiency": "/api/efficiency",
            "efficiency_streams": "/api/efficiency/streams",
            "efficiency_cost": "/api/efficiency/cost",
            "efficiency_recording": "/api/efficiency/recording",
            "efficiency_scene": "/api/efficiency/scene",
            "client_requirement": "/api/client-requirement",
            "completeness": "/api/client-requirement/completeness"
        },
        "postgres_enabled": get_settings().postgres_enabled
    }


@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    """Global exception handler"""
    logger.error(f"Unhandled exception: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={
            "success": False,
            "message": str(exc) if settings.debug else "Internal server error",
            "data": None
        }
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug
    )
