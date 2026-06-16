<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import { User, Lock } from '@element-plus/icons-vue';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();

const formRef = ref();
const loading = ref(false);

const loginForm = reactive({
  username: '',
  password: '',
  rememberMe: false
});

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少6位', trigger: 'blur' }
  ]
};

async function handleLogin() {
  const form = formRef.value;
  if (!form) return;

  await form.validate(async (valid: boolean) => {
    if (!valid) return;

    loading.value = true;
    try {
      const success = await authStore.login(loginForm.username, loginForm.password);

      if (success) {
        ElMessage.success('登录成功');

        // 跳转到原页面或首页
        const redirect = route.query.redirect as string;
        router.push(redirect || '/dashboard');
      }
      // R18-ext fix #280: do not show a hardcoded "用户名或密码错误" on failure.
      // The response interceptor already surfaces the server's actual message
      // (e.g. "用户账号已被禁用", "用户名或密码错误") from data.message. A second
      // toast here just stacked misleading info on the screen.
    } catch (error) {
      console.error('Login error:', error);
      // Interceptor shows server message for API errors; only fallback for
      // thrown non-ApiError cases (network, unexpected JS).
    } finally {
      loading.value = false;
    }
  });
}

// 快捷登录 (开发用)
function quickLogin(username: string) {
  loginForm.username = username;
  loginForm.password = '123456';
}
</script>

<template>
  <div class="login-page">
    <!-- Geometric pattern overlay -->
    <div class="login-bg-pattern"></div>
    <div class="login-container">
      <!-- Logo 区域 -->
      <div class="login-header">
        <div class="login-logo-wrap">
          <img src="/logo.svg" alt="Logo" class="login-logo" />
        </div>
        <h1 class="login-title">白垩纪AI Agent</h1>
        <p class="login-subtitle">企业级智能管理平台</p>
      </div>

      <!-- 登录表单 -->
      <el-form
        ref="formRef"
        :model="loginForm"
        :rules="rules"
        class="login-form"
        @keyup.enter="handleLogin"
      >
        <el-form-item prop="username">
          <el-input
            v-model="loginForm.username"
            placeholder="请输入用户名"
            size="large"
            :prefix-icon="User"
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="loginForm.rememberMe">记住我</el-checkbox>
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          :loading="loading"
          class="login-button"
          @click="handleLogin"
        >
          {{ loading ? '登录中...' : '登 录' }}
        </el-button>

        <!-- UX P2-4: 忘记密码提示 — prod 无自助 reset, 引导联系管理员 -->
        <div class="forgot-tip">
          <el-popover placement="top" trigger="click" width="280">
            <template #reference>
              <a class="forgot-link">忘记密码?</a>
            </template>
            <div style="padding: 4px 0">
              <p style="margin: 0 0 8px; font-weight: 500">请联系您的工厂管理员</p>
              <p style="margin: 0; color: #909399; font-size: 12px; line-height: 1.6">
                由于账号权限与工厂绑定, 密码重置需通过管理员在"人事管理 → 账号管理"中操作.
                如您就是管理员, 请联系平台 superadmin.
              </p>
            </div>
          </el-popover>
        </div>
      </el-form>

      <!-- 快捷登录 (开发环境) -->
      <div class="quick-login">
        <p class="quick-title">快捷登录 (测试账号，密码均为 123456)</p>
        <div class="quick-buttons">
          <el-button size="small" type="danger" @click="quickLogin('factory_admin1')">
            工厂总监
          </el-button>
          <el-button size="small" type="primary" @click="quickLogin('hr_admin1')">
            人事经理
          </el-button>
          <el-button size="small" type="success" @click="quickLogin('dispatcher1')">
            调度
          </el-button>
          <el-button size="small" type="warning" @click="quickLogin('warehouse_mgr1')">
            仓储经理
          </el-button>
          <el-button size="small" type="info" @click="quickLogin('finance_mgr1')">
            财务经理
          </el-button>
          <el-button size="small" @click="quickLogin('viewer1')">
            访客
          </el-button>
          <el-button size="small" style="background: #FF5630; color: #fff; border-color: #FF5630" @click="quickLogin('restaurant_admin1')">
            餐饮管理
          </el-button>
        </div>
        <p class="quick-note">* 一线员工 (operator1) 仅限移动端登录</p>
      </div>

      <!-- 底部信息 -->
      <div class="login-footer">
        <p>© 2026 白垩纪科技. All rights reserved.</p>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0C1929 0%, #14507F 50%, #1B65A8 100%);
  padding: 20px;
  position: relative;
  overflow: hidden;
}

.login-bg-pattern {
  position: absolute;
  inset: 0;
  opacity: 0.06;
  background-image:
    radial-gradient(circle at 25% 25%, rgba(255, 255, 255, 0.2) 1px, transparent 1px),
    radial-gradient(circle at 75% 75%, rgba(255, 255, 255, 0.15) 1px, transparent 1px);
  background-size: 40px 40px;
  pointer-events: none;
}

.login-container {
  width: 100%;
  max-width: 420px;
  background: #fff;
  border-radius: 16px;
  padding: 48px 40px;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.25);
  position: relative;
  z-index: 1;
  animation: slideUp 0.5s ease-out;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(24px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.login-header {
  text-align: center;
  margin-bottom: 36px;

  .login-logo-wrap {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 72px;
    height: 72px;
    border-radius: 16px;
    background: linear-gradient(135deg, #1B65A8, #2B7EC1);
    box-shadow: 0 8px 24px rgba(27, 101, 168, 0.3);
    margin-bottom: 20px;
  }

  .login-logo {
    width: 42px;
    height: 42px;
    filter: brightness(0) invert(1);
  }

  .login-title {
    font-size: 24px;
    font-weight: 700;
    color: var(--color-text-primary, #1A2332);
    margin: 0 0 8px;
  }

  .login-subtitle {
    font-size: 14px;
    color: var(--color-text-secondary, #7A8599);
    margin: 0;
    letter-spacing: 0.5px;
  }
}

.login-form {
  :deep(.el-input__wrapper) {
    transition: box-shadow 0.25s ease;

    &:focus-within {
      box-shadow: 0 0 0 2px rgba(27, 101, 168, 0.2) !important;
    }
  }

  .login-button {
    width: 100%;
    margin-top: 12px;
    height: 44px;
    font-size: 15px;
    font-weight: 600;
    letter-spacing: 2px;
    border-radius: var(--radius-sm, 6px);
    background: linear-gradient(135deg, #1B65A8, #2B7EC1);
    border: none;
    transition: all 0.3s ease;

    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 6px 20px rgba(27, 101, 168, 0.35);
    }

    &:active {
      transform: translateY(0);
    }
  }
}

.forgot-tip {
  margin-top: 12px;
  text-align: right;
}

.forgot-link {
  color: #909399;
  font-size: 13px;
  cursor: pointer;
  text-decoration: none;

  &:hover {
    color: #1B65A8;
    text-decoration: underline;
  }
}

.quick-login {
  margin-top: 28px;
  padding-top: 24px;
  border-top: 1px solid var(--border-color-light, #EDF2F7);

  .quick-title {
    font-size: 12px;
    color: var(--color-text-secondary, #7A8599);
    text-align: center;
    margin: 0 0 12px;
  }

  .quick-buttons {
    display: flex;
    gap: 8px;
    justify-content: center;
    flex-wrap: wrap;
  }

  .quick-note {
    font-size: 11px;
    color: var(--color-text-placeholder, #A0AEC0);
    text-align: center;
    margin: 12px 0 0;
  }
}

.login-footer {
  margin-top: 32px;
  text-align: center;

  p {
    font-size: 12px;
    color: var(--color-text-secondary, #7A8599);
    margin: 0;
  }
}

@media (max-width: 480px) {
  .login-container {
    padding: 32px 20px;
    border-radius: 12px;
  }
  .login-header {
    margin-bottom: 24px;

    .login-title {
      font-size: 20px;
    }
  }
}
</style>
