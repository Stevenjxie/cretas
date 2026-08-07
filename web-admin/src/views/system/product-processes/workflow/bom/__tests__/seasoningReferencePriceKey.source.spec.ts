import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * 「未税采购参考价」这条判据曾经从来没有生效过。
 *
 * 后端 RawMaterialTypeDTO 把 entity 的未税价装进 `materialReferencePrice`
 * (RawMaterialTypeServiceImpl:803)，该字段上的 JsonAlias 只作用于反序列化（入参），
 * 不影响响应 JSON —— 响应里根本没有旧键。
 *
 * 前端却一直读旧键，永远是 undefined。于是「有没有有效价格」实际只剩
 * 「有没有移动平均库存成本」一个条件，而移动平均价要有真实入库流水才有值 ——
 * 新建的调料因此永远存不了，且被提示「去配置价格」，去配一个已经配好的东西。
 *
 * 这些断言把「前端读的键」钉在「后端发的键」上。
 */

/** 只保留代码：剥掉注释，避免解释性文字里出现的字段名被误判为「还在读旧键」。 */
function codeOnly(source: string): string {
  const withoutBlocks = source.replace(/\/\*[\s\S]*?\*\//g, '');
  return withoutBlocks
    .split(/\r?\n/)
    .filter((line) => {
      const t = line.trim();
      return !t.startsWith('*') && !t.startsWith('//');
    })
    .join('\n');
}

const read = (file: string) => codeOnly(readFileSync(resolve(__dirname, '..', file), 'utf-8'));

const DIALOG = read('SeasoningBindingDialog.vue');

describe('辅料价格判据读的键必须与后端响应一致', () => {
  it('调料绑定弹窗读 materialReferencePrice', () => {
    expect(DIALOG).toMatch(/materialReferencePrice/);
  });

  it('调料绑定弹窗不再读后端从不下发的那个键', () => {
    // 响应里没有该键 —— 读它等于这条判据恒为假
    expect(DIALOG).not.toMatch(/\.unitPrice\b/);
    expect(DIALOG).not.toMatch(/\bunitPrice\?:/);
  });

  it('AI 批量导入弹窗已随旧 BOM 页一起删除', () => {
    // 2026-08-07 阶段 5: AuxiliaryAiImportDialog 只被已删的 BomAuxiliaryWorkspace 用,
    // 画布侧零引用, 跟着旧 BOM 页一起删了。原来那两条断言钉的是它读的价格键 ——
    // 文件不在了, 断言就没有载体。这里改成钉「它确实不在」, 以免有人把它复活回来
    // 却不知道要一起把价格键钉回去。若将来画布要做辅料 AI 批量导入, 新弹窗必须
    // 在本文件里补回「读 materialReferencePrice / 不读 unitPrice」两条。
    expect(existsSync(resolve(__dirname, '..', 'AuxiliaryAiImportDialog.vue'))).toBe(false);
  });

  it('重新读取价格的重取路径也用同一个键', () => {
    const at = DIALOG.indexOf('async function refreshSelectedMaterialPrice');
    expect(at, '重取函数应存在').toBeGreaterThan(-1);
    const refetch = DIALOG.slice(at, at + 700);
    expect(refetch, '重取若仍读旧键, 用户点「重新读取价格」永远读不到').toMatch(/materialReferencePrice/);
  });
});
