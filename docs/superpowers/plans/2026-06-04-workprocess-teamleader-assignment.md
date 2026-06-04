# 工序-小组长分配 Phase 1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 让每个小组长登录只看/报自己负责的工序;工序责任在 web-admin 配默认(产品级,过夜延续),批次 spawn 自动按工序归属到对应小组长,后端强制归属鉴权。

**Architecture:** `ProductWorkProcess` 加 `responsibleWorkerId`(产品级默认)→ spawnTasks 写入 `work_process_task.assigned_to` → RN 报工屏按 `assignedTo=登录用户` 过滤(全 null 兜底显示全部)→ 后端 submitReport/recordMaterialInput/submitNormalReport 三处强制归属+角色守卫。代报工/早会改派/RN 配置 = Phase 2 defer。

**Tech Stack:** Java 21 + Spring Boot + JPA + Flyway(PostgreSQL) / Vue3 web-admin / RN Expo。

**Spec:** `docs/superpowers/specs/2026-06-04-workprocess-teamleader-assignment-design.md` (v2, 审计修订)

**Worktree:** `C:/Users/Steve/cretas-e2e-replica` (off origin/main; 已含 Alert/clip 修复 + 撤代报选择器残留死代码待清理)。Maven: `C:/tools/apache-maven-3.9.6/bin/mvn.cmd`。

---

## 文件结构(改动面)

**后端 Java** (`backend/java/cretas-api/src/main/java/com/cretas/aims/`)
- `entity/production/ProductWorkProcess.java` — 加 `responsibleWorkerId` 字段
- `dto/.../ProductWorkProcessDTO.java` — 加字段
- `service/.../impl/ProductWorkProcessServiceImpl.java` — create/update/toDTO 三处透传
- `service/.../impl/WorkProcessTaskServiceImpl.java` — spawn 写 assignedTo(:98-110)、list 加 assignedTo 过滤(:149)
- `controller/WorkProcessTaskController.java` — listByBatch(:93-99) 加 assignedTo 参数
- `service/yield/impl/YieldReportServiceImpl.java` — submitReport(:105-126) + recordMaterialInput(:943) 归属守卫
- `controller/.../ProcessWorkReportingController.java` — submitNormalReport(:81-105) 同守卫
- `resources/db/flyway/V20260919_02__*.sql`(取号见 Task1) — 迁移

**web-admin** (`web-admin/src/`)
- 产品工序管理页(工序链编辑处)— 加"默认责任小组长"下拉

**RN** (`frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx`)
- tasks 加载传 userId + 过滤;完工按钮按角色;清理代报死代码

> 注: 后端先确认实际包路径(`grep -rl "class ProductWorkProcess" backend/java`),file:line 以审计为准但实现期以真实文件为准。

---

### Task 1: Flyway 迁移 — ProductWorkProcess.responsible_worker_id

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20260919_02__product_work_process_responsible_worker.sql`(版本号见 Step1)

- [ ] **Step 1: 取迁移版本号(防撞号 — 必查 origin/main)**

Run:
```bash
cd C:/Users/Steve/cretas-e2e-replica
git ls-tree -r origin/main backend/java/cretas-api/src/main/resources/db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | tail -3
```
取结果最高号 +1(如最高 `V20260919_01` → 用 `V20260919_02`)。若已有 V20260919_02 则继续往上。文件名套此号。

- [ ] **Step 2: 写迁移(带 to_regclass 守卫 — entity-only 表)**

```sql
-- product_work_processes 是 entity-only 表(Hibernate ddl-auto 建), fresh-DB Flyway 先于 ddl-auto → 必须守卫
DO $$
BEGIN
    IF to_regclass('public.product_work_processes') IS NOT NULL THEN
        ALTER TABLE product_work_processes
          ADD COLUMN IF NOT EXISTS responsible_worker_id BIGINT;
        COMMENT ON COLUMN product_work_processes.responsible_worker_id IS '默认责任小组长 user_id; spawn 时作为 work_process_task.assigned_to 默认值';
    END IF;
END $$;
```
不加 FK(对齐 assigned_to/worker_id 既有约定)。主库 cretas, 无需 GRANT/RLS。

- [ ] **Step 3: 本地 fresh-DB 起后端验证迁移不阻断**

Run: `C:/tools/apache-maven-3.9.6/bin/mvn.cmd -q -pl cretas-api spring-boot:run`(或 CI e2e-pr-gate)。Expected: 启动成功, 日志无 "relation does not exist"/Flyway 错误。

- [ ] **Step 4: Commit**
```bash
git add backend/java/cretas-api/src/main/resources/db/flyway/V20260919_02__*.sql
git commit -m "feat(assign): 迁移 product_work_processes.responsible_worker_id (守卫)"
```

---

### Task 2: ProductWorkProcess 责任人字段(entity/DTO/create/update/toDTO 五处)

**Files:**
- Modify: `entity/.../ProductWorkProcess.java` / `dto/.../ProductWorkProcessDTO.java` / `service/.../impl/ProductWorkProcessServiceImpl.java`(create/update/toDTO)
- Test: `service/.../ProductWorkProcessServiceImplTest.java`(若无则建)

- [ ] **Step 1: 写失败测试 — 配责任人后能存能读回**

```java
@Test
void update_setsAndReturnsResponsibleWorkerId() {
    // arrange: 已有一条 ProductWorkProcess(产品X 工序Y)
    var dto = new ProductWorkProcessDTO();
    dto.setResponsibleWorkerId(1616L); // 魏振江
    // act
    var saved = service.update(FACTORY, existingId, dto);
    // assert
    assertThat(saved.getResponsibleWorkerId()).isEqualTo(1616L);
    assertThat(service.getById(FACTORY, existingId).getResponsibleWorkerId()).isEqualTo(1616L);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn.cmd -q -pl cretas-api -Dtest=ProductWorkProcessServiceImplTest#update_setsAndReturnsResponsibleWorkerId test`
Expected: FAIL(字段不存在/编译失败)。

- [ ] **Step 3: 实现五处**

1. entity: `@Column(name = "responsible_worker_id") private Long responsibleWorkerId;`
2. DTO: `private Long responsibleWorkerId;`
3. create(:45-52): builder/setter 透传 `dto.getResponsibleWorkerId()`
4. update(:85-87): 透传(清空语义见 Step3b)
5. toDTO(:116-124): `dto.setResponsibleWorkerId(entity.getResponsibleWorkerId());`

- [ ] **Step 3b: 清空语义**

update 现为局部(仅非 null 才 set)→ 无法传 null 清空。约定: DTO `responsibleWorkerId == -1L` 视为"清空"(set null);其余 null=不改、正整数=改。在 update 实现该三态。

- [ ] **Step 4: 跑测试 + 加清空用例**
```java
@Test void update_minusOne_clearsResponsible() { dto.setResponsibleWorkerId(-1L); assertThat(service.update(...).getResponsibleWorkerId()).isNull(); }
```
Run: `mvn.cmd -q -pl cretas-api -Dtest=ProductWorkProcessServiceImplTest test` Expected: PASS。

- [ ] **Step 5: 配默认端点限主管(用 @RequireRole, 不是 @PreAuthorize)**

`ProductWorkProcessController` 的 update 端点(`PUT /api/mobile/{factoryId}/product-work-processes/{id}`)加:
```java
@RequireRole({"factory_super_admin","workshop_supervisor","department_admin"})
```
注: 本仓库 `@PreAuthorize` 是 NO-OP(SecurityAutoConfiguration excluded), 必须用自定义 `@RequireRole`。

- [ ] **Step 6: Commit**
```bash
git commit -am "feat(assign): ProductWorkProcess 责任人字段(五处)+ 清空语义 + 配默认限主管"
```

---

### Task 3: spawnTasks 写 assignedTo = 默认责任人

**Files:**
- Modify: `service/.../impl/WorkProcessTaskServiceImpl.java`(spawn, ~:98-110)
- Test: `WorkProcessTaskServiceImplTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void spawnTasks_setsAssignedToFromProductDefault() {
    // arrange: 产品X 工序链[修油(默认null), 滚揉(默认莫云1615)]
    // act
    var tasks = service.spawnTasks(FACTORY, batchId);
    // assert
    assertThat(taskFor("修油").getAssignedTo()).isNull();
    assertThat(taskFor("滚揉").getAssignedTo()).isEqualTo(1615L);
}
```

- [ ] **Step 2: 跑测试确认失败**
Run: `mvn.cmd -q -pl cretas-api -Dtest=WorkProcessTaskServiceImplTest#spawnTasks_setsAssignedToFromProductDefault test` Expected: FAIL(spawn 现不写 assignedTo, 全 null)。

- [ ] **Step 3: 实现** — spawn 每道 task 的 builder 链加 `.assignedTo(pwp.getResponsibleWorkerId())`(pwp = 该工序对应的 ProductWorkProcess)。null 保持 null(=未指派=谁都可报)。

- [ ] **Step 4: 跑测试** Expected: PASS。

- [ ] **Step 5: Commit** `git commit -am "feat(assign): spawnTasks 按工序默认责任人写 assigned_to"`

---

### Task 4: 后端归属/角色鉴权(submitReport + recordMaterialInput + submitNormalReport)

**Files:**
- Modify: `service/yield/impl/YieldReportServiceImpl.java`(submitReport :105-126, recordMaterialInput :943)
- Modify: `controller/.../ProcessWorkReportingController.java`(submitNormalReport :81-105)
- Test: `YieldReportServiceImplTest.java`

- [ ] **Step 1: 写失败测试(反向安全)**

```java
@Test
void submitReport_rejectsWhenTaskAssignedToOther() {
    // task.assignedTo = 1616(魏振江); 登录 userId = 1615(莫云), 非主管
    var ex = assertThrows(BusinessException.class,
        () -> service.submitReport(FACTORY, batchId, 1615L, reqFor(taskAssignedTo1616)));
    assertThat(ex.getCode()).isEqualTo(403);
}
@Test
void submitReport_allowsWhenAssignedToSelfOrNull() {
    assertDoesNotThrow(() -> service.submitReport(FACTORY, batchId, 1615L, reqFor(taskAssignedTo1615)));
    assertDoesNotThrow(() -> service.submitReport(FACTORY, batchId, 1615L, reqFor(taskAssignedToNull)));
}
@Test
void submitReport_operatorCannotForgeTargetWorkerId() {
    // 非主管传 targetWorkerId=9999 → effectiveWorker 强制=登录 1615, 不采纳 9999
    var saved = service.submitReport(FACTORY, batchId, 1615L, reqWithTargetWorker(9999L));
    assertThat(savedReport.getWorkerId()).isEqualTo(1615L);
}
```

- [ ] **Step 2: 跑测试确认失败**
Run: `mvn.cmd -q -pl cretas-api -Dtest=YieldReportServiceImplTest test` Expected: FAIL(当前无守卫, 不抛 403 / 采纳伪造 targetWorkerId)。

- [ ] **Step 3: 实现守卫(submitReport :105-126)**

取 task 后:
```java
boolean isSupervisor = SecurityUtils.hasAnyRole("factory_super_admin","workshop_supervisor","department_admin");
Long assignee = task.getAssignedTo();
if (assignee != null && !assignee.equals(workerId) && !isSupervisor) {
    throw new BusinessException(403, "该工序已指派给他人, 您无权报工");
}
// targetWorkerId 仅主管可传; 非主管强制 effectiveWorker = workerId
Long effectiveWorker = (isSupervisor && req.getTargetWorkerId() != null) ? req.getTargetWorkerId() : workerId;
```
(替换原 :126 无条件 effectiveWorker。)

- [ ] **Step 3b: recordMaterialInput(:943) + submitNormalReport(:81-105) 同守卫** — 抽 helper `assertCanReport(task, workerId)` 复用, 三处都调。

- [ ] **Step 4: 跑测试** Expected: PASS(三个用例)。

- [ ] **Step 5: Commit** `git commit -am "feat(assign): 报工三链 task 归属鉴权 + targetWorkerId 主管门控 (M3)"`

---

### Task 5: 报工列表按本人过滤 + 全-null 兜底(listByBatch)

**Files:**
- Modify: `controller/WorkProcessTaskController.java`(listByBatch :93-99)
- Modify: `service/.../impl/WorkProcessTaskServiceImpl.java`(:149)
- Test: `WorkProcessTaskServiceImplTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test void listByBatch_filtersToAssignee_whenAnyAssigned() {
    // 批次有道 assignedTo=1615/1616, 传 assignedTo=1615 → 只返回 1615 的(+ null 道)
    var r = service.listByBatch(FACTORY, batchId, 1615L);
    assertThat(r).allMatch(t -> t.getAssignedTo()==null || t.getAssignedTo().equals(1615L));
}
@Test void listByBatch_returnsAll_whenAllNull() {
    // 批次全 null(f006_worker1 回归) → 传 assignedTo=1311 → 返回全部道(不锁死)
    assertThat(service.listByBatch(FACTORY, allNullBatchId, 1311L)).hasSize(6);
}
```

- [ ] **Step 2: 跑测试确认失败** Run: `mvn.cmd -q -pl cretas-api -Dtest=WorkProcessTaskServiceImplTest test` Expected: FAIL(listByBatch 无 assignedTo 参数)。

- [ ] **Step 3: 实现**
- controller listByBatch 加 `@RequestParam(required=false) Long assignedTo` 透传 service。
- service:149 逻辑: 先查该批 tasks; **若全部 assignedTo==null → 返回全部(忽略入参)**; 否则 filter `t.assignedTo==null || t.assignedTo.equals(assignedTo)`。保留 processOrder 升序。
- 跨工厂由 JwtAuthInterceptor 路径级保证(无需额外)。

- [ ] **Step 4: 跑测试** Expected: PASS(两个用例)。

- [ ] **Step 5: Commit** `git commit -am "feat(assign): listByBatch 按本人过滤 + 全null兜底 (M1/M2)"`

---

### Task 6: web-admin 产品工序管理 "默认责任小组长" 下拉

**Files:**
- Modify: web-admin 产品工序链编辑组件(实现期 `grep -rl "product-work-process\|工序链\|responsibleWorker" web-admin/src` 定位)
- Modify: 对应 api client(加 responsibleWorkerId 透传)

- [ ] **Step 1: 定位工序链编辑页 + operator 数据源**
Run: `grep -rln "product-work-process\|工序" web-admin/src/views web-admin/src/api`。operator 列表: `GET /api/mobile/{factoryId}/users/role/operator`(已存在)。

- [ ] **Step 2: 加下拉**
工序链每道加 `<el-select v-model="row.responsibleWorkerId" placeholder="默认责任小组长" clearable>` 选项=operator 列表(label=fullName, value=id);clearable 清空时传 `-1`(对齐 Task2 清空语义)。保存时 PUT 带 responsibleWorkerId。

- [ ] **Step 3: 构建验证** Run: `cd web-admin && npm run build`(或 `vite build`)Expected: 构建通过。

- [ ] **Step 4: Commit** `git commit -am "feat(assign): web-admin 工序链配默认责任小组长下拉"`

---

### Task 7: RN 报工屏按本人过滤 + 完工归主管 + 清理代报死代码

**Files:**
- Modify: `frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx`
- Modify: `frontend/CretasFoodTrace/src/services/api/yieldReportApi.ts`(listByBatch 加 assignedTo 参数)

- [ ] **Step 1: tasks 加载传 userId(:167 区域)**
`processingApiClient`/`yieldReportApi` 取 tasks 的调用(YieldStepReportScreen :167)补传当前登录 userId(从 auth store/SecureStore 取);yieldReportApi 的 listByBatch 方法签名加 `assignedTo?`。

- [ ] **Step 2: 完工按钮按角色(C6)**
done 卡/完工入库按钮(`handleSettleDay`, :688/:735-797 区域): operator 角色隐藏(他们只报自己那道, 看不到全道完成);主管角色可见。用当前用户角色判断。

- [ ] **Step 3: 清理代报死代码(C2)**
删除: `operators` state + fetch useEffect、`selectedReporterId`/`reporterPickerOpen` state、`selectedReporter`/`reporterRef`/`reporterFields`、3 处 `...reporterFields()` 注入、reporter* 死样式、`userApiClient`/`Modal`(若仅代报用则删)import。**保留** Alert→appAlert 修复 + AppDialog + 时段行 minWidth:0 修复(那是有用的)。

- [ ] **Step 4: 构建/类型检查** Run: `cd frontend/CretasFoodTrace && npx tsc --noEmit`(或 expo 启动无报错)Expected: 无类型错误。

- [ ] **Step 5: Commit** `git commit -am "feat(assign): RN 报工屏按本人过滤工序 + 完工归主管 + 清理代报死代码"`

---

### Task 8: headed E2E + 反向安全 + 回归验收

**Files:** 临时脚本 `e2e-replica/verify_assignment.py` + RN headed(playwright-rn)

- [ ] **Step 1: 配默认(API 或 web headed)**
给 F006 猪舌工序链配: 修油→徐(1617) / 滚揉→莫云(1615) / 焯水·熟制→魏振江(1616) / 装盒→永珍(1618)。

- [ ] **Step 2: 建批次 spawn → 验 assigned_to**
建猪舌批次, spawn, 查 DB: `SELECT wp.process_name, wpt.assigned_to FROM work_process_tasks wpt JOIN ... WHERE batch_id=X` → 各道 = 对应组长 id。

- [ ] **Step 3: RN headed — 各组长只看自己**
莫云(f006_moyun)登录 RN → 报工选批次 → 进批次 → **工序列表只见滚揉**;魏振江登录 → 只见焯水/熟制。截图。

- [ ] **Step 4: 反向安全(M3)**
API 以莫云 token POST 报魏振江的焯水任务 → **403**;operator 传 targetWorkerId → 落库 worker_id=登录者(未伪造)。

- [ ] **Step 5: 回归(M1)**
f006_worker1(未配)登录或全-null 批次 → 报工列表**看到全部道**(不锁死)。

- [ ] **Step 6: 主管完工(C6)**
主管登录 → 看全道 + 可完工入库。

- [ ] **Step 7: 记录验收 doc + Commit**

---

## 并行工作建议
### Subagent: ✅ Task1-5(后端)串行(互相 import: 1→2→3, 4/5 依赖 2);Task6(web)Task7(RN)接口定后可并行;Task8 串行末尾。
### 多Chat: ❌ 都改同一批后端文件 + 同一 RN 屏, 冲突风险高, 单 chat subagent-driven 内做。

---

## Self-Review
- **Spec 覆盖**: §8 十单元 → 本计划 Task1(M4/M5)+Task2(M6/C1)+Task3(M1 spawn)+Task4(M3)+Task5(M1/M2)+Task6(web)+Task7(RN 过滤+C6+C2)+Task8(验收 §7)。✅ 全覆盖。早会改派/RN配置/代报 = Phase 2 不在本计划(符合"越简单越好")。
- **类型一致**: `responsibleWorkerId:Long`(后端)/`responsibleWorkerId`(DTO/web)/`assigned_to=BIGINT=Long`/清空 sentinel `-1L` 三处一致(Task2 定义, Task6 web 用)。✅
- **占位扫描**: 无 TBD;file:line 标"以审计为准,实现期以真实文件为准"(因 worktree behind origin/main 9 commits, 行号可能偏移, 故每 Task Step1 先定位)。✅
