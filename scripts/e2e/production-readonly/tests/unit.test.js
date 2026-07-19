'use strict';

// Stable cross-platform entry: PowerShell does not expand test globs in the
// same way as POSIX shells. These modules register their cases with node:test.
require('./sanitizer.test');
require('./whitelist.test');
require('./mutation-guard.test');
require('./result-schema.test');
require('./mcp-entry.test');
require('./skill-drift.test');
require('./production-plan-routing.test');
