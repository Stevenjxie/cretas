import { describe, expect, it } from 'vitest';
import {
  DEFAULT_PRODUCTION_DOCUMENT_CHAPTERS,
  normalizeProductionDocumentChapters,
  productionDocumentPackRequest,
} from '../productionDocumentPack';

describe('production document pack contract', () => {
  it('defaults to all three business-document chapters in a stable order', () => {
    expect(DEFAULT_PRODUCTION_DOCUMENT_CHAPTERS).toEqual([
      'work-order',
      'material-requisition',
      'batching-sheet',
    ]);
  });

  it('deduplicates and orders chapter selection for one PDF request', () => {
    expect(normalizeProductionDocumentChapters([
      'batching-sheet',
      'work-order',
      'work-order',
      'unknown',
    ])).toEqual(['work-order', 'batching-sheet']);

    expect(productionDocumentPackRequest('F006', 'plan-1', [
      'batching-sheet',
      'work-order',
    ])).toEqual({
      url: '/F006/print/production-document-pack/plan-1',
      params: { chapters: 'work-order,batching-sheet' },
    });
  });

  it('fails closed when no chapter is selected', () => {
    expect(() => productionDocumentPackRequest('F006', 'plan-1', []))
      .toThrow('请至少选择一章生产单据');
  });
});
