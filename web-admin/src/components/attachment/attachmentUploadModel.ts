export const DEFAULT_ATTACHMENT_ACCEPT = [
  'image/*',
  'application/pdf',
  '.doc', '.docx', '.xls', '.xlsx', '.csv', '.txt',
].join(',');

export interface AttachmentValidationPolicy {
  accept: string;
  maxSize: number;
  maxFiles: number;
}

export interface AttachmentValidationError {
  fileName: string;
  reason: string;
}

export interface AttachmentValidationResult {
  accepted: File[];
  errors: AttachmentValidationError[];
}

export function attachmentFingerprint(file: File): string {
  return `${file.name.trim().toLowerCase()}:${file.size}:${file.lastModified}`;
}

export function fileMatchesAccept(file: File, accept: string): boolean {
  const rules = accept.split(',').map((rule) => rule.trim().toLowerCase()).filter(Boolean);
  if (!rules.length) return true;
  const name = file.name.toLowerCase();
  const type = file.type.toLowerCase();
  return rules.some((rule) => {
    if (rule.startsWith('.')) return name.endsWith(rule);
    if (rule.endsWith('/*')) return type.startsWith(rule.slice(0, -1));
    return type === rule;
  });
}

export function validateAttachmentFiles(
  files: File[],
  existingFingerprints: ReadonlySet<string>,
  policy: AttachmentValidationPolicy,
): AttachmentValidationResult {
  const accepted: File[] = [];
  const errors: AttachmentValidationError[] = [];
  const seen = new Set(existingFingerprints);

  for (const file of files) {
    const fingerprint = attachmentFingerprint(file);
    if (seen.has(fingerprint)) {
      errors.push({ fileName: file.name, reason: '已在上传队列中，已忽略重复文件' });
      continue;
    }
    if (seen.size >= policy.maxFiles) {
      errors.push({ fileName: file.name, reason: `单次最多 ${policy.maxFiles} 个文件` });
      continue;
    }
    if (!fileMatchesAccept(file, policy.accept)) {
      errors.push({ fileName: file.name, reason: '不支持此文件类型' });
      continue;
    }
    if (file.size <= 0) {
      errors.push({ fileName: file.name, reason: '空文件不能上传' });
      continue;
    }
    if (file.size > policy.maxSize) {
      errors.push({ fileName: file.name, reason: `文件超过 ${formatAttachmentSize(policy.maxSize)}` });
      continue;
    }
    seen.add(fingerprint);
    accepted.push(file);
  }
  return { accepted, errors };
}

export function formatAttachmentSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
