/**
 * 实体字段元数据注册表
 *
 * 添加新字段只需在对应数组中加一行配置，无需修改任何模板。
 * 后期可对接后端 FormTemplate API 实现 per-factory 动态配置。
 */

export interface FieldConfig {
  /** 字段 key，对应后端 entity camelCase 字段名 */
  key: string
  /** 显示标签 */
  label: string
  /** 字段类型 */
  type: 'text' | 'number' | 'decimal' | 'select' | 'date' | 'datetime' | 'boolean' | 'textarea' | 'image'
  /** 分组 — 表单按组渲染 */
  group: string
  /** 是否必填 */
  required?: boolean
  /** 占列数 (el-col span, 默认12=半行, 24=整行) */
  span?: number
  /** 下拉选项 (type=select 时) */
  options?: { label: string; value: string }[]
  /** 小数位 (type=decimal 时) */
  precision?: number
  /** 后缀文本 (如 "元", "kg") */
  suffix?: string
  /** 占位提示 */
  placeholder?: string
  /** 只在详情展示，不可编辑 */
  readonly?: boolean
  /** 排序权重 (越小越靠前, 默认100) */
  order?: number
  /** 是否默认隐藏 (可通过配置开启) */
  hidden?: boolean
  /** select: 允许用户手动输入创建新选项 (filterable + allow-create) */
  allowCreate?: boolean
  /** tooltip 说明文字 */
  tooltip?: string
}

export interface EntityFieldGroup {
  name: string
  label: string
  fields: FieldConfig[]
}

/** 将扁平字段数组按 group 聚合 */
export function groupFields(fields: FieldConfig[]): EntityFieldGroup[] {
  const sorted = [...fields].sort((a, b) => (a.order ?? 100) - (b.order ?? 100))
  const map = new Map<string, FieldConfig[]>()
  for (const f of sorted) {
    if (f.hidden) continue
    const arr = map.get(f.group) || []
    arr.push(f)
    map.set(f.group, arr)
  }
  return Array.from(map.entries()).map(([name, fields]) => ({
    name,
    label: name,
    fields,
  }))
}

// ======================== 产品信息表 ========================

export const productTypeFields: FieldConfig[] = [
  // --- 基本信息 ---
  { key: 'code', label: '产品编号', type: 'text', group: '基本信息', required: true, order: 1 },
  { key: 'name', label: '产品名称', type: 'text', group: '基本信息', required: true, order: 2 },
  { key: 'category', label: '分类', type: 'select', group: '基本信息', order: 3,
    options: [
      { label: '成品', value: 'FINISHED_PRODUCT' },
      { label: '原料', value: 'RAW_MATERIAL' },
      { label: '包材', value: 'PACKAGING' },
      { label: '调料', value: 'SEASONING' },
      { label: '客供料', value: 'CUSTOMER_MATERIAL' },
    ] },
  { key: 'productCategory', label: '产品大类', type: 'text', group: '基本信息', order: 4 },
  { key: 'specification', label: '规格', type: 'text', group: '基本信息', order: 5, placeholder: '如 200g/盒、1kg/袋' },
  { key: 'unit', label: '单位', type: 'text', group: '基本信息', order: 6 },
  { key: 'brand', label: '品牌', type: 'text', group: '基本信息', order: 7 },
  { key: 'imageUrl', label: '产品图片', type: 'image', group: '基本信息', order: 8, span: 24 },

  // --- 价格/商务 ---
  { key: 'unitPrice', label: '不含税单价', type: 'decimal', group: '价格与商务', precision: 4, suffix: '元', order: 10 },
  { key: 'taxIncludedUnitPrice', label: '含税单价', type: 'decimal', group: '价格与商务', precision: 4, suffix: '元', order: 11 },
  { key: 'settlementMethod', label: '结算方式', type: 'select', group: '价格与商务', order: 12,
    options: [
      { label: '月结', value: 'MONTHLY' },
      { label: '现结', value: 'CASH' },
      { label: '预付', value: 'PREPAID' },
      { label: '货到付款', value: 'COD' },
    ] },
  { key: 'boxConversionCoefficient', label: '箱规转换系数', type: 'decimal', group: '价格与商务', precision: 4, order: 13,
    placeholder: '如 10kg/箱 填 10' },

  // --- 库存/采购 ---
  { key: 'inventoryWarningThreshold', label: '库存预警值', type: 'decimal', group: '库存与采购', precision: 2, order: 20 },
  { key: 'minimumOrderQuantity', label: '起订量(MOQ)', type: 'decimal', group: '库存与采购', precision: 2, order: 21 },
  { key: 'shelfLifeDays', label: '保质期(天)', type: 'number', group: '库存与采购', order: 22 },
  { key: 'temperatureZone', label: '温区', type: 'select', group: '库存与采购', order: 23,
    options: [
      { label: '常温', value: '常温' },
      { label: '冷藏', value: '冷藏' },
      { label: '冷冻', value: '冷冻' },
    ] },

  // --- 生产 ---
  { key: 'productionTimeMinutes', label: '生产周期(分钟)', type: 'number', group: '生产', order: 30 },
  { key: 'relatedCustomer', label: '关联客户', type: 'text', group: '生产', order: 31 },

  // --- 备注 ---
  { key: 'notes', label: '备注', type: 'textarea', group: '其他', span: 24, order: 99 },
]

// ======================== 客户信息表 ========================

export const customerFields: FieldConfig[] = [
  // --- 基本信息 ---
  { key: 'customerCode', label: '客户编号', type: 'text', group: '基本信息', required: true, order: 1 },
  { key: 'name', label: '客户简称', type: 'text', group: '基本信息', required: true, order: 2 },
  { key: 'customerType', label: '客户性质', type: 'select', group: '基本信息', order: 3,
    options: [
      { label: '餐饮', value: 'RESTAURANT' },
      { label: '经销', value: 'DISTRIBUTOR' },
      { label: '零售', value: 'RETAIL' },
      { label: '电商', value: 'ECOMMERCE' },
      { label: '加工', value: 'PROCESSOR' },
    ] },
  { key: 'rating', label: '客户等级', type: 'select', group: '基本信息', order: 4,
    options: [
      { label: 'A类客户', value: '1' },
      { label: 'B类客户', value: '2' },
      { label: 'C类客户', value: '3' },
    ] },
  { key: 'paymentTerms', label: '结算模式', type: 'text', group: '基本信息', order: 5, placeholder: '如 月结30天' },

  // --- 联系方式 ---
  { key: 'contactName', label: '联系人', type: 'text', group: '联系方式', order: 10 },
  { key: 'contactPhone', label: '联系电话', type: 'text', group: '联系方式', order: 11 },
  { key: 'contactEmail', label: '邮箱', type: 'text', group: '联系方式', order: 12 },
  { key: 'shippingAddress', label: '送货地址', type: 'textarea', group: '联系方式', span: 24, order: 13 },

  // --- 开票信息 ---
  { key: 'taxNumber', label: '纳税人识别号', type: 'text', group: '开票信息', order: 20 },
  { key: 'billingAddress', label: '开票地址', type: 'text', group: '开票信息', order: 21 },
  { key: 'bankName', label: '开户行', type: 'text', group: '开票信息', order: 22 },
  { key: 'bankAccount', label: '银行账号', type: 'text', group: '开票信息', order: 23 },

  // --- 信用 ---
  { key: 'creditLimit', label: '信用额度', type: 'decimal', group: '信用管理', precision: 2, suffix: '元', order: 30 },
  { key: 'currentBalance', label: '当前余额', type: 'decimal', group: '信用管理', precision: 2, suffix: '元', readonly: true, order: 31 },

  // --- 备注 ---
  { key: 'notes', label: '备注', type: 'textarea', group: '其他', span: 24, order: 99 },
]

// ======================== 销售订单 ========================

export const salesOrderFields: FieldConfig[] = [
  // --- 基本信息 ---
  { key: 'orderNumber', label: '订单编号', type: 'text', group: '基本信息', readonly: true, order: 1 },
  { key: 'salesperson', label: '业务员', type: 'text', group: '基本信息', order: 2 },
  { key: 'orderDate', label: '销售日期', type: 'date', group: '基本信息', order: 3 },
  { key: 'requiredDeliveryDate', label: '交货日期', type: 'date', group: '基本信息', order: 4 },
  { key: 'deliveryReminderDate', label: '交付提醒日期', type: 'date', group: '基本信息', order: 5 },
  { key: 'deliveryAddress', label: '送货地址', type: 'textarea', group: '基本信息', span: 24, order: 6 },

  // --- 金额 ---
  { key: 'totalAmount', label: '销售订单金额', type: 'decimal', group: '金额信息', precision: 2, suffix: '元', order: 10 },
  { key: 'estimatedCost', label: '成本订单金额', type: 'decimal', group: '金额信息', precision: 2, suffix: '元', order: 11 },
  { key: 'estimatedProfit', label: '预估利润', type: 'decimal', group: '金额信息', precision: 2, suffix: '元', readonly: true, order: 12 },
  { key: 'boxQuantity', label: '下单箱数', type: 'decimal', group: '金额信息', precision: 2, order: 13 },

  // --- 物流 ---
  { key: 'shippingIncluded', label: '是否含运', type: 'boolean', group: '物流', order: 20 },
  { key: 'shippingFee', label: '运费', type: 'decimal', group: '物流', precision: 2, suffix: '元', order: 21 },
  { key: 'transportPlanStatus', label: '运输计划状态', type: 'select', group: '物流', order: 22,
    options: [
      { label: '未定制', value: 'NOT_PLANNED' },
      { label: '已安排', value: 'PLANNED' },
      { label: '运输中', value: 'IN_TRANSIT' },
      { label: '已送达', value: 'DELIVERED' },
    ] },

  // --- 开票/回款 (readonly, Phase 3 填充) ---
  { key: 'invoiceStatus', label: '开票状态', type: 'select', group: '财务状态', readonly: true, order: 30,
    options: [
      { label: '未开票', value: 'NOT_INVOICED' },
      { label: '部分开票', value: 'PARTIAL_INVOICED' },
      { label: '已开票', value: 'FULLY_INVOICED' },
    ] },
  { key: 'invoicedAmount', label: '已开票金额', type: 'decimal', group: '财务状态', precision: 2, suffix: '元', readonly: true, order: 31 },
  { key: 'paidAmount', label: '已收款金额', type: 'decimal', group: '财务状态', precision: 2, suffix: '元', readonly: true, order: 32 },
  { key: 'settlementFlag', label: '是否结清', type: 'boolean', group: '财务状态', readonly: true, order: 33 },

  // --- 关联 ---
  { key: 'quoteId', label: '关联报价单', type: 'text', group: '关联', readonly: true, order: 40 },

  // --- 备注 ---
  { key: 'remark', label: '备注', type: 'textarea', group: '其他', span: 24, order: 99 },
]

// ======================== 销售订单明细行 ========================

export const salesOrderItemFields: FieldConfig[] = [
  { key: 'productName', label: '产品名称', type: 'text', group: '明细', order: 1 },
  { key: 'quantity', label: '下单数量', type: 'decimal', group: '明细', precision: 2, order: 2 },
  { key: 'unit', label: '单位', type: 'text', group: '明细', order: 3 },
  { key: 'unitPrice', label: '销售单价', type: 'decimal', group: '明细', precision: 4, suffix: '元', order: 4 },
  { key: 'costUnitPrice', label: '成本单价(含税)', type: 'decimal', group: '明细', precision: 4, suffix: '元', order: 5 },
  { key: 'taxRate', label: '税率', type: 'decimal', group: '明细', precision: 2, suffix: '%', order: 6 },
  { key: 'discountRate', label: '折扣率', type: 'decimal', group: '明细', precision: 2, suffix: '%', order: 7 },
  { key: 'deliveredQuantity', label: '已出库数量', type: 'decimal', group: '明细', precision: 2, readonly: true, order: 8 },
  { key: 'remark', label: '备注', type: 'text', group: '明细', order: 9 },
]
