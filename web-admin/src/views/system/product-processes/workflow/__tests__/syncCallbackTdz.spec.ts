import { describe, expect, it } from 'vitest';
import { resolve } from 'node:path';
import { scanForSyncCallbackTdz } from '../../../../../../scripts/tdz-scan.mjs';

/**
 * 🔴 2026-08-08 真机事故的闸。
 *
 * `loadBomOverlayData` 里 `packagingBindingsByOutput` 的 `const` 声明写在了引用它的
 * forEach **后面**。引用在闭包里 ⇒ TypeScript 不报 TS2448(编译器假定闭包延后执行),
 * 而 forEach 是同步的 ⇒ 运行期 `ReferenceError: Cannot access ... before initialization`,
 * 又被函数末尾的 catch 吞成一行 console.error。
 *
 * 后果远大于"少了包材数值": **整个 BOM 浮层加载全灭** —— 辅料/包材 cell 全空、
 * hydrate 从不执行、`materialBindings` 从没进过工艺定义, 于是「改克数产生新工艺版本」
 * 这条已经写对了的链路, 在真机上恒定是断的。web-admin 没有 ESLint, 这一类此前
 * 结构性地无人看守。
 *
 * ## 为什么闸盯的是「同步回调」而不是「声明之前引用」
 * 「声明之前引用」本身极常见且合法(事件处理器里写后面声明的 let、参数遮蔽同名变量)。
 * 全仓无差别扫会出一堆噪声, 闸迟早被关掉 —— 判据要按**行为**收窄到
 * 「一定会在声明之前执行」的那一种。全仓实测: 收窄前 12 条(11 条误报),
 * 收窄后精确命中 1 条真缺陷。
 */
const SRC = resolve(process.cwd(), 'src');

describe('同步回调里不得引用后面才声明的块作用域变量 (运行期 TDZ)', () => {
  it('web-admin/src 全仓零命中', { timeout: 120_000 }, () => {
    const findings = scanForSyncCallbackTdz(SRC) as Array<{
      file: string; name: string; method: string; usedLine: number; declLine: number;
    }>;
    // 报告要能直接照着去改: 文件 + 变量名 + 迭代方法 + 两个行号。
    const readable = findings.map(
      (f) => `${f.file}:${f.usedLine} 引用了 ${f.declLine} 行才声明的 \`${f.name}\` (在 .${f.method}() 回调里)`,
    );
    expect(readable, readable.join('\n')).toEqual([]);
  });
});
