import { describe, it, expect } from 'vitest';
import { evaluateWorkflowConnection } from '../workflowModel';

describe('evaluateWorkflowConnection (#8 拖拽连线类型规则)', () => {
  it('物料 → 工序 合法 (投入/合流)', () => {
    for (const m of ['RAW_MATERIAL', 'SEMI_FINISHED', 'FINISHED_GOOD']) {
      const r = evaluateWorkflowConnection(m, 'PROCESS');
      expect(r.valid).toBe(true);
      expect(r.direction).toBe('MATERIAL_TO_PROCESS');
    }
  });

  it('工序 → 半成品/成品 合法 (产出)', () => {
    for (const m of ['SEMI_FINISHED', 'FINISHED_GOOD']) {
      const r = evaluateWorkflowConnection('PROCESS', m);
      expect(r.valid).toBe(true);
      expect(r.direction).toBe('PROCESS_TO_MATERIAL');
    }
  });

  it('工序 → 原料 非法 (原料不能作产出)', () => {
    const r = evaluateWorkflowConnection('PROCESS', 'RAW_MATERIAL');
    expect(r.valid).toBe(false);
    expect(r.direction).toBeNull();
  });

  it('物料 ↔ 物料 非法', () => {
    expect(evaluateWorkflowConnection('SEMI_FINISHED', 'SEMI_FINISHED').valid).toBe(false);
    expect(evaluateWorkflowConnection('RAW_MATERIAL', 'FINISHED_GOOD').valid).toBe(false);
  });

  it('工序 ↔ 工序 非法', () => {
    expect(evaluateWorkflowConnection('PROCESS', 'PROCESS').valid).toBe(false);
  });

  it('自环非法', () => {
    expect(evaluateWorkflowConnection('SEMI_FINISHED', 'PROCESS', true).valid).toBe(false);
  });

  it('空 kind 非法 (防御)', () => {
    expect(evaluateWorkflowConnection('', 'PROCESS').valid).toBe(false);
    expect(evaluateWorkflowConnection('PROCESS', '').valid).toBe(false);
  });
});
