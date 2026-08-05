'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

/**
 * BOM 融入工序画布后的三条口径, 都只读可验:
 *
 *   1. 辅料/包材以浮层 cell 形式长在画布上(vue-flow 节点类型 bomAuxiliary / bomPackaging)。
 *   2. 画布不再拉旧的 BOM 抽屉, 入口改成跳 BOM 菜单页, 文案「去 BOM 配置」。
 *      旧文案「在右侧配置 BOM」若还在, 说明按钮行为改了而文案没跟上(上线前正是这处漏过一次)。
 *   3. 不点击任何按钮 —— 入口只验文案与存在性, 跳转由路由单测覆盖。
 *
 * ⚠️ 「画布上没有辅料 cell」有两种成因, 必须分开判, 否则会把没数据报成缺陷
 * (bom-readonly 就是没分开: 产品没有任何 BOM 版本, 却连报 5 条 contract failure):
 *   - 画布压根没有工序节点 → 数据前提不满足 → UNVERIFIED / data
 *   - 有工序节点却没有辅料 cell → 浮层没派生出来 → CONFIRMED_DEFECT / frontend
 */
module.exports = {
  id: 'bom-canvas-overlay-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'bom-canvas-overlay-readonly',
    path: ROUTES.workflow,
    landmarks: ['Workflow'],
    screenshot: true,
    inspect: async (page, body) => {
      // 画布是异步水合的: networkidle 之后节点仍可能未挂载。不等就去数, 会把
      // 「还没渲染完」读成「没有节点」, 于是整条滑进 UNVERIFIED 而看不出真假。
      await page.locator('.vue-flow__node').first()
        .waitFor({ state: 'attached', timeout: 20_000 })
        .catch(() => {});

      const count = (selector) => page.locator(selector).count();
      const processNodes = await count('.vue-flow__node-process');
      const materialNodes = await count('.vue-flow__node-material');
      const auxiliaryCells = await count('.vue-flow__node-bomAuxiliary');
      const packagingCells = await count('.vue-flow__node-bomPackaging');
      const auxDetailEntries = await count('[data-testid="aux-open-detail"]');
      const packSubtitles = await count('[data-testid="pack-subtitle"]');

      // 「去 BOM 配置 →」在 el-alert 里, 由 bomMissingProducts 非空才渲染 —— 是条件文案。
      // 无条件断言它存在会误报, 所以只在缺 BOM 横幅出现时才要求它在。
      const bomMissingBannerVisible = body.includes('暂未读取到生效 BOM');
      const bomEntryCopyVisible = body.includes('去 BOM 配置');
      // 旧抽屉文案则是无条件的: 抽屉已下掉, 它在任何状态下都不该再出现。
      const legacyDrawerCopyVisible = body.includes('在右侧配置 BOM');

      const evidence = {
        processNodes,
        materialNodes,
        auxiliaryCells,
        packagingCells,
        auxDetailEntries,
        packSubtitles,
        bomMissingBannerVisible,
        bomEntryCopyVisible,
        legacyDrawerCopyVisible,
        note: 'No button is clicked; entry is verified by copy and presence only.',
      };

      // 数据前提: 没有工序节点就没有可派生浮层的宿主, 不能据此判缺陷。
      if (processNodes === 0) {
        return {
          ...evidence,
          contractFailures: [],
          precondition: 'canvas has no PROCESS node; overlay cells have nothing to attach to',
          assessment: { result: 'UNVERIFIED', rootCauseClass: 'data' },
        };
      }

      const contractFailures = [];
      if (auxiliaryCells === 0) contractFailures.push('auxiliary overlay cell missing while process nodes exist');
      if (auxiliaryCells > 0 && auxDetailEntries === 0) contractFailures.push('auxiliary cell rendered without its detail entry');
      if (bomMissingBannerVisible && !bomEntryCopyVisible) {
        contractFailures.push('missing-BOM banner rendered without its 「去 BOM 配置」 entry');
      }
      if (legacyDrawerCopyVisible) contractFailures.push('legacy drawer copy 「在右侧配置 BOM」 still rendered');

      return {
        ...evidence,
        contractFailures,
        assessment: contractFailures.length === 0
          ? { result: 'PASS', rootCauseClass: 'none' }
          : { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' },
      };
    },
  }),
};
