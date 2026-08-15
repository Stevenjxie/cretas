import { LabelQcPhoto } from '../../types/labelQc';
import {
  addMobileObjectLabel,
  buildObjectReviewPayload,
  confirmObjectTray,
  hydrateObjectReviewDraft,
  setObjectPresence,
  updateMobileObjectLabelBox,
} from './labelQcObjectReviewModel';

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

describe('labelQcObjectReviewModel', () => {
  it('hydrates all AI boxes and requires an explicit per-tray confirmation', () => {
    const draft = hydrateObjectReviewDraft(photo(JSON.stringify({
      trays: [{
        index: 0,
        bbox: [0.1, 0.1, 0.9, 0.9],
        labels: [{ type: 'white', bbox: [0.2, 0.2, 0.4, 0.4] }],
      }],
    })));

    expect(draft.trays[0]).toMatchObject({
      key: 'tray-0',
      confirmed: false,
      whitePresence: 'PRESENT',
      colorPresence: 'UNJUDGEABLE',
    });
    expect(() => buildObjectReviewPayload(draft)).toThrow('尚未确认');
  });

  it('turns an AI false positive into an explicit rejected key', () => {
    let draft = hydrateObjectReviewDraft(photo(JSON.stringify({
      trays: [{
        index: 0,
        bbox: [0, 0, 1, 1],
        labels: [{ type: 'color', bbox: [0.2, 0.2, 0.4, 0.4] }],
      }],
    })));
    draft = setObjectPresence(draft, 'tray-0', 'COLOR_LABEL', 'MISSING');
    draft = setObjectPresence(draft, 'tray-0', 'WHITE_LABEL', 'MISSING');
    draft = confirmObjectTray(draft, 'tray-0');

    expect(draft.trays[0]?.rejectedAiObjectKeys).toEqual(['label-0-0']);
    expect(buildObjectReviewPayload(draft).trays[0]?.colorPresence).toBe('MISSING');
  });

  it('adds a visible human label inside its owning tray before confirmation', () => {
    let draft = hydrateObjectReviewDraft(photo(JSON.stringify({
      trays: [{ index: 0, bbox: [0.1, 0.1, 0.9, 0.9], labels: [] }],
    })));
    draft = addMobileObjectLabel(draft, 'tray-0', 'WHITE_LABEL', 'human-white-1');
    draft = setObjectPresence(draft, 'tray-0', 'COLOR_LABEL', 'MISSING');
    draft = confirmObjectTray(draft, 'tray-0');

    expect(buildObjectReviewPayload(draft).trays[0]?.labels[0]).toMatchObject({
      type: 'WHITE_LABEL',
      decision: 'ADDED',
    });
  });

  it('keeps mobile drag edits as corrected human truth and blocks a label moved outside its tray', () => {
    let draft = hydrateObjectReviewDraft(photo(JSON.stringify({
      trays: [{
        index: 0,
        bbox: [0.1, 0.1, 0.5, 0.5],
        labels: [{ type: 'white', bbox: [0.2, 0.2, 0.3, 0.3] }],
      }],
    })));
    draft = setObjectPresence(draft, 'tray-0', 'COLOR_LABEL', 'MISSING');
    draft = updateMobileObjectLabelBox(
      draft,
      'tray-0',
      'label-0-0',
      { xMin: 0.25, yMin: 0.25, xMax: 0.4, yMax: 0.4 },
    );
    expect(draft.trays[0]?.labels[0]?.decision).toBe('CORRECTED');

    draft = updateMobileObjectLabelBox(
      draft,
      'tray-0',
      'label-0-0',
      { xMin: 0.7, yMin: 0.7, xMax: 0.8, yMax: 0.8 },
    );
    draft = confirmObjectTray(draft, 'tray-0');
    expect(draft.trays[0]?.confirmed).toBe(false);
  });
});
