# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

**白垩纪食品溯源系统 (Cretas Food Traceability System)**

| 组件 | 技术栈 |
|------|--------|
| **后端** | Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA (Hibernate 6) |
| **前端** | Expo 53+ + TypeScript + React Navigation 7+ |
| **AI服务** | Python + LLM API |

**项目状态**: Phase 3 核心完成 (82-85%)

---

## Quick Reference

### 端口配置

| Service | Port | URL |
|---------|------|-----|
| React Native | 3010 | `http://localhost:3010` |
| Cretas 后端 (Java) | 10010 | `http://47.100.235.168:10010` |
| Python 服务 | 8083 | `http://localhost:8083` |
| Embedding 服务 | 9090 | gRPC |
| Mall 后端 | 8080 | `http://139.196.165.140:8080` |
| PostgreSQL | 5432 | `localhost:5432` |
| Redis | 6379 | `localhost:6379` |
| FRP | 7501 | 内网穿透 |

### 测试账号
测试凭证不提交到代码仓库。参见项目根目录 `.env.test.example` 获取格式，实际密码本地配置。

---

## Development Commands

### 前端 (React Native)
```bash
cd frontend/CretasFoodTrace
npm start                    # Start Expo
npx expo start --clear      # Clear cache
```

### 后端 (Java - Spring Boot)
```bash
cd backend/java/cretas-api
mvn clean package -DskipTests    # Build
mvn spring-boot:run              # Run locally
```

### 后端 (Python - FastAPI)
```bash
cd backend/python
pip install -r requirements.txt  # 安装依赖
uvicorn main:app --port 8083     # 启动服务
```

### 部署到服务器
```bash
# 方式1: JAR 部署 (推荐，默认)
./scripts/deploy/deploy-backend.sh              # 本地打包 → GitHub Release → 服务器拉取

# 方式2: Git 部署 (旧方式)
./scripts/deploy/deploy-backend.sh --git        # git push → 服务器编译

# 或使用 skill
/deploy-backend
```

---

## Server Structure

### 服务器目录 (`/www/wwwroot/`)
```
/www/wwwroot/
├── cretas/              # Cretas 食品溯源系统
│   ├── aims-0.0.1-SNAPSHOT.jar  # 主 JAR
│   ├── pull-jar.sh      # 从 Release 拉取 JAR
│   ├── deploy.sh        # Git 部署脚本
│   ├── restart.sh       # 重启服务
│   ├── code/            # 完整代码仓库
│   └── logs/            # 日志目录
├── mall/                # 商城系统
│   ├── admin/           # 管理前端
│   ├── backend/         # 后端服务 (logistics-admin.jar)
│   └── data/            # 数据文件
├── showcase/            # 展示网站
│   └── cretaceousfuture/
└── web-admin/           # Web 管理前端
```

### 服务管理
```bash
# Mall 后端 (systemd 管理)
systemctl status mall-backend
systemctl restart mall-backend

# Cretas 后端 (脚本管理)
cd /www/wwwroot/cretas && bash restart.sh

# 查看日志
tail -f /www/wwwroot/cretas/cretas-backend.log
tail -f /www/wwwroot/mall/backend/mall-admin.log
```

---

## Architecture

### 后端目录结构
```
backend/
├── java/                          # Java 服务
│   ├── cretas-api/                # 主后端 (Spring Boot, 端口 10010)
│   │   └── src/main/java/com/cretas/aims/
│   │       ├── controller/        # REST API
│   │       ├── entity/            # JPA 实体
│   │       ├── service/           # 业务逻辑
│   │       │   ├── impl/          # Service 实现
│   │       │   └── skill/         # Skill 编排层
│   │       ├── ai/                # AI 意图系统
│   │       │   ├── tool/          # Tool-Skill 架构 (310 tools)
│   │       │   │   ├── ToolExecutor.java
│   │       │   │   ├── AbstractBusinessTool.java
│   │       │   │   ├── ToolRegistry.java
│   │       │   │   └── impl/{domain}/  # 按领域分包
│   │       │   └── dto/           # AI DTO
│   │       ├── repository/        # 数据访问
│   │       └── config/            # 配置类
│   └── embedding-service/         # 向量嵌入服务 (gRPC, 端口 9090)
│
└── python/                        # Python 服务 (FastAPI, 端口 8083)
    ├── main.py                    # 统一入口
    ├── smartbi/                   # SmartBI 数据分析模块
    │   ├── api/                   # API 路由
    │   └── services/              # 业务逻辑
    └── efficiency_recognition/    # 人效识别模块
        ├── api/                   # API 路由
        └── services/              # VL 分析服务
```

### 前端结构
```
frontend/CretasFoodTrace/src/
├── screens/       # 页面组件
├── components/    # UI 组件
├── services/api/  # API 客户端
├── store/         # Zustand 状态
├── navigation/    # 路由配置
└── types/         # TypeScript 类型
```

### API 路径

**Java 后端 (10010)**
- 基础路径: `/api/mobile/*`
- 认证: `/api/mobile/auth/*`
- 业务: `/api/mobile/{factoryId}/*`

**Python 服务 (8083)**
- SmartBI: `/api/smartbi/*`
  - Excel: `/api/smartbi/excel/*`
  - 分析: `/api/smartbi/analysis/*`
  - 图表: `/api/smartbi/chart/*`
  - 预测: `/api/smartbi/forecast/*`
- 人效识别: `/api/efficiency/*`
  - 帧分析: `/api/efficiency/analyze-frame`
  - 视频分析: `/api/efficiency/analyze-video-upload`

---

## Key Patterns

### 代码质量原则

本项目用 **Thin-Opus-Organizer 编排**：所有想法经一个 Opus organizer 分配给 Sonnet/Codex/Composer，详见 `organizer-protocol.md`。

详见 `.claude/rules/` 目录下的规范文件：
- `organizer-protocol.md` - **多模型编排模型（Thin-Opus Organizer，顶层入口，核心）**
- `multi-model-dispatch.md` - 模型/effort/orchestration 三轴路由（含 Sonnet 执行层 + 预算均衡）
- `ai-intent-tool-skill-architecture.md` - **AI Tool-Skill 架构规范（核心）**
- `api-response-handling.md` - API 响应处理
- `typescript-type-safety.md` - TypeScript 类型安全
- `jwt-token-handling.md` - JWT Token 处理
- `database-entity-sync.md` - 数据库同步
- `field-naming-convention.md` - 字段命名
- `server-operations.md` - 服务器运维规范
- `concurrent-edit-safety.md` - **并发编辑安全（共享脚本修改前必读）**

### 核心原则
1. **禁止降级处理** - 不返回假数据，明确显示错误
2. **类型安全** - 避免 `as any`，使用明确类型
3. **统一响应格式** - `{ success, data, message }`
4. **Tool-Skill Only** - AI 意图处理只用 Tool/Skill，禁止创建 IntentHandler（已废弃）

### UX Flow Gate（低技术素养用户屏幕）

任何涉及以下角色/路径/功能的 RN 屏幕设计，**brainstorming 阶段必须在 propose approaches 之前先 invoke `ux-flow` skill**：

- **角色词**：operator、操作员、仓管、warehouse_worker、quality_inspector、质检员
- **路径词**：screens/processing、screens/warehouse、screens/quality-inspector
- **功能词**：报工、入库、出库、盘点、质检、扫码收货

`ux-flow` Phase 1 产出的「UX Flow Analysis」章节是 spec 的强制组成部分，缺失则不进入 writing-plans。

详见 `.claude/skills/ux-flow/SKILL.md`。

---

## Documentation

- [PRD-功能与文件映射-v3.0.md](./docs/prd/PRD-功能与文件映射-v3.0.md)
- [PRD-完整业务流程与界面设计-v5.0.md](./docs/prd/PRD-完整业务流程与界面设计-v5.0.md)
- [QUICK_START.md](./QUICK_START.md)

---

## Troubleshooting

### 健康检查
```bash
curl http://localhost:10010/api/mobile/health
lsof -i :10010
```

### 缓存问题
```bash
npx expo start --clear
rm -rf node_modules && npm install
```
