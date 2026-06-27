# Python 服务架构规范

**最后更新**: 2026-02-19

---

## 核心原则

**所有 Python 服务统一部署在一个进程中** (端口 8083)，通过模块化组织代码，避免端口和进程泛滥。

---

## 实际服务结构

```
backend/python/ (端口 8083)
├── main.py                          # 统一入口，注册所有路由
├── config.py                        # 全局配置 (DB, LLM API)
├── requirements.txt                 # 统一依赖
├── .env                             # 环境变量 (DASHSCOPE_API_KEY, DB连接)
│
├── smartbi/                         # SmartBI 数据分析模块
│   ├── api/
│   │   ├── excel.py                 # /api/smartbi/excel/*
│   │   ├── chart.py                 # /api/smartbi/chart/*
│   │   ├── cross_sheet.py           # /api/smartbi/cross-sheet-analysis
│   │   ├── yoy.py                   # /api/smartbi/yoy-comparison
│   │   ├── statistical.py           # /api/statistical/*
│   │   ├── finance_extract.py       # /api/smartbi/finance-extract
│   │   └── forecast.py              # /api/smartbi/forecast/*
│   ├── services/
│   │   ├── chart_builder.py         # 图表构建 (ECharts)
│   │   ├── insight_generator.py     # LLM AI 分析
│   │   ├── cross_sheet_aggregator.py # 跨 sheet 分析
│   │   └── ml/forecast.py           # 预测模型
│   └── config.py                    # SmartBI 配置
│
├── chat/                            # AI 对话模块
│   └── api/
│       └── chat.py                  # /api/chat/* (drill-down, hierarchy)
│
├── intent_classifier/               # 意图分类器
│   └── classifier_service.py        # ONNX 模型推理
│
├── food_kb/                         # 食品知识库
│   ├── api/                         # /api/food-kb/*
│   └── services/                    # RAG + pgvector
│
└── efficiency_recognition/          # 人效识别模块
    ├── api/                         # /api/efficiency/*
    └── services/                    # VL 分析服务
```

---

## 路由规范

| 模块 | 路由前缀 | 说明 |
|------|----------|------|
| SmartBI | `/api/smartbi/*` | Excel 解析、图表、预测 |
| Chart | `/api/smartbi/chart/*` | 批量图表构建 |
| Statistical | `/api/statistical/*` | 统计分析、相关性 |
| Chat | `/api/chat/*` | AI 对话、drill-down |
| Insight | `/api/insight/*` | 快速摘要 |
| Intent Classifier | `/api/classifier/*` | ONNX 意图分类 |
| Food KB | `/api/food-kb/*` | 食品知识库 RAG |
| Efficiency | `/api/efficiency/*` | 人效识别 |

---

## 添加新模块的步骤

1. **创建模块目录**
   ```
   new_module/
   ├── __init__.py
   ├── api/
   │   └── endpoints.py
   └── services/
       └── service.py
   ```

2. **在 main.py 中注册路由**
   ```python
   from new_module.api import endpoints as new_module_api
   app.include_router(new_module_api.router, prefix="/api/new-module", tags=["NewModule"])
   ```

3. **更新 Java 配置** (如需要)
   - `PythonSmartBIConfig.java` 添加新端点
   - `PythonSmartBIClient.java` 添加调用方法

---

## 禁止事项

- 不要创建新的独立 Python 服务 (新端口/新进程)
- 不要在模块外放置业务代码
- 不要硬编码 URL，使用配置文件
- 不要硬编码数据库密码，使用 `.env`

---

## 部署

```bash
# 部署脚本
./scripts/deploy/deploy-smartbi-python.sh

# 服务器目录
/www/wwwroot/cretas/code/backend/python/

# 服务器启动 (由 restart.sh 管理)
cd /www/wwwroot/cretas/code/backend/python
source venv38/bin/activate
uvicorn main:app --host 0.0.0.0 --port 8083
```

---

## 端口分配

| 端口 | 服务 | 语言 |
|------|------|------|
| 8083 | python-services (统一) | Python |
| 9090 | embedding-service | Java (gRPC) |
| 10010 | cretas-backend | Java |

**注意**: Python 服务只使用 8083 端口，不要分配新端口。
