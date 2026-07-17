'use strict';

const { createScenarioResult, assertScenarioResult } = require('../core/result-schema');

function arrayDelta(after, beforeLength) {
  return (after || []).slice(beforeLength);
}

async function runReadOnlyPageScenario(ctx, definition) {
  const startedAt = Date.now();
  const result = createScenarioResult(definition.id);
  ctx.scenarioRef.value = definition.id;
  const beforeNetwork = ctx.network.snapshot();
  const beforeConsole = ctx.consoleRecorder.snapshot();
  const beforeGuard = ctx.mutationGuard.snapshot();

  try {
    await ctx.page.goto(new URL(definition.path, ctx.baseUrl).toString(), {
      waitUntil: 'domcontentloaded',
      timeout: definition.timeoutMs || 45_000,
    });
    await ctx.page.waitForLoadState('networkidle', { timeout: 6_000 }).catch(() => {});
    result.url = ctx.page.url();
    const body = await ctx.page.locator('body').innerText().catch(() => '');
    const matched = (definition.landmarks || []).filter((landmark) => body.includes(landmark));
    result.pageEvidence.push({
      expectedLandmarks: definition.landmarks || [],
      matchedLandmarks: matched,
      bodyTextLength: body.trim().length,
      finalPath: new URL(result.url).pathname,
    });
    if (definition.inspect) {
      const inspected = await definition.inspect(ctx.page, body, ctx);
      if (inspected) result.pageEvidence.push(inspected);
    }
    if (definition.screenshot) result.screenshots.push(await ctx.screenshot(definition.id));
    result.result = matched.length === (definition.landmarks || []).length && body.trim().length > 40
      ? 'PASS'
      : 'UNVERIFIED';
    result.rootCauseClass = result.result === 'PASS' ? 'none' : 'tool';
  } catch (error) {
    result.result = 'TOOL_ERROR';
    result.rootCauseClass = 'tool';
    result.pageEvidence.push({ error: String(error?.message || error) });
  }

  await ctx.network.flush();
  const afterNetwork = ctx.network.snapshot();
  const afterConsole = ctx.consoleRecorder.snapshot();
  const afterGuard = ctx.mutationGuard.snapshot();
  result.apiEvidence = arrayDelta(afterNetwork.apiEvidence, beforeNetwork.apiEvidence.length);
  result.httpErrors = arrayDelta(afterNetwork.httpErrors, beforeNetwork.httpErrors.length);
  result.consoleErrors = arrayDelta(afterConsole.consoleErrors, beforeConsole.consoleErrors.length)
    .concat(arrayDelta(afterConsole.pageErrors, beforeConsole.pageErrors.length));
  result.consoleWarnings = arrayDelta(afterConsole.consoleWarnings, beforeConsole.consoleWarnings.length);
  result.blockedMutationAttempts = arrayDelta(afterGuard.blockedMutationAttempts, beforeGuard.blockedMutationAttempts.length);
  result.actualBusinessWrites = afterGuard.actualBusinessWrites - beforeGuard.actualBusinessWrites;
  if (result.blockedMutationAttempts.length || result.actualBusinessWrites > 0) {
    result.result = 'TOOL_ERROR';
    result.rootCauseClass = 'tool';
    result.pageEvidence.push({ safetyStatus: 'FAIL', reason: 'Unexpected mutation attempted during read-only scenario' });
  } else if (result.httpErrors.length || result.consoleErrors.length) {
    result.result = result.result === 'PASS' ? 'PARTIAL_DEFECT' : result.result;
    result.rootCauseClass = result.rootCauseClass === 'none' ? 'frontend' : result.rootCauseClass;
  }
  result.durationMs = Date.now() - startedAt;
  return assertScenarioResult(result);
}

module.exports = { runReadOnlyPageScenario };
