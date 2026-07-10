# 产品工序 Workflow 设计规格

状态：用户已于 2026-07-10 在视觉原型中确认。
范围：Cretas Web Admin 的“产品-工序配置”页面及其后端配置模型。

## 1. 目标

把现有按 `processOrder` 排列的产品工序列表升级为可视化 Workflow 编辑器，支持：

- 原料、半成品、工序、成品四类节点；
- 一入一出、一入多出、多入一出和多入多出拓扑；
- 投入物料、产出物料、各自单位与数量关系在工序节点内配置；
- 半成品和成品节点只显示物料身份，不重复配置工序字段；
- 右侧可折叠 AI 助手；
- 画布缩放、平移、节点拖动、16px 网格吸附和自动布局；
- 每个产品独立保存节点位置、画布视口和草稿版本。

本功能不包含 Preview、批次模拟或运行批次数量展示。

## 2. 分阶段边界

### 阶段一：编辑器与图定义持久化

交付 Vue Flow 编辑器、节点/边数据合同、草稿保存、位置恢复、右侧 AI 草稿助手和旧列表兼容视图。阶段一不改变生产任务生成与报工运行时，保存图定义不会静默改写生产任务。

### 阶段二：运行时拓扑投影

将已发布图定义投影为可执行工序拓扑，支持重复工序节点、分支/合流、多产出、同 SKU 多批投入和完整追溯。阶段二单独设计、测试和上线。

### 阶段三：AI 图变更工具

扩展现有 `canvas_product_work_process_config` Tool，使 AI 返回结构化图补丁；补丁先进入前端草稿，用户确认后才保存，不允许 AI 直接发布版本。

## 3. 现状与约束

- 当前页面：`web-admin/src/views/system/product-processes/index.vue`，采用本地 `draftLinked` + `pendingOps` + 显式保存。
- 当前后端：`ProductWorkProcess` 唯一约束为 `(factory_id, product_type_id, work_process_id)`，只适合单一线性工序链。
- 当前运行时：`spawnTasksForBatch` 从 `product_work_processes` 生成任务，阶段一不得改变其语义。
- 当前 AI：`WorkProcessAIChatPanel` 已调用 `/{factoryId}/config/v2/ai/chat`，`module-code=product_work_process_config`。
- Web Admin 已安装 `@vue-flow/core`、`@vue-flow/background` 和 `@vue-flow/controls`。
- 当前工作区包含产品工序相关未提交修改；正式执行必须先在隔离 worktree 中确认这些修改的归属，不得覆盖。

## 4. 页面结构

页面采用三层结构：

1. 顶部工具栏：产品选择、保存草稿、发布版本、自动布局、适应画布、撤销/重做。
2. 中央画布：Vue Flow，占满 AI 侧栏之外的剩余宽度。
3. 右侧 AI 侧栏：展开宽度 320px，折叠宽度 44px；折叠后画布自动扩展。

旧的线性列表在阶段一保留为“兼容列表”折叠区，用于核对现有运行时配置，不与画布同时编辑同一个字段。

## 5. Cell 职责

| 节点类型 | 显示 | 支持操作 | 禁止重复显示 |
|---|---|---|---|
| 原料 | 名称、SKU、绑定状态 | 新 Workflow 自动生成；选择/更换入口物料；从右侧增加首道工序 | 工序单位、数量关系、运行批次 |
| 半成品 | 名称、SKU、绑定状态 | 查看 SKU、增加后续工序 | 来源工序文字、仓库、单位、数量关系、运行批次 |
| 工序 | 工序、投入端口、产出端口、每个端口单位、数量关系 | 增删端口、选择/现场创建产出 SKU、连接、删除、配置报工规则 | 产出类型手工选择、运行批次、固定配方明细 |
| 成品 | 名称、SKU、规格、绑定状态 | 查看成品 SKU | 来源工序、数量关系、仓库、运行批次 |

连接线已表达上下游，因此物料节点不再重复写“来源工序”。

### 5.1 默认建图与自动产出 Cell

- 新建空白 Workflow 时自动放置一个原料 Cell，文员不需要手动创建入口 Cell；
- 原料 SKU 已知时自动绑定，未知时只在原料 Cell 选择一次，工序投入栏直接继承；
- 原料或半成品 Cell 右侧提供“+ 后续工序”；
- 选择工序后，系统在一次操作中生成工序 Cell、默认产出物料 Cell 和两条连接线；
- 工序主数据新增 `defaultOutputMaterialKind`，默认值为 `SEMI_FINISHED`；明确配置为 `FINISHED_GOOD` 的出品工序自动生成紫色成品 Cell，并绑定当前产品 SKU；
- 文员不能在 Workflow 中手工切换半成品 / 成品类型，报工人员也不能选择；
- 半成品产出 Cell 仍允许选择已有半成品 SKU 或现场创建；成品 Cell 使用当前产品 SKU，不重复选择；
- 一个工序新增额外产出 Cell 时，选择/创建 SKU 后按 SKU 分类自动确定 Cell 类型；不显示独立类型下拉；
- 工序主数据后来发生类型变更时，不静默改写已发布 Workflow；草稿/发布校验提示差异，由管理员明确同步后形成新版本。

### 5.2 多产出新增手势

采用“双入口、同一动作”，兼顾第一次使用的可发现性和熟练用户的操作速度：

- 工序 Cell 的“产出物料”区域底部始终显示 `＋ 添加产出`；
- 工序 Cell 被选中或鼠标悬停时，右边缘显示一个带 `＋` 的空心快捷圆点；
- 实际连线 Handle 继续使用实心蓝点，快捷新增使用白底蓝边 `＋`，避免误认为拖拽连线；
- 两个入口调用同一个 `addOutputToProcess` 动作，不产生不同结果；
- 点击后一次性新增：第二个产出端口、对应物料 Cell、连接线和端口计数徽标；
- 新物料 Cell 默认放在现有产出 Cell 下方 160px，并吸附到 16px 网格；
- 新端口立即出现在工序 Cell 内，显示“产出 2 / 产出 3”，后续绑定 SKU 后自动更新名称和 Cell 类型；
- 新增动作作为一个撤销单元，点击一次“撤销”应同时删除端口、Cell 和连线；
- 拖动工序或连接 Handle 不触发新增，只有明确点击 `＋` 才执行；
- 触发时不弹半成品 / 成品类型选择，类型继续按工序主数据或绑定 SKU 自动判定。

## 6. 图数据合同

后端新增独立图定义聚合，不把所有图字段继续塞入 `ProductWorkProcess`：

```ts
type ProductProcessNodeKind = 'RAW_MATERIAL' | 'PROCESS' | 'SEMI_FINISHED' | 'FINISHED_GOOD'

interface ProductProcessWorkflowNode {
  id: string
  kind: ProductProcessNodeKind
  position: { x: number; y: number }
  data: MaterialNodeData | ProcessNodeData
}

interface ProcessPort {
  id: string
  direction: 'INPUT' | 'OUTPUT'
  materialNodeId: string
  unit: string
  ordinal: number
}

interface ProcessNodeData {
  workProcessId: string
  processName: string
  ports: ProcessPort[]
  conversionRule: {
    mode: 'ACTUAL_WEIGHT' | 'FIXED_RATIO' | 'SUM_OUTPUTS' | 'FORMULA'
    expression?: string
  }
  reportingRequired: boolean
  allowMultipleUpstreamSources: boolean
  allowFinishedGoodsSource: boolean
}

interface ProductProcessWorkflowEdge {
  id: string
  source: string
  sourceHandle: string
  target: string
  targetHandle: string
}

interface ProductProcessWorkflowDefinition {
  id?: number
  factoryId: string
  productTypeId: string
  schemaVersion: 1
  status: 'DRAFT' | 'PUBLISHED'
  version: number
  nodes: ProductProcessWorkflowNode[]
  edges: ProductProcessWorkflowEdge[]
  viewport: { x: number; y: number; zoom: number }
}
```

数据库新增 `product_process_workflows` 表，JSONB 保存节点、边与视口，使用乐观锁防止覆盖其他管理员的草稿。阶段一不修改现有 `product_work_processes` 唯一约束。

## 7. 画布交互

- 鼠标滚轮：以指针位置为中心缩放；范围 `0.35` 至 `1.80`。
- 触控板双指：缩放；空白区域拖动：平移画布。
- Cell 拖动：仅移动节点；松手后吸附到 `[16, 16]` 网格。
- 新节点默认位于当前选中节点右侧 240px；分支节点按 160px 垂直间距展开。
- “自动布局”按拓扑层级从左到右排列；只在用户主动点击时执行，不覆盖手工位置。
- “适应画布”只改变当前视口，不改变节点坐标。
- 每个 `productTypeId` 独立保存 `nodes.position` 和 `viewport`。
- AI 侧栏折叠状态按 `factoryId + userId` 保存到 localStorage，不进入业务图版本。

Vue Flow 必须启用：

```vue
<VueFlow
  v-model:nodes="nodes"
  v-model:edges="edges"
  :min-zoom="0.35"
  :max-zoom="1.8"
  :pan-on-drag="true"
  :zoom-on-scroll="true"
  :zoom-on-pinch="true"
  :nodes-draggable="canWrite"
  :snap-to-grid="true"
  :snap-grid="[16, 16]"
/>
```

## 8. AI 侧栏

AI 侧栏只出现一次，服务当前产品和当前选中 Cell。

支持四类命令：

- 检查 SKU 上下游承接；
- 检查投入/产出单位与换算；
- 检查分流、合流、同 SKU 多投入；
- 根据自然语言生成节点/边/字段变更建议。

AI 返回的结构化补丁只应用到前端草稿：

```ts
type WorkflowPatch =
  | { op: 'UPSERT_NODE'; node: ProductProcessWorkflowNode }
  | { op: 'REMOVE_NODE'; nodeId: string }
  | { op: 'UPSERT_EDGE'; edge: ProductProcessWorkflowEdge }
  | { op: 'REMOVE_EDGE'; edgeId: string }
  | { op: 'SET_NODE_FIELD'; nodeId: string; path: string; value: unknown }
```

用户必须看到差异摘要并点击“应用到草稿”；保存与发布仍由页面顶部按钮完成。

## 9. 校验与错误恢复

保存草稿时执行结构校验，发布时执行完整校验：

- 必须有至少一个原料节点和一个成品节点；
- 所有工序输入/产出端口必须连接；
- 所有物料节点必须绑定 SKU；
- 单位不能为空；
- 图必须为有向无环图；
- 除允许的物料复用外，不允许孤立节点；
- 多投入自动混批只在多个输入端口指向同一 SKU 类型时成立；
- 发布失败保留全部草稿与当前视口，错误消息包含节点名称和下一步操作。

并发保存返回 `409`，提示“该 Workflow 已被其他人更新”，提供“重新加载最新版本”和“复制当前草稿 JSON”两个安全动作；关闭提示则保留本地草稿不变。数据库约束每个产品只能有一个活动草稿，因此不提供会制造第二个活动草稿的“另存为新草稿”。

## 10. 测试要求

- 后端：JSONB 往返、乐观锁冲突、跨工厂隔离、DAG 校验、草稿/发布权限。
- 前端单元：序列化、反序列化、16px 吸附、自动布局、每产品视口隔离、AI 补丁应用。
- 组件：侧栏展开/折叠、点击节点切换 AI 上下文、只读角色禁止拖动和修改。
- E2E：选择产品、拖动节点、缩放/平移、保存、刷新恢复、切换产品后恢复各自视口。

## 11. 阶段一验收标准

- 三类示例流程可以由真实节点数据渲染；
- 右侧 AI 侧栏可折叠，折叠后画布占满；
- 三个产品分别保存并恢复节点坐标和视口；
- 缩放、平移、拖动和网格吸附均可用；
- 草稿保存与刷新恢复成功；
- 现有线性产品工序列表和报工运行时行为不变；
- 不出现 Preview 或模拟批次功能。
