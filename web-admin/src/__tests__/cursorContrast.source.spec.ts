import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const styleSource = readFileSync(resolve(__dirname, '..', 'style.css'), 'utf8');
const editorSource = readFileSync(
  resolve(__dirname, '..', 'views', 'system', 'product-processes', 'workflow',
    'ProductProcessWorkflowEditor.vue'), 'utf8');

/**
 * 客户 2026-07-20 (Sheet 第 13 行):「背景为白色、鼠标指针为白色, 对比度非常低, 找不到指针。
 * 自定义系统鼠标颜色与任务框内鼠标无关联。具体场景为: 标签栏宽度调整时、WorkFlow 编辑时。」
 *
 * 只有一半是网页能改的, 断言分别钉住这两半, 并钉住「另一半改不了」这个事实 ——
 * 免得后来人以为漏做了而去写覆盖系统光标的代码。
 */
describe('白底上找不到指针 (Sheet 第 13 行)', () => {
  it('WorkFlow 画布的十字光标自带白色描边, 且保留标准 crosshair 作回退', () => {
    expect(styleSource).toContain('--cretas-cursor-crosshair:');
    // 描边(白) + 主体(深) 两层都要在, 少一层就还是单色光标
    expect(styleSource).toContain('stroke="%23ffffff"');
    expect(styleSource).toContain('stroke="%23111111"');
    // data URI 不被支持时必须回落到标准光标, 不能变成 auto
    expect(styleSource).toMatch(/--cretas-cursor-crosshair:[\s\S]*?crosshair;/);
  });

  it('画布两处 is-connecting / is-batch-selecting 都换成了带描边的那个变量', () => {
    expect(styleSource).toContain('.canvas-shell.is-connecting');
    expect(styleSource).toContain('.workflow-canvas.is-batch-selecting .vue-flow__pane');
    const uses = styleSource.match(/cursor: var\(--cretas-cursor-crosshair\)/g) || [];
    expect(uses.length).toBeGreaterThanOrEqual(1);
    // 编辑器组件里原来那两条裸 crosshair 仍在(作用域样式), 全局这条优先级更高;
    // 这里只确认编辑器没有再引入第三处别的光标定义
    expect((editorSource.match(/cursor: crosshair/g) || []).length).toBeLessThanOrEqual(2);
  });

  it('列宽拖动指示线做成高对比 —— 指针看不见时至少看得见落点', () => {
    expect(styleSource).toContain('.el-table__column-resize-proxy');
    expect(styleSource).toMatch(/\.el-table__column-resize-proxy\s*\{[^}]*border-left: 2px/);
    expect(styleSource).toMatch(/\.el-table__column-resize-proxy\s*\{[^}]*box-shadow/);
  });

  it('注释写明列宽那半的光标是系统级、网页覆盖不了 —— 别让后来人再去试', () => {
    expect(styleSource).toContain('网页覆盖不了系统配色');
  });
});
