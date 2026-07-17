'use strict';

const { createScenarioResult, assertScenarioResult } = require('../core/result-schema');
const { ROUTES } = require('../config/routes');

module.exports = {
  id: 'tenant-isolation',
  async run(ctx) {
    const startedAt = Date.now();
    const result = createScenarioResult(this.id, ctx.page.url());
    ctx.scenarioRef.value = this.id;
    const body = await ctx.page.locator('body').innerText().catch(() => '');
    const login = ctx.loginEvidence;
    const staleTenantDetected = /liushanmen_admin/i.test(body);
    result.pageEvidence.push({
      username: login.username,
      factoryId: login.factoryId,
      factoryName: login.factoryName,
      displayedUsername: body.includes(login.username),
      displayedFactoryName: login.factoryName ? body.includes(login.factoryName) : null,
      staleTenantDetected,
    });
    result.apiEvidence.push(login);
    result.result = login.username === ctx.expectedUsername
      && login.factoryId === ctx.expectedFactoryId
      && !staleTenantDetected ? 'PASS' : 'CONFIRMED_DEFECT';
    result.rootCauseClass = result.result === 'PASS' ? 'none' : 'config';
    result.screenshots.push(await ctx.screenshot(this.id));
    result.durationMs = Date.now() - startedAt;
    return assertScenarioResult(result);
  },
  path: ROUTES.dashboard,
};
