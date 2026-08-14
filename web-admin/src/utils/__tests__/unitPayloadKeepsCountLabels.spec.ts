import { readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 落库/回传侧不许用会吞掉「个 / 只」的那支归一。
 *
 * <h3>为什么需要这条</h3>
 * 后端 #2628 起把 件/个/只 拆成**三个独立单位**，`个`/`只` 没有任何英文别名
 * （`pcs/pc/piece/pieces` 只是「件」的别名）。前端 `canonicalUnitCode` 仍把三者
 * 全折成 `pcs`，于是档案单位是「个」的物料一旦被提交，后端判不等价直接拒。
 *
 * 2026-08-14 真机复现：给成品加包材 `2030真空袋（16s）`（档案单位「个」），
 * 界面「档案单位」显示成「件」，提交 `pcs`，后端回
 * 「包材单位必须继承物料档案，不能在 BOM 中另选／请一次补齐：单位：原样回传物料档案的「个」」——
 * 而那个字段是只读的，**用户没有任何办法满足它**。该厂 30 个包材、隔壁厂 20 个，
 * 单位都是「个」。
 *
 * 仓里本来就有正确的那支 `canonicalUnitCodeKeepingCount`（注释写着「与落库/展示同一份归一」），
 * 销售订单一直在用；坏在**只改了一半调用点**。这条闸把剩下那半钉住。
 *
 * <h3>判据</h3>
 * 扫源码：`unit`/`quantityUnit`/`priceUnit` 这类字段被赋值时，右手边不许是
 * `canonicalUnitCode(...)`。比较、查表、选项过滤不受限制 —— 只管**赋给单位字段**的那一刻。
 */

const SRC = resolve(__dirname, '..', '..');

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name: string) => {
    const full = resolve(dir, name);
    if (statSync(full).isDirectory()) {
      return name === '__tests__' || name === 'node_modules' ? [] : sourceFiles(full);
    }
    return /\.(vue|ts)$/.test(name) ? [full] : [];
  });
}

/** `unit: canonicalUnitCode(` / `quantityUnit: canonicalUnitCode(` … —— 赋给单位字段的那一刻。 */
const OFFENDING = /\b(unit|quantityUnit|priceUnit|packageUnit|baseUnit|purchaseUnit|outputUnit|inputUnit)\s*:\s*canonicalUnitCode\s*\(/g;

describe('落库单位归一: 不得吞掉「个 / 只」', () => {
  const files = sourceFiles(SRC);

  it('阳性对照 —— 真的扫到了源码 (扫到 0 个文件会让下面那条恒绿)', () => {
    expect(files.length, '一个源码文件都没扫到, 仪器坏了').toBeGreaterThan(200);
    const joined = files.map((f) => readFileSync(f, 'utf8')).join('\n');
    expect(joined, '扫到的内容里连 canonicalUnitCodeKeepingCount 都没有, 说明扫错了地方')
      .toContain('canonicalUnitCodeKeepingCount');
  });

  it('没有把 canonicalUnitCode 的结果直接赋给单位字段', () => {
    const offenders: string[] = [];
    for (const file of files) {
      const source = readFileSync(file, 'utf8');
      for (const m of source.matchAll(OFFENDING)) {
        const line = source.slice(0, m.index ?? 0).split('\n').length;
        offenders.push(`${file.replace(/\\/g, '/').split('/src/')[1]}:${line} → ${m[0].trim()}`);
      }
    }
    expect(offenders, [
      '这些地方把 canonicalUnitCode 的结果当单位落库/回传, 会把「个 / 只」折成 pcs。',
      '改用 canonicalUnitCodeKeepingCount —— 它是本仓指定的落库归一, 依然是规范码,',
      '只是不合并 只/个/件 (#1976: 一只鸡不是一件包材)。',
      '', ...offenders,
    ].join('\n')).toEqual([]);
  });
});
