'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { normalizeQueryValues, pathnameOf, resolveUrl } = require('../core/url-utils');

test('resolves and parses production URLs without relying on a global URL constructor', () => {
  assert.equal(resolveUrl('/login', 'https://admin.cretaceousfuture.com'), 'https://admin.cretaceousfuture.com/login');
  assert.equal(pathnameOf('https://admin.cretaceousfuture.com/restaurant/staffing?horizon=week'), '/restaurant/staffing');
  assert.equal(
    normalizeQueryValues('https://admin.cretaceousfuture.com/api/items?page=2&name=x'),
    'https://admin.cretaceousfuture.com/api/items?page=*&name=*',
  );
});
