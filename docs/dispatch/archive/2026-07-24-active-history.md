# 2026-07-24 调度归档

## `FEATURE-LABEL-QC-COLD-START-001` / `BUG-LABEL-QC-OSS-DOWNLOAD-001` — `merged`

- Owner: `/root`
- Base SHA: `4c265eb920cf97e1d2d73ec2eb3581d4aa90bb72` / `4d32a232735b2f35e1d78b933e9ffacc12c0c0ed`
- 合入：主功能 PR #1744 合并为 `4d32a232735b2f35e1d78b933e9ffacc12c0c0ed`；OSS 下载热修 PR #1745 合并为 `bf5753999823aab508d5e495dbc36822e4773d77`。
- 发布：Java 最终从 clean exact `origin/main` 复用已测试 JAR `0b709ea4757e525f88134b339cab74035b322ccd2857499d5725802822b90903`，蓝绿切至 blue/10010，5/5 轮 nginx 200 与 systemd active；Web HTTP 200 且四方哈希 `4dda6733e0b7e8701b8a725479f3a072bd266006beee4d69549a6aa0aae4bf74`；Python 8083 本机 health/数据库连接通过；RN Android OTA `1.0.1/production/1784901301246` 已注册。
- 故障闭环：生产首轮任务证明 Apache `RestTemplate` 在 Python 调用前下载 OSS 签名图片失败；改用带连接/请求超时、重定向与 10 MB 上限的 JDK 21 `HttpClient`，`LabelQcAnalysisClientTest` 1/1 通过，统一 release Java build 与 manifest 校验通过。
- F006 写测：实时登录证明 `factoryId=F006` 后，仅对任务 `0d86db18-435a-49bf-aa2b-ba0a88fdf6f2` 执行任务/附件/照片、重试与人工审核写入；模型 `qwen3-vl-plus-2025-12-19`、Prompt `label-presence-high-recall-v1` 返回 8 个保守候选，人工逐一纠正并补充 1 个 `MISSING_WHITE_LABEL` 真值框 `[0.0,0.61,0.32,0.79]`，最终状态 `REVIEWED`、缺陷数 1、训练导出命中 1 张/9 条最终标注。
- UI 验收：生产 headed 浏览器以 `f006_admin` 打开 `/quality/label-qc`，统计显示已审核 1，队列和详情显示目标批次 `CODEX-LABEL-QC-E2E-20260724-220214`、原图、8 个 AI 纠正项及 1 个人工缺白标项。
- 边界：默认 E2E 映射曾返回 F001，已在任何业务写入前停止；本轮没有 F006 以外租户业务写入。Scope 锁已释放。

## `DOC-F006-SOP-CURRENT-FLOW-20260724` — `merged`

- Owner: `/root`
- Base SHA: `db2d666f946de0e74b94f5c60aa328e874bbd581`
- 结果：工厂 SOP 静态页、RAG 来源与 AI Assist 确定性回答已同步到当前生产链路，覆盖 BOM 创建时自动固定唯一 Workflow 草稿修订、只读工艺来源/DAG 工序、稳定 ID 显式升级与历史不漂移。
- 报工与状态机：SOP 按“投入 → 工序执行 → 产出 → 确认提交”组织；明确草稿零扣库/零正式成本、正式报工对原料/调料/包材 FEFO 分配并计入实际成本、计划创建幂等、取消/停产可审计、小结入库与库存流水一致。
- UI 验收：双出成率总览表头与内容线对线，保留鼠标/键盘升降序及表头漏斗筛选；正式报工为唯一主操作，时间/人数与产出数量分区。
- 验证：`python -m pytest tests/test_food_kb_manual_chat_sop_contract.py -q` 为 `10 passed`；Python 编译、36 个 RAG 分块、HTML 26 sections / 14 tables / 134 steps / 0 duplicate IDs、关键文案和 `git diff --check` 均通过。
- 发布边界：只允许 PR 合入后从 clean exact `origin/main` 更新 `/lsmsop/`、Python 服务与 SOP RAG；发布前必须确认相关 Codex 任务不存在等待/执行项。
- Scope 锁已释放。

## `AUTO-SOP-RAG-SYNC-20260724` — `merged`

- Owner: `/root`
- Base SHA: `19bed09f8f78474c78ef84164bac44621535d458`
- 合入：PR #1732 / `cfe2b63cd7688da2e3581081eb16e86e3674ff1e` 更新 SOP、RAG 来源和系统回答约束；PR #1733 / `99f1d2301a894b308f0daf54038d25ff231016c4` 将关键 BOM/Workflow 发布门禁改为保留 RAG 来源的代码级确定性答案。
- 发布：从 clean exact `origin/main` 运行 `scripts/deploy/deploy-smartbi-python.sh --env prod`；生产 migration `0 pending / 120 already applied`，依赖缓存命中，import smoke 和 `8083/health` 通过。
- 验收：`python -m pytest tests/test_food_kb_manual_chat_sop_contract.py -q` 为 `10 passed`；3 种生产真实问法均为 HTTP 200、命中完整 `Workflow 草稿 → BOM 激活 → Workflow 发布启用` 顺序、ACTIVE BOM 门禁与双状态验收，禁词计数 0，每次返回 8 个当前 SOP 来源。
- 页面与知识库：`/aiassist.html` SHA-256 为 `a7000b8dd425b355b0b2dafb4775e6cce9bc9c066b9d4fa1bd5058053b837524`；`/lsmsop/` SHA-256 为 `9d9914aab5a76c07413a8d4e140f4e860c9ce7f4632a835d086e37bb7bf501b8`；RAG 为 36 个正式块且无 `.NEW` 临时块。
- 边界：生产业务写入为 0。测试 Python 未重启，原因是服务器缺少 `/www/wwwroot/cretas/.env.test`；测试库已由标准 runner 应用 34 个历史待执行 migration，需另项治理测试环境配置漂移。
- Scope 锁已释放。

## `PERF-RELEASE-LATENCY-20260724` — `review`

- Owner: `/root`
- Base SHA: `f61e94599bc62df0ca826940a24e1ea1ba4287fd`
- 结果：统一候选 build 支持显式 `--stage-backend YES-STAGE`，在合并前把已验证 Java JAR 上传到不可变 SHA-256 缓存，但不安装、不重启、不切流；Web release archive 按精确 `web-admin` Git tree 保留并可 A/B/A 恢复，避免并行候选覆盖 `current` 后重复 `npm ci/build`。
- 稳定性：昂贵 fallback 前增加短 exact-main freshness guard，制品校验/回退后、任何 child deploy 前再次 fetch 并确认 `HEAD == origin/main` 与 clean worktree；主线漂移时 child deploy 调用数为 0。
- 流程：AGENTS 与 deploy skill 要求预期合并后部署时前置唯一构建/Java 预热，生产发布窗口不再合入无关 PR；统一成功回执已含服务级验证时不重复 raw SSH，只补任务特有断言。JPA/Flyway、蓝绿 5×6 秒观察、Web 四方哈希与自动回滚门禁均保留。
- 验证：`test-release-web-manifest.sh` 通过（A/B/A tree cache）；`test-release-cretas.sh` 26 个场景通过；`test-web-admin-deploy-acceleration.sh` 已按 tree cache 语义更新 fixture 并通过；`test-release-pipeline-acceleration.sh`、`test-release-preflight.sh`、`test-deploy-web-admin-preflight.sh`、全部修改脚本 `bash -n` 与 `git diff --check` 通过。
- 边界：只创建 PR，`NOT_DEPLOYED`；没有执行生产制品 staging、服务重启、切流或业务数据写入。
- Scope 锁已释放。

## `FEATURE-WORKFLOW-BOM-AUTO-BINDING-DAG-001` — `merged`

- Owner: `/root`
- Base SHA: `19bed09f8f78474c78ef84164bac44621535d458`
- 结果：BOM 首次创建自动固定唯一完整 Workflow DRAFT revision；DAG 按目标终端精确回溯；BOM-only 克隆保持历史 snapshot；多投入、多产出、稳定替代料槽、共享/部分共享/产出专属成本和副产品 NRV 抵扣形成一致模型。
- 风险关闭：三产出以上的部分共享使用稳定终端 nodeId 集合与 `cost_scope_key` 精确归属；BY_PRODUCT 分摊比例固定为 0，净值仅抵扣实际共享路径，缺价、负成本、无 MAIN、比例或单位不完整均失败关闭。
- UI：普通用户不再选择 Workflow revision；工艺来源只读，DAG 工序锁定；新增桌面表格与移动卡片式产出成本配置，价格权限不足时 NRV 字段脱敏。
- 验证：后端单一 release lifecycle 11 suites / 91 tests 全通过，包含真实 JPA Context；Web 目标测试 3 files / 24 tests、`vue-tsc` 与可信 Web build 通过；桌面/移动本地 Playwright 无横向溢出且 console/page errors 为 0；secret gate 与 `git diff --check` 通过。
- 实现：commits `a0ac271f8eac53d2911b1bfad8a10074522f7b8e`、`fa4d3f3fa640d3b819cbec4dbf87da8e58c114bd`；PR [#1731](https://github.com/Stevenjxie/cretas/pull/1731)。
- 发布：用户已明确授权合入后从 clean exact `origin/main` 统一部署 Java 与 Web；最终 release receipt 记录 exact main、真实 upstream、健康轮次及 Web 四方哈希。
- 安全：部署前未写入生产业务数据；发布仅执行 schema migration、服务切流和 Web 原子替换，不执行 F006 或其他租户业务 mutation。
- Scope 锁已释放。

## `FIX-F006-PRODUCTION-INTEGRITY-20260724` — `review`

- Owner: `/root`
- Base SHA: `14fcf69a231948fd6345d8e40847c4ec8108ae16`
- 结果：生产计划创建增加客户端幂等键与重复创建防线；取消/停产状态机收紧并记录审计字段；逐工序调料和成品包装包材按固定 BOM/Workflow 自动分配、扣减并进入实际成本；生产批次列表及逐工序报工 UI 重排。
- 数据库：新增生产计划幂等键、取消审计字段，并将调料/包材库存及消耗数量精度无损扩展为 `NUMERIC(18,6)`；真实 `ProductionPlanRepositoryQueryValidationTest` JPA Context 门禁通过。
- 验证：单次 `mvn clean package` 生命周期执行 19 个目标测试类、188 tests，0 failures / 0 errors / 0 skipped；Web 6 files / 40 tests、`vue-tsc -b`、RN 17 tests、Web release build、`git diff --check` 均通过。
- 制品：候选 JAR SHA-256 `969246e3fe4d603190ef1d08764acd2244ec15935bd54c6b40ca3434f3727432` 已只读校验并暂存至不可变 release cache；暂存未安装、未重启、未切流。
- 发布边界：等待 PR #1730 合并后，从 clean exact `origin/main` 统一部署 Java/Web；部署只执行 schema migration、蓝绿切流与 Web 原子替换，不执行 F006 或其他租户业务写入。
- Scope 锁已释放。

## `UX-F006-R6-PROCESS-REPORT-FLOW-SORT-002` — `review`

- Owner: `/root`
- Base SHA: `ced086481c44eeeb57f43a9b86e908d68529e432`
- 结果：逐工序报工的卡片与表格视图统一为“投入 → 工序执行 → 产出 → 确认提交”；开始时间、结束时间、人数和总工时从产出区移到工序执行区，草稿降为次操作，正式报工强化为唯一主操作。
- 总览：双出成率表保留列线对齐和固定布局，为所有可排序表头增加鼠标、键盘焦点、升降序提示及可见焦点反馈；筛选入口继续由原表头漏斗提供。
- UX Flow：面向车间填报人员按真实作业顺序组织，数量与单位成组，执行信息与产出数量分区，窄屏单列堆叠且提交按钮保持完整触达。
- 验证：目标 Vitest 4 files / 20 tests 通过，`vue-tsc -b` 通过，Web Interface Guidelines 只读审查完成；可信 Web manifest build 在精确候选 commit 上执行。
- 边界：只创建 PR，严格 `NOT_DEPLOYED`；未修改报工 API、Java 状态机或生产数据，生产业务写入为 0。
- Scope 锁已释放。

## `RELEASE-CRETAS-ANDROID-1.0.2-20260724` — `merged`

- Owner: `/root`
- Base SHA: `6514af9ed352125e9b12ba6aec960f9d74f2b084`
- 合入：PR [#1750](https://github.com/Stevenjxie/cretas/pull/1750) squash 合并为 `4cbfaf8d1bdf01243a1366d65454dcbc8b3a749d`；Android `versionName=1.0.2`、`versionCode=13`，Expo `app.json` / `app.config.js` 与下载页版本已统一。
- 构建：隔离工作树以 `.env.production`、`EXPO_PUBLIC_ENV=production` 和正式 keystore 执行 `assembleRelease`；APK 包名 `com.cretas.foodtrace`，内嵌 Expo 配置为 `production`，Babel 验证注入生产域名且未注入测试直连地址。
- 制品：APK 大小 `122175342` bytes，SHA-256 `44dc6adfca5c4d680e7697d490beb650b08266936c095da40be9680845b363c2`；APK signer SHA-256 `e2c55e0b74e0d12a4e0fbfcf0527d3ef571df950c60932c37faa24689ca6a941` 与 release keystore 完全匹配。
- 主线等价：候选构建 commit `97ecec42ed8a1131955faee31928e91eea7a89b7` 与合入后 `origin/main` 的 `frontend/CretasFoodTrace` tree 均为 `2eb14592b0896f1a78e85650db8d88c6c75ac112`，下载页 tree 均为 `50500b843cfc949d155689bf0b01b768b640e9b4`。
- 发布：`cretas-v1.0.2.apk` 与 `cretas-latest.apk` 已写入 `cretas-download` OSS；`https://download.cretaceousfuture.com/` 已切至 1.0.2，页面无 1.0.1 APK 链接残留，版本化直链与 latest 均 HTTP 200、`Content-Length=122175342`、ETag 一致。
- 公网回验：从 `https://dl.cretaceousfuture.com/cretas-v1.0.2.apk` 完整回下载，字节数与 SHA-256 均与本地正式 APK 一致；公开 health 仍保持最低兼容版本 `appMinVersion=1.0.0`，未将“最新版本”误设为强制最低版本。
- 边界：未重启 Java/Python/Web 服务，未执行任何生产业务数据写入。Scope 锁已释放。
