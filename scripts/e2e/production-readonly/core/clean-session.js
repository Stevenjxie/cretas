'use strict';

async function establishCleanSession(page, baseUrl) {
  const context = page.context();
  await context.clearCookies();
  await context.clearPermissions().catch(() => {});
  for (const worker of context.serviceWorkers()) await worker.close().catch(() => {});
  await page.goto('about:blank');
  const loginUrl = new URL('/login', baseUrl).toString();
  await page.goto(loginUrl, { waitUntil: 'domcontentloaded', timeout: 45_000 });
  await page.evaluate(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 45_000 });
  return { loginUrl, cookiesAfterClear: (await context.cookies()).length };
}

module.exports = { establishCleanSession };
