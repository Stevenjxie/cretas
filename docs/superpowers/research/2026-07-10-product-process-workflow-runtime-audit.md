# 产品工序 Workflow Superpowers 审计

审计日期：2026-07-10

审计分支：`codex/product-process-workflow`

审计基线：`fe870c21e`

## 结论

阶段一编辑器目前总先生成半成品 Cell，并提供半成品 / 成品类型下拉，但最终规则是不让文员或报工人员选择类型：类型应由所选工序的主数据自动决定。现有工序主数据只有 `semiFinishedOutputCode`，缺少明确的默认产出类型字段，因此当前实现无法可靠完成自动判定。阶段二初稿还引入了新的任务就绪状态和全面的端口报工合同，超过了“Workflow 不改变当前三阶段报工逻辑”的边界。AI 当前安全地被页面入口强制为 preview，但仍调用旧的线性产品工序草稿工具，并非真正的 Workflow 图助手。

本次已同步修订设计与运行时规格：新图自动带原料 Cell；选择工序时自动生成工序与产出 Cell；工序主数据决定默认产出类型；保留现有 `INPUT → SEGMENT → OUTPUT`、审批、超收、续报与库存过账入口；Workflow 只提供配置上下文，当前字段表达不了多端口时才增加可选字段；AI 改为只生成 Workflow 图补丁的专用预览工具。

## 高风险 / 未完成

### P0：阶段二初稿扩大了报工状态机

- 初稿设计了 `WAITING_INPUT/READY`、INPUT 预占和新的 `inputLines/outputLines`，会改变现有任务与报工语义。
- 真实代码已经在 `YieldReportServiceImpl` 中按 `reportKind` 隔离 INPUT、SEGMENT、OUTPUT 字段，并在同一服务中处理超收、累计产出与任务完成。
- 修正：删除新的任务状态；Workflow 只给现有报工提供 SKU、端口、单位和数量关系。

证据：

- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java:167-247`
- `frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx:1011-1074`
- `frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx:1278-1365`

状态：设计已修正，代码尚未实现。

### P0：当前 Cell 类型仍依赖文员手工切换

- 新 Workflow 已能自动创建一个原料 Cell，但当前选择工序后固定先生成 `SEMI_FINISHED` 产出 Cell。
- Workflow 工序 Cell仍提供 `半成品 / 成品` 下拉，要求文员人工纠正类型，与最终交互规则冲突。
- 工序主数据目前只有“本工序产出半成品”开关和 `semiFinishedOutputCode`，没有 `defaultOutputMaterialKind`；关闭半成品开关不能区分“普通中间工序”与“最终成品出品工序”。
- 绑定已有 SKU 时前端虽会按 `productCategory` 推导类型，但仍可手动切换，发布校验也没有验证工序默认类型、SKU 分类和 Cell 类型一致性。
- 当前 RN 报工不让用户选择类型，而是根据 `output-options` 和半成品数量推导 `FINISHED/BOTH`。这个方向符合防呆，但数据来源仍是 `WorkProcess.semiFinishedOutputCode`，尚未读取 Workflow 端口。

证据：

- `web-admin/src/views/system/product-processes/workflow/WorkflowProcessNode.vue:54-95`
- `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue:528-590`
- `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue:692-734`
- `web-admin/src/views/system/work-processes/index.vue:55-60`
- `web-admin/src/views/system/work-processes/index.vue:464-475`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/WorkProcess.java:87-93`
- `frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx:1290-1327`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java:1306-1329`

状态：missing。下一步新增工序默认产出类型，从 Workflow 移除类型下拉，并在选择工序时自动生成正确类型的产出 Cell。

### P1：现有报工字段只能覆盖部分图结构

- 已支持：原料多批 `materialBatchRefs[]`、原料 + 一笔 WIP `sourceWipNo/sourceWipQuantity`、一个主产出 + 一个半成品产出 `outputKind=BOTH`。
- 未支持：多个不同 WIP 输入、明确的 Workflow 端口归属、两个同类计划产出或三个以上计划产出。
- 修正：不替换现有 DTO，只为这些缺口增加可选字段；旧任务不传、不读新字段。

证据：

- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/yield/YieldReportRequest.java:14-83`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/yield/MaterialBatchRef.java:9-24`
- `frontend/CretasFoodTrace/src/services/api/yieldReportApi.ts:173-246`

状态：missing，纳入阶段二实施。

### P1：AI 是安全 preview，但不是 Workflow 图原生助手

- 页面把 `moduleCode` 固定为 `product_work_process_config`，后端窄路由强制 `apply=false` 并调用 preview，当前不会直接写库。
- 返回类型仍是 `PRODUCT_WORK_PROCESS_DRAFT`，前端通过 `applyLegacyAIDraft` 转换线性工序；无法可靠表达分支、合流、多端口和 Cell 字段。
- 底层 `ProductWorkProcessConfigTool` 自身仍有 `apply=true` 执行路径，不符合“AI 只适用于 Workflow，不进行其它操作”的最终边界。

证据：

- `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue:97-117`
- `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue:276-280`
- `web-admin/src/views/system/components/WorkProcessAIChatPanel.vue:163-215`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/CanvasAIController.java:162-207`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/workprocess/ProductWorkProcessConfigTool.java:53-118`

状态：partial。下一步改为专用的 Workflow 图补丁 preview 工具，无 execute/apply 路径。

## 覆盖矩阵

| 用户诉求 | 真实代码证据 | 测试深度 | 状态 | 下一步 Hook |
|---|---|---|---|---|
| 新图自动含原料 Cell | `workflowModel.ts:35-57` | 有模型测试 | done | 新图直接显示，不要求文员新增 |
| 选择工序自动生成工序 + 产出 Cell | `ProductProcessWorkflowEditor.vue:528-590` | 当前固定生成半成品 | partial | 继承工序默认产出类型 |
| 文员不选择半成品 / 成品类型 | `WorkflowProcessNode.vue:86-95` 当前仍有下拉 | 无自动类型测试 | missing | 新增主数据字段并删除下拉 |
| 报工端不重复选择类型 | `YieldStepReportScreen.tsx:1290-1320` | 现有单/双产出路径有后端测试 | done（旧模型） | Workflow 端口接入 output-options |
| Workflow 不改变三阶段报工 | `YieldReportServiceImpl.java:167-247` | 现有 Yield 单测 | done（现状） | 新字段走兼容适配，不建新状态机 |
| 缺少字段时与报工步骤协同 | `YieldReportRequest.java:14-83` | 多 WIP / 多计划产出未覆盖 | missing | `workflowPortId/sourceWipRefs/configuredOutputs` |
| AI 只帮助 Workflow 配置 | `CanvasAIController.java:162-207` | preview 边界有代码证据，图补丁未实现 | partial | 专用 module/tool + 图补丁白名单 |
| AI 不进行任何其它操作 | 窄路由强制 `apply=false`，但旧 Tool 有 execute | 未验证其它调用入口 | partial | 新工具删除 execute；端点拒绝其它 module |

## 需要补的实现 Hook

1. 工序主数据：新增 `defaultOutputMaterialKind`，默认 `SEMI_FINISHED`；工序管理页明确配置成品出品工序。
2. Web Admin Workflow：选择工序时自动生成对应类型产出 Cell，删除类型下拉，成品 Cell 自动绑定当前产品 SKU。
3. 多产出交互：保留 Cell 内“添加产出”，增加选中/悬停时的边缘快捷 `＋`；两者共用同一原子新增动作和撤销记录。
4. 发布校验：工序默认类型、端口类型、物料 Cell 类型与 SKU 分类必须一致。
5. Workflow AI：新增只返回 `WorkflowPatch[]` 的专用模块和工具；移除编辑器对 `applyLegacyAIDraft` 的依赖。
6. Backend：批次快照和任务节点绑定只提供配置上下文，不新增报工状态。
7. 报工 DTO：先做现有字段映射测试，再只为多 WIP、多同类产出增加可选字段。
8. RN：保留三阶段页面和提交函数，仅根据 Workflow 配置渲染必要的附加数量行。
9. E2E：F006 分别验证旧任务零回归、Workflow 单入单出映射、多入和多出扩展字段。

## E2E 深度判定

按 `depth-first-e2e` 标准，本次阶段二审计的实际执行数为 0：

| 深度 | 数量 | 说明 |
|---|---:|---|
| smoke | 0 | 本次未重新运行页面检查 |
| medium | 0 | 未执行真实报工提交 |
| deep | 0 | 未执行提交、刷新读回、库存与追溯下游验证 |

阶段一已有单元测试和浏览器交互证据不能替代阶段二 deep E2E。阶段二只有完成“真实 INPUT/SEGMENT/OUTPUT 提交 + 审批 + 刷新读回 + 库存/追溯下游核对”后，才可声明运行时可用。

## 审计限制

- 阶段二尚未实现，因此本次只能审计阶段一代码、现有报工合同与设计一致性，不能声称运行时已完成。
- 本次未连接数据库或执行 F006 生产链 E2E；这些属于实施完成后的 deep 验收。
- 当前环境 `rg.exe` 无执行权限，本次使用 `git grep` 做等价的 tracked-file 定向检索。
