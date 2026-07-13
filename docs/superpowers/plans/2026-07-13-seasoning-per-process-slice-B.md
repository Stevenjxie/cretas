# 调料配方按工序 — Slice B(配方 UI + 保存端点 + 孤儿守卫)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Steps 用 checkbox。
> **🔒 B1/B2 写的是驱动成本核算的配置 + 删工序守卫 → Opus keystone + 审计; B3 前端由 Sonnet/Composer。收尾回 main 由 Opus 终审 + 部署。**

**Goal:** 让用户在调料配方页**按工序**配调料:打开 SKU → 拉出该产品全部工序 → 按工序分组 → 熟制工序填锅序+卤料明细 / 注射工序填绝对注射量+注射内容 / 普通工序只填明细 / 不需要的留空。保存写 `work_process_id` 到明细 + per-工序参数到 `bom_process_seasoning`,喂给 Slice A 的成本路径。

**Architecture:** 扩展现有 `PUT /{recipeId}/seasoning` 契约(明细加 `workProcessId` + 新增 per-工序参数列表),`BomRecipeServiceImpl.saveSeasoning` 全量替换时写工序字段 + 写 `bom_process_seasoning`;前端 `ProductRecipeView.vue` 从"按 section 两块"重构成"按工序分组",工序列表复用 `GET /product-work-processes`。

**隔离:** worktree `cretas-season`(B1/B2 inline);前端 B3 独立 worktree 并行, 按下方契约建, 完成 merge。

---

## API 契约(B1 定, B3 依此建, 二者并行)

**扩展 `BomSeasoningSaveRequest`**(`dto/bom/BomSeasoningSaveRequest.java`):
- `SeasoningItemDTO` 加 `String workProcessId`(per-工序 UI 下必填;为空=未分配工序的旧数据)。
- 新增 `List<ProcessSeasoningParamDTO> processParams`,`ProcessSeasoningParamDTO = { String workProcessId, BigDecimal subsequentPotRatio, BigDecimal injectionAmountKg, String notes }`。
- 保留 header `cookingPotBaseKg/subsequentPotRatio/injectionRate`(向后兼容, 新 UI 不再写)。

**扩展 `BomSeasoningResponse`**(`dto/bom/BomSeasoningResponse.java`):
- `seasoningItems`(现返 `List<BomSeasoningItem>` 实体, 已含 `workProcessId` 自动序列化)。
- 新增 `List<ProcessSeasoningParamDTO> processParams`(从 `bom_process_seasoning` 读)。

前端按 `workProcessId` **客户端分组** items + params,工序清单/名称/类别来自 `GET /{factoryId}/product-work-processes?productTypeId=`(`ProductWorkProcessDTO` 已含 `workProcessId/processName/processCategory`)。

---

## Task B1 — 后端:保存/读取 per-工序 调料(🔒 Opus keystone)

**Files:** Modify `dto/bom/BomSeasoningSaveRequest.java`、`dto/bom/BomSeasoningResponse.java`、`service/bom/impl/BomRecipeServiceImpl.java`(`saveSeasoning` `:408-469`、`cloneRecipe` `:234-250`、`buildSeasoningResponse` `:471-482`);新建 `dto/bom/ProcessSeasoningParamDTO.java`。注入 `BomProcessSeasoningRepository`。

- [ ] **Step 1:** 建 `ProcessSeasoningParamDTO`(workProcessId/subsequentPotRatio/injectionAmountKg/notes)。
- [ ] **Step 2:** `SeasoningItemDTO` 加 `workProcessId`;`BomSeasoningSaveRequest` 加 `List<ProcessSeasoningParamDTO> processParams`;`BomSeasoningResponse` 加同字段。
- [ ] **Step 3:** `saveSeasoning`(DRAFT-only 不变):
  - 插入明细时 `si.setWorkProcessId(dto.getWorkProcessId())`(现 `:448-465` 漏)。
  - 全量替换 per-工序参数:软删该 recipe 全部 `bom_process_seasoning` 行 → 按 `processParams` 重建(`recipeId/factoryId/workProcessId/subsequentPotRatio/injectionAmountKg/notes`)。
  - 保留 header pot 参数写入(兼容)。
  - 校验:`processParams` 里 workProcessId 不得重复(每工序一行);`section` 仍校验 INJECTION/COOKING。
- [ ] **Step 4:** `cloneRecipe` 复制 `workProcessId`(`cs.setWorkProcessId(s.getWorkProcessId())`, 现 `:237-248` 漏)+ 复制 `bom_process_seasoning` 行。
- [ ] **Step 5:** `buildSeasoningResponse` 带上 `processParams`(读 `bomProcessSeasoningRepository.findByRecipeIdAndDeletedAtIsNull`)。
- [ ] **Step 6:** 单测:save 一个含熟制(锅序)+注射(绝对量)两工序的配方 → read 回来 items 带 workProcessId 分两工序、processParams 两行;clone 保 workProcessId。
- [ ] **Step 7:** `mvn -o test -Dtest='*BomRecipe*Seasoning*,*BomRecipeServiceImpl*'` 绿。Commit。

## Task B2 — 后端:删工序孤儿守卫(🔒, re-audit 发现)

**Files:** Modify `repository/bom/BomSeasoningItemRepository.java`(加 `boolean existsByWorkProcessId(String)`)、`repository/bom/BomProcessSeasoningRepository.java`(同)、`service/impl/WorkProcessServiceImpl.java`(注入两 repo + `delete()` 守卫)。

- [ ] **Step 1:** 两 repo 加 `existsByWorkProcessId(String workProcessId)`。
- [ ] **Step 2:** `WorkProcessServiceImpl.delete()`(`:181-186`)守卫:若 `bomSeasoningItemRepository.existsByWorkProcessId(id) || bomProcessSeasoningRepository.existsByWorkProcessId(id)` → `throw new BusinessException(409, "该工序已被调料配方引用，无法删除").withHint("请先在「生产 → BOM 配方 → 调料配方」移除引用该工序的调料配置")`。
- [ ] **Step 3:** ⚠️ 注入新 repo 改了 `WorkProcessServiceImpl` 构造器 → 更新 `WorkProcessServiceImplTest`(@Mock 或显式构造补参)。跑该测试绿。
- [ ] **Step 4:** 单测:有调料引用 → delete 抛 409;无引用 → 正常删。Commit。

## Task B3 — 前端:调料配方按工序 UI(Sonnet/Composer, 独立 worktree)

**Files:** Rewrite `web-admin/src/views/production/ProductRecipeView.vue`(loaded 分支);Modify `web-admin/src/api/bom.ts`(TS 类型 + api)。

- [ ] **Step 1:** `bom.ts`:`BomSeasoningItem` 加 `workProcessId`;`BomSeasoningSaveRequest`/`Response` 加 `processParams: ProcessSeasoningParam[]`;新增 `ProcessSeasoningParam` 类型。加取工序列表 api `getProductWorkProcesses(factoryId, productTypeId)` → `GET /{factoryId}/product-work-processes?productTypeId=`。
- [ ] **Step 2:** 加载:选中产品后 fetch 工序列表 + seasoning。**按工序分组**渲染:每道工序一块,标题=工序名 + 类别徽标。
- [ ] **Step 3:** 按 `processCategory` 出对表单:
  - `熟制` → 该工序锅序(第二锅比例, 来自 processParams)+ 卤料明细表(名/每kg用量/单价/计入调料 switch)。
  - `注射` → 该工序绝对注射量(kg)+ 注射内容明细表(盐水/添加剂: 名/每kg用量/单价)。
  - 其它 → 只明细表(可留空)。
- [ ] **Step 4:** 软提示:检测到 N 道工序未配调料 → 顶部 el-alert "去配置"(不强制,留空=0)。空状态:没建 workflow/工序链 → EmptyState 导航去工序配置(防呆 Rule 5)。
- [ ] **Step 5:** 保存:payload 每明细带其工序 `workProcessId` + `processParams` 每工序一行(熟制填 ratio, 注射填 amount)。DRAFT-only 才可编辑(沿用 isReadOnly)。
- [ ] **Step 6:** `npm run build` 无 TS 错。headed(zh-CN)走查:建 workflow → 调料页按工序列出 → 熟制出锅序/注射出注射量/普通只明细 → 留空不阻断 → 保存 → 重载数据回显正确。Commit。

## Task B4 — 集成 + 验收
- [ ] merge B3 → cretas-season;`git diff origin/main...HEAD --stat` scope 干净。
- [ ] 端到端:建带熟制+注射工序的 SKU → 调料页各工序配不同调料 + 锅序/注射量 → 保存 → 报工两道 → 各工序调料成本按各自参数算对(接 Slice A 成本路径)。
- [ ] 🔒 B1/B2 对抗审计(成本配置写入 + 删守卫)→ Opus 终审。

## Self-Review(对照 spec Slice B)
- 按工序分组 UI + 按类别出表单 → B3 ✓;工序列表复用 `product-work-processes` ✓
- 保存写 workProcessId + per-工序参数 → B1 ✓;clone 保工序 → B1 Step4 ✓
- 软提示 + 空状态导航 → B3 Step4 ✓
- 孤儿守卫(re-audit 必做)→ B2 ✓
- 契约先定 → 前后端并行 ✓
