import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(process.cwd(), 'src/router/index.ts'), 'utf8');

describe('label QC tray crop refinement route', () => {
  it('keeps the single-tray refinement page platform-only', () => {
    expect(source).toContain("const platformAdminOnlyRoles = ['platform_admin']");
    expect(source).toMatch(
      /path:\s*'label-qc-tray-crops'[\s\S]*name:\s*'SystemLabelQcTrayCrops'[\s\S]*views\/platform\/label-qc-crops\/index\.vue[\s\S]*roles:\s*platformAdminOnlyRoles/,
    );
  });
});
