**最高优先级模型与 Effort 门禁：执行任何新任务前，必须先调用 `model-effort-router` skill，根据任务的实际复杂度、风险和范围向用户推荐具体模型与 effort；当前设置匹配时告知后直接执行，不匹配或无法确认时等待用户切换并明确确认。**

# AGENTS.md

## Mandatory Model and Effort Gate

- 每个新任务必须按 `.agents/skills/model-effort-router/SKILL.md` 单独判断；不能沿用上一个任务的设置，也不能把仓库默认值视为已确认。
- 当前设置匹配时要明确说明“无需切换”，随后直接开始执行，不再要求用户重复确认；设置不匹配或无法观察时必须要求用户完成切换并确认。
- 当前设置不匹配或无法确认时，开始编辑、测试、长时间研究、状态变更或部署前必须取得确认；确认前只允许路由所需的最小只读检查。
- 任务范围、风险或工作类型发生实质变化时，必须暂停并重新路由。
- 如推荐外部 Claude Fable 5，必须在同一回复直接附上针对当前任务写好的、可完整粘贴到 Claude Code 的 Prompt 与回交要求；禁止只给出模型/effort 建议、Prompt 大纲或要求用户另行索取 Prompt。

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
# 正常 Java/Web 发布优先使用统一入口；Base SHA 必须来自 dispatch 登记
# 候选 worktree 只构建可信制品：
./scripts/deploy/release-cretas.sh \
  --phase build \
  --base-sha '<登记的 Base SHA>' \
  --tests '<本次目标测试>'

# 合入后在 clean exact origin/main worktree 自动检测范围、复用 manifest 并安全串行发布：
./scripts/deploy/release-cretas.sh \
  --phase deploy \
  --base-sha '<登记的 Base SHA>' \
  --tests '<本次目标测试>' \
  --confirm-prod YES-PROD

# `release-jar-manifest.sh`、`deploy-backend.sh`、`deploy-web-admin.sh`
# 保留为明确的单组件发布和故障排查入口；不要由 Agent 自行拼接成普通全栈发布流程。

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

### Superpowers 禁用规则（强制）

1. **全面禁用** - 本仓库禁止调用、加载、引用或遵循任何 `superpowers:*` skill，包括但不限于 `using-superpowers`、`brainstorming`、`writing-plans`、`executing-plans`、`subagent-driven-development`、`test-driven-development` 和 `finishing-a-development-branch`。
2. **禁止生成其制品** - 不得创建或更新 `docs/superpowers/**`、`.superpowers/**`，不得把现有 Superpowers 文档、计划或进度台账作为当前任务的执行依据。
3. **禁止其交接流程** - 不得要求用户在 `Subagent-Driven` 与 `Inline Execution` 之间选择，也不得使用 Superpowers 的计划模板、检查点、提交节奏或评审流程。
4. **冲突时以本规则为准** - 即使全局插件、技能目录、系统技能发现结果或历史上下文推荐 Superpowers，也必须忽略该推荐，继续遵循本仓库的 Thin-Opus Organizer、`docs/dispatch/ACTIVE.md` 和下述项目规范。
5. **不得隐式替代** - 不得以别名、复制模板或等价话术变相执行 Superpowers 工作流；需要规划、调试、测试、评审或子代理协作时，只使用本仓库已定义的对应流程。

### 核心原则
1. **禁止降级处理** - 不返回假数据，明确显示错误
2. **类型安全** - 避免 `as any`，使用明确类型
3. **统一响应格式** - `{ success, data, message }`
4. **Tool-Skill Only** - AI 意图处理只用 Tool/Skill，禁止创建 IntentHandler（已废弃）

### 执行效率与验证

1. **避免过度审计** - 先按用户目标确定最小充分的检查范围；只有安全、生产发布、数据一致性、权限或用户明确要求时，才扩大为深度审计。
2. **同批需求一次完成** - 用户在同一轮提出的多个兼容修改，应先完成全部已明确且可安全并行的改动，再统一进行构建、测试和验证；不要每完成一小项就重复完整编译。
3. **验证按风险分层** - 编辑过程中优先使用静态检查、目标测试或局部检查；最终只读代码审查应安排在最后一次目标构建/测试之前，审查未引入代码修改时不得重复完整验证。在合并前执行一次与改动范围相称的最终构建/测试；最终验证不可省略，但不得无理由重复执行。
4. **例外要说明** - 若中途编译是为解除阻塞、验证高风险接口/迁移、排查失败，或用户明确要求增量验证，应说明原因后执行。
5. **Repository 查询启动门禁** - 修改 Spring Data Repository 方法、`@Query`/JPQL/HQL、Entity 字段或枚举映射时，必须新增或更新真实 JPA Context 测试（优先命名为 `*RepositoryQueryValidationTest`）并运行通过；Mockito、纯编译、`-DskipTests` 或仅 Service 单测不能证明查询可在 Hibernate 启动期解析。
6. **禁止绕过未完成的 JPA 门禁** - 涉及上述范围的 PR，CI 中 `JPA repository query startup gate` 必须完成并通过后才可合并。CI 因基础设施异常无法完成时，只有在同一 commit 本地运行等价真实 JPA Context 测试并取得成功证据后，协调者才可说明原因并请求/执行管理员合并；没有该证据不得以紧急发布为由绕过。

### 开发流程去浪费

1. **Worktree 生命周期** - 代码改动前先检查 `git worktree list` 和 `docs/dispatch/ACTIVE.md`；只读、调研、纯文档任务不新建 worktree。默认不自动删除；用户明确要求清理时，仅删除路径已核验、工作区干净且已由祖先关系、补丁等价或 merged PR 证明进入 `origin/main` 的 worktree。
2. **窄范围排查** - 先读取当前 handoff、目标模块和已有测试 helper；无命中才扩大搜索。禁止默认全仓递归扫描、整段 CI 日志倾倒或重建已有 E2E 脚手架。
3. **真值与交接** - `origin/main` 和精简 ACTIVE 是当前状态真值；本地旧文档、旧会话和 feature worktree 不可覆盖它们。完成项进入带日期归档，ACTIVE 不累积历史。
4. **测试与浏览器纪律** - 同一 worktree 的 Maven 目标测试顺序执行；优先复用 helper。浏览器/设备验证须隔离配置并限制并发；没有文件、截图或断言进展时停止或切到更快的 Expo/Web 路径。
5. **发布证据复用** - 发布后优先运行 `scripts/deploy/verify-release.sh` 汇总只读证据，再补充任务特有的业务断言；不得以脚本退出码替代真实线上验证。
6. **受控无 PR 快速通道** - PR 仍是默认路径。只有用户明确要求“不做 PR/直接发 main”、本批只有一个协调者且已在 clean `codex/*` worktree 完成 scope 审查和目标验证时，才可使用 `scripts/deploy/publish-main-fastlane.sh` 快进推送。必须锁定登记的 Base SHA、在同一最终 commit 归档 ACTIVE 并释放 scope、推送前重新 fetch 且证明 `origin/main` 未前进；禁止 force push。脚本任一门禁失败即回退为一次 PR，不得手工绕过。迁移、Entity/Repository/Security、共享发布脚本等高风险 scope 默认仍走 PR；只有用户明确授权且必需深度门禁已通过时才可传入高风险覆盖口令。合入 main 与生产部署仍是两个独立状态。

### 子代理协作约束

1. **单一台账写者** - 只有当前协调者可以修改 `docs/dispatch/ACTIVE.md`；子代理不得直接修改台账，只能回传结构化回执。
2. **派工前置条件** - 每个代码任务必须先登记唯一任务 ID、`Base SHA`、允许修改的文件/目录 scope 锁、Owner 与验收命令；没有 scope 锁不得启动代码子代理。
3. **固定状态机** - 任务状态只使用 `queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。合并完成时，协调者必须在同一收尾中将任务归档并释放 scope 锁。
4. **结构化回执** - 子代理完成或阻塞时必须返回：任务 ID、base SHA、实际修改文件、验证命令与结果、commit/PR、阻塞原因、下一动作。缺少这些字段不得进入 review。
5. **WIP 上限** - 同一仓库默认最多同时运行 2 个代码执行子代理和 1 个只读/测试子代理；超过上限必须先完成、阻塞或回收现有任务。
6. **临时扩容例外** - 当任务之间具备明确且互不重叠的文件/目录 scope，不修改共享文件，且已登记任务 ID、Base SHA、Owner 与验收命令时，协调者可临时将上限提高到最多 4 个代码执行子代理和 1 个只读/测试子代理。扩容必须在 `docs/dispatch/ACTIVE.md` 记录理由、参与任务与释放条件；出现 scope 重叠、共享文件竞争或合并冲突风险时立即恢复默认上限。
7. **验证责任分层** - 执行子代理只跑改动范围内的目标测试；协调者核对 scope、diff、commit/PR 和证据；只有权限、迁移、生产、数据一致性或跨模块高风险任务才追加深度终审。

### 发布与 E2E 去重复

1. **唯一发布构建与可信 manifest** - 后续发布必须从干净且 `HEAD == origin/main` 的 exact release worktree 执行。同一 `backend/java/cretas-api` Git tree 的目标测试与最终 release JAR 必须合并为唯一一次 `mvn clean package -Dtest=<tests>` 生命周期，成功后生成可信 manifest；不得先单跑目标测试再重复 package。manifest/制品复用前必须证明记录的 build commit 可由 Git 解析，且该 commit 的后端 tree、manifest 记录的后端 tree、当前 `origin/main` 后端 tree 三者一致，同时通过 SHA-256、JAR 完整性和当前 worktree clean 校验。squash 后 commit 不同但后端 tree 完全相同可复用；任一校验失败只回退一次本地 `clean package`。生产当前 JAR 的 MD5、真实 upstream、active systemd 与直连健康均匹配时，Java 未变化可按后端 tree cache 判定安全 no-op。
2. **蓝绿槽位交替** - prod Java 的 `10010` 与 `10020` 都是可用槽位，会交替成为 active。部署前必须读取 `139.196.165.140:/www/server/panel/vhost/nginx/_upstream_cretas.conf`，禁止假设某个槽永久停用。
3. **问题前移** - SKU、Workflow、报工单位或历史快照改动，合并前必须覆盖生产形态数据，包括旧 `g/box/case`、中文基本单位和多包装规格。
4. **生产验收责任** - Codex 常规发布默认完成服务级验收：真实 upstream、systemd/端口、直连健康、网关 HTTP，以及 Web 四方哈希。完整线上业务验收与 F006 UI E2E 由用户/QA 执行；只有用户明确要求时 Codex 才运行，并且业务写请求必须为 0。脚本/选择器失败与产品失败分开记录；共享前置条件未变化时只重跑失败场景，不机械重跑全套。
5. **全量 CI 分层** - 改动相关目标测试和必需门禁必须通过；已确认的全量测试基线噪声应单独治理，不得用重复本地全量构建代替，也不得隐瞒其状态。
6. **部署快速失败与制品复用** - Java 日常部署先检查 manifest-backed 后端 tree cache；Java 未变时允许 cache 命中或 no-op。GitHub Artifact 只在已经存在时作为显式手动备用，必须通过 exact-commit 清单、SHA-256 与部署阶段 JAR 完整性校验；禁止触发后等待远端制品。缓存/Artifact 缺失或校验失败时只回退一次现有本地 `clean package`，不得再次 retry package。相同制品 no-op 仍必须读取真实 upstream 并验证 active systemd 与健康；真正切流时 5×6 秒观察和自动回滚不得省略。idle 槽连续自动重启达到阈值时应立即保留旧 upstream、输出有限诊断日志并终止，不得盲等完整健康超时。
7. **Maven 单生命周期** - release gate 所需目标测试、编译和最终 JAR 必须在同一干净、已审查源码 worktree 内通过 `./scripts/deploy/release-jar-manifest.sh build --tests '<tests>'` 完成；该入口只执行一条 `mvn clean package -Dtest=<tests>`，成功后才写可信 manifest。squash merge 后必须改在 clean exact `origin/main` release worktree 校验 build commit 与前后 backend tree，再决定复用或单次安全回退。禁止用旧 `target/`、mtime 或先前分开的测试命令证明最终 JAR 已测试；protobuf 的 staleness 检查仍是构建路径的必要条件，除非有等价性能回归证据不得移除。
8. **生产 Web Playwright 唯一入口** - 用户明确要求生产 Web 只读验收时，必须优先使用 Codex 直接集成的 Playwright MCP，并以 filename 方式加载 `scripts/e2e/production-readonly/mcp-entry.js`；同一次验收复用一个干净 UI 登录会话。该 MCP entry、Node CLI、本地 fixture 与 CI drift gate 必须共享同一套 `core/`、`scenarios/`、before-send mutation guard、证据 schema 与脱敏规则，禁止临时复制脚本、启动第二浏览器、复用历史 storageState 或以 `.mcp.json` 推断当前工具可用性。
9. **旧 Runner 与写测试边界** - 已迁移的 SmartBI/BOM standalone runner 不得恢复为生产入口；历史 JSON/截图只作证据。生产只读验收仅允许 UI 登录和注册表中精确匹配的 query-only POST，所有其他 `POST`/`PUT`/`PATCH`/`DELETE` 必须发送前拦截；通过条件同时要求 `actualBusinessWrites == 0` 且 blocked mutation attempts 为 0。`tests/e2e-yield-mixed-sku/nonprod-business-flow-audit.mjs` 等写测试只能用于显式测试环境，必须拒绝生产 host 并要求非生产写确认，绝不能作为生产验收替代品。
10. **Web 构建一次** - Web Admin 的审查/验证构建应通过 `./scripts/deploy/release-web-manifest.sh build` 生成一个不可变 `dist.tar.gz` 和可信 manifest。部署时只有当 clean `HEAD == origin/main`、原 build commit 可解析、原与当前 `web-admin` Git tree 相同、package-lock/index/archive SHA-256 及 tar 内引用完整性全部通过时才能跳过第二次 `npm ci/build`；squash 后 commit 不同但 Web tree 相同允许复用。不得为每个 dist 文件启动独立哈希进程；archive SHA 已覆盖制品全部字节。任一校验失败只回退一次既有本地 build，不得按 mtime、文件名或 `dist` 目录存在判定复用。远端 archive 与 index 指纹相同且 HTTP 健康时允许 no-op；否则原子切换、旧 chunk 保留和 Web 四方哈希验收不得削弱。
11. **统一发布总入口** - 正常 Java/Web 发布优先调用 `./scripts/deploy/release-cretas.sh --base-sha '<dispatch Base SHA>' --tests '<tests>' --confirm-prod YES-PROD`，由它自动检测 `backend/java/cretas-api` 与 `web-admin` 的变更、选择可信构建/部署/no-op，并输出统一 JSON 回执。统一回执的最终 `no-op`/`deployed` 必须取自 Java/Web 子部署回执，不能仅按 Git diff 推断；部署阶段发生的单次 manifest fallback build 必须反映在 `build_mode`、耗时和 build count 中。默认必须安全串行；只有调用者显式传入 `--parallel-if-independent YES-INDEPENDENT-SERVICES` 且迁移、Entity、Repository/查询、Security/Auth、Controller/DTO/API 契约、配置/环境契约和显式顺序检测均未命中时才可调用现有并行部署。`--order backend-first|web-first` 只用于显式串行顺序。底层 manifest、单组件 deploy 和并行 wrapper 继续负责原有完整性、蓝绿、原子交换、健康、观察与回滚，不得在总入口复制或削弱这些门禁。
12. **并行发布边界** - Java/Web 制品可在同一干净候选 worktree 用 `./scripts/deploy/release-cretas-artifacts.sh --tests '<tests>'` 并行构建；Java 仍只能执行一次最终 `mvn clean package -Dtest=<tests>`。生产并行部署仅限两边在任意切换顺序均 API 兼容的独立发布，且必须从 clean exact `origin/main` 使用 `deploy-cretas-parallel.sh --confirm-prod YES-PROD --confirm-independent-services YES-INDEPENDENT-SERVICES`。任何跨端接口、迁移、认证契约或需要严格先后顺序的改动必须串行发布。`release-java-preflight.sh` 只验证显式测试选择器和项目导入，不能替代 Maven/Mockito 运行时门禁。

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
