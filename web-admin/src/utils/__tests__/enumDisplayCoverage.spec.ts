import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  COMMON_ENUM_LABELS,
  FINISHED_GOODS_BATCH_STATUS_LABELS,
  INTERNAL_TRANSFER_STATUS_LABELS,
  MATERIAL_REQUISITION_STATUS_LABELS,
  PAYMENT_REQUEST_STATUS_LABELS,
  PRODUCTION_BATCH_STATUS_LABELS,
  PRODUCTION_PLAN_STATUS_LABELS,
  PRODUCTION_SETTLEMENT_POSTING_STATUS_LABELS,
  PURCHASE_INVOICE_RECONCILE_STATUS_LABELS,
  PURCHASE_ORDER_STATUS_LABELS,
  PURCHASE_RECEIVE_STATUS_LABELS,
  RETURN_ORDER_STATUS_LABELS,
  ROLE_LABELS,
  SALES_DELIVERY_STATUS_LABELS,
  SALES_ORDER_STATUS_LABELS,
  TRACE_STATUS_LABELS_BY_DOCUMENT_TYPE,
  TRANSFER_DIFF_STATUS_LABELS,
  enumLabel,
  traceStatusLabel,
} from '../enumDisplay';

/**
 * 客户 2026-07-30 (Sheet Row 6): 单据追踪抽屉显示「未知状态（IN_PROGRESS）」。
 *
 * 光补一个码没用 —— 下次后端加枚举值还会漏。本测试**直接读 Java 枚举源文件**断言覆盖,
 * 与 `EnumCheckConstraintDriftTest` 挡 DB CHECK 漂移是同一招: 让"忘了同步"在 CI 就红,
 * 而不是等客户看到英文码。
 */

const JAVA_ROOT = resolve(__dirname, '../../../../backend/java/cretas-api/src/main/java/com/cretas/aims');

function readJava(relative: string): string {
  const path = resolve(JAVA_ROOT, relative);
  // 刻意不 skip: 后端重命名/移动文件时必须让这条测试红, 静默跳过等于门禁消失。
  expect(existsSync(path), `找不到 ${relative} —— 后端文件被移动或改名了, 请同步本测试的路径`).toBe(true);
  return readFileSync(path, 'utf8');
}

/** 取顶层 enum 的常量名 (常量在 `;` 之前, 之后是字段/构造器)。 */
function topLevelEnumConstants(source: string, enumName: string): string[] {
  const start = source.indexOf(`enum ${enumName}`);
  expect(start, `${enumName} 声明找不到`).toBeGreaterThan(-1);
  const body = source.slice(source.indexOf('{', start) + 1);
  const head = body.split(';')[0];
  return [...head.matchAll(/^\s*([A-Z][A-Z0-9_]*)\s*(?:\(|,|$)/gm)].map((m) => m[1]);
}

/** 取嵌套 enum 的常量名 (无构造器时常量列表到 `}` 为止)。 */
function nestedEnumConstants(source: string, enumName: string): string[] {
  const start = source.indexOf(`enum ${enumName}`);
  expect(start, `嵌套 enum ${enumName} 声明找不到`).toBeGreaterThan(-1);
  const open = source.indexOf('{', start);
  const body = source.slice(open + 1, source.indexOf('}', open));
  return [...body.matchAll(/^\s*([A-Z][A-Z0-9_]*)\s*(?:\(|,|$)/gm)].map((m) => m[1]);
}

const ENUM_CASES: Array<{
  label: string;
  constants: () => string[];
  map: Record<string, string>;
}> = [
  {
    label: 'SalesOrderStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/SalesOrderStatus.java'), 'SalesOrderStatus'),
    map: SALES_ORDER_STATUS_LABELS,
  },
  {
    label: 'PurchaseOrderStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/PurchaseOrderStatus.java'), 'PurchaseOrderStatus'),
    map: PURCHASE_ORDER_STATUS_LABELS,
  },
  {
    label: 'PurchaseReceiveStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/PurchaseReceiveStatus.java'), 'PurchaseReceiveStatus'),
    map: PURCHASE_RECEIVE_STATUS_LABELS,
  },
  {
    label: 'ProductionBatchStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/ProductionBatchStatus.java'), 'ProductionBatchStatus'),
    map: PRODUCTION_BATCH_STATUS_LABELS,
  },
  {
    label: 'SalesDeliveryStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/SalesDeliveryStatus.java'), 'SalesDeliveryStatus'),
    map: SALES_DELIVERY_STATUS_LABELS,
  },
  {
    label: 'FactoryMaterialRequisition.Status',
    constants: () => nestedEnumConstants(readJava('entity/factory/FactoryMaterialRequisition.java'), 'Status'),
    map: MATERIAL_REQUISITION_STATUS_LABELS,
  },
  // ↓ 2026-07-30: 单据追踪扩到销售/采购/调拨后新增的四个枚举 (同样对着 Java 源文件断言)
  {
    label: 'ProductionPlanStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/ProductionPlanStatus.java'), 'ProductionPlanStatus'),
    map: PRODUCTION_PLAN_STATUS_LABELS,
  },
  {
    label: 'TransferStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/TransferStatus.java'), 'TransferStatus'),
    map: INTERNAL_TRANSFER_STATUS_LABELS,
  },
  {
    label: 'ReturnOrderStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/ReturnOrderStatus.java'), 'ReturnOrderStatus'),
    map: RETURN_ORDER_STATUS_LABELS,
  },
  {
    label: 'PaymentRequestStatus',
    constants: () => topLevelEnumConstants(readJava('entity/enums/PaymentRequestStatus.java'), 'PaymentRequestStatus'),
    map: PAYMENT_REQUEST_STATUS_LABELS,
  },
];

describe('枚举中文覆盖 (对着 Java 源文件断言)', () => {
  it.each(ENUM_CASES)('$label 的每个常量都有中文', ({ label, constants, map }) => {
    const values = constants();
    // 解析器坏掉会让循环空转而假绿 —— 先钉住"确实解析出了东西"
    expect(values.length, `${label} 一个常量都没解析出来, 大概率是本测试的解析器坏了`).toBeGreaterThan(3);

    const missing = values.filter((code) => !map[code]);
    expect(missing, `${label} 缺中文: ${missing.join(', ')} —— 后端加了枚举值但前端没补, `
      + '客户会看到「未知状态（CODE）」。补到 enumDisplay.ts 对应的分域表里。').toEqual([]);
  });

  it('单据追踪抽屉的每种 documentType 都配了状态表', () => {
    // key 必须与后端两个 trace service 的 document(type, …) 字面量一致。
    // 2026-07-30 起追踪扩到销售/采购/调拨, 第二个 service 也必须纳入门禁, 否则新单据类型
    // 加了没补中文这条测试照样绿。
    const sources = [
      readJava('service/production/ProductionDocumentTraceService.java'),
      readJava('service/trace/BusinessDocumentTraceService.java'),
    ];
    const types = sources.flatMap(
      (backend) => [...backend.matchAll(/document\(\s*"([A-Z_]+)"/g)].map((m) => m[1]),
    );
    expect(types.length, 'documentType 一个都没解析出来').toBeGreaterThan(3);
    // 锚点自身的类型也要有状态表 (抽屉顶部那行也渲染中文)
    const anchorTypes = sources.flatMap(
      (backend) => [...backend.matchAll(/anchorType\("([A-Z_]+)"\)/g)].map((m) => m[1]),
    );
    expect(anchorTypes.length, 'anchorType 一个都没解析出来').toBeGreaterThan(2);

    const unmapped = [...new Set([...types, ...anchorTypes])]
      .filter((t) => !TRACE_STATUS_LABELS_BY_DOCUMENT_TYPE[t]);
    expect(unmapped, `这些单据类型没有状态表, 会退回全局表并可能显示错的中文: ${unmapped.join(', ')}`)
      .toEqual([]);
  });

  it('String 型状态列也覆盖到 (它们不是枚举, 取值来自后端 setter)', () => {
    // production_settlements.posting_status —— 实体默认值 + 所有 setPostingStatus("…")
    const settlement = readJava('entity/ProductionSettlement.java');
    expect(settlement).toContain('PENDING_POSTING');
    for (const code of ['PENDING_POSTING', 'PENDING_CLEARING', 'PENDING_WAREHOUSE_RECEIPT', 'POSTED', 'POSTED_WITH_TOLERANCE']) {
      expect(PRODUCTION_SETTLEMENT_POSTING_STATUS_LABELS[code], `postingStatus 缺 ${code}`).toBeTruthy();
    }
    for (const code of ['AVAILABLE', 'DEFECTIVE', 'DEPLETED']) {
      expect(FINISHED_GOODS_BATCH_STATUS_LABELS[code], `成品批次状态缺 ${code}`).toBeTruthy();
    }
    // transfer_diff_records.status —— 实体默认值 "PENDING" + TransferDiffServiceImpl 的两个 setStatus
    const diff = readJava('entity/inventory/TransferDiffRecord.java');
    expect(diff).toContain('private String status = "PENDING"');
    for (const code of ['PENDING', 'RESOLVED']) {
      expect(TRANSFER_DIFF_STATUS_LABELS[code], `调拨差异状态缺 ${code}`).toBeTruthy();
    }
    // purchase_invoices.reconcile_status —— 取值写在实体的行内注释里
    const invoice = readJava('entity/inventory/PurchaseInvoice.java');
    expect(invoice).toContain('PENDING / MATCHED / MISMATCHED');
    for (const code of ['PENDING', 'MATCHED', 'MISMATCHED']) {
      expect(PURCHASE_INVOICE_RECONCILE_STATUS_LABELS[code], `采购发票对账状态缺 ${code}`).toBeTruthy();
    }
  });

  it('同码在新加的单据里也没有被全局表压平', () => {
    // PENDING: 付款申请是「待财务初审」, 采购发票是「待对账」, 全局表是「待处理」
    expect(traceStatusLabel('PAYMENT_REQUEST', 'PENDING')).toBe('待财务初审');
    expect(traceStatusLabel('PURCHASE_INVOICE', 'PENDING')).toBe('待对账');
    expect(enumLabel('PENDING')).toBe('待处理');
    // FINANCE_APPROVED: 退货单是「财务已审」, 全局表是「财务已审核」
    expect(traceStatusLabel('SALES_RETURN', 'FINANCE_APPROVED')).toBe('财务已审');
    // PREPARED 只在生产计划里出现且是「草稿」—— 全局表根本没有这个码
    expect(traceStatusLabel('PRODUCTION_PLAN', 'PREPARED')).toBe('草稿');
    // 调拨的 RECEIVED 是「已签收」
    expect(traceStatusLabel('INTERNAL_TRANSFER', 'RECEIVED')).toBe('已签收');
  });

  it('客户实际撞到的那个码在生产批次语境下是「生产中」不是「进行中」', () => {
    expect(traceStatusLabel('PRODUCTION_BATCH', 'IN_PROGRESS')).toBe('生产中');
    // 全局表仍给一个中性译法, 兜住没有分域表的场景
    expect(enumLabel('IN_PROGRESS')).toBe('进行中');
    expect(enumLabel('IN_PROGRESS')).not.toContain('未知状态');
  });

  it('同码不同义靠分域表区分, 没有被全局表压平', () => {
    expect(traceStatusLabel('PURCHASE_RECEIPT', 'REJECTED')).toBe('已退回');
    expect(traceStatusLabel('SALES_ORDER', 'FINANCE_APPROVED')).toBe('财务已批准');
    expect(traceStatusLabel('PURCHASE_ORDER', 'FINANCE_APPROVED')).toBe('财务已审核');
  });

  it('角色码全部有中文 (审批人一栏原本只映射了 2 个角色)', () => {
    // 角色码的权威来源是 DB (users.role_code ∪ platform_role_permissions.role_code),
    // 代码里没有单一枚举可解析 —— 故此处钉住 2026-07-30 实测 prod 的全集。
    // 新增角色时同步这里和 ROLE_LABELS。
    const rolesInProd = [
      'cashier', 'department_admin', 'dispatcher', 'equipment_admin', 'factory_super_admin',
      'finance_manager', 'group_leader', 'hr_admin', 'operator', 'permission_admin',
      'platform_admin', 'procurement_manager', 'production_manager', 'quality_controller',
      'quality_inspector', 'quality_manager', 'restaurant_chef', 'restaurant_manager',
      'restaurant_owner', 'restaurant_purchaser', 'sales_manager', 'team_leader',
      'unactivated', 'viewer', 'warehouse_manager', 'warehouse_worker',
      'workshop_supervisor', 'yield_operator',
    ];
    const missing = rolesInProd.filter((role) => !ROLE_LABELS[role]);
    expect(missing, `角色缺中文: ${missing.join(', ')}`).toEqual([]);
    for (const role of rolesInProd) {
      expect(enumLabel(role), `${role} 渲染成了兜底文案`).not.toContain('未知状态');
    }
  });

  it('角色码 (小写) 与状态码 (大写) 不会互相串, 所以能同链兜底', () => {
    const roleKeys = Object.keys(ROLE_LABELS);
    const statusKeys = Object.keys(COMMON_ENUM_LABELS);
    expect(roleKeys.every((k) => k === k.toLowerCase())).toBe(true);
    expect(statusKeys.every((k) => k === k.toUpperCase())).toBe(true);
    expect(roleKeys.filter((k) => statusKeys.includes(k))).toEqual([]);
  });

  it('仍然把真正未知的码原样吐出来, 不假装认识 (禁降级)', () => {
    expect(enumLabel('SOME_BRAND_NEW_CODE')).toBe('未知状态（SOME_BRAND_NEW_CODE）');
    expect(enumLabel(null)).toBe('—');
    expect(enumLabel('')).toBe('—');
  });

  it('approval-workflow-editor 不再自带一份角色表 (曾有 4 份互相矛盾)', () => {
    const directory = readFileSync(
      resolve(__dirname, '../../views/platform/approval-workflow-editor/lib/approvalDirectory.ts'),
      'utf8',
    );
    expect(directory).toContain("import { ROLE_LABELS } from '@/utils/enumDisplay'");
    expect(directory).not.toContain('const FALLBACK_ROLE_LABELS');
    // 本页特有语义: 未登记的码是"历史配置", 不能退成「未知状态（…）」
    expect(directory).toContain("?? '历史审批角色'");
  });
});
