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
  restoreRejectedAiCandidate,
  strokeBounds,
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
      version: 0,
      photoCount: 2,
      aiCandidateCount: withCandidate ? 1 : 0,
      finalDefectCount: 0,
      archived: false,
      trainingStatus: 'PENDING',
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

  it('restores a rejected AI candidate for another explicit decision', () => {
    const drafts = buildReviewDraft(buildDetail(true));
    const item = drafts[0]!.items[0]!;
    item.label = 'NO_DEFECT';
    item.notes = '人工复核：AI 疑点不成立';
    drafts[0]!.reviewed = true;

    const restored = restoreRejectedAiCandidate(drafts[0]!, item.key);

    expect(restored).toBe(item);
    expect(item.label).toBeUndefined();
    expect(item.notes).toBe('');
    expect(drafts[0]!.reviewed).toBe(false);
    expect(validateReviewDraft(drafts)).toBe('第 1 张照片还有 1 个框未确认');
  });

  it('refuses to mark a photo normal while a human box still exists', () => {
    const drafts = buildReviewDraft(buildDetail());
    appendHumanBox(
      drafts[0]!,
      { xMin: 0.1, yMin: 0.2, xMax: 0.3, yMax: 0.4 },
      'human-guard',
    );

    expect(() => markPhotoNormal(drafts[0]!)).toThrow(
      '还有 1 个人工补框，请先逐个删除或确认问题',
    );
    expect(drafts[0]!.items).toHaveLength(1);
    expect(drafts[0]!.reviewed).toBe(false);
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

describe('strokeBounds', () => {
  it('把笔刷半径算进外接矩形', () => {
    // 质检员涂的是"这块区域", 不是"这条中心线" —— 不加半径框会比涂过的范围小一圈
    expect(strokeBounds([{ x: 50, y: 50 }], 10)).toEqual({
      x0: 40, y0: 40, x1: 60, y1: 60,
    });
  });

  it('多点笔迹取整体包围盒', () => {
    expect(strokeBounds([{ x: 30, y: 80 }, { x: 90, y: 20 }, { x: 60, y: 50 }], 5)).toEqual({
      x0: 25, y0: 15, x1: 95, y1: 85,
    });
  });

  it('空笔迹或非法半径返回 null, 不落成一个零面积的框', () => {
    expect(strokeBounds([], 10)).toBeNull();
    expect(strokeBounds([{ x: 1, y: 1 }], 0)).toBeNull();
  });

  it('跳过非有限坐标, 不让一个 NaN 把整个框拉成 NaN', () => {
    expect(strokeBounds([{ x: 10, y: 10 }, { x: NaN, y: 40 }, { x: 20, y: 20 }], 2)).toEqual({
      x0: 8, y0: 8, x1: 22, y1: 22,
    });
  });
});
