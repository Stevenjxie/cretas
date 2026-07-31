/**
 * Vitest global test setup for web-admin.
 *
 * Provides localStorage mock and cleans up between tests.
 *
 * NOTE: Pinia init was attempted twice (PR #635 + PR #638) but reverted both
 * times. Each attempt surfaces more pre-existing TS errors + vitest assertion
 * failures (18+ TS, 5+ vitest tests through 3 CI rounds). Re-enabling Pinia
 * requires a dedicated multi-PR rabbit-hole cleanup. Tracked in #636.
 */
import { beforeEach, afterEach } from 'vitest';
import { enableAutoUnmount } from '@vue/test-utils';

// 每个 spec 挂载出来的组件在用例结束时自动卸载。
//
// 为什么放全局而不是各 spec 自己写 afterEach: 全仓 78 个挂载组件的 spec 里,
// 改动前只有 7 个卸载。#2110 的形态是 ProductProcessWorkflowEditor.spec 挂载
// 26 次一次不卸载, 每个实例留一个 2.5s 的 autosave 定时器(实测文件跑完残留 67 个
// 定时器, 其中 26 个是 2500ms), 它们在环境拆卸窗口里 console.error, 日志经
// onUserConsoleLog RPC 回主进程时通道正在关闭 →
// `EnvironmentTeardownError: Closing rpc while "onUserConsoleLog" was pending`,
// 表现是**单测全过但 npm test exit 1**, 且只在整套跑时偶发。
//
// 逐个 spec 补 afterEach 是「发现一个修一个」; 放这里, 组件的 onUnmounted 清理契约
// 从此**每个用例都真的执行一次** —— 这类泄漏在结构上不再发生。
//
// 上闸前实测过 fallout: 打开后全套 303 files / 2308 tests **零新增失败**。
// 我原本以为「可能立刻翻出一批失败」, 实测推翻了那个猜测。
enableAutoUnmount(afterEach);

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  localStorage.clear();
});
