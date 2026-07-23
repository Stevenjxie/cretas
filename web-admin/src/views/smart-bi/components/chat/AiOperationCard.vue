<script setup lang="ts">
/**
 * AI 操作确认卡 (2026-07-23 AI 读写分离 P2)
 *
 * 渲染 操作 tab 的写操作状态响应 (per 防呆五规则):
 * - WRITE_CONFIRM_REQUIRED → 「{intentName}」需要确认 + 「正在生成操作预览…」过渡态
 *   (父组件 AIQuery 收到此状态后自动 previewOnly 重发, 拿到 PREVIEW 后替换 response prop)
 * - PREVIEW + confirmableAction → 操作预览卡: previewData 键值网格 + [确认执行]/[取消]
 *   + expiresInSeconds 倒计时 (到 0 禁用按钮提示重新发起) — confirmToken TCC
 * - PREVIEW 无 confirmableAction → 「该操作暂不支持一键确认」info 卡
 * - NO_PERMISSION / PERMISSION_DENIED → 「需要 {中文名} 权限」卡
 * - DEMO_WRITE_BLOCKED → 演示环境 info 卡
 * - PENDING_APPROVAL → 已提交审批卡
 */
import { ref, computed, watch, onBeforeUnmount } from 'vue';
import { ElMessage } from 'element-plus';
import { Loading, WarningFilled, InfoFilled, Lock, Clock, CircleCheckFilled } from '@element-plus/icons-vue';
import { confirmIntentAction, type IntentExecuteResponse } from '@/api/smartbi/intent-chat';
import { permissionDisplayName } from './permissionNames';

const props = defineProps<{
  response: IntentExecuteResponse;
  factoryId: string;
}>();

const emit = defineEmits<{
  (e: 'confirmed', result: IntentExecuteResponse): void;
  (e: 'cancelled'): void;
}>();

const status = computed(() => String(props.response.status || '').toUpperCase());
const intentName = computed(() => props.response.intentName || props.response.intentCode || '该操作');
const confirmable = computed(() => props.response.confirmableAction ?? null);

// ── 预览键值网格 (Rule 2: 上下文必带身份信息; 跳过系统内部键) ──────────────
const SKIP_KEYS = new Set([
  'factoryId', 'factory_id', 'userId', 'user_id', 'userRole', 'role', 'intentCode', 'userInput',
]);

const previewEntries = computed<Array<[string, string]>>(() => {
  const data = confirmable.value?.previewData;
  if (!data || typeof data !== 'object') return [];
  return Object.entries(data)
    .filter(([k]) => !SKIP_KEYS.has(k))
    .map(([k, v]) => [k, formatValue(v)]);
});

function formatValue(v: unknown): string {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'object') {
    try { return JSON.stringify(v); } catch { return String(v); }
  }
  return String(v);
}

// ── 确认/取消 状态机 ────────────────────────────────────────────────────
type Phase = 'idle' | 'pending' | 'done' | 'cancelled';
const phase = ref<Phase>('idle');
const resultMessage = ref('');
const resultStatus = ref('');

// ── 过期倒计时 (Rule 1: 预先显示边界) ───────────────────────────────────
const remainingSeconds = ref<number | null>(null);
let countdownTimer: ReturnType<typeof setInterval> | null = null;

function stopCountdown() {
  if (countdownTimer) {
    clearInterval(countdownTimer);
    countdownTimer = null;
  }
}

function startCountdown() {
  stopCountdown();
  const seconds = confirmable.value?.expiresInSeconds;
  if (typeof seconds !== 'number' || seconds <= 0) {
    remainingSeconds.value = typeof seconds === 'number' ? 0 : null;
    return;
  }
  remainingSeconds.value = Math.floor(seconds);
  countdownTimer = setInterval(() => {
    if (remainingSeconds.value !== null && remainingSeconds.value > 0) {
      remainingSeconds.value -= 1;
    }
    if (remainingSeconds.value !== null && remainingSeconds.value <= 0) {
      stopCountdown();
    }
  }, 1000);
}

const isExpired = computed(() => remainingSeconds.value !== null && remainingSeconds.value <= 0);

watch(
  () => props.response,
  () => {
    // 父组件把 WRITE_CONFIRM_REQUIRED 换成 PREVIEW 响应时重置本卡状态。
    phase.value = 'idle';
    resultMessage.value = '';
    resultStatus.value = '';
    if (status.value === 'PREVIEW' && confirmable.value) startCountdown();
    else stopCountdown();
  },
  { immediate: true },
);

onBeforeUnmount(stopCountdown);

async function handleConfirm() {
  const action = confirmable.value;
  if (!action || phase.value === 'pending' || isExpired.value) return;
  phase.value = 'pending';
  try {
    const result = await confirmIntentAction(props.factoryId, action.confirmToken, {
      commandDigest: action.commandDigest,
      expiresAt: action.expiresAt,
    });
    stopCountdown();
    phase.value = 'done';
    resultStatus.value = String(result.status || '').toUpperCase();
    resultMessage.value = result.message || result.formattedText
      || (resultStatus.value === 'SUCCESS' ? '操作已执行。' : '操作未完成。');
    emit('confirmed', result);
  } catch (e) {
    phase.value = 'idle';
    const msg = e instanceof Error ? e.message : String(e);
    ElMessage({ message: `确认执行失败: ${msg}`, type: 'error', duration: 0, showClose: true });
  }
}

function handleCancel() {
  stopCountdown();
  phase.value = 'cancelled';
  emit('cancelled');
}

const permissionLabel = computed(() => permissionDisplayName(props.response.requiredPermission));
</script>

<template>
  <!-- 已取消 → 收起成一行 -->
  <div v-if="phase === 'cancelled'" class="op-card-cancelled">已取消</div>

  <!-- WRITE_CONFIRM_REQUIRED: 过渡态, 父组件正在自动生成预览 -->
  <div v-else-if="status === 'WRITE_CONFIRM_REQUIRED'" class="op-card op-card--confirm">
    <div class="op-card-header">
      <el-icon><WarningFilled /></el-icon>
      <span>「{{ intentName }}」需要确认</span>
    </div>
    <div class="op-card-body op-card-transient">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>正在生成操作预览…</span>
    </div>
  </div>

  <!-- PREVIEW + confirmableAction: 操作预览确认卡 -->
  <div v-else-if="status === 'PREVIEW' && confirmable" class="op-card op-card--preview">
    <div class="op-card-header">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ intentName }} — 操作预览</span>
    </div>
    <!-- 确认成功后卡体替换为结果 -->
    <div v-if="phase === 'done'" class="op-card-body">
      <div class="op-card-result">
        <el-icon v-if="resultStatus === 'SUCCESS'" class="op-result-icon-success"><CircleCheckFilled /></el-icon>
        <el-icon v-else><InfoFilled /></el-icon>
        <span class="op-result-message">{{ resultMessage }}</span>
        <el-tag v-if="resultStatus === 'DEMO_WRITE_BLOCKED'" type="info" size="small" effect="plain">演示环境</el-tag>
      </div>
    </div>
    <template v-else>
      <div class="op-card-body">
        <div v-if="confirmable.description" class="op-card-description">{{ confirmable.description }}</div>
        <div v-if="previewEntries.length" class="op-preview-grid">
          <template v-for="[key, value] in previewEntries" :key="key">
            <div class="op-preview-key">{{ key }}</div>
            <div class="op-preview-value">{{ value }}</div>
          </template>
        </div>
        <pre v-else class="op-preview-raw">{{ response.message }}</pre>
      </div>
      <div class="op-card-footer">
        <span v-if="isExpired" class="op-expired-hint">预览已过期，请重新发起</span>
        <span v-else-if="remainingSeconds !== null" class="op-countdown">
          <el-icon><Clock /></el-icon> {{ remainingSeconds }}s 后过期
        </span>
        <el-button
          type="primary"
          size="small"
          :loading="phase === 'pending'"
          :disabled="phase === 'pending' || isExpired"
          @click="handleConfirm"
        >确认执行</el-button>
        <el-button size="small" :disabled="phase === 'pending'" @click="handleCancel">取消</el-button>
      </div>
    </template>
  </div>

  <!-- PREVIEW 无 confirmableAction: 不支持一键确认 (Rule 5: 给出路) -->
  <div v-else-if="status === 'PREVIEW'" class="op-card op-card--info">
    <div class="op-card-header">
      <el-icon><InfoFilled /></el-icon>
      <span>{{ intentName }}</span>
    </div>
    <div class="op-card-body">该操作暂不支持一键确认，请到对应功能页面手工操作。</div>
  </div>

  <!-- NO_PERMISSION / PERMISSION_DENIED -->
  <div v-else-if="status === 'NO_PERMISSION' || status === 'PERMISSION_DENIED'" class="op-card op-card--denied">
    <div class="op-card-header">
      <el-icon><Lock /></el-icon>
      <span>权限不足</span>
    </div>
    <div class="op-card-body">
      <template v-if="response.requiredPermission">
        需要 {{ permissionLabel }} 权限，请联系管理员开通
      </template>
      <template v-else>{{ response.message || '您没有执行该操作的权限，请联系管理员。' }}</template>
    </div>
  </div>

  <!-- DEMO_WRITE_BLOCKED -->
  <div v-else-if="status === 'DEMO_WRITE_BLOCKED'" class="op-card op-card--info">
    <div class="op-card-header">
      <el-icon><InfoFilled /></el-icon>
      <span>{{ intentName }}</span>
      <el-tag type="info" size="small" effect="plain">演示环境</el-tag>
    </div>
    <div class="op-card-body">{{ response.message || '演示环境不落库，操作未实际执行。' }}</div>
  </div>

  <!-- PENDING_APPROVAL -->
  <div v-else-if="status === 'PENDING_APPROVAL'" class="op-card op-card--info">
    <div class="op-card-header">
      <el-icon><Clock /></el-icon>
      <span>已提交审批</span>
    </div>
    <div class="op-card-body">{{ response.message || '该操作已进入审批流程，请等待审批人处理。' }}</div>
  </div>

  <!-- 兜底: 未知操作状态按纯文本渲染 -->
  <div v-else class="op-card op-card--info">
    <div class="op-card-body">{{ response.message || response.formattedText }}</div>
  </div>
</template>

<style lang="scss" scoped>
.op-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  overflow: hidden;
  max-width: 560px;
}

.op-card--confirm,
.op-card--preview {
  border-color: var(--el-color-warning-light-5);

  .op-card-header {
    background: var(--el-color-warning-light-9);
    color: var(--el-color-warning-dark-2);
  }
}

.op-card--denied {
  border-color: var(--el-color-danger-light-5);

  .op-card-header {
    background: var(--el-color-danger-light-9);
    color: var(--el-color-danger-dark-2);
  }
}

.op-card--info {
  border-color: var(--el-border-color);

  .op-card-header {
    background: var(--el-fill-color-light);
    color: var(--el-text-color-primary);
  }
}

.op-card-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  font-weight: 600;
  font-size: 14px;
}

.op-card-body {
  padding: 12px;
  font-size: 13px;
  color: var(--el-text-color-primary);
}

.op-card-transient {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
}

.op-card-description {
  margin-bottom: 8px;
  color: var(--el-text-color-secondary);
}

.op-preview-grid {
  display: grid;
  grid-template-columns: minmax(90px, auto) 1fr;
  gap: 4px 12px;
}

.op-preview-key {
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.op-preview-value {
  color: var(--el-text-color-primary);
  word-break: break-all;
}

.op-preview-raw {
  margin: 0;
  padding: 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 240px;
  overflow: auto;
}

.op-card-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.op-countdown {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.op-expired-hint {
  margin-right: auto;
  font-size: 12px;
  color: var(--el-color-danger);
}

.op-card-result {
  display: flex;
  align-items: center;
  gap: 8px;
}

.op-result-icon-success {
  color: var(--el-color-success);
}

.op-result-message {
  flex: 1;
  white-space: pre-wrap;
}

.op-card-cancelled {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
