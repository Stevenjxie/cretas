import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 「替代包材」的作用域判据必须落在**真实存在的**载体上。
 *
 * <h2>🔴 2026-08-13 真机实测(LIUSHANMEN 生产)</h2>
 * 画布上包材 Cell → 编辑包材 → 「替代包材（可选）」这一栏**恒灰**, 旁边写着
 * 「主包材缺少可验证的分类/包装作用域」。旧判据是:
 *
 * ```
 * 编码是 10 位以上纯数字 → code:前10位
 * 否则 category 不是「包材」→ category:xxx
 * 否则 → ''(禁用)
 * ```
 *
 * 两条分支在生产上都走不到:
 * - **10 位数字码**: 全库 552 个启用物料命中 **0 个**(本仓编码是 BC001/WL0xx 这种短码)。
 * - **category 分支**: 能进包材选择器的 category 只有 `包材/packaging/PACKAGING`
 *   (utils/materialCategory.ts#PACKAGING_CATEGORY_VALUES), 而这条分支正好把它们排除 ——
 *   **构造上就死的**, 不是当前数据碰巧走不到。
 *
 * 于是这个功能上线以来对所有工厂、所有包材 100% 不可用, 而提示指向一个
 * **没有任何可达数据状态能满足**的条件: 用户以为是自己档案没维护好。
 * 闸的意图是对的(不能让「标签」被「真空袋」替代), 坏的是没人有路遵守它。
 *
 * <h2>这条闸守什么</h2>
 * ① 判据只认 `classificationId`(三级物料分类, 系统本来就为「分类/包装作用域」设计的载体);
 * ② 那两条构造上死的分支不许回来 —— 它们看起来像「多一层兜底」, 实际是把功能锁死;
 * ③ 提示必须指向一个**做得到**的动作, 不能再说「缺少可验证的分类」了事。
 */
const source = readFileSync(
  resolve(__dirname, '..', 'PackagingBindingDialog.vue'),
  'utf8',
);

/** 剥注释 —— 注释里引用了被删掉的旧写法, 不剥会自己命中自己。 */
const code = source
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/\/\/[^\n]*/g, '');

function classificationKeyFn(): string {
  const start = code.indexOf('function packagingClassificationKey(');
  expect(start, '找不到 packagingClassificationKey —— 结构变了, 这条闸需要重写').toBeGreaterThan(-1);
  const end = code.indexOf('\n}', start);
  expect(end).toBeGreaterThan(start);
  return code.slice(start, end);
}

describe('替代包材的作用域判据', () => {
  it('① 判据读 classificationId(三级物料分类)', () => {
    expect(classificationKeyFn()).toContain('material.classificationId');
  });

  it('② 不许回到 10 位数字编码 —— 全库 0 命中, 等于把功能锁死', () => {
    expect(classificationKeyFn(), '这条分支在真实编码方案下永远不成立')
      .not.toMatch(/\\d\{10,\}/);
  });

  it('③ 不许回到 category 分支 —— 它把唯一可能的 category 排除掉了', () => {
    expect(classificationKeyFn(), "包材选择器里 category 恒为 包材/packaging/PACKAGING")
      .not.toContain('material.category');
  });

  it('④ 类型上声明了 classificationId, 否则字段到不了这里', () => {
    expect(source).toMatch(/classificationId\?:\s*number \| string \| null/);
  });

  it('⑤ 提示指向一个做得到的动作(去哪、做什么), 不再只说「缺少分类」', () => {
    expect(source).toContain('该包材还没挂物料分类');
    expect(source, '必须告诉用户去哪配')
      .toMatch(/原料类型字典/);
    expect(source, '旧文案把设计缺陷说成用户的数据问题')
      .not.toContain('主包材缺少可验证的分类');
  });
});

/**
 * 提示指向的那个动作必须真的做得到 —— 否则只是把死结往上挪了一层。
 *
 * 实测: 物料档案的分类段选择器原本 `&& !editingId`(只在新建时显示), 提交时也只有
 * 新建分支带 `classificationId`。存量物料因此**永远补不上分类**:
 * 生产上 479 个启用物料只有 10 个挂了分类, 包材 63 个里 0 个。
 * 后端两条路径本来就都支持, 缺的只是界面。
 */
describe('物料档案 · 存量物料能补分类', () => {
  const archive = readFileSync(
    resolve(__dirname, '..', '..', '..', '..', 'warehouse', 'material-types', 'list.vue'),
    'utf8',
  );
  const archiveCode = archive
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\n]*/g, '');

  it('① 编辑既有物料时也显示分类段选择器', () => {
    expect(archiveCode).toMatch(/showSegmentEditor = computed\(\(\) => hasSegmentDictionary\.value\)/);
    expect(archiveCode, '不许再用 !editingId 把编辑态排除掉')
      .not.toMatch(/showSegmentEditor[\s\S]{0,80}!editingId/);
  });

  /**
   * ⚠️ 锚在**真正发 PUT 的那句**上, 不要锚 `if (editingId.value) {` ——
   * 那个条件在本文件里出现多次, 会切到别的代码块去(实测第一次就切错了)。
   */
  it('② 更新请求带上 classificationId(不带则后端收不到, 等于没改)', () => {
    const putAt = archiveCode.indexOf('raw-material-types/${editingId.value}');
    expect(putAt, '找不到更新请求 —— 结构变了, 这条闸需要重写').toBeGreaterThan(-1);
    // ⚠️ 窗口必须收在这次 PUT 自己的闭合括号处。实测取固定 400 字符时会越过它、
    //    命中【新建分支】里的那个 classificationId —— 把字段从 PUT 里删掉照样绿。
    const closeAt = archiveCode.indexOf('});', putAt);
    expect(closeAt).toBeGreaterThan(putAt);
    const putBlock = archiveCode.slice(putAt, closeAt);
    expect(putBlock, 'PUT 的 payload 里必须带 classificationId').toContain('classificationId:');
  });

  it('③ 打开编辑时回填已有分类, 否则随手保存会改掉原值', () => {
    expect(archiveCode).toContain('applyExistingClassification(');
    expect(archiveCode, '回填要在分类树加载之后, 否则树是空的什么也找不到')
      .toMatch(/await loadSegmentTree\(\);[\s\S]{0,200}applyExistingClassification\(/);
  });

  /** 反向断言: 找不到对应节点时必须保持三个都为空(= 本次不改分类)。 */
  it('④ 反解不到分类节点时保持空 —— 空表示「本次不改」', () => {
    const fn = archiveCode.slice(
      archiveCode.indexOf('function applyExistingClassification('),
      archiveCode.indexOf('\n}', archiveCode.indexOf('function applyExistingClassification(')),
    );
    expect(fn).toContain('if (!Number.isFinite(target)) return;');
  });
});
