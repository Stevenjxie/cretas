import { describe, expect, it } from 'vitest';
import {
  formatFeedPlaceholder,
  formatPlannedOutput,
  formatProcessOutput,
  formatSourceFeedSummary,
  resolveProcessSheetUnits,
  resolveWorkflowProcessSheetUnits,
  withProcessSheetUnits,
} from '../processSheetUnits';

describe('processSheetUnits', () => {
  it('uses the product-process override as input and the process output unit as output', () => {
    expect(resolveProcessSheetUnits({ unitOverride: '只', defaultUnit: 'kg', defaultOutputUnit: '袋' }))
      .toEqual({ inputUnit: '只', outputUnit: '袋' });
  });

  it('uses workflow port units as the sole authority', () => {
    expect(resolveWorkflowProcessSheetUnits({
      processName: '装件',
      inputs: [{ workflowPortId: 'IN-1', unit: ' g ' }],
      output: { workflowPortId: 'OUT-1', unit: ' 件 ' },
    })).toEqual({ inputUnit: 'g', outputUnit: '件' });
  });

  it.each([
    {
      name: 'missing input port unit',
      config: { processName: '清洗', inputs: [{ workflowPortId: 'IN-1', unit: null }], output: { workflowPortId: 'OUT-1', unit: '件' } },
      message: '投入端口',
    },
    {
      name: 'missing output port unit',
      config: { processName: '清洗', inputs: [{ workflowPortId: 'IN-1', unit: 'g' }], output: { workflowPortId: 'OUT-1', unit: ' ' } },
      message: '产出端口',
    },
    {
      name: 'missing secondary output port unit',
      config: {
        processName: '分装',
        inputs: [{ workflowPortId: 'IN-1', unit: 'g' }],
        output: { workflowPortId: 'OUT-1', unit: '件' },
        outputs: [
          { workflowPortId: 'OUT-1', unit: '件' },
          { workflowPortId: 'OUT-2', unit: null },
        ],
      },
      message: '产出端口 OUT-2 缺少单位',
    },
    {
      name: 'heterogeneous input port units',
      config: {
        processName: '混料',
        inputs: [
          { workflowPortId: 'IN-1', unit: 'g' },
          { workflowPortId: 'IN-2', unit: 'ml' },
        ],
        output: { workflowPortId: 'OUT-1', unit: '件' },
      },
      message: '投入端口单位不一致',
    },
  ])('fails loudly for $name', ({ config, message }) => {
    expect(() => resolveWorkflowProcessSheetUnits(config)).toThrow(message);
  });

  it('changes only quantity labels, keeping weight-specific fields in kg', () => {
    const cols = withProcessSheetUnits([
      { key: 'before', label: '投入(kg)' },
      { key: 'after', label: '产出(kg)' },
      { key: 'remain', label: '剩余(kg)' },
      { key: 'productWeight', label: '成品重量(kg)' },
    ], { inputUnit: '只', outputUnit: '袋' });
    expect(cols.map((it) => it.label)).toEqual(['投入(只)', '产出(袋)', '剩余(只)', '成品重量(kg)']);
  });

  it('labels plan quantity as finished-product output and keeps its explicit unit', () => {
    expect(formatPlannedOutput(10, 'kg')).toBe('计划成品 10 kg');
    expect(formatPlannedOutput(10, '包')).toBe('计划成品 10 包');
  });

  it('renders process summaries and feed prompts using configured units', () => {
    expect(formatProcessOutput(400, 'bag')).toBe('产出 400.00 bag');
    expect(formatSourceFeedSummary(2, 200, 'each')).toBe('2批 · 200.0each');
    expect(formatFeedPlaceholder('each')).toBe('投料each');
  });
});
