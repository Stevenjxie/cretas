import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  allowsInjection,
  allowsPotRatio,
  isValidDosagePerKgG,
  isValidInjectionAmount,
  isValidSubsequentPotRatio,
  SEASONING_CATEGORY_COOKING,
  SEASONING_CATEGORY_INJECTION,
} from '../seasoningProcessCategory';

/**
 * 画布 AI 扩能（2026-08-07 阶段 4）在**确定性编译器路**上的约束。
 *
 * ## 为什么这套判据要在前端再写一遍
 * 画布 AI 有两条路：
 *   1. 补丁路 —— LLM 出 patches，后端 ProductProcessWorkflowConfigTool 校验
 *   2. 编译器路 —— LLM 只出「语义规格」，**前端**确定性建图
 * 第二条路的规格根本不经过那个后端 Tool，后端那份 sanitize 一行都跑不到。
 * 设计定稿的约束 5 就是这件事：「两条路都要覆盖，只改一条会留半个洞」。
 *
 * 后端同约束的反例测试在
 * `ProductProcessWorkflowConfigToolBomFieldsTest`（Java 侧）。
 */
describe('调味参数的类别闸（编译器路）', () => {
  it('锅序比例只在熟制类工序上允许', () => {
    expect(allowsPotRatio(SEASONING_CATEGORY_COOKING)).toBe(true);
    expect(allowsPotRatio('切配')).toBe(false);
    expect(allowsPotRatio(SEASONING_CATEGORY_INJECTION)).toBe(false);
  });

  it('注射量只在注射类工序上允许', () => {
    expect(allowsInjection(SEASONING_CATEGORY_INJECTION)).toBe(true);
    expect(allowsInjection(SEASONING_CATEGORY_COOKING)).toBe(false);
  });

  it('没设类别按【不允许】处理 —— 缺证据不许降级放行', () => {
    // ⛔ 这条是禁止降级处理的直接体现: 若把 undefined 当成"允许", AI 就能把锅序比例
    //    写进任何一道没填类别的工序 —— 正是类别闸要拦的事。
    for (const absent of [undefined, null, '', '   ', 0, false, {}]) {
      expect(allowsPotRatio(absent), `${String(absent)} 不该被当成熟制`).toBe(false);
      expect(allowsInjection(absent), `${String(absent)} 不该被当成注射`).toBe(false);
    }
  });

  it('类别前后空白不影响判定', () => {
    expect(allowsPotRatio(` ${SEASONING_CATEGORY_COOKING} `)).toBe(true);
  });
});

describe('调味参数的数值域（编译器路）', () => {
  it('用量必须大于 0 —— 0 不是「没配」而是「配了个静默无效的行」', () => {
    expect(isValidDosagePerKgG(12.5)).toBe(true);
    expect(isValidDosagePerKgG(0)).toBe(false);
    expect(isValidDosagePerKgG(-1)).toBe(false);
  });

  it('锅序比例 0–100，且 0 合法', () => {
    // 0 表示「后续锅不再加这味调料」, 是真实配置, 不能跟越界混为一谈。
    expect(isValidSubsequentPotRatio(0)).toBe(true);
    expect(isValidSubsequentPotRatio(100)).toBe(true);
    expect(isValidSubsequentPotRatio(100.01)).toBe(false);
    expect(isValidSubsequentPotRatio(-0.01)).toBe(false);
  });

  it('注射量必须大于 0', () => {
    expect(isValidInjectionAmount(3.5)).toBe(true);
    expect(isValidInjectionAmount(0)).toBe(false);
    expect(isValidInjectionAmount(-2)).toBe(false);
  });

  it('非数字 / 非有限值一律拒绝，不做字符串转换', () => {
    // ⛔ 若这里接受 '12.5' 并 Number() 一下, AI 就能靠类型模糊绕过数值域;
    //    更糟的是 NaN/Infinity 会一路写进图里, 到扣料时才炸。
    for (const bad of ['12.5', NaN, Infinity, -Infinity, null, undefined, {}, []]) {
      expect(isValidDosagePerKgG(bad), `${String(bad)} 不该通过`).toBe(false);
      expect(isValidSubsequentPotRatio(bad), `${String(bad)} 不该通过`).toBe(false);
      expect(isValidInjectionAmount(bad), `${String(bad)} 不该通过`).toBe(false);
    }
  });
});

describe('两边常量必须一致', () => {
  /**
   * 🔴 前端这份常量是后端 `SeasoningProcessCategory` 的副本。副本一旦漂移，
   * 两条 AI 路径的类别闸就会给出不同答案 —— 而且**都不会报错**：
   * 补丁路拒了、编译器路放行了，用户只会觉得"AI 有时候听话有时候不听"。
   * 所以直接读 Java 源文件比对，不靠人记得同步。
   */
  it('前端常量与后端 SeasoningProcessCategory 逐字相同', () => {
    const java = readFileSync(
      resolve(
        process.cwd(),
        '../backend/java/cretas-api/src/main/java/com/cretas/aims/constant/SeasoningProcessCategory.java',
      ),
      'utf-8',
    );
    const read = (name: string) => {
      const match = java.match(new RegExp(`String\\s+${name}\\s*=\\s*"([^"]+)"`));
      expect(match, `后端应有常量 ${name}`).not.toBeNull();
      return match![1];
    };
    expect(SEASONING_CATEGORY_COOKING).toBe(read('COOKING'));
    expect(SEASONING_CATEGORY_INJECTION).toBe(read('INJECTION'));
  });
});

describe('编译器真的把约束接上了（不是只导入了函数）', () => {
  const EDITOR = readFileSync(
    resolve(process.cwd(), 'src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue'),
    'utf-8',
  );

  it('规格里的调味行经过类别闸与数值域', () => {
    // ⚠️ 只断言 import 存在是不够的 —— 导入了但没调用同样会绿。这里钉调用点。
    expect(EDITOR).toMatch(/if \(!isValidDosagePerKgG\(row\?\.dosagePerKgG\)\)/);
    expect(EDITOR).toMatch(/if \(!allowsPotRatio\(stepCategory\)\)/);
    expect(EDITOR).toMatch(/allowsInjection\(stepCategory\)/);
  });

  it('被拒的行不静默丢弃 —— 必须告诉用户', () => {
    // ⛔ 静默丢弃 = 用户以为 AI 照做了, 到扣料时才发现没配, 那时已经排产。
    expect(EDITOR).toContain('seasoningRejections');
    expect(EDITOR).toMatch(/以下调味配置未采纳/);
    // 提示不能自动消失: 这条是要被读到的
    const at = EDITOR.indexOf('以下调味配置未采纳');
    expect(EDITOR.slice(Math.max(0, at - 300), at)).toMatch(/duration: 0/);
  });

  it('规格里的 byproduct 产出建成副产 cell', () => {
    expect(EDITOR).toMatch(/extra\?\.byproduct === true \? \{ isByproduct: true \}/);
  });
});
