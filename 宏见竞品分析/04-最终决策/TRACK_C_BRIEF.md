# Track C 工作 Brief — Attachment + 单据打印 + 三价对比 bug + RBAC 审计

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **接收对象**: 4 个并行 Track Chat 中的 Track C (本 chat)
> **执行机制**: 你 (本 chat) 独立做实现, Organizer Chat 1 协调 + review + merge
> **工时**: 名义 **11 工作日** / Claude 加速预期 **~6-7 工作日**
> **完全 self-contained**: 你不需要任何 organizer chat 的对话历史 — 这份 brief 就是全部 context

---

## §1 项目 Onboarding

**项目**: 白垩纪食品溯源系统 (Cretas Food Traceability System)

**技术栈**:
- **后端**: Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA (Hibernate 6)
- **前端**: React Native (Expo 53+) + TypeScript + React Navigation 7+
- **AI 服务**: Python (FastAPI) + LLM API (Aliyun/ZhipuAI/DeepSeek)
- **附件存储**: OSS (阿里云对象存储, Cretas 已有基础)

**业务背景**: 食品溯源 SaaS, 覆盖食品厂 + 餐饮 2 条业务线。客户 F006 (六扇门卤制品工厂) 为 ASAP 客户, 已签约要求 **1.5 个月内** 交付 P0 修复 + 新功能。Sprint 1 (本) 是这个交付期内的核心冲刺。

**你的角色**: Track C 工作 Chat, 与 Track A (Canvas)、Track B (AI 钉钉 + 抄码品)、Track D (BOM + 工序) 并行干活, 由 Organizer Chat 1 协调。**4 个 Chat 同时跑**, 所以严格按 brief 执行避免冲突。

**项目根目录**: `C:\Users\Steve\my-prototype-logistics`

**重要项目规范** (强制遵守):
- `.claude/rules/ai-intent-tool-skill-architecture.md` — AI Tool-Skill 架构 (新加 Tool 必看)
- `.claude/rules/api-response-handling.md` — 统一响应 `{ success, data, message }`
- `.claude/rules/typescript-type-safety.md` — 禁止 `as any`
- `.claude/rules/database-entity-sync.md` — Entity 与表必须同步
- `.claude/rules/field-naming-convention.md` — 表/列 snake_case, Java/TS camelCase
- `.claude/rules/jwt-token-handling.md` — 前端用 `cretas_access_token` (localStorage 在 web-admin, SecureStore 在 RN)
- `.claude/rules/concurrent-edit-safety.md` — **共享文件改前 git status, commit 用 `git commit -- F1 F2` 防 husky 吞别 chat 文件**

**关键命名约定**:
- Java 实体: PascalCase (`Attachment`)
- 数据库: snake_case (`attachments` 表, `entity_type` 列)
- API JSON: camelCase (`"entityType"`)
- TS 接口: camelCase (`interface Attachment { entityType: string }`)

---

## §2 任务范围与工时

你负责 4 个项目, 全部独立但都在 Track C ownership 内:

| # | 项目 | 工时 | 优先级 | 类型 |
|---|---|---|---|---|
| 1 | **C-ATT-1 通用 Attachment 系统** | 5d | P0 | 新建基础能力, 5 业务接入 |
| 2 | **C-PRT-1 单据打印 PDF 起步** | 2d | P0 | 5 单据 PDF + AIChat Tool |
| 3 | **三价对比新建后不刷新 bug** | 2d | P0 (客户反馈) | 前端 SmartBI + 事件触发 bug |
| 4 | **C-RBAC-1 RBAC 仓管隔离审计** | 2d | P0 (客户反馈) | 验证 PR #423, 写测试 |
| | **合计** | **11d** | | |

**Claude 加速倍数**: 1.7-2x, 实际 ~6-7 工作日 (你应该在 1 周左右完成)

**关键依赖**:
- **Attachment 是基础能力**: Day 3 后 Track B/D 会 import 它的 Service 接口 (用来挂照片). 所以 **Day 1-3 优先把 attachment 接口骨架 + DDL + Service 公开**, 让其他 Track 能 import.
- **三价对比 bug**: 客户 P0 反馈, 客户原话: "三家对比没有... 可能是一些数据的 bug". 现状: PR #297 D2 BOM algo UI 已 ship, 但三价对比在新采购单创建后未刷新.
- **RBAC 审计**: 验证 PR #423 已 ship 的 RBAC 价格保护框架完整性, 重点验证仓管角色完全看不到价格字段. 客户原话: "其他的话就尽量少让仓管员去参与什么什么价格类的不要让他们去参与".

**Sprint 整体目标**: ASAP Phase 0 + Sprint 1 全部内容 (48 人天名义), 4 chat 并行 ~1.5 周完成. 你这 11d 是其中一块.

---

## §3 文件 Ownership (强制边界)

### 你的 (Track C 拥有, 你可以任意创建/修改):

**Attachment (C-ATT-1)**:
- NEW `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/Attachment.java`
- NEW `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/AttachmentEntityType.java` (枚举)
- NEW `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/FileCategory.java`
- NEW `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/AttachmentRepository.java`
- NEW `backend/java/cretas-api/src/main/java/com/cretas/aims/service/attachment/` (整个目录)
- NEW `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/AttachmentController.java`
- NEW `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/attachment/` (Tool 实现)
- NEW Flyway migration: `backend/java/cretas-api/src/main/resources/db/migration/V20260516_01__attachment.sql`
- NEW `frontend/CretasFoodTrace/src/services/api/attachmentApi.ts`
- NEW `frontend/CretasFoodTrace/src/components/attachment/` (UI 组件)
- NEW `web-admin/src/api/attachment.ts` (web 端 API)
- NEW `web-admin/src/components/attachment/` (web 上传组件)

**单据打印 (C-PRT-1)**:
- NEW `backend/python/printing/` (新建 Python 模块, 注册到 main.py)
- NEW `backend/python/printing/templates/` (jinja2 模板目录)
- NEW `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/PrintController.java` (Java 端 entry, 调用 Python)
- NEW `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/print/` (AIChat "打印这张单" Tool)
- 修改 `frontend/CretasFoodTrace/src/screens/sales/` (列表行末加打印按钮)
- 修改 `web-admin/src/views/sales/` 等列表页

**三价对比 bug 修复**:
- 修改 `frontend/CretasFoodTrace/src/screens/smartbi/` 下相关页面
- 修改 `web-admin/src/views/smartbi/` 下相关页面 (如果 bug 在 web 端)
- 可能需要修改 `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/PurchaseOrderController.java` 增加事件发布

**RBAC 审计 (C-RBAC-1)**:
- NEW `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/finance/RBACAuditTool.java`
- NEW `backend/java/cretas-api/src/test/java/com/cretas/aims/security/RBACWarehouseManagerIsolationTest.java`
- NEW `scripts/rbac-warehouse-mgr-audit-2026-05-15/` (5x5 negative regression scripts)

### 你**不准动** (其他 Track 拥有 或 共享只读):

**Track A 拥有**:
- `frontend/CretasFoodTrace/src/screens/lowcode/`
- `backend/.../service/impl/DecorationServiceImpl.java`
- `backend/.../ai/tool/impl/pagedesign/`
- `backend/.../ai/tool/impl/decoration/`

**Track B 拥有**:
- `backend/.../service/dingtalk/`
- `backend/.../entity/integration/DingTalkWebhookLog.java`
- `frontend/.../screens/shared/LabelScanScreen.tsx`
- `backend/.../ai/tool/impl/material/` (抄码品扩展)

**Track D 拥有**:
- `backend/.../entity/bom/`
- `backend/.../service/workprocess/`
- `backend/.../service/impl/BomServiceImpl.java`
- `frontend/.../screens/management/MaterialSpecManagementScreen.tsx`

**共享只读** (4 track 都不准随意改, 改前必须 ping organizer):
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/BaseEntity.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/IntentExecutorServiceImpl.java`
- `frontend/CretasFoodTrace/src/services/api/aiApiClient.ts`
- `CLAUDE.md` + `.claude/rules/*`

**Git 策略**: 用 worktree 隔离, 每个 PR 一个独立 branch:
```bash
git worktree add ../cretas-track-c-attachment HEAD
cd ../cretas-track-c-attachment
git checkout -b feature/asap-track-c-att-1-attachment
```

---

## §4 Day-by-Day 执行计划

### Day 1-5: C-ATT-1 通用 Attachment 系统

#### Day 1 — Spec 阅读 + Service 接口设计

**目标**: 看完 SCHEMA_DESIGN.md §2.3 + 设计 Service 接口骨架 (供 Track B/D 后续 import)

**具体步骤**:
1. 完整读 `宏见竞品分析/01-客户档案/SCHEMA_DESIGN.md` §2.3 (行 522-731), 关键决策:
   - 多态 entity_type + entity_id (不强外键)
   - file_storage = OSS / R2 / LOCAL
   - file_hash 用 SHA256 去重
   - 权限跟随 entity (不单独权限模型)
   - 下载用 OSS pre-signed URL (1h 有效)
2. grep 现有 OSS 上传代码 (Cretas 有基础): `grep -r "ALIBABA_ACCESSKEY_ID\|OSSClient" backend/java/cretas-api/src`
3. 起 worktree + branch `feature/asap-track-c-att-1`
4. 写 `AttachmentService` 接口 (放在 service/attachment/):
   ```java
   public interface AttachmentService {
       Attachment register(String factoryId, RegisterAttachmentRequest req, Long userId);
       List<Attachment> queryByEntity(String factoryId, EntityType type, String entityId);
       Map<String, Long> countByEntities(String factoryId, EntityType type, List<String> ids);
       Attachment getById(String factoryId, String id);
       String generateUploadUrl(String factoryId, String fileName, String fileType);
       String generateDownloadUrl(String factoryId, String id);  // 1h pre-signed
       void softDelete(String factoryId, String id, Long userId);
       Attachment update(String factoryId, String id, UpdateAttachmentRequest req, Long userId);
   }
   ```
5. **不**实现 method body, 先 commit 接口定义让 Track B/D 看到, 防止 Day 3 后他们 import 时签名变动.
6. **DoD**: 接口 commit + 推到 branch, 在 `04-最终决策/STATUS/TRACK_C_STATUS.md` 写一段 Day 1 done.

#### Day 2 — Flyway Migration + Entity + Repository

**目标**: DB schema + JPA Entity 全部就位, 能跑 spring-boot:run 不报错

**具体步骤**:
1. 创建 Flyway migration `V20260516_01__attachment.sql`, **完整复制** SCHEMA_DESIGN.md §2.3 DDL (DDL 在行 537-595, 含 CREATE TABLE + 5 个 INDEX + 4 个 CHECK CONSTRAINT + COMMENT)
2. 创建 `Attachment.java` Entity, **完整复制** SCHEMA_DESIGN.md §2.3 JPA Entity (行 600-682). 关键:
   - 继承 `BaseEntity` (拿到 createdAt/updatedAt/deletedAt 自动)
   - `@Where(clause = "deleted_at IS NULL")` 软删除
   - 内嵌 `EntityType` enum (18 个值) + `FileCategory` enum (6 个值)
   - `@PrePersist` 自动赋 UUID
3. 创建 `AttachmentRepository extends JpaRepository<Attachment, String>`:
   ```java
   List<Attachment> findByFactoryIdAndEntityTypeAndEntityId(String factoryId, EntityType type, String entityId);
   @Query("SELECT a.entityId, COUNT(a) FROM Attachment a WHERE a.factoryId = :fid AND a.entityType = :type AND a.entityId IN :ids GROUP BY a.entityId")
   List<Object[]> countByEntities(String fid, EntityType type, List<String> ids);
   Optional<Attachment> findByFactoryIdAndId(String factoryId, String id);
   Optional<Attachment> findByFactoryIdAndFileHash(String factoryId, String fileHash);  // 去重
   ```
4. 本地启动 Java 后端验证 (用 `mvn spring-boot:run`, **不要** `java -jar` 见 server-operations.md 第 11 条), 启动日志看 Flyway apply 成功
5. **DoD**: `localhost:10010/api/mobile/health` 返回 OK + 数据库 `\d attachments` 能看到表 + STATUS 更新

#### Day 3 — Service 实现 + Controller + 通用 API

**目标**: 5 个核心 endpoint 跑通 (列出在 SCHEMA_DESIGN.md §2.3 API 契约), Track B/D 可以 import 使用

**具体步骤**:
1. 实现 `AttachmentServiceImpl`:
   - `register` — 注册元数据 (前端 OSS 直传完成后调), 算 SHA256 去重 (同 factory 同 hash 报 409 或返回已有 attachment)
   - `queryByEntity` — 按 factoryId + entityType + entityId 查
   - `countByEntities` — 批量计数 (列表页徽章用)
   - `generateUploadUrl` — 调阿里 OSS pre-signed PUT URL, 5min 有效
   - `generateDownloadUrl` — 调 OSS pre-signed GET URL, 1h 有效
   - `softDelete` — 验证 uploadedBy == userId 或 admin, 标记 deletedAt
2. 实现 `AttachmentController` 8 个 endpoint:
   - `GET /api/mobile/{factoryId}/attachments?entityType=X&entityId=Y`
   - `GET /api/mobile/{factoryId}/attachments/{id}`
   - `GET /api/mobile/{factoryId}/attachments/{id}/download` (302 重定向到签名 URL)
   - `POST /api/mobile/{factoryId}/attachments/upload-url` (返回 pre-signed PUT URL)
   - `POST /api/mobile/{factoryId}/attachments` (注册元数据)
   - `PUT /api/mobile/{factoryId}/attachments/{id}` (改描述/tag)
   - `DELETE /api/mobile/{factoryId}/attachments/{id}` (软删)
   - `POST /api/mobile/{factoryId}/attachments/batch-by-entity` (批量计数)
3. **权限**: 所有 endpoint 走 Cretas 现有 `@PreAuthorize` 框架, attachment 权限 = entity 权限 (查时调对应模块的 hasAccess())
4. 统一响应格式 `{ success, data, message }` (见 api-response-handling.md)
5. 写 unit test 至少覆盖 register / queryByEntity / softDelete
6. **公开通知 Track B/D**: 在 STATUS 写 "Day 3 done: AttachmentService 接口稳定, Track B/D 可 `@Autowired AttachmentService` 使用". Organizer 会 propagate.
7. **DoD**: Postman/curl 验证 8 个 endpoint 全部通, STATUS 更新

#### Day 4 — 5 业务接入 (前后端打通)

**目标**: 5 个业务模块都能挂附件 (附件实体类型 5 选 5)

**5 业务接入清单** (后端 + 前端 RN):
1. **客户跟踪** (`CUSTOMER_TRACKING`): 跟踪记录详情页加"附件"区域
2. **采购订单** (`PURCHASE_ORDER`): 采购单详情页加"附件"区域 (拍照留单据原始凭证)
3. **质检** (`QUALITY_CHECK`): 质检单详情页加"附件" (检测报告/照片)
4. **生产任务** (`PRODUCTION_BATCH`): 生产批次页加"附件" (现场照片)
5. **财务凭证** (`PAYMENT_VOUCHER`): 财务单据页加"附件" (发票扫描)

**前端 (RN) 通用组件**:
- 在 `frontend/CretasFoodTrace/src/components/attachment/` 创建:
  - `AttachmentList.tsx` — 列出某实体的全部附件 (缩略图 + 文件名)
  - `AttachmentUploadButton.tsx` — 拍照/选文件按钮, 完成调 `/upload-url` → OSS PUT → `/attachments` 注册
  - `AttachmentViewer.tsx` — 点击预览 (图片用 RN Image, PDF 用 Linking)
- Service: `frontend/CretasFoodTrace/src/services/api/attachmentApi.ts` 封装 8 个 endpoint
- 5 业务详情页都 import `<AttachmentList entityType=... entityId=... />` 和 `<AttachmentUploadButton />`

**Web-admin 通用组件**:
- 类似在 `web-admin/src/components/attachment/` 建 Vue 组件
- 5 业务列表/详情页同样 import

**DoD**: 5 业务详情页都能看到 attachment 区, 都能上传, 上传后刷新能看到. STATUS 更新.

#### Day 5 — 文件上传 OSS 集成 + 缩略图 + PR

**目标**: 端到端走通 OSS 上传 + 图片自动生缩略图 + PR 推送 review

**具体步骤**:
1. 完善 OSS 集成: 用 Cretas 现有 OSS credentials (env `ALIBABA_ACCESSKEY_ID` / `ALIBABA_SECRET_KEY`), bucket 用 `cretas-media`. 配置: 见 `.claude/rules/CREDENTIAL-MANAGEMENT.md`.
2. **缩略图**: register 时 if `fileType.startsWith("image/")`, 用 Java `BufferedImage` (或 ImageMagick CLI) 生成 200x200 缩略图, 上传到 OSS 单独 key, 存 `thumbnailUrl`. 缩略图生成异步 (用 `@Async` 或线程池), 失败不阻塞 register.
3. **权限测试**: 用 F006 测试账号 (六扇门), 验证 Tenant A 的人查不到 Tenant B 的附件 (factoryId 隔离)
4. **集成测试**: 在 `AttachmentControllerTest` 写 6 case:
   - register 成功
   - queryByEntity 跨 factory 隔离
   - softDelete 权限校验 (非上传者非 admin 报 403)
   - 重复 hash 去重 (返回已有)
   - generateUploadUrl 生成的 URL 5min 内能 PUT
   - 缩略图自动生成 (异步, 用 Awaitility 等)
5. 推 PR `[Track-C] C-ATT-1 通用 Attachment 系统`:
   - body 写: 涉及文件清单 / DB migration / 5 业务接入截图 / 性能测试 (1000 attachment 查询时间)
   - PR labels: `track-c`, `enhancement`, `sprint-1`
6. **DoD**: PR 推送, organizer review 中. STATUS 写 "Day 5 done, C-ATT-1 PR 推送".

---

### Day 6-7: C-PRT-1 单据打印 PDF 起步

#### Day 6 — PDF 模板设计 + 3 单据 (销售/采购/报价)

**目标**: 3 个核心单据能生成 PDF 下载

**架构决策**:
- **PDF 生成放 Python 服务** (端口 8083), Python 用 `weasyprint` (中文支持好) 渲染 jinja2 模板. Cretas 已有 `pdf-creator` skill 可参考.
- Java 后端做 entry, 接到 `GET /api/mobile/{factoryId}/print/sales-order/{id}` → 内部 HTTP 调 Python `/api/printing/sales-order` → 返回 PDF stream.
- 这样 Java 不动 (避免引 weasyprint 依赖), Python 集中处理.

**具体步骤**:
1. 在 `backend/python/printing/` 建模块:
   - `printing/api/print.py` — 5 个 endpoint
   - `printing/templates/sales_order.html` — jinja2 模板 (有公司 logo + 字段占位)
   - `printing/templates/purchase_order.html` — 采购单模板, **加二维码** (用 `qrcode` 库, 二维码内容 = 采购单 ID 用于扫码入库)
   - `printing/templates/quotation.html` — 报价单
   - `printing/services/pdf_renderer.py` — weasyprint 渲染 + 字体注册 (中文用 `Noto Sans CJK SC`, 见 pdf-creator skill)
2. 在 `backend/python/main.py` 注册 router:
   ```python
   from printing.api import print as print_api
   app.include_router(print_api.router, prefix="/api/printing", tags=["Printing"])
   ```
3. Java 端 `PrintController.java`:
   ```java
   @GetMapping("/api/mobile/{factoryId}/print/sales-order/{id}")
   public ResponseEntity<byte[]> printSalesOrder(@PathVariable String factoryId, @PathVariable Long id) {
       // 1. 校验权限 (查得到这个 sales order 就给打印)
       // 2. 调 Python /api/printing/sales-order 拿 PDF bytes
       // 3. Content-Disposition: attachment; filename=sales-order-XXX.pdf
   }
   ```
4. 3 单据前端按钮: 在 RN 列表行末加"打印 PDF" 按钮, 点击调 `/print/...` endpoint, 收到 PDF 用 `expo-file-system` 下载到本地 → `expo-sharing` 分享或 `Linking.openURL` 打开
5. **DoD**: 3 单据能下载到 PDF, 中文显示正常 (没乱码方块), PDF 内容字段全 (用 F006 真实数据测).

#### Day 7 — 生产任务单 + 领料单 + AIChat Tool + PR

**目标**: 5 单据全部完成 + AIChat 能说"打印这张单"

**具体步骤**:
1. 加 2 个剩余模板:
   - `production_task.html` — 生产任务单 (产品 + 数量 + 工序流水)
   - `material_requisition.html` — 领料单 (BOM 折算的领料明细, **依赖 Track D 的 BOM**, 暂用 mock 数据, Track D ship 后再连真实)
2. 各列表页加按钮: 生产任务列表 / 领料单列表
3. AIChat Tool: 在 `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/print/PrintDocumentTool.java`:
   ```java
   @Component
   public class PrintDocumentTool extends AbstractBusinessTool {
       @Override
       public String getToolName() { return "print_document"; }

       @Override
       public String getDescription() {
           return "打印单据 PDF (销售订单/采购订单/报价单/生产任务单/领料单). " +
                  "用户说'打印这张单'/'下载 PDF'/'发给供应商'触发. " +
                  "参数: documentType (5 选 1), documentId.";
       }

       @Override
       public Map<String, Object> getParametersSchema() {
           return Map.of(
               "type", "object",
               "properties", Map.of(
                   "documentType", Map.of("type", "string",
                       "enum", List.of("SALES_ORDER", "PURCHASE_ORDER", "QUOTATION", "PRODUCTION_TASK", "MATERIAL_REQUISITION")),
                   "documentId", Map.of("type", "string")
               ),
               "required", List.of("documentType", "documentId")
           );
       }

       @Override
       protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) {
           String type = getString(params, "documentType");
           String id = getString(params, "documentId");
           String url = "/api/mobile/" + factoryId + "/print/" + toEndpointPath(type) + "/" + id;
           return buildSimpleResult("PDF 生成中, 即将下载",
               Map.of("downloadUrl", url, "documentType", type, "documentId", id));
       }
   }
   ```
4. 数据库绑定 (Tool 自动注册到 ToolRegistry, 但要在 ai_intent_config 加一条触发意图):
   ```sql
   INSERT INTO ai_intent_config (id, intent_code, intent_name, intent_category, tool_name,
     keywords, is_active, sensitivity_level)
   VALUES (gen_random_uuid(), 'PRINT_DOCUMENT', '打印单据', 'DOCUMENT_OPERATION', 'print_document',
     '["打印", "PDF", "发给供应商", "下载单子"]', true, 'LOW');
   ```
5. 推 PR `[Track-C] C-PRT-1 单据打印 PDF 起步`
6. **DoD**: 5 单据可下载, AIChat 说"打印这张采购单 PO-20260514-001" 能返回 download URL.

---

### Day 8-9: 三价对比新建采购单后不刷新 Bug 修复

**Bug 背景** (客户原话, 来自 `01-客户档案/六扇门第三次-May7-part1.md` 行 115-185):
> "这边不是有一个三价对比分析吗"
> 系统答: "是自动生成的, 根据以往的采购价会生成的"
> 客户测试: 新建一个采购单后, "三家对比没有" — "**可能是一些数据的 bug**"

**bug 性质**: 数据源问题. 三价对比应该用入库价 (即 PurchaseReceipt 实际收货价) 算近期价, 但新建采购单后没刷新对比数据.

**关联**: PR #297 D2 BOM algo UI 已 ship (BOM 计价框架已有), 但三价对比是 SmartBI 模块的独立组件, 没接事件.

#### Day 8 — 复现 + 定位

**具体步骤**:
1. 用 F006 测试账号登录 web-admin (端口 8086 / 47.100.235.168 prod 或 localhost:5173 dev)
2. 进入 SmartBI 三价对比页面 (具体路径 grep `三价` 或 `price-comparison`):
   ```bash
   grep -r "三价\|priceComparison\|price-comparison\|三家" web-admin/src/views/
   grep -r "三价\|priceComparison\|price-comparison\|三家" frontend/CretasFoodTrace/src/screens/
   ```
3. 找到三价对比页面, 看它怎么拉数据 (是 SmartBI Java endpoint 还是 Python /api/smartbi/*)
4. 用 Postman/浏览器 devtools 看初始加载请求 + 返回. 记录 endpoint URL + response shape.
5. 新建一个采购单 (任意原材料, 任意价格)
6. 重新加载三价对比页, 看新单是否出现. **不出现 = 复现成功**.
7. 排查方向 (按优先级):
   - **方向 A**: 后端聚合数据缺事件触发. PurchaseOrder 创建后没有 publish 事件, SmartBI 物化视图/缓存没刷新.
   - **方向 B**: 前端缓存. 页面 mount 时没拉新数据, 用旧 cache.
   - **方向 C**: 数据源粒度错. 三价对比按入库价算, 而不是采购单价. 新建采购单没入库不应该出现 (这是设计而非 bug).
8. 重点 grep:
   ```bash
   # 后端: 三价对比的数据源
   grep -r "PriceComparison\|priceComparison\|三价\|三家对比" backend/java/cretas-api/src/main/java/
   grep -r "PriceComparison\|priceComparison\|三价" backend/python/smartbi*/
   # 前端: 三价对比页缓存
   grep -r "PriceComparison\|priceComparison\|三价" web-admin/src/
   ```
9. **DoD**: 写一个清晰的 bug RCA, 在 STATUS 写 "Day 8: 三价对比 bug RCA = [一句话原因] + 修复方案 [一句话方案]"

#### Day 9 — 修复 + 测试 + PR

**修复方案** (根据 Day 8 RCA):

**如果是方向 A** (事件缺失):
- 在 `PurchaseOrderService.create()` 或 `PurchaseReceiptService.confirm()` 发 Spring `ApplicationEvent`:
  ```java
  applicationEventPublisher.publishEvent(new PurchaseReceiptConfirmedEvent(...));
  ```
- SmartBI 模块订阅, 触发物化视图刷新 / 缓存失效

**如果是方向 B** (前端缓存):
- 前端三价对比页用 `useFocusEffect` (RN) 或 `onMounted` (Vue) 强制重拉, 不用 cache
- 或在采购单创建成功后, 全局 EventEmitter 通知三价对比页 invalidate

**如果是方向 C** (设计 vs bug 误会):
- 在 UI 加提示: "三价对比基于入库价, 新建采购单需先入库才会出现"
- 在 STATUS 标注: "客户可能误会, 已加 UI 提示" + ping organizer 让他跟客户 confirm

**测试**:
1. 写 Playwright E2E (用 `e2e-web-admin` skill 或参考 `scripts/customer-audit-e2e-2026-05-14-qhj/`):
   - 步骤: 登录 → 看初始三价对比 (记 baseline) → 新建采购单 → 入库 → 回三价对比页 → 验证新数据出现
2. 用 F006 真实数据跑一遍

**PR**: `[Track-C] fix(smartbi): 三价对比新建采购单后未刷新`
- PR body 写客户原话 + RCA + 修复方案 + E2E 测试结果

**DoD**: PR 推送, E2E 通过, STATUS 更新 "Day 9: 三价对比 bug PR 推送"

---

### Day 10-11: C-RBAC-1 RBAC 仓管隔离审计

**背景**: PR #423 已 ship RBAC 价格保护框架 (canViewPrice 已 sweep 15-35 views). 客户原话 (`六扇门第三次-May7-part2.md`):
> "其他的话就尽量少让那个仓管员去参与什么什么价格类的不要让他们去参与"
> "做仓管的他年纪都比较大文化素质很低的"

**任务性质**: 审计 + 测试, **不是重写 RBAC**. 验证现有框架完整, 写自动化 + 5x5 negative regression.

#### Day 10 — 看 PR #423 + 写自动化测试

**具体步骤**:
1. 拉 PR #423 看:
   ```bash
   gh pr view 423 --json title,body,files,mergedAt
   gh pr diff 423
   ```
   关注: 哪些 view 加了 `canViewPrice`? 后端哪些 endpoint 加了价格字段过滤? 测试覆盖了哪些角色?
2. grep 现有 RBAC 仓管角色定义:
   ```bash
   grep -rn "warehouse_manager\|warehouse_mgr\|WAREHOUSE_MGR\|仓管" backend/java/cretas-api/src/main/java/ | head -50
   ```
3. 列出**所有应该对仓管隐藏价格的 view**:
   - 采购订单详情 (purchase order detail) — 价格字段
   - 采购单列表 (purchase order list) — 单价列
   - 销售订单详情/列表 — 单价
   - 收货单详情 (purchase receipt) — 价格
   - 库存详情 (inventory) — 成本价
   - 报价单 (quotation) — 报价
   - 三价对比 (SmartBI) — 整个页面
   - 财务凭证 — 金额
   - BOM 详情 — 原料价
   - 工序成本 — 工时成本
4. 写自动化测试 `RBACWarehouseManagerIsolationTest.java`:
   - 用 SpringBootTest + MockMvc
   - 准备 5 个角色 token: super_admin, factory_admin, purchase_mgr, sales_mgr, warehouse_mgr
   - 对每个上述 view 的 endpoint, 用 warehouse_mgr token 调:
     - 看返回 JSON 是否含价格字段 (`price`, `unitPrice`, `totalAmount`, `cost` 等)
     - 含 = FAIL (RBAC 漏过)
     - 不含 = PASS
   - 同时验证 super_admin 能看到 (sanity check, 不是 over-filter)
5. **DoD**: 自动化测试跑通, 列出每个 view 的 pass/fail 状态. STATUS 写 "Day 10: 自动化测试覆盖 X 个 view, Y 个 pass, Z 个 fail (需修)"

#### Day 11 — 5x5 Negative Regression + 文档 + PR

**5x5 Negative Regression**:
- 5 个非授权角色 × 5 个价格敏感视图 = 25 个 negative case
- 5 角色 (除 super_admin / factory_admin / purchase_mgr / sales_mgr 之外, 仓管员是核心):
  - `warehouse_mgr` (仓管员, 主测试)
  - `operator` (操作工)
  - `quality_inspector` (质检员)
  - `customer_service` (客服)
  - `viewer` (只读账号)
- 5 视图:
  - 采购订单详情 (`GET /api/mobile/{factoryId}/purchase-orders/{id}`)
  - 销售订单详情
  - 三价对比 (SmartBI)
  - BOM 详情
  - 财务凭证

**测试脚本**: `scripts/rbac-warehouse-mgr-audit-2026-05-15/`
- `run-regression.sh` — bash 脚本, 用 curl 跑 25 个 case
- `expected-rbac-matrix.csv` — 期望矩阵 (哪些角色能看, 哪些不能)
- `report.md` — 跑完输出报告

**报告输出格式**:
```markdown
| Role / View | 采购详情 | 销售详情 | 三价对比 | BOM 详情 | 财务凭证 |
|---|---|---|---|---|---|
| warehouse_mgr | PASS (无价格) | PASS | PASS (403) | PASS (无价格) | PASS (403) |
| operator | ... | ... | ... | ... | ... |
```

**新建 AI Tool**: `RBACAuditTool.java`, 让管理员能在 AIChat 问 "审计仓管 RBAC", Tool 返回当前 RBAC 矩阵:
```java
@Component
public class RBACAuditTool extends AbstractBusinessTool {
    @Override public String getToolName() { return "rbac_audit"; }
    @Override public String getDescription() { return "审计仓管员 RBAC 价格隔离. 返回 5x5 矩阵报告."; }
    // doExecute: 跑 25 个 negative case, 返回 JSON 矩阵
}
```

**修复发现的漏洞** (如果 Day 10 发现 fail):
- 如 X 个 view 价格字段未过滤, 加 `@JsonView` 或 DTO 投影
- 后端 endpoint 加 `@PreAuthorize("hasAuthority('price:view')")` (如果角色无此权限, 自动 403)

**PR**: `[Track-C] C-RBAC-1 仓管隔离审计 + 5x5 negative regression`
- 含审计报告 + 自动化测试 + RBACAuditTool + 修复(if any)

**DoD**: 25/25 negative case 全 pass, RBACAuditTool 注册成功, PR 推送.

---

## §5 关键参考文档

| 文档 | 路径 | 用途 |
|---|---|---|
| **Attachment schema 完整 spec** | `宏见竞品分析/01-客户档案/SCHEMA_DESIGN.md` §2.3 (行 522-731) | 表 DDL + Entity + 8 API + AIChat Tool 全套 |
| **三价对比 bug 客户原话** | `宏见竞品分析/01-客户档案/六扇门第三次-May7-part1.md` 行 110-185 (三价对比) + 行 593-599 (chat0 提取的 bug 行动项) | bug 重现描述 |
| **RBAC 客户原话** | `宏见竞品分析/01-客户档案/六扇门第三次-May7-part2.md` 行 186-190 (仓管不参与价格) + 行 552-562 (RBAC 真实 gap) | RBAC 需求源头 |
| **PDF 打印闭环描述** | `六扇门第三次-May7-part2.md` 行 537-550 | 完整流程 (采购单 PDF → 二维码 → 仓管扫码 → 拍照附件) |
| **Track 文件 ownership** | `宏见竞品分析/04-最终决策/DISPATCH_OVERVIEW.md` §2 | 4 track 的边界 |
| **API 响应规范** | `.claude/rules/api-response-handling.md` | 后端统一 `{ success, data, message }` |
| **Tool-Skill 架构** | `.claude/rules/ai-intent-tool-skill-architecture.md` | 加 Tool 必看 (PrintDocumentTool, RBACAuditTool) |
| **TS 类型安全** | `.claude/rules/typescript-type-safety.md` | 禁止 `as any` |
| **数据库 Entity 同步** | `.claude/rules/database-entity-sync.md` | BaseEntity 必填字段 + Flyway |
| **并发编辑安全** | `.claude/rules/concurrent-edit-safety.md` | **commit 前 git status, 用 `git commit -- F1 F2`** |
| **服务器运维** | `.claude/rules/server-operations.md` | 部署 / 端口 / systemd |
| **凭证管理** | `.claude/rules/CREDENTIAL-MANAGEMENT.md` | OSS / LLM keys |
| **Cretas 项目规范** | `CLAUDE.md` | 端口配置 / 目录结构 / 开发命令 |
| **Cretas 现有代码审计** | `宏见竞品分析/03-审计过程/AUDIT_FRESH_C_CODE.md` | Cretas 现有 OSS / RBAC / SmartBI 代码状态 |
| **PR #423 RBAC 价格保护** | `gh pr view 423` | RBAC 框架已 ship 范围 |
| **PR #297 D2 BOM algo UI** | `gh pr view 297` | 三价对比 bug 的上下文 |

**辅助 skill (按需调用)**:
- `pdf-creator` — 中文 PDF 生成 (Day 6-7 用)
- `e2e-web-admin` — Playwright E2E (Day 9 三价对比测试, Day 11 RBAC 测试)
- `commit-commands:commit` — 用 safe-commit 包装 (避免 husky 吞别 chat 文件)

---

## §6 接口契约

### 6.1 Attachment API (完整 8 endpoint)

**Base**: `/api/mobile/{factoryId}/attachments`

#### 6.1.1 注册附件元数据 (前端 OSS 直传完后调)

```
POST /api/mobile/{factoryId}/attachments
Authorization: Bearer <cretas_access_token>
Content-Type: application/json

Body:
{
  "entityType": "PURCHASE_ORDER",
  "entityId": "PO-20260514-001",
  "fileName": "delivery-proof.jpg",
  "fileUrl": "https://cretas-media.oss-cn-shanghai.aliyuncs.com/uploads/2026/05/15/abc.jpg",
  "thumbnailUrl": null,  // 后端异步生成
  "fileSize": 245680,
  "fileType": "image/jpeg",
  "fileCategory": "PHOTO",
  "fileHash": "<SHA256>",
  "businessTag": "DELIVERY_PROOF",
  "description": "收货时拍的证据"
}

Response 200:
{
  "success": true,
  "data": { "id": "<UUID>", "fileUrl": "...", "thumbnailUrl": null, ... },
  "message": "上传成功"
}

Response 409 (重复 hash):
{
  "success": false,
  "data": { "existingId": "<UUID>" },
  "message": "同 hash 文件已存在"
}
```

#### 6.1.2 查某实体的所有附件

```
GET /api/mobile/{factoryId}/attachments?entityType=PURCHASE_ORDER&entityId=PO-20260514-001

Response:
{
  "success": true,
  "data": [
    { "id": "...", "fileName": "...", "fileUrl": "...", "thumbnailUrl": "...", "uploadedAt": "2026-05-15T10:30:00", ... }
  ],
  "message": null
}
```

#### 6.1.3 获取 OSS 预签 PUT URL (前端直传)

```
POST /api/mobile/{factoryId}/attachments/upload-url
Body: { "fileName": "abc.jpg", "fileType": "image/jpeg" }
Response:
{
  "success": true,
  "data": {
    "uploadUrl": "https://cretas-media.oss-cn-shanghai.aliyuncs.com/uploads/2026/05/15/abc.jpg?signature=...&expires=...",
    "fileUrl": "https://cretas-media.oss-cn-shanghai.aliyuncs.com/uploads/2026/05/15/abc.jpg"
  }
}
```

#### 6.1.4 其他 endpoint 见 SCHEMA_DESIGN.md §2.3 API 契约表

### 6.2 业务模块如何接入 Attachment

**Java 侧**: 任何 Service 都可 `@Autowired AttachmentService` 用:

```java
@Autowired
private AttachmentService attachmentService;

// 查附件计数 (列表页徽章)
public List<PurchaseOrderDTO> list(...) {
    List<PurchaseOrderDTO> orders = ...;
    Map<String, Long> attachmentCounts = attachmentService.countByEntities(
        factoryId, EntityType.PURCHASE_ORDER,
        orders.stream().map(PurchaseOrderDTO::getId).toList());
    orders.forEach(o -> o.setAttachmentCount(attachmentCounts.getOrDefault(o.getId(), 0L)));
    return orders;
}
```

**RN 前端**: 任何详情页 import 通用组件:

```tsx
import { AttachmentList } from '@/components/attachment/AttachmentList';
import { AttachmentUploadButton } from '@/components/attachment/AttachmentUploadButton';

<View>
  <AttachmentList entityType="PURCHASE_ORDER" entityId={order.id} />
  <AttachmentUploadButton entityType="PURCHASE_ORDER" entityId={order.id} onUploaded={refresh} />
</View>
```

### 6.3 单据打印 PDF endpoint 协议

```
GET /api/mobile/{factoryId}/print/{documentType}/{documentId}
documentType: sales-order | purchase-order | quotation | production-task | material-requisition

Response 200:
Content-Type: application/pdf
Content-Disposition: attachment; filename="purchase-order-PO-20260514-001.pdf"
<binary PDF>
```

### 6.4 RBAC 审计输出 schema

```json
{
  "success": true,
  "data": {
    "matrix": [
      {
        "role": "warehouse_mgr",
        "views": {
          "purchase_order_detail": { "status": "PASS", "hiddenFields": ["unitPrice", "totalAmount"] },
          "sales_order_detail": { "status": "PASS", "hiddenFields": ["unitPrice"] },
          "price_comparison": { "status": "PASS", "httpCode": 403 },
          "bom_detail": { "status": "FAIL", "leakedFields": ["materialCost"] },
          "payment_voucher": { "status": "PASS", "httpCode": 403 }
        }
      },
      ...
    ],
    "summary": { "totalCases": 25, "passed": 23, "failed": 2 }
  }
}
```

---

## §7 PR / Status Update 流程

### 7.1 每日 STATUS 更新

每天结束在 `宏见竞品分析/04-最终决策/STATUS/TRACK_C_STATUS.md` 追加一段 (该目录可能要新建):

```markdown
## Day N (YYYY-MM-DD)
- 完成: X / Y / Z
- 进行中: A
- Blocker: B (需 organizer 协调)
- 明日计划: C / D
- 关键决策/发现: [一句话]
```

如果目录不存在: `mkdir -p 宏见竞品分析/04-最终决策/STATUS` 然后创建文件.

### 7.2 Track 间同步信号

**Day 3 后**: 在 STATUS 写明确通知:
> "Day 3 done. AttachmentService 接口稳定, Track B/D 可 `@Autowired AttachmentService` + RN 端 `import { AttachmentList } from '@/components/attachment/AttachmentList'` 使用. 接口签名 commit 在 `<sha>`, 不会再变."

Organizer 会 propagate 给 Track B/D.

### 7.3 PR 流程

完成一项推一个 PR (不要一个 PR 4 个项目):

**PR 1**: `[Track-C] C-ATT-1 通用 Attachment 系统` (Day 5 推)
**PR 2**: `[Track-C] C-PRT-1 单据打印 PDF 起步` (Day 7 推)
**PR 3**: `[Track-C] fix(smartbi): 三价对比新建采购单后未刷新` (Day 9 推)
**PR 4**: `[Track-C] C-RBAC-1 仓管隔离审计 + 5x5 negative regression` (Day 11 推)

**PR body 模板**:
```markdown
## Summary
- [Track-C] 编号 项目名
- 客户原话/背景 (如适用)
- 涉及文件清单
- 测试方式
- 风险点 / 已知限制

## 验收清单
- [ ] DDL apply 成功 (如适用)
- [ ] 单元测试通过
- [ ] E2E 通过 (如适用)
- [ ] 5 业务接入 (Att) / 5 单据 (PRT) / 5x5 矩阵 (RBAC)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

### 7.4 Blocker 处理

碰到这些立即 ping organizer (在 STATUS 标 ❌ + 一行解释):
- 需要改共享只读文件 (BaseEntity / IntentExecutorServiceImpl / aiApiClient.ts / CLAUDE.md)
- 与 Track A/B/D 文件 ownership 冲突
- 三价对比 bug RCA 不明确 (需 Steve 确认是 bug 还是设计)
- RBAC 审计发现严重漏洞 (>5 个 view fail)
- OSS / LLM 凭证缺失或无效

### 7.5 Commit 规范

**强制用 safe commit** (并发 chat 防 husky 吞文件):

```bash
git add backend/.../Attachment.java backend/.../AttachmentRepository.java
git status --short   # 验证只有你的文件 staged
git commit -- backend/.../Attachment.java backend/.../AttachmentRepository.java -m "$(cat <<'EOF'
feat(attachment): 添加 Attachment Entity + Repository (C-ATT-1 Day 2)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

注意: `git commit -- F1 F2` 仅 commit 列出文件, 即使别 chat 也 staged 别文件也不会被吞.

---

## §8 不要做 (强制约束)

1. **不要 refactor SmartBI 整体** — 三价对比 bug 只修单点 (事件触发 / 前端缓存), 不重写 SmartBI 模块
2. **不要写新 N# 编号** — 4 个项目都已有编号 (C-ATT-1 / C-PRT-1 / C-RBAC-1 + 三价对比 bug), 不要发明新的
3. **PDF 5 模板, 不要做完整自定义编辑器** — 留 Phase 2 做. 当前 Phase 0 就是固定 5 个 jinja2 模板
4. **RBAC 审计是测试性质, 不要重写 RBAC 框架** — PR #423 已 ship 框架, 你的工作是验证 + 测试 + 补漏洞
5. **不要改其他 Track 的文件** — 文件 ownership 见 §3
6. **不要改共享只读文件** — BaseEntity / IntentExecutorServiceImpl / aiApiClient.ts / CLAUDE.md / `.claude/rules/*` (除非 organizer 明确允许)
7. **不要部署 prod 不通知** — 任何 deploy 前在 STATUS 注明 + ping organizer. 默认 `--env test` 先 (见 `.claude/rules/server-operations.md`)
8. **不要在 main 直 commit** — 用 feature branch + PR
9. **不要 catch (error: any)** — TS 类型安全规范, 用 `isAxiosError` 判断
10. **不要 `as any`** — 见 typescript-type-safety.md
11. **不要硬编码密码 / API key** — 用环境变量 (见 CREDENTIAL-MANAGEMENT.md)

---

## §9 验收清单 (Sprint 末 organizer 验收)

### C-ATT-1 通用 Attachment 系统
- [ ] Flyway V20260516_01__attachment.sql 在 prod / test 双环境 apply
- [ ] `attachments` 表 DDL 完整 (含 5 INDEX + 4 CHECK + COMMENT)
- [ ] 8 个 API endpoint 全通 (Postman 验证)
- [ ] 5 业务接入完成 (客户跟踪 / 采购订单 / 质检 / 生产任务 / 财务凭证), 列表/详情页都能看到附件
- [ ] OSS pre-signed URL 上传/下载流程跑通 (5min PUT + 1h GET)
- [ ] 图片自动生缩略图 (异步, 失败不阻塞)
- [ ] factoryId 多租户隔离测试通过 (Tenant A 查不到 Tenant B 附件)
- [ ] AIChat Tool `attachment_upload` / `attachment_query` 注册到 ToolRegistry

### C-PRT-1 单据打印 PDF
- [ ] 5 单据 PDF 模板就位 (销售单 / 采购单 / 报价单 / 生产任务单 / 领料单)
- [ ] 中文显示正常 (无乱码方块)
- [ ] 采购单 PDF 含二维码 (内容 = 采购单 ID, 为后续扫码入库准备)
- [ ] 5 列表页都有"打印 PDF"按钮 (RN + web-admin 同步)
- [ ] AIChat 说"打印这张单"能返回正确 download URL (`print_document` Tool 工作)
- [ ] 后端 Python `/api/printing/*` 5 endpoint 通 + Java `/api/mobile/{factoryId}/print/*` 5 entry 通

### 三价对比 bug 修复
- [ ] Bug RCA 明确 (方向 A/B/C 之一, 写在 PR body)
- [ ] 修复方案 ship
- [ ] Playwright E2E 通过: 新建采购单 → 入库 → 三价对比页能看到新数据
- [ ] 用 F006 真实数据回归通过

### C-RBAC-1 RBAC 仓管隔离审计
- [ ] 自动化测试 `RBACWarehouseManagerIsolationTest.java` 覆盖 ≥10 个价格敏感 view
- [ ] 5x5 negative regression: 5 角色 × 5 视图 = 25 case, **25/25 PASS**
- [ ] 发现的漏洞全修 (如有)
- [ ] AIChat Tool `rbac_audit` 注册成功, 能在 chat 里跑审计返回矩阵
- [ ] 审计报告 doc 写明 (markdown 表格, commit 到 `scripts/rbac-warehouse-mgr-audit-2026-05-15/report.md`)

---

## §10 客户场景对照 (确保理解客户真实诉求)

| 客户原话 | 来源 | 对应任务 |
|---|---|---|
| "拍照也可以留个单谱吧, 就是你留个附件类似一个拍照然后一个附件也可以的呀" | 六扇门第三次-May7-part2.md 行 546 | **C-ATT-1** (通用附件) |
| "采购订单这个送货单是仓管员拿着的... 这个是送货的人拿的, 会带着货一起过来" | 六扇门第三次-May7-part2.md 行 156-160 | **C-PRT-1** (采购单 PDF, 含二维码供扫码入库) |
| "PDF 模板... 发给那个供应商" | 六扇门第三次-May7-part2.md 行 142 | **C-PRT-1** (PDF 打印) |
| "这边不是有一个三家对比分析吗" → "三家对比没有... 可能是一些数据的 bug" | 六扇门第三次-May7-part1.md 行 115 + part2 行 593-599 | **三价对比 bug** |
| "三价对比的数据是从哪里转的? 是入库的呃价格还是说以那个我新建了采购订单的价格" → "应该是入库的, 就是实际上已经在用了的" | part2.md 行 595-596 | **三价对比 bug** (数据源是入库价不是采购单价) |
| "其他的话就尽量少让那个仓管员去参与什么什么价格类的不要让他们去参与" | part2.md 行 188 | **C-RBAC-1** (仓管不见价格) |
| "做仓管的他年纪都比较大文化素质很低的, 你不能太伪赖他们" | part2.md 行 189 | **C-RBAC-1** (设计原则) |
| "采购跟入库是两个人吗? 两个人两个角色" | part2.md 行 558 | **C-RBAC-1** (角色完全分离) |

---

## §11 上手 Checklist (你开始前先做)

1. [ ] 读完这份 brief (你正在做)
2. [ ] 切到项目根: `cd C:\Users\Steve\my-prototype-logistics`
3. [ ] git 状态干净: `git status`, 如果有 unstaged 改动确认是不是你的, 不是的话先 `git stash` 或 ping organizer
4. [ ] 起 worktree: `git worktree add ../cretas-track-c HEAD && cd ../cretas-track-c`
5. [ ] 创建 branch: `git checkout -b feature/asap-track-c-att-1`
6. [ ] 读 SCHEMA_DESIGN.md §2.3 (Attachment 完整 spec)
7. [ ] 读 `.claude/rules/concurrent-edit-safety.md` (并发安全规则, 重要!)
8. [ ] 读 `.claude/rules/ai-intent-tool-skill-architecture.md` (Tool 添加规范)
9. [ ] `mkdir -p 宏见竞品分析/04-最终决策/STATUS` + 创建 `TRACK_C_STATUS.md`, 第一段写 "Day 0: Brief 阅读完毕, 起 worktree, 准备开始 Day 1"
10. [ ] 开始 Day 1 工作 (Service 接口设计)

---

**完成 Sprint 1 后**:
- 4 个 PR 全部 merge → Cretas 多了通用 Attachment + PDF 打印 + 三价对比修复 + RBAC 仓管隔离
- 六扇门 F006 客户 P0 反馈全闭环
- 为 Phase 1 (扫码入库流程闭环) 打下基础

加油 — 开始吧。