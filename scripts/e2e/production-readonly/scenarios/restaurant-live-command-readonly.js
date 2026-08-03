'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');
const { pathnameOf } = require('../core/url-utils');

const STAFFING_METRIC_KEYS = ['predictedGuests', 'staffing', 'gap', 'confidence'];

module.exports = {
  id: 'restaurant-live-command-readonly',
  path: ROUTES.dashboard,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'restaurant-live-command-readonly',
    path: ROUTES.dashboard,
    landmarks: ['餐饮 AI 实时经营指挥屏', '数据传输链路', '明日预测客流'],
    screenshot: true,
    inspect: async (page) => {
      await page.waitForFunction(() => {
        const value = document.querySelector(
          '[data-testid="restaurant-live-metric-predictedGuests"] .digital-metric__value',
        );
        return value && value.textContent.trim() !== '—';
      }, null, { timeout: 15_000 }).catch(() => {});
      const command = page.locator('[data-testid="restaurant-live-command"]');
      const metricValues = Object.fromEntries(await Promise.all(STAFFING_METRIC_KEYS.map(async (key) => {
        const value = await page
          .locator(`[data-testid="restaurant-live-metric-${key}"] .digital-metric__value`)
          .innerText()
          .catch(() => '');
        return [key, value.trim()];
      })));
      const populatedMetricCount = Object.values(metricValues)
        .filter((value) => value && value !== '—')
        .length;
      const finalPath = pathnameOf(page.url());
      const commandCount = await command.count();
      const transmissionNodeCount = await command.locator('.transmission-rail li').count();
      const transmissionLabel = await command.locator('.live-command__eyebrow').innerText().catch(() => '');
      const loadError = await page.locator('.load-alert.el-alert--error').innerText().catch(() => '');
      const assessment = finalPath === ROUTES.dashboard
        && commandCount === 1
        && transmissionNodeCount === 3
        && populatedMetricCount === STAFFING_METRIC_KEYS.length
        && !loadError
        ? { result: 'PASS', rootCauseClass: 'none' }
        : { result: 'CONFIRMED_DEFECT', rootCauseClass: loadError ? 'backend' : 'frontend' };
      return {
        finalPath,
        commandCount,
        transmissionNodeCount,
        transmissionLabel,
        staffingMetricValues: metricValues,
        populatedMetricCount,
        loadError: loadError || null,
        assessment,
      };
    },
  }),
};
