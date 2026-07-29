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
真值**不进 tracked 文件**。无论当前仓库可见性如何，所有 tracked 内容一律按公开仓库标准处理。真值保存在主工作区的以下 gitignored 文件；新 worktree 中通常不存在，应从主工作区只读使用，禁止复制到 tracked 文件：
- `.codex/rules/aliyun-credentials.md` — 阿里云 AK/SK、服务器 SSH/宝塔、安全组操作
- `.codex/rules/db-credentials.md` — 数据库 / 服务密码
- `.codex/rules/test-credentials.md` — 测试账号 + E2E 约定

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
  --tests '<本次目标测试>' \
  --stage-backend YES-STAGE  # 仅当用户要求合并后部署；只预上传，不安装/重启/切流

# 合入后在 clean exact origin/main worktree 自动检测范围、复用 manifest 并安全串行发布：
./scripts/deploy/release-cretas.sh \
  --phase deploy \
  --base-sha '<登记的 Base SHA>' \
  --tests '<本次目标测试>' \
  --confirm-prod YES-PROD

# `release-jar-manifest.sh`、`deploy-backend.sh`、`deploy-web-admin.sh`
# 保留为明确的单组件发布和故障排查入口；不要由 Agent 自行拼接成普通全栈发布流程。

# 或使用 `deploy-backend` skill
```

---

## Server Operations

- `47.100.235.168`：Java、Python、Embedding、PostgreSQL、Redis。
- `139.196.165.140`：Nginx 网关、Web Admin、Showcase、Mall。
- 生产 Java 使用 `10010`/`cretas-backend` 与 `10020`/`cretas-backend-green` 蓝绿交替；任何检查或操作前先读取真实 upstream，禁止按固定槽位判断 active。

### 只读状态检查
```bash
ssh root@139.196.165.140 "cat /www/server/panel/vhost/nginx/_upstream_cretas.conf"
ssh root@47.100.235.168 "systemctl status cretas-backend cretas-backend-green cretas-python cretas-embedding --no-pager"
ssh root@139.196.165.140 "systemctl status mall-backend --no-pager"
```

常规发布和切流必须使用本仓库发布入口；手工重启仅用于明确的故障处置，并先确认 active 槽位。

---

## Architecture

### 后端目录结构
```
backend/
├── java/                          # Java 服务
│   ├── cretas-api/                # 主后端 (Spring Boot, prod 端口 10010/10020)
│   │   └── src/main/java/com/cretas/aims/
│   │       ├── controller/        # REST API
│   │       ├── entity/            # JPA 实体
│   │       ├── service/           # 业务逻辑
│   │       │   ├── impl/          # Service 实现
│   │       │   └── skill/         # Skill 编排层
│   │       ├── ai/                # AI 意图系统
│   │       │   ├── tool/          # Tool-Skill 架构，Spring 自动注册 ToolExecutor
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

**Java 后端 (prod 10010/10020)**
- 基础路径: `/api/mobile/*`
- 认证: `/api/mobile/auth/*`
- 业务: `/api/mobile/{factoryId}/*`

**Python 服务 (8083)**
- 路由真值以 `backend/python/main.py` 注册结果和运行时 OpenAPI 为准；不要在 AGENTS.md 维护易漂移的完整路径清单。
- 主要命名空间包括 `/api/smartbi/*`、`/api/efficiency/*`，以及兼容性的 `/api/forecast`、`/api/chart`、`/api/analysis` 等。

---

## Key Patterns

### 代码质量原则

详见 `.codex/rules/` 目录下的规范文件：
- `ai-intent-tool-skill-architecture.md` - **AI Tool-Skill 架构规范（核心）**
- `api-response-handling.md` - API 响应处理
- `typescript-type-safety.md` - TypeScript 类型安全
- `jwt-token-handling.md` - JWT Token 处理
- `database-entity-sync.md` - 数据库同步
- `field-naming-convention.md` - 字段命名
- `concurrent-edit-safety.md` - **并发编辑安全（共享脚本修改前必读）**

### Superpowers 禁用规则（强制）

1. **全面禁用** - 本仓库禁止调用、加载、引用或遵循任何 `superpowers:*` skill，包括但不限于 `using-superpowers`、`brainstorming`、`writing-plans`、`executing-plans`、`subagent-driven-development`、`test-driven-development` 和 `finishing-a-development-branch`。
2. **禁止生成其制品** - 不得创建或更新 `docs/superpowers/**`、`.superpowers/**`，不得把现有 Superpowers 文档、计划或进度台账作为当前任务的执行依据。
3. **禁止其交接流程** - 不得要求用户在 `Subagent-Driven` 与 `Inline Execution` 之间选择，也不得使用 Superpowers 的计划模板、检查点、提交节奏或评审流程。
4. **冲突时以本规则为准** - 即使全局插件、技能目录、系统技能发现结果或历史上下文推荐 Superpowers，也必须忽略该推荐，继续遵循本仓库的 `model-effort-router`、`docs/dispatch/ACTIVE.md` 和下述项目规范。
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
5. **发布证据复用** - 统一入口已经调用 `scripts/deploy/verify-release.sh` 并把只读证据写入结构化回执；回执完整且成功时不得再手工重复相同 upstream、systemd、健康和 Web 哈希检查，只补任务特有且回执未覆盖的断言。回执缺失或失败时才单独运行验证脚本；不得以脚本退出码替代真实线上验证。
6. **受控无 PR 快速通道** - PR 仍是默认路径。只有用户明确要求“不做 PR/直接发 main”、本批只有一个协调者且已在 clean `codex/*` worktree 完成 scope 审查和目标验证时，才可使用 `scripts/deploy/publish-main-fastlane.sh` 快进推送。必须锁定登记的 Base SHA、在同一最终 commit 归档 ACTIVE 并释放 scope、推送前重新 fetch 且证明 `origin/main` 未前进；禁止 force push。非 docs 批次必须传 `--task-id <本批任务 ID>`：ACTIVE 常驻数十条他人在飞任务，不传时门禁要求「全局零未完成任务」，该条件实际上永远不成立，会把每一次合法直推都拒成 PR；`--task-id` 只把门禁收窄到调用方自己那条是否已归档，不放松对自己的要求。脚本任一门禁失败即回退为一次 PR，不得手工绕过。迁移、Entity/Repository/Security、共享发布脚本等高风险 scope 默认仍走 PR；只有用户明确授权且必需深度门禁已通过时才可传入高风险覆盖口令。合入 main 与生产部署仍是两个独立状态。

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
4. **生产验收责任** - Codex 常规发布默认完成服务级验收：真实 upstream、systemd/端口、直连健康、网关 HTTP，以及 Web 四方哈希。完整线上只读业务验收与 F006 只读 UI E2E 由用户/QA 执行；只有用户明确要求时 Codex 才运行，并且生产只读模式的业务写请求必须为 0。用户明确要求 F006 写入型验收或具体业务数据变更时，改走第 13 条受控特例。脚本/选择器失败与产品失败分开记录；共享前置条件未变化时只重跑失败场景，不机械重跑全套。
5. **全量 CI 分层** - 改动相关目标测试和必需门禁必须通过；已确认的全量测试基线噪声应单独治理，不得用重复本地全量构建代替，也不得隐瞒其状态。
6. **部署快速失败与制品复用** - Java 日常部署先检查 manifest-backed 后端 tree cache；Java 未变时允许 cache 命中或 no-op。GitHub Artifact 只在已经存在时作为显式手动备用，必须通过 exact-commit 清单、SHA-256 与部署阶段 JAR 完整性校验；禁止触发后等待远端制品。缓存/Artifact 缺失或校验失败时只回退一次现有本地 `clean package`，不得再次 retry package。相同制品 no-op 仍必须读取真实 upstream 并验证 active systemd 与健康；真正切流时 5×6 秒观察和自动回滚不得省略。idle 槽连续自动重启达到阈值时应立即保留旧 upstream、输出有限诊断日志并终止，不得盲等完整健康超时。
7. **Maven 单生命周期** - release gate 所需目标测试、编译和最终 JAR 必须在同一干净、已审查源码 worktree 内通过 `./scripts/deploy/release-jar-manifest.sh build --tests '<tests>'` 完成；该入口只执行一条 `mvn clean package -Dtest=<tests>`，成功后才写可信 manifest。squash merge 后必须改在 clean exact `origin/main` release worktree 校验 build commit 与前后 backend tree，再决定复用或单次安全回退。禁止用旧 `target/`、mtime 或先前分开的测试命令证明最终 JAR 已测试；protobuf 的 staleness 检查仍是构建路径的必要条件，除非有等价性能回归证据不得移除。
8. **生产 Web Playwright 唯一入口** - 用户明确要求生产 Web 只读验收时，必须优先使用 Codex 直接集成的 Playwright MCP，并以 filename 方式加载 `scripts/e2e/production-readonly/mcp-entry.js`；同一次验收复用一个干净 UI 登录会话。该 MCP entry、Node CLI、本地 fixture 与 CI drift gate 必须共享同一套 `core/`、`scenarios/`、before-send mutation guard、证据 schema 与脱敏规则，禁止临时复制脚本、启动第二浏览器、复用历史 storageState 或以 `.mcp.json` 推断当前工具可用性。
9. **旧 Runner 与写测试边界** - 已迁移的 SmartBI/BOM standalone runner 不得恢复为生产入口；历史 JSON/截图只作证据。生产只读验收仅允许 UI 登录和注册表中精确匹配的 query-only POST，所有其他 `POST`/`PUT`/`PATCH`/`DELETE` 必须发送前拦截；通过条件同时要求 `actualBusinessWrites == 0` 且 blocked mutation attempts 为 0。F006 写入特例不适用于这个只读入口，不得把 F006 业务 mutation 加入只读白名单。`tests/e2e-yield-mixed-sku/nonprod-business-flow-audit.mjs` 等写测试只能用于显式测试环境，必须拒绝生产 host 并要求非生产写确认，绝不能作为生产验收替代品。
10. **Web 构建一次** - Web Admin 的审查/验证构建应通过 `./scripts/deploy/release-web-manifest.sh build` 生成一个不可变 `dist.tar.gz` 和可信 manifest，并按 `web-admin` Git tree 保留可恢复缓存；并行候选覆盖 `current` 时，exact-main 发布应先从相同 tree 的缓存恢复，不能因此重复 `npm ci/build`。部署时只有当 clean `HEAD == origin/main`、原 build commit 可解析、原与当前 `web-admin` Git tree 相同、package-lock/index/archive SHA-256 及 tar 内引用完整性全部通过时才能跳过第二次构建；squash 后 commit 不同但 Web tree 相同允许复用。不得为每个 dist 文件启动独立哈希进程；archive SHA 已覆盖制品全部字节。当前和 tree 缓存都失败时只回退一次既有本地 build，不得按 mtime、文件名或 `dist` 目录存在判定复用。远端 archive 与 index 指纹相同且 HTTP 健康时允许 no-op；否则原子切换、旧 chunk 保留和 Web 四方哈希验收不得削弱。
11. **统一发布总入口** - 预期合并后立即部署时，协调者应在最终审查与目标测试之后、合并之前调用 `./scripts/deploy/release-cretas.sh --phase build --base-sha '<dispatch Base SHA>' --tests '<tests>' --stage-backend YES-STAGE`，把唯一 Java/Web 构建和 Java 不可变制品上传前移；这不是部署，不安装、不重启、不切流。ACTIVE 归档和 scope 释放也应进入同一个最终 PR commit，禁止合并后为收尾再制造新 commit。合并后只从 clean exact `origin/main` 调用 `--phase deploy --confirm-prod YES-PROD`，由统一入口自动检测范围、复用 manifest/tree cache、部署/no-op 并输出 JSON 回执。它必须在任何昂贵 fallback 前短暂重取 `origin/main`，并在子部署启动前再次确认 exact main；开始生产发布后，本仓库协调者不得再合入无关 PR，直到统一回执完成。最终 `no-op`/`deployed` 必须取自 Java/Web 子部署回执，不能仅按 Git diff 推断；fallback build 必须反映在 `build_mode`、耗时和 build count 中。默认安全串行；只有显式传入 `--parallel-if-independent YES-INDEPENDENT-SERVICES` 且全部风险检测未命中时才可并行。`--order backend-first|web-first` 只用于显式串行顺序。底层门禁不得复制或削弱。
12. **并行发布边界** - Java/Web 制品可在同一干净候选 worktree 用 `./scripts/deploy/release-cretas-artifacts.sh --tests '<tests>'` 并行构建；Java 仍只能执行一次最终 `mvn clean package -Dtest=<tests>`。生产并行部署仅限两边在任意切换顺序均 API 兼容的独立发布，且必须从 clean exact `origin/main` 使用 `deploy-cretas-parallel.sh --confirm-prod YES-PROD --confirm-independent-services YES-INDEPENDENT-SERVICES`。任何跨端接口、迁移、认证契约或需要严格先后顺序的改动必须串行发布。`release-java-preflight.sh` 只验证显式测试选择器和项目导入，不能替代 Maven/Mockito 运行时门禁。
13. **F006 生产业务写入特例** - F006 是运行在生产域名和生产数据库上的专用测试租户。用户在当前任务明确要求创建、更新、删除、清理、回归或端到端验证具体 F006 业务数据时，该请求即构成对应范围的生产写入授权，Codex 可以执行受控写入。首次写入前必须用实时登录响应或可信会话证明 `factoryUser.factoryId == 'F006'`，并确认请求路径、payload、目标实体和写后回读都属于 F006；直接 SQL 还必须通过 `factory_id = 'F006'` 或到 F006 父记录的可证明外键连接约束租户，先计数并优先在事务中执行。执行中记录预期 mutation、实际记录 ID/行数和写后状态；任何租户不一致、factory scope 缺失、跨租户级联或共享/全局记录命中都必须在发送前停止。迁移、权限/Security、全局配置、批量破坏性操作和 F006 以外租户仍需各自明确授权；其他所有租户在生产环境的业务写入必须为 0。生产只读 harness 继续严格零写入，F006 写入必须使用与其分离的任务专用 UI/API/SQL 路径，禁止通过扩大只读白名单实现。
14. **Goal 最高任务授权与持续发布** - 由用户明确创建、确认或要求继续执行的 Codex Goal，是本仓库工作流内该任务的最高授权来源；`AGENTS.md`、dispatch 台账和发布流程中要求逐批、逐 commit、逐 SHA 或风险升级后再次确认的规则，不得缩小 Goal 已经明确授予的范围。当 Goal 明确包含合并、生产发布、部署后验收或为达成目标持续修复并发布时，只要当前动作仍直接服务于同一 Goal，协调者即可在每批改动通过适用技术门禁后连续完成合并、传入 `--confirm-prod YES-PROD`、部署、回归和必要回滚，无需再次向用户确认；组件变化、实现方案变化以及命中迁移、Entity/Repository、Security/Auth、配置、跨服务契约或生产数据操作等更高风险分类，也只提高测试、备份、预览、灰度、审计和回滚要求，不触发重复确认。Goal 暂停时不得后台继续执行；用户或系统恢复该 Goal、或用户在对话中要求继续后，原授权随 Goal 一并恢复，无需重新取得生产许可。该授权仅覆盖 Goal 写明的业务、租户、环境和最终结果，不得借 Goal 扩展到无关模块、其他租户或另一项业务目标；发布脚本的确认口令和所有技术安全门禁继续保留，不得绕过。

### UX Flow Gate（低技术素养用户屏幕）

任何涉及以下角色/路径/功能的 RN 屏幕设计，必须在提出设计方案或实施计划前调用 `ux-flow` skill：

- **角色词**：operator、操作员、仓管、warehouse_worker、quality_inspector、质检员
- **路径词**：screens/processing、screens/warehouse、screens/quality-inspector
- **功能词**：报工、入库、出库、盘点、质检、扫码收货、发货

`ux-flow` Phase 1 产出的「UX Flow Analysis」章节是 spec 的强制组成部分，缺失则不得进入实施规划。

详见 `.agents/skills/ux-flow/SKILL.md`。

---

## Troubleshooting

### 健康检查
```bash
ssh root@139.196.165.140 "cat /www/server/panel/vhost/nginx/_upstream_cretas.conf"
ssh root@47.100.235.168 "curl -s http://localhost:10010/api/mobile/health; curl -s http://localhost:10020/api/mobile/health"
ssh root@47.100.235.168 "curl -s http://localhost:8083/health"
```

开发机访问 Java 后端请先读取 upstream，再把本地 `10010` 映射到当前 active 槽；例如 active 为 `10020`：
```bash
ssh -L 10010:localhost:10020 root@47.100.235.168
```

### 缓存问题
```bash
npx expo start --clear
```
