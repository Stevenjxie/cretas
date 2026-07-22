import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('procurement unified OA contract', () => {
  const detail = source('src/views/procurement/orders/detail.vue');
  const pending = source('src/views/workflow/pending.vue');
  const router = source('src/router/index.ts');

  it('keeps purchase details read-only after submission and links to truthful progress', () => {
    expect(detail).toContain('/approval-progress');
    expect(detail).toContain("path: '/workflow/my-created'");
    expect(detail).not.toContain("path: '/workflow/my-participated'");
    expect(detail).not.toContain("finance-approve");
    expect(detail).not.toContain("finance-reject");
    expect(detail).not.toContain("submit-for-finance-review");
  });

  it('provides one pending-work entry and guards each action with the visible node', () => {
    expect(router).toContain("path: 'pending'");
    expect(router).toContain("name: 'WorkflowPending'");
    expect(pending).toContain('/workflow/instances/pending');
    expect(pending).toContain('/workflow/instances/${row.instanceId}/actions');
    expect(pending).toContain('expectedNodeId: row.currentNodeId');
    expect(pending).toContain('idempotencyKey: `oa-${action.toLowerCase()}-${row.instanceId}-${row.currentNodeId}`');
    expect(pending).toContain("operatingId.value = row.instanceId");
    expect(pending).toContain("new Set(['PURCHASE_ORDER', 'SALES_ORDER'])");
    expect(pending).toContain('value="SALES_ORDER"');
  });
});
