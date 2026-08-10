import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const transferSource = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');
const routerSource = readFileSync(resolve(import.meta.dirname, '../../../router/index.ts'), 'utf8');
const globalStyleSource = readFileSync(resolve(import.meta.dirname, '../../../style.css'), 'utf8');

describe('调拨新建小屏工作区', () => {
  it('使用独立路由承载长表单，并避免动态 :id 抢先匹配', () => {
    const newRoute = routerSource.indexOf("path: 'new'");
    const detailRoute = routerSource.indexOf("path: ':id'", newRoute);

    expect(newRoute).toBeGreaterThan(-1);
    expect(detailRoute).toBeGreaterThan(newRoute);
    expect(routerSource).toContain("name: 'TransferCreate'");
    expect(transferSource).toContain("router.push({ name: 'TransferCreate' })");
    expect(transferSource).toContain('v-if="isCreateWorkspace"');
    expect(transferSource).not.toContain('<el-dialog');
  });

  it('保留仓管防错边界并在成功后进入详情', () => {
    expect(transferSource).toContain('findDuplicateTransferRow(form.value.items)');
    expect(transferSource).toContain('transferBaseQuantity(it) > Number(stock)');
    expect(transferSource).toContain(':max="transferPackageLimit(row)"');
    expect(transferSource).toContain('同一种物料不能拆成重复行');
    expect(transferSource).toContain("router.replace({ name: 'TransferDetail'");
  });

  it('为窄屏提供字段重排、局部表格滚动和固定操作区', () => {
    expect(transferSource).toContain('class="material-table-scroll"');
    expect(transferSource).toContain('.workspace-actions {');
    expect(transferSource).toContain('flex: 0 0 auto;');
    expect(transferSource).toContain('@media (max-width: 768px)');
    expect(transferSource).toContain('.create-workspace :deep(.el-col-12)');
  });
});

describe('全局业务弹窗安全基线', () => {
  it('限制 Dialog 在 viewport 内，并只让正文滚动', () => {
    expect(globalStyleSource).toContain('.el-overlay-dialog > .el-dialog:not(.is-fullscreen)');
    expect(globalStyleSource).toContain('max-height: calc(100dvh - 24px)');
    expect(globalStyleSource).toContain('> .el-dialog__body');
    expect(globalStyleSource).toContain('overscroll-behavior: contain');
  });

  it('Drawer 正文可滚动且底部操作区不被挤出', () => {
    expect(globalStyleSource).toContain('.el-drawer__body');
    expect(globalStyleSource).toContain('.el-drawer__footer');
    expect(globalStyleSource).toContain('flex: 0 0 auto');
  });
});
