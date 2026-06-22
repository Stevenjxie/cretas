<script setup lang="ts">
/**
 * ReturnBanner — FK_BLOCK 防呆导航浮条
 *
 * 当 URL query 含 _returnTo 时显示顶部返回浮条，引导用户处理完关联数据后回原页。
 * fool-proof-design Rule 5: dead-end 改导航，让用户知道从哪里来、怎么回去。
 *
 * 挂载在 AppLayout.vue <main> 顶部 (header 下)，v-if 有 _returnTo 才显示。
 */
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, Close } from '@element-plus/icons-vue';

const route = useRoute();
const router = useRouter();

// _returnTo 来源路径。注意: vue-router 已对 query value 解码一次,
// 这里【不能】再 decodeURIComponent — 否则多级嵌套的 _returnTo
// (计划←订单←产品 级联跳转, 内层 _returnTo 自带编码的 ?/&) 会被过度解码,
// 内层 query 的 ?/& 变成字面量 → 回退时内层参数 (planSO/editItems 等) 丢失,
// 三级级联最后一跳无法重开弹窗。单级返回不受影响 (router 解码后即可直接用)。
const returnTo = computed(() => {
  const raw = route.query._returnTo;
  if (!raw || typeof raw !== 'string') return null;
  return raw;
});

// 反查来源路由的 meta.title（用于友好显示）
const returnTitle = computed(() => {
  if (!returnTo.value) return '';
  // 从路由列表中匹配 fullPath 对应的 title
  const allRoutes = router.getRoutes();
  // returnTo.value 可能含 query，先取 pathname 部分
  const pathname = returnTo.value.split('?')[0];
  // 从长到短匹配 path（子路由）
  const matched = allRoutes
    .filter(r => r.path && pathname === r.path)
    .sort((a, b) => (b.path?.length ?? 0) - (a.path?.length ?? 0));
  if (matched.length > 0 && matched[0].meta?.title) {
    return String(matched[0].meta.title);
  }
  // fallback: 取 pathname 末段作为描述
  const segments = pathname.split('/').filter(Boolean);
  return segments.length > 0 ? segments[segments.length - 1] : '原页面';
});

const handleReturn = () => {
  if (!returnTo.value) return;
  // 移除 _returnTo query，保留其他 query 参数
  const { _returnTo: _, ...restQuery } = route.query;
  void restQuery; // 跳转到来源路径（不带 _returnTo）
  router.push(returnTo.value);
};

const handleDismiss = () => {
  // 删除 _returnTo query，保留当前页面其余 query
  const { _returnTo: _, ...restQuery } = route.query;
  router.replace({ query: restQuery });
};
</script>

<template>
  <div v-if="returnTo" class="return-banner" role="status" aria-live="polite">
    <el-icon class="return-banner__icon"><ArrowLeft /></el-icon>
    <span class="return-banner__text">
      处理完毕后，点击返回
      <strong v-if="returnTitle">「{{ returnTitle }}」</strong>
      <strong v-else>原页面</strong>
    </span>
    <div class="return-banner__actions">
      <el-button
        type="primary"
        size="small"
        plain
        @click="handleReturn"
      >
        <el-icon><ArrowLeft /></el-icon>
        返回原页面
      </el-button>
      <el-button
        text
        size="small"
        class="return-banner__close"
        aria-label="关闭提示"
        @click="handleDismiss"
      >
        <el-icon><Close /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.return-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background-color: var(--el-color-warning-light-9, #fdf6ec);
  border-bottom: 1px solid var(--el-color-warning-light-5, #f5dab1);
  color: var(--el-color-warning-dark-2, #e6a23c);
  font-size: 13px;
  position: relative;
  z-index: 10;

  .return-banner__icon {
    flex-shrink: 0;
    font-size: 14px;
  }

  .return-banner__text {
    flex: 1;
    color: var(--el-text-color-primary, #303133);

    strong {
      color: var(--el-color-primary, #409eff);
    }
  }

  .return-banner__actions {
    display: flex;
    align-items: center;
    gap: 4px;
    flex-shrink: 0;
  }

  .return-banner__close {
    color: var(--el-text-color-secondary, #909399);
    padding: 4px;
    min-height: unset;

    &:hover {
      color: var(--el-text-color-primary, #303133);
    }
  }
}
</style>
