import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 跨单位出成率的契约。
 *
 * 用源码断言而不是挂载组件: 出成率与补录入口位于卡片模式的产出块内，要驱动它需要
 * 同时具备 workflow 端口、已新增行、投入总量与产出数量四者，而投入块本身还有
 * 首道/非首道、自动总量/上游来源等多条渲染分支。搭这套脚手架的成本远高于它能验到的
 * 东西，且脚手架一旦与真实分支不符，测试会以「通过」的姿态掩盖问题。
 * 项目已有同类 source-contract 用例 (oaProcurementContract / personalOaWorkbench)，
 * 此处沿用同一手法。
 */
const source = readFileSync(
  resolve(process.cwd(), 'src/views/production/components/processSheet/ProcessDataTable.vue'),
  'utf8',
);

/** 产出块已抽成共享子组件 (卡片/表格两种视图共用), 说明条与「去设置」按钮落在这里。 */
const outputTableSource = readFileSync(
  resolve(process.cwd(), 'src/views/production/components/processSheet/ProcessOutputTable.vue'),
  'utf8',
);

describe('cross-unit yield contract', () => {
  it('算不出出成率时说明原因，而不是只留一个「—」', () => {
    expect(source).toContain('function outputLineYieldBlocker');
    // 文案要点名物料与两端单位，让人知道该去补哪一个
    expect(source).toContain('需要先设置「${line.materialName}」的每${outputUnit}重量');
    // 父组件把结果算进视图模型, 子组件负责显示 —— 两种视图因此不可能只有一边有说明条
    expect(source).toContain('blocker: outputLineYieldBlocker(row, line),');
    expect(outputTableSource).toContain('v-if="view.blocker"');
    expect(outputTableSource).toContain('{{ view.blocker }}');
  });

  it('同单位不提示 —— 只有两端单位不同才需要重量桥', () => {
    expect(source).toContain('if (!inputUnit || !outputUnit || inputUnit === outputUnit) return null;');
  });

  it('投入还没录时不提示 —— 那是「还没填完」不是单位问题', () => {
    expect(source).toContain('const inputFilled = reportingInputFacts(row).some((fact) => fact.quantity > 0);');
    expect(source).toContain('if (!inputFilled) return null;');
  });

  it('「去设置」就地弹窗，只改每单位重量这一个字段', () => {
    // 按钮在子组件里, 事件冒到父组件打开弹窗 —— 两段都要在, 少一段入口就断了
    expect(outputTableSource).toContain(`emit('open-spec', view.line)`);
    expect(source).toContain('@open-spec="openSpecDialog"');
    expect(source).toContain('v-model="specDialog.visible"');
    // 只提交 gramsPerUnit，不带整份产品表单
    expect(source).toContain('gramsPerUnit: draft.gramsPerUnit,');
    expect(source).toContain('/product-types/${draft.productTypeId}`');
  });

  it('保存后就地回写并重算，不要求刷新页面', () => {
    expect(source).toContain('row.multiOutputs?.forEach((line: MultiOutputLine) => {');
    expect(source).toContain('line.gramsPerUnit = draft.gramsPerUnit;');
  });
});
