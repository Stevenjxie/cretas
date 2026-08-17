import { describe, expect, it } from 'vitest';
import { buildHorizontalGuideRows, deduplicateOverlayBoxes } from './overlayGeometry';

const box = (
  key: string,
  type: 'WHITE_LABEL' | 'COLOR_LABEL',
  values: [number, number, number, number],
) => ({
  key,
  type,
  bbox: { xMin: values[0], yMin: values[1], xMax: values[2], yMax: values[3] },
});

describe('label QC overlay geometry', () => {
  it('collapses a three-box duplicate clique and keeps the largest proposal', () => {
    const result = deduplicateOverlayBoxes([
      box('medium', 'COLOR_LABEL', [0.10, 0.10, 0.30, 0.30]),
      box('large', 'COLOR_LABEL', [0.09, 0.09, 0.31, 0.31]),
      box('small', 'COLOR_LABEL', [0.11, 0.11, 0.29, 0.29]),
    ]);

    expect(result.kept.map((item) => item.key)).toEqual(['large']);
    expect(result.rejected.map((item) => item.key)).toEqual(['medium', 'small']);
  });

  it('does not merge adjacent labels or labels of different types', () => {
    const result = deduplicateOverlayBoxes([
      box('white-left', 'WHITE_LABEL', [0.10, 0.10, 0.24, 0.20]),
      box('white-right', 'WHITE_LABEL', [0.25, 0.10, 0.39, 0.20]),
      box('color-same-place', 'COLOR_LABEL', [0.10, 0.10, 0.24, 0.20]),
    ]);

    expect(result.kept).toHaveLength(3);
    expect(result.rejected).toHaveLength(0);
  });

  it('creates only horizontal row spans and keeps label types separate', () => {
    const guides = buildHorizontalGuideRows([
      box('w1', 'WHITE_LABEL', [0.10, 0.10, 0.20, 0.20]),
      box('w2', 'WHITE_LABEL', [0.40, 0.11, 0.50, 0.21]),
      box('c1', 'COLOR_LABEL', [0.12, 0.40, 0.22, 0.50]),
      box('c2', 'COLOR_LABEL', [0.42, 0.41, 0.52, 0.51]),
    ]);

    expect(guides).toHaveLength(2);
    expect(guides.map((guide) => guide.type)).toEqual(['WHITE_LABEL', 'COLOR_LABEL']);
    expect(guides[0]).toMatchObject({ count: 2 });
    expect(guides[0]!.left).toBeCloseTo(0.15);
    expect(guides[0]!.width).toBeCloseTo(0.3);
    expect(guides[0]!.top).toBeCloseTo(0.155);
  });
});
