import { unzipSync } from 'fflate';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { LabelQcTaskDetail } from '@/api/labelQc';
import {
  archiveTimestamp,
  createPhotoArchive,
  photoArchiveEntryName,
  safeArchiveName,
} from './photoArchive';

const JPEG_BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xdb, 0x00, 0x01, 0xff, 0xd9]);

function detail(): LabelQcTaskDetail {
  return {
    task: {
      id: 'task-1',
      productTypeId: 'sku-1',
      skuCode: 'SKU-001',
      skuName: '干式熟成鸡/半只',
      batchNumber: 'B-001',
      productionDate: '2026-08-03',
      createdBy: 1,
      status: 'REVIEWED',
      version: 1,
      photoCount: 2,
      aiCandidateCount: 0,
      finalDefectCount: 0,
      archived: false,
      trainingStatus: 'PENDING',
      createdAt: '2026-08-03T14:30:45',
      updatedAt: '2026-08-03T14:35:00',
    },
    photos: [1, 0].map((orderIndex) => ({
      id: `photo-${orderIndex + 1}`,
      attachmentId: `attachment-${orderIndex + 1}`,
      orderIndex,
      imageWidth: 1200,
      imageHeight: 1600,
      status: 'REVIEWED',
      imageUrl: `https://example.test/photo-${orderIndex + 1}.jpg`,
      annotations: [],
    })),
  };
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('label QC photo archive', () => {
  it('builds customer-readable and Windows-safe JPG names', () => {
    expect(safeArchiveName(' 干式熟成鸡/半只:*? ')).toBe('干式熟成鸡_半只___');
    expect(archiveTimestamp('2026-08-03T14:30:45')).toBe('20260803_143045');
    expect(photoArchiveEntryName('干式熟成鸡/半只', '2026-08-03T14:30:45', 2))
      .toBe('干式熟成鸡_半只_20260803_143045_02.jpg');
  });

  it('downloads every photo as a JPG entry in one ZIP archive', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => (
      new Response(JPEG_BYTES.slice(), {
        status: 200,
        headers: { 'content-type': 'image/jpeg' },
      })
    )));

    const archive = await createPhotoArchive(detail());
    const files = unzipSync(new Uint8Array(await archive.blob.arrayBuffer()));

    expect(archive.filename).toBe('干式熟成鸡_半只_20260803_143045_照片备份.zip');
    expect(archive.photoCount).toBe(2);
    expect(Object.keys(files)).toEqual([
      '干式熟成鸡_半只_20260803_143045_01.jpg',
      '干式熟成鸡_半只_20260803_143045_02.jpg',
    ]);
    expect(files['干式熟成鸡_半只_20260803_143045_01.jpg']).toEqual(JPEG_BYTES);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('fails closed when a photo URL is missing', async () => {
    const input = detail();
    input.photos.find((photo) => photo.orderIndex === 0)!.imageUrl = null;
    await expect(createPhotoArchive(input)).rejects.toThrow('未生成残缺归档包');
  });
});
