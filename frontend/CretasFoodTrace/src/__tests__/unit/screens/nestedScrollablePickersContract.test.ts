import * as fs from 'fs';
import * as path from 'path';
import * as ts from 'typescript';

/**
 * 闸：**同向嵌套的内层 ScrollView 必须带 `nestedScrollEnabled`**。
 *
 * Android 上两个同向 ScrollView 嵌套时，内层默认拿不到手势 —— 外层把它吃掉了。
 * 长相是「下拉框列出来了，但**划不动**，只能看见前几项」，而且**不报错、不留痕**：
 * 日志正常、快照正常，且 **RN Web 与 iOS 上都是好的** —— 只有 Android 真机会坏。
 * 所以它跑不出 E2E，只会从用户那边回来。
 *
 * 2026-08-15 实测（Google Sheet 反馈「新建计划产品类型无法滑动」）：
 * `dispatcher/plan/PlanCreateScreen`（产品类型 / 客户两个下拉）与
 * `factory-admin/ai-analysis/CreatePlanScreen`（产品类型下拉）三处都没带。
 * 两个屏都叫「新建计划」，用户报的可能是其中任意一个 —— 一起修。
 *
 * ⚠️ 这不是「没人想到」，是**漂移**：仓里 `MaterialBatchPicker` / `WipBatchPicker`
 * 这两个同形态的选择器**一直是对的**。同一个约定有 N 处实现，只有几处漏了 —— 用闸钉住。
 *
 * 判据只认**真嵌套**，不认「限了高就算」：
 * 第一版闸拿「style 里有 maxHeight」当代理判据，报出 43 条，其中绝大多数是 Modal /
 * BottomSheet 里的独立列表，**根本没有外层 ScrollView**。一道天天误报的闸最后一定被关掉，
 * 那时它的覆盖率归零。宁可窄而可信。
 *
 * ⚠️ 代理判据的边界（写出来，别让下一个人以为它是全的）：
 *   - 只看**同一个文件里**的 JSX 嵌套。跨组件边界的嵌套（父屏的 ScrollView 里渲染了
 *     另一个文件的 <Foo/>，而 Foo 内部又是 ScrollView）**看不见**。
 *   - `horizontal` 的内层不算 —— 横向套纵向不抢手势。
 *   - FlatList / SectionList 的同类问题不在本闸范围内。
 *
 * 用 AST 而不是正则：正则数的是文本，闸要守的是结构。
 */
describe('闸: 同向嵌套的 ScrollView 必须 nestedScrollEnabled', () => {
  const SRC = path.join(__dirname, '..', '..', '..');

  const collect = (dir: string, acc: string[] = []): string[] => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        if (e.name === '__tests__' || e.name === 'node_modules') continue;
        collect(full, acc);
      } else if (e.name.endsWith('.tsx')) {
        acc.push(full);
      }
    }
    return acc;
  };

  const attrsOf = (tag: ts.JsxOpeningElement | ts.JsxSelfClosingElement) =>
    tag.attributes.properties.filter(ts.isJsxAttribute);

  const hasAttr = (tag: ts.JsxOpeningElement | ts.JsxSelfClosingElement, name: string, root: ts.SourceFile) =>
    attrsOf(tag).some((a) => a.name.getText(root) === name);

  it('每个同向嵌套的内层 ScrollView 都带 nestedScrollEnabled', () => {
    const files = collect(SRC);

    // 仪器自检 ①: 扫不到文件时下面的断言恒真
    expect(files.length).toBeGreaterThan(150);

    const nested: string[] = [];
    const offenders: string[] = [];

    for (const file of files) {
      const source = fs.readFileSync(file, 'utf-8');
      const root = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
      const rel = path.relative(SRC, file).replace(/\\/g, '/');

      // insideVertical = 祖先链上存在一个「纵向 ScrollView」
      const visit = (node: ts.Node, insideVertical: boolean): void => {
        let childrenInsideVertical = insideVertical;

        const tag: ts.JsxOpeningElement | ts.JsxSelfClosingElement | null = ts.isJsxElement(node)
          ? node.openingElement
          : ts.isJsxSelfClosingElement(node)
            ? node
            : null;

        if (tag && tag.tagName.getText(root) === 'ScrollView') {
          const isHorizontal = hasAttr(tag, 'horizontal', root);

          if (insideVertical && !isHorizontal) {
            const line = root.getLineAndCharacterOfPosition(tag.getStart(root)).line + 1;
            const where = `${rel}:${line}`;
            nested.push(where);
            if (!hasAttr(tag, 'nestedScrollEnabled', root)) offenders.push(where);
          }

          if (!isHorizontal) childrenInsideVertical = true;
        }

        ts.forEachChild(node, (child) => visit(child, childrenInsideVertical));
      };

      visit(root, false);
    }

    // 仪器自检 ②: 一个嵌套都没找到时, 下面那条断言恒真 —— 那是闸坏了, 不是代码干净了
    expect(nested.length).toBeGreaterThanOrEqual(3);

    expect(offenders).toEqual([]);
  });
});
