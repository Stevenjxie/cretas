import { describe, expect, it } from 'vitest';
import type { LabelQcTaskDetail } from '@/api/labelQc';
import {
  appendHumanBox,
  buildReviewDraft,
  normalizedBox,
  toReviewRequest,
  validateReviewDraft,
} from './reviewModel';

const detail: LabelQcTaskDetail = {
  task: {
    id: 'task-1',
    productTypeId: 'sku-1',
    skuCode: 'SKU-001',
    skuName: '腌制牛肉片',
    batchNumber: 'B-001',
    productionDate: '2026-07-24',
    createdBy: 1,
    status: 'NEEDS_REVIEW',
    photoCount: 1,
    aiCandidateCount: 0,
    finalDefectCount: 0,
    createdAt: '2026-07-24T10:00:00',
    updatedAt: '2026-07-24T10:00:00',
  },
  photos: [{
    id: 'photo-1',
    attachmentId: 'attachment-1',
    orderIndex: 0,
    imageWidth: 1000,
    imageHeight: 2000,
    status: 'ANALYZED',
    imageUrl: 'https://example.test/photo.jpg',
    annotations: [],
  }],
};

describe('label QC review model', () => {
  it('turns a photo without candidates into an explicit human no-defect review', () => {
    const drafts = buildReviewDraft(detail);
    expect(drafts[0]?.items[0]?.label).toBe('NO_DEFECT');
    expect(validateReviewDraft(drafts)).toBeNull();
  });

  it('replaces the automatic negative when a reviewer draws a missed defect', () => {
    const drafts = buildReviewDraft(detail);
    appendHumanBox(
      drafts[0]!,
      { xMin: 0.1, yMin: 0.2, xMax: 0.3, yMax: 0.4 },
      'human-1',
    );
    expect(drafts[0]?.items).toHaveLength(1);
    expect(drafts[0]?.items[0]?.label).toBe('MISSING_WHITE_LABEL');
    expect(toReviewRequest(drafts).photos[0]?.annotations[0]?.annotationId).toBeUndefined();
  });

  it('normalizes a reverse drag and rejects accidental clicks', () => {
    expect(normalizedBox(80, 90, 20, 10, 100, 100)).toEqual({
      xMin: 0.2,
      yMin: 0.1,
      xMax: 0.8,
      yMax: 0.9,
    });
    expect(normalizedBox(10, 10, 14, 14, 100, 100)).toBeNull();
  });
});
