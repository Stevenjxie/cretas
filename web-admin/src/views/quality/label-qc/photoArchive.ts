import { zipSync } from 'fflate';
import type { LabelQcTaskDetail } from '@/api/labelQc';

const JPEG_MIME_TYPE = 'image/jpeg';
const ZIP_MIME_TYPE = 'application/zip';

export interface PhotoArchive {
  blob: Blob;
  filename: string;
  photoCount: number;
}

function twoDigits(value: number): string {
  return String(value).padStart(2, '0');
}

export function archiveTimestamp(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    throw new Error('质检任务缺少有效的提交时间，无法生成归档文件名');
  }
  return [
    date.getFullYear(),
    twoDigits(date.getMonth() + 1),
    twoDigits(date.getDate()),
    '_',
    twoDigits(date.getHours()),
    twoDigits(date.getMinutes()),
    twoDigits(date.getSeconds()),
  ].join('');
}

export function safeArchiveName(value: string): string {
  const normalized = value
    .normalize('NFKC')
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_')
    .replace(/\s+/g, ' ')
    .replace(/[. ]+$/g, '')
    .trim();
  return (normalized || '未命名SKU').slice(0, 80);
}

export function photoArchiveEntryName(
  skuName: string,
  createdAt: string,
  sequence: number,
): string {
  return `${safeArchiveName(skuName)}_${archiveTimestamp(createdAt)}_${twoDigits(sequence)}.jpg`;
}

function isJpeg(bytes: Uint8Array): boolean {
  return bytes.length >= 3
    && bytes[0] === 0xff
    && bytes[1] === 0xd8
    && bytes[2] === 0xff;
}

async function canvasToJpeg(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (blob) resolve(blob);
        else reject(new Error('照片转换为 JPG 失败'));
      },
      JPEG_MIME_TYPE,
      0.92,
    );
  });
}

async function convertToJpeg(blob: Blob, originalBytes: Uint8Array): Promise<Uint8Array> {
  if (isJpeg(originalBytes)) return originalBytes;
  if (typeof createImageBitmap !== 'function') {
    throw new Error('当前浏览器不支持照片转 JPG，请升级浏览器后重试');
  }

  const bitmap = await createImageBitmap(blob);
  try {
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('浏览器无法创建照片转换画布');
    context.drawImage(bitmap, 0, 0);
    const jpeg = await canvasToJpeg(canvas);
    return new Uint8Array(await jpeg.arrayBuffer());
  } finally {
    bitmap.close();
  }
}

async function fetchJpeg(url: string): Promise<Uint8Array> {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`照片下载失败（HTTP ${response.status}）`);
  }
  const blob = await response.blob();
  const bytes = new Uint8Array(await blob.arrayBuffer());
  return convertToJpeg(blob, bytes);
}

export async function createPhotoArchive(detail: LabelQcTaskDetail): Promise<PhotoArchive> {
  if (!detail.photos.length) {
    throw new Error('这一批没有可下载的照片');
  }

  const photos = [...detail.photos].sort((left, right) => left.orderIndex - right.orderIndex);
  const entries: Record<string, Uint8Array> = {};
  for (const [index, photo] of photos.entries()) {
    if (!photo.imageUrl) {
      throw new Error(`第 ${index + 1} 张照片缺少下载地址，未生成残缺归档包`);
    }
    const filename = photoArchiveEntryName(
      detail.task.skuName,
      detail.task.createdAt,
      index + 1,
    );
    entries[filename] = await fetchJpeg(photo.imageUrl);
  }

  const archiveBytes = zipSync(entries, { level: 0 });
  const archiveBuffer = archiveBytes.buffer.slice(
    archiveBytes.byteOffset,
    archiveBytes.byteOffset + archiveBytes.byteLength,
  ) as ArrayBuffer;
  const basename = `${safeArchiveName(detail.task.skuName)}_${archiveTimestamp(detail.task.createdAt)}_照片备份`;
  return {
    blob: new Blob([archiveBuffer], { type: ZIP_MIME_TYPE }),
    filename: `${basename}.zip`,
    photoCount: photos.length,
  };
}

export function downloadPhotoArchive(archive: PhotoArchive): void {
  const url = URL.createObjectURL(archive.blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = archive.filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
