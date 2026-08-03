'use strict';

function splitUrl(input) {
  const value = String(input || '');
  const match = value.match(/^([A-Za-z][A-Za-z0-9+.-]*:\/\/[^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/);
  if (!match) throw new Error(`Unsupported URL: ${value}`);
  return {
    origin: match[1],
    pathname: match[2] || '/',
    search: match[3] || '',
    hash: match[4] || '',
  };
}

function resolveUrl(path, baseUrl) {
  const candidate = String(path || '');
  if (/^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(candidate)) return candidate;
  const base = splitUrl(baseUrl);
  if (candidate.startsWith('/')) return `${base.origin}${candidate}`;
  const directory = base.pathname.replace(/[^/]*$/, '');
  return `${base.origin}${directory}${candidate}`;
}

function pathnameOf(input) {
  return splitUrl(input).pathname;
}

function mapQueryValues(input, mapper) {
  const parsed = splitUrl(input);
  if (!parsed.search) return String(input);
  const mapped = parsed.search.slice(1).split('&').filter(Boolean).map((pair) => {
    const separator = pair.indexOf('=');
    const rawKey = separator >= 0 ? pair.slice(0, separator) : pair;
    const rawValue = separator >= 0 ? pair.slice(separator + 1) : '';
    let decodedKey = rawKey;
    try { decodedKey = decodeURIComponent(rawKey.replace(/\+/g, ' ')); } catch {}
    const nextValue = mapper(decodedKey, rawValue);
    return separator >= 0 || nextValue !== '' ? `${rawKey}=${nextValue}` : rawKey;
  });
  return `${parsed.origin}${parsed.pathname}${mapped.length ? `?${mapped.join('&')}` : ''}${parsed.hash}`;
}

function normalizeQueryValues(input, replacement = '*') {
  return mapQueryValues(input, () => replacement);
}

module.exports = { mapQueryValues, normalizeQueryValues, pathnameOf, resolveUrl, splitUrl };
