'use strict';

const { findAuthRequest, findReadonlyPost, READONLY_POST_WHITELIST } = require('../config/readonly-post-whitelist');
const { sanitizeUrl } = require('./sanitizer');

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function classifyMutation(method, url, whitelist = READONLY_POST_WHITELIST) {
  const upper = String(method || '').toUpperCase();
  if (!MUTATING_METHODS.has(upper)) return { kind: 'safe-method' };
  const auth = findAuthRequest(upper, url);
  if (auth) return { kind: 'auth', entry: auth };
  const readonly = findReadonlyPost(upper, url, whitelist);
  if (readonly) return { kind: 'readonly-post', entry: readonly };
  return { kind: 'business-mutation' };
}

async function installMutationGuard(context, options = {}) {
  const scenarioRef = options.scenarioRef || { value: 'bootstrap' };
  const whitelist = options.readonlyPostWhitelist || READONLY_POST_WHITELIST;
  const authRequests = [];
  const readonlyPostRequests = [];
  const blockedMutationAttempts = [];
  const actualBusinessWriteRequests = [];
  const allowedByRequest = new WeakMap();

  const handler = async (route) => {
    const request = route.request();
    const method = request.method().toUpperCase();
    const classification = classifyMutation(method, request.url(), whitelist);
    if (classification.kind === 'safe-method') {
      await route.continue();
      return;
    }

    const record = {
      method,
      url: sanitizeUrl(request.url()),
      scenario: scenarioRef.value || 'unknown',
      at: new Date().toISOString(),
    };
    if (classification.kind === 'auth') {
      authRequests.push({ ...record, classificationId: classification.entry.id });
      allowedByRequest.set(request, classification.kind);
      await route.continue();
      return;
    }
    if (classification.kind === 'readonly-post') {
      readonlyPostRequests.push({ ...record, classificationId: classification.entry.id });
      allowedByRequest.set(request, classification.kind);
      await route.continue();
      return;
    }

    blockedMutationAttempts.push({ ...record, safetyStatus: 'FAIL', blockedBeforeSend: true });
    await route.abort('blockedbyclient');
  };

  const responseListener = (response) => {
    const request = response.request();
    const method = request.method().toUpperCase();
    if (!MUTATING_METHODS.has(method)) return;
    const classification = classifyMutation(method, request.url(), whitelist);
    if (classification.kind === 'business-mutation' && !allowedByRequest.has(request)) {
      actualBusinessWriteRequests.push({
        method,
        url: sanitizeUrl(request.url()),
        status: response.status(),
        scenario: scenarioRef.value || 'unknown',
        at: new Date().toISOString(),
      });
    }
  };

  await context.route('**/*', handler);
  context.on('response', responseListener);

  return {
    authRequests,
    readonlyPostRequests,
    blockedMutationAttempts,
    actualBusinessWriteRequests,
    get actualBusinessWrites() {
      return actualBusinessWriteRequests.length;
    },
    snapshot() {
      return {
        authRequests: authRequests.slice(),
        readonlyPostRequests: readonlyPostRequests.slice(),
        blockedMutationAttempts: blockedMutationAttempts.slice(),
        actualBusinessWriteRequests: actualBusinessWriteRequests.slice(),
        actualBusinessWrites: actualBusinessWriteRequests.length,
      };
    },
    async dispose() {
      context.off('response', responseListener);
      await context.unroute('**/*', handler);
    },
  };
}

module.exports = { MUTATING_METHODS, classifyMutation, installMutationGuard };
