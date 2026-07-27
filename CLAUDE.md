# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

**白垩纪食品溯源系统 (Cretas Food Traceability System)**

| 组件 | 技术栈 |
|------|--------|
| **后端** | Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA (Hibernate 6) |
| **前端** | Expo 53+ + TypeScript + React Navigation 7+ |
| **AI服务** | Python + FastAPI + LLM API |

## 端口配置

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

测试凭证不提交代码仓库：格式见根目录 `.env.test.example`，真值本地配置。

## 常用命令

```bash
cd frontend/CretasFoodTrace && npm start                       # RN 前端 (Expo)
cd backend/java/cretas-api && mvn clean package -DskipTests    # Java 构建
cd backend/python && uvicorn main:app --port 8083              # Python 服务
./scripts/deploy/deploy-backend.sh                             # 部署 (详见 server-operations skill / /deploy-backend)
```

API 基础路径：Java `/api/mobile/*`（业务 `/api/mobile/{factoryId}/*`）；Python `/api/smartbi/*`、`/api/efficiency/*` 等（详见 python-services-architecture rule）。

## 核心原则

1. **禁止降级处理** - 不返回假数据，明确显示错误
2. **类型安全** - 避免 `as any`，使用明确类型
3. **统一响应格式** - `{ success, data, message }`
4. **Tool-Skill Only** - AI 意图处理只用 Tool/Skill，禁止创建 IntentHandler（已废弃）

## 编排/运维 skill（按需加载，`.claude/skills/`）

- `organizer` - Thin-Opus Organizer 多模型编排（顶层入口，核心）
- `multi-model-dispatch` - 模型/effort/orchestration 三轴路由 + 分发卡
- `server-operations` - 服务器运维（服务器目录结构/systemd/日志/凭证/部署）

## 规则（`.claude/rules/`）

**常驻**（每 session 加载）：
- `concurrent-edit-safety.md` - 并发编辑安全（共享文件修改前必读）
- `worktree-and-main-only-deploy.md` - worktree 隔离 + prod 只从 main 部署
- `CREDENTIAL-MANAGEMENT.md` - 凭证与环境变量

**按路径加载**（`paths:` frontmatter，涉及对应文件时自动加载）：
- `ai-intent-tool-skill-architecture.md` - AI Tool-Skill 架构（backend/java）
- `database-entity-sync.md` - PostgreSQL 与 Entity 同步（backend/java）
- `python-services-architecture.md` - Python 统一进程规范（backend/python）
- `python-java-port.md` - Java→Python parity port 12 rules（backend）
- `coding-conventions.md` - 响应格式/TS 类型/字段命名/JWT（代码目录）
- `fool-proof-design.md` - 防呆设计 5 规则（frontend/web-admin）
- `playwright-headed-mode.md` - E2E headed 强制（tests）

## UX Flow Gate（低技术素养用户屏幕）

任何涉及以下角色/路径/功能的 RN 屏幕设计，**brainstorming 阶段必须在 propose approaches 之前先 invoke `ux-flow` skill**：

- **角色词**：operator、操作员、仓管、warehouse_worker、quality_inspector、质检员
- **路径词**：screens/processing、screens/warehouse、screens/quality-inspector
- **功能词**：报工、入库、出库、盘点、质检、扫码收货

`ux-flow` Phase 1 产出的「UX Flow Analysis」是 spec 强制组成部分，缺失不进入 writing-plans。

## Documentation

- [PRD-功能与文件映射-v3.0.md](./docs/prd/PRD-功能与文件映射-v3.0.md)
- [PRD-完整业务流程与界面设计-v5.0.md](./docs/prd/PRD-完整业务流程与界面设计-v5.0.md)
- [QUICK_START.md](./QUICK_START.md)
