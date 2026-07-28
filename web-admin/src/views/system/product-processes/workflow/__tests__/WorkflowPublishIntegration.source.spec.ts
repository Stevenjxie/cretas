import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(
    process.cwd(),
    'src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue',
  ),
  'utf8',
);

describe('ProductProcessWorkflowEditor atomic publish source contract', () => {
  it('uses a fresh preflight and the atomic publish endpoint', () => {
    const publishBody = source.slice(
      source.indexOf('async function publishWorkflow()'),
      source.indexOf('async function activateWorkflow()'),
    );

    expect(publishBody).toContain('getWorkflowBomSyncPreflight(');
    expect(publishBody).toContain('publishAndActivateProductProcessWorkflow(');
    expect(publishBody).toContain('canPublishWorkflowWithBomSync(freshPreflight)');
    expect(publishBody.indexOf('canPublishWorkflowWithBomSync(freshPreflight)'))
      .toBeLessThan(publishBody.indexOf('publishAndActivateProductProcessWorkflow('));
    expect(publishBody).toContain('const refreshedResponse = await getProductProcessWorkflow(');
    expect(publishBody.indexOf('const refreshedResponse = await getProductProcessWorkflow('))
      .toBeGreaterThan(publishBody.indexOf('getWorkflowBomSyncPreflight('));
    expect(publishBody.indexOf('const refreshedResponse = await getProductProcessWorkflow('))
      .toBeLessThan(publishBody.indexOf('publishAndActivateProductProcessWorkflow('));
  });

  it('does not call the legacy publish endpoint or activate separately inside publishWorkflow', () => {
    const publishBody = source.slice(
      source.indexOf('async function publishWorkflow()'),
      source.indexOf('async function activateWorkflow()'),
    );

    expect(publishBody).not.toContain('publishProductProcessWorkflow(');
    expect(publishBody).not.toContain('activateProductProcessWorkflow(');
  });

  it('preloads and reuses the BOM drawer component', () => {
    expect(source).toContain('scheduleBomUnifiedPanelPreload()');
    expect(source).toContain('void preloadBomUnifiedPanel();');
    expect(source).toContain('<Suspense>');
  });

  it('shows a named loading state before a route product is resolved', () => {
    expect(source).toContain('data-testid="workflow-product-loading"');
    expect(source).toContain('正在加载目标产品与 Workflow');
  });

  it('refreshes preflight after a publish race without retrying the mutation', () => {
    const raceBody = source.slice(
      source.indexOf('if (isWorkflowBomSyncRace(error))'),
      source.indexOf("console.error('[ProductProcessWorkflow] atomic publish failed'"),
    );

    expect(raceBody).toContain('getWorkflowBomSyncPreflight(');
    expect(raceBody).toContain('workflowBomSyncPreflight.value = refreshed.data');
    expect(raceBody).not.toContain('publishAndActivateProductProcessWorkflow(');
  });
});
