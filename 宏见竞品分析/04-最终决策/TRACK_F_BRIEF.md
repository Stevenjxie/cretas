# TRACK F BRIEF — Sprint 2: N48 研发样品 → BOM → 报价

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **Sprint 2 chat (基于 Sprint 1 已完成 ASAP)**
> **Brief 来源**: `SPRINT_2_PLAN.md` §5.2 (Chat F — N48 5d 名义)
> **接收方**: Chat F (Sprint 2 worker)
> **派发方**: Chat 1 (Organizer)
> **派发日期**: 2026-05-15
> **预期完成**: ~3-3.5 工作日 (名义 5d, Claude 加速 ~1.7x)
> **PR 命名**: `[Sprint2-F] S-RD-1 研发样品→BOM→报价`
> **STATUS 文件**: `宏见竞品分析/04-最终决策/STATUS/TRACK_F_STATUS.md`
>
> **本文件原则**: 完全 self-contained。你不需要任何额外 context 就能动手干活。

---

## §1 项目 Onboarding (新人入门)

### Cretas 是什么

**Cretas Food Traceability System (白垩纪食品溯源系统)**
- 后端: Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA (Hibernate 6), 端口 10010
- 前端: Expo 53+ + TypeScript + React Navigation 7+ (React Native), 端口 3010
- AI 服务: Python + FastAPI + LLM API, 端口 8083
- 项目状态: Phase 3 核心完成 (82-85%)

源码位置: `C:\Users\Steve\my-prototype-logistics\`
- Java 后端: `backend/java/cretas-api/`
- RN 前端: `frontend/CretasFoodTrace/`
- Python 服务: `backend/python/`

### 当前业务背景

**客户**: 六扇门 (F006) 卤制品工厂 — ASAP 1.5 月交付 P0

**Sprint 2 在哪**:
- **Sprint 1 (Week 6-7) 已完成 ASAP** — 6 个 track 全 ship + main 已合并
- **Sprint 2 (Week 8-10) 现在启动** — 6 个 worker chat 并行

**完整业务流第一节** (Sprint 2 拼出来):
**研发样品 (N48, 你 Chat F)** → BOM → 报价 → 销售下单 → 审批 → 缺料分流 (N31, Chat E) → 采购建议 / 生产任务 → 钉钉群通知

**你是业务流的起点** — Cretas 工厂端缺研发样品管理, 你来从 0 建。

### 你是谁

**你 = Chat F = Sprint 2 worker**。Sprint 2 有 6 个并行 chat:
- Chat E: N31 销售→采购自动分流 (4d) 后端
- **Chat F (你)**: N48 研发样品→BOM→报价 (5d) 全栈
- Chat G: UX-A1 业务流程图导航 (10d) RN+Vue
- Chat H: UX-A2 行末操作下拉 (10d) RN+Vue
- Chat I: UX-A3 Sticky Footer 实时合计 (7d) RN+Vue
- Chat J: P-FIN-1 采购财务审核+三价标红 (3d) 后端+小前端

### 沟通方式

- **不要在本 chat 跟 organizer 战略讨论** — 战略已定, 你只执行
- **每日在 STATUS 文件追加 1 段** (格式见 §7)
- **完成一个 sub-task → 推 PR 不要等 Day 5**
- **碰到 blocker 立即在 STATUS 报**

---

## §2 任务范围与工时

### 单项目 (S-RD-1)

| 项目 | N# 编号 | 工时名义 | 工时加速 | 优先级 | 客户感知 |
|---|---|---|---|---|---|
| **研发样品 → BOM → 报价 链路** | S-RD-1 (MUST_COPY.N48) | 5d | ~3.5d | P0 | 研发员建样品 → 一键审核 → 自动生成 BOM + 推送报价任务 + 通知销售 |

### 客户原话证据

**来源**: 全流程文档 §1, MUST_COPY.md N48

> 研发员建样品 → 审核 → 自动生成 BOM → 推送报价任务

**Cretas 当前状态**: grep `Sample|sample|样品` — **餐饮端 SampleRecipeScreen 有, 工厂端无样品管理**。

### v3 销售红线 (Sprint 2 解禁)

完成本项目后, 销售可以说:
- ✅ "研发→样品→BOM→自动报价" (本项目)
- ✅ "AI 一句话从样品建 BOM" (本项目 AI Tool)
- ✅ "样品审核 approve 后钉钉群通知销售" (集成 Sprint 1 Track B1)

### 工时不达标怎么办

- 名义 5d 是上限。Claude 加速 ~1.7-2x → 实际预期 3-3.5 工作日
- 如果工时 >> 1.5 倍名义 (例如超过 8d), 立即在 STATUS 报 organizer
- Organizer 会决定: 减 scope (跳过 AI Tool) / 拉外援 / 降级样品照片用 placeholder

---

## §3 文件 Ownership (防冲突)

### 你的 (Chat F 独占, 你可以随便改)

```
backend/java/cretas-api/src/main/java/com/cretas/aims/
├── entity/sample/                                       ← 新建目录
│   └── SampleRequest.java                              ← NEW Entity
├── repository/sample/                                   ← 新建
│   └── SampleRequestRepository.java                    ← NEW
├── service/sample/                                      ← 新建目录
│   ├── SampleRequestService.java                       ← NEW 接口
│   └── impl/
│       └── SampleRequestServiceImpl.java               ← NEW 实现
├── controller/
│   └── SampleRequestController.java                    ← NEW
├── dto/sample/                                          ← 新建
│   ├── SampleRequestCreateDTO.java
│   ├── SampleRequestReviewDTO.java
│   └── SampleRequestResponse.java
└── ai/tool/impl/sample/                                 ← 新建目录
    └── SampleToBomTool.java                            ← NEW AI Tool

backend/java/cretas-api/src/main/resources/db/flyway/
└── V20260601_03__sample_request.sql                    ← NEW Flyway

frontend/CretasFoodTrace/src/
├── screens/rd/                                          ← 新建目录
│   ├── SampleRequestListScreen.tsx                     ← NEW
│   └── SampleRequestDetailScreen.tsx                   ← NEW
└── services/api/
    └── sampleApiClient.ts                              ← NEW
```

### 修改 (改前确认其他 chat 没动)

```
backend/.../service/quotation/QuotationService.java     ← 加 createTaskFromSample 方法
backend/.../service/quotation/impl/QuotationServiceImpl.java ← 实现
frontend/.../navigation/factory-admin/*StackNavigator.tsx ← 加 RD 子栈路由 (找研发员对应 Navigator)
```

### 共享只读 (改之前必须 ping organizer)

```
backend/java/cretas-api/src/main/java/com/cretas/aims/
├── entity/BaseEntity.java                              ← 跨 chat 共用
├── service/impl/IntentExecutorServiceImpl.java         ← AI 意图核心路由
└── ai/tool/AbstractBusinessTool.java                   ← Tool 基类

frontend/CretasFoodTrace/src/services/api/aiApiClient.ts
CLAUDE.md
.claude/rules/*
```

### 别 chat 的 (绝对不准碰)

- Chat E: `backend/.../service/shortage/`, `frontend/.../screens/sales/SalesOrderShortageReviewScreen.tsx`
- Chat G: `frontend/.../components/workflow/`, `web-admin/.../components/workflow/`
- Chat H: `frontend/.../components/list/RowActionBottomSheet.tsx`, `web-admin/.../components/list/RowActionMenu.vue`
- Chat I: `frontend/.../components/list/StickyFooterSummary.tsx`, `web-admin/.../components/list/TableFooter.vue`
- Chat J: `backend/.../service/purchase/PurchaseOrderApprovalFlow.java`

### Sprint 1 已 ship 你强依赖 (只读, 不改, 强 import)

```
backend/.../service/bom/BomService.java                  ← Sprint 1 Track D1 ship
  └─ 你调 BomService.createFromSample(sampleId, factoryId, userId) 自动建 BOM (强依赖!)
backend/.../service/attachment/AttachmentService.java   ← Sprint 1 Track C ship
  └─ 样品照片用 AttachmentService.upload (entity=SampleRequest)
backend/.../service/dingtalk/DingTalkBotService.java    ← Sprint 1 Track B1 ship
  └─ 样品审核 approve 后通知销售
backend/.../ai/client/PythonLLMClient.java               ← 你 SampleToBomTool 内调 LLM
frontend/.../screens/factory-admin/bom/BomConfigScreen.tsx ← Sprint 1 Track D1 ship
  └─ 样品审核 approve 跳 BomConfigScreen 预填
```

---

## §4 Day-by-Day 执行计划

### Day 1 — SampleRequest Entity + Flyway + Service 接口

#### 任务

1. **起 worktree**:
   ```bash
   cd C:\Users\Steve\my-prototype-logistics
   git worktree add ../my-prototype-logistics-sprint2-track-f feature/sprint2-track-f-n48-sample
   cd ../my-prototype-logistics-sprint2-track-f
   ```

2. **写 Flyway V20260601_03__sample_request.sql**:
   ```sql
   CREATE TABLE sample_requests (
       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
       factory_id VARCHAR(36) NOT NULL,
       sample_code VARCHAR(64) NOT NULL,         -- SR-2026-001 唯一编码
       sample_name VARCHAR(200) NOT NULL,         -- 样品名称
       customer_id VARCHAR(36),                   -- 关联客户 (可为空, 内部研发)
       customer_name VARCHAR(200),                -- 冗余
       spec TEXT,                                 -- 规格描述
       grade VARCHAR(50),                         -- 等级 (A/B/C/特级)
       main_material_id VARCHAR(36),              -- 主原料 FK to raw_material_types
       main_material_name VARCHAR(200),           -- 冗余
       urgency VARCHAR(20) DEFAULT 'NORMAL',      -- LOW/NORMAL/HIGH/URGENT
       status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', -- DRAFT/SUBMITTED/REVIEWING/APPROVED/REJECTED
       notes TEXT,                                -- 客户需求描述
       reference_sku VARCHAR(64),                 -- 参考 SKU (类似产品)
       photo_attachment_ids TEXT[],               -- 样品照片 attachment id 数组 (Sprint 1 Track C)
       tracking_records JSONB,                    -- 追踪记录 [{date, action, note}]
       expected_completion_date DATE,             -- 预期完成日期
       reviewer_id BIGINT,                        -- 审核人
       reviewed_at TIMESTAMP,
       review_comment TEXT,                       -- 审核意见
       generated_bom_id VARCHAR(36),              -- 审核 approve 后生成的 BOM ID
       generated_quotation_task_id VARCHAR(36),   -- 审核后推送的报价任务 ID
       created_at TIMESTAMP DEFAULT NOW(),
       updated_at TIMESTAMP DEFAULT NOW(),
       deleted_at TIMESTAMP NULL,
       created_by BIGINT,
       updated_by BIGINT,
       UNIQUE (factory_id, sample_code)
   );
   CREATE INDEX idx_sample_factory_status ON sample_requests(factory_id, status);
   CREATE INDEX idx_sample_customer ON sample_requests(customer_id);
   ```

3. **SampleRequest Entity** (继承 BaseEntity, per `.claude/rules/database-entity-sync.md`):
   ```java
   @Entity
   @Table(name = "sample_requests")
   @Getter @Setter @NoArgsConstructor
   public class SampleRequest extends BaseEntity {
       @Id
       @GeneratedValue(generator = "UUID")
       private String id;

       @Column(name = "factory_id", nullable = false)
       private String factoryId;

       @Column(name = "sample_code", nullable = false)
       private String sampleCode;

       @Column(name = "sample_name", nullable = false)
       private String sampleName;

       // ... 其他字段, 注意 camelCase Entity / snake_case Column
   }
   ```

4. **状态机** (写注释或单独 helper):
   ```
   DRAFT (草稿)
     ↓ submit()
   SUBMITTED (已提交)
     ↓ startReview() (主管 / 研发总监打开)
   REVIEWING (审核中)
     ↓ approve() — 必走自动建 BOM + 推报价任务 + 通知销售
   APPROVED
     ↓ reject() — 退回研发员
   REJECTED
   ```

5. **SampleRequestService 接口 commit** (CRUD + submit + startReview + approve + reject):
   ```java
   public interface SampleRequestService {
       SampleRequest create(String factoryId, SampleRequestCreateDTO dto, Long userId);
       Page<SampleRequest> list(String factoryId, String status, Pageable pageable);
       SampleRequest get(String factoryId, String id);
       SampleRequest submit(String factoryId, String id, Long userId);
       SampleRequest startReview(String factoryId, String id, Long reviewerId);
       SampleRequest approve(String factoryId, String id, SampleRequestReviewDTO dto, Long reviewerId);
       SampleRequest reject(String factoryId, String id, SampleRequestReviewDTO dto, Long reviewerId);
   }
   ```

6. **不实现 method body** Day 1. Commit Entity + Flyway + 接口 + DTO.

**DoD Day 1**: 表 + Entity + 接口 commit, 启动 `mvn spring-boot:run` Flyway 跑过, `\d sample_requests` 可见.

---

### Day 2 — SampleRequestServiceImpl + Controller + 5 API

#### 任务

1. **SampleRequestServiceImpl** 实现 6 个方法 (CRUD + submit + review + approve + reject)

2. **5 个 REST endpoint**:
   ```java
   @RestController
   @RequestMapping("/api/mobile/{factoryId}/sample-requests")
   public class SampleRequestController {

       @PostMapping
       public ApiResponse<SampleRequestResponse> create(@PathVariable String factoryId,
                                                         @RequestBody SampleRequestCreateDTO dto, ...);

       @GetMapping
       public ApiResponse<Page<SampleRequestResponse>> list(@PathVariable String factoryId,
                                                              @RequestParam(required = false) String status, ...);

       @GetMapping("/{id}")
       public ApiResponse<SampleRequestResponse> get(@PathVariable String factoryId, @PathVariable String id);

       @PostMapping("/{id}/submit")
       public ApiResponse<SampleRequestResponse> submit(@PathVariable String factoryId, @PathVariable String id, ...);

       @PostMapping("/{id}/review")
       public ApiResponse<SampleRequestResponse> review(@PathVariable String factoryId, @PathVariable String id,
                                                          @RequestBody SampleRequestReviewDTO dto, ...);
   }
   ```

   `review` endpoint body 含 `action: 'APPROVE' | 'REJECT'`, `comment`, `customFields`.

3. **approve 时核心编排** (强依赖 Sprint 1):
   ```java
   @Override
   @Transactional
   public SampleRequest approve(String factoryId, String id, SampleRequestReviewDTO dto, Long reviewerId) {
       SampleRequest sample = repository.findByFactoryIdAndId(factoryId, id).orElseThrow(...);
       if (!"REVIEWING".equals(sample.getStatus())) {
           throw new IllegalStateException("只有 REVIEWING 状态可审核");
       }
       sample.setStatus("APPROVED");
       sample.setReviewerId(reviewerId);
       sample.setReviewedAt(LocalDateTime.now());
       sample.setReviewComment(dto.getComment());

       // 1. 自动生成 BOM (Sprint 1 Track D1)
       String bomId = bomService.createFromSample(sample.getId(), factoryId, reviewerId);
       sample.setGeneratedBomId(bomId);

       // 2. 推送报价任务 (你新加 QuotationService.createTaskFromSample)
       String quotationTaskId = quotationService.createTaskFromSample(sample.getId(), factoryId);
       sample.setGeneratedQuotationTaskId(quotationTaskId);

       // 3. 钉钉通知销售 (Sprint 1 Track B1)
       String msg = String.format("样品 %s 审核通过, BOM 已生成 (%s), 报价任务已推送", sample.getSampleCode(), bomId);
       dingTalkBotService.sendNotification(factoryId, "样品审核通过", msg);

       return repository.save(sample);
   }
   ```

4. **QuotationService 修改** (新加 `createTaskFromSample`):
   ```java
   String createTaskFromSample(String sampleId, String factoryId);
   ```
   实现: 在 quotation_tasks 表插一行, 状态 PENDING, 关联 sampleId

5. **F001 dev seed 单测**: 端到端跑通 (create → submit → startReview → approve → 验证 BOM 自动建 + quotation task 自动建)

**DoD Day 2**: curl 跑通 5 个 endpoint + 审核 approve 后 BOM 自动建 + quotation task 建.

---

### Day 3 — AI Tool: SampleToBomTool + 历史相似推荐

#### 任务

1. **SampleToBomTool** (per `.claude/rules/ai-intent-tool-skill-architecture.md`):
   ```java
   @Slf4j
   @Component
   public class SampleToBomTool extends AbstractBusinessTool {

       @Autowired private SampleRequestService sampleService;
       @Autowired private PythonLLMClient pythonLLMClient;
       @Autowired private MaterialTypeRepository materialRepo;  // Sprint 1 Track D1

       @Override
       public String getToolName() { return "sample_to_bom"; }

       @Override
       public String getDescription() {
           return "根据样品 ID + 参考 SKU + 调整说明 (例如 '减 10% 包材'), 生成 BOM 草稿 JSON";
       }

       @Override
       public Map<String, Object> getParametersSchema() {
           return Map.of(
               "type", "object",
               "properties", Map.of(
                   "sampleId", Map.of("type", "string", "description", "样品 ID"),
                   "referenceSku", Map.of("type", "string", "description", "参考产品 SKU (可选)"),
                   "adjustments", Map.of("type", "string", "description", "调整说明 (自然语言)")
               ),
               "required", List.of("sampleId")
           );
       }

       @Override
       protected List<String> getRequiredParameters() { return List.of("sampleId"); }

       @Override
       protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
           String sampleId = getString(params, "sampleId");
           String referenceSku = getString(params, "referenceSku");
           String adjustments = getString(params, "adjustments");

           SampleRequest sample = sampleService.get(factoryId, sampleId);
           // 查参考 SKU 的 BOM (如果有)
           String systemPrompt = buildSampleToBomPrompt();
           String userPrompt = buildUserPrompt(sample, referenceSku, adjustments);
           String llmResponse = pythonLLMClient.chatLowTemp(systemPrompt, userPrompt);
           List<BomItem> bomDraft = parseBomJson(llmResponse);

           // 校验物料 ID 都在物料字典里 (强依赖 Sprint 1 Track D1)
           bomDraft = bomDraft.stream()
               .filter(item -> materialRepo.existsByIdAndFactoryId(item.getMaterialId(), factoryId))
               .toList();

           return Map.of(
               "status", "SUCCESS",
               "sampleId", sampleId,
               "bomDraft", bomDraft,
               "displayHint", "bom-draft-card"
           );
       }
   }
   ```

2. **绑定 intent** (Flyway V20260601_04__sample_intent.sql):
   ```sql
   INSERT INTO ai_intent_config (...)
   VALUES (gen_random_uuid(), 'SAMPLE_TO_BOM', '样品建BOM', 'AI_GENERATE',
           'sample_to_bom', '["建BOM","建配方","类似","参考SKU","样品BOM"]',
           true, 'LOW');
   ```

3. **Prompt template** (system prompt):
   ```
   你是 Cretas 食品溯源系统的 BOM 配方设计师。根据样品描述 + 参考 SKU + 调整说明, 生成 BOM 草稿 JSON。

   【输出格式】严格 JSON, 不要 markdown:
   {
     "items": [
       {"materialName":"<物料名>","materialId":"<物料 UUID 如知道, 否则空>","quantity":<数字>,"unit":"<g/kg/L 等>","percentage":<占比%>}
     ],
     "totalWeight": <总重量 g>,
     "explanation": "<为什么这么配>",
     "warnings": ["<需要注意的事项>"]
   }
   ```

4. **AI Skill** (可选, 编排 SampleToBomTool + BomCreateTool):
   - Skill name: `sample-to-bom-create`
   - 步骤: 1) `sample_to_bom` 生成草稿 → 2) `bom_create` 写入 BOM 表
   - 这是 advanced, 如果时间紧可跳过, Day 3 后半天加

5. **单测**:
   - mock PythonLLMClient
   - 验证 prompt 包含 sample 信息
   - 验证 LLM 返回非法物料 ID 时过滤掉
   - 验证 LLM 返回非 JSON 时降级

**DoD Day 3**: AIChat "给样品 SR-001 建 BOM 类似 SKU-201 但减 10% 包材" 触发 Tool, 返回 BOM 草稿 JSON.

---

### Day 4 — RN UI: SampleRequestListScreen + DetailScreen

#### 任务

1. **路由配置**:
   - 找研发员对应 Navigator (如 `RDStackNavigator.tsx` 或 `FactoryAdminStack` 子栈)
   - 加路由:
     ```typescript
     SampleRequestList: undefined;
     SampleRequestDetail: { sampleId: string };
     SampleRequestCreate: undefined;
     ```

2. **SampleRequestListScreen.tsx**:
   - 列表卡片显示: sampleCode / sampleName / customerName / urgency (颜色 chip) / status (chip)
   - 顶部 filter: status 多选 (DRAFT / SUBMITTED / REVIEWING / APPROVED / REJECTED)
   - 右下 FAB: 新建样品 → 跳 SampleRequestCreate
   - 紧急程度色卡: URGENT 红 / HIGH 橙 / NORMAL 蓝 / LOW 灰

3. **SampleRequestDetailScreen.tsx**:
   - 顶部样品摘要 (sampleCode / sampleName / customerName / urgency / status)
   - 中部样品照片 (用 Sprint 1 Track C Attachment API):
     ```typescript
     import { AttachmentService } from '../services/AttachmentService';
     const photos = await AttachmentService.list('SampleRequest', sampleId);
     ```
   - 追踪记录 timeline (JSONB tracking_records 数组)
   - 客户需求 notes
   - 底部操作按钮 (按状态动态):
     - DRAFT → 编辑 / 提交审核
     - SUBMITTED → (主管视角) 开始审核
     - REVIEWING → 通过 / 退回
     - APPROVED → 查看 BOM (跳 BomConfigScreen, Sprint 1 Track D1) / 查看报价任务
     - REJECTED → 复制重建

4. **Attachment 集成** (Sprint 1 Track C):
   ```typescript
   // 强依赖 Sprint 1 Track C ship 了 AttachmentService 和 PhotoPicker 组件
   import { PhotoPicker } from '../components/attachment/PhotoPicker';

   <PhotoPicker
     entityType="SampleRequest"
     entityId={sampleId}
     maxPhotos={10}
     onUploaded={(attachmentId) => refresh()}
   />
   ```

   **降级方案**: 如 Track C 没 ship, 用 placeholder 图片 + TODO 注释

5. **BomConfigScreen 跳转** (Sprint 1 Track D1):
   - approve 后 detail screen "查看 BOM" 按钮 → `navigation.navigate('BomConfig', { bomId: sample.generatedBomId })`

6. **类型安全** (`.claude/rules/typescript-type-safety.md`):
   - 禁 `useRoute<any>()`, 用 `useRoute<RouteProp<...>>()`
   - 禁 `as any`

**DoD Day 4**: 研发员账号能创样品 / 看列表 / 主管账号能审核 → approve 跳 BomConfigScreen.

---

### Day 5 — 链路联调 + 钉钉通知 + Demo + PR

#### 任务

1. **端到端测试**:
   1. 研发员登录 (F006) → 新建样品 SR-2026-001
   2. 上传 3 张样品照片 (依赖 Sprint 1 Track C)
   3. 填客户需求 + 参考 SKU
   4. 提交审核
   5. 主管登录 → 看到 SUBMITTED 列表
   6. 开始审核 → 状态变 REVIEWING
   7. (可选) AIChat: "给样品 SR-2026-001 建 BOM 类似 SKU-201"
   8. approve → 自动触发:
      - BOM 自动建 (Track D1 BomService.createFromSample)
      - quotation task 推送 (你的 QuotationService.createTaskFromSample)
      - 钉钉群通知 (Track B1 DingTalkBotService)
   9. 研发员收到通知, 跳 BomConfigScreen 完善 BOM 细节
   10. 销售员收到 quotation task → 开始填报价

2. **Demo 录** (2 分钟):
   - 串接 Chat E 的 N31 demo 形成 "完整业务流第一节" 视频:
     - 研发员建样品 (Chat F, 你) →
     - 主管审核 approve → BOM 自动建 + 报价任务 + 钉钉
     - 销售员填报价 → 客户下单
     - 销售单审批 → 缺料分流 (Chat E, N31)
     - 钉钉再次通知

3. **推 PR**:
   ```bash
   git push -u origin feature/sprint2-track-f-n48-sample
   gh pr create --title "[Sprint2-F] S-RD-1 研发样品→BOM→报价" --body "..."
   ```

   PR body 含:
   - 涉及文件清单 (后端 Entity + Service + Controller + DTO + Tool + Flyway + 前端 2 screen)
   - 测试方式 (单测 + curl + AI Tool + E2E demo)
   - 风险点 (BOM 自动建强依赖 Track D1 / 样品照片强依赖 Track C / 钉钉强依赖 Track B1)
   - 跟 Sprint 1 哪些 PR 依赖

**DoD Day 5**: PR + demo + STATUS 5 段完整.

---

## §5 关键参考文档

| 路径 | 用途 |
|---|---|
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\MUST_COPY.md` §B N48 | 业务定义 + 客户原话 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\SPRINT_2_PLAN.md` §5.2 | Day-by-day 来源 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\01-客户档案\NUMBERING_MAP.md` | S-RD-1 编号 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\TRACK_D1_BRIEF.md` | Sprint 1 BOM 工厂端 UI (你强依赖 BomService.createFromSample) |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\TRACK_C_BRIEF.md` | Sprint 1 Attachment (样品照片用) |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\TRACK_B1_BRIEF.md` | Sprint 1 钉钉 PoC |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\ai-intent-tool-skill-architecture.md` HARD | Tool 注册 / Skill 编排 / 禁 IntentHandler |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\api-response-handling.md` | 统一响应 |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\database-entity-sync.md` | Entity / Flyway |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\field-naming-convention.md` | camelCase/snake_case |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\typescript-type-safety.md` | 禁 `as any` |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\concurrent-edit-safety.md` HARD | 6 chat 并行 commit 安全 |

---

## §6 接口契约 (Interface Contracts)

### 后端 → 前端 API

**POST /api/mobile/{factoryId}/sample-requests** — 创建样品
```typescript
// Request
{ sampleName, customerId?, spec, grade, mainMaterialId, urgency, notes, referenceSku?, expectedCompletionDate? }
// Response
{ success: true, data: { id, sampleCode (auto-gen), status: 'DRAFT', ...所有字段 } }
```

**POST /api/mobile/{factoryId}/sample-requests/{id}/submit** — 提交审核
```typescript
// Response
{ success: true, data: { ...sample, status: 'SUBMITTED' } }
```

**POST /api/mobile/{factoryId}/sample-requests/{id}/review** — 审核
```typescript
// Request
{ action: 'APPROVE' | 'REJECT', comment }
// Response (approve)
{ success: true, data: { ...sample, status: 'APPROVED', generatedBomId, generatedQuotationTaskId } }
```

### AIChat Tool 输出

```json
{
  "status": "SUCCESS",
  "sampleId": "SR-2026-001",
  "bomDraft": [
    {"materialId": "MT-001", "materialName": "牛肉", "quantity": 200, "unit": "g", "percentage": 80},
    {"materialId": "MT-002", "materialName": "盐", "quantity": 10, "unit": "g", "percentage": 4},
    {"materialId": "MT-003", "materialName": "糖", "quantity": 5, "unit": "g", "percentage": 2}
  ],
  "totalWeight": 250,
  "explanation": "参考 SKU-201 配方, 包材减 10%",
  "displayHint": "bom-draft-card"
}
```

### Sprint 1 依赖接口 (你必 import)

| Sprint 1 提供 | 你怎么用 |
|---|---|
| Track D1 `BomService.createFromSample(sampleId, factoryId, userId)` | approve 时调, 返回 bomId |
| Track D1 `MaterialTypeRepository.existsByIdAndFactoryId` | SampleToBomTool 校验物料 ID |
| Track C `AttachmentService.upload / list` | 样品照片上传 |
| Track C `PhotoPicker` (RN 组件) | SampleRequestDetailScreen 直接 import |
| Track B1 `DingTalkBotService.sendNotification` | approve 后通知销售 |

---

## §7 PR / Status Update 流程

### 每日 STATUS 更新

文件: `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\STATUS\TRACK_F_STATUS.md`

格式 (每天追加):
```markdown
## Day N (YYYY-MM-DD)
- ✅ 完成: X / Y / Z
- 🟡 进行中: A
- ❌ Blocker: B (需 organizer 协调)
- 明日计划: C / D
```

### PR 流程

可以分 sub-PR 也可以一个大 PR:

**推荐**: Day 1-2 ship 后端 PR ("[Sprint2-F-1] S-RD-1 后端 Entity + Service + Controller"), Day 3-4 ship AI Tool + RN PR ("[Sprint2-F-2] S-RD-1 AI Tool + RN UI"), Day 5 集成 + Demo.

**或者**: 一个大 PR `[Sprint2-F] S-RD-1 研发样品→BOM→报价` Day 5 一次推.

跟 organizer 商量, 默认走第二种 (一次大 PR 减少 review 次数).

### 并发安全 commit

```bash
# 用 specific paths
git commit -m "feat: SampleRequest Entity + Flyway" -- backend/.../entity/sample/SampleRequest.java backend/.../resources/db/flyway/V20260601_03__sample_request.sql
```

### Blocker 上报模板

```markdown
## Day N (YYYY-MM-DD)
- ❌ Blocker: Track D1 BomService.createFromSample 还没 ship
- 影响: Day 2 approve 自动建 BOM 流程不通
- 建议方案: A) 等 D1; B) 用 mock BomService; C) 提供 stub 让我先继续
- 需要 organizer: 拍板 A/B/C
```

---

## §8 不要做 (Do Not Do)

### 严格禁止

1. **不要重写 BomService / AttachmentService / DingTalkBotService** — 你只调用, 不修改 (它们是 Sprint 1 ship 的, 你的 PR 不应碰)

2. **不要改 ownership 外的文件** (§3):
   - 不准改 `BaseEntity.java`
   - 不准改 `IntentExecutorServiceImpl.java`
   - 不准改其他 chat ownership

3. **不要创建 IntentHandler** — Handler 架构已废弃 (`.claude/rules/ai-intent-tool-skill-architecture.md`)

4. **不要直接 @Autowired AIIntentService 到 SampleToBomTool** — 循环依赖, 用 `@Lazy`

5. **不要降级处理** (CLAUDE.md):
   - LLM 失败时不要返回假 BOM, 应该 throw + 让 caller 决定
   - 但 LLM 返回非法物料 ID 可以过滤 (这是合理校验)

6. **不要用 `as any`** (`.claude/rules/typescript-type-safety.md`):
   - 路由用 `useRoute<RouteProp<...>>()`

7. **不要并发改同一文件** (`.claude/rules/concurrent-edit-safety.md`):
   - 用 git worktree
   - `git commit -- F1 F2` 锁定 scope

8. **不要 Tool name 重名**:
   - `sample_to_bom` 全仓 grep 唯一

9. **不要 BaseEntity 缺 audit 字段** (`.claude/rules/database-entity-sync.md`):
   - 表必须含 `created_at / updated_at / deleted_at`
   - 触发器自动更新 updated_at

---

## §9 验收清单

### 功能验收

- [ ] **后端**: SampleRequest Entity 继承 BaseEntity, 表有 audit 字段
- [ ] **后端**: 5 个 REST endpoint 跑通 (create/list/get/submit/review)
- [ ] **后端**: approve 时自动生成 BOM (调 Track D1 BomService.createFromSample)
- [ ] **后端**: approve 时推送 quotation task (你新加 QuotationService.createTaskFromSample)
- [ ] **后端**: approve 时钉钉通知销售 (调 Track B1 DingTalkBotService)
- [ ] **AI**: SampleToBomTool 注册到 ToolRegistry, intent SAMPLE_TO_BOM 绑定
- [ ] **AI**: AIChat "给样品 SR-001 建 BOM 类似 SKU-201" 返回 BOM 草稿
- [ ] **前端**: SampleRequestListScreen 接入路由, 显示列表 + 过滤 + 紧急色卡
- [ ] **前端**: SampleRequestDetailScreen 显示样品 + 照片 + 追踪 + 审核按钮
- [ ] **前端**: 样品照片上传 (依赖 Track C Attachment)
- [ ] **前端**: approve 后跳 BomConfigScreen (依赖 Track D1)
- [ ] **集成**: 端到端 demo 跑通 (创建 → 提交 → 审核 → BOM/报价/钉钉)

### 销售红线验收

- [ ] **红线**: 销售可说 "研发→样品→BOM→自动报价"
- [ ] **红线**: 销售可说 "AI 一句话从样品建 BOM"
- [ ] **红线**: 销售可说 "样品审核 approve 后钉钉群通知销售"

### 技术验收

- [ ] PR merged 到 main
- [ ] 无新增 `as any`
- [ ] 无新增 `catch (error: any)`
- [ ] Flyway migration 文件存在 (sample_request + ai_intent_config)
- [ ] 单元测试覆盖 SampleRequestServiceImpl + SampleToBomTool
- [ ] E2E demo 视频录制 (跟 Chat E 串成业务流第一节)

---

## §10 客户场景对照

### 客户期望

**六扇门 F006 (卤制品工厂)** 希望:
1. **研发员低成本建样品** — 不用纸笔 / Excel, 手机上点几下建样品记录
2. **审核 approve 后自动生成 BOM** — 不用人手再建一遍 BOM 表
3. **报价任务自动推送销售** — 不用打电话提醒销售去报价
4. **钉钉群通知** — 销售实时知道有新样品要报价
5. **AI 协助建 BOM** — "建个类似 SKU-201 的, 但牛肉用低价的" 一句话

### Cretas 的差异化卖点

宏见 ERP 范式: 研发员 Excel 填表 → 主管邮件审核 → 主管手工录 BOM → 销售员手工录报价 → **数据 5 处不同步**, **客户嫌断点多**。

Cretas Sprint 2 完成后:
- ✅ 研发员 RN App 建样品 (含照片附件)
- ✅ 主管 RN App 审核, approve 触发自动化流水线
- ✅ BOM / 报价任务 / 钉钉通知 一次性触发, 0 断点
- ✅ AIChat 协助建 BOM (类似产品 + 调整说明)

### 跟其他 Chat 的串联

```
Chat F (你 N48 研发样品→BOM→报价) — 业务流起点
       ↓ 提供 BOM 数据 + 报价任务
Chat E (N31 销售→采购分流) — 销售单审批后调你建的 BOM
       ↓ 推荐采购
Chat J (P-FIN-1 采购财务审核) — 接 Chat E 的采购建议, 三价标红 + 财务审批
       ↓
钉钉群通知
```

完整业务流第一节: **样品 (你) → BOM → 销售下单 → 缺料分流 (E) → 采购 → 财务审核 (J) → 钉钉**

### 跟 UX 三件套的集成

- Chat G (UX-A1 流程图导航): 研发员首页 BentoGrid 加 "今日样品工作流" 卡片, 节点显示 (DRAFT 5 / REVIEWING 3 / APPROVED 12)
- Chat H (UX-A2 行末操作下拉): SampleRequestList 卡片长按 → BottomSheet "提交审核 / 复制 / 删除 / 跟 AI 说"
- Chat I (UX-A3 Sticky Footer): SampleRequestList 底部显示 "共 N 个 / 紧急 X 个 / 待审 Y 个 + 📊 AI 分析"

---

## 附录: 关键命令速查

### 启动开发环境

```powershell
# 后端 Java (10010)
cd C:\Users\Steve\my-prototype-logistics\backend\java\cretas-api
mvn spring-boot:run

# 后端 Python (8083, 用于 LLM 调用)
cd C:\Users\Steve\my-prototype-logistics\backend\python
uvicorn main:app --port 8083

# 前端 (3010)
cd C:\Users\Steve\my-prototype-logistics\frontend\CretasFoodTrace
npm start
```

### 验证 Flyway 跑过

```powershell
# 数据库连接 (本地 PG)
psql -h localhost -U postgres -d cretas_db -c "\d sample_requests"
```

### Git Worktree

```powershell
cd C:\Users\Steve\my-prototype-logistics
git worktree add ../my-prototype-logistics-sprint2-track-f feature/sprint2-track-f-n48-sample
cd ../my-prototype-logistics-sprint2-track-f
```

### 安全 Commit

```powershell
git commit -m "feat: SampleRequestServiceImpl + 5 endpoint" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/sample/impl/SampleRequestServiceImpl.java backend/java/cretas-api/src/main/java/com/cretas/aims/controller/SampleRequestController.java
```

---

**Brief 结束。Day 1 开始干活。第一件事: 创建 STATUS 文件 + 启动 worktree, 然后写 Flyway + Entity + Service 接口。**