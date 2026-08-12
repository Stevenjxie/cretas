import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 解析失败时不许显示包装层的「操作成功」。
 *
 * <h2>🔴 2026-08-13 真机抓到(LIUSHANMEN 生产)</h2>
 * AI 录入把两行物料解析得完全正确, 界面只回一句 **「操作成功」**, 预览卡不出。
 * 后端返回体里 `data.success === false`(模型自报, 已在服务端修掉), `data.message` 为 null,
 * 于是前端这条兜底链:
 *
 * ```ts
 * say(result?.message || response.message || 'AI 解析失败…')
 * //                     ^^^^^^^^^^^^^^^^ ApiResponse 包装层, 这个接口恒为「操作成功」
 * ```
 *
 * 把**失败**渲染成了「成功」二字 —— 用户看到"成功"却拿不到任何结果, 排查的人
 * 也不会想到去点开一个写着成功的气泡。这正撞在项目「禁止降级处理 —— 不返回假数据,
 * 明确显示错误」那条上。
 *
 * <h2>判据</h2>
 * **包装层的 message 描述的是 HTTP 调用本身, 与业务成败无关**, 不能进失败文案链。
 * 业务失败要么有自己的 `result.message`, 要么就老实说「解析失败」。
 */
const source = readFileSync(
  resolve(__dirname, '..', 'useAiChat.ts'),
  'utf8',
);

/** 剥掉注释 —— 注释里引用了被修掉的旧写法, 不剥会自己命中自己。 */
const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

/** 失败分支: 从 `if (!response.success` 到该 if 的 `return;`。 */
function failureBranch(): string {
  const start = code.indexOf('if (!response.success');
  expect(start, '找不到失败分支 —— 结构变了, 这条闸需要重写').toBeGreaterThan(-1);
  const end = code.indexOf('return;', start);
  expect(end).toBeGreaterThan(start);
  return code.slice(start, end);
}

describe('AI 录入 · 解析失败的文案', () => {
  it('① 失败文案不许取 ApiResponse 包装层的 message(它恒为「操作成功」)', () => {
    expect(failureBranch(), 'response.message 是包装层文案, 与业务成败无关')
      .not.toContain('response.message');
  });

  it('② 仍然优先显示后端给的业务 message(真失败带着它)', () => {
    expect(failureBranch()).toContain('result?.message');
  });

  it('③ 兜底文案明确说失败, 不含「成功」二字', () => {
    const branch = failureBranch();
    expect(branch).toContain('AI 解析失败');
    expect(branch, '失败分支里不该出现「成功」').not.toContain('成功');
  });

  /**
   * ⚠️ 反向断言: 证明这个失败分支确实还在被走到。
   * 少了这条, 上面三条在「分支被整段删掉」时会一起变成恒真式 —— 那时
   * failureBranch() 会先红在自己的 expect 上, 但那是 fail-fast, 不是断言意图。
   */
  it('失败分支仍然由三种情况触发(整段被删就应当红)', () => {
    const branch = failureBranch();
    expect(branch).toContain('!response.success');
    expect(branch).toContain('!result');
    expect(branch).toContain('result.success === false');
  });
});
