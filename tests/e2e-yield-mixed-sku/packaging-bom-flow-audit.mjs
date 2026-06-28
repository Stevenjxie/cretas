/**
 * Packaging BOM flow + calc verification (F006, live prod).
 *
 * Verifies 张权's packaging model end-to-end as CONFIG (no code):
 *   包材按数量(件/个)记 + BOM 配「固定值(配比) + 损耗(出成率)」
 *   → 领料/物料建议自动算: 用量 = 计划数 × 配比 ÷ (出成率/100), 单位=件, 无"无法换算".
 *
 * Reads the REAL BOM (table bom_items via GET /bom/items/{productTypeId} — the one the
 * material-advisory actually consumes), creates a plan, reads the advisory, and asserts the
 * computed packaging requirement equals the first-principles oracle exactly, in 件, no 无法换算.
 *
 * NOTE: F006's packaging line was already configured (吸塑盒 配比=1, 损耗 via yieldRate, unit=pcs).
 * The "无法换算" advisory was caused by mis-tagged kg WIP batches (cleaned separately), not config.
 *
 * Env: E2E_USERNAME E2E_PASSWORD [E2E_ADMIN_URL E2E_FACTORY_ID]
 */
const BASE = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086';
const FACTORY = process.env.E2E_FACTORY_ID || 'F006';
const U = process.env.E2E_USERNAME, P = process.env.E2E_PASSWORD;
if (!U || !P) { console.error('E2E_USERNAME/E2E_PASSWORD required'); process.exit(1); }

const PRODUCT_ID = 'c2974690-4ac7-4c17-9ad4-5ee5b12bb26c'; // 叮咚好食光纸片牛腱肉 80g (gramsPerUnit=80)
const PKG_ID = 'RMT_1777441647310';   // 吸塑盒2014-3.5
const PLANNED = 1000;
const COUNT_UNITS = ['个', '件', '只', 'pcs', 'pc'];

const asserts = [];
function ok(p, label, d = {}) { asserts.push({ pass: !!p, label, ...d }); console.log(`${p ? 'PASS' : 'FAIL'}  ${label}  ${JSON.stringify(d)}`); return !!p; }
function approx(a, e, tol, label) { const p = a != null && Math.abs(a - e) <= tol; asserts.push({ pass: p, label, actual: a, expected: e }); console.log(`${p ? 'PASS' : 'FAIL'}  ${label}  actual=${a} expected=${e}`); return p; }
const arr = (d) => Array.isArray(d) ? d : (d && (d.content || d.records || d.warnings || d.items)) || [];
const today = (o = 0) => new Date(Date.now() + 8 * 3600e3 + o * 86400e3).toISOString().slice(0, 10);

async function main() {
  const lr = await fetch(`${BASE}/api/mobile/auth/unified-login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: U, password: P, factoryId: FACTORY }) });
  const tok = (await lr.json()).data.token;
  const H = { Authorization: `Bearer ${tok}`, 'Content-Type': 'application/json' };
  async function api(m, p, b) {
    const r = await fetch(`${BASE}/api/mobile${p}`, { method: m, headers: H, body: b == null ? undefined : JSON.stringify(b) });
    const t = await r.text(); let j = null; try { j = t ? JSON.parse(t) : null; } catch { j = { raw: t }; }
    if (!r.ok || (j && j.success === false)) throw new Error(`${m} ${p} -> ${r.status}: ${(j && j.message) || t.slice(0, 300)}`);
    return j && 'data' in j ? j.data : j;
  }

  // ---- 1. read the REAL BOM (bom_items) packaging line ----
  const bomItems = arr(await api('GET', `/${FACTORY}/bom/items/${PRODUCT_ID}`));
  const pkg = bomItems.find((i) => String(i.materialTypeId) === PKG_ID);
  ok(!!pkg, 'BOM(bom_items) 含吸塑盒包材行', { found: !!pkg });
  if (!pkg) { dump(); return; }
  ok(pkg.materialCategory === 'PACKAGING', '吸塑盒行分类=包材(PACKAGING)', { cat: pkg.materialCategory });
  ok(COUNT_UNITS.includes((pkg.unit || '').trim().toLowerCase()) || COUNT_UNITS.includes((pkg.unit || '').trim()), '吸塑盒 BOM 单位=计数单位', { unit: pkg.unit });
  const ratio = Number(pkg.standardQuantity);       // 固定值/配比
  const yieldR = Number(pkg.yieldRate);             // 出成率(损耗)
  ok(ratio > 0, '吸塑盒配比(固定值)已配', { ratio });
  ok(yieldR > 0, '吸塑盒损耗(出成率)已配', { yieldRate: yieldR });

  // ---- 2. product 净含量(gramsPerUnit) — for kg<->盒 折算 (产品侧, 独立于包材) ----
  const prod = await api('GET', `/${FACTORY}/product-types/${PRODUCT_ID}`).catch(() => null);
  ok(prod && Number(prod.gramsPerUnit) > 0, '产品每盒净含量(gramsPerUnit)已配(气调报kg→盒数)', { gramsPerUnit: prod && prod.gramsPerUnit });

  // ---- 3. first-principles oracle ----
  // 包材为非主料: perUnit = 配比 ÷ (出成率/100); 需求 = 计划数 × perUnit
  const expectedReq = PLANNED * ratio / (yieldR / 100);
  console.log(`oracle: ${PLANNED} × ${ratio} ÷ (${yieldR}/100) = ${expectedReq}`);

  // ---- 4. create plan, read advisory, verify ----
  const plan = await api('POST', `/${FACTORY}/production-plans`, {
    productTypeId: PRODUCT_ID, plannedQuantity: PLANNED, plannedDate: today(0), expectedCompletionDate: today(2),
    customerOrderNumber: `PKG-AUDIT-${Date.now()}`, priority: 5, sourceType: 'MANUAL', notes: 'packaging flow audit',
  });
  const planId = String(plan.id || plan.planId);
  ok(!!planId, '生产计划创建成功', { planNumber: plan.planNumber });

  const adv = await api('GET', `/${FACTORY}/production-plans/${planId}/material-advisory`);
  const warnings = arr(adv.warnings || adv);
  const pkgAdv = warnings.find((i) => String(i.materialTypeId) === PKG_ID || (i.materialName || '').includes('吸塑盒'));
  ok(!!pkgAdv, 'advisory 含吸塑盒行(可读取计算值)', { found: !!pkgAdv, warningsCount: warnings.length });
  if (pkgAdv) {
    ok(!String(pkgAdv.message || '').includes('无法换算'), '吸塑盒不再报"无法换算"(pcs↔件 归一相通)', { msg: pkgAdv.message });
    ok(COUNT_UNITS.includes((pkgAdv.unit || '').trim()), '吸塑盒 advisory 单位=计数单位(件)', { unit: pkgAdv.unit });
    approx(Number(pkgAdv.requiredQuantity), expectedReq, 0.001, '吸塑盒需求量 = 计划 × 配比 ÷ 出成率 (精确)');
  }
  ok(!warnings.some((i) => String(i.message || '').includes('无法换算')), 'advisory 整体无"无法换算"告警');

  function dump() {}
  const fails = asserts.filter((a) => !a.pass);
  console.log(`\n=== ${fails.length === 0 ? 'PASS' : 'FAIL'} ===  ${asserts.length} assertions, ${fails.length} failed`);
  console.log(JSON.stringify({ planNumber: plan.planNumber, ratio, yieldRate: yieldR, expectedReq, failures: fails.map((f) => f.label) }, null, 2));
  if (fails.length) process.exitCode = 1;
}
main().catch((e) => { console.error('ERROR:', e.message); process.exitCode = 1; });
