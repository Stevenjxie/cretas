<script setup lang="ts">
import { ref, computed } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';

/**
 * Sprint 4 W1 C-INLINE-CS-1: 在线客服悬浮入口.
 *
 * 默认折叠为右下角圆形按钮, 点击展开 iframe 嵌入第三方客服系统.
 * iframe URL 从 props 传入 (默认空, 不渲染 iframe 但保留按钮 placeholder).
 * 后续接入实际客服系统 (商务通 / 美洽 / 网易七鱼 等) 时配置 `serviceUrl`.
 *
 * 防呆 R5 (Dead-end → 导航): URL 未配置时不卡用户, 提供"前去系统设置"
 * 二次确认 → router.push('/system'). 避免 placeholder 死胡同.
 */
const props = withDefaults(
  defineProps<{
    serviceUrl?: string;
    title?: string;
  }>(),
  {
    serviceUrl: '',
    title: '在线客服',
  },
);

const router = useRouter();
const expanded = ref(false);

const hasUrl = computed(() => props.serviceUrl.trim().length > 0);

function toggle() {
  expanded.value = !expanded.value;
}

/**
 * R5: dead-end → next action. 用户期望"找客服", 未配时给 actionable 下一步.
 */
async function goConfigure() {
  try {
    await ElMessageBox.confirm(
      '客服系统尚未配置 (需管理员设置 VITE_CUSTOMER_SERVICE_URL 或在系统设置中接入第三方客服). 是否前往系统设置?',
      '客服未配置',
      {
        confirmButtonText: '前去系统设置',
        cancelButtonText: '取消',
        type: 'info',
      },
    );
    expanded.value = false;
    router.push('/system');
  } catch {
    // user cancelled — no-op, panel stays open so user can close manually
  }
}
</script>

<template>
  <div class="inline-cs">
    <!-- 展开面板 -->
    <div v-if="expanded" class="inline-cs__panel" role="dialog" :aria-label="title">
      <div class="inline-cs__header">
        <span>{{ title }}</span>
        <button class="inline-cs__close" type="button" @click="toggle" aria-label="关闭客服">
          ×
        </button>
      </div>
      <div class="inline-cs__body">
        <iframe
          v-if="hasUrl"
          :src="serviceUrl"
          :title="title"
          class="inline-cs__iframe"
          frameborder="0"
        />
        <div v-else class="inline-cs__empty">
          <div class="inline-cs__empty-icon"></div>
          <div class="inline-cs__empty-title">客服系统尚未配置</div>
          <div class="inline-cs__empty-desc">
            请管理员在系统设置中接入第三方客服 (商务通 / 美洽 / 网易七鱼 等),
            或配置 <code>VITE_CUSTOMER_SERVICE_URL</code> 环境变量.
          </div>
          <button
            type="button"
            class="inline-cs__empty-action"
            @click="goConfigure"
          >
            前去系统设置 →
          </button>
        </div>
      </div>
    </div>

    <!-- 折叠按钮 -->
    <button
      v-show="!expanded"
      class="inline-cs__bubble"
      type="button"
      :aria-label="title"
      :title="title"
      @click="toggle"
    >
      <svg
        width="24"
        height="24"
        viewBox="0 0 24 24"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
      >
        <path
          d="M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2z"
          stroke="currentColor"
          stroke-width="2"
          stroke-linejoin="round"
          fill="currentColor"
          fill-opacity="0.15"
        />
      </svg>
    </button>
  </div>
</template>

<style lang="scss" scoped>
.inline-cs {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 1500;
}

.inline-cs__bubble {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  border: none;
  background-color: var(--el-color-primary, #409eff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.18);
  transition: transform 0.2s ease, box-shadow 0.2s ease;

  &:hover {
    transform: scale(1.06);
    box-shadow: 0 6px 16px rgba(0, 0, 0, 0.22);
  }
}

.inline-cs__panel {
  width: 380px;
  height: 500px;
  background-color: #fff;
  border-radius: 12px;
  box-shadow: 0 10px 32px rgba(0, 0, 0, 0.22);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.inline-cs__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background-color: var(--el-color-primary, #409eff);
  color: #fff;
  font-weight: 600;
}

.inline-cs__close {
  background: transparent;
  border: none;
  color: #fff;
  font-size: 24px;
  line-height: 1;
  cursor: pointer;
  padding: 0 4px;
}

.inline-cs__body {
  flex: 1;
  display: flex;
}

.inline-cs__iframe {
  width: 100%;
  height: 100%;
  border: 0;
}

.inline-cs__empty {
  margin: auto;
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 12px;
}

.inline-cs__empty-icon {
  font-size: 36px;
  opacity: 0.5;
}

.inline-cs__empty-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}

.inline-cs__empty-desc {
  font-size: 13px;
  color: var(--el-text-color-secondary, #909399);
  line-height: 1.6;

  code {
    background-color: #f5f7fa;
    padding: 1px 5px;
    border-radius: 3px;
    font-family: 'Consolas', 'Monaco', monospace;
    font-size: 12px;
  }
}

.inline-cs__empty-action {
  margin-top: 4px;
  padding: 8px 18px;
  border-radius: 16px;
  border: 1px solid var(--el-color-primary, #409eff);
  background-color: transparent;
  color: var(--el-color-primary, #409eff);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    background-color: var(--el-color-primary, #409eff);
    color: #fff;
  }
}

@media (max-width: 768px) {
  .inline-cs {
    right: 12px;
    bottom: 12px;
  }
  .inline-cs__panel {
    width: calc(100vw - 24px);
    height: 70vh;
  }
}
</style>
