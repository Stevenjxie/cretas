from __future__ import annotations

from fastapi import FastAPI

from .keruyun import router as keruyun_router


def create_app() -> FastAPI:
    app = FastAPI(title="Cretas 餐饮平台模拟器", version="1.0.0")
    app.include_router(keruyun_router)

    @app.get("/healthz")
    async def healthz():
        return {"status": "ok"}

    return app
