# AGENTS.md

This file provides guidance to Codex when working with this repository.

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
| Cretas 后端 (Java) | 10010 / 10020 | prod 蓝绿槽交替使用；开发机经 SSH tunnel 访问，public direct access is closed |
| Python 服务 | 8083 | `http://localhost:8083` |
| Embedding 服务 | 9090 | gRPC |
| Mall 后端 | 8080 | `http://139.196.165.140:8080` |
| PostgreSQL | 5432 | `localhost:5432` |
| Redis | 6379 | `localhost:6379` |
| FRP | 7501 | 内网穿透 |

### 测试账号 / 凭证
真值**不进 tracked 文件**（两个 GitHub 仓库都是 public）。真值在以下 gitignored 文件，Codex 可直接读取：
- `.codex/rules/aliyun-credentials.md` — 阿里云 AK/SK、服务器 SSH/宝塔、安全组操作
- `.codex/rules/db-credentials.md` — 数据库 / 服务密码
- `.codex/rules/test-credentials.md` — 测试账号（默认密码 123456）+ E2E 约定

格式占位见 `.env.test.example`。**禁止把这些真值复制进任何会提交的文件。**

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
./scripts/deploy/deploy-backend.sh              # 默认执行本次发布唯一一次 clean package → 上传 → 蓝绿切换

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

# Cretas 后端 (systemd 管理, 在 47 服务器)
systemctl status cretas-backend cretas-python cretas-embedding
systemctl restart cretas-backend
systemctl restart cretas-python

# 全部 prod 服务按依赖顺序重启
bash /www/wwwroot/cretas/restart.sh prod

# 查看日志
journalctl -u cretas-backend -f
tail -f /www/wwwroot/cretas/cretas-prod.log
tail -f /www/wwwroot/cretas/python-prod.log
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

详见 `.codex/rules/` 目录下的规范文件：
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

### 发布与 E2E 去重复

1. **唯一发布构建** - 同一提交的最终 JAR/Web 制品只完整构建一次。若部署脚本会构建，部署前不得再机械执行一次完整 package/build；只有具备同一 commit、哈希和制品清单的可信制品时才可跳过脚本构建并复用。
2. **蓝绿槽位交替** - prod Java 的 `10010` 与 `10020` 都是可用槽位，会交替成为 active。部署前必须读取 `139.196.165.140:/www/server/panel/vhost/nginx/_upstream_cretas.conf`，禁止假设某个槽永久停用。
3. **问题前移** - SKU、Workflow、报工单位或历史快照改动，合并前必须覆盖生产形态数据，包括旧 `g/box/case`、中文基本单位和多包装规格。
4. **一次生产验收** - 后端与 Web 都部署完成后执行一次 F006 只读 E2E，业务写请求必须为 0。脚本/选择器失败与产品失败分开记录；共享前置条件未变化时只重跑失败场景，不机械重跑全套。
5. **全量 CI 分层** - 改动相关目标测试和必需门禁必须通过；已确认的全量测试基线噪声应单独治理，不得用重复本地全量构建代替，也不得隐瞒其状态。

### UX Flow Gate（低技术素养用户屏幕）

任何涉及以下角色/路径/功能的 RN 屏幕设计，**brainstorming 阶段必须在 propose approaches 之前先 invoke `ux-flow` skill**：

- **角色词**：operator、操作员、仓管、warehouse_worker、quality_inspector、质检员
- **路径词**：screens/processing、screens/warehouse、screens/quality-inspector
- **功能词**：报工、入库、出库、盘点、质检、扫码收货

`ux-flow` Phase 1 产出的「UX Flow Analysis」章节是 spec 的强制组成部分，缺失则不进入 writing-plans。

详见 `.agents/skills/ux-flow/SKILL.md`。

---

## Documentation

- [PRD-功能与文件映射-v3.0.md](./docs/prd/PRD-功能与文件映射-v3.0.md)
- [PRD-完整业务流程与界面设计-v5.0.md](./docs/prd/PRD-完整业务流程与界面设计-v5.0.md)
- [QUICK_START.md](./QUICK_START.md)

---

## Troubleshooting

### 健康检查
```bash
ssh root@139.196.165.140 "cat /www/server/panel/vhost/nginx/_upstream_cretas.conf"
ssh root@47.100.235.168 "curl -s http://localhost:10010/api/mobile/health; curl -s http://localhost:10020/api/mobile/health"
ssh root@47.100.235.168 "curl -s http://localhost:8083/health"
lsof -i :10010 -i :10020
```

开发机访问 Java 后端请先读取 upstream，再把本地 `10010` 映射到当前 active 槽；例如 active 为 `10020`：
```bash
ssh -L 10010:localhost:10020 root@47.100.235.168
```

### 缓存问题
```bash
npx expo start --clear
rm -rf node_modules && npm install
```
