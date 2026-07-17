'use strict';

const { parsePostData, redactText, sanitizeUrl, summarizePayload } = require('./sanitizer');

function normalizeDuplicateKey(method, url) {
  try {
    const parsed = new URL(url);
    for (const key of [...parsed.searchParams.keys()]) parsed.searchParams.set(key, '*');
    return `${method} ${parsed.origin}${parsed.pathname}${parsed.search}`;
  } catch {
    return `${method} ${url}`;
  }
}

function pickResponseSummary(body, extractors, url) {
  const summary = {
    success: typeof body?.success === 'boolean' ? body.success : null,
    message: body?.message == null ? null : redactText(body.message, 160),
    keyFields: {},
  };
  for (const extractor of extractors || []) {
    if (!extractor.match(url)) continue;
    Object.assign(summary.keyFields, extractor.pick(body) || {});
  }
  return summary;
}

function installNetworkRecorder(context, options = {}) {
  const records = [];
  const httpErrors = [];
  const failedRequests = [];
  const started = new WeakMap();
  const pending = new Set();
  const extractors = options.responseExtractors || [];

  const onRequest = (request) => {
    if (!request.url().includes('/api/mobile/')) return;
    started.set(request, {
      at: Date.now(),
      payload: summarizePayload(parsePostData(request)),
    });
  };

  const onResponse = (response) => {
    const request = response.request();
    if (!request.url().includes('/api/mobile/')) return;
    const task = (async () => {
      const start = started.get(request);
      let body = null;
      try {
        const contentType = response.headers()['content-type'] || '';
        if (/json/i.test(contentType)) body = await response.json();
      } catch {
        body = null;
      }
      const record = {
        method: request.method(),
        url: sanitizeUrl(request.url()),
        status: response.status(),
        durationMs: start ? Math.max(0, Date.now() - start.at) : null,
        requestPayload: start?.payload || null,
        response: pickResponseSummary(body, extractors, request.url()),
        at: new Date(start?.at || Date.now()).toISOString(),
      };
      records.push(record);
      if (response.status() >= 400) httpErrors.push(record);
    })();
    pending.add(task);
    task.finally(() => pending.delete(task));
  };

  const onRequestFailed = (request) => {
    if (!request.url().includes('/api/mobile/')) return;
    const errorText = request.failure()?.errorText || 'request failed';
    // Requests aborted by the mutation guard are represented in the guard's
    // blockedMutationAttempts collection, not duplicated as HTTP failures.
    if (/ERR_BLOCKED_BY_CLIENT|blockedbyclient/i.test(errorText)) return;
    failedRequests.push({
      method: request.method(),
      url: sanitizeUrl(request.url()),
      error: redactText(errorText, 160),
      at: new Date().toISOString(),
    });
  };

  context.on('request', onRequest);
  context.on('response', onResponse);
  context.on('requestfailed', onRequestFailed);

  return {
    records,
    httpErrors,
    failedRequests,
    async flush() {
      await Promise.allSettled([...pending]);
    },
    snapshot() {
      const duplicates = new Map();
      for (const record of records) {
        const key = normalizeDuplicateKey(record.method, record.url);
        duplicates.set(key, (duplicates.get(key) || 0) + 1);
      }
      return {
        apiEvidence: records.slice(),
        httpErrors: httpErrors.slice(),
        failedRequests: failedRequests.slice(),
        duplicateRequests: [...duplicates.entries()]
          .filter(([, count]) => count > 1)
          .map(([request, count]) => ({ request, count }))
          .sort((a, b) => b.count - a.count),
      };
    },
    dispose() {
      context.off('request', onRequest);
      context.off('response', onResponse);
      context.off('requestfailed', onRequestFailed);
    },
  };
}

module.exports = { installNetworkRecorder, normalizeDuplicateKey, pickResponseSummary };
