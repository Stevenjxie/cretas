'use strict';

function slug(value) {
  return String(value || 'evidence').replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80) || 'evidence';
}

function createScreenshotWriter(page, evidenceDir, config = {}) {
  const root = String(evidenceDir || '').replace(/\\/g, '/').replace(/\/+$/, '');
  if (!root) throw new Error('Evidence directory is required for screenshots');
  let counter = 0;
  return async function screenshot(name, options = {}) {
    const filename = `${String(++counter).padStart(2, '0')}-${slug(name)}.png`;
    const target = `${root}/${filename}`;
    const mask = [
      page.locator('input[type="password"], input[placeholder*="用户名"]'),
      ...(config.sensitiveTexts || []).map((text) => page.getByText(text, { exact: true })),
    ];
    await page.screenshot({
      path: target,
      fullPage: Boolean(options.fullPage),
      mask,
      maskColor: '#1f2937',
    });
    return target;
  };
}

module.exports = { createScreenshotWriter, slug };
