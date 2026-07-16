import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, 'list.vue'), 'utf8');

describe('material requisition list loading', () => {
  it('uses immutable plan snapshots and never fetches one production plan per row', () => {
    expect(source).toContain('row.productionPlanNumber');
    expect(source).not.toContain('production-plans/${id}');
  });
});
