# 出成率报工 A4 — 超收软告警 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 提交某道工序产出量时, 若累计产出超过 `投入×standardYieldMax×(1+容差)`, 返回 409 OVER_RECEIPT 软告警(可 forceSubmit 突破); + getLimits 预检端点; 容差工厂级可配默认 30%。

**Architecture:** 后端在 `submitReport` 加超收检查块(基准量动态算自 `inputQuantity×WorkProcess.standardYieldMax`, 绕开永空的 plannedQuantity) + getLimits 端点 + FactorySettings 容差配置; 前端 RN 报工 dialog 预检 + OVER_RECEIPT 确认 Alert。纯增量, 不改既有报工成功路径。

**Tech Stack:** Java 21 + Spring Boot 3 + JPA; RN (Expo) + TS。

**Worktree:** `C:/Users/Steve/cretas-yield-a4` (branch `feat/yield-a4-over-receipt` off origin/main)。`mvn` at `/c/tools/apache-maven-3.9.6/bin`, 用 `MAVEN_OPTS="-Xmx2g"`。
**Spec:** `docs/superpowers/specs/2026-06-01-yield-A4-over-receipt-tolerance-design.md` (含精确代码 + §9 审计修订 + 测试计划)。

---

## 实施顺序
Task 1 (容差配置+helper) → Task 2 (submitReport 超收检查, 核心) → Task 3 (getLimits 端点) → Task 4 (RN 前端) → Task 5 (部署+验证)。后端 1-3 先于前端 4。

## 前置核查 (Task 2 实施者必做, spec 开放问题 Q1/Q2)
- **Q1**: `grep -rn "withCode\|withSeverity\|withHint" backend/.../exception/BusinessException.java` — 确认链式方法是否存在 + `GlobalExceptionHandler` 是否把它们映射到 `ApiResponse.errorWithCode`。若不存在 → 改用 controller `@ExceptionHandler(BusinessException.class)` 构建 `ApiResponse.errorWithCode(409, "OVER_RECEIPT", msg, hint, "BLOCKING")`, 或用现有 BusinessException 的实际 API。**以现有 BusinessException 真实 API 为准, 不臆造方法。**
- **Q2** (Task 4): `grep -rn "skipError\|_skip\|skipDefaultErrorHandler\|interceptor" frontend/CretasFoodTrace/src/services/api/` — 确认请求级绕过默认 error toast 的机制, 没有则加。

---

## Task 1: 容差配置 + getToleranceForFactory helper

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/FactorySettingsDTO.java` (ProductionSettings 内类加字段)
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java` (加 helper + 注入 factorySettingsRepo + objectMapper)
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/YieldReportServiceImplTest.java`

- [ ] **Step 1**: 读 `FactorySettingsDTO.java` 找 `ProductionSettings` 内类, 加字段:
```java
@Schema(description = "出成率报工超收容差 (0.30 = 30%), null 时默认 30%", example = "0.30")
private BigDecimal yieldOverReceiptTolerance;
```
(确认 import BigDecimal + Schema; Lombok @Data 自动 getter/setter)

- [ ] **Step 2**: 在 `YieldReportServiceImpl` 加 helper + 依赖。先确认现有构造注入方式(`@RequiredArgsConstructor` final fields)。需要 `FactorySettingsRepository`(grep 确认类名 + findByFactoryId 方法存在) + `ObjectMapper`。加 final 字段 + helper:
```java
private static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("0.30");

private BigDecimal getToleranceForFactory(String factoryId) {
    try {
        FactorySettings settings = factorySettingsRepo.findByFactoryId(factoryId).orElse(null);
        if (settings == null || settings.getProductionSettings() == null) return DEFAULT_TOLERANCE;
        FactorySettingsDTO.ProductionSettings ps = objectMapper.readValue(
            settings.getProductionSettings(), FactorySettingsDTO.ProductionSettings.class);
        return ps.getYieldOverReceiptTolerance() != null ? ps.getYieldOverReceiptTolerance() : DEFAULT_TOLERANCE;
    } catch (Exception e) {
        log.warn("[A4] 读取容差设置失败, 使用默认 30%", e);
        return DEFAULT_TOLERANCE;
    }
}
```
(若 FactorySettings.getProductionSettings 返回类型/repo 方法名不同, 按实际调整。先 grep 确认。)

- [ ] **Step 3**: 写测试(Mockito, 复用现有 YieldReportServiceImplTest 的 setUp 风格 — 它用 `new YieldReportServiceImpl(...)` 手工构造, 加新依赖 mock): `getTolerance_customValue` (productionSettings JSON 含 0.10 → 返 0.10), `getTolerance_nullSettings_default30` (null → 0.30)。helper 是 private → 通过 submitReport 间接测, 或临时改 package-private 测。**实施者决定: 若 helper private 难测, 在 Task 2 的 submitReport 测试里覆盖容差读取即可, Task 1 只交付字段+helper+编译过。**

- [ ] **Step 4**: `MAVEN_OPTS="-Xmx2g" mvn -Dtest=YieldReportServiceImplTest test` 编译过 + 既有测试不回归。

- [ ] **Step 5**: Commit (safe-commit `-- <paths>`):
```bash
git commit -m "feat(yield-a4): 容差配置字段 + getToleranceForFactory helper

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" -- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/FactorySettingsDTO.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/YieldReportServiceImplTest.java
```

---

## Task 2: submitReport 超收检查块 (核心, P1-2 wire forceSubmit)

**Files:**
- Modify: `backend/.../service/yield/impl/YieldReportServiceImpl.java` (submitReport 加超收检查)
- Possibly Modify: `backend/.../controller/YieldReportController.java` (若 BusinessException 不支持 withCode → @ExceptionHandler)
- Test: `YieldReportServiceImplTest.java`

- [ ] **Step 1: 前置核查 Q1** — grep BusinessException 的实际 API (withCode/withSeverity/withHint 是否存在; GlobalExceptionHandler 怎么映射 errorCode/severity/actionHint)。记录结论决定实施路径(链式 vs controller @ExceptionHandler vs BusinessException 现有构造)。

- [ ] **Step 2: 写失败测试** — 在 YieldReportServiceImplTest 加 spec 测试计划的核心用例(用现有 task()/report builder 风格):
  - `submitReport_overLimit_noForce_throwsOverReceipt`: input=100, syMax=1.0(mock WorkProcess), 已报 100, 本次 31, tolerance 默认 0.30 → maxAllowed=130, cumul=131>130, forceSubmit=false → 抛 BusinessException(409, errorCode OVER_RECEIPT), 且 `verify(reportRepo, never()).save(...)`。
  - `submitReport_overLimit_force_saves`: 同上 forceSubmit=true → 正常 save, 返 reportId。
  - `submitReport_withinLimit_saves`: input=100 syMax=0.85 target=85 已报0 本次80 → cumul 80 ≤ 110.5 → 正常 save 无异常。
  - `submitReport_waterRetention_noFalseAlarm`: input=100 syMax=1.35 已报130 本次5 → cumul135 ≤ 175.5 → 正常 save。
  - `submitReport_nullStandardYieldMax_skips`: WorkProcess.standardYieldMax=null → 跳过 → save。
  - `submitReport_nullInput_skips`: req.inputQuantity=null → 跳过 → save。
  - `submitReport_customTolerance`: productionSettings 0.10 → maxAllowed=target×1.10。
  (mock processRepo.findById 返回带 standardYieldMax 的 WorkProcess; mock factorySettingsRepo。)

- [ ] **Step 3: 跑测试确认 FAIL** (`mvn -Dtest=YieldReportServiceImplTest test` — 新测试 fail, 因检查未实现)。

- [ ] **Step 4: 实现** — 在 submitReport 的 `reportRepo.save(r)` **之前** 插入 spec §3 的超收检查块(精确代码见 spec 行 108-152), 关键: 读 `req.getForceSubmit()`, 用 Q1 确认的 BusinessException API 抛 409 OVER_RECEIPT。注意 `existingTaskReports` 变量名要对齐 submitReport 现有的已查报工列表(spec 说行 90-96 已有 taskTotal 逻辑, 复用那个 list)。

- [ ] **Step 5: 跑测试确认 PASS** (paste "Tests run: N, Failures: 0")。

- [ ] **Step 6: Commit** `feat(yield-a4): submitReport 超收软告警 (target=input×standardYieldMax×(1+容差), 接 forceSubmit)`。

---

## Task 3: getLimits 预检端点

**Files:**
- Create: `backend/.../dto/yield/YieldLimitsDTO.java` (spec 行 89-100 字段)
- Modify: `backend/.../service/yield/YieldReportService.java` (+ getLimits 接口)
- Modify: `backend/.../service/yield/impl/YieldReportServiceImpl.java` (实现 getLimits)
- Modify: `backend/.../controller/YieldReportController.java` (GET /limits)
- Test: `YieldReportServiceImplTest.java`

- [ ] **Step 1**: 建 `YieldLimitsDTO`(@Data @Builder, 字段见 spec)。
- [ ] **Step 2**: 写测试: `getLimits_withData` (input=100 syMax=0.85 已有Σ60 tol0.30 → target85 maxAllowed110.5 remaining50.5 + message); `getLimits_noBase` (syMax=null → targetQuantity/maxAllowed/remaining null)。
- [ ] **Step 3**: 跑 FAIL。
- [ ] **Step 4**: 实现 service getLimits (target=input×syMax, alreadyReported=Σ既有YIELD output, maxAllowed, remaining, message) + 接口 + controller GET `/api/mobile/{factoryId}/production/batches/{batchId}/yield/limits?workProcessTaskId=&inputQuantity=` (mirror YieldReportController 既有注解; @RequireModule 同 controller 现有)。
- [ ] **Step 5**: 跑 PASS + 全后端编译 (`MAVEN_OPTS="-Xmx2g" mvn -Dtest='Yield*' test`)。
- [ ] **Step 6**: Commit `feat(yield-a4): getLimits 预检端点 (防呆 Rule 1 边界预显)`。

---

## Task 4: RN 前端 — 预检 + OVER_RECEIPT 确认 Alert

**Files:**
- Modify: `frontend/CretasFoodTrace/src/services/api/yieldReportApi.ts` (+ getYieldLimits + submit 传 forceSubmit)
- Modify: `frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx` (投入量 onChange 预检 + OVER_RECEIPT 确认)

> RN 无单测惯例; 验证 = `npx tsc --noEmit` 该 2 文件 0 error。RN UI 真渲染验证受 Expo Web 全 repo bundle 健康度限制(Phase D 经验), 尽力 tsc + 真机由 Steve 测。

- [ ] **Step 1: 前置核查 Q2** — grep RN api client 的请求级 skip-error-handler 机制; 没有则在 yieldReportApi 的超收提交调用处用 try/catch 捕获 OVER_RECEIPT 不让默认 toast 弹。
- [ ] **Step 2**: `yieldReportApi.ts` 加 `getYieldLimits(factoryId, batchId, workProcessTaskId, inputQuantity)` (GET /yield/limits) + submitReport 支持传 `forceSubmit`。
- [ ] **Step 3**: `YieldStepReportScreen.tsx`: 投入量 onChange (debounce 500ms, inputQuantity>0) 调 getYieldLimits → 显示 "目标/已报/最多可报" + input max; 提交 catch `errorCode==='OVER_RECEIPT'` → `Alert.alert('超收确认', actionHint, [取消, {确认超收提交 → submitWithForce(forceSubmit:true)}])`。
- [ ] **Step 4**: `cd frontend/CretasFoodTrace && npx tsc --noEmit 2>&1 | grep -iE "yieldReportApi|YieldStepReport" || echo "0 errors"`。
- [ ] **Step 5**: Commit `feat(yield-a4): RN 报工 dialog 超收预检 + OVER_RECEIPT 确认 Alert`。

---

## Task 5: 部署 + prod 验证 (per HARD RULE)

- [ ] PR scope 干净 (`git diff origin/main...HEAD --stat` 只本 feature) → PR → admin-merge (e2e gate pre-existing broken, 看 java-build-test + vue + rn-test 绿)。
- [ ] 从 main 部署: 在 deploy worktree `git checkout origin/main` (fetch 后) → `deploy-backend.sh --env prod` (blue-green)。RN 无需部署(前端跟 App 走)。
- [ ] **prod API 验证** (核心, SSH on server, 活跃端口先 curl 找): seed 一个有 standardYieldMax 的工序任务批次 → submitReport 超限 → 断言 409 + errorCode OVER_RECEIPT + actionHint 含准确数字; 再 forceSubmit=true → 200 + reportId 保存。getLimits 端点返 target/maxAllowed/remaining。清理 seed。
- [ ] 写验证记录 (PASS=N FAIL=0)。

---

## Self-Review
- Spec 覆盖: Task1(容差配置§4/DTO)、Task2(submitReport超收§3 + forceSubmit P1-2)、Task3(getLimits§2 + YieldLimitsDTO)、Task4(RN前端§前端设计)、Task5(部署+验证§测试计划) 全覆盖。§9 审计修订(基准量=input×syMax / errorWithCode 409 / forceSubmit wire / 容差FactorySettings)全落地。
- 关键一致性: target=inputQuantity×standardYieldMax 在 submitReport(Task2)、getLimits(Task3)两处算法一致; OVER_RECEIPT errorCode 后端(Task2)↔前端识别(Task4)一致; forceSubmit 字段后端读(Task2)↔前端传(Task4)一致。
- 无 placeholder: 每 task 有具体代码引用/grep 命令/测试用例数字。
- 开放问题 Q1(BusinessException API)/Q2(RN skip-handler) 作为 Task 2/4 的前置核查步, 实施者读真实代码定路径, 不臆造。
