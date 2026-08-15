import { describe, expect, it } from 'vitest';
import type { LabelQcPhoto } from '@/api/labelQc';
import {
  addObjectLabel,
  buildObjectReviewDraft,
  setTrayPresence,
  toObjectReviewPayload,
} from './objectReviewModel';

const photo = (screeningDetail: string): LabelQcPhoto => ({
  id: 'photo-1',
  attachmentId: 'attachment-1',
  orderIndex: 0,
  imageWidth: 1000,
  imageHeight: 1200,
  status: 'ANALYZED',
  screeningDetail,
  annotations: [],
});

describe('label QC object review model', () => {
  it('hydrates every AI tray and label as an unconfirmed human-review draft', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [{
        index: 0,
        bbox: [0.1, 0.1, 0.9, 0.9],
        labels: [
          { type: 'white', bbox: [0.2, 0.2, 0.4, 0.4] },
          { type: 'color', bbox: [0.5, 0.5, 0.7, 0.7] },
        ],
      }],
    })));

    expect(draft.trays).toHaveLength(1);
    expect(draft.trays[0]).toMatchObject({
      key: 'tray-0',
      confirmed: false,
      whitePresence: 'PRESENT',
      colorPresence: 'PRESENT',
    });
    expect(draft.trays[0]?.labels.map((label) => label.aiObjectKey))
      .toEqual(['label-0-0', 'label-0-1']);
  });

  it('records rejected AI labels when the inspector marks a label missing', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [{
        index: 0,
        bbox: [0, 0, 1, 1],
        labels: [{ type: 'white', bbox: [0.2, 0.2, 0.4, 0.4] }],
      }],
    })));
    const tray = draft.trays[0]!;

    setTrayPresence(tray, 'WHITE_LABEL', 'MISSING');

    expect(tray.labels).toHaveLength(0);
    expect(tray.rejectedAiObjectKeys).toEqual(['label-0-0']);
    expect(() => toObjectReviewPayload(draft)).toThrow('尚未确认');
  });

  it('exports only after each tray is explicitly confirmed and presence matches boxes', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [{ index: 0, bbox: [0.1, 0.1, 0.9, 0.9], labels: [] }],
    })));
    const tray = draft.trays[0]!;
    addObjectLabel(
      tray,
      'WHITE_LABEL',
      { xMin: 0.2, yMin: 0.2, xMax: 0.4, yMax: 0.4 },
      'human-white-1',
    );
    setTrayPresence(tray, 'COLOR_LABEL', 'MISSING');
    tray.confirmed = true;

    const payload = toObjectReviewPayload(draft);
    expect(payload.complete).toBe(true);
    expect(payload.trays[0]?.labels[0]).toMatchObject({
      type: 'WHITE_LABEL',
      decision: 'ADDED',
    });
    expect(payload.trays[0]?.labels[0]).not.toHaveProperty('aiObjectKey');
  });
});
