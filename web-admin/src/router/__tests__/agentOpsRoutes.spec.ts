import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(process.cwd(), 'src/router/modules/ops.ts'), 'utf8');

describe('AgentOps route gate', () => {
  it('exposes three evidence pages only to explicit admin roles on restaurant tenants', () => {
    expect(source).toContain("const agentOpsAdminRoles = [");
    for (const role of ['factory_super_admin', 'platform_admin', 'permission_admin', 'restaurant_manager', 'restaurant_owner']) {
      expect(source).toContain(`'${role}'`);
    }
    expect(source).toMatch(/name:\s*'AgentOps'[\s\S]*roles:\s*agentOpsAdminRoles[\s\S]*hideForFactoryTypes:\s*\['FACTORY'\]/);
    expect(source).toMatch(/name:\s*'AgentOps'[\s\S]*businessDomain:\s*'RESTAURANT'/);
    expect(source).toContain("name: 'AgentOpsEvalSets'");
    expect(source).toContain("name: 'AgentOpsExperiments'");
    expect(source).toContain("name: 'AgentOpsRunTrace'");
  });
});
