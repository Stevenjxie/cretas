# SmartBI 收口 — 第一刀:清理死代码 + 修 P0 假数据驾驶舱 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development。每个 Task 一个 fresh subagent + 两段 review。步骤用 `- [ ]`。
> **🔒 红线**: 触 prod + 活客户 qhj_prod 驾驶舱。执行者只做到"实现+自测+PR off origin/main";**merge + prod 部署由 Opus(organizer)终审后从 main 执行**,执行者不自部署。
> **隔离**: 在独立 worktree off origin/main 做(`git worktree add -b feat/smartbi-cleanup ../cretas-smartbi-cleanup origin/main`)。

**Goal**: 删除 Phase 2A 留下的死 stub,并把 `enrichUnifiedDashboard` 里两个伪随机 mock(生产/质量)替换为 Python 真实端点后删除——修掉 prod 驾驶舱当前展示假随机数据的 P0 bug。

**Architecture**: 不动统一源核心。先收割独立、低耦合的清理:① 纯删零调用方 stub;② 把 dashboard 进程内 mock 调用改为 HTTP 调 Python(`PythonSmartBIClient` 现有模式)后删 Java mock。6 个 THIN 真实数据服务的退役(Task C)需逐个 parity 验证,作为本计划尾部低紧迫任务,可拆下一计划。

**Tech Stack**: Java 21 + Spring Boot(`backend/java/cretas-api`)+ Python FastAPI(`backend/python/smartbi_compat`)+ nginx(`ops/nginx-vhosts-139/smart-bi-routing.conf`)。测试 `.\mvnw.cmd test`(H2)。

---

## 文件结构(本计划触及)

| 文件 | 责任 | 动作 |
|--|--|--|
| `client/PythonSmartBIClient.java` | Java→Python 委托客户端 | 删 5 个 `callConfigThresholds*` + `fetchIndicatorValue` stub;新增 `getProductionOEEViaPython` / `getQualitySummaryViaPython` |
| `service/smartbi/impl/ProductionAnalysisServiceImpl.java` + `ProductionAnalysisService.java` | 伪随机生产 mock | 删 |
| `service/smartbi/impl/QualityAnalysisServiceImpl.java` + `QualityAnalysisService.java` | 伪随机质量 mock | 删 |
| `controller/SmartBIDashboardController.java` `enrichUnifiedDashboard`(541-589) | 驾驶舱组装 | 行 551-558 两处改调 PythonSmartBIClient |
| `controller/SmartBIAnalysisController.java`(80-155) | `/analysis/production\|quality` 端点 | 删 Java 实现(nginx 已切 Python) |
| `service/impl/IndicatorQueryServiceImpl.java`(204) | 指标查询 | 视 DB 检查删 `PYTHON_ENDPOINT` 分支 |
| 测试:`PythonSmartBIClientTest` / `IndicatorQueryServiceImplTest` / `SmartBIDashboard*RbacTest` | — | 同步更新 |

---

## Task 1: 删除零调用方 stub `callConfigThresholds*`

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/client/PythonSmartBIClient.java:1375-1419`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/client/PythonSmartBIClientTest.java`

- [ ] **Step 1: 确认零调用方**

Run: `grep -rn "callConfigThresholds" backend/java/cretas-api/src/main`
Expected: 只命中 `PythonSmartBIClient.java` 内的 5 个**定义**,无外部调用。若出现任何外部调用方 → 停,改为 NEEDS_CONTEXT 上报。

- [ ] **Step 2: 删除 5 个方法**

删 `PythonSmartBIClient.java:1375-1419` 的全部 `callConfigThresholds*` 方法(`callConfigThresholdsList` 等 5 个,均 `log.warn("...stub...")` + `return null`)。

- [ ] **Step 3: 删对应测试**

在 `PythonSmartBIClientTest.java` 里删除引用 `callConfigThresholds*` 的 test case(grep 定位)。

- [ ] **Step 4: 构建 + 全量测试**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS,无 `callConfigThresholds` 编译引用残留。

- [ ] **Step 5: Commit**

```bash
git commit -m "chore(smartbi): remove dead callConfigThresholds* stubs (zero callers)" -- backend/java/cretas-api/src/main/java/com/cretas/aims/client/PythonSmartBIClient.java backend/java/cretas-api/src/test/java/com/cretas/aims/client/PythonSmartBIClientTest.java
```

---

## Task 2: 处理 `fetchIndicatorValue` stub(条件删除)

**Files:**
- Modify: `client/PythonSmartBIClient.java:2249`
- Modify: `service/impl/IndicatorQueryServiceImpl.java:204`

- [ ] **Step 1: 查 prod 是否存在 PYTHON_ENDPOINT 指标**

Run(prod 只读):
```bash
ssh -o StrictHostKeyChecking=no root@47.100.235.168 "PGPASSWORD=cretas123 psql -U cretas_user -h 127.0.0.1 -d cretas_prod_db -tAc \"SELECT count(*) FROM indicator_computation WHERE compute_type='PYTHON_ENDPOINT'\""
```
Expected:
- 返回 `0` → 走 Step 2a(删 stub + 分支)。
- 返回 `>0` → **不删**;改为 DONE_WITH_CONCERNS 上报"PYTHON_ENDPOINT 指标在用,stub 替换为真实 HTTP 是独立任务",跳过 Task 2 剩余步骤。

- [ ] **Step 2a: 删 stub + 调用分支**

删 `PythonSmartBIClient.java:2249` 的 `fetchIndicatorValue`;删 `IndicatorQueryServiceImpl.java:204` 处 `computeType == "PYTHON_ENDPOINT"` 分支(连同其 `FetchOutcome` 包装),保留其它 computeType 分支。

- [ ] **Step 2b: 更新测试**

`IndicatorQueryServiceImplTest.java` 删/改引用 `fetchIndicatorValue` 的 6 处 call site(grep 定位)。

- [ ] **Step 3: 构建 + 全量测试**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(smartbi): remove fetchIndicatorValue stub + dead PYTHON_ENDPOINT branch" -- backend/java/cretas-api/src/main/java/com/cretas/aims/client/PythonSmartBIClient.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/IndicatorQueryServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/IndicatorQueryServiceImplTest.java
```

---

## Task 3: 在 `PythonSmartBIClient` 新增生产/质量 Python 委托方法

**Files:**
- Modify: `client/PythonSmartBIClient.java`
- Test: `PythonSmartBIClientTest.java`

- [ ] **Step 1: 先核 Python 端点返真实数据(非 mock)**

Run(prod 只读,验证 Python 生产/质量端点对真实工厂返实数):
```bash
ssh -o StrictHostKeyChecking=no root@47.100.235.168 "curl -s 'http://localhost:8083/api/mobile/F006/smart-bi/analysis/production?start=2026-04-01&end=2026-06-30' | head -c 600"
```
Expected: 返回真实结构(非 `产线A/B/C` 那种固定 mock 串)。质量端点同理(`/analysis/quality`)。若 Python 端也是占位 → DONE_WITH_CONCERNS 上报(替换无净收益,需先让 Python 出真数据)。

- [ ] **Step 2: 读现有委托方法模式**

阅读 `PythonSmartBIClient.java` 里任一现有 `*ViaPython` / analysis 委托方法(如 finance overview 的委托),照搬其:RestTemplate/WebClient 调用、`aiServiceUrl` base、`X-Internal-Secret` header、超时、异常处理(失败返回 null 或抛、与 caller 容错一致)。

- [ ] **Step 3: 新增两方法(镜像现有模式)**

```java
/** 委托 Python 生产 OEE 分析(替换原 Java 伪随机 mock)。失败返 null,caller(enrich)已 try-catch 容错。 */
public Object getProductionOEEViaPython(String factoryId, java.time.LocalDate start, java.time.LocalDate end) {
    // 镜像现有 analysis 委托:GET {aiServiceUrl}/api/mobile/{factoryId}/smart-bi/analysis/production?start=&end=
    // header X-Internal-Secret;反序列化为 enrich 现用的返回类型(见 SmartBIDashboardController setProduction 的入参类型)
}
/** 委托 Python 质量分析(替换原 Java 伪随机 mock)。 */
public Object getQualitySummaryViaPython(String factoryId, java.time.LocalDate start, java.time.LocalDate end) {
    // 同上,路径 .../smart-bi/analysis/quality
}
```
> 返回类型与 `UnifiedDashboardResponse.setProduction(...)` / `setQuality(...)` 入参一致(读该 DTO 定 shape);若 Python JSON 与 Java DTO 字段不齐,在此方法内映射对齐(parity 验证点)。

- [ ] **Step 4: 单测(mock HTTP,断言路径/header/反序列化)**

在 `PythonSmartBIClientTest.java` 加两个 test:mock RestTemplate 返样例 JSON,断言调用 `.../analysis/production`、带 `X-Internal-Secret`、反序列化字段正确。
Run: `.\mvnw.cmd test -Dtest=PythonSmartBIClientTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(smartbi): add Python production/quality analysis delegation methods" -- backend/java/cretas-api/src/main/java/com/cretas/aims/client/PythonSmartBIClient.java backend/java/cretas-api/src/test/java/com/cretas/aims/client/PythonSmartBIClientTest.java
```

---

## Task 4: `enrichUnifiedDashboard` 改调 Python(修 P0 假数据)

**Files:**
- Modify: `controller/SmartBIDashboardController.java:551-558`
- Test: `SmartBIDashboardExecutiveRbacTest.java` / `SmartBIDashboardSiblingsRbacTest.java`

- [ ] **Step 1: 替换两处进程内 mock 调用**

`enrichUnifiedDashboard` 行 551-558:
```java
// 原(假数据):
//   response.setProduction(productionAnalysisService.getOEEOverview(factoryId, startDate, endDate));
//   response.setQuality(qualityAnalysisService.getQualitySummary(factoryId, startDate, endDate));
// 改为(Python 真数据):
response.setProduction(pythonSmartBIClient.getProductionOEEViaPython(factoryId, startDate, endDate));
response.setQuality(pythonSmartBIClient.getQualitySummaryViaPython(factoryId, startDate, endDate));
```
注入 `pythonSmartBIClient`(若类内未注入则加 `@Autowired` 字段)。移除对 `productionAnalysisService`/`qualityAnalysisService` 的注入字段。

- [ ] **Step 2: 更新受影响 RBAC 测试**

`SmartBIDashboard*RbacTest` 里 mock 掉 `pythonSmartBIClient.getProductionOEEViaPython/...QualitySummary...` 返样例,移除对两个被删 service 的 mock。断言 dashboard `production`/`quality` 字段来自 client(非 Random)。
Run: `.\mvnw.cmd test -Dtest=SmartBIDashboardExecutiveRbacTest,SmartBIDashboardSiblingsRbacTest`
Expected: PASS。

- [ ] **Step 3: 全量测试**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git commit -m "fix(smartbi): dashboard production/quality now from Python real data, not Java random mock" -- backend/java/cretas-api/src/main/java/com/cretas/aims/controller/SmartBIDashboardController.java backend/java/cretas-api/src/test/java/com/cretas/aims/controller/SmartBIDashboardExecutiveRbacTest.java backend/java/cretas-api/src/test/java/com/cretas/aims/controller/SmartBIDashboardSiblingsRbacTest.java
```

---

## Task 5: 删除两个 mock 服务 + Java 端点

**Files:**
- Delete: `service/smartbi/impl/ProductionAnalysisServiceImpl.java`、`service/smartbi/ProductionAnalysisService.java`
- Delete: `service/smartbi/impl/QualityAnalysisServiceImpl.java`、`service/smartbi/QualityAnalysisService.java`
- Modify: `controller/SmartBIAnalysisController.java:80-155`

- [ ] **Step 1: 确认无其它调用方**

Run: `grep -rn "ProductionAnalysisService\|QualityAnalysisService" backend/java/cretas-api/src/main`
Expected: 仅命中 `SmartBIAnalysisController`(待改)+ 自身定义。Task 4 已移除 dashboard 引用。若有其它调用方 → 停、上报。

- [ ] **Step 2: 删 SmartBIAnalysisController 的 production/quality 端点**

删 `SmartBIAnalysisController.java:80-116`(`/analysis/production`)+ `120-155`(`/analysis/quality`)及其对两 service 的注入(nginx 已把这两路由切 Python,Java 端点是死路径)。

- [ ] **Step 3: 删四个文件**

删 `ProductionAnalysisServiceImpl/Service` + `QualityAnalysisServiceImpl/Service` 四个文件(无专属测试)。

- [ ] **Step 4: 全量测试**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS,无 `Production/QualityAnalysisService` 残留引用。

- [ ] **Step 5: Commit**

```bash
git commit -m "chore(smartbi): delete production/quality random-mock services + dead Java endpoints" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/smartbi/impl/ProductionAnalysisServiceImpl.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/smartbi/ProductionAnalysisService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/smartbi/impl/QualityAnalysisServiceImpl.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/smartbi/QualityAnalysisService.java backend/java/cretas-api/src/main/java/com/cretas/aims/controller/SmartBIAnalysisController.java
```

---

## Task 6:(尾,可拆下一计划)6 个 THIN 真实数据服务退役

> ⚠️ 与 Task 1-5 不同:这 6 个(Finance/Inventory/Procurement/Department/Region)是**真实 DB 查询**,Python 已有替代但**必须逐个 parity 验证**返回 shape 与 `UnifiedDashboardResponse` 字段一致后才切。紧迫度低(不展示假数据),建议作为独立计划。本计划不强制完成。

- [ ] 每服务:核 Python 端点返回 shape vs Java DTO → `PythonSmartBIClient` 加委托方法 → `enrich` 改调 → 删 Java service → 测试 → PR。逐个 parity gate,不批量。

---

## 交付 / 部署(🔒 Opus 终审)

- [ ] 全部 PR off `origin/main`;`git diff origin/main...HEAD --stat` 确认 scope 干净。
- [ ] **Opus 终审**(尤其 Task 4 客户驾驶舱 parity:确认 Python 生产/质量数据对 qhj_prod 等真实工厂可用、shape 对齐、无回归)。
- [ ] merge 进 main → Opus 从 main 部署 prod(`deploy-backend.sh --env prod`)→ 核对运行 jar + headed 驾驶舱验证生产/质量字段是真数据(非固定"产线A/B/C")。

---

## Self-Review(对 spec)
- **覆盖**: 本计划 = spec §5 Phase -1 的"🟢 删(S)stub/mock"+ 触客户 mock 的 parity 替换;6 THIN 僵尸(🟡 M)列 Task 6 尾/拆分;大 Java 核心(🔴 替换)与统一源核心是后续计划,不在此。
- **无 placeholder**: 删除任务给确切 file:line + 零调用方 grep gate;P0 修给确切替换行 + 两个 DB/curl 真实性 gate;HTTP 方法给契约 + 现有模式镜像引用(执行者读)。
- **一致性**: 方法名 `getProductionOEEViaPython`/`getQualitySummaryViaPython` 在 Task 3 定义、Task 4 调用一致。
