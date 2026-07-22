import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const guardSource = readFileSync(resolve(process.cwd(), 'src/router/guards.ts'), 'utf8');
const routerSource = readFileSync(resolve(process.cwd(), 'src/router/index.ts'), 'utf8');

describe('personal OA route access', () => {
  it('allows finance managers to reach the shared OA workbench', () => {
    const financeWhitelist = guardSource.match(
      /finance_manager:\s*\[([\s\S]*?)\n\s*\],/,
    )?.[1];

    expect(financeWhitelist).toBeDefined();
    expect(financeWhitelist).toContain("'/workflow'");
  });

  it('keeps personal OA queues authenticated and under the shared dashboard module', () => {
    expect(routerSource).toMatch(
      /path:\s*'workflow'[\s\S]*title:\s*'个人 OA'[\s\S]*module:\s*'dashboard'/,
    );
    for (const path of ['pending', 'my-created', 'acted', 'copied']) {
      expect(routerSource).toMatch(
        new RegExp(`path:\\s*'${path}'[\\s\\S]*?module:\\s*'dashboard'`),
      );
    }
  });
});
