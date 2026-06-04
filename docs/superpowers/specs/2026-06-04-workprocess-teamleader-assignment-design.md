# 工序-小组长 分配流程 设计 (v2)

**日期**: 2026-06-04
**修订**: v2 — 吸收客户"越简单越好"约束 + superpowers 对抗审计(20 agent, 7 必修 M1-M7 + 6 建议 C1-C6, 0 证伪)。
**触发**: 六扇门真实报工 —— 每道工序由不同小组长自己报(莫云滚揉/魏振江熟制焯水/徐修油/永珍装盒),组长登录只报自己那道。

---

## 0. 客户约束(决定范围 — 微信 2026-06-04 原话)

- "报工的都是小组长" —— 报工人恒=登录小组长。**确认**。
- "工序责任基本上是固定的"(同产品/不同批次/不同天)—— **默认绑定就覆盖绝大多数**。
- "早会改派" —— 客户**下周见面才和工厂确认**,且"不知道他们能不能习惯"。**未确认 → Phase 2**。
- ⭐ "前期要**越简单越好,先落地,跑起来再慢慢加功能**,前期加太多他们抵触心理很强" —— **强制最小化**。

→ **Phase 1 只做"默认绑定 + 组长只报自己那道",其余全 defer。**

---

## 1. 分期

| | Phase 1(本期,最小落地) | Phase 2(defer,跑起来再加) |
|---|---|---|
| 工序→小组长 默认绑定(产品级,过夜延续) | ✅ | |
| spawn 时 task.assignedTo = 默认责任人 | ✅ | |
| 组长报工列表只看/报自己的工序 | ✅(含 M1 兜底) | |
| **后端归属/角色鉴权(M3 安全, 不可 defer)** | ✅ | |
| 配置入口 web-admin 产品工序管理 | ✅ | |
| 完工入库归主管(C6) | ✅ | |
| 早会临时改派(单批 task 级) | | ⏳ |
| RN 端配置/改派屏 | | ⏳ |
| 代报工(targetWorkerId UI) | | ⏳(后端通道按 M3 收口,UI 不出) |

---

## 2. 现状能力 / 缺口(审计已核实, 含 file:line)

| 能力 | 现状(审计核实) |
|---|---|
| `work_process_tasks.assigned_to` | ✅ 存在, BIGINT=Long, 对齐 users.id; 有 `idx_wpt_assignee`(`WorkProcessTask.java:109-110`)。**但 spawn 现在不写它**(`WorkProcessTaskServiceImpl:98-110` builder 无 `.assignedTo()`)→ 现存所有 task assigned_to 全 null。 |
| 改单道 assignedTo | ✅ `PUT /api/mobile/{factoryId}/work-process-tasks/{id}`(updatePlan, `:268` 已 setAssignedTo)—— Phase 2 改派用, 无需新代码。 |
| 按 assignedTo 过滤列表 | ⚠️ 只有扁平端点 `GET /work-process-tasks`(`WorkProcessTaskController:74-87` findByFilters)支持; **RN 报工屏调的 batch 端点 `GET /production/batches/{id}/work-process-tasks`(`:93-99` listByBatch)不吃该参数**(`yieldReportApi.ts:289`→`YieldStepReportScreen.tsx:167`)。 |
| ProductWorkProcess 责任人 | ❌ entity(`:18-62`)/DTO(`:17-43`)/service create(`:45-52`)/update(`:85-87`)/toDTO(`:116-124`)**五处全缺** —— 本设计要全加。 |
| submitReport 归属鉴权 | ❌ `YieldReportServiceImpl:105-106` 不读 task.assignedTo; `:126` targetWorkerId 无角色门控; 第二条链 `ProcessWorkReportingController.submitNormalReport:81-105` 同洞。 |
| `assign-workers` | ⚠️ `POST /batches/{id}/assign-workers` 只建 BatchWorkSession 考勤(`ProcessingServiceImpl:2329-2397`), **不碰 work_process_tasks.assigned_to** —— 不能用于本功能(M7 纠正)。 |
| 权限注解 | ⚠️ **`@PreAuthorize` 在本仓库是 NO-OP**(全 profile `exclude=SecurityAutoConfiguration`, 见 `RequireRole.java:9-20`)。真生效的是自定义 `@RequireRole` 拦截器(C1)。 |

---

## 3. 数据层

### 3.1 ProductWorkProcess 加默认责任人(**带 to_regclass 守卫** — M4)
`product_work_processes` 是 entity-only 表(active Flyway location `classpath:db/flyway` 0 处 CREATE TABLE, 靠 Hibernate ddl-auto 建)→ fresh-DB Flyway 先于 ddl-auto, 裸 ALTER 会 "relation does not exist" 阻断启动。**必须守卫**(照抄 `V20260906_02:14-20`):
```sql
DO $$
BEGIN
    IF to_regclass('public.product_work_processes') IS NOT NULL THEN
        ALTER TABLE product_work_processes
          ADD COLUMN IF NOT EXISTS responsible_worker_id BIGINT;
        COMMENT ON COLUMN product_work_processes.responsible_worker_id IS '默认责任小组长 user_id; spawn 时作为 work_process_task.assigned_to 默认值';
    END IF;
END $$;
```
- **不加 FK 到 users**(与本域 assigned_to/worker_id 既有约定一致, 实现期勿擅自加)。
- **主库 cretas, 新列无需 GRANT/RLS**(主库表 owner=cretas_user, db/flyway 0 RLS; smartbi GRANT-gap 硬规则只适用 smartbi_db)。

### 3.2 迁移版本号(**防跨 session 撞号** — M5)
worktree 本地最高 V20260916, 但 **origin/main 已到 V20260919**(本分支 behind 9 commits)。`out-of-order=false` 下低号合并后静默跳过或同号报错阻断启动(项目已复发≥3次)。
取号: `git ls-tree -r origin/main backend/java/cretas-api/src/main/resources/db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | tail -1` 之上 → **用 ≥ V20260919_02**(必须查 origin/main, 不能 ls 本地)。merge 后部署前再 `... | sort | uniq -d` 查重。

---

## 4. 后端(Phase 1)

### 4.1 ProductWorkProcess 责任人字段(**五处全改** — M6)
1. entity `responsibleWorkerId: Long`
2. `ProductWorkProcessDTO` 同字段
3. service create 透传(`:45-52`)
4. service update 透传(`:85-87`)
5. toDTO 回填(`:116-124`)
- 清空默认: update 是局部(仅非 null 才 set), 无法传 null 清空 → 约定 sentinel(如 `-1` = 清空)或专用 PATCH。**实现期定**。
- 配默认走现有 `ProductWorkProcessController` 写端点 `PUT /api/mobile/{factoryId}/product-work-processes/{id}`(全路径), **限主管角色**: `@RequireRole({"factory_super_admin","workshop_supervisor","department_admin"})`(**不是 @PreAuthorize** — C1)。

### 4.2 spawnTasks 设默认 assignedTo(M1)
`WorkProcessTaskServiceImpl` spawn 每道 task 时 `.assignedTo(productWorkProcess.responsibleWorkerId)`(未配=null)。
**null 语义**: null = 未指派 = 该批此道"谁都可报"(配合 §4.4 兜底, 不会因 null 锁死)。

### 4.3 后端归属/角色鉴权(**安全, 不可 defer** — M3)
报工提交两条链都加守卫:
- `YieldReportServiceImpl.submitReport`(+ `recordMaterialInput:943-944` 同洞)
- `ProcessWorkReportingController.submitNormalReport:81-105`(同根 live 代码, **必须一起改**)

规则:
1. 取 `task.assignedTo`: **null 或 == 登录 userId** 才允许提交; 否则 403「该工序已指派给他人, 您无权报工」。**主管角色(factory_super_admin/workshop_supervisor)豁免**(为 Phase 2 代报留)。
2. `targetWorkerId` **仅主管角色可传**; operator 传则忽略(强制 effectiveWorker=登录 userId), 防伪造报工人。

### 4.4 报工列表过滤(**改对端点 + null 兜底** — M2 + M1)
- 给 `WorkProcessTaskController.listByBatch:93-99` + `WorkProcessTaskServiceImpl:149` 加**可选** `assignedTo` 参数(推荐, RN 改动最小, 保留 processOrder 升序)。
- **M1 兜底(零回归)**: 该批工序**全为 null → 不过滤, 显示全部道**; 仅当**至少一道非 null** 才按本人过滤。SQL: `AND (:assignedTo IS NULL OR t.assigned_to = :assignedTo OR t.assigned_to IS NULL)` —— 但"全 null 显示全部"需在 service 判定(先查该批有无非 null, 有才传 assignedTo)。
- RN `YieldStepReportScreen.tsx:167` tasks 加载补传当前 userId(真正过滤点在这, **不是** YieldBatchSelect — 那是选批次不是选工序)。
- 跨工厂由 `JwtAuthInterceptor:167-185` 路径级保证; assignedTo 仅本厂内**视图过滤**, 真行级隔离靠 §4.3 后端守卫。

### 4.5 完工入库归主管(C6)
组长只看自己那道后, done 卡(需全道 COMPLETED, `YieldStepReportScreen.tsx:186-197`)对单 operator 永不可达 → 整批完工入库(`handleSettleDay:688`)**归主管**: 主管报工屏不过滤(看全部道), done/完工按钮对 operator 隐藏、对主管可见。

---

## 5. web-admin(Phase 1 唯一配置入口)
- **产品工序管理页**: 每道工序加"默认责任小组长"下拉(数据源: `GET /users/role/operator`), 配产品级默认(影响今后所有批次)。回显需 §4.1 toDTO 回填。
- 配默认限主管(同 §4.1 @RequireRole)。

## 6. RN(Phase 1)
- **组长报工列表只看自己的工序**(§4.4)。
- **报工人恒=登录组长**(report.workerId=登录账号; targetWorkerId 不发)。
- **清理已撤代报选择器的残留死代码**(C2): operator fetch(`:110-122`)、orphan state(`reporterPickerOpen`/`selectedReporterId`)、`reporterSheet`等死样式(`:1334-1348`)、3 处 `...reporterFields()` 注入(`:426/489/569`)、相关注释。targetWorkerId 后端通道保留(按 M3 收口)。

---

## 7. 验收(headed)
1. web 配: 猪舌 修油→徐 / 滚揉→莫云 / 焯水·熟制→魏振江 / 装盒→永珍。
2. 建批次 spawn → 各道 task.assigned_to = 对应组长 id。
3. 莫云登录 RN → 报工列表**只见滚揉**; 魏振江只见焯水/熟制 → 各报各的, report.worker_id=本人。
4. **反向安全(M3)**: 莫云用 API 直接 POST 报魏振江的焯水 → **403**; operator 传 targetWorkerId → 被忽略。
5. **回归(M1)**: f006_worker1(未配任何分配) / 全 null 批次 → 报工列表**看到全部道**(不锁死)。
6. 主管登录 → 看全部道 + 可完工入库(C6)。

## 8. 实现单元(交 writing-plans)
1. Flyway 迁移(守卫+取号 ≥V20260919_02, M4/M5) + ProductWorkProcess.responsibleWorkerId(五处, M6)。
2. 配默认端点 @RequireRole 主管 + 清空语义(C1)。
3. spawnTasks 设 assignedTo=默认(M1) + 单测。
4. **后端归属/角色鉴权**: submitReport + recordMaterialInput + submitNormalReport 三处守卫 + targetWorkerId 门控 + 单测(M3)。
5. listByBatch 加 assignedTo 参数 + 全-null 兜底(M2/M1) + 单测。
6. RN 报工屏 tasks 加载传 userId + 过滤(M2)。
7. web-admin 工序管理"默认责任小组长"下拉(回显)。
8. 完工入库归主管 + operator 隐藏 done(C6)。
9. 清理代报死代码(C2)。
10. headed E2E + 反向安全用例 + f006_worker1 回归(§7)。

## 9. Phase 2 backlog(defer)
- 早会临时改派: 单批 `PUT /work-process-tasks/{id}` 改 task.assignedTo(**只改批次级, 不动产品级默认** — C5); 限主管 @RequireRole。RN/web 改派屏。
- 代报工 UI(targetWorkerId, 主管用)。
- 同产品当天多批次: 改 B 批不影响 A 批(验收 C5)。

## 10. 审计结论
superpowers 对抗审计(20 agent): 7 必修(M1-M7)已全部并入上方对应章节; 6 建议(C1 @RequireRole/C2 死代码/C5 产品vs批次/C6 完工归属 已并入, C3 start懒赋值不在报工链/C4 读端点视图过滤 为文档级备注); 0 证伪。**结论: 按本 v2(已修)可交付 writing-plans 开建。**
