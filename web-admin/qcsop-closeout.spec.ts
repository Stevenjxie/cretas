import { expect, test, type Page } from '@playwright/test';

const sopUrl = '/qc-label-inspection-sop.html';
const webReviewScreenshots = [
  '22-web-review-entry.png',
  '23-web-review-ai-candidate.png',
  '24-web-review-human-frame.png',
  '25-web-review-ready-submit.png',
];

async function expectAllImagesLoaded(page: Page) {
  const imageSources = await page.locator('img[src]').evaluateAll((images) =>
    images.map((image) => (image as HTMLImageElement).getAttribute('src')).filter(Boolean) as string[],
  );
  const brokenImages: string[] = [];
  for (const source of imageSources) {
    const response = await page.request.get(new URL(source, page.url()).toString());
    if (!response.ok()) brokenImages.push(`${source}: ${response.status()}`);
  }
  expect(brokenImages).toEqual([]);
}

test('桌面版 QCSOP 展示真实 Web 人工审核入口、动作和整单提交', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(sopUrl, { waitUntil: 'networkidle' });

  await expect(page.getByRole('heading', { name: '质量主管也可在 Web 完成同一套人工审核' })).toBeVisible();
  await expect(page.getByText('质量管理 → 包装标签拍检 → 待人工审核')).toBeVisible();

  for (const screenshot of webReviewScreenshots) {
    const image = page.locator(`img[src="qcsop-assets/${screenshot}"]`);
    await expect(image).toHaveCount(1);
  }

  await expectAllImagesLoaded(page);

  const firstWebReviewImage = page.locator('img[src="qcsop-assets/22-web-review-entry.png"]');
  await firstWebReviewImage.scrollIntoViewIfNeeded();
  await firstWebReviewImage.click();
  await expect(page.locator('#lightbox')).toHaveAttribute('open', '');
  await expect(page.locator('#lightbox img')).toHaveAttribute('src', 'qcsop-assets/22-web-review-entry.png');
  await page.getByRole('button', { name: '关闭截图预览' }).click();
  await expect(page.locator('#lightbox')).not.toHaveAttribute('open', '');
});

test('手机宽度无水平溢出且 Web 审核目录可直达', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(sopUrl, { waitUntil: 'networkidle' });

  const viewportMetrics = await page.evaluate(() => ({
    innerWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth,
    bodyWidth: document.body.scrollWidth,
  }));
  expect(viewportMetrics.documentWidth).toBeLessThanOrEqual(viewportMetrics.innerWidth);
  expect(viewportMetrics.bodyWidth).toBeLessThanOrEqual(viewportMetrics.innerWidth);

  const webReviewLink = page.getByRole('link', { name: 'Web 人工审核' });
  await expect(webReviewLink).toHaveAttribute('href', '#step-web-review');
  await webReviewLink.click();
  await expect(page).toHaveURL(/#step-web-review$/);
  await page.locator('#step-web-review').scrollIntoViewIfNeeded();
  await expect(page.locator('#step-web-review')).toBeInViewport();
  await expectAllImagesLoaded(page);
});
