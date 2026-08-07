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
      const settle = async () => {
        await page.locator('.vue-flow__node').first()
          .waitFor({ state: 'attached', timeout: 20_000 })
          .catch(() => {});
      };
      await settle();

      const count = (selector) => page.locator(selector).count();

      /**
       * ⛔ 2026-08-06: 这条场景**长期 UNVERIFIED**, 原因不是画布坏了, 是它落在
       * 页面**默认选中的那个产品**上, 而那个产品恰好一个工序节点都没有。
       * 于是每次都走「数据前提不满足」的分支 —— 一条永远不会给出结论的检查,
       * 比没有这条检查更糟(它看起来在跑)。
       *
       * 所以让场景**自己换一个产品再看**: 打开「归属对象」下拉, 逐个选前 N 个选项,
       * 命中有工序节点的就停。只点下拉与选项, 不碰任何写按钮。
       *
       * ⚠️ 第一版是在页面里 `fetch(/product-types/options)` 拿候选 —— **跑了 20 分钟
       * 不返回**: 只读 harness 的 mutation guard 会 abort 非白名单请求, 被 abort 的
       * promise 永远 pending, `page.evaluate` 就挂死了, 而且当时没有任何超时兜底。
       * 教训写在这: **只读场景里不要自己发请求**(要的数据页面已经渲染出来了),
       * 且**每一步都必须有超时 + 整体 deadline**, 否则一个 hang 会拖垮整轮。
       */
      let processNodes = await count('.vue-flow__node-process');
      const probedProducts = [];
      if (processNodes === 0) {
        const DEADLINE_MS = 90_000;
        const MAX_TRIES = 6;
        const startedAt = Date.now();
        const ownerSelect = page.locator('.el-select').first();

        for (let index = 0; index < MAX_TRIES; index += 1) {
          if (Date.now() - startedAt > DEADLINE_MS) {
            probedProducts.push({ note: 'deadline reached, stop probing' });
            break;
          }
          try {
            await ownerSelect.click({ timeout: 5_000 });
            // 选项挂在 body 下的 popper 里, 懒渲染 —— 等它出来再数
            const options = page.locator('.el-select-dropdown:visible .el-select-dropdown__item');
            await options.first().waitFor({ state: 'visible', timeout: 5_000 });
            const total = await options.count();
            if (index >= total) {
              await page.keyboard.press('Escape').catch(() => {});
              break;
            }
            const label = (await options.nth(index).innerText().catch(() => '')).trim();
            await options.nth(index).click({ timeout: 5_000 });
            await page.waitForLoadState('networkidle', { timeout: 6_000 }).catch(() => {});
            await settle();
            processNodes = await count('.vue-flow__node-process');
            probedProducts.push({ option: label.slice(0, 60), processNodes });
            if (processNodes > 0) break;
          } catch (error) {
            probedProducts.push({ index, error: String(error && error.message).slice(0, 120) });
            await page.keyboard.press('Escape').catch(() => {});
            break;
          }
        }
      }
      const materialNodes = await count('.vue-flow__node-material');
      const auxiliaryCells = await count('.vue-flow__node-bomAuxiliary');
      const packagingCells = await count('.vue-flow__node-bomPackaging');
      const auxDetailEntries = await count('[data-testid="aux-open-detail"]');
      const packSubtitles = await count('[data-testid="pack-subtitle"]');

      /**
       * ⛔ 2026-08-06 修正: 这里原本断言「缺 BOM 横幅出现时必须有『去 BOM 配置』入口」——
       * 那是**旧设计**。冷启动已在画布内闭环(PR#2314): 零版本时直接点辅料/包材/副产 cell
       * 就会建首版草稿, 横幅不再把用户支去别的页面, 文案换成了「直接在下方的
       * 辅料 / 包材 / 副产 cell 上配置即可」。
       * 仓里 `bomMenuRetired.source.spec.ts` 已经明确断言**不许再出现**「去 BOM 配置」。
       *
       * 探针没跟上产品口径, 于是它一旦真的跑起来就报一条**假缺陷** ——
       * 比它此前长期 UNVERIFIED 更坏(UNVERIFIED 至少不冤枉人)。
       * 判据: **改了产品口径要回头看有没有闸还在断言旧口径**, 否则修好探针的同一天
       * 就会收到一条假报警。
       */
      const bomMissingBannerVisible = body.includes('暂未读取到生效 BOM');
      // 现在要求的是「横幅把人指回 cell」, 而不是把人支走
      const bomEntryCopyVisible = body.includes('辅料 / 包材 / 副产 cell 上配置');
      const legacyJumpCopyVisible = body.includes('去 BOM 配置');
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
        legacyJumpCopyVisible,
        legacyDrawerCopyVisible,
        probedProducts,
        note: 'No button is clicked; entry is verified by copy and presence only.',
      };

      // 数据前提: 没有工序节点就没有可派生浮层的宿主, 不能据此判缺陷。
      // ⚠️ 走到这里意味着**试过的每一个产品**都没有工序节点(见 probedProducts),
      // 不再是「默认那个恰好是空的」—— 这时 UNVERIFIED 才是诚实的结论。
      if (processNodes === 0) {
        return {
          ...evidence,
          contractFailures: [],
          precondition: probedProducts.length
            ? `probed ${probedProducts.length} products, none has a PROCESS node`
            : 'canvas has no PROCESS node and no product candidates could be listed',
          assessment: { result: 'UNVERIFIED', rootCauseClass: 'data' },
        };
      }

      const contractFailures = [];
      if (auxiliaryCells === 0) contractFailures.push('auxiliary overlay cell missing while process nodes exist');
      if (auxiliaryCells > 0 && auxDetailEntries === 0) contractFailures.push('auxiliary cell rendered without its detail entry');
      if (bomMissingBannerVisible && !bomEntryCopyVisible) {
        contractFailures.push('missing-BOM banner rendered without its on-canvas 「辅料 / 包材 / 副产 cell」 guidance');
      }
      // 旧的「支走用户」入口已下线, 任何状态下都不该再出现
      if (legacyJumpCopyVisible) contractFailures.push('retired 「去 BOM 配置」 jump-away entry still rendered');
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
