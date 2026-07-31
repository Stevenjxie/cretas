import { readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { displayUnit } from '../unitPricing';
import { displayProcessUnit } from '../processSheetUnits';

/**
 * 单位展示契约 —— 规则由 Steve 2026-07-31 拍板:
 *
 * - **可换算的国际单位** (kg/g/mg/ml/L/mm/cm/m/km) 保留英文码: 它们真的参与换算, 用户也认。
 * - **计数 / 包装单位** (box/bag/pcs/case/slice/pack/bottle/can/crate/pail/roll/portion/item)
 *   **一律不得以英文码示人**: 它们不参与任何换算, 码只是内部标识, 对用户没有意义。
 *
 * 🔴 为什么必须用测试钉住, 而不是靠人记得:
 * 后端保存时写的**就是规范码** (`BomRecipeServiceImpl` 的 `recipe.setOutputUnit(outputUnit.code())`),
 * 所以 `box` / `bag` / `pcs` 大量存在于库里 —— 不是脏数据, 是设计。前端只要有一个地方漏了
 * 展示映射, 用户就会看到「box」。而**「box」看着还行**, 不像乱码那样刺眼, 于是能一路混过 review
 * 上到 prod (2026-07-31 实测: `BomFamilyOutputCostingDialog` 就是这么显示了 21 行数据的英文码)。
 *
 * 本文件直接读**后端权威表源码**比对, 所以后端加了新单位而前端没跟, 这里就会红。
 */

/** 后端权威表: backend/java/.../service/unit/impl/UnitContractServiceImpl.java */
const AUTHORITY = readFileSync(
  resolve(
    __dirname, '..', '..', '..', '..',
    'backend', 'java', 'cretas-api', 'src', 'main', 'java', 'com', 'cretas', 'aims',
    'service', 'unit', 'impl', 'UnitContractServiceImpl.java',
  ),
  'utf8',
);

/** 从 systemUnits() 里解析出 `code -> dimension`。 */
function authorityUnits(): Array<{ code: string; dimension: string }> {
  const block = AUTHORITY.match(/private static Map<String, CanonicalUnit> systemUnits\(\)[\s\S]*?\n {4}}/);
  expect(block, '找不到后端 systemUnits()').not.toBeNull();
  const units = [...block![0].matchAll(/add\(units,\s*"([^"]+)",\s*UnitDimension\.(\w+)/g)]
    .map((m) => ({ code: m[1], dimension: m[2] }));
  expect(units.length, '后端至少应有 20 个内置单位, 解析明显不对').toBeGreaterThan(20);
  return units;
}

/** 从 systemAliases() 里解析出每个 code 的全部别名 (含中文写法)。 */
function authorityAliases(): Map<string, string[]> {
  const block = AUTHORITY.match(/private static Map<String, String> systemAliases\(\)[\s\S]*?\n {4}}/);
  expect(block, '找不到后端 systemAliases()').not.toBeNull();
  const map = new Map<string, string[]>();
  for (const m of block![0].matchAll(/alias\(aliases,\s*"([^"]+)"((?:,\s*"[^"]*")+)\)/g)) {
    map.set(m[1], [...m[2].matchAll(/"([^"]*)"/g)].map((x) => x[1]));
  }
  return map;
}

const UNITS = authorityUnits();
const ALIASES = authorityAliases();
const COUNTING = UNITS.filter((u) => u.dimension === 'COUNT' || u.dimension === 'PACKAGE');
const SI = UNITS.filter((u) => ['MASS', 'VOLUME', 'LENGTH'].includes(u.dimension));

const hasHan = (value: string) => /\p{Script=Han}/u.test(value);

describe('单位展示契约: 计数/包装单位不得以英文码示人', () => {
  it('后端权威表解析成功 (阳性对照 —— 解析失败会让下面所有断言假绿)', () => {
    expect(COUNTING.map((u) => u.code)).toEqual(
      expect.arrayContaining(['box', 'bag', 'pcs', 'case', 'slice', 'pack', 'bottle', 'can']),
    );
    expect(SI.map((u) => u.code)).toEqual(expect.arrayContaining(['kg', 'g', 'ml', 'mm']));
    expect(ALIASES.get('bag')).toContain('袋');
  });

  describe.each([
    ['displayUnit (unitPricing)', displayUnit],
    ['displayProcessUnit (processSheetUnits)', displayProcessUnit],
  ])('%s', (_name, display) => {
    it.each(COUNTING.map((u) => u.code))('规范码 %s 显示为中文', (code) => {
      const shown = display(code);
      expect(shown, `${code} 显示成了「${shown}」—— 英文码不该给用户看到`).not.toBe(code);
      expect(hasHan(shown), `${code} 显示成了「${shown}」, 应为中文`).toBe(true);
    });

    it.each(
      COUNTING.flatMap((u) => (ALIASES.get(u.code) ?? []).filter(hasHan).map((a) => [u.code, a])),
    )('%s 的中文别名「%s」也显示为中文', (_code, alias) => {
      expect(hasHan(display(alias))).toBe(true);
    });

    it('可换算的国际单位保留英文码 (不要连坐改成中文)', () => {
      expect(display('kg')).toBe('kg');
      expect(display('g')).toBe('g');
      // 中文写法同样归一到英文码, 不是原样返回
      expect(display('公斤')).toBe('kg');
      expect(display('千克')).toBe('kg');
      expect(display('克')).toBe('g');
    });

    it('认不出的单位原样返回, 不猜 (禁降级: 不折成某个"最像"的单位)', () => {
      expect(display('自定义单位')).toBe('自定义单位');
    });
  });
});

describe('展示层调用点: 单位不得原样插值', () => {
  /**
   * 这几个字段名只承载 SKU/物料计量单位 (不像 dashboard 的 `card.unit` 那种 KPI 单位),
   * 所以只要出现在插值里, 就必须裹一层展示映射。
   *
   * 2026-07-31 之前 `BomFamilyOutputCostingDialog` 与 `batches/detail.vue` 共 20+ 处原样插值,
   * 而 `production_batches.unit` 里确有 box 5 / bag 3, `bom_recipes.output_unit` 有 box 14 / bag 7。
   */
  const FIELDS = ['outputUnit', 'quantityUnit', 'processedUnit', 'stageOutputUnit', 'segmentWasteUnit'];
  /**
   * 认可的包裹函数。`countDisplayUnit` 是 stocktakes 自己的薄封装, 内部就是 `displayUnit` ——
   * 收录它是因为它**确实**做了映射, 不是为了让测试变绿 (核过 stocktakeCount.ts:15)。
   */
  const WRAPPERS = /displayUnit|displayProcessUnit|businessUnitLabel|formatPriceUnit|unitLabel|countDisplayUnit/;

  /**
   * 少数页面把「数量 + 单位」封成了本地 helper, 映射做在 helper 内部, 调用点自然看不到包裹。
   *
   * ⚠️ 这里**按文件**放行, 不按函数名 —— `formatQty` 这种通用名在别的文件另有实现, 全局放行
   * 等于给那些文件开了后门。每一条都附带下面 `helperItselfMaps` 的断言: helper 哪天不再映射了,
   * 那条会红, 而不是这里悄悄放过。
   */
  const FILE_LOCAL_WRAPPERS: Array<{ file: string; helper: string }> = [
    { file: 'views/production/approval/list.vue', helper: 'segmentQty' },
    { file: 'views/warehouse/transit-ledger/list.vue', helper: 'formatQty' },
  ];

  const VIEWS = resolve(__dirname, '..', '..', 'views');

  function vueFiles(dir: string): string[] {
    return readdirSync(dir).flatMap((name: string) => {
      const full = resolve(dir, name);
      if (statSync(full).isDirectory()) return vueFiles(full);
      return name.endsWith('.vue') ? [full] : [];
    });
  }

  it.each(FILE_LOCAL_WRAPPERS)('$file 的 $helper 确实做了单位映射 (放行的前提)', ({ file, helper }) => {
    const source = readFileSync(resolve(__dirname, '..', '..', ...file.split('/')), 'utf8');
    const body = source.slice(source.indexOf(`function ${helper}(`));
    const end = body.indexOf('\n}\n');
    expect(end, `${helper} 没有顶格收尾`).toBeGreaterThan(0);
    expect(WRAPPERS.test(body.slice(0, end)), `${helper} 内部必须映射单位, 否则不该被放行`).toBe(true);
  });

  it('production / procurement / warehouse 视图里没有裸露的单位插值', () => {
    const offenders: string[] = [];
    for (const file of vueFiles(VIEWS)) {
      if (!/[\\/](production|procurement|warehouse|inventory)[\\/]/.test(file)) continue;
      const normalized = file.replace(/\\/g, '/');
      const localHelpers = FILE_LOCAL_WRAPPERS
        .filter((entry) => normalized.endsWith(entry.file))
        .map((entry) => entry.helper);
      const source = readFileSync(file, 'utf8');
      for (const [index, line] of source.split('\n').entries()) {
        const interpolations = line.match(/\{\{[^}]*\}\}/g) ?? [];
        for (const chunk of interpolations) {
          if (!FIELDS.some((f) => chunk.includes(f))) continue;
          if (WRAPPERS.test(chunk)) continue;
          if (localHelpers.some((helper) => chunk.includes(`${helper}(`))) continue;
          offenders.push(`${normalized.replace(/.*\/views\//, 'views/')}:${index + 1}  ${chunk.trim()}`);
        }
      }
    }
    expect(offenders, `以下插值直接把单位摆给用户看, 请裹一层 displayProcessUnit/displayUnit:\n${offenders.join('\n')}`)
      .toEqual([]);
  });
});
