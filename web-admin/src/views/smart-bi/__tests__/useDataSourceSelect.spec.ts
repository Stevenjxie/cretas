/**
 * #8 (2026-06-02) — AI 问答默认数据源选择逻辑.
 *
 * Covers pickDefaultDataSource:
 *   - 评价文件最新 + POS 较旧 → 默认选 POS (非评价), 不选评价文件 (核心 bug 修复).
 *   - 只有评价文件时 → 选评价文件 (回退).
 *   - 同名存在分卷 (_part1) 与完整文件 → 选完整文件.
 *   - 全为 [自动同步] 时 → 仍能从自动同步里选.
 *   - 非自动同步存在时 → 排除 [自动同步].
 *   - 空列表 → null.
 *   - 多个非评价上传 → 完整优先, 再按新近度.
 */
import { describe, it, expect } from 'vitest';
import {
  pickDefaultDataSource,
  isReviewFile,
  isPartFile,
} from '../composables/useDataSourceSelect';
import type { UploadHistoryItem } from '@/api/smartbi';

let idSeq = 1;
function upload(partial: Partial<UploadHistoryItem>): UploadHistoryItem {
  return {
    id: idSeq++,
    fileName: 'file.xlsx',
    sheetName: 'Sheet1',
    tableType: 'GENERIC',
    rowCount: 100,
    columnCount: 5,
    status: 'COMPLETED',
    createdAt: '2026-01-01T00:00:00Z',
    ...partial,
  };
}

describe('isReviewFile', () => {
  it('detects 评价/评论/大众点评/review by name (case-insensitive)', () => {
    expect(isReviewFile({ fileName: '大众点评_评价下载_2026.xlsx' })).toBe(true);
    expect(isReviewFile({ fileName: 'Customer Review Export.csv' })).toBe(true);
    expect(isReviewFile({ fileName: '美团评价明细.xlsx' })).toBe(true);
    expect(isReviewFile({ fileName: 'POS销售流水.csv' })).toBe(false);
    expect(isReviewFile({ fileName: '收入管理报表.xlsx' })).toBe(false);
  });
});

describe('isPartFile', () => {
  it('detects part/分卷/(n) suffixes', () => {
    expect(isPartFile({ fileName: 'POS流水_part1.csv' })).toBe(true);
    expect(isPartFile({ fileName: 'POS流水_part2.csv' })).toBe(true);
    expect(isPartFile({ fileName: 'sales-part-3.xlsx' })).toBe(true);
    expect(isPartFile({ fileName: '销售数据(1).xlsx' })).toBe(true);
    expect(isPartFile({ fileName: '销售数据第2部分.xlsx' })).toBe(true);
    expect(isPartFile({ fileName: 'POS流水.csv' })).toBe(false);
  });
});

describe('pickDefaultDataSource — #8 default data-source selection', () => {
  it('prefers a NON-review upload (POS) over a newer review file', () => {
    const review = upload({
      id: 10,
      fileName: '大众点评_评价下载_2026.xlsx',
      rowCount: 12903,
      createdAt: '2026-06-02T12:00:00Z', // newest
    });
    const pos = upload({
      id: 11,
      fileName: 'POS销售流水_5月.csv',
      rowCount: 800,
      createdAt: '2026-05-01T08:00:00Z', // older
    });
    const chosen = pickDefaultDataSource([review, pos]);
    expect(chosen?.id).toBe(pos.id);
    expect(isReviewFile(chosen!)).toBe(false);
  });

  it('falls back to the review file when NO non-review upload exists', () => {
    const review1 = upload({ id: 20, fileName: '评价下载_4月.xlsx', createdAt: '2026-04-01T00:00:00Z' });
    const review2 = upload({ id: 21, fileName: '美团评价_5月.xlsx', createdAt: '2026-05-01T00:00:00Z' });
    const chosen = pickDefaultDataSource([review1, review2]);
    // only review files → pick one (newest review)
    expect(chosen).not.toBeNull();
    expect(isReviewFile(chosen!)).toBe(true);
    expect(chosen?.id).toBe(review2.id); // newest review
  });

  it('prefers the COMPLETE file over a part file of the same base name', () => {
    const part1 = upload({
      id: 30,
      fileName: '收入管理报表_part1.xlsx',
      rowCount: 500,
      createdAt: '2026-05-10T00:00:00Z', // newer
    });
    const complete = upload({
      id: 31,
      fileName: '收入管理报表.xlsx',
      rowCount: 480,
      createdAt: '2026-05-09T00:00:00Z', // slightly older but COMPLETE
    });
    const chosen = pickDefaultDataSource([part1, complete]);
    expect(chosen?.id).toBe(complete.id);
    expect(isPartFile(chosen!)).toBe(false);
  });

  it('among non-review uploads with no parts, picks the most recent', () => {
    const older = upload({ id: 40, fileName: '销售A.csv', createdAt: '2026-03-01T00:00:00Z' });
    const newer = upload({ id: 41, fileName: '销售B.csv', createdAt: '2026-05-20T00:00:00Z' });
    const chosen = pickDefaultDataSource([older, newer]);
    expect(chosen?.id).toBe(newer.id);
  });

  it('excludes [自动同步] uploads when non-auto-sync uploads exist', () => {
    const autoSync = upload({
      id: 50,
      fileName: '[自动同步] POS流水.csv',
      createdAt: '2026-06-02T00:00:00Z', // newest
    });
    const manual = upload({
      id: 51,
      fileName: '手动上传销售.csv',
      createdAt: '2026-05-01T00:00:00Z',
    });
    const chosen = pickDefaultDataSource([autoSync, manual]);
    expect(chosen?.id).toBe(manual.id);
  });

  it('still selects from [自动同步] uploads when ALL are auto-sync', () => {
    const a = upload({ id: 60, fileName: '[自动同步] 销售1.csv', createdAt: '2026-04-01T00:00:00Z' });
    const b = upload({ id: 61, fileName: '[自动同步] 销售2.csv', createdAt: '2026-05-01T00:00:00Z' });
    const chosen = pickDefaultDataSource([a, b]);
    expect(chosen?.id).toBe(b.id); // newest auto-sync
  });

  it('returns null for an empty list', () => {
    expect(pickDefaultDataSource([])).toBeNull();
    // @ts-expect-error — defensive: non-array input
    expect(pickDefaultDataSource(undefined)).toBeNull();
  });

  it('review-only-with-parts: still prefers complete review file', () => {
    const reviewPart = upload({ id: 70, fileName: '评价下载_part1.xlsx', createdAt: '2026-05-10T00:00:00Z' });
    const reviewFull = upload({ id: 71, fileName: '评价下载.xlsx', createdAt: '2026-05-09T00:00:00Z' });
    const chosen = pickDefaultDataSource([reviewPart, reviewFull]);
    // no non-review files, so review pool; complete preferred over part
    expect(chosen?.id).toBe(reviewFull.id);
  });
});
