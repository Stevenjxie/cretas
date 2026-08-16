import * as fs from 'fs';
import * as path from 'path';

import {
  resolvePurchaseSpecState,
  canSubmitPurchaseSpecState,
  buildPurchaseOrderItemPayload,
} from '../../../screens/factory-admin/inventory/purchaseOrderItemPayload';

/**
 * 闸：新建采购单必须能满足「供应关系已配置采购包装规格」这条后端要求。
 *
 * 2026-08-16。后端 `PurchaseServiceImpl.applySupplierPurchaseContract`：该供应关系只要有
 * **启用中**的采购包装规格，请求就必须带 `purchasePackagingSpecId`，否则
 *   422「该供应关系已配置采购包装规格，必须选择具体规格」
 * 而本屏此前**根本不发这个字段**，屏上也没有任何地方能填 —— 用户填完整张单才被拒，
 * 且无论怎么操作都过不去。
 *
 * ⚠️ 它当时**摸不到**：全库 `supplier_material_purchase_specs` 0 行。
 * 任何人在供应商详情点一次「新增规格」就会激活它 —— 属于「一次配置操作即武装」的潜伏死路，
 * 不是「不会发生」。
 *
 * 两个规格字段还必须**互斥**：同时发时后端比对二者换算系数，不一致抛
 * 409「供应商包装规格与原料包装换算不一致」。web-admin 一直是「要么发这个、要么发那个」。
 */
describe('闸: 采购单新建的采购包装规格', () => {
  const base = {
    materialTypeId: 'M1',
    supplierMaterialId: 'R1',
    purchasePackagingSpecId: '',
    materialPackagingSpecId: '',
  };

  describe('三态判定 —— 「不知道」不许塌成「不需要」', () => {
    it('配了规格但没选 → required（这正是此前会撞 422 的那一格）', () => {
      expect(resolvePurchaseSpecState(base, { R1: [{ id: 'S1' }] })).toBe('required');
    });

    it('配了规格且已选 → selected', () => {
      expect(resolvePurchaseSpecState(
        { ...base, purchasePackagingSpecId: 'S1' },
        { R1: [{ id: 'S1' }] },
      )).toBe('selected');
    });

    it('确认没有规格 → none（走原料包装那条）', () => {
      expect(resolvePurchaseSpecState(base, { R1: [] })).toBe('none');
    });

    it('🔴 取失败(null) → unknown，⛔ 不是 none', () => {
      // 这一条是本闸的核心：把 null 读成「没有规格」就会照常放行，提交时才撞 422。
      expect(resolvePurchaseSpecState(base, { R1: null })).toBe('unknown');
    });

    it('还没取(undefined) → loading，⛔ 也不是 none', () => {
      expect(resolvePurchaseSpecState(base, {})).toBe('loading');
    });

    it('选了物料却没有供应关系 id → unknown（查不了，不等于没有）', () => {
      expect(resolvePurchaseSpecState({ ...base, supplierMaterialId: '' }, {})).toBe('unknown');
    });

    it('还没选物料 → none（这一行本来就还没成形）', () => {
      expect(resolvePurchaseSpecState({ ...base, materialTypeId: '' }, {})).toBe('none');
    });
  });

  describe('可提交性', () => {
    it('只有 none / selected 放行', () => {
      expect(canSubmitPurchaseSpecState('none')).toBe(true);
      expect(canSubmitPurchaseSpecState('selected')).toBe(true);
    });

    it('🔴 required / unknown / loading 一律拦住', () => {
      // 阴性对照：这三态里任何一个被放行，用户都会撞上一个他满足不了的 422。
      expect(canSubmitPurchaseSpecState('required')).toBe(false);
      expect(canSubmitPurchaseSpecState('unknown')).toBe(false);
      expect(canSubmitPurchaseSpecState('loading')).toBe(false);
    });
  });

  describe('payload 的两个规格字段互斥', () => {
    const row = {
      materialTypeId: 'M1',
      supplierMaterialId: 'R1',
      purchasePackagingSpecId: '',
      materialPackagingSpecId: '',
      quantity: '3',
      unitPrice: '12.5',
      unit: 'kg',
    };

    it('🔴 选了采购规格 → 发 purchasePackagingSpecId，且【不发】materialPackagingSpecId', () => {
      const payload = buildPurchaseOrderItemPayload({
        ...row, purchasePackagingSpecId: 'S1', materialPackagingSpecId: 'MP1', unit: 'box',
      });
      expect(payload.purchasePackagingSpecId).toBe('S1');
      // 同时发会让后端比对换算系数 → 409「供应商包装规格与原料包装换算不一致」
      expect(payload.materialPackagingSpecId).toBeUndefined();
    });

    it('没有采购规格 → 照旧发 materialPackagingSpecId', () => {
      const payload = buildPurchaseOrderItemPayload({ ...row, materialPackagingSpecId: 'MP1' });
      expect(payload.materialPackagingSpecId).toBe('MP1');
      expect(payload.purchasePackagingSpecId).toBeUndefined();
    });

    it('带上 supplierMaterialId，空串转成 undefined 而不是发空串', () => {
      expect(buildPurchaseOrderItemPayload(row).supplierMaterialId).toBe('R1');
      expect(buildPurchaseOrderItemPayload({ ...row, supplierMaterialId: '' }).supplierMaterialId)
        .toBeUndefined();
    });

    it('数量与单价转成数字', () => {
      const payload = buildPurchaseOrderItemPayload(row);
      expect(payload.quantity).toBe(3);
      expect(payload.unitPrice).toBe(12.5);
    });
  });

  describe('屏幕确实接上了这些判定（抽了不接 = 没修）', () => {
    const SRC = path.join(__dirname, '..', '..', '..');
    const readCode = (rel: string): string => fs
      .readFileSync(path.join(SRC, rel), 'utf-8')
      // ⚠️ 剥注释后再断言 —— 上面那段说明里就写着这些名字，
      //    不剥的话闸会被自己的文档喂成假绿（本仓记过这个坑）。
      .replace(/\/\*[\s\S]*?\*\//g, ' ')
      .replace(/\/\/.*/g, ' ');

    const SCREEN = 'screens/factory-admin/inventory/PurchaseOrderCreateScreen.tsx';
    const CLIENT = 'services/api/supplierApiClient.ts';

    it('屏幕用纯函数构造 payload、并用它判可提交性', () => {
      const code = readCode(SCREEN);
      expect(code.length).toBeGreaterThan(2000);       // 仪器自检
      expect(code).toContain('PurchaseOrderCreateScreen');

      expect(code).toContain('buildPurchaseOrderItemPayload');
      expect(code).toContain('canSubmitPurchaseSpecState');
      expect(code).toContain('resolvePurchaseSpecState');
      // 用户得有地方选规格，否则拦住了也没有出路
      expect(code).toContain("openPicker('purchaseSpec'");
    });

    it('API 客户端取失败时返回 null 而不是空数组', () => {
      const code = readCode(CLIENT);
      expect(code).toContain('getSupplierPurchaseSpecs');
      // 与后端 findBy...AndActiveTrue 同口径
      expect(code).toMatch(/active\s*!==\s*false/);
      // 阴性对照：catch 里退回 [] 就等于把「不知道」说成「没有」
      expect(code).not.toMatch(/catch\s*\{\s*return\s*\[\]/);
    });
  });
});
