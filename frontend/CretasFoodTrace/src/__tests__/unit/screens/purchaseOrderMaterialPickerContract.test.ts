import * as fs from 'fs';
import * as path from 'path';

/**
 * 闸：新建采购单的物料选择器**必须收敛到「该供应商可供的原料」**。
 *
 * 2026-08-15（Google Sheet 反馈「采购订单新建 409」）：本屏此前加载的是**全厂所有原料**，
 * 选择器只按搜索词过滤、不看供应关系。用户选完供应商后能选到跟他没有供应关系的物料，
 * 一路填完提交才被后端拒：
 *   409「该供应商未启用所选物料的供应关系」/「供应商与物料的供应关系不存在」
 *
 * ⚠️ 这是防呆反模式：**界面提供了走不通的选项**。web-admin 一直是对的
 * （`resolveSupplierMaterialRelations` + 提交前「请选择当前供应商可供的所有原料」），
 * 只有 RN 这处漂了 —— 同一条规则两处实现，漏掉的那处从任何一侧看都像已经修好了。
 *
 * 闸守两件事：
 *   ① 屏幕确实按供应商取供应关系，且选择器从收敛后的集合出（不是原始 materials）
 *   ② API 客户端过滤掉 `active === false` 的关系（与 web-admin 同口径）
 *
 * ⚠️ 剥注释后再断言 —— 上面这段说明里就写着 `supplierRelations` 之类的名字，
 * 不剥的话闸会被自己的文档喂成假绿（本仓记过这个坑）。
 */
describe('闸: 采购单物料选择器按供应商收敛', () => {
  const SRC = path.join(__dirname, '..', '..', '..');

  const readCode = (rel: string): string => fs
    .readFileSync(path.join(SRC, rel), 'utf-8')
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/.*/g, ' ');

  const SCREEN = 'screens/factory-admin/inventory/PurchaseOrderCreateScreen.tsx';
  const CLIENT = 'services/api/supplierApiClient.ts';

  it('屏幕按供应商取供应关系, 且选择器从收敛后的集合出', () => {
    const code = readCode(SCREEN);

    // 仪器自检: 文件读到了、而且确实是这一屏
    expect(code.length).toBeGreaterThan(2000);
    expect(code).toContain('PurchaseOrderCreateScreen');

    // ① 必须按供应商取供应关系
    expect(code).toContain('getSupplierMaterials');

    // ② 选择器必须从「收敛后的集合」出, 而不是直接用全厂 materials
    expect(code).toMatch(/suppliedMaterials\s*=/);
    expect(code).toMatch(/visiblePickerMaterials\s*=[\s\S]{0,200}suppliedMaterials/);

    // ③ 阴性对照: 不许再退回「直接把 materials 交给选择器」的写法
    expect(code).not.toMatch(/visiblePickerMaterials\s*=\s*normalizedPickerSearch\s*\?\s*materials\b/);
  });

  it('API 客户端按 web-admin 同口径过滤掉停用的供应关系', () => {
    const code = readCode(CLIENT);
    expect(code).toContain('getSupplierMaterials');
    // active !== false —— 与 resolveSupplierMaterialRelations 一致
    expect(code).toMatch(/active\s*!==\s*false/);
  });
});
