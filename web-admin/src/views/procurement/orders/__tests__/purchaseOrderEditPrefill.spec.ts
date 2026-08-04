import { describe, expect, it } from 'vitest';

import { resolveSupplierMaterialRelations } from '../purchaseOrderEditPrefill';

/**
 * 客户反馈 (Google Sheet 2026-07-20, 采购订单):
 * 「"编辑"功能无法进入，点击后仅会在域名后新增字符且无响应」.
 *
 * #2004 修好了第一层 (watch route.query.edit), 但 2026-08-04 在 prod 复现仍然打不开:
 * F006 唯一那张草稿单 PO-20260717-0002 是「开始采购」自动生成的, supplierId 为 null,
 * openEditDialog 仍然无条件调 listSupplierMaterials(factoryId, '') →
 * GET /F006/suppliers//materials (注意双斜杠) → 404 → 整个函数在 dialogVisible=true
 * 之前抛出, 弹窗永不打开 —— 用户看到的症状与修复前逐字一致.
 *
 * 讽刺的是这正是表里第 22 行要求「编辑」按钮的场景: 自动生成的采购单没有供应商,
 * 需要事后补录. 为它而生的入口, 恰恰只在它身上必崩.
 *
 * 同文件的 onSupplierChange 早就写对了 (`form.value.supplierId ? await ... : []`),
 * 本模块把那个判断抽出来, 让两个调用点共用同一套规则.
 */
describe('resolveSupplierMaterialRelations (Google Sheet 2026-07-20 / prod 复现 2026-08-04)', () => {
  it('无供应商的草稿单不发 supplier-scoped 请求, 直接给空关系表', async () => {
    const calls: Array<[string, string]> = [];
    const fetchRelations = async (factoryId: string, supplierId: string) => {
      calls.push([factoryId, supplierId]);
      return [{ id: 'r1', active: true }];
    };

    const relations = await resolveSupplierMaterialRelations('F006', null, fetchRelations);

    // 关键断言: 一次请求都不能发 —— 发了就会拼出 /F006/suppliers//materials 并 404.
    expect(calls).toEqual([]);
    expect(relations).toEqual([]);
  });

  it('空串与纯空格同样按「没有供应商」处理', async () => {
    const calls: string[] = [];
    const fetchRelations = async (_f: string, supplierId: string) => {
      calls.push(supplierId);
      return [];
    };

    expect(await resolveSupplierMaterialRelations('F006', '', fetchRelations)).toEqual([]);
    expect(await resolveSupplierMaterialRelations('F006', '   ', fetchRelations)).toEqual([]);
    expect(calls).toEqual([]);
  });

  it('有供应商时照常读取, 并滤掉已停用的供货关系', async () => {
    const fetchRelations = async (factoryId: string, supplierId: string) => {
      expect(factoryId).toBe('F006');
      expect(supplierId).toBe('sup-1');
      return [
        { id: 'r1', active: true },
        { id: 'r2', active: false },
        { id: 'r3' },
      ];
    };

    const relations = await resolveSupplierMaterialRelations('F006', 'sup-1', fetchRelations);

    // active 未声明视为在用 (与 onSupplierChange 的 `active !== false` 口径一致).
    expect(relations.map((row) => row.id)).toEqual(['r1', 'r3']);
  });

  it('真实读取失败必须抛出, 不能吞成空列表', async () => {
    // 禁止降级处理: 供应商存在却读不到供货关系, 是错误, 不是「这个供应商没有物料」.
    // 吞掉会让用户在残缺的物料下拉里存出错单.
    const fetchRelations = async () => {
      throw new Error('Request failed with status code 500');
    };

    await expect(
      resolveSupplierMaterialRelations('F006', 'sup-1', fetchRelations),
    ).rejects.toThrow('Request failed with status code 500');
  });
});
