import { describe, expect, it } from 'vitest';
import { calculateImagePlaneStyle, resolveImageSize } from './imageViewport';

describe('label QC image viewport compatibility', () => {
  it('uses the decoded image size when backend dimensions are invalid', () => {
    expect(resolveImageSize(
      { width: 0, height: Number.NaN },
      { width: 3072, height: 4096 },
    )).toEqual({ width: 3072, height: 4096 });
  });

  it('falls back to a finite ratio when no valid dimensions exist', () => {
    expect(resolveImageSize(
      { width: Number.POSITIVE_INFINITY, height: -1 },
    )).toEqual({ width: 4, height: 3 });
  });

  it('fits a portrait camera photo inside the viewport without an idle transform', () => {
    const style = calculateImagePlaneStyle(
      { width: 3072, height: 4096 },
      { width: 800, height: 600 },
      1,
      { x: 0, y: 0 },
    );

    expect(style).toMatchObject({
      width: '450px',
      height: '600px',
      left: '175px',
      top: '0px',
    });
    expect(style).not.toHaveProperty('transform');
    expect(Object.values(style).join(' ')).not.toMatch(/NaN|Infinity/);
  });

  it('adds a transform only while the reviewer zooms or pans', () => {
    expect(calculateImagePlaneStyle(
      { width: 3072, height: 4096 },
      { width: 800, height: 600 },
      1.25,
      { x: 12, y: -8 },
    ).transform).toBe('translate(12px, -8px) scale(1.25)');
  });

  it('never produces invalid CSS from a zero-size drawer measurement', () => {
    const style = calculateImagePlaneStyle(
      { width: 0, height: 0 },
      { width: 0, height: Number.NaN },
      Number.NaN,
      { x: Number.NaN, y: Number.POSITIVE_INFINITY },
    );

    expect(Object.values(style).join(' ')).not.toMatch(/NaN|Infinity/);
    expect(style).not.toHaveProperty('transform');
  });
});
