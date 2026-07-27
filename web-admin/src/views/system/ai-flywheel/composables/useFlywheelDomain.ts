/**
 * 飞轮运营台 5 个页面共用的 domain 选择状态 (模块级单例 ref, 跨页面导航保留选择)。
 * 首发只有 restaurant, 但字段从第一天就在 (per spec §7 平台化通用准备)。
 */
import { ref } from 'vue';

const domain = ref<string>('restaurant');

export function useFlywheelDomain() {
  return { domain };
}
