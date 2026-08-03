'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');
const { pathnameOf } = require('../core/url-utils');

const STAFFING_METRIC_KEYS = [
  'reservationOrders', 'liveGuests', 'predictedGuests', 'staffing', 'gap', 'confidence',
];
const EXPECTED_TRANSMISSION_LABELS = [
  '今日经营汇总',
  '预订 / POS / 客流',
  '预测 FactBook',
  '大模型解释',
];

module.exports = {
  id: 'restaurant-live-command-readonly',
  path: ROUTES.dashboard,
  expectedTransmissionLabels: EXPECTED_TRANSMISSION_LABELS,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'restaurant-live-command-readonly',
    path: ROUTES.dashboard,
    landmarks: ['餐饮 AI 实时经营指挥屏', '数据传输链路', '明日预测客流', '连锁预订事件流'],
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
      const streamCount = await page.locator('[data-testid="restaurant-live-stream"]').count();
      const transmissionLabels = (await command.locator('.transmission-rail li strong').allInnerTexts())
        .map((label) => label.trim());
      const transmissionLabel = await command.locator('.live-command__eyebrow').innerText().catch(() => '');
      const loadError = await page.locator('.load-alert.el-alert--error').innerText().catch(() => '');
      const assessment = finalPath === ROUTES.dashboard
        && commandCount === 1
        && streamCount === 1
        && transmissionLabels.length === EXPECTED_TRANSMISSION_LABELS.length
        && transmissionLabels.every((label, index) => label === EXPECTED_TRANSMISSION_LABELS[index])
        && populatedMetricCount === STAFFING_METRIC_KEYS.length
        && !loadError
        ? { result: 'PASS', rootCauseClass: 'none' }
        : { result: 'CONFIRMED_DEFECT', rootCauseClass: loadError ? 'backend' : 'frontend' };
      return {
        finalPath,
        commandCount,
        streamCount,
        transmissionNodeCount: transmissionLabels.length,
        transmissionLabels,
        transmissionLabel,
        staffingMetricValues: metricValues,
        populatedMetricCount,
        loadError: loadError || null,
        assessment,
      };
    },
  }),
};
