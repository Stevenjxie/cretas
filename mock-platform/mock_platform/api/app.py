from __future__ import annotations

from fastapi import FastAPI

from .keruyun import router as keruyun_router


def create_app() -> FastAPI:
    app = FastAPI(title="Cretas 餐饮平台模拟器", version="1.0.0")
    app.include_router(keruyun_router)

    @app.get("/healthz")
    async def healthz():
        task = getattr(app.state, "generator_task", None)
        if task is None:
            # backfill / 测试场景没有常驻生成器，不算异常。
            return {"status": "ok", "generator": "not_armed"}
        if task.done():
            # 被 GC / 抛异常退出 —— 必须让外部看得见，不能继续报 ok。
            return {"status": "degraded", "generator": "stopped"}
        return {"status": "ok", "generator": "running"}

    return app
