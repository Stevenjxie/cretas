<template>
  <div class="demo-loading">
    <div class="demo-card">
      <div class="demo-title">白垩纪 AI Agent</div>
      <div class="demo-subtitle">演示模式 · 免登录</div>

      <template v-if="!error">
        <div class="demo-spinner"></div>
        <p class="demo-hint">正在加载演示数据，请稍候…</p>
      </template>

      <template v-else>
        <p class="demo-error">{{ error }}</p>
        <button class="demo-btn" @click="enterDemo">重试</button>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';

// 公开演示账号 (qhj_prod) —— 一个无真实客户的演示租户, 凭证可公开。
// TODO(后期): 后端加只读 demo-token (/auth/demo-login) 后, 这里改为调用该端点,
//   不再把密码放进前端 bundle, 并由后端硬锁所有写操作。
const DEMO_USERNAME = 'qhj_prod';
const DEMO_PASSWORD = '123456';

const router = useRouter();
const authStore = useAuthStore();
const error = ref('');

async function enterDemo() {
  error.value = '';
  try {
    // 清掉任何已有登录态, 保证始终以演示账号进入 (避免上一个访客的缓存身份)
    authStore.clearAuth();
    const ok = await authStore.login(DEMO_USERNAME, DEMO_PASSWORD);
    if (ok) {
      // replace 而非 push: 防止用户点后退又回到 /demo 造成死循环
      router.replace('/dashboard');
    } else {
      error.value = '演示账号加载失败，请点击重试';
    }
  } catch (e) {
    console.error('Demo auto-login failed:', e);
    error.value = '演示账号加载失败，请点击重试';
  }
}

onMounted(enterDemo);
</script>

<style scoped>
.demo-loading {
  height: 100vh;
  width: 100vw;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1e3a5f 0%, #2c5282 60%, #3182ce 100%);
}
.demo-card {
  text-align: center;
  color: #fff;
  padding: 48px 64px;
}
.demo-title {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 2px;
}
.demo-subtitle {
  margin-top: 8px;
  font-size: 15px;
  opacity: 0.8;
  letter-spacing: 1px;
}
.demo-spinner {
  width: 40px;
  height: 40px;
  margin: 32px auto 20px;
  border: 4px solid rgba(255, 255, 255, 0.25);
  border-top-color: #fff;
  border-radius: 50%;
  animation: demo-spin 0.9s linear infinite;
}
@keyframes demo-spin {
  to { transform: rotate(360deg); }
}
.demo-hint {
  font-size: 14px;
  opacity: 0.85;
  margin: 0;
}
.demo-error {
  margin: 28px 0 20px;
  color: #ffd6d6;
  font-size: 15px;
}
.demo-btn {
  padding: 10px 28px;
  font-size: 15px;
  color: #2c5282;
  background: #fff;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: opacity 0.2s;
}
.demo-btn:hover { opacity: 0.85; }
</style>
