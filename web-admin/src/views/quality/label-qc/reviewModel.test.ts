import { describe, expect, it } from 'vitest';
import type { LabelQcTaskDetail } from '@/api/labelQc';
import {
  appendHumanBox,
  buildReviewDraft,
  completedPhotoCount,
  firstIncompletePhotoIndex,
  markPhotoNormal,
  markPhotoReviewed,
  moveBox,
  normalizedBox,
  pointBox,
  resizeBox,
  toReviewRequest,
  validateReviewDraft,
} from './reviewModel';

function buildDetail(withCandidate = false): LabelQcTaskDetail {
  return {
    task: {
      id: 'task-1',
      productTypeId: 'sku-1',
      skuCode: 'SKU-001',
      skuName: '腌制牛肉片',
      batchNumber: 'B-001',
      productionDate: '2026-07-24',
      createdBy: 1,
      status: 'NEEDS_REVIEW',
      photoCount: 2,
      aiCandidateCount: withCandidate ? 1 : 0,
      finalDefectCount: 0,
      createdAt: '2026-07-24T10:00:00',
      updatedAt: '2026-07-24T10:00:00',
    },
    photos: ['photo-1', 'photo-2'].map((id, index) => ({
      id,
      attachmentId: `attachment-${index + 1}`,
      orderIndex: index,
      imageWidth: 1000,
      imageHeight: 2000,
      status: 'ANALYZED',
      imageUrl: `https://example.test/${id}.jpg`,
      annotations: withCandidate && index === 0 ? [{
        id: 'ai-1',
        source: 'AI',
        aiLabel: 'MISSING_WHITE_LABEL',
        aiConfidence: 0.82,
        bbox: { xMin: 0.1, yMin: 0.2, xMax: 0.3, yMax: 0.4 },
      }] : [],
    })),
  };
}

describe('label QC review model', () => {
  it('does not auto-accept AI candidates or auto-complete photos', () => {
    const drafts = buildReviewDraft(buildDetail(true));
    expect(drafts[0]?.items[0]?.label).toBeUndefined();
    expect(drafts[0]?.reviewed).toBe(false);
    expect(validateReviewDraft(drafts)).toBe('第 1 张照片还有 1 个框未确认');
  });

  it('persists a rejected AI frame using the original annotation id', () => {
    const drafts = buildReviewDraft(buildDetail(true));
    drafts[0]!.items[0]!.label = 'NO_DEFECT';
    expect(markPhotoReviewed(drafts[0]!)).toBeNull();
    markPhotoNormal(drafts[1]!);
    const request = toReviewRequest(drafts);
    expect(request.photos[0]?.annotations[0]).toMatchObject({
      annotationId: 'ai-1',
      label: 'NO_DEFECT',
    });
    expect(request.photos[1]?.annotations[0]?.label).toBe('NO_DEFECT');
  });

  it('requires an explicit whole-image conclusion after a human box is labelled', () => {
    const drafts = buildReviewDraft(buildDetail());
    const human = appendHumanBox(
      drafts[0]!,
      { xMin: 0.1, yMin: 0.2, xMax: 0.3, yMax: 0.4 },
      'human-1',
    );
    human.label = 'MISSING_COLOR_LABEL';
    expect(validateReviewDraft(drafts)).toBe('第 1 张照片尚未给出整图结论');
    expect(markPhotoReviewed(drafts[0]!)).toBeNull();
    markPhotoNormal(drafts[1]!);
    expect(validateReviewDraft(drafts)).toBeNull();
  });

  it('loops to the next incomplete photo and reports progress', () => {
    const drafts = buildReviewDraft(buildDetail());
    markPhotoNormal(drafts[1]!);
    expect(completedPhotoCount(drafts)).toBe(1);
    expect(firstIncompletePhotoIndex(drafts, 1)).toBe(0);
    markPhotoNormal(drafts[0]!);
    expect(firstIncompletePhotoIndex(drafts, 0)).toBe(-1);
  });

  it('creates, moves and resizes a normalized click box safely', () => {
    expect(pointBox(0.05, 0.05)).toEqual({
      xMin: 0,
      yMin: 0,
      xMax: 0.22,
      yMax: 0.12,
    });
    expect(moveBox(
      { xMin: 0.8, yMin: 0.8, xMax: 1, yMax: 1 },
      0.5,
      0.5,
    )).toEqual({ xMin: 0.8, yMin: 0.8, xMax: 1, yMax: 1 });
    expect(resizeBox(
      { xMin: 0.1, yMin: 0.1, xMax: 0.3, yMax: 0.3 },
      -0.5,
      -0.5,
    )).toEqual({ xMin: 0.1, yMin: 0.1, xMax: 0.13, yMax: 0.13 });
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
