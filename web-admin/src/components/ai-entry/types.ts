export interface FieldDef {
  key: string;
  label: string;
  required?: boolean;
}

export interface TutorialStep {
  title: string;
  description: string;
  icon: string;   // emoji icon
}

export interface AiEntryConfig {
  entityType: string;
  title: string;
  placeholder: string;
  welcomeMessage: string;
  scopeLabel: string;            // e.g. "仅限生产计划相关操作"
  examples: string[];            // clickable quick-start prompts
  tutorialSteps: TutorialStep[]; // step-by-step guide
  systemPrompt: string;
  fields: FieldDef[];
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

// ======================== Entity Configs ========================

export const PRODUCTION_PLAN_CONFIG: AiEntryConfig = {
  entityType: 'PRODUCTION_PLAN',
  title: 'AI 智能创建生产计划',
  placeholder: '描述你的生产计划需求...',
  welcomeMessage: '你好！我可以帮你快速创建生产计划。',
  scopeLabel: '仅限生产计划相关操作',
  examples: [
    '帮我创建一个明天生产500kg [产品名] 的计划',
    '给 [客户名] 客户排一批 [产品名]，300kg，后天交货',
    '创建生产计划：[产品名] 200kg，工序分切，批次日期今天',
  ],
  tutorialSteps: [
    { title: '描述需求', description: '用自然语言说出你要创建的生产计划，比如产品、数量、日期等', icon: '1' },
    { title: '补充信息', description: 'AI 会追问缺少的必填项（产品名称、数量、计划日期），逐步回答即可', icon: '2' },
    { title: '确认预览', description: '信息收集完毕后会显示预览卡片，核对所有字段是否正确', icon: '3' },
    { title: '填入表单', description: '点击「填入表单」自动打开新建对话框，所有字段已预填，确认后提交', icon: '4' },
  ],
  systemPrompt: `你是食品工厂的生产计划助手。用户会用自然语言描述生产计划需求，你需要通过对话收集以下字段信息：

必填字段：
- productTypeName: 产品名称（由工厂决定，你不知道具体清单，需要用户提供）
- plannedQuantity: 计划数量（数字）
- plannedDate: 计划日期（YYYY-MM-DD 格式）

可选字段：
- sourceCustomerName: 客户名称
- processName: 工序（如"分切"、"包装"）
- batchDate: 批次日期（YYYY-MM-DD 格式）
- notes: 备注

️ 重要规则：
- **不要假设**工厂可生产哪些产品。每个工厂的产品不同（食品厂可能做豆制品、肉制品、烘焙等）。
- 如果用户问"我们有哪些产品", 实话告诉用户："我无法直接查询工厂产品清单, 请直接告诉我您要生产的产品名称, 系统会根据您输入的名称匹配。"
- 不要列举编造的产品例子。

交互规则：
1. 如果用户一次性提供了所有必填信息，直接返回 FILL_FORM
2. 如果缺少必填字段，礼貌追问（每次只问1-2个问题）
3. 日期支持自然语言（"明天"、"下周一"等），你需要转换为 YYYY-MM-DD
4. 数量支持带单位（"500kg"→500）

当所有必填字段收集完毕后，返回如下格式（用 markdown 代码块包裹）：
\`\`\`json
{"action":"FILL_FORM","params":{"productTypeName":"<用户提供的产品名>","plannedQuantity":500,"plannedDate":"2026-03-10","sourceCustomerName":"<客户名>","processName":"分切","batchDate":"2026-03-10","notes":""}}
\`\`\`

在返回 JSON 之前，先用一句话总结收集到的信息。`,
  fields: [
    { key: 'productTypeName', label: '产品名称', required: true },
    { key: 'plannedQuantity', label: '计划数量', required: true },
    { key: 'plannedDate', label: '计划日期', required: true },
    { key: 'sourceCustomerName', label: '客户名称' },
    { key: 'processName', label: '工序' },
    { key: 'batchDate', label: '批次日期' },
    { key: 'notes', label: '备注' },
  ],
};

export const PRODUCT_CONFIG: AiEntryConfig = {
  entityType: 'PRODUCT',
  title: 'AI 智能录入产品',
  placeholder: '描述你要添加的产品...',
  welcomeMessage: '你好！我可以帮你快速录入新产品。',
  scopeLabel: '仅限产品信息录入',
  examples: [
    '添加一个成品 [产品名] 规格310g 单位kg',
    '录入原料：[原料名]，单位kg',
    '新增包辅材 纸箱 规格60*40*30 单位个',
  ],
  tutorialSteps: [
    { title: '描述产品', description: '说出产品名称、类型（成品/原料/包辅材/调味品）和单位', icon: '1' },
    { title: '补充信息', description: 'AI 会追问缺少的必填项（名称、大类、单位），逐步回答即可', icon: '2' },
    { title: '确认预览', description: '信息收集完毕后会显示预览卡片，核对产品信息', icon: '3' },
    { title: '填入表单', description: '点击「填入表单」自动打开新增对话框，确认后提交', icon: '4' },
  ],
  systemPrompt: `你是食品工厂的产品管理助手。用户会用自然语言描述要添加的产品信息，你需要通过对话收集以下字段：

必填字段：
- name: 产品名称
- productCategory: 产品大类，必须是以下之一：FINISHED_PRODUCT(成品)、RAW_MATERIAL(原料)、PACKAGING(包辅材)、SEASONING(调味品)、CUSTOMER_MATERIAL(客户自带原料加工)、CONTRACT_MANUFACTURING(纯代工)
- unit: 单位（如 kg、箱、袋、瓶）

可选字段：
- specification: 规格（如"310g*42袋/箱"）
- relatedCustomer: 关联客户
- notes: 备注

交互规则：
1. 如果用户一次性提供了所有必填信息，直接返回 FILL_FORM
2. 如果缺少必填字段，礼貌追问
3. 根据用户描述智能判断 productCategory（如"成品"→FINISHED_PRODUCT、"原料"→RAW_MATERIAL）

当所有必填字段收集完毕后，返回如下格式：
\`\`\`json
{"action":"FILL_FORM","params":{"name":"<产品名>","productCategory":"FINISHED_PRODUCT","unit":"kg","specification":"310g","relatedCustomer":"","notes":""}}
\`\`\`

在返回 JSON 之前，先用一句话总结。`,
  fields: [
    { key: 'name', label: '产品名称', required: true },
    { key: 'productCategory', label: '产品大类', required: true },
    { key: 'unit', label: '单位', required: true },
    { key: 'specification', label: '规格' },
    { key: 'relatedCustomer', label: '关联客户' },
    { key: 'notes', label: '备注' },
  ],
};

export const PURCHASE_ORDER_CONFIG: AiEntryConfig = {
  entityType: 'PURCHASE_ORDER',
  title: 'AI 智能创建采购单',
  placeholder: '描述你的采购需求...',
  welcomeMessage: '你好！我可以帮你快速创建采购单。',
  scopeLabel: '仅限采购单相关操作',
  examples: [
    '从XX供应商采购500kg大豆，下周三交货',
    '紧急采购200kg小麦粉和100kg食用油，供应商YY',
    '创建采购单：供应商ZZ，大豆300kg单价5元',
  ],
  tutorialSteps: [
    { title: '描述采购', description: '说出供应商、原料名称、数量等，支持同时添加多种原料', icon: '1' },
    { title: '补充信息', description: 'AI 会追问缺少的供应商或原料明细信息，逐步回答即可', icon: '2' },
    { title: '确认预览', description: '信息收集完毕后会显示预览卡片，核对供应商和采购明细', icon: '3' },
    { title: '填入表单', description: '点击「填入表单」自动打开新建对话框，确认后提交', icon: '4' },
  ],
  systemPrompt: `你是食品工厂的采购助手。用户会用自然语言描述采购需求，你需要通过对话收集以下字段：

必填字段：
- supplierName: 供应商名称
- items: 采购明细数组，每项包含：
  - materialName: 原料名称
  - quantity: 数量（数字）
  - unit: 单位（默认 kg）
  - unitPrice: 单价（数字，如未提供可为0）

可选字段：
- purchaseType: 采购类型 DIRECT(直接采购)/HQ_UNIFIED(总部统采)/URGENT(紧急采购)，默认 DIRECT
- expectedDeliveryDate: 期望交货日期（YYYY-MM-DD）
- remark: 备注

交互规则：
1. 至少需要供应商和一项采购明细
2. 如果缺少必填信息，礼貌追问
3. 支持一次添加多项原料（"500kg大豆和200kg小麦"）
4. 日期支持自然语言转换

当信息收集完毕后，返回如下格式：
\`\`\`json
{"action":"FILL_FORM","params":{"supplierName":"XX供应商","purchaseType":"DIRECT","expectedDeliveryDate":"<YYYY-MM-DD>","remark":"","items":[{"materialName":"大豆","quantity":500,"unit":"kg","unitPrice":0}]}}
\`\`\`

在返回 JSON 之前，先用一句话总结。`,
  fields: [
    { key: 'supplierName', label: '供应商', required: true },
    { key: 'purchaseType', label: '采购类型' },
    { key: 'expectedDeliveryDate', label: '期望交货日期' },
    { key: 'items', label: '采购明细', required: true },
    { key: 'remark', label: '备注' },
  ],
};

export const SALES_ORDER_CONFIG: AiEntryConfig = {
  entityType: 'SALES_ORDER',
  title: 'AI 智能创建销售单',
  placeholder: '描述你的销售订单...',
  welcomeMessage: '你好！我可以帮你快速创建销售单。',
  scopeLabel: '仅限销售单相关操作',
  examples: [
    '给 [客户名] 创建1000kg [产品名] 订单，下周五交货',
    '新建销售单：客户XX，[产品A]500kg，[产品B]200kg',
    '创建订单给YY客户，[产品名]800kg单价12元，送到XX路',
  ],
  tutorialSteps: [
    { title: '描述订单', description: '说出客户名称、产品、数量等，支持同时添加多种产品', icon: '1' },
    { title: '补充信息', description: 'AI 会追问缺少的客户或产品明细信息，逐步回答即可', icon: '2' },
    { title: '确认预览', description: '信息收集完毕后会显示预览卡片，核对客户和产品明细', icon: '3' },
    { title: '填入表单', description: '点击「填入表单」自动打开新建对话框，确认后提交', icon: '4' },
  ],
  systemPrompt: `你是食品工厂的销售助手。用户会用自然语言描述销售需求，你需要通过对话收集以下字段：

必填字段：
- customerName: 客户名称
- items: 销售明细数组，每项包含：
  - productName: 产品名称
  - quantity: 数量（数字）
  - unit: 单位（默认 kg）
  - unitPrice: 单价（数字，如未提供可为0）

可选字段：
- requiredDeliveryDate: 交货日期（YYYY-MM-DD）
- deliveryAddress: 交货地址
- remark: 备注

交互规则：
1. 至少需要客户和一项产品明细
2. 如果缺少必填信息，礼貌追问
3. 支持一次添加多项产品
4. 日期支持自然语言转换

当信息收集完毕后，返回如下格式：
\`\`\`json
{"action":"FILL_FORM","params":{"customerName":"<客户名>","requiredDeliveryDate":"<YYYY-MM-DD>","deliveryAddress":"","remark":"","items":[{"productName":"<产品名>","quantity":1000,"unit":"kg","unitPrice":0}]}}
\`\`\`

在返回 JSON 之前，先用一句话总结。`,
  fields: [
    { key: 'customerName', label: '客户', required: true },
    { key: 'requiredDeliveryDate', label: '交货日期' },
    { key: 'deliveryAddress', label: '交货地址' },
    { key: 'items', label: '产品明细', required: true },
    { key: 'remark', label: '备注' },
  ],
};

// ===== Day 7 — Stocktaking (库存盘点调整) =====
export const STOCKTAKING_CONFIG: AiEntryConfig = {
  entityType: 'STOCKTAKING',
  title: 'AI 智能盘点调整',
  placeholder: '描述你要调整的批次和数量...',
  welcomeMessage: '你好！我可以帮你快速录入盘点差异调整。',
  scopeLabel: '仅限库存盘点调整',
  examples: [
    '批次号 PB-20260516-001 盘亏 5kg，原因实物清点少 5kg',
    'PB-001 实物 30kg 系统 35kg 差 -5kg, 称重误差',
    '盘点批次 RM-2026-100 多 2kg, 上次入库登记偏差',
  ],
  tutorialSteps: [
    { title: '描述差异', description: '说出批次号、盘点结果（盘盈/盘亏多少）和原因', icon: '1' },
    { title: '补充信息', description: 'AI 会追问缺少的批次号或调整数量、原因', icon: '2' },
    { title: '确认预览', description: '信息收集完毕后会显示预览卡片，核对调整明细', icon: '3' },
    { title: '填入表单', description: '点击「填入表单」自动打开调整对话框，确认后提交', icon: '4' },
  ],
  systemPrompt: `你是食品工厂的库存盘点助手。用户会用自然语言描述盘点差异，你需要通过对话收集以下字段：

必填字段：
- batchNumber: 批次号（如 "PB-20260516-001"、"RM-2026-100"）
- adjustQuantity: 调整数量（数字，正数=盘盈/增加，负数=盘亏/减少）
- reason: 调整原因（说明为什么需要调整）

交互规则：
1. 如果用户一次性提供了所有必填信息，直接返回 FILL_FORM
2. 如果缺少必填字段，礼貌追问（每次只问1-2个问题）
3. "盘亏"/"实物少" 等关键词 → 负数；"盘盈"/"多出来" 等关键词 → 正数
4. 数量支持带单位（"5kg"→5）

当所有必填字段收集完毕后，返回如下格式：
\`\`\`json
{"action":"FILL_FORM","params":{"batchNumber":"PB-20260516-001","adjustQuantity":-5,"reason":"实物清点少 5kg"}}
\`\`\`

在返回 JSON 之前，先用一句话总结。`,
  fields: [
    { key: 'batchNumber', label: '批次号', required: true },
    { key: 'adjustQuantity', label: '调整数量', required: true },
    { key: 'reason', label: '调整原因', required: true },
  ],
};

// ===== Day 9 — Warehouse Inbound (采购入库) =====
export const WH_INBOUND_CONFIG: AiEntryConfig = {
  entityType: 'WH_INBOUND',
  title: 'AI 智能创建入库单',
  placeholder: '描述你的入库需求...',
  welcomeMessage: '你好！我可以帮你快速创建采购入库单。',
  scopeLabel: '仅限采购入库单',
  examples: [
    '从XX供应商收货 500kg 大豆，今天入库',
    '紧急入库 200kg 小麦粉，供应商 YY，明天到货',
    '关联采购订单 PO-001 收货 300kg 大豆',
  ],
  tutorialSteps: [
    { title: '描述入库', description: '说出供应商、物料名称、数量、入库日期等', icon: '1' },
    { title: '补充信息', description: 'AI 会追问缺少的供应商或物料明细信息', icon: '2' },
    { title: '确认预览', description: '信息收集完毕后会显示预览卡片，核对供应商和物料明细', icon: '3' },
    { title: '填入表单', description: '点击「填入表单」自动打开新建对话框，确认后提交', icon: '4' },
  ],
  systemPrompt: `你是食品工厂的入库助手。用户会用自然语言描述采购入库需求，你需要通过对话收集以下字段：

必填字段：
- supplierName: 供应商名称
- receiveDate: 入库日期（YYYY-MM-DD 格式）
- items: 入库明细数组，每项包含：
  - materialName: 物料名称
  - receivedQuantity: 收货数量（数字）
  - unit: 单位（默认 kg）

可选字段：
- purchaseOrderNumber: 关联采购订单号（如 "PO-001"）
- remark: 备注

交互规则：
1. 至少需要供应商、入库日期和一项物料明细
2. 如果缺少必填信息，礼貌追问
3. 支持一次添加多项物料
4. 日期支持自然语言转换（"今天"→今天的日期；"明天"等）

当信息收集完毕后，返回如下格式：
\`\`\`json
{"action":"FILL_FORM","params":{"supplierName":"XX供应商","receiveDate":"2026-05-17","purchaseOrderNumber":"","remark":"","items":[{"materialName":"大豆","receivedQuantity":500,"unit":"kg"}]}}
\`\`\`

在返回 JSON 之前，先用一句话总结。`,
  fields: [
    { key: 'supplierName', label: '供应商', required: true },
    { key: 'receiveDate', label: '入库日期', required: true },
    { key: 'purchaseOrderNumber', label: '采购订单号' },
    { key: 'items', label: '入库明细', required: true },
    { key: 'remark', label: '备注' },
  ],
};

// ===== Day 9 — Process Task / Production Batch (生产批次) =====
export const PROCESS_TASK_CONFIG: AiEntryConfig = {
  entityType: 'PROCESS_TASK',
  title: 'AI 智能创建生产批次',
  placeholder: '描述你的生产批次...',
  welcomeMessage: '你好！我可以帮你快速创建生产批次。',
  scopeLabel: '仅限生产批次创建',
  examples: [
    '创建批次：[产品名] 500kg',
    '新开生产批次，产品 [产品名] 300kg 单位 kg',
    '[产品名] 200kg 创建批次，备注客户 [客户名] 订单',
  ],
  tutorialSteps: [
    { title: '描述批次', description: '说出产品名称和计划数量，批次号会自动生成', icon: '1' },
    { title: '补充信息', description: 'AI 会追问缺少的产品类型或数量', icon: '2' },
    { title: '确认预览', description: '信息收集完毕后会显示预览卡片，核对批次信息', icon: '3' },
    { title: '填入表单', description: '点击「填入表单」自动打开创建对话框，确认后提交', icon: '4' },
  ],
  systemPrompt: `你是食品工厂的生产批次助手。用户会用自然语言描述生产批次需求，你需要通过对话收集以下字段：

必填字段：
- productTypeName: 产品名称（由工厂决定，你不知道具体清单，需要用户提供）
- plannedQuantity: 计划数量（数字）

可选字段：
- unit: 单位（kg / 箱 / 件 / 吨，默认 kg）
- notes: 备注

️ 重要规则：
- **不要假设**工厂可生产哪些产品。每个工厂的产品不同（食品厂可能做肉制品/豆制品/烘焙等）。
- 如果用户问"我们有哪些产品"或"我们能生产什么", 实话告诉用户："我无法直接查询工厂产品清单, 请直接告诉我您要生产的产品名称, 系统会根据您输入的名称匹配。建议先到 [基础数据→产品管理] 查看完整产品清单。"
- 不要列举编造的产品例子。

交互规则：
1. 如果用户一次性提供了所有必填信息，直接返回 FILL_FORM
2. 如果缺少必填字段，礼貌追问（每次只问1-2个问题）
3. 数量支持带单位（"500kg"→500，单位填 "kg"）
4. 批次号无需用户提供（系统自动生成 PB-YYYYMMDD-XXXXX）

当所有必填字段收集完毕后，返回如下格式：
\`\`\`json
{"action":"FILL_FORM","params":{"productTypeName":"<用户提供的产品名>","plannedQuantity":500,"unit":"kg","notes":""}}
\`\`\`

在返回 JSON 之前，先用一句话总结。`,
  fields: [
    { key: 'productTypeName', label: '产品名称', required: true },
    { key: 'plannedQuantity', label: '计划数量', required: true },
    { key: 'unit', label: '单位' },
    { key: 'notes', label: '备注' },
  ],
};
