import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const routerSource = readFileSync(resolve(process.cwd(), 'src/router/index.ts'), 'utf8');

describe('finance review routes', () => {
  it('uses the shared role gate for the sales finance review queue', () => {
    expect(routerSource).toContain('const financeReviewRoles = [');
    // 2026-08-16: 采购那两条路由已整条移除(恒空 + 按钮打 410)。阴性对照钉住它别回来。
    expect(routerSource).not.toContain('PurchaseOrderFinanceReviewList');
    expect(routerSource).not.toContain('PurchaseOrderFinanceReviewDetail');
    expect(routerSource).toMatch(/name:\s*'SalesOrderFinanceReviewList'[\s\S]*roles:\s*financeReviewRoles/);
    expect(routerSource).toMatch(/name:\s*'SalesOrderFinanceReviewDetail'[\s\S]*roles:\s*financeReviewRoles/);
  });
});
