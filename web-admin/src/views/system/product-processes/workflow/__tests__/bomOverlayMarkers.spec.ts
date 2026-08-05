import { describe, expect, it } from 'vitest';
import { markersForAuxiliaryRow, markersForPackagingRow } from '../bomOverlayMarkers';

const glyphs = (markers: { glyph: string }[]) => markers.map((m) => m.glyph);

describe('辅料行标记', () => {
  it('全默认状态不产生任何标记', () => {
    expect(markersForAuxiliaryRow({
      subsequentPotRatio: null, countInSeasoning: true,
      substituteCount: 0, costScope: 'SHARED',
    })).toEqual([]);
  });

  it('按锅序标 ◷ 并在 title 带出比例', () => {
    const markers = markersForAuxiliaryRow({
      subsequentPotRatio: 0.6, countInSeasoning: true,
      substituteCount: 0, costScope: 'SHARED',
    });
    expect(glyphs(markers)).toEqual(['◷']);
    expect(markers[0].title).toContain('60');
  });

  it('不计入成本标 ⊘', () => {
    expect(glyphs(markersForAuxiliaryRow({
      subsequentPotRatio: null, countInSeasoning: false,
      substituteCount: 0, costScope: 'SHARED',
    }))).toEqual(['⊘']);
  });

  it('有替代标 ⇄ 并在 title 带出数量', () => {
    const markers = markersForAuxiliaryRow({
      subsequentPotRatio: null, countInSeasoning: true,
      substituteCount: 2, costScope: 'SHARED',
    });
    expect(glyphs(markers)).toEqual(['⇄']);
    expect(markers[0].title).toContain('2');
  });

  it('成本不共享才标 ◑ —— SHARED 与缺失都不标', () => {
    const base = { subsequentPotRatio: null, countInSeasoning: true, substituteCount: 0 };
    expect(glyphs(markersForAuxiliaryRow({ ...base, costScope: 'SHARED' }))).toEqual([]);
    expect(glyphs(markersForAuxiliaryRow({ ...base, costScope: null }))).toEqual([]);
    expect(glyphs(markersForAuxiliaryRow({ ...base, costScope: 'OUTPUT_EXCLUSIVE' }))).toEqual(['◑']);
  });

  it('多个条件同时成立时全部标出, 顺序稳定', () => {
    expect(glyphs(markersForAuxiliaryRow({
      subsequentPotRatio: 0.6, countInSeasoning: true,
      substituteCount: 1, costScope: 'OUTPUT_GROUP',
    }))).toEqual(['◷', '⇄', '◑']);
  });
});

describe('包材行标记', () => {
  it('全默认不标', () => {
    expect(markersForPackagingRow({
      substituteCount: 0, isOptional: false, perPortion: false, packagingSpecId: null,
    })).toEqual([]);
  });

  it('可选 / 按份 / 层级各自标出', () => {
    expect(glyphs(markersForPackagingRow({
      substituteCount: 0, isOptional: true, perPortion: false, packagingSpecId: null,
    }))).toEqual(['○']);
    expect(glyphs(markersForPackagingRow({
      substituteCount: 0, isOptional: false, perPortion: true, packagingSpecId: null,
    }))).toEqual(['⊞']);
    expect(glyphs(markersForPackagingRow({
      substituteCount: 0, isOptional: false, perPortion: false, packagingSpecId: 'spec-1',
    }))).toEqual(['▤']);
  });
});
