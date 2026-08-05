import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { usePermissionStore } from '@/store/modules/permission';
import { canWriteProductMaster, canWriteProductProcess } from '../productWriteGates';

const pageSource = readFileSync(resolve(__dirname, '..', 'index.vue'), 'utf8');

const permissionApiMocks = vi.hoisted(() => ({
  getPlatformPermissions: vi.fn(),
  getFactoryOverride: vi.fn(),
  getUserModuleAccess: vi.fn(),
}));
vi.mock('@/api/permissionApi', () => permissionApiMocks);

/** 用真实权限矩阵造探针 —— 行为断言全部经它, 不拿手写的假权限自证。 */
function probeFor(role: string) {
  const store = usePermissionStore();
  store.setRole(role, 'F006', 'FACTORY', '1309');
  return (module: string) => store.canWrite(module);
}

/**
 * 客户（张权）2026-08-05:「无法新建产品了」—— SKU 管理页右上角只剩 导出 / 导入 / SKU组装,
 * 「新增 SKU」整个不见了。报告人角色是**调度**。
 *
 * 根因是前后端判了两个不同的权限:
 *   后端 ProductTypeController 每一个写端点 = {"production:read_write","rd:read_write"}
 *   前端 products/index.vue 却按 canWrite('system') 决定显不显示
 * 调度是 production/rd 可写、system 只读 → 后端本来允许他建产品, 界面把入口藏了。
 */
describe('产品页写入闸必须与后端一致 (客户 2026-08-05「无法新建产品了」)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    permissionApiMocks.getPlatformPermissions.mockRejectedValue(new Error('offline'));
    permissionApiMocks.getFactoryOverride.mockRejectedValue(new Error('offline'));
    permissionApiMocks.getUserModuleAccess.mockRejectedValue(new Error('offline'));
  });

  it('调度能建产品 —— 这就是被修的那条', () => {
    const probe = probeFor('dispatcher');
    expect(canWriteProductMaster(probe)).toBe(true);
    expect(probe('system')).toBe(false);   // 缺陷成因: 页面原来取的是它
  });

  it('对产品主数据只读的角色仍然进不来 —— 修的是取错键, 不是把闸拆了', () => {
    // 仓管: production 'r' / rd '-'。刻意不用 operator —— 它 production 可写,
    // 拿它当「只读」例子会让断言恒真而失去意义 (第一版就这么写错, 被测试当场判红)。
    expect(canWriteProductMaster(probeFor('warehouse_manager'))).toBe(false);
  });

  it('🔴 两把闸不能合并: 销售经理能建产品, 但不能配工序', () => {
    // 后端 ProductWorkProcessController 的写端点只认 production, **不含 rd**;
    // sales_manager 是 production='r' / rd='rw', 正好落在两者之间。
    // 这条是「两把闸被合并」的唯一 oracle —— 源码 grep 对合并是沉默的。
    const probe = probeFor('sales_manager');
    expect(canWriteProductMaster(probe)).toBe(true);
    expect(canWriteProductProcess(probe)).toBe(false);
  });

  it('生产口径的角色两把闸都放行 —— 收紧不能收过头', () => {
    const probe = probeFor('dispatcher');
    expect(canWriteProductMaster(probe)).toBe(true);
    expect(canWriteProductProcess(probe)).toBe(true);
  });

  it('工序闸严格不宽于产品闸 —— 全角色扫一遍, 不靠挑例子', () => {
    const roles = ['dispatcher', 'sales_manager', 'warehouse_manager', 'quality_manager',
      'procurement_manager', 'equipment_admin', 'factory_super_admin', 'hr_admin', 'cashier'];
    for (const role of roles) {
      const probe = probeFor(role);
      if (canWriteProductProcess(probe)) {
        expect(canWriteProductMaster(probe), `${role}: 能配工序就必然能改产品`).toBe(true);
      }
    }
  });

  it('页面经这两把闸, 不再自己判 system', () => {
    expect(pageSource).toContain('canWriteProductMaster(probe)');
    expect(pageSource).toContain('canWriteProductProcess(probe)');
    expect(pageSource).not.toContain("permissionStore.canWrite('system')");
  });

  it('「导入」也是写操作, 不能对只读角色敞着', () => {
    expect(pageSource).toMatch(/<el-button[^>]*v-if="canWrite"[^>]*@click="handleImport"/);
  });

  it('编辑入口不藏 (打开走 GET, 要能看), 但保存按写产品的闸禁用', () => {
    expect(pageSource).toMatch(/@click="handleEdit\(row\)"/);
    expect(pageSource).not.toMatch(/v-if="canWrite"[^>]*@click="handleEdit\(row\)"/);
    expect(pageSource).toMatch(/:disabled="!canWrite"[\s\S]{0,120}@click="handleSubmit"/);
  });

  it('工序抽屉四个写动作全部禁用, 且说明原因 —— 只 disable 不解释是另一种 dead-end', () => {
    expect((pageSource.match(/!canWriteProcess/g) || []).length).toBeGreaterThanOrEqual(4);
    expect(pageSource).toMatch(/只读查看：需要生产模块写权限/);
    expect(pageSource).toMatch(/需要生产或研发写权限才能保存产品资料/);
  });
});
