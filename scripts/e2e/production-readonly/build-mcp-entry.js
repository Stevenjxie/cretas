'use strict';

const fs = require('node:fs');
const path = require('node:path');

const harnessRoot = __dirname;
const entryId = 'core/run-suite.js';

function moduleId(absolutePath) {
  return path.relative(harnessRoot, absolutePath).replace(/\\/g, '/');
}

function resolveLocal(fromId, request) {
  if (!request.startsWith('.')) throw new Error(`MCP bundle cannot include external module ${request} from ${fromId}`);
  const base = path.resolve(harnessRoot, path.dirname(fromId), request);
  const candidate = path.extname(base) ? base : `${base}.js`;
  if (!fs.existsSync(candidate)) throw new Error(`Cannot resolve ${request} from ${fromId}`);
  return moduleId(candidate);
}

function collectModules(id, modules = new Map()) {
  if (modules.has(id)) return modules;
  const source = fs.readFileSync(path.join(harnessRoot, id), 'utf8').replace(/^\uFEFF/, '');
  modules.set(id, source);
  const requirePattern = /require\((['"])([^'"]+)\1\)/g;
  for (const match of source.matchAll(requirePattern)) collectModules(resolveLocal(id, match[2]), modules);
  return modules;
}

function renderBundle() {
  const modules = collectModules(entryId);
  const moduleEntries = [...modules.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([id, source]) => (
    `${JSON.stringify(id)}: function(module, exports, require) {\n${source}\n}`
  ));
  return `async (page) => {
  const __modules = {
    ${moduleEntries.join(',\n    ')}
  };
  const __cache = Object.create(null);
  const __resolve = (fromId, request) => {
    const parts = fromId.split('/');
    parts.pop();
    for (const part of request.split('/')) {
      if (!part || part === '.') continue;
      if (part === '..') parts.pop();
      else parts.push(part);
    }
    let id = parts.join('/');
    if (!/\\.[A-Za-z0-9]+$/.test(id)) id += '.js';
    return id;
  };
  const __load = (id) => {
    if (__cache[id]) return __cache[id].exports;
    const factory = __modules[id];
    if (!factory) throw new Error('Missing bundled module: ' + id);
    const module = { exports: {} };
    __cache[id] = module;
    factory(module, module.exports, (request) => __load(__resolve(id, request)));
    return module.exports;
  };
  const options = page.__cretasReadonlyOptions || {};
  delete page.__cretasReadonlyOptions;
  const harness = __load(${JSON.stringify(entryId)});
  if (options.dryRun) return harness.describeHarness();
  return harness.runSuiteWithPage(page, { ...options, productionReadonly: true });
}\n`;
}

if (require.main === module) {
  fs.writeFileSync(path.join(harnessRoot, 'mcp-entry.js'), renderBundle(), 'utf8');
}

module.exports = { collectModules, renderBundle };
