import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');

/** 取一个 function 的完整源码 —— 切到下一个顶格 `}` 为止, 别按固定字符数截。 */
function fnBody(name: string): string {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `找不到 function ${name}`).toBeGreaterThan(-1);
  const end = source.indexOf('\n}\n', start);
  expect(end, `function ${name} 没有顶格收尾`).toBeGreaterThan(start);
  return source.slice(start, end);
}

/** 剥注释 —— 否则闸会红在解释它自己的那段注释上 (本仓同形已踩过三次)。 */
function stripComments(s: string): string {
  return s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

/**
 * 「原料参考」这一列的文案 **以后端为准**。
 *
 * <h3>🔴 为什么有这道闸 (2026-08-18 两端走查实测)</h3>
 *
 * F006 计划 PLAN-1786954657305 (黄油鸡 80 盒)，4 个原料各 200kg **全在原料仓、生产仓 0**：
 *
 * ```
 * 计划列表「原料参考」 → 「原料库存参考: 暂无缺料预警」  (全厂口径)
 * 点进逐道录入         → 四行全是「0kg / 原料仓另有 200kg，待调拨入生产仓」 (生产仓口径)
 * ```
 *
 * 后端已把那句话改成带口径的三态（全厂够且生产仓已备料 / 全厂够但没核对生产仓 / 有预警），
 * 并新增 `NOT_IN_WORKSHOP` 这一类预警。**而这个函数原来在 `!hasWarning` 分支里
 * 硬编码返回 `'原料库存参考: 暂无缺料预警'`** —— 后端说什么都不算数，改动在这一列上不可见。
 *
 * 形态 D：同一句话两份，一定会漂。抽不成一份（后端拼、前端显示）就得钉住
 * **一份是源，另一份只负责显示**。
 *
 * ⚠️ 这里守的是「谁说了算」，不是具体措辞 —— 措辞会改，源不能变。
 */
describe('计划列表「原料参考」: 文案以后端 message 为准', () => {
  it('阳性对照: 函数找得到且非空 (否则下面全是恒真)', () => {
    const body = fnBody('getPlanAdvisorySummary');
    expect(body.length).toBeGreaterThan(40);
    expect(body).toContain('advisory');
  });

  it('🔴 必须返回后端的 message', () => {
    const body = stripComments(fnBody('getPlanAdvisorySummary'));
    expect(body, '没有读后端 message, 这一列显示的是前端自己编的话').toMatch(/advisory\.message/);
  });

  it('🔴 不许在 !hasWarning 分支里直接 return 一个写死的串', () => {
    const body = stripComments(fnBody('getPlanAdvisorySummary'));
    expect(
      body,
      "`if (!advisory.hasWarning) return '…'` 会把后端的口径说明整段盖掉",
    ).not.toMatch(/!\s*advisory\.hasWarning\s*\)\s*return\s*['"`]/);
  });

  it('写死串只能作为「后端没给 message」的兜底, 必须挂在 || 之后', () => {
    const body = stripComments(fnBody('getPlanAdvisorySummary'));
    const literal = body.indexOf('原料库存参考');
    if (literal === -1) return;                         // 完全不写死也合格
    const msgRef = body.indexOf('advisory.message');
    expect(msgRef, '写死串出现在 advisory.message 之前 ⇒ 它是主路径不是兜底')
      .toBeGreaterThan(-1);
    expect(msgRef).toBeLessThan(literal);
  });
});
