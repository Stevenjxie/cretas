import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const routerSource = readFileSync(resolve(process.cwd(), 'src/router/index.ts'), 'utf8');

describe('finance review routes', () => {
  it('uses the same role gate for procurement and sales finance review queues', () => {
    expect(routerSource).toContain('const financeReviewRoles = [');
    expect(routerSource).toMatch(/name:\s*'PurchaseOrderFinanceReviewList'[\s\S]*roles:\s*financeReviewRoles/);
    expect(routerSource).toMatch(/name:\s*'PurchaseOrderFinanceReviewDetail'[\s\S]*roles:\s*financeReviewRoles/);
    expect(routerSource).toMatch(/name:\s*'SalesOrderFinanceReviewList'[\s\S]*roles:\s*financeReviewRoles/);
    expect(routerSource).toMatch(/name:\s*'SalesOrderFinanceReviewDetail'[\s\S]*roles:\s*financeReviewRoles/);
  });
});
