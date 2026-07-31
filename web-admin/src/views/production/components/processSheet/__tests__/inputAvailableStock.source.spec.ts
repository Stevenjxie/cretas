import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '..', 'ProcessDataTable.vue'), 'utf8');

/**
 * 取一个 function 的完整源码, 用来断言"这个数是从哪儿来的"。
 *
 * 切到**下一个顶格 `}`** 为止 —— 早先按固定字符数截取, 长函数会被拦腰截断,
 * 于是「catch 里也清空了吗」这种问题得到的是假阴性 (看不见 ≠ 不存在)。
 */
function fnBody(name: string): string {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `找不到 function ${name}`).toBeGreaterThan(-1);
  const end = source.indexOf('\n}\n', start);
  expect(end, `function ${name} 没有顶格收尾`).toBeGreaterThan(start);
  return source.slice(start, end);
}

/**
 * 投入行的「生产仓可用」—— 这组契约的**守护对象换过一次**, 别按旧标题理解。
 *
 * 时间线:
 *   2026-07-30 客户: 「填完点正式报工才被告知可用 0只」→ 于是把可用量摆进录入行。
 *   2026-07-31 客户实测: 行内显示「可用 10kg」, 提交时后端说「可用 0kg, 缺少 1kg」。
 *              根因**不是参数调错, 是前端结构上算不出这个数** → 先整块撤下 (#2062)。
 *   2026-07-31 后端补只读接口 `POST .../input-availability`, 前端只负责显示 → 就是现在这一版。
 *
 * 🔴 前端为什么算不出来: `ProductionStockAllocationServiceImpl.plan()` 的口径含三样它拿不到的:
 *   1. `warehouseResolver.resolveWorkshopId()` —— 只认那**一个**生产仓;
 *      前端 `pickConsumableWarehouseIds` 汇总的是原料仓 + 物流仓 + 生产仓。
 *   2. `allocationRepository.sumPendingQuantityByMaterialBatchId()` —— 扣掉**其它草稿行已占用**的量。
 *   3. `ProductionInventoryOwnershipGuard` —— 客供料 / 归属别的订单的批次在仓里, 但本计划不能用。
 *
 * 少任何一样都偏大, 而**一个偏大且看着权威的数字比不显示更糟** —— 仓管员会照着它排活。
 *
 * 所以这里守两条, 缺一不可:
 *   A. 不许再由前端自己算 (那套代码删干净了, 别让人捡回来)
 *   B. 必须显示后端返回的那个值, 拿不到就什么都不显示 (不猜、不留占位、不用上次的)
 *
 * 行为侧 (喂 mock 响应、看渲染出什么) 在同目录 `ProcessDataTable.inputAvailableStock.spec.ts`。
 */
describe('投入行「生产仓可用」: 显示后端权威值 (客户 2026-07-31)', () => {
  describe('A. 不许前端自己算', () => {
    it('自算那套函数与类型一个都不许再出现', () => {
      // 撤下前是 inputStock() 遍历 rawBatchOptions 汇总 + inputStockText() 拼文案
      expect(source).not.toMatch(/function inputStock\s*\(/);
      expect(source).not.toMatch(/function inputStockText\s*\(/);
      expect(source).not.toMatch(/type InputStock\b/);
      // 按自算值标红同样是错的 —— 判据错了, 拿它做的一切都错
      expect(source).not.toMatch(/function inputExceedsAvailable\s*\(/);
      expect(source).not.toContain('need > stock.available');
    });

    it('连单位换算工具都不再 import —— 留着 import 就是留着复活的路', () => {
      // convertQuantityToUnit 在本文件唯一的用途就是自算可用量; 别处要用请另行 import 并说明
      expect(source).not.toContain('convertQuantityToUnit');
      // 自算版的「12件未计入」披露文案, 一并不许回来
      expect(source).not.toMatch(/`可用 \$\{/);
      expect(source).not.toContain('单位不同, 未计入');
    });

    it('三个可用量函数只读 portAvailability, 一律不碰批次列表', () => {
      // 这是「不自算」最实质的一条: 只要它们开始读 rawBatchOptions, 自算就复活了
      for (const name of ['workshopStockText', 'elsewhereStockText', 'workshopStockIsZero']) {
        const body = fnBody(name);
        expect(body, `${name} 必须从后端返回的 portAvailability 取数`)
          .toContain('portAvailability.value.get(');
        expect(body, `${name} 不许读 rawBatchOptions (那是前端自己汇总的)`)
          .not.toContain('rawBatchOptions');
      }
    });

    it('注释里必须留下三条口径差异, 否则后人只当是漏写了', () => {
      expect(source).toContain('resolveWorkshopId');
      expect(source).toContain('sumPendingQuantityByMaterialBatchId');
      expect(source).toContain('ProductionInventoryOwnershipGuard');
    });
  });

  describe('B. 必须显示后端返回的值', () => {
    it('值来自 input-availability 只读接口, 按 workflowPortId 索引', () => {
      expect(source).toContain('getInputAvailability');
      expect(source).toMatch(/portAvailability\s*=\s*ref<Map<string, PortAvailability>>/);
      const loader = fnBody('loadInputAvailability');
      expect(loader).toContain('getInputAvailability(props.factoryId, props.planId, ports)');
      // 按端口索引 —— 同一物料可能挂在多个端口上, 按 materialTypeId 索引会串行
      expect(loader).toContain('next.set(item.workflowPortId, item)');
    });

    it('拿不到就清空: 不猜、不留占位、更不拿上一次的数字顶上去 (禁降级)', () => {
      const loader = fnBody('loadInputAvailability');
      // 前置守卫 + catch 两条路径都必须回到空 Map, 而不是保留旧值 —— 旧值就是过期库存
      expect(loader.match(/portAvailability\.value = new Map\(\)/g) ?? []).toHaveLength(2);
      expect(fnBody('workshopStockText')).toMatch(/if \(!a\) return '';/);
      // 没有 elsewhere 就整段不显示, 不给「其它仓库: 无」这种占位
      expect(fnBody('elsewhereStockText')).toMatch(/if \(!a \|\| !a\.elsewhere\?\.length\) return '';/);
    });

    it('两套模板各一份 —— 该文件历史上出现过卡片/表格漂移', () => {
      const available = source.match(/data-testid="input-available-stock"/g) ?? [];
      expect(available.length, '卡片 + 表格各一份').toBe(2);
      const elsewhere = source.match(/data-testid="input-elsewhere-stock"/g) ?? [];
      expect(elsewhere.length, '「别处另有…待调拨入生产仓」同样两份').toBe(2);
      // 可用 0 时标红的那一份也必须两套都在, 否则表格视图看不出"没货"
      expect((source.match(/workshopStockIsZero\(item\)/g) ?? []).length).toBe(2);
    });
  });

  describe('C. 加这一列没把别的列挤歪', () => {
    it('表格模板: 表头多出的那一列, legacy 行要补占位格', () => {
      // tbody 的第三个 <td> 由行级 usesAutoMaterialTotals(row) 决定, thead 只能按表级
      // workflowRawInputs.length 出 —— 两者必须成对, 否则 legacy 行从这列起整行错位一格。
      expect(source).toMatch(/<th v-if="workflowRawInputs\.length" class="sp-th">生产仓可用<\/th>/);
      expect(source).toMatch(/<td v-if="workflowRawInputs\.length" class="sp-td"><\/td>/);
    });

    it('卡片模板: grid 列数必须等于 sp-in-cell 个数 (4 列, 带选用时 5 列)', () => {
      // 少一列 grid 会开隐式行, 把「来源批次」甩到下一行 —— 表头与数据看着就对不上。
      // 「生产仓可用」那一列就是 minmax(140px, 0.9fr); 它没了 = 又漏改了一次。
      const cols = 'minmax(180px, 1.6fr) 200px minmax(140px, 0.9fr) minmax(200px, 1.2fr)';
      expect(source, '本次投入原料/投料总量/生产仓可用/来源批次 四列')
        .toContain(`grid-template-columns: ${cols};`);
      expect(source, '有可选端口时前面再多一列「选用」')
        .toContain(`grid-template-columns: 56px ${cols};`);
    });
  });

  describe('D. 别连坐 (这几条与本次改动无关, 客户是满意的)', () => {
    it('物料名仍是固定文本, 没有多余下拉', () => {
      const fixed = source.match(/data-testid="bom-authorized-material-fixed"/g) ?? [];
      expect(fixed.length).toBe(2); // 卡片 + 表格两套模板
      expect(source).not.toContain('data-testid="bom-authorized-material-select"');
    });

    it('成品工序投入量保留 advisory 语义', () => {
      expect(source).toContain('function finishedInputOverAvailableHint');
      expect(source).toContain('账实差异由盘点纠正');
    });
  });
});
