import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(import.meta.dirname, '../index.vue'),
  'utf8',
);
const editorSource = readFileSync(
  resolve(import.meta.dirname, '../workflow/ProductProcessWorkflowEditor.vue'),
  'utf8',
);

describe('product-process unified Workflow entry', () => {
  it('uses one searchable anchor selector and removes user-selectable owner modes', () => {
    // 契约是"单一可搜索的归属选择器", 不是某一句具体文案. 2026-07-30 按客户反馈
    // (SOP 说「成品归属」但界面无标签, 操作员找不到) 给它补了可见标签并改写了 placeholder,
    // 断言随之改写而非删除: 仍然只有一个选择器, 仍然可拼音搜索.
    //
    // 2026-08-11: 文案再改一次. 归属对象只是**存放位置**, 不是「这张图属于谁/做什么」——
    // 一张原料分流图的归属对象也只能填一个成品, 于是画布顶部「系统研判：原料分流」和这里的
    // 「成品 · 拓扑成品C」在同一屏上打架(用户真机反馈). 契约不变(仍是唯一可搜索选择器),
    // 措辞从"属于"改成"存放在…下".
    expect(source).toContain('归属对象');
    expect(source).toContain('选择本条工艺存放在哪个成品或原料下（支持拼音首字母搜索）');
    expect(source).toContain('无需选择模式，发布时由画布自动识别');
    expect(source).not.toContain('<el-radio-button label="FINISHED">');
    expect(source).not.toContain('<el-radio-button label="RAW">');
  });

  it('only offers finished products and actual raw materials as Workflow owners', () => {
    expect(source).toContain('FINISHED_WORKFLOW_OWNER_CATEGORIES');
    expect(source).not.toMatch(/FINISHED_WORKFLOW_OWNER_CATEGORIES[\s\S]{0,240}SEMI_FINISHED/);
    expect(source).toContain('rawRes.data.filter(isRawMaterialOption)');
    expect(source).toContain('finishedWorkflowOptions.value.find');
    expect(source).not.toContain("return option.productCategory === 'SEMI_FINISHED' ? '半成品' : '成品'");
  });

  it('treats the legacy raw-owner prop only as an initial anchor and never as a topology lock', () => {
    expect(editorSource).toContain('仅用于读取旧 owner-centric 数据时的初始锚点兼容');
    expect(editorSource).toContain(':allow-add-input="true"');
    expect(editorSource).not.toContain('原料模式只能有一个入口原料');
    expect(editorSource).not.toContain('成品模式只能有一个最终成品出口');
    expect(editorSource).not.toContain('isRawOwnerFirstProcess');
  });

  it('shows the derived read-only classification beside activation status', () => {
    expect(editorSource).toContain('data-testid="workflow-system-classification"');
    expect(editorSource).toContain('系统研判：{{ workflowClassificationLabel }}');
    expect(editorSource).toContain("case 'SINGLE_OUTPUT_PRODUCT': return '单产出产品'");
    expect(editorSource).toContain("case 'RAW_MATERIAL_SPLIT': return '原料分流'");
    expect(editorSource).toContain("case 'JOINT_PRODUCTION': return '联产'");
  });

  /**
   * 「本图产出」必须由**画布研判**驱动, 不能由归属对象驱动 —— 用户真机看到的
   * 「研判：原料分流 / 归属对象：成品·拓扑成品C」正是后者当主角造成的。
   * 名字与顺序的正确性由 canvasTopologyMapper.spec.ts 走真实 node.data 验证,
   * 这里只钉住「顶部确实由 terminalOutputNames 渲染, 而不是 selectedProductName」。
   */
  it('renders the canvas-derived outputs at the top instead of the storage anchor', () => {
    expect(editorSource).toContain('data-testid="workflow-terminal-outputs"');
    expect(editorSource).toContain("本图产出：{{ terminalOutputNames.join('、') }}");
    expect(editorSource).toContain('const terminalOutputNames = computed(() => terminalOutputLabels(');
    // 空画布也要有明确说法, 不是把标签整个藏掉让用户以为「没这回事」。
    expect(editorSource).toContain('本图产出：尚未画出终端产出');
  });

  /** 归属对象降为次要信息, 并且当场说清它只是存放位置。 */
  it('demotes the storage anchor to secondary information with an explicit explanation', () => {
    expect(source).toContain('toolbar-field-label--secondary');
    expect(source).toContain('存放位置（归属对象）');
    expect(source).toContain('实际产出以画布顶部「本图产出」为准');
  });

  /**
   * 按产出反查必须走配置侧独立接口(包含语义)。
   * ⛔ 复用计划侧 resolveWorkflowByOutputs 会把「产出集合更大的图」整层丢掉 ——
   * 配置界面丢掉任何一张, 用户就找不到那张图了(spec §4.5 要求两个入口)。
   */
  it('looks up producing workflows through the config-side entry point, not the plan-side one', () => {
    expect(source).toMatch(/findWorkflowsProducing\(factoryId\.value,/);
    // 断的是「代码里有没有真的去调计划侧那条」, 不是「文件里有没有出现这个词」——
    // index.vue 的注释正解释着为什么不用它, 直接扫全文会把自己的解释判成缺陷
    // (本用例前两版分别被 toContain 和 toMatch 假红过)。先剥掉注释再断言。
    const code = source
      .replace(/<!--[\s\S]*?-->/g, '')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/(^|[^:])\/\/.*$/gm, '$1');
    expect(code).toMatch(/findWorkflowsProducing\(/);
    expect(code).not.toMatch(/resolveWorkflowByOutputs/);
    expect(source).toContain('data-testid="produced-by-other-workflows"');
    expect(source).toContain('data-testid="no-workflow-produces-selection"');
    expect(source).toContain('目前没有任何已启用的工艺图产出这个成品');
  });
});
