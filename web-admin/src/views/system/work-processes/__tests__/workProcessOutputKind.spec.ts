import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  normalizeOutputMaterialKind,
  usesSemiFinishedCode,
} from '../workProcessOutputKind';

const source = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');

describe('work process output kind', () => {
  it('defaults missing legacy values to SEMI_FINISHED', () => {
    expect(normalizeOutputMaterialKind(undefined)).toBe('SEMI_FINISHED');
  });

  it('only keeps semi code controls for semi-finished output', () => {
    expect(usesSemiFinishedCode('SEMI_FINISHED')).toBe(true);
    expect(usesSemiFinishedCode('FINISHED_GOOD')).toBe(false);
  });

  it('keeps units out of process master data forms and moves optional fields into advanced settings', () => {
    expect(source).not.toContain('label="投入单位" prop="unit"');
    expect(source).not.toContain('label="产出单位" prop="outputUnit"');
    expect(source).not.toContain('outputUnitManuallyEdited');
    expect(source).toContain('<el-radio-group');
    expect(source).toContain('title="高级设置（可选）"');
    expect(source.indexOf('title="高级设置（可选）"')).toBeLessThan(source.indexOf('label="预估工时"'));
    expect(source).toContain('placeholder="默认留空；需要固定识别码时再配置"');
  });

  it('suggests history and blocks exact duplicate process names', () => {
    expect(source).toContain(':fetch-suggestions="queryProcessNames"');
    expect(source).toContain(':fetch-suggestions="queryProcessCategories"');
    expect(source).toContain('const exactNameDuplicate = computed');
    expect(source).toContain('已存在同名工序');
    expect(source).toContain('if (exactNameDuplicate.value)');
  });
});
