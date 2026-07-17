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
    expect(source).toContain('选择关联的原料或成品（支持拼音首字母搜索）');
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
});
