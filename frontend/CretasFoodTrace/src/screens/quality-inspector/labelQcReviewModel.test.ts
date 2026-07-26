import {
  addHumanAnnotation,
  buildLabelQcReviewRequest,
  createCenteredBoundingBox,
  getLabelQcReviewConflictMessage,
  hydrateLabelQcReviewDrafts,
  markPhotoReviewed,
  nextIncompletePhotoIndex,
  resizeBoundingBox,
  translateBoundingBox,
  updateDraftAnnotation,
} from './labelQcReviewModel';
import { LabelQcTaskDetail } from '../../types/labelQc';

const detail: LabelQcTaskDetail = {
  task: {
    id: 'task-1',
    productTypeId: 'sku-1',
    skuCode: 'PTSYCS0097',
    skuName: '澳洲 M5 和牛牛肉卷',
    batchNumber: 'QC-0726-A03',
    productionDate: '2026-07-26',
    createdBy: 7,
    status: 'NEEDS_REVIEW',
    version: 3,
    photoCount: 4,
    aiCandidateCount: 3,
    finalDefectCount: 0,
    archived: false,
    trainingStatus: 'PENDING',
    createdAt: '2026-07-26T09:41:00',
    updatedAt: '2026-07-26T09:42:00',
  },
  photos: [
    {
      id: 'photo-1',
      attachmentId: 'att-1',
      orderIndex: 0,
      imageWidth: 1152,
      imageHeight: 2048,
      status: 'ANALYZED',
      annotations: [
        {
          id: 'ai-1',
          source: 'AI',
          aiLabel: 'MISSING_WHITE_LABEL',
          bbox: { xMin: 0.04, yMin: 0.63, xMax: 0.25, yMax: 0.78 },
        },
      ],
    },
    {
      id: 'photo-2',
      attachmentId: 'att-2',
      orderIndex: 1,
      imageWidth: 1152,
      imageHeight: 2048,
      status: 'ANALYZED',
      annotations: [],
    },
    {
      id: 'photo-3',
      attachmentId: 'att-3',
      orderIndex: 2,
      imageWidth: 1152,
      imageHeight: 2048,
      status: 'ANALYZED',
      annotations: [
        {
          id: 'ai-3',
          source: 'AI',
          aiLabel: 'MISSING_COLOR_LABEL',
          bbox: { xMin: 0.02, yMin: 0.57, xMax: 0.25, yMax: 0.7 },
        },
      ],
    },
    {
      id: 'photo-4',
      attachmentId: 'att-4',
      orderIndex: 3,
      imageWidth: 1152,
      imageHeight: 2048,
      status: 'ANALYZED',
      annotations: [],
    },
  ],
};

describe('labelQcReviewModel', () => {
  it('将四张样例形成 AI 确认、正常图和人工漏检补框的完整训练真值', () => {
    const drafts = hydrateLabelQcReviewDrafts(detail);

    drafts[0] = markPhotoReviewed(
      updateDraftAnnotation(drafts[0]!, 'ai-1', {
        label: 'MISSING_WHITE_LABEL',
      }),
    );
    drafts[1] = markPhotoReviewed(drafts[1]!);
    drafts[2] = markPhotoReviewed(
      updateDraftAnnotation(drafts[2]!, 'ai-3', {
        label: 'MISSING_COLOR_LABEL',
      }),
    );
    drafts[3] = addHumanAnnotation(drafts[3]!, 0.82, 0.88, 'human-4');
    drafts[3] = markPhotoReviewed(
      updateDraftAnnotation(drafts[3]!, 'human-4', {
        label: 'MISSING_WHITE_LABEL',
      }),
    );

    const request = buildLabelQcReviewRequest(
      drafts,
      detail.task.version,
      'review-device-a',
    );

    expect(request.expectedVersion).toBe(3);
    expect(request.reviewRequestId).toBe('review-device-a');
    expect(request.photos).toEqual([
      {
        photoId: 'photo-1',
        annotations: [
          expect.objectContaining({
            annotationId: 'ai-1',
            label: 'MISSING_WHITE_LABEL',
          }),
        ],
      },
      {
        photoId: 'photo-2',
        annotations: [
          expect.objectContaining({
            label: 'NO_DEFECT',
          }),
        ],
      },
      {
        photoId: 'photo-3',
        annotations: [
          expect.objectContaining({
            annotationId: 'ai-3',
            label: 'MISSING_COLOR_LABEL',
          }),
        ],
      },
      {
        photoId: 'photo-4',
        annotations: [
          expect.objectContaining({
            annotationId: undefined,
            label: 'MISSING_WHITE_LABEL',
          }),
        ],
      },
    ]);
  });

  it('拒绝 AI 疑点时仍保留 annotationId 并提交 NO_DEFECT', () => {
    const drafts = hydrateLabelQcReviewDrafts(detail).slice(0, 1);
    drafts[0] = markPhotoReviewed(
      updateDraftAnnotation(drafts[0]!, 'ai-1', { label: 'NO_DEFECT' }),
    );

    expect(
      buildLabelQcReviewRequest(drafts, 3, 'review-device-a')
        .photos[0]!.annotations[0],
    ).toEqual(
      expect.objectContaining({
        annotationId: 'ai-1',
        label: 'NO_DEFECT',
        bbox: undefined,
      }),
    );
  });

  it('未做整图结论或还有未确认框时禁止提交，并定位下一张未完成照片', () => {
    const drafts = hydrateLabelQcReviewDrafts(detail);
    expect(() => buildLabelQcReviewRequest(drafts, 3, 'review-device-a')).toThrow(
      '仍有照片未完成最终确认',
    );
    expect(nextIncompletePhotoIndex(drafts, 3)).toBe(0);
  });

  it('人工框始终被限制在照片范围内并保留最小尺寸', () => {
    const centered = createCenteredBoundingBox(0.99, 0.99);
    expect(centered.xMax).toBeLessThanOrEqual(1);
    expect(centered.yMax).toBeLessThanOrEqual(1);

    const moved = translateBoundingBox(centered, -2, -2);
    expect(moved.xMin).toBe(0);
    expect(moved.yMin).toBe(0);

    const resized = resizeBoundingBox(moved, -2, -2);
    expect(resized.xMax - resized.xMin).toBeGreaterThanOrEqual(0.04);
    expect(resized.yMax - resized.yMin).toBeGreaterThanOrEqual(0.04);
  });

  it('将另一台设备已提交和版本过期识别为防覆盖冲突', () => {
    expect(
      getLabelQcReviewConflictMessage({
        response: {
          data: {
            errorCode: 'LABEL_QC_ALREADY_REVIEWED',
            message: '另一台设备已完成',
          },
        },
      }),
    ).toBe('另一台设备已完成');
    expect(
      getLabelQcReviewConflictMessage({
        response: {
          data: { errorCode: 'LABEL_QC_REVIEW_STALE' },
        },
      }),
    ).toContain('没有覆盖');
    expect(
      getLabelQcReviewConflictMessage({
        response: { data: { errorCode: 'NETWORK_ERROR' } },
      }),
    ).toBeNull();
  });
});
