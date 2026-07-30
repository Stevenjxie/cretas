import { describe, expect, it } from 'vitest';
import {
  convertQuantityToUnit,
  formatFeedPlaceholder,
  formatPlannedOutput,
  formatProcessOutput,
  formatSourceFeedSummary,
  displayProcessUnit,
  normalizeMassQuantityForReporting,
  workflowPortDisplayUnit,
  formatWorkflowPlannedOutput,
  resolveProcessSheetUnits,
  resolveWorkflowProcessSheetUnits,
  withProcessSheetUnits,
} from '../processSheetUnits';

describe('processSheetUnits', () => {
  it('uses the product-process override as input and the process output unit as output', () => {
    expect(resolveProcessSheetUnits({ unitOverride: '只', defaultUnit: 'kg', defaultOutputUnit: '袋' }))
      .toEqual({ inputUnit: '只', outputUnit: '袋' });
  });

  it.each([
    [{ defaultUnit: null, defaultOutputUnit: 'g' }, '投入单位'],
    [{ defaultUnit: 'g', defaultOutputUnit: null }, '产出单位'],
  ])('legacy process units fail closed when %s unit is missing', (config, expected) => {
    expect(() => resolveProcessSheetUnits(config)).toThrow(expected);
  });

  it('projects raw/semi workflow mass units to kg and keeps the finished SKU descriptor unit', () => {
    expect(resolveWorkflowProcessSheetUnits({
      processName: '装件',
      inputs: [{ workflowPortId: 'IN-1', materialKind: 'SEMI_FINISHED', unit: ' g ' }],
      output: { workflowPortId: 'OUT-1', materialKind: 'FINISHED_GOOD', finished: true, unit: ' 件 ' },
    })).toEqual({ inputUnit: 'kg', outputUnit: '件' });
  });

  it('treats mixed g/kg raw inputs as one kg reporting unit and projects semi output to kg', () => {
    expect(resolveWorkflowProcessSheetUnits({
      processName: '混料',
      inputs: [
        { workflowPortId: 'IN-1', materialKind: 'RAW_MATERIAL', unit: 'g' },
        { workflowPortId: 'IN-2', materialKind: 'RAW_MATERIAL', unit: 'kg' },
      ],
      output: { workflowPortId: 'OUT-1', materialKind: 'SEMI_FINISHED', unit: 'g' },
    })).toEqual({ inputUnit: 'kg', outputUnit: 'kg' });
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

  it('formats a legacy mass plan using the terminal finished-SKU descriptor', () => {
    expect(formatWorkflowPlannedOutput(100_000, 'g', {
      materialKind: 'FINISHED_GOOD', finished: true, unit: '盒', gramsPerUnit: 200,
    })).toBe('计划成品 500 盒');
    expect(formatWorkflowPlannedOutput(2_000, 'g', {
      materialKind: 'SEMI_FINISHED', finished: false, unit: 'g',
    })).toBe('计划成品 2 kg');
  });

  it('renders persisted English packaging aliases with Chinese business labels', () => {
    expect(displayProcessUnit('box')).toBe('盒');
    expect(displayProcessUnit('case')).toBe('箱');
    expect(displayProcessUnit('slice')).toBe('片');
    expect(displayProcessUnit('bag')).toBe('袋');
    expect(formatPlannedOutput(500, 'box')).toBe('计划成品 500 盒');
  });

  it('normalizes legacy g quantities to kg for reporting without changing the stored value', () => {
    expect(normalizeMassQuantityForReporting(100_000, 'g')).toEqual({ quantity: 100, unit: 'kg' });
    expect(normalizeMassQuantityForReporting(50, '克')).toEqual({ quantity: 0.05, unit: 'kg' });
    expect(normalizeMassQuantityForReporting(10, 'box')).toEqual({ quantity: 10, unit: '盒' });
  });

  it('uses kg for raw and semi-finished workflow ports and Chinese SKU units for finished ports', () => {
    expect(workflowPortDisplayUnit({ materialKind: 'SEMI_FINISHED', unit: 'g' })).toBe('kg');
    expect(workflowPortDisplayUnit({ materialKind: 'FINISHED_GOOD', finished: true, unit: 'box' })).toBe('盒');
    expect(workflowPortDisplayUnit({ materialKind: 'FINISHED_GOOD', finished: true, unit: 'case' })).toBe('箱');
    expect(workflowPortDisplayUnit({ materialKind: 'FINISHED_GOOD', finished: true, unit: 'slice' })).toBe('片');
  });

  it('renders missing units as unconfigured instead of kilograms', () => {
    expect(formatPlannedOutput(10, null)).toContain('未配置');
    expect(formatProcessOutput(10, null)).toContain('未配置');
    expect(formatSourceFeedSummary(2, 10, null)).toContain('未配置');
    expect(formatFeedPlaceholder(null)).toContain('未配置');
  });

  it('renders process summaries and feed prompts using configured units', () => {
    expect(formatProcessOutput(400, 'bag')).toBe('产出 400.00 袋');
    expect(formatSourceFeedSummary(2, 200, 'each')).toBe('2批 · 200.0件');
    expect(formatFeedPlaceholder('each')).toBe('投料件');
  });
});

/**
 * convertQuantityToUnit 支撑报工投入行的「可用库存」汇总 (防呆 Rule 1)。
 *
 * 口径必须与 #1976 (2026-07-29) 一致: **等价码只对科学单位成立, 计数/包装单位按字面比较**
 * (一只 ≠ 一件)。跨单位相加会得出偏大且看着权威的可用量, 就是 2026-07-30 客户
 * 「生产仓有货, 报工说可用 0」那一类错。
 */
describe('convertQuantityToUnit (投入可用量汇总口径)', () => {
  it('passes through when the unit is literally the same (case/空白 不敏感)', () => {
    expect(convertQuantityToUnit(12, '只', '只')).toBe(12);
    expect(convertQuantityToUnit(12, ' KG ', 'kg')).toBe(12);
  });

  it('converts within mass units only', () => {
    expect(convertQuantityToUnit(2, 'kg', 'g')).toBe(2000);
    expect(convertQuantityToUnit(500, 'g', 'kg')).toBe(0.5);
    expect(convertQuantityToUnit(1, '千克', '克')).toBe(1000);
  });

  it('refuses to convert between counting units even when 显示别名 merges them', () => {
    // displayProcessUnit 把 pcs / each / piece 全映射成「件」——那是**显示**别名。
    // 若拿它当换算依据, 后端会拒的两个单位会被算成同一个, 可用量偏大。
    expect(displayProcessUnit('pcs')).toBe('件');
    expect(convertQuantityToUnit(5, 'pcs', '件')).toBeNull();
    expect(convertQuantityToUnit(5, '只', '件')).toBeNull();
    expect(convertQuantityToUnit(5, '箱', '盒')).toBeNull();
  });

  it('refuses to bridge counting units and mass (每单位重量桥 已拍板暂不做)', () => {
    expect(convertQuantityToUnit(5, '只', 'kg')).toBeNull();
    expect(convertQuantityToUnit(5, 'kg', '只')).toBeNull();
  });

  it('returns null for blank units instead of guessing kg', () => {
    expect(convertQuantityToUnit(5, null, 'kg')).toBeNull();
    expect(convertQuantityToUnit(5, 'kg', '  ')).toBeNull();
  });
});
