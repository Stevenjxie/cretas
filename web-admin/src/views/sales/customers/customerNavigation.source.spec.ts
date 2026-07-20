import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const listSource = readFileSync(resolve(__dirname, 'list.vue'), 'utf8');
const detailSource = readFileSync(resolve(__dirname, 'detail.vue'), 'utf8');

describe('customer list/detail navigation contract', () => {
  it('carries filter, pagination and scroll state into the detail return target', () => {
    expect(listSource).toContain('function listRouteQuery(includeScroll = false)');
    expect(listSource).toContain('if (includeScroll && window.scrollY > 0)');
    expect(listSource).toContain('await syncListRoute(true)');
    expect(listSource).toContain('query: { from: returnTarget }');
  });

  it('renders an explicit return action and restores the safe customer-list URL', () => {
    expect(detailSource).toContain('@click="goBackCustomerList"');
    expect(detailSource).toContain('/^\\/sales\\/customers(?:\\?|$)/.test(from)');
    expect(detailSource).toContain('router.back()');
    expect(detailSource).toContain('router.replace(customerListTarget.value)');
  });

  it('uses replace as the direct-entry fallback without adding duplicate history entries', () => {
    const functionBody = detailSource.slice(
      detailSource.indexOf('function goBackCustomerList'),
      detailSource.indexOf('const customer = ref'),
    );
    expect(functionBody).toContain('window.history.state?.back === customerListTarget.value');
    expect(functionBody).not.toContain('router.push');
  });
});
