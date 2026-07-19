/**
 * Controlled production fixture for the F006 workflow-routing topology matrix.
 *
 * This is deliberately separate from the production-readonly harness and from
 * the historical non-production business-flow runners. It only accepts the
 * exact production admin origin, tenant F006, user f006_admin, and an explicit
 * per-task confirmation phrase.
 *
 * Usage:
 *   node tests/e2e-workflow-routing/f006-topology-matrix.mjs --plan
 *   E2E_F006_PROD_WRITE_CONFIRM=F006-TOPOLOGY-MATRIX-20260719 \
 *     node tests/e2e-workflow-routing/f006-topology-matrix.mjs --apply
 */
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const ADMIN_URL = process.env.E2E_ADMIN_URL || 'https://admin.cretaceousfuture.com';
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'F006';
const USERNAME = process.env.E2E_USERNAME;
const PASSWORD = process.env.E2E_PASSWORD;
const WRITE_CONFIRM = process.env.E2E_F006_PROD_WRITE_CONFIRM;
const REQUIRED_CONFIRM = 'F006-TOPOLOGY-MATRIX-20260719';
const RUN_ID = process.env.E2E_RUN_ID || '20260719-A1';
const PREFIX = `E2E-WF-MATRIX-${RUN_ID}`;
const OUT_DIR = path.resolve(
  process.env.E2E_OUT || `.playwright-mcp/f006-topology-matrix-${RUN_ID}`,
);
const REPORT_FILE = path.join(OUT_DIR, 'api-fixture-report.json');
const modeArgs = process.argv.slice(2).filter((arg) => arg === '--plan' || arg === '--apply');
const MODE = modeArgs[0] || null;

const EXPECTED_MUTATION_PLAN = Object.freeze({
  workProcessCreates: 2,
  productTypeCreates: 11,
  bomRecipeCreates: 8,
  bomRecipeActivations: 8,
  workflowDraftSaves: 8,
  workflowPublishes: 8,
  workflowActivations: 8,
  fixtureMutationRequests: 53,
  laterUiPlanCreates: 1,
  maximumTaskMutationRequests: 54,
});

const RAW = Object.freeze({
  A: { id: 'RMT_1784312656291', name: 'E2E-替代链-原料A', unit: 'kg' },
  B: { id: 'RMT_1784312671686', name: 'E2E-替代链-原料B', unit: 'kg' },
  C: { id: 'RMT_1784312690953', name: 'E2E-替代链-原料C', unit: 'kg' },
  D: { id: 'RMT_1784312702870', name: 'E2E-替代链-原料D', unit: 'kg' },
});

const PRODUCT_LABELS = Object.freeze({
  A: '产品A-单产',
  B: '产品B-多投一产',
  C: '产品C-单投分产',
  D: '产品D-联产',
  E: '产品E-重叠候选',
  F: '产品F-重叠候选',
  G: '产品G-替代组',
  H: '产品H-替代组',
  X: '归属X-重叠候选',
  Y: '归属Y-重叠候选',
  L: '归属L-大超集',
});

const report = {
  schemaVersion: 1,
  task: 'CRETAS-F006-WORKFLOW-TOPOLOGY-MATRIX-20260719',
  runId: RUN_ID,
  prefix: PREFIX,
  mode: MODE,
  adminOrigin: ADMIN_URL,
  factoryId: FACTORY_ID,
  expectedMutationPlan: EXPECTED_MUTATION_PLAN,
  identityProof: null,
  preflight: null,
  mutations: [],
  readbacks: [],
  ids: { workProcesses: {}, products: {}, boms: {}, workflows: {} },
  resolverCases: [],
  status: 'STARTED',
  startedAt: new Date().toISOString(),
};

let token = null;
let identity = null;

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function listOf(value) {
  if (Array.isArray(value)) return value;
  if (!value || typeof value !== 'object') return [];
  if (Array.isArray(value.content)) return value.content;
  if (Array.isArray(value.records)) return value.records;
  if (Array.isArray(value.items)) return value.items;
  if (Array.isArray(value.processes)) return value.processes;
  return [];
}

function unwrap(json) {
  return json && typeof json === 'object' && Object.hasOwn(json, 'data') ? json.data : json;
}

function sameSet(left, right) {
  const a = [...new Set((left || []).map(String))].sort();
  const b = [...new Set((right || []).map(String))].sort();
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

function today(offsetDays = 0) {
  return new Date(Date.now() + 8 * 3600e3 + offsetDays * 86400e3).toISOString().slice(0, 10);
}

function guardedTenantPath(relativePath) {
  assert(relativePath.startsWith(`/${FACTORY_ID}/`), `tenant path is not F006-scoped: ${relativePath}`);
  assert(!relativePath.includes('..'), `unsafe relative path: ${relativePath}`);
  return `/api/mobile${relativePath}`;
}

function assertFactory(value, label) {
  assert(value && typeof value === 'object', `${label}: response is not an object`);
  assert(value.factoryId === FACTORY_ID, `${label}: factoryId=${value.factoryId} (expected ${FACTORY_ID})`);
}

async function saveReport() {
  await mkdir(OUT_DIR, { recursive: true });
  await writeFile(REPORT_FILE, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
}

async function login() {
  const response = await fetch(`${ADMIN_URL}/api/mobile/auth/unified-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USERNAME, password: PASSWORD }),
  });
  const text = await response.text();
  let json;
  try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
  if (!response.ok || json?.success === false) {
    throw new Error(`login failed: HTTP ${response.status} ${JSON.stringify(json).slice(0, 500)}`);
  }
  const data = unwrap(json) || {};
  token = data.accessToken || data.token;
  assert(token, 'login returned no access token');
  const liveFactoryId = data.factoryUser?.factoryId || data.factoryId;
  assert(liveFactoryId === FACTORY_ID, `live login factoryId=${liveFactoryId}; refusing all writes`);
  assert(String(data.username || USERNAME) === USERNAME, 'live login username mismatch');
  identity = {
    userId: data.userId,
    username: data.username || USERNAME,
    role: data.role,
    factoryId: liveFactoryId,
    factoryType: data.factoryType,
    businessDomain: data.businessDomain,
    tokenPresent: true,
  };
  report.identityProof = identity;
  console.log(`IDENTITY PASS userId=${identity.userId} role=${identity.role} factoryId=${identity.factoryId}`);
}

async function api(method, relativePath, body, options = {}) {
  const { mutation = false, label = `${method} ${relativePath}` } = options;
  const apiPath = guardedTenantPath(relativePath);
  if (mutation) {
    assert(MODE === '--apply', `${label}: mutation attempted outside --apply mode`);
    assert(identity?.factoryId === 'F006', `${label}: missing live F006 identity proof`);
    assert(WRITE_CONFIRM === REQUIRED_CONFIRM, `${label}: missing exact production-write confirmation`);
  }
  const response = await fetch(`${ADMIN_URL}${apiPath}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: body == null ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json;
  try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
  const data = unwrap(json);
  if (!response.ok || json?.success === false) {
    const error = new Error(`${label}: HTTP ${response.status} ${JSON.stringify(json).slice(0, 1200)}`);
    error.status = response.status;
    error.path = apiPath;
    error.response = json;
    throw error;
  }
  if (mutation) {
    report.mutations.push({
      index: report.mutations.length + 1,
      label,
      method,
      path: apiPath,
      payload: body ?? null,
      httpStatus: response.status,
      returnedId: data?.id ?? data?.planId ?? null,
      returnedFactoryId: data?.factoryId ?? null,
      completedAt: new Date().toISOString(),
    });
    await saveReport();
  }
  return data;
}

async function preflight() {
  const [rawTypes, activeProducts, activeProcesses] = await Promise.all([
    api('GET', `/${FACTORY_ID}/raw-material-types/active`),
    api('GET', `/${FACTORY_ID}/product-types/active`),
    api('GET', `/${FACTORY_ID}/work-processes/active`),
  ]);
  const rawRows = listOf(rawTypes);
  const productRows = listOf(activeProducts);
  const processRows = listOf(activeProcesses);
  const rawParents = Object.fromEntries(Object.entries(RAW).map(([key, expected]) => {
    const actual = rawRows.find((row) => String(row.id) === expected.id);
    assert(actual, `preflight: missing raw parent ${key}/${expected.id}`);
    assert(actual.factoryId == null || actual.factoryId === FACTORY_ID,
      `preflight: raw parent ${key} belongs to ${actual.factoryId}`);
    assert(actual.name === expected.name, `preflight: raw parent ${key} name drifted to ${actual.name}`);
    assert(actual.unit === 'kg', `preflight: raw parent ${key} unit=${actual.unit}`);
    return [key, { id: actual.id, name: actual.name, unit: actual.unit, factoryId: actual.factoryId || FACTORY_ID }];
  }));
  const productPrefixRows = productRows.filter((row) => String(row.name || '').startsWith(PREFIX));
  const processPrefixRows = processRows.filter((row) => String(row.processName || '').startsWith(PREFIX));
  assert(productPrefixRows.length === 0, `preflight: ${productPrefixRows.length} products already use ${PREFIX}`);
  assert(processPrefixRows.length === 0, `preflight: ${processPrefixRows.length} processes already use ${PREFIX}`);
  report.preflight = {
    checkedAt: new Date().toISOString(),
    rawParents,
    productPrefixCountViaApi: productPrefixRows.length,
    processPrefixCountViaApi: processPrefixRows.length,
  };
  console.log(`PREFLIGHT PASS prefix=${PREFIX} products=0 processes=0 rawParents=4`);
}

async function createWorkProcesses() {
  const specs = {
    P1: `${PREFIX}-工序一-定量包装`,
    P2: `${PREFIX}-工序二-联产分拣`,
  };
  for (const [key, processName] of Object.entries(specs)) {
    const body = {
      processName,
      processCategory: 'WF_E2E_MATRIX',
      unit: 'kg',
      outputUnit: 'kg',
      estimatedMinutes: key === 'P1' ? 10 : 15,
      sortOrder: key === 'P1' ? 9101 : 9102,
      isActive: true,
      needsInput: true,
      defaultOutputMaterialKind: 'FINISHED_GOOD',
      description: `${PREFIX} controlled F006 topology-matrix fixture`,
    };
    const created = await api('POST', `/${FACTORY_ID}/work-processes`, body, {
      mutation: true,
      label: `create work process ${key}`,
    });
    assert(created?.id, `create work process ${key}: no id`);
    const readback = await api('GET', `/${FACTORY_ID}/work-processes/${encodeURIComponent(created.id)}`);
    assert(String(readback.id) === String(created.id), `work process ${key}: id readback mismatch`);
    assert(readback.processName === processName, `work process ${key}: name readback mismatch`);
    assert(readback.isActive === true, `work process ${key}: not active after create`);
    assert(readback.defaultOutputMaterialKind === 'FINISHED_GOOD',
      `work process ${key}: default output kind mismatch`);
    report.ids.workProcesses[key] = { id: String(created.id), processName };
    report.readbacks.push({ entity: `workProcess.${key}`, path: `/${FACTORY_ID}/work-processes/${created.id}`, state: readback });
  }
}

async function createProducts() {
  for (const [key, suffix] of Object.entries(PRODUCT_LABELS)) {
    const body = {
      name: `${PREFIX}-${suffix}`,
      unit: 'kg',
      productCategory: 'FINISHED_PRODUCT',
      isActive: true,
      notes: `${PREFIX} controlled F006 topology-matrix fixture (${key})`,
    };
    const created = await api('POST', `/${FACTORY_ID}/product-types`, body, {
      mutation: true,
      label: `create product ${key}`,
    });
    assert(created?.id, `create product ${key}: no id`);
    assertFactory(created, `create product ${key}`);
    const readback = await api('GET', `/${FACTORY_ID}/product-types/${encodeURIComponent(created.id)}`);
    assertFactory(readback, `read product ${key}`);
    assert(readback.name === body.name, `product ${key}: name readback mismatch`);
    assert(readback.unit === 'kg', `product ${key}: unit=${readback.unit}`);
    assert(readback.productCategory === 'FINISHED_PRODUCT',
      `product ${key}: category=${readback.productCategory}`);
    report.ids.products[key] = { id: String(created.id), name: body.name, unit: 'kg' };
    report.readbacks.push({ entity: `product.${key}`, path: `/${FACTORY_ID}/product-types/${created.id}`, state: readback });
  }
}

async function createBoms() {
  for (const key of ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H']) {
    const product = report.ids.products[key];
    const body = {
      productTypeId: product.id,
      productName: product.name,
      outputQuantityPerUnit: 1,
      outputUnit: 'kg',
      sourceType: 'MANUAL',
      items: [{
        materialTypeId: RAW.A.id,
        standardQuantity: null,
        unit: 'kg',
        materialCategory: 'RAW',
        sortOrder: 0,
        isOptional: false,
        perPortion: false,
        remark: `${PREFIX} association-only raw material`,
      }],
      notes: `${PREFIX} BOM for ${key}; RAW quantity intentionally unset`,
    };
    const draft = await api('POST', `/${FACTORY_ID}/bom/recipes`, body, {
      mutation: true,
      label: `create BOM ${key}`,
    });
    assert(draft?.id, `create BOM ${key}: no id`);
    assertFactory(draft, `create BOM ${key}`);
    const active = await api(
      'POST',
      `/${FACTORY_ID}/bom/recipes/${encodeURIComponent(draft.id)}/activate?operatorId=${encodeURIComponent(identity.userId)}`,
      undefined,
      { mutation: true, label: `activate BOM ${key}` },
    );
    assertFactory(active, `activate BOM ${key}`);
    assert(active.status === 'ACTIVE' && active.isCurrent === true,
      `BOM ${key}: status=${active.status} isCurrent=${active.isCurrent}`);
    const readback = await api(
      'GET',
      `/${FACTORY_ID}/bom/recipes/by-product/${encodeURIComponent(product.id)}/current`,
    );
    assertFactory(readback, `read BOM ${key}`);
    assert(String(readback.id) === String(active.id), `BOM ${key}: current id mismatch`);
    assert(readback.status === 'ACTIVE' && readback.isCurrent === true, `BOM ${key}: current state mismatch`);
    assert(Array.isArray(readback.items) && readback.items.length === 1, `BOM ${key}: expected one item`);
    assert(readback.items[0].materialTypeId === RAW.A.id, `BOM ${key}: raw parent mismatch`);
    assert(readback.items[0].standardQuantity == null, `BOM ${key}: RAW quantity should remain null`);
    report.ids.boms[key] = { id: String(active.id), productTypeId: product.id, status: active.status };
    report.readbacks.push({ entity: `bom.${key}`, path: `/${FACTORY_ID}/bom/recipes/by-product/${product.id}/current`, state: readback });
  }
}

function buildWorkflowGraph(spec) {
  const process = report.ids.workProcesses[spec.processKey];
  const nodeId = (type, key) => `${spec.key}-${type}-${key}`.toLowerCase();
  const rawNodes = spec.roots.map((key, index) => ({
    id: nodeId('raw', key),
    kind: 'RAW_MATERIAL',
    position: { x: 40, y: 40 + index * 150 },
    data: {
      name: RAW[key].name,
      skuId: RAW[key].id,
      skuCode: RAW[key].id,
      baseUnit: 'kg',
      bound: true,
    },
  }));
  const processNodeId = nodeId('process', 'main');
  const outputNodes = spec.outputs.map((key, index) => ({
    id: nodeId('finished', key),
    kind: 'FINISHED_GOOD',
    position: { x: 780, y: 40 + index * 150 },
    data: {
      name: report.ids.products[key].name,
      skuId: report.ids.products[key].id,
      skuCode: report.ids.products[key].id,
      baseUnit: 'kg',
      bound: true,
    },
  }));
  const inputPorts = spec.roots.map((key, index) => ({
    id: nodeId('in', key),
    direction: 'INPUT',
    materialNodeId: nodeId('raw', key),
    materialName: RAW[key].name,
    skuId: RAW[key].id,
    materialKind: 'RAW_MATERIAL',
    unit: 'kg',
    ordinal: index,
  }));
  const outputPorts = spec.outputs.map((key, index) => ({
    id: nodeId('out', key),
    direction: 'OUTPUT',
    materialNodeId: nodeId('finished', key),
    materialName: report.ids.products[key].name,
    skuId: report.ids.products[key].id,
    materialKind: 'FINISHED_GOOD',
    unit: 'kg',
    ordinal: index,
  }));
  const processNode = {
    id: processNodeId,
    kind: 'PROCESS',
    position: { x: 410, y: Math.max(40, ((Math.max(spec.roots.length, spec.outputs.length) - 1) * 150) / 2) },
    data: {
      workProcessId: process.id,
      processName: process.processName,
      inputUnit: 'kg',
      outputUnit: 'kg',
      ports: [...inputPorts, ...outputPorts],
      conversionRule: { mode: 'ACTUAL_WEIGHT' },
      reportingRequired: true,
      processCategory: 'WF_E2E_MATRIX',
      allowMultipleUpstreamSources: true,
      allowFinishedGoodsSource: false,
      ...(spec.exactlyOne ? {
        portGroups: [{
          id: nodeId('group', 'alternatives'),
          direction: 'INPUT',
          label: '四种原料互相替代',
          mode: 'EXACTLY_ONE',
          minSelections: 1,
          maxSelections: 1,
          portIds: inputPorts.map((port) => port.id),
        }],
      } : {}),
    },
  };
  const inputEdges = spec.roots.map((key) => ({
    id: nodeId('edge-in', key),
    source: nodeId('raw', key),
    sourceHandle: 'output',
    target: processNodeId,
    targetHandle: nodeId('in', key),
  }));
  const outputEdges = spec.outputs.map((key) => ({
    id: nodeId('edge-out', key),
    source: processNodeId,
    sourceHandle: nodeId('out', key),
    target: nodeId('finished', key),
    targetHandle: 'input',
  }));
  return {
    schemaVersion: 1,
    nodes: [...rawNodes, processNode, ...outputNodes],
    edges: [...inputEdges, ...outputEdges],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

const WORKFLOW_SPECS = Object.freeze([
  { key: 'W1', anchor: 'A', roots: ['A'], outputs: ['A'], processKey: 'P1', expectedType: 'SINGLE_OUTPUT_PRODUCT' },
  { key: 'W2', anchor: 'B', roots: ['A', 'B'], outputs: ['B'], processKey: 'P1', expectedType: 'SINGLE_OUTPUT_PRODUCT' },
  { key: 'W3', anchor: 'C', roots: ['C'], outputs: ['C', 'D'], processKey: 'P1', expectedType: 'RAW_MATERIAL_SPLIT' },
  { key: 'W4', anchor: 'D', roots: ['A', 'B'], outputs: ['A', 'D'], processKey: 'P1', expectedType: 'JOINT_PRODUCTION' },
  { key: 'W5', anchor: 'X', roots: ['A'], outputs: ['E', 'F'], processKey: 'P1', expectedType: 'RAW_MATERIAL_SPLIT' },
  { key: 'W6', anchor: 'Y', roots: ['B', 'C'], outputs: ['E', 'F'], processKey: 'P2', expectedType: 'JOINT_PRODUCTION' },
  { key: 'W7', anchor: 'L', roots: ['A'], outputs: ['A', 'C', 'D'], processKey: 'P1', expectedType: 'RAW_MATERIAL_SPLIT' },
  { key: 'W8', anchor: 'G', roots: ['A', 'B', 'C', 'D'], outputs: ['G', 'H'], processKey: 'P1', expectedType: 'RAW_MATERIAL_SPLIT', exactlyOne: true },
]);

async function createWorkflows() {
  for (const spec of WORKFLOW_SPECS) {
    const anchor = report.ids.products[spec.anchor];
    const graph = buildWorkflowGraph(spec);
    const draft = await api(
      'PUT',
      `/${FACTORY_ID}/product-process-workflows/${encodeURIComponent(anchor.id)}/draft`,
      graph,
      { mutation: true, label: `save workflow draft ${spec.key}` },
    );
    assertFactory(draft, `save workflow draft ${spec.key}`);
    assert(draft.id != null && draft.lockVersion != null, `workflow ${spec.key}: missing id/lockVersion`);
    const published = await api(
      'POST',
      `/${FACTORY_ID}/product-process-workflows/${encodeURIComponent(anchor.id)}/publish`,
      { lockVersion: draft.lockVersion },
      { mutation: true, label: `publish workflow ${spec.key}` },
    );
    assertFactory(published, `publish workflow ${spec.key}`);
    assert(published.status === 'PUBLISHED', `workflow ${spec.key}: status=${published.status}`);
    const activation = await api(
      'PUT',
      `/${FACTORY_ID}/product-process-workflows/${encodeURIComponent(published.id)}/activation`,
      undefined,
      { mutation: true, label: `activate workflow ${spec.key}` },
    );
    assertFactory(activation, `activate workflow ${spec.key}`);
    assert(activation.enabled === true, `workflow ${spec.key}: activation not enabled`);
    assert(String(activation.activeWorkflowId) === String(published.id), `workflow ${spec.key}: active id mismatch`);
    const activeReadback = await api(
      'GET',
      `/${FACTORY_ID}/product-process-workflows/${encodeURIComponent(anchor.id)}/activation`,
    );
    assertFactory(activeReadback, `read workflow activation ${spec.key}`);
    assert(String(activeReadback.activeWorkflowId) === String(published.id),
      `workflow ${spec.key}: activation readback mismatch`);
    const versionReadback = await api(
      'GET',
      `/${FACTORY_ID}/product-process-workflows/${encodeURIComponent(anchor.id)}/versions/${encodeURIComponent(published.version)}`,
    );
    assertFactory(versionReadback, `read workflow version ${spec.key}`);
    assert(versionReadback.status === 'PUBLISHED', `workflow ${spec.key}: version readback not published`);
    assert(versionReadback.nodes.length === graph.nodes.length && versionReadback.edges.length === graph.edges.length,
      `workflow ${spec.key}: graph size readback mismatch`);
    report.ids.workflows[spec.key] = {
      id: String(published.id),
      anchorProductTypeId: anchor.id,
      anchorProductKey: spec.anchor,
      definitionVersion: published.version,
      roots: spec.roots.map((key) => RAW[key].id),
      outputs: spec.outputs.map((key) => report.ids.products[key].id),
      processKey: spec.processKey,
      expectedType: spec.expectedType,
      exactlyOne: Boolean(spec.exactlyOne),
    };
    report.readbacks.push({
      entity: `workflow.${spec.key}`,
      activationPath: `/${FACTORY_ID}/product-process-workflows/${anchor.id}/activation`,
      versionPath: `/${FACTORY_ID}/product-process-workflows/${anchor.id}/versions/${published.version}`,
      activation: activeReadback,
      version: {
        id: versionReadback.id,
        factoryId: versionReadback.factoryId,
        productTypeId: versionReadback.productTypeId,
        status: versionReadback.status,
        version: versionReadback.version,
        nodeCount: versionReadback.nodes.length,
        edgeCount: versionReadback.edges.length,
      },
    });
  }
}

function workflowIds(candidates) {
  return candidates.map((candidate) => String(candidate.workflowId)).sort();
}

async function resolveCase(spec) {
  const requestedIds = spec.requested.map((key) => report.ids.products[key].id);
  const resolved = await api(
    'POST',
    `/${FACTORY_ID}/product-process-workflows/resolve-by-outputs`,
    { productTypeIds: requestedIds },
  );
  assert(sameSet(resolved.requestedProductTypeIds, requestedIds), `${spec.name}: requested ids readback mismatch`);
  const candidates = Array.isArray(resolved.candidates) ? resolved.candidates : [];
  const expectedIds = spec.expectedWorkflows.map((key) => report.ids.workflows[key].id);
  assert(sameSet(workflowIds(candidates), expectedIds),
    `${spec.name}: candidate ids=${workflowIds(candidates)} expected=${expectedIds}`);
  if (expectedIds.length === 0) {
    assert(resolved.resolutionMode === 'NONE', `${spec.name}: resolutionMode=${resolved.resolutionMode}`);
  }
  for (const candidate of candidates) {
    const workflowKey = spec.expectedWorkflows.find(
      (key) => report.ids.workflows[key].id === String(candidate.workflowId),
    );
    const expected = report.ids.workflows[workflowKey];
    assert(candidate.workflowType === expected.expectedType,
      `${spec.name}/${workflowKey}: workflowType=${candidate.workflowType}`);
    assert(sameSet(candidate.rootInputProductTypeIds, expected.roots),
      `${spec.name}/${workflowKey}: root inputs mismatch`);
    assert(sameSet((candidate.terminalOutputs || []).map((item) => item.productTypeId), expected.outputs),
      `${spec.name}/${workflowKey}: terminal outputs mismatch`);
    assert(candidate.exactMatch === spec.exactMatch,
      `${spec.name}/${workflowKey}: exactMatch=${candidate.exactMatch}`);
    assert(Array.isArray(candidate.processSteps) && candidate.processSteps.length === 1,
      `${spec.name}/${workflowKey}: expected one process step`);
    assert(Array.isArray(candidate.previewNodes) && candidate.previewNodes.length >= 3,
      `${spec.name}/${workflowKey}: preview nodes missing`);
    assert(Array.isArray(candidate.previewEdges) && candidate.previewEdges.length >= 2,
      `${spec.name}/${workflowKey}: preview edges missing`);
  }
  const row = {
    name: spec.name,
    requestedKeys: spec.requested,
    requestedProductTypeIds: requestedIds,
    resolutionMode: resolved.resolutionMode,
    exactMatch: spec.exactMatch,
    candidateWorkflowKeys: spec.expectedWorkflows,
    candidateWorkflowIds: workflowIds(candidates),
    candidates: candidates.map((candidate) => ({
      workflowId: String(candidate.workflowId),
      definitionVersion: candidate.definitionVersion,
      ownerProductTypeId: candidate.ownerProductTypeId,
      ownerProductName: candidate.ownerProductName,
      workflowType: candidate.workflowType,
      rootInputProductTypeIds: candidate.rootInputProductTypeIds,
      terminalOutputProductTypeIds: (candidate.terminalOutputs || []).map((item) => item.productTypeId),
      exactMatch: candidate.exactMatch,
      processSteps: candidate.processSteps,
      previewNodeCount: candidate.previewNodes?.length || 0,
      previewEdgeCount: candidate.previewEdges?.length || 0,
    })),
    status: 'PASS',
  };
  report.resolverCases.push(row);
  console.log(`RESOLVE PASS ${spec.name} -> [${spec.expectedWorkflows.join(',') || 'NONE'}]`);
}

async function verifyResolverMatrix() {
  const cases = [
    { name: '1-to-1 exact beats supersets', requested: ['A'], expectedWorkflows: ['W1'], exactMatch: true },
    { name: 'many-to-1 exact', requested: ['B'], expectedWorkflows: ['W2'], exactMatch: true },
    { name: '1-to-many minimal superset', requested: ['C'], expectedWorkflows: ['W3'], exactMatch: false },
    { name: 'same-layer minimal-superset ambiguity', requested: ['D'], expectedWorkflows: ['W3', 'W4'], exactMatch: false },
    { name: 'duplicate exact multi-output ambiguity', requested: ['E', 'F'], expectedWorkflows: ['W5', 'W6'], exactMatch: true },
    { name: 'many-to-many exact', requested: ['A', 'D'], expectedWorkflows: ['W4'], exactMatch: true },
    { name: '1-to-many exact', requested: ['C', 'D'], expectedWorkflows: ['W3'], exactMatch: true },
    { name: 'larger superset exact', requested: ['A', 'C', 'D'], expectedWorkflows: ['W7'], exactMatch: true },
    { name: 'EXACTLY_ONE roots stay logical single-input', requested: ['G', 'H'], expectedWorkflows: ['W8'], exactMatch: true },
    { name: 'no covering workflow', requested: ['B', 'G'], expectedWorkflows: [], exactMatch: false },
  ];
  for (const spec of cases) await resolveCase(spec);
}

async function main() {
  assert(modeArgs.length === 1, 'choose exactly one of --plan or --apply');
  assert(ADMIN_URL === 'https://admin.cretaceousfuture.com', `unexpected admin origin: ${ADMIN_URL}`);
  assert(FACTORY_ID === 'F006', `unexpected factory: ${FACTORY_ID}`);
  assert(USERNAME === 'f006_admin', `unexpected username: ${USERNAME}`);
  assert(PASSWORD, 'E2E_PASSWORD is required');
  assert(/^[A-Za-z0-9-]+$/.test(RUN_ID), `unsafe E2E_RUN_ID: ${RUN_ID}`);
  if (MODE === '--apply') {
    assert(WRITE_CONFIRM === REQUIRED_CONFIRM,
      `--apply requires E2E_F006_PROD_WRITE_CONFIRM=${REQUIRED_CONFIRM}`);
  }
  await mkdir(OUT_DIR, { recursive: true });
  await login();
  await preflight();
  if (MODE === '--plan') {
    report.status = 'PLAN_PASS_ZERO_WRITES';
    report.completedAt = new Date().toISOString();
    await saveReport();
    console.log(`PLAN PASS zero writes; expected fixture mutations=${EXPECTED_MUTATION_PLAN.fixtureMutationRequests}`);
    console.log(`REPORT ${REPORT_FILE}`);
    return;
  }

  await createWorkProcesses();
  await createProducts();
  await createBoms();
  await createWorkflows();
  assert(report.mutations.length === EXPECTED_MUTATION_PLAN.fixtureMutationRequests,
    `mutation count=${report.mutations.length}, expected=${EXPECTED_MUTATION_PLAN.fixtureMutationRequests}`);
  await verifyResolverMatrix();
  report.status = 'APPLY_PASS';
  report.completedAt = new Date().toISOString();
  await saveReport();
  console.log(`APPLY PASS fixture mutations=${report.mutations.length} resolverCases=${report.resolverCases.length}`);
  console.log(`REPORT ${REPORT_FILE}`);
}

main().catch(async (error) => {
  report.status = 'FAILED';
  report.completedAt = new Date().toISOString();
  report.error = {
    name: error?.name || 'Error',
    message: error?.message || String(error),
    status: error?.status ?? null,
    path: error?.path ?? null,
    response: error?.response ?? null,
    stack: error?.stack || null,
  };
  try { await saveReport(); } catch { /* preserve original failure */ }
  console.error(error?.stack || error);
  console.error(`REPORT ${REPORT_FILE}`);
  process.exitCode = 1;
});
