# 产品工序 Workflow 运行时设计规格

状态：用户于 2026-07-10 确认“产品显式启用后才切换，其他产品继续旧流程”。

依赖：`2026-07-10-product-process-workflow-design.md` 的阶段一编辑器、发布版本和图校验。

## 1. 目标与范围

把已发布的产品工序 Workflow 接入真实生产链路，使一个生产批次可以按图生成工序任务，并在逐道报工时正确处理：

- 重复出现的同一种工序；
- 一入一出、多入一出、一入多出和多入多出；
- 同一投入端口选用一个或多个实际库存批次；
- 半成品与成品的多行产出；
- 投入扣减、产出入库、成本归集和批次血缘；
- 正在生产的批次不受后来 Workflow 修改影响。

本阶段不恢复 Preview，不重做现有报工流程，不允许 AI 访问或操作报工、任务、库存、审批和激活，也不自动把图定义覆盖回旧的 `product_work_processes`。

### 1.1 半成品 / 成品类型在哪里选择

类型必须选择，但选择发生在 **Workflow 配置端**，不发生在 **现场报工端**：

- 管理员在工序 Cell 的每个产出端口选择 `半成品` 或 `成品`，同时绑定对应 SKU；
- 选择已有 SKU 时，系统按 SKU 的产品分类自动带入类型；管理员切换类型时只显示该类型可选 SKU；
- 已绑定 SKU 与所选类型不一致时必须清空绑定或阻止发布，不能只改变 Cell 颜色；
- 现场报工只显示 Workflow 已确定的产出名称、SKU、类型和单位，操作员填写实际数量；
- 未启用 Workflow 的旧任务继续保持当前报工页面与 `outputKind` 兼容行为。

不在报工现场再次选择类型，是因为同一产出不能在不同班次被临时记成不同库存类别；库存去向属于产品工艺配置，不属于操作员的实报数据。

## 2. 已确认的上线策略

采用“显式激活 + 批次快照 + 旧流程回退”：

1. 发布 Workflow 只产生一个不可编辑的发布版本，不自动影响生产。
2. 管理员对产品执行“启用此版本”后，只有新创建的生产批次使用该版本。
3. 启用前已存在的批次继续使用其原任务链；启用后创建的批次保存不可变运行时快照。
4. 发布新版本不会自动切换当前激活版本，管理员必须再次明确激活。
5. 停用只影响之后创建的批次；已有运行时实例继续执行到结束。
6. 没有激活版本的产品继续走现有 `product_work_processes` 线性任务生成与旧报工逻辑。

该策略避免配置编辑、发布和生产执行之间产生隐式切换。

## 3. 备选方案与结论

### 方案 A：版本化运行时快照（采用）

把激活的发布图编译为批次级运行时实例，并让任务、报工明细、库存流水引用稳定的节点与端口 ID。优点是能够表达分支、合流、重复工序和完整血缘；缺点是需要新增运行时表和兼容适配层。

### 方案 B：投影回 `product_work_processes`（不采用）

实现较少，但该表的 `(factory_id, product_type_id, work_process_id)` 唯一约束无法表达重复工序，`processOrder` 也无法表达图的分支与合流。强行投影会丢失业务语义。

### 方案 C：新建一套平行报工引擎（不采用）

模型最纯粹，但会复制现有三阶段报工、审批、成本、库存和 RN 页面，产生两套规则长期漂移。采用扩展现有权威报工栈的方式。

## 4. 领域模型

### 4.1 激活记录

新增 `product_process_workflow_activations`：

| 字段 | 语义 |
|---|---|
| `factory_id` + `product_type_id` | 工厂内产品唯一键 |
| `active_workflow_id` | 当前激活的已发布 Workflow |
| `active_definition_version` | 激活时的发布版本 |
| `enabled` | 新批次是否使用 Workflow |
| `activated_by/activated_at` | 审计信息 |
| `lock_version` | 防止并发切换 |

激活服务必须验证 Workflow 属于同一工厂和产品、状态为 `PUBLISHED`、发布校验仍通过。重复激活同一版本返回当前记录，不创建重复数据。

### 4.2 批次运行时实例

新增 `production_workflow_instances`：

| 字段 | 语义 |
|---|---|
| `production_batch_id` | 一个批次最多一个运行时实例 |
| `workflow_id/definition_version` | 来源发布版本 |
| `nodes_json/edges_json` | 创建批次时的不可变执行快照 |
| `compiled_at` | 编译时间 |
| `status` | `ACTIVE/COMPLETED/CANCELLED` |

快照不保存画布坐标与 viewport，只保存执行所需的节点、端口、单位、SKU、换算和报工规则。发布图后来变化时，实例内容不变。

### 4.3 工序任务绑定

扩展 `work_process_tasks`：

- `workflow_instance_id`：空值表示旧线性任务；
- `workflow_node_id`：图中的工序节点 ID；
- 唯一约束 `(workflow_instance_id, workflow_node_id)`，从而允许同一个 `work_process_id` 在图中重复出现。

所有工序节点在创建批次时一次性生成任务。拓扑排序只用于稳定展示顺序和承接关系；任务的开始、进行中、三阶段报工、审批和完成仍使用现有 `WorkProcessTask` 与 Yield 报工状态机，不新增平行状态机。

### 4.4 运行时端口

新增 `workflow_task_ports`，每行固定一个批次任务的输入或产出端口：

- `task_id/workflow_port_id/direction/ordinal`；
- `material_node_id/material_kind/sku_id`；
- `unit`；
- `required`；
- `conversion_mode/conversion_expression`。

端口是“配置需要什么物料”的稳定合同；实际使用哪个库存批次、数量多少属于报工事实，不能写回端口。

### 4.5 报工字段协同，而不是重做报工

Workflow 先适配现有 `YieldReportRequest` 和 `ProductionReport`：

| Workflow 场景 | 复用当前字段 |
|---|---|
| 单投入、单产出 | `inputQuantity/inputUnit/outputQuantity/outputUnit` |
| 同一种原料使用多个库存批次 | `materialBatchRefs[]` |
| 原料 + 一笔半成品投入 | `materialBatchRefs[] + sourceWipNo/sourceWipQuantity` |
| 一个成品产出 | `outputKind=FINISHED + outputQuantity` |
| 一个半成品产出 | `outputKind=SEMI + semiOutputQuantity/semiOutputUnit/semiCode` |
| 一个成品 + 一个半成品 | `outputKind=BOTH` 与当前双产出字段 |
| 非计划损耗 / 副产物 | 继续使用 `wasteQuantity/byproducts[]`，不冒充 Workflow 计划产出 |

只有当前合同无法表达时才增加可选字段：

- `materialBatchRefs[].workflowPortId`：多个配置投入端口需要区分归属时使用；
- `sourceWipRefs[]`：需要同时投入两笔或以上不同半成品来源时使用；
- `configuredOutputs[]`：需要两个同类产出或三个以上计划产出时使用，只传 `workflowPortId + quantity`，SKU、类型和单位由服务端快照解析。

这些字段只在 Workflow 任务中出现；旧任务请求、数据库兼容列、三阶段隔离规则和审批入口保持不变。必要时新增 `production_report_workflow_lines` 保存额外端口明细，但不把所有旧报工迁移到新表。

新增 `production_inventory_lineage` 将 Workflow 报工实际来源与各产出库存对象建立 N:M 血缘。它表达“哪些投入共同形成哪些产出”，不在没有可靠规则时伪造重量分摊。

## 5. Workflow 编译规则

编译器只接受已发布且通过完整校验的图：

1. 使用 DAG 拓扑排序生成稳定的 `processOrder`，同层按节点 ID 排序，排序不代表强制线性依赖。
2. 每个 `PROCESS` 节点生成一个 `WorkProcessTask`。
3. 连接到工序的物料节点生成输入端口；从工序连出的物料节点生成产出端口。
4. 原料节点只能成为输入来源；成品节点只能成为最终产出；半成品节点可承接上下游。
5. 物料节点如果连接上游工序和下游工序，就形成前一任务产出端口到后一任务输入端口的依赖。
6. 多条上游边指向一个工序时形成合流；一条工序连向多个物料节点时形成多产出。
7. 同一个 SKU 的多个实际批次不是额外图节点，而是报工时一个端口下的多条 allocation。
8. 固定配方辅料不生成操作员录入端口；它们继续由现有 BOM/成本配置自动计算。
9. 编译失败必须阻止生产批次使用该版本，并给出具体节点、原因和“返回 Workflow 配置”操作提示；不得静默退回旧流程。

## 6. 拓扑只提供配置上下文

- Workflow 快照告诉现有报工：当前任务有哪些投入、产出、SKU、单位和数量关系。
- 不新增 `WAITING_INPUT/READY` 等任务状态，不改变现有任务开始、报工、审批和完成转换。
- 上游半成品尚未入库时，沿用现有库存可用量与 INPUT 校验阻止提交；Workflow 只把缺少的端口名称显示清楚。
- 合流任务的多个投入在现有 INPUT 阶段一次确认；缺任一必填端口时不能提交 INPUT，但不改变后续 SEGMENT/OUTPUT 顺序。
- 多产出的审批与库存写入复用现有 `postApprovedOutput` 事务，在其内部按配置产出逐行调用现有半成品/成品过账能力；任一行失败仍整体回滚。
- 批次完工仍走现有完工服务；只把“哪些节点属于末端产出”作为判断输入，不创建第二套完工逻辑。

## 7. 与现有三阶段报工协同

继续使用 `YieldStepReportScreen` 与 `YieldReportServiceImpl` 的 `INPUT → SEGMENT → OUTPUT` 权威链路：

### INPUT

- 保留当前 INPUT 页面和提交入口，仅用 Workflow 配置预填投入名称、SKU、单位和端口提示。
- 当前字段能表达时仍提交 `inputQuantity/materialBatchRefs/sourceWipNo`；只有多半成品、多端口归属场景才提交新增可选字段。
- 单一明确来源继续自动带入；多来源继续使用当前批次选择方式，并补充端口归属。

### SEGMENT

- 工时与人员记录保持现有逻辑，不重复展示物料配置。
- 页面顶部持续显示产品、生产批次和工序名称，避免操作员报错任务。

### OUTPUT

- 保留当前 OUTPUT 页面、超收确认、续报、证据、副产物和提交入口。
- Workflow 已决定计划产出的 SKU、类型和单位；页面按配置显示数量输入，不在现场重新选择类型。
- 一个成品 + 一个半成品优先映射到当前 `FINISHED/SEMI/BOTH` 字段；只有当前合同表达不了的额外计划产出才使用 `configuredOutputs[]`。
- 自由文本 `byproducts[]` 只表示非计划副产物，不能代替 Workflow 中已配置的第二、第三产出。

旧任务没有 `workflow_instance_id` 时，请求和界面保持现在的单投入、单/双产出兼容逻辑。

## 8. UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像

车间报工操作员——可能不熟悉 ERP、工序拓扑和库存术语，通常在生产现场使用手机，关注“这一步拿什么、填多少、产出什么”，不应承担判断物料类型或单位换算的责任。

### 用户旅程

| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|---|---|---|---|
| 1 | 产品名、批次号、当前工序、任务状态 | 按现有入口打开报工 | 系统进入当前 INPUT 阶段；缺来源时列出具体端口 |
| 2 | 每个投入端口的 SKU、单位、可用数量 | 单来源确认；多来源选择批次并填数量 | 合计和边界实时计算，超量时提交键不可用 |
| 3 | 当前任务与已确认投入摘要 | 记录人员和工时 | 输入内容保留，可多次累计时段 |
| 4 | Workflow 配置的计划产出；旧任务仍是当前产出栏 | 填实际数量并上传证据 | 当前 OUTPUT 逻辑照常提交，额外字段仅承载缺失的多产出信息 |
| 5 | 提交确认页：投入、产出、批次、数量、单位 | 只点击一个主按钮“提交报工” | 幂等提交成功，等待审批；重复点击不产生第二份数据 |
| 6 | 审批结果或明确错误 | 查看结果；失败时点“重试/返回选择批次/联系主管” | 已填数据不丢失，用户知道下一步做什么 |

### 摩擦点清单

| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|---|---|---|
| F1 | 操作员不知道多投入任务还缺哪个来源 | HIGH | fool-proof Rule 1、Rule 5 |
| F2 | 同 SKU 多批投入容易选错批次或超用库存 | HIGH | fool-proof Rule 1、Rule 2 |
| F3 | 配置端未校验 SKU 分类，或报工端再次选择类型，会导致入错库存类别 | HIGH | fool-proof Rule 3 |
| F4 | 多单位数字密集，容易把 kg、只、盒混填 | HIGH | 内联 UX 规则、Rule 2 |
| F5 | 网络抖动或重复点击产生重复扣减/入库 | HIGH | fool-proof Rule 4 |
| F6 | 提交失败后表单清空，现场无法复原 | HIGH | 内联错误恢复规则 |
| F7 | Workflow 配置缺失或投入来源不足时只显示技术错误 | MED | fool-proof Rule 5、错误四位一体 |

### 每个摩擦点的设计回应

- F1 → INPUT 阶段直接列“待黄油鸡前处理 120 kg、待腌制液 18 kg”，缺任一项时禁用现有 INPUT 提交按钮。
- F2 → 批次卡固定显示品名、批次号、当前可用量；单一来源自动选，多来源分配合计实时校验。
- F3 → 管理员在 Workflow 选择并校验类型；现场报工只读类型并填数量。
- F4 → 单位紧贴每个数字且不可编辑；不同单位绝不跨行合计成一个数字。
- F5 → 客户端提交 `idempotencyKey`，后端按任务、阶段、键唯一防重；按钮提交后立即进入 loading。
- F6 → 失败保留全部本地输入；错误文案说明“发生了什么 + 怎么解决”，提供重试。
- F7 → 配置问题给管理员跳转 Workflow 编辑器；库存不足给操作员“重新选批次”，权限问题提示联系主管。

### RN 实现约束

- 可点击目标至少 `44×44pt`，相邻操作至少 `8px`。
- 每屏只暴露当前阶段字段，主操作按钮仅一个。
- 数量输入使用 Paper `TextInput`、`keyboardType="numeric"`，主要数量字号不小于 24px。
- 使用 `TouchableRipple` 提供即时反馈，加载使用 `ActivityIndicator`。
- 失败信息为 sticky，并复用后端 message；禁止展示空白页或纯 `500`。
- 提交确认必须再次显示产品名、批次号、工序、投入与产出摘要。

## 9. API 合同

新增或扩展以下合同：

- `PUT /product-process-workflows/{workflowId}/activation`：显式激活发布版本。
- `DELETE /product-process-workflows/activation?productTypeId=...`：停用新批次切换。
- `GET /production-batches/{batchId}/workflow-runtime`：运行时任务、端口和图承接关系，不引入新的任务状态。
- `GET /yield-reports/{batchId}/tasks/{taskId}/workflow-config`：在现有报工 DTO 之外返回 Workflow 预填上下文，不替代现有 limits、WIP 和 output-options 接口。
- 仅在缺口场景扩展 `MaterialBatchRef.workflowPortId`、`YieldReportRequest.sourceWipRefs[]` 和 `configuredOutputs[]`。
- 服务端按快照解析产出 SKU、类型和单位，不信任客户端回传这些配置字段。
- 响应继续沿用现有报工结果，并按需补充端口级校验错误；不返回或推进新的任务状态。

所有运行时接口继续按 `factoryId` 隔离。任何 task、workflow、SKU、库存批次跨工厂引用均返回明确的 404/409，不泄露其他工厂数据。

## 10. 校验、幂等与错误恢复

- 继续执行当前 INPUT/OUTPUT 数量、库存、超收、续报和三阶段字段隔离校验。
- Workflow 额外校验只检查端口归属、必填计划产出、配置单位与 SKU 类型一致性。
- 客户端提交的端口必须属于当前任务快照，单位必须与端口一致。
- 同一输出 SKU 的两个不同端口仍保留两行，不按 SKU 偷偷合并。
- 换算规则用于提示与边界校验；超出工序配置阈值时按现有软告警或主管规则处理，不篡改实报量。
- 提交、审批、撤回均有业务幂等键和数据库唯一约束。
- 库存写入和血缘写入在同一事务；失败时不允许部分成功。
- 错误响应包含 `message/code/actionHint/hintTarget`，前端错误保持显示并提供可执行的下一步。

## 11. AI 边界

AI 助手只存在于产品工序 Workflow 编辑器，只接收当前产品的图定义和当前选中 Cell，用于：

- 帮助增加、删除、连接和整理 Workflow Cell；
- 检查 SKU 承接、半成品/成品类型、单位和数量关系；
- 生成结构化 Workflow 图补丁和配置说明。

实现时使用专用 `product_process_workflow_config` 模块与 `canvas_product_process_workflow_config` 预览工具，替换当前线性 `product_work_process_config` 草稿适配。该工具只返回允许的图补丁，不提供 `apply=true` 执行路径。

AI 不读取运行批次、报工、人员工时、库存余额或审批数据；不保存、发布、激活 Workflow；不开始任务、提交/批准报工、扣减库存或创建 SKU。用户审核补丁后只应用到前端 Workflow 草稿，后续保存和发布仍由页面按钮执行。

## 12. 测试与验收

### 后端测试

- 激活：仅发布版本可激活、跨工厂拒绝、重复激活幂等、停用不影响旧实例。
- 编译：线性、重复工序、分支、合流、多入多出均生成正确任务和端口。
- 快照：发布新版本后旧批次实例与任务不变。
- 协同：现有单投入、原料多批、原料 + 单 WIP、`FINISHED/SEMI/BOTH` 请求保持逐字段兼容。
- 缺口字段：多 WIP、多个同类计划产出和端口归属只在 Workflow 任务启用。
- 报工：INPUT/SEGMENT/OUTPUT 隔离、审批、超收、续报和旧任务结果保持不变。
- 库存：多产出复用现有审批过账事务，任一失败全部回滚；现有拒绝/撤回行为保持不变。
- 血缘：每个产出可追溯到所有实际投入批次和来源报工。
- 回归：未启用产品继续生成旧任务，现有 Yield 报工测试保持通过。

### RN 测试

- 现有三阶段页面、按钮和状态转换保持不变。
- Workflow 任务显示配置端已确定的名称、SKU、类型和单位；报工端不出现类型下拉。
- 来源不足、库存不足、重复提交、网络失败均保留输入并提供下一步。
- 老任务仍显示当前单投入/兼容双产出页面。

### E2E 验收

仅在 F006 测试数据执行，禁止使用真实客户租户：

1. 激活一个测试产品的发布版本。
2. 创建批次并核对绑定的 workflow/version 快照。
3. 跑通一条线性流程、一条多批混合流程和一条同时多产出流程。
4. 验证现有三阶段报工、审批、原料/半成品扣减、多个产出入库及追溯链。
5. 发布并激活新版本，证明旧批次仍走旧快照、新批次走新快照。
6. 停用后创建新批次，证明其回到旧线性流程。

## 13. 分批交付边界

阶段二按三个可独立验证的增量实现，但属于同一运行时设计：

1. **2A 激活、快照与任务编译**：新批次可生成图任务，旧流程回退成立。
2. **2B 报工字段适配、库存与血缘**：优先复用现有字段，只为表达缺口增加 Workflow 可选字段。
3. **2C RN 协同展示与 F006 E2E**：保留三阶段报工，消费 Workflow 配置并完成端到端验证。

2A、2B 在没有对应测试通过前不得启用运行时开关；2C 验收前不得部署到生产租户。
