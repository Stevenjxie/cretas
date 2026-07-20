import { describe, expect, it } from 'vitest';
import {
  attachmentFingerprint,
  fileMatchesAccept,
  validateAttachmentFiles,
} from './attachmentUploadModel';

function file(name: string, size = 4, type = 'application/pdf', lastModified = 1): File {
  return new File([new Uint8Array(size)], name, { type, lastModified });
}

describe('shared attachment drop-zone model', () => {
  it('accepts multiple safe documents and images', () => {
    const files = [file('contract.pdf'), file('photo.png', 4, 'image/png')];
    const result = validateAttachmentFiles(files, new Set(), {
      accept: 'image/*,application/pdf', maxSize: 10, maxFiles: 5,
    });
    expect(result.accepted).toHaveLength(2);
    expect(result.errors).toEqual([]);
  });

  it('reports invalid type, size and empty file independently', () => {
    const result = validateAttachmentFiles([
      file('danger.exe', 4, 'application/octet-stream'),
      file('large.pdf', 11),
      file('empty.pdf', 0),
    ], new Set(), { accept: 'application/pdf', maxSize: 10, maxFiles: 5 });
    expect(result.accepted).toEqual([]);
    expect(result.errors.map((entry) => entry.reason)).toEqual([
      '不支持此文件类型', '文件超过 10 B', '空文件不能上传',
    ]);
  });

  it('deduplicates repeated drops and enforces the queue count', () => {
    const repeated = file('same.pdf', 4, 'application/pdf', 7);
    const result = validateAttachmentFiles(
      [repeated, file('next.pdf')],
      new Set([attachmentFingerprint(repeated)]),
      { accept: 'application/pdf', maxSize: 10, maxFiles: 1 },
    );
    expect(result.accepted).toEqual([]);
    expect(result.errors).toHaveLength(2);
    expect(result.errors[0].reason).toContain('重复');
    expect(result.errors[1].reason).toContain('最多 1');
  });

  it('recognizes extensions and MIME wildcards without allowing macros', () => {
    expect(fileMatchesAccept(file('sheet.xlsx', 4, ''), '.xlsx,image/*')).toBe(true);
    expect(fileMatchesAccept(file('macro.xlsm', 4, ''), '.xlsx,image/*')).toBe(false);
  });
});
