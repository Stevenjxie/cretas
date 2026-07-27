import { expect, test, type Page } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const evidenceDir = resolve(process.cwd(), '../docs/manual/qcsop-assets');
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
      imageWidth: 1152,
      imageHeight: 2048,
      status: 'ANALYZED',
      imageUrl: 'https://qc-evidence.test/photo-1.jpg',
      aiModel: 'vision-review',
      promptVersion: 'qc-label-v3',
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
      imageWidth: 1152,
      imageHeight: 2048,
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
    <svg xmlns="http://www.w3.org/2000/svg" width="1152" height="2048">
      <rect width="1152" height="2048" fill="#dce3df"/>
      <rect x="90" y="180" width="972" height="1600" rx="28" fill="#87968e"/>
      <text x="576" y="980" text-anchor="middle" font-family="sans-serif" font-size="54" fill="#ffffff">
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
  await expect(page.getByText('人工审核已完成，当前状态为待训练确认')).toBeVisible();
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
