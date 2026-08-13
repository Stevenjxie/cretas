import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 包材的采购参考价：界面必须说它是**必填**。
 *
 * <h2>🔴 2026-08-13 真机抓到(LIUSHANMEN 生产)</h2>
 * 编辑包材 `BC001 吸塑盒2014-3.5` 点保存, 弹窗纹丝不动、什么也没发生。
 * 直接打接口做三向对照才看清:
 *
 * | 请求 | 结果 |
 * |---|---|
 * | 不带 classificationId | **400 含税单价必须大于0** |
 * | 带 classificationId | 同样 400 |
 * | 带价格 + classificationId | **200** |
 *
 * 前两条一模一样 ⇒ 与那次改动无关。真因是后端
 * `RawMaterialTypeServiceImpl` 的 **create 与 update 两条路径**都有
 * `if (packaging) validateRequiredPricing(...)`, 缺价直接 400
 * (hint: 「包材主数据需要维护采购参考价」)。
 *
 * <p>而界面上这个字段一直写着 **「选填；未知价格请留空」** ——
 * 用户照着做, 然后存不进去。LIUSHANMEN **25 个启用包材里 24 个价格是空的**,
 * 等于这 24 个**保存任何修改都会被拦下**, 包括「去把价格补上」以外的一切改动。
 *
 * <p>这是本轮反复出现的同一形状: **闸判的和界面说的不是一回事**。
 * 后端那条规则本身是对的(BOM 要按它算包装成本, 两条路径一致), 所以修的是界面。
 *
 * <h2>这条闸守什么</h2>
 * 别再把「包材的采购参考价」说成选填 —— 无论是 placeholder、字段说明,
 * 还是提交前的校验文案。
 */
const source = readFileSync(
  resolve(__dirname, '..', 'list.vue'),
  'utf8',
);

/** 剥注释 —— 注释里引用了旧文案(在讲这个缺陷), 不剥会自己命中自己。 */
const code = source
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/\/\/[^\n]*/g, '');

describe('包材采购参考价 · 界面要说实话', () => {
  it('① 表单项对包材标必填', () => {
    expect(code).toMatch(/:required="isPackagingMaterial"/);
  });

  it('② placeholder 按类别区分, 不再对包材说「选填」', () => {
    expect(code).toMatch(/isPackagingMaterial \? '包材必填' : '选填；未知价格请留空'/);
  });

  it('③ 字段说明对包材讲清后果(留空存不进去)', () => {
    expect(code).toContain('留空会被后端拒绝保存');
  });

  it('④ 提交前就拦住, 不靠后端 400 才发现', () => {
    expect(code).toMatch(/isPackagingMaterial\.value[\s\S]{0,160}taxIncludedUnitPrice == null[\s\S]{0,200}包材必须填写采购参考价/);
  });

  /**
   * ⚠️ 反向断言: 非包材仍然是选填 —— 这条规则只对包材成立,
   * 一刀切全设必填会把原料/辅料的正常留空也拦掉。
   */
  it('⑤ 非包材仍然可以留空(规则只对包材成立)', () => {
    expect(code).toContain("'选填；未知价格请留空'");
    expect(code, '非包材的提示语不能被删掉')
      .toContain('空值不会被当作 0 元');
  });
});
