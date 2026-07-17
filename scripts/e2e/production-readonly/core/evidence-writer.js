'use strict';

const { sanitizeValue } = require('./sanitizer');

const SAFE_EVIDENCE_SEGMENTS = ['.playwright-mcp', 'test-results', 'test-output', 'tmp'];

function normalizePath(value) {
  return String(value || '').replace(/\\/g, '/').replace(/\/+$/, '');
}

function isSafeEvidenceDirectory(directory) {
  const normalized = normalizePath(directory).toLowerCase();
  return SAFE_EVIDENCE_SEGMENTS.some((segment) => normalized.split('/').includes(segment.toLowerCase()));
}

function joinPath(directory, filename) {
  return `${normalizePath(directory)}/${filename}`;
}

function markdownReport(report) {
  const lines = [
    '# Production read-only Playwright report',
    '',
    `- Run: ${report.runId}`,
    `- Base URL: ${report.baseUrl}`,
    `- Scenarios: ${report.scenarios.length}`,
    `- Auth requests: ${report.authRequests.length}`,
    `- Read-only POST requests: ${report.readonlyPostRequests.length}`,
    `- Blocked mutation attempts: ${report.blockedMutationAttempts.length}`,
    `- Actual business writes: ${report.actualBusinessWrites}`,
    `- Console retained/raw: ${report.console.retainedCount}/${report.console.rawCount}`,
    `- HTTP errors: ${report.network.httpErrors.length}`,
    '',
    '| Scenario | Result | Root cause | Writes | Duration |',
    '|---|---|---|---:|---:|',
  ];
  for (const result of report.scenarios) {
    lines.push(`| ${result.scenario} | ${result.result} | ${result.rootCauseClass} | ${result.actualBusinessWrites} | ${result.durationMs} ms |`);
  }
  lines.push('', '## Priorities', '');
  for (const priority of ['P0', 'P1', 'P2']) {
    const items = report.priorities?.[priority] || [];
    lines.push(`### ${priority}`, '', ...(items.length ? items.map((item) => `- ${item}`) : ['- None']), '');
  }
  lines.push('## Screenshots', '');
  const screenshots = report.scenarios.flatMap((result) => result.screenshots || []);
  lines.push(...(screenshots.length ? screenshots.map((item) => `- ${item}`) : ['- None']), '');
  return `${lines.join('\n')}\n`;
}

async function saveTextWithDownload(page, content, target, mimeType) {
  const downloadPromise = page.waitForEvent('download', { timeout: 15_000 });
  await page.evaluate(({ text, filename, type }) => {
    const blob = new Blob([text], { type });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }, { text: content, filename: target.split(/[\\/]/).pop(), type: mimeType });
  const download = await downloadPromise;
  await download.saveAs(target);
}

async function writeEvidence(report, evidenceDir, options = {}) {
  const directory = normalizePath(evidenceDir);
  if (options.productionReadonly !== false && !isSafeEvidenceDirectory(directory)) {
    throw new Error(`Evidence directory must be gitignored (.playwright-mcp/test-results/test-output/tmp): ${directory}`);
  }
  if (!options.page) throw new Error('A Playwright page is required to write browser-safe evidence');
  const safeReport = sanitizeValue(report);
  const jsonPath = joinPath(directory, 'report.json');
  const markdownPath = joinPath(directory, 'report.md');
  await saveTextWithDownload(options.page, `${JSON.stringify(safeReport, null, 2)}\n`, jsonPath, 'application/json');
  await saveTextWithDownload(options.page, markdownReport(safeReport), markdownPath, 'text/markdown');
  return { evidenceDir: directory, jsonPath, markdownPath };
}

module.exports = { SAFE_EVIDENCE_SEGMENTS, isSafeEvidenceDirectory, markdownReport, writeEvidence };
