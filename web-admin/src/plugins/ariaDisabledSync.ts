/**
 * el-input-number 的 aria-disabled 陈旧修复 (app 级, 一处覆盖全仓)
 *
 * ## 缺陷
 * Element Plus 的 `el-input-number` 只在 `onMounted` 里对内层原生 input
 * 写一次 `setAttribute('aria-disabled', String(inputNumberDisabled))`,
 * 而它的 `onUpdated` **只刷 `aria-valuenow`**。
 * 于是 `:disabled` 在挂载之后由 true 翻 false 时:
 *   - 原生 `input.disabled` 已正确变 false (响应式绑定, 走 el-input)
 *   - 外层 `.el-input-number` 的 `is-disabled` 类也已正确移除
 *   - **唯独 `aria-disabled` 停在 "true"**
 *
 * ## 影响
 *   - 鼠标用户无感 (控件其实能用)
 *   - **读屏用户被告知「已禁用」** —— 真实无障碍缺陷
 *   - **Playwright 据 `aria-disabled` 一律拒绝 click/fill** —— 逐道报工那条
 *     「勾选用 → 填投料数量」的流程用自动化走不完
 *
 * ## 为什么不升级 Element Plus
 * 已实测: 撰写时最新的 2.14.3 同一处仍只在 `onMounted` 写 `aria-disabled`,
 * `onUpdated` 仍只刷 `aria-valuenow` —— **升级修不了**。
 *
 * ## 为什么放在 app 级而不是逐处改
 * 全仓 436 处 `el-input-number`, 其中 32 处 `:disabled` 是动态的 (可挂载后翻转),
 * 逐处改既漏又难维护。与本文件同目录的 ElMessage 全局补丁同一取舍
 * (见 main.ts「Global override 避免 114 文件逐一改」)。
 *
 * ## 修法
 * `aria-disabled` 应当**镜像原生 `input.disabled`** —— 后者是 Vue 的响应式绑定,
 * 任何时刻都是权威值 (它同时覆盖了 EP 的表单级 disabled 继承)。
 * 在 `updated` 时把二者对齐即可, 不碰组件内部实现。
 */
import type { App, ComponentPublicInstance } from 'vue';

/** 只处理这个组件, 其余组件的 updated 立即返回 */
const TARGET_COMPONENT_NAME = 'ElInputNumber';

/**
 * 把 root 内层原生 input 的 aria-disabled 对齐到它自己的 disabled。
 * 导出以便单测直接验证, 且对非元素节点 (v-if 时 $el 可能是注释节点) 安全。
 */
export function syncAriaDisabled(root: unknown): void {
  const host = root as { querySelector?: (s: string) => Element | null } | null | undefined;
  const input = host?.querySelector?.('input') as HTMLInputElement | null;
  if (!input) return;
  const expected = String(input.disabled);
  if (input.getAttribute('aria-disabled') !== expected) {
    input.setAttribute('aria-disabled', expected);
  }
}

/**
 * 全局 mixin: 每个组件 updated 后先比一次组件名, 非目标组件立即返回。
 * 用 `this.$.type.name` 而不是 `this.$options.name` —— 前者是直接属性读取,
 * 后者会走一遍合并选项解析。
 */
export const ariaDisabledSyncMixin = {
  updated(this: ComponentPublicInstance) {
    const type = this.$?.type as { name?: string } | undefined;
    if (type?.name !== TARGET_COMPONENT_NAME) return;
    syncAriaDisabled(this.$el);
  },
};

export default {
  install(app: App): void {
    app.mixin(ariaDisabledSyncMixin);
  },
};
