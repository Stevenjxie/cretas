/**
 * AI 对话式建单 —— 各实体的抽屉配置。
 *
 * 2026-07-28: systemPrompt 已搬到后端 `resources/ai/form-prompts/factory/{ENTITY}.md`
 * (FormPromptRegistry + FormPromptRegistryTest 锁死防呆规则)。这里只留「前端要展示的东西」
 * (文案/示例/教程/预览字段) 和 entityType —— 后端按 entityType 查 prompt。
 * 搬走的理由见 FormPromptRegistry 的类注释: prompt 跟前端发布走 → 改不动、看不见、会复制。
 */

/** 字段类型 —— 传给后端当 formFields[].type，让模型知道该返回什么形状。 */
export type FieldType = 'string' | 'number' | 'array';

export interface FieldDef {
  key: string;
  label: string;
  required?: boolean;
  /** 省略视作 'string' */
  type?: FieldType;
}

export interface TutorialStep {
  title: string;
  description: string;
  icon: string;   // emoji icon
}

export interface AiEntryConfig {
  /** 后端据此查专属 prompt (ai/form-prompts/factory/{entityType}.md) */
  entityType: string;
  title: string;
  placeholder: string;
  welcomeMessage: string;
  scopeLabel: string;            // e.g. "仅限生产计划相关操作"
  examples: string[];            // clickable quick-start prompts
  tutorialSteps: TutorialStep[]; // step-by-step guide
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
  fields: [
    { key: 'productTypeName', label: '产品名称', required: true },
    { key: 'plannedQuantity', label: '计划数量', required: true, type: 'number' },
    { key: 'quantityUnit', label: '数量单位', required: true },
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
  fields: [
    { key: 'supplierName', label: '供应商', required: true },
    { key: 'purchaseType', label: '采购类型' },
    { key: 'expectedDeliveryDate', label: '期望交货日期' },
    { key: 'items', label: '采购明细', required: true, type: 'array' },
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
  fields: [
    { key: 'customerName', label: '客户', required: true },
    { key: 'requiredDeliveryDate', label: '交货日期' },
    { key: 'deliveryAddress', label: '交货地址' },
    { key: 'items', label: '产品明细', required: true, type: 'array' },
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
  fields: [
    { key: 'batchNumber', label: '批次号', required: true },
    { key: 'adjustQuantity', label: '调整数量', required: true, type: 'number' },
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
  fields: [
    { key: 'supplierName', label: '供应商', required: true },
    { key: 'receiveDate', label: '入库日期', required: true },
    { key: 'purchaseOrderNumber', label: '采购订单号' },
    { key: 'items', label: '入库明细', required: true, type: 'array' },
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
  fields: [
    { key: 'productTypeName', label: '产品名称', required: true },
    { key: 'plannedQuantity', label: '计划数量', required: true, type: 'number' },
    { key: 'unit', label: '单位' },
    { key: 'notes', label: '备注' },
  ],
};
