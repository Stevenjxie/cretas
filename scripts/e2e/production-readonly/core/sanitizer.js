'use strict';

const SENSITIVE_KEY_RE = /authorization|cookie|password|passwd|secret|token|credential|session|jwt|set-cookie|(?:^|[_-])username(?:$|[_-])/i;
const SAFE_STRING_KEYS = new Set(['factoryId', 'mode', 'moduleCode', 'action', 'status', 'code', 'category', 'type']);

function redactText(value, maxLength = 240) {
  let text = String(value ?? '');
  text = text
    .replace(/Bearer\s+[A-Za-z0-9._~+\/-]+=*/gi, 'Bearer [REDACTED]')
    .replace(/([?&](?:token|access_token|refresh_token|password|secret|authorization)=)[^&#\s]*/gi, '$1[REDACTED]')
    .replace(/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g, '[REDACTED_EMAIL]')
    .replace(/(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)/g, '[REDACTED_PHONE]')
    .replace(/\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{8,}(?:\.[A-Za-z0-9_-]{8,})?\b/g, '[REDACTED_JWT]');
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}

function sanitizeUrl(input) {
  try {
    const url = new URL(String(input));
    if (url.username) url.username = '[REDACTED]';
    if (url.password) url.password = '[REDACTED]';
    for (const key of [...url.searchParams.keys()]) {
      if (SENSITIVE_KEY_RE.test(key)) url.searchParams.set(key, '[REDACTED]');
    }
    return redactText(url.toString(), 500);
  } catch {
    return redactText(input, 500);
  }
}

function summarizeScalar(key, value) {
  if (value == null || typeof value === 'boolean' || typeof value === 'number') return value;
  if (SENSITIVE_KEY_RE.test(key)) return '[REDACTED]';
  if (typeof value === 'string') {
    if (SAFE_STRING_KEYS.has(key)) return redactText(value, 80);
    return `<string:${value.length}>`;
  }
  return `<${typeof value}>`;
}

function summarizePayload(value, depth = 0) {
  if (value == null) return null;
  if (depth > 3) return '[DEPTH_LIMIT]';
  if (Array.isArray(value)) {
    return {
      type: 'array',
      length: value.length,
      sample: value.slice(0, 3).map((item) => summarizePayload(item, depth + 1)),
    };
  }
  if (typeof value !== 'object') return summarizeScalar('', value);
  const keys = Object.keys(value).slice(0, 40);
  const fields = {};
  for (const key of keys) {
    const child = value[key];
    if (SENSITIVE_KEY_RE.test(key)) fields[key] = '[REDACTED]';
    else if (child && typeof child === 'object') fields[key] = summarizePayload(child, depth + 1);
    else fields[key] = summarizeScalar(key, child);
  }
  return { type: 'object', keys, fields };
}

function sanitizeValue(value, key = '', depth = 0) {
  if (SENSITIVE_KEY_RE.test(key)) return '[REDACTED]';
  if (value == null || typeof value === 'boolean' || typeof value === 'number') return value;
  if (typeof value === 'string') return redactText(value);
  if (depth > 5) return '[DEPTH_LIMIT]';
  if (Array.isArray(value)) return value.slice(0, 200).map((item) => sanitizeValue(item, '', depth + 1));
  if (typeof value === 'object') {
    const result = {};
    for (const [childKey, childValue] of Object.entries(value)) {
      result[childKey] = sanitizeValue(childValue, childKey, depth + 1);
    }
    return result;
  }
  return redactText(value);
}

function parsePostData(request) {
  try {
    return request.postDataJSON();
  } catch {
    const raw = request.postData();
    return raw ? { rawLength: raw.length } : null;
  }
}

module.exports = {
  SENSITIVE_KEY_RE,
  redactText,
  sanitizeUrl,
  sanitizeValue,
  summarizePayload,
  parsePostData,
};
