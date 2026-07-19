'use strict';

const AUTH_REQUESTS = [
  {
    id: 'ui-login',
    method: 'POST',
    path: /^\/api\/mobile\/auth\/unified-login\/?$/,
  },
];

// Every entry must point to a server contract that is query-only. Do not add
// generic prefixes. AI chat is deliberately absent because some modes may
// execute tools with side effects behind a single HTTP request.
const READONLY_POST_WHITELIST = [
  {
    id: 'purchase-order-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/purchaseOrder\/?$/,
    rationale: 'Query-only list summary for purchase order rows.',
  },
  {
    id: 'sales-order-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/salesOrder\/?$/,
    rationale: 'Query-only list summary for sales order rows.',
  },
  {
    id: 'production-plan-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/productionPlan\/?$/,
    rationale: 'Query-only list summary for production plan rows.',
  },
  {
    id: 'workflow-resolve-by-outputs',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/product-process-workflows\/resolve-by-outputs\/?$/,
    rationale: 'Read-only workflow resolution; backend service is @Transactional(readOnly = true).',
  },
  {
    id: 'attachment-chip-counts',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/attachments\/batch-3chip-counts\/?$/,
    rationale: 'Batch query for attachment counts; no attachment mutation.',
  },
];

function matchesEntry(entry, method, url) {
  const parsed = new URL(url);
  return entry.method === method.toUpperCase() && entry.path.test(parsed.pathname);
}

function findAuthRequest(method, url) {
  return AUTH_REQUESTS.find((entry) => matchesEntry(entry, method, url)) || null;
}

function findReadonlyPost(method, url, entries = READONLY_POST_WHITELIST) {
  return entries.find((entry) => matchesEntry(entry, method, url)) || null;
}

module.exports = {
  AUTH_REQUESTS,
  READONLY_POST_WHITELIST,
  matchesEntry,
  findAuthRequest,
  findReadonlyPost,
};
