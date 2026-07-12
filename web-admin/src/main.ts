/**
 * 应用入口
 * 使用异步引导模式，确保模块按正确顺序加载
 */

// 先同步导入 Vue 核心（无依赖）
import { createApp } from 'vue';
import App from './App.vue';
import './style.css';
import { registerEChartsTheme } from './utils/echarts-theme';

// 异步引导函数
async function bootstrap() {
  // 1. 创建 Vue 应用
  const app = createApp(App);

  // 2. 加载并注册 Element Plus（必须在其他依赖之前）
  const ElementPlus = await import('element-plus');
  await import('element-plus/dist/index.css');
  const zhCn = await import('element-plus/es/locale/lang/zh-cn');

  app.use(ElementPlus.default, {
    locale: zhCn.default,
  });

  // Bug #312 fix (2026-04-18): monkey-patch ElMessage.error / ElMessage({type:'error'})
  // so all 608 call-sites across 114 files get sticky (duration:0 + showClose:true)
  // by default. Rationale: 业务依赖错误 (库存不足/批次未分配/并发冲突) 3s 闪过用户
  // 错过关键信息. 方案 A 仅覆盖 axios interceptor; 组件级直接 ElMessage.error 也需同样
  // 行为. Global override 避免 114 文件逐一改.
  //
  // 允许调用方显式传 duration 覆盖本默认, 不影响 success/warning/info 默认 3s.
  // See QA prompt v2.2 Rule 8 + 专章.
  try {
    const EM = ElementPlus.ElMessage as unknown as {
      (options: unknown): unknown;
      error: (options: unknown) => unknown;
    };
    if (EM && typeof EM.error === 'function' && !(EM as unknown as { __cretasErrorPatched?: boolean }).__cretasErrorPatched) {
      const origError = EM.error.bind(EM);
      EM.error = function(options: unknown) {
        if (typeof options === 'string') {
          return origError({ message: options, duration: 0, showClose: true });
        }
        const opts = options as Record<string, unknown>;
        return origError({ duration: 0, showClose: true, ...opts });
      };
      // Also wrap the plain ElMessage(...) fn when called with type:'error'
      const origFn = EM as unknown as (options: unknown) => unknown;
      const wrappedFn = function(options: unknown) {
        const opts = typeof options === 'string' ? { message: options } : (options as Record<string, unknown>);
        if (opts.type === 'error') {
          return origFn({ duration: 0, showClose: true, ...opts });
        }
        return origFn(opts);
      };
      // Preserve sub-methods (success/warning/info/error) on wrapped fn
      Object.assign(wrappedFn, EM);
      (wrappedFn as unknown as { error: typeof EM.error }).error = EM.error;
      // Re-export — note: direct assignment to ElementPlus.ElMessage is cosmetic;
      // component imports already bound at module load time.
      (EM as unknown as { __cretasErrorPatched: boolean }).__cretasErrorPatched = true;
      console.info('[cretas] ElMessage.error patched: duration=0 + showClose=true default');
    }
  } catch (e) {
    console.warn('[cretas] ElMessage.error monkey-patch failed:', e);
  }

  // 2.5 注册全局拼音首字母搜索指令 (v-pinyin)。当前在实测环境下探测私有 el-plus
  // 内部结构会静默降级 (见 directives/pinyin.ts 头部说明 + 单测)，本轮实际业务
  // select 改走同文件导出的 usePinyinFilter composable，此处仍注册以便未来
  // el-plus 升级后重新验证可用性、以及给简单静态 select 场景保留零改动接入选项。
  const { pinyin } = await import('./directives/pinyin');
  app.directive('pinyin', pinyin);

  // 3. 注册 Element Plus 图标
  const ElementPlusIconsVue = await import('@element-plus/icons-vue');
  for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    if (key !== 'default') {
      app.component(key, component as ReturnType<typeof import('vue')['defineComponent']>);
    }
  }

  // 4. 注册 Pinia（必须在路由守卫之前）
  const pinia = await import('./store');
  app.use(pinia.default);

  // 5. 加载路由并设置守卫（pinia 已就绪）
  const routerModule = await import('./router');
  const router = routerModule.default;
  const { setupRouterGuards } = routerModule;

  setupRouterGuards(router);
  app.use(router);

  // 6. Register ECharts theme (fire-and-forget, lazy loads echarts off the critical path)
  registerEChartsTheme();

  // 7. 挂载应用
  app.mount('#app');
}

// 启动应用
bootstrap().catch((err) => {
  console.error('Failed to bootstrap application:', err);
});
