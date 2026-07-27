---
paths:
  - "backend/python/**"
---

# Python 服务架构规范

**最后更新**: 2026-07-28

## 核心原则

**所有 Python 服务统一部署在一个进程中**（`backend/python/main.py`，端口 8083），模块化组织代码，禁止端口和进程泛滥。

## 路由规范

| 模块 | 路由前缀 |
|------|----------|
| SmartBI（Excel/图表/预测） | `/api/smartbi/*` |
| Statistical | `/api/statistical/*` |
| Chat（AI 对话/drill-down） | `/api/chat/*` |
| Insight | `/api/insight/*` |
| Intent Classifier（ONNX） | `/api/classifier/*` |
| Food KB（RAG + pgvector） | `/api/food-kb/*` |
| Efficiency（人效识别） | `/api/efficiency/*` |

## 添加新模块

1. 建 `new_module/{api,services}/` 目录
2. `main.py` 注册路由：`app.include_router(new_module_api.router, prefix="/api/new-module", tags=["NewModule"])`
3. 如 Java 需调用：`PythonSmartBIConfig.java` 加端点 + `PythonSmartBIClient.java` 加方法

## 禁止事项

- 不要创建新的独立 Python 服务（新端口/新进程）— 只用 8083
- 不要在模块外放业务代码
- 不要硬编码 URL / 数据库密码（用配置文件 / `.env`）

## 部署

`./scripts/deploy/deploy-smartbi-python.sh`；服务器目录 `/www/wwwroot/cretas/code/backend/python/`（venv38，restart.sh 管理）。端口分配：8083 Python 统一 / 9090 embedding gRPC / 10010 Java。
