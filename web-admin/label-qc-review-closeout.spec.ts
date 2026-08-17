import { expect, test, type Page } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const evidenceDir = resolve(
  process.env.LABEL_QC_EVIDENCE_DIR ?? resolve(process.cwd(), '../docs/manual/qcsop-assets'),
);
const evidenceImagePath = process.env.LABEL_QC_EVIDENCE_IMAGE;

const task = {
  id: 'QC-WEB-GUIDE-001',
  productTypeId: 'PTSYC0097',
  skuCode: 'PTSYC0097',
  skuName: '澳洲谷饲嫩滑牛肉片',
  batchNumber: 'QC-WEB-SAMPLE',
  productionDate: '2026-07-26',
  createdBy: 9001,
  status: 'NEEDS_REVIEW',
  version: 3,
  photoCount: 2,
  aiCandidateCount: 1,
  finalDefectCount: 0,
  archived: false,
  trainingStatus: 'PENDING',
  createdAt: '2026-07-26T09:30:00',
  updatedAt: '2026-07-26T09:32:00',
};

const detail = {
  task,
  photos: [
    {
      id: 'photo-web-1',
      attachmentId: 'attachment-web-1',
      orderIndex: 0,
      imageWidth: 3072,
      imageHeight: 4096,
      status: 'ANALYZED',
      imageUrl: 'https://qc-evidence.test/photo-1.jpg',
      aiModel: 'vision-review',
      promptVersion: 'qc-label-v3',
      screeningDetail: JSON.stringify({
        trays: [
          {
            index: 0,
            bbox: [0.05, 0.1, 0.32, 0.48],
            trayConfidence: 0.91,
            screenVerdict: 'CLEAR',
            labels: [
              { type: 'white', confidence: 0.88, bbox: [0.12, 0.28, 0.23, 0.35] },
              { type: 'white', confidence: 0.84, bbox: [0.115, 0.275, 0.235, 0.355] },
              { type: 'color', confidence: 0.79, bbox: [0.08, 0.17, 0.2, 0.24] },
            ],
          },
          {
            index: 1,
            bbox: [0.36, 0.1, 0.63, 0.48],
            trayConfidence: 0.9,
            screenVerdict: 'CLEAR',
            labels: [
              { type: 'white', confidence: 0.87, bbox: [0.43, 0.285, 0.54, 0.355] },
              { type: 'color', confidence: 0.78, bbox: [0.39, 0.175, 0.51, 0.245] },
            ],
          },
          {
            index: 2,
            bbox: [0.67, 0.1, 0.94, 0.48],
            trayConfidence: 0.89,
            screenVerdict: 'CLEAR',
            labels: [
              { type: 'white', confidence: 0.86, bbox: [0.74, 0.28, 0.85, 0.35] },
              { type: 'color', confidence: 0.77, bbox: [0.7, 0.17, 0.82, 0.24] },
            ],
          },
        ],
      }),
      annotations: [{
        id: 'ai-web-1',
        source: 'AI',
        aiCandidateId: 'candidate-web-1',
        aiLabel: 'MISSING_WHITE_LABEL',
        aiConfidence: 0.82,
        aiEvidence: '左下堆叠区最上层包装疑似没有白色标签',
        bbox: { xMin: 0.08, yMin: 0.56, xMax: 0.34, yMax: 0.77 },
      }],
    },
    {
      id: 'photo-web-2',
      attachmentId: 'attachment-web-2',
      orderIndex: 1,
      imageWidth: 3072,
      imageHeight: 4096,
      status: 'ANALYZED',
      imageUrl: 'https://qc-evidence.test/photo-2.jpg',
      aiModel: 'vision-review',
      promptVersion: 'qc-label-v3',
      annotations: [],
    },
  ],
};

function apiResponse(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data, message: '操作成功' }),
  };
}

async function installMockSession(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('cretas_access_token', 'label-qc-e2e-token');
    localStorage.setItem('cretas_user', JSON.stringify({
      id: 9001,
      username: 'qc_web_guide',
      email: '',
      isActive: true,
      createdAt: '2026-07-26T09:00:00',
      updatedAt: '2026-07-26T09:00:00',
      userType: 'factory',
      factoryUser: {
        role: 'quality_manager',
        factoryId: 'F006',
        factoryName: '六膳门示例工厂',
        factoryType: 'FACTORY',
        businessDomain: 'FACTORY',
        permissions: ['quality:read', 'quality:read_write'],
      },
    }));
  });
}

async function installPhotoRoute(page: Page) {
  if (evidenceImagePath && existsSync(evidenceImagePath)) {
    const image = readFileSync(evidenceImagePath);
    await page.route('https://qc-evidence.test/photo-*.jpg', async (route) => {
      await route.fulfill({ status: 200, contentType: 'image/jpeg', body: image });
    });
    return;
  }
  const fallbackSvg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="3072" height="4096">
      <rect width="3072" height="4096" fill="#dce3df"/>
      <rect x="240" y="360" width="2592" height="3200" rx="72" fill="#87968e"/>
      <text x="1536" y="1960" text-anchor="middle" font-family="sans-serif" font-size="108" fill="#ffffff">
        包装标签拍检示例照片
      </text>
    </svg>`;
  await page.route('https://qc-evidence.test/photo-*.jpg', async (route) => {
    await route.fulfill({ status: 200, contentType: 'image/svg+xml', body: fallbackSvg });
  });
}

test('Web 人工审核入口、逐图标注和提交回读形成闭环', async ({ page }) => {
  let submittedPayload: Record<string, unknown> | null = null;
  let reviewed = false;
  const unexpectedApiRequests: string[] = [];

  await installMockSession(page);
  await installPhotoRoute(page);

  await page.route('**/api/admin/role-permissions', async (route) => {
    await route.fulfill(apiResponse([
      {
        id: 1,
        roleCode: 'quality_manager',
        moduleCode: 'dashboard',
        permissionLevel: 'r',
      },
      {
        id: 2,
        roleCode: 'quality_manager',
        moduleCode: 'quality',
        permissionLevel: 'rw',
      },
    ]));
  });

  await page.route('**/api/mobile/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;

    if (path === '/api/mobile/F006/canvas/role-module-override' && request.method() === 'GET') {
      await route.fulfill(apiResponse({}));
      return;
    }

    if (path === '/api/mobile/F006/canvas/user-module-access/9001' && request.method() === 'GET') {
      await route.fulfill(apiResponse([]));
      return;
    }

    if (path === '/api/mobile/F006/config/disabled-modules' && request.method() === 'GET') {
      await route.fulfill(apiResponse([]));
      return;
    }

    if (path === '/api/mobile/F006/notifications/unread-count' && request.method() === 'GET') {
      await route.fulfill(apiResponse({ unreadCount: 0 }));
      return;
    }

    if (path === '/api/mobile/F006/sales/orders' && request.method() === 'GET') {
      await route.fulfill(apiResponse({
        content: [], page: 1, currentPage: 1, size: 20,
        totalElements: 0, totalPages: 0, first: true, last: true,
      }));
      return;
    }

    if (
      request.method() === 'GET'
      && (
        path === '/api/mobile/F006/warehouse/receiving/tasks'
        || path === '/api/mobile/F006/operations/customer-material-arrivals'
      )
    ) {
      await route.fulfill(apiResponse([]));
      return;
    }

    if (path === '/api/mobile/F006/label-qc/tasks/status-counts' && request.method() === 'GET') {
      await route.fulfill(apiResponse({
        counts: reviewed
          ? { NEEDS_REVIEW: 0, REVIEWED: 1 }
          : { NEEDS_REVIEW: 1, REVIEWED: 0 },
      }));
      return;
    }

    if (path === '/api/mobile/F006/label-qc/tasks' && request.method() === 'GET') {
      const content = reviewed ? [] : [task];
      await route.fulfill(apiResponse({
        content,
        page: 1,
        currentPage: 1,
        size: 20,
        totalElements: content.length,
        totalPages: content.length ? 1 : 0,
        first: true,
        last: true,
      }));
      return;
    }

    if (path === `/api/mobile/F006/label-qc/tasks/${task.id}` && request.method() === 'GET') {
      await route.fulfill(apiResponse(detail));
      return;
    }

    if (path === `/api/mobile/F006/label-qc/tasks/${task.id}/review` && request.method() === 'PUT') {
      submittedPayload = request.postDataJSON() as Record<string, unknown>;
      reviewed = true;
      await route.fulfill(apiResponse({
        ...detail,
        task: {
          ...task,
          status: 'REVIEWED',
          version: 4,
          reviewedBy: 9001,
          reviewedAt: '2026-07-26T10:00:00',
        },
      }));
      return;
    }

    unexpectedApiRequests.push(`${request.method()} ${path}`);
    await route.fulfill(apiResponse({}));
  });

  await page.goto('/quality/label-qc', { waitUntil: 'networkidle' });

  await expect(page.getByRole('menuitem', { name: '包装标签拍检' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '包装标签拍检' })).toBeVisible();
  await expect(page.getByText('澳洲谷饲嫩滑牛肉片')).toBeVisible();
  await page.screenshot({
    path: resolve(evidenceDir, '22-web-review-entry.png'),
    fullPage: true,
  });

  await page.getByRole('button', { name: '人工审核' }).click();
  await expect(page.getByText('逐张人工审核')).toBeVisible();
  await expect(page.getByRole('button', { name: '确认：缺白标' })).toBeVisible();
  await expect(page.getByRole('button', { name: '拒绝并移除框' })).toBeVisible();
  const mainImage = page.getByAltText('待审核包装标签照片');
  await expect(mainImage).toBeVisible();
  const drawerBounds = await page.locator('.review-drawer').boundingBox();
  expect(drawerBounds?.width).toBeGreaterThan(1290);
  await expect(page.locator('.reference-box:visible')).toHaveCount(0);
  await expect(page.locator('.object-tray-box')).toHaveCount(3);
  await expect(page.locator('.object-label-box')).toHaveCount(6);
  await expect(page.locator('.reference-tag, .object-final-tag, .box-label')).toHaveCount(0);
  await expect(page.locator('.guide-white')).toHaveCount(1);
  await expect(page.locator('.guide-color')).toHaveCount(1);
  const trayLayerToggle = page.getByRole('button', { name: '隐藏盒子' });
  await trayLayerToggle.click();
  await expect(page.locator('.object-tray-box:visible')).toHaveCount(0);
  await page.getByRole('button', { name: '显示盒子' }).click();
  await expect(page.locator('.object-tray-box').first()).toBeVisible();
  await expect(page.getByText('正在加载照片…')).toBeHidden();
  await expect.poll(() => mainImage.evaluate((image) => ({
    complete: (image as HTMLImageElement).complete,
    naturalWidth: (image as HTMLImageElement).naturalWidth,
    naturalHeight: (image as HTMLImageElement).naturalHeight,
  }))).toMatchObject({ complete: true, naturalWidth: 3072, naturalHeight: 4096 });

  const viewportBounds = await page.locator('.image-viewport').boundingBox();
  const imageBounds = await mainImage.boundingBox();
  expect(viewportBounds).not.toBeNull();
  expect(imageBounds).not.toBeNull();
  expect(imageBounds!.width).toBeGreaterThan(0);
  expect(imageBounds!.height).toBeGreaterThan(0);
  expect(imageBounds!.width).toBeLessThanOrEqual(viewportBounds!.width + 1);
  expect(imageBounds!.height).toBeLessThanOrEqual(viewportBounds!.height + 1);
  await expect(page.locator('.image-plane')).toHaveCSS('transform', 'none');

  const zoomPoint = {
    x: imageBounds!.x + imageBounds!.width * 0.8,
    y: imageBounds!.y + imageBounds!.height * 0.8,
  };
  const beforeWheel = await page.locator('.image-plane').boundingBox();
  await page.mouse.move(zoomPoint.x, zoomPoint.y);
  await page.mouse.wheel(0, -600);
  const afterWheel = await page.locator('.image-plane').boundingBox();
  expect(beforeWheel).not.toBeNull();
  expect(afterWheel).not.toBeNull();
  const normalizedBefore = {
    x: (zoomPoint.x - beforeWheel!.x) / beforeWheel!.width,
    y: (zoomPoint.y - beforeWheel!.y) / beforeWheel!.height,
  };
  const normalizedAfter = {
    x: (zoomPoint.x - afterWheel!.x) / afterWheel!.width,
    y: (zoomPoint.y - afterWheel!.y) / afterWheel!.height,
  };
  expect(normalizedAfter.x).toBeCloseTo(normalizedBefore.x, 4);
  expect(normalizedAfter.y).toBeCloseTo(normalizedBefore.y, 4);
  await page.getByRole('button', { name: '复位' }).click();

  const whitePresenceRow = page.locator('.presence-editor > div').first();
  await whitePresenceRow.getByRole('button', { name: '实物缺标' }).click();
  await expect(page.locator('.object-label-box')).toHaveCount(5);
  await expect(whitePresenceRow.getByRole('button', { name: '实物缺标' })).toHaveClass(/on/);
  await whitePresenceRow.getByRole('button', { name: '+ 补白标框' }).click();
  await expect(page.locator('.object-label-box')).toHaveCount(6);
  await expect(whitePresenceRow.getByRole('button', { name: '有标签' })).toHaveClass(/on/);

  await mainImage.dispatchEvent('error');
  await expect(page.locator('.image-load-error')).toContainText('主图没有加载出来');
  await expect(page.getByRole('link', { name: '新窗口打开原图' })).toHaveAttribute(
    'href',
    'https://qc-evidence.test/photo-1.jpg',
  );
  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByText('正在加载照片…')).toBeHidden();
  await expect(mainImage).toHaveClass(/loaded/);

  await page.getByRole('button', { name: '放大照片' }).click();
  await expect(page.locator('.image-plane')).not.toHaveCSS('transform', 'none');
  await page.getByRole('button', { name: '复位' }).click();
  await expect(page.locator('.image-plane')).toHaveCSS('transform', 'none');
  await page.getByRole('button', { name: '确认：缺白标' }).scrollIntoViewIfNeeded();
  await page.screenshot({
    path: resolve(evidenceDir, '23-web-review-ai-candidate.png'),
    fullPage: true,
  });

  await page.getByRole('button', { name: '确认：缺白标' }).click();
  const viewport = page.locator('.image-viewport');
  await viewport.click({ position: { x: 520, y: 410 } });
  await expect(page.getByText('人工补充框')).toBeVisible();
  await page.getByRole('button', { name: '缺彩标', exact: true }).click();
  await page.screenshot({
    path: resolve(evidenceDir, '24-web-review-human-frame.png'),
    fullPage: true,
  });

  await page.getByRole('button', { name: '确认本图结论' }).click();
  await page.getByRole('button', { name: /下一张/ }).click();
  await page.getByRole('button', { name: '整图正常 · 本图没有其他问题' }).click();
  await expect(page.getByRole('button', { name: '提交整单人工审核' })).toBeVisible();
  await page.screenshot({
    path: resolve(evidenceDir, '25-web-review-ready-submit.png'),
    fullPage: true,
  });

  await page.getByRole('button', { name: '提交整单人工审核' }).click();
  await expect(page.getByText('确认完成整单审核')).toBeVisible();
  await page.getByRole('button', { name: '确认提交' }).click();
  await expect(page.getByText('当前筛选下没有待处理任务')).toBeVisible();

  expect(submittedPayload).not.toBeNull();
  expect(submittedPayload).toMatchObject({
    expectedVersion: 3,
    photos: [
      {
        photoId: 'photo-web-1',
        annotations: expect.arrayContaining([
          expect.objectContaining({ annotationId: 'ai-web-1', label: 'MISSING_WHITE_LABEL' }),
          expect.objectContaining({ label: 'MISSING_COLOR_LABEL' }),
        ]),
      },
      {
        photoId: 'photo-web-2',
        annotations: [expect.objectContaining({ label: 'NO_DEFECT' })],
      },
    ],
  });
  expect(typeof submittedPayload?.reviewRequestId).toBe('string');
  expect(unexpectedApiRequests).toEqual([]);
});
