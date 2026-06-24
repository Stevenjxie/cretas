#!/usr/bin/env node
/**
 * Generator for f006_finance_demo_seed.sql — realistic COGS / expense / cash vouchers
 * for the F006 (六膳门 test tenant) demo so the voucher-based 财务报表 (三大报表) shows
 * a realistic income statement (cost + expense, not revenue-only) and a non-zero cash
 * flow statement. All vouchers are balanced double-entry → balance sheet stays balanced.
 *
 * Existing F006 ledger (revenue-only): AR 1122 ≈ 9.81M, revenue 6001 = 9,710,036.42,
 * 库存商品 1405 ≈ 42k. This seed adds, per active month (proportional to that month's
 * revenue): inventory purchase, COGS recognition, period expenses, cash receipt (from AR),
 * supplier payment. Tagged source_business_type='DEMO_SEED' + deterministic ids → idempotent
 * (re-run safe via DELETE-first). NOT a Flyway migration; applied manually to F006 only.
 *
 * Output: prints the SQL to stdout. `node f006_finance_demo_seed.sql.gen.mjs > f006_finance_demo_seed.sql`
 */

const FACTORY = 'F006';
const CREATED_BY = 1309; // f006_admin

// Per-month revenue (from income statement, matches existing 6001 recognition)
const MONTHS = [
  { ym: '202604', date: '2026-04-15', revenue: 14000.00 },
  { ym: '202605', date: '2026-05-15', revenue: 6123550.00 },
  { ym: '202606', date: '2026-06-15', revenue: 3572486.42 },
];

const r2 = (n) => Math.round(n * 100) / 100;
const f = (n) => n.toFixed(2);
const sq = (s) => s.replace(/'/g, "''");

const ACC = {
  bank: ['1002', '银行存款'],
  ar: ['1122', '应收账款'],
  inv: ['1405', '库存商品'],
  ap: ['2202', '应付账款'],
  cogs: ['6401', '主营业务成本'],
  sell: ['6601', '销售费用'],
  admin: ['6602', '管理费用'],
};

const vouchers = []; // {ym, seq, type, date, desc, entries:[{acc, debit, credit, note}]}

for (const m of MONTHS) {
  const R = m.revenue;
  const cogs = r2(R * 0.62);
  const sell = r2(R * 0.08);
  const admin = r2(R * 0.06);
  const expenseTotal = r2(sell + admin);
  const receipt = r2(R * 0.85);   // collect 85% of this month's revenue from AR
  const payment = r2(cogs * 0.55); // pay 55% of purchases to suppliers

  let seq = 0;
  const v = (type, desc, entries) => vouchers.push({ ym: m.ym, seq: ++seq, type, date: m.date, desc, entries });

  // 1) 采购入库: 借 库存商品 / 贷 应付账款
  v('INVENTORY_TRANSFER', `${m.ym} 原料采购入库`, [
    { acc: 'inv', debit: cogs, note: '采购入库' },
    { acc: 'ap', credit: cogs, note: '应付供应商' },
  ]);
  // 2) 结转成本: 借 主营业务成本 / 贷 库存商品
  v('EXPENSE', `${m.ym} 结转销售成本`, [
    { acc: 'cogs', debit: cogs, note: '主营业务成本' },
    { acc: 'inv', credit: cogs, note: '结转库存' },
  ]);
  // 3) 期间费用: 借 销售费用 + 管理费用 / 贷 银行存款
  v('EXPENSE', `${m.ym} 销售及管理费用`, [
    { acc: 'sell', debit: sell, note: '销售费用' },
    { acc: 'admin', debit: admin, note: '管理费用' },
    { acc: 'bank', credit: expenseTotal, note: '支付费用' },
  ]);
  // 4) 收款: 借 银行存款 / 贷 应收账款
  v('SALES_RECEIPT', `${m.ym} 客户回款`, [
    { acc: 'bank', debit: receipt, note: '收到货款' },
    { acc: 'ar', credit: receipt, note: '冲减应收' },
  ]);
  // 5) 付款: 借 应付账款 / 贷 银行存款
  v('PURCHASE_PAYMENT', `${m.ym} 支付供应商货款`, [
    { acc: 'ap', debit: payment, note: '支付应付' },
    { acc: 'bank', credit: payment, note: '银行付款' },
  ]);
}

// ---- emit SQL ----
const out = [];
out.push('-- F006 (六膳门 test tenant) finance demo seed — COGS/expense/cash vouchers');
out.push('-- Idempotent: DELETE-first by source_business_type=DEMO_SEED. F006 ONLY. Balanced double-entry.');
out.push('BEGIN;');
out.push(`DELETE FROM voucher_entries WHERE voucher_id IN (SELECT id FROM vouchers WHERE factory_id='${FACTORY}' AND source_business_type='DEMO_SEED');`);
out.push(`DELETE FROM vouchers WHERE factory_id='${FACTORY}' AND source_business_type='DEMO_SEED';`);

for (const v of vouchers) {
  const totalDebit = r2(v.entries.reduce((s, e) => s + (e.debit || 0), 0));
  const totalCredit = r2(v.entries.reduce((s, e) => s + (e.credit || 0), 0));
  if (f(totalDebit) !== f(totalCredit)) {
    throw new Error(`UNBALANCED voucher ${v.ym}-${v.seq}: D=${totalDebit} C=${totalCredit}`);
  }
  const vid = `seed-v-${v.ym}-${v.seq}`;
  const vno = `SEED-${v.ym}-${String(v.seq).padStart(2, '0')}`;
  const sbid = `demo-fin-${v.ym}-${v.seq}`;
  out.push(
    `INSERT INTO vouchers (id, factory_id, voucher_number, voucher_type, voucher_date, ` +
    `source_business_type, source_business_id, total_debit, total_credit, status, created_by, description, created_at, updated_at) VALUES (` +
    `'${vid}','${FACTORY}','${vno}','${v.type}','${v.date}','DEMO_SEED','${sbid}',${f(totalDebit)},${f(totalCredit)},'POSTED',${CREATED_BY},'${sq(v.desc)}',NOW(),NOW());`
  );
  v.entries.forEach((e, i) => {
    const [code, name] = ACC[e.acc];
    const eid = `seed-e-${v.ym}-${v.seq}-${i + 1}`;
    const debit = e.debit != null ? f(e.debit) : 'NULL';
    const credit = e.credit != null ? f(e.credit) : 'NULL';
    out.push(
      `INSERT INTO voucher_entries (id, voucher_id, line_no, subject_code, subject_name, description, debit, credit, created_at, updated_at) VALUES (` +
      `'${eid}','${vid}',${i + 1},'${code}','${sq(name)}','${sq(e.note)}',${debit},${credit},NOW(),NOW());`
    );
  });
}
out.push('COMMIT;');
console.log(out.join('\n'));
