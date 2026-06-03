/**
 * Unit tests for G7 supplier-delivery-note API client.
 *
 * Mocks `../request` (get/post/put/del) and asserts each function builds the
 * correct Java backend URL + params. URL contracts are load-bearing (a typo
 * = 404), so these guard the path strings.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../request', () => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

import { get, post, put, del } from '../request';

import {
  ocrParseNote,
  createManualNote,
  getNoteLimits,
  getNoteList,
  getNoteDetail,
  confirmNote,
  rejectNote,
  updateNoteLines,
  deleteNote,
  type SupplierDeliveryNoteLineDto,
} from '../restaurant/supplierDeliveryNote';

const F = 'F006';
const base = `/${F}/restaurant/supplier-delivery-notes`;

beforeEach(() => {
  vi.mocked(get).mockReset().mockResolvedValue({ success: true, data: {}, message: 'OK' });
  vi.mocked(post).mockReset().mockResolvedValue({ success: true, data: {}, message: 'OK' });
  vi.mocked(put).mockReset().mockResolvedValue({ success: true, data: {}, message: 'OK' });
  vi.mocked(del).mockReset().mockResolvedValue({ success: true, data: null, message: 'OK' });
});

describe('supplierDeliveryNote API client', () => {
  it('ocrParseNote posts FormData to /ocr-parse with long timeout', async () => {
    const fd = new FormData();
    await ocrParseNote(F, fd);
    expect(post).toHaveBeenCalledWith(`${base}/ocr-parse`, fd, { timeout: 600000 });
  });

  it('createManualNote posts body to /manual', async () => {
    const body = { deliveryDate: '2026-06-01', lines: [] as SupplierDeliveryNoteLineDto[] };
    await createManualNote(F, body);
    expect(post).toHaveBeenCalledWith(`${base}/manual`, body);
  });

  it('getNoteLimits gets /limits with month param', async () => {
    await getNoteLimits(F, '2026-06');
    expect(get).toHaveBeenCalledWith(`${base}/limits`, { params: { month: '2026-06' } });
  });

  it('getNoteList gets base with pagination params', async () => {
    await getNoteList(F, { status: 'DRAFT', page: 2, size: 20 });
    expect(get).toHaveBeenCalledWith(base, { params: { status: 'DRAFT', page: 2, size: 20 } });
  });

  it('getNoteDetail gets /{id}', async () => {
    await getNoteDetail(F, 'N1');
    expect(get).toHaveBeenCalledWith(`${base}/N1`);
  });

  it('confirmNote puts /{id}/confirm with long timeout', async () => {
    await confirmNote(F, 'N1');
    expect(put).toHaveBeenCalledWith(`${base}/N1/confirm`, {}, { timeout: 600000 });
  });

  it('rejectNote puts /{id}/reject with reason body', async () => {
    const body = { rejectReasonCode: 'IMAGE_BLUR' };
    await rejectNote(F, 'N1', body);
    expect(put).toHaveBeenCalledWith(`${base}/N1/reject`, body);
  });

  it('updateNoteLines puts /{id}/lines with line array', async () => {
    const lines = [{ ingredientName: '猪肉', quantity: 5 }] as SupplierDeliveryNoteLineDto[];
    await updateNoteLines(F, 'N1', lines);
    expect(put).toHaveBeenCalledWith(`${base}/N1/lines`, lines);
  });

  it('deleteNote dels /{id}', async () => {
    await deleteNote(F, 'N1');
    expect(del).toHaveBeenCalledWith(`${base}/N1`);
  });
});
