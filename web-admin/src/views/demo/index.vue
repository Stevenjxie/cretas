<template>
  <div class="demo-page">
    <div class="demo-inner">
      <div class="demo-header">
        <div class="demo-title">白垩纪 AI Agent</div>
        <div class="demo-subtitle">扫码即看 · 免登录演示</div>
      </div>

      <!-- 登录中 -->
      <div v-if="loading" class="demo-loading">
        <div class="demo-spinner"></div>
        <p>正在进入{{ loadingLabel }}，请稍候…</p>
      </div>

      <!-- 选择业态 -->
      <div v-else class="demo-choices">
        <button class="demo-choice demo-choice--factory" @click="enter('factory')">
          <div class="demo-choice-icon">🏭</div>
          <div class="demo-choice-name">工厂演示</div>
          <div class="demo-choice-desc">食品加工厂 · 生产 / 采购 / 进销存 / 经营驾驶舱 / AI 分析</div>
        </button>
        <button class="demo-choice demo-choice--rest" @click="enter('rest')">
          <div class="demo-choice-icon">🍽️</div>
          <div class="demo-choice-name">餐饮演示</div>
          <div class="demo-choice-desc">连锁餐饮 · 营收 / 损耗 / 配方 / 经营驾驶舱 / AI 问答</div>
        </button>
      </div>

      <p v-if="error" class="demo-error">{{ error }}</p>
      <p class="demo-foot">演示数据均为脱敏示例 · 仅供浏览</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';

const router = useRouter();
const authStore = useAuthStore();
const loading = ref(false);
const error = ref('');
const loadingLabel = ref('');

async function enter(tenant: 'factory' | 'rest') {
  error.value = '';
  loadingLabel.value = tenant === 'factory' ? '工厂演示' : '餐饮演示';
  loading.value = true;
  try {
    // 清掉任何已有登录态, 保证以演示账号进入 (避免上一个访客的缓存身份)
    authStore.clearAuth();
    const ok = await authStore.demoLogin(tenant);
    if (ok) {
      // replace: 防止用户后退又回到 /demo
      router.replace('/dashboard');
    } else {
      error.value = '演示加载失败，请重试';
      loading.value = false;
    }
  } catch (e) {
    console.error('Demo enter failed:', e);
    error.value = '演示加载失败，请重试';
    loading.value = false;
  }
}
</script>

<style scoped>
.demo-page {
  min-height: 100vh;
  width: 100vw;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1e3a5f 0%, #2c5282 55%, #3182ce 100%);
  color: #fff;
  padding: 24px;
  box-sizing: border-box;
}
.demo-inner { text-align: center; max-width: 760px; width: 100%; }
.demo-header { margin-bottom: 36px; }
.demo-title { font-size: 30px; font-weight: 700; letter-spacing: 2px; }
.demo-subtitle { margin-top: 10px; font-size: 16px; opacity: 0.82; letter-spacing: 1px; }

.demo-choices {
  display: flex;
  gap: 24px;
  justify-content: center;
  flex-wrap: wrap;
}
.demo-choice {
  flex: 1 1 280px;
  max-width: 340px;
  min-width: 240px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 16px;
  padding: 36px 28px;
  color: #fff;
  cursor: pointer;
  transition: transform 0.18s, background 0.18s, box-shadow 0.18s;
  backdrop-filter: blur(2px);
}
.demo-choice:hover {
  transform: translateY(-6px);
  background: rgba(255, 255, 255, 0.18);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.25);
}
.demo-choice-icon { font-size: 52px; line-height: 1; }
.demo-choice-name { margin-top: 16px; font-size: 22px; font-weight: 600; }
.demo-choice-desc { margin-top: 12px; font-size: 13px; line-height: 1.6; opacity: 0.8; }

.demo-loading { padding: 48px 0; }
.demo-spinner {
  width: 44px; height: 44px; margin: 0 auto 20px;
  border: 4px solid rgba(255, 255, 255, 0.25);
  border-top-color: #fff;
  border-radius: 50%;
  animation: demo-spin 0.9s linear infinite;
}
@keyframes demo-spin { to { transform: rotate(360deg); } }

.demo-error { margin-top: 22px; color: #ffd6d6; font-size: 15px; }
.demo-foot { margin-top: 32px; font-size: 12px; opacity: 0.6; }
</style>
