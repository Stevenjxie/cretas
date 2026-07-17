<script setup lang="ts">
import { ref, nextTick, watch, computed } from 'vue';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { useAiChat } from '@/composables/useAiChat';
import type { AiEntryConfig } from './types';

const props = defineProps<{
  modelValue: boolean;
  config: AiEntryConfig;
  confirmGuard?: (params: Record<string, unknown>) => string | null;
  guardActionLabel?: string;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  'fill-form': [params: Record<string, unknown>];
  'guard-action': [];
}>();

const { messages, loading, previewParams, sendMessage, continueEditing, confirmParams, reset } = useAiChat(props.config);

const chatInput = ref('');
const chatContainer = ref<HTMLElement | null>(null);
const showTutorial = ref(false);

// Animated tutorial: step-by-step walkthrough
const tutorialStep = ref(0);
const tutorialActive = ref(false); // true = playing animated walkthrough

const currentTutorialData = computed(() => props.config.tutorialSteps[tutorialStep.value]);
const tutorialProgress = computed(() => ((tutorialStep.value + 1) / props.config.tutorialSteps.length) * 100);

// Count filled fields in preview
const filledFieldCount = computed(() => {
  if (!previewParams.value) return 0;
  return config.fields.filter(f => {
    const val = previewParams.value?.[f.key];
    return val !== null && val !== undefined && val !== '' && val !== '-';
  }).length;
});
const guardMessage = computed(() => (
  previewParams.value && props.confirmGuard ? props.confirmGuard(previewParams.value) : null
));

const { config } = props;

function startTutorial() {
  showTutorial.value = true;
  tutorialActive.value = true;
  tutorialStep.value = 0;
}

function nextStep() {
  if (tutorialStep.value < props.config.tutorialSteps.length - 1) {
    tutorialStep.value++;
  } else {
    tutorialActive.value = false;
  }
}

function prevStep() {
  if (tutorialStep.value > 0) {
    tutorialStep.value--;
  }
}

function skipTutorial() {
  tutorialActive.value = false;
}

function backToChat() {
  showTutorial.value = false;
  tutorialActive.value = false;
}

function renderMarkdown(content: string): string {
  try {
    const cleaned = content.replace(/```json\s*\{[\s\S]*?"action"\s*:\s*"FILL_FORM"[\s\S]*?\}\s*```/g, '').trim();
    return DOMPurify.sanitize(marked.parse(cleaned) as string);
  } catch {
    return content;
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTo({
        top: chatContainer.value.scrollHeight,
        behavior: 'smooth',
      });
    }
  });
}

async function handleSend() {
  const text = chatInput.value.trim();
  if (!text || loading.value) return;
  chatInput.value = '';
  scrollToBottom();
  await sendMessage(text);
  await nextTick();
  scrollToBottom();
}

function handleInputKeydown(e: KeyboardEvent) {
  // Enter alone sends; Shift+Enter adds newline
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    handleSend();
  }
}

async function handleExampleClick(example: string) {
  showTutorial.value = false;
  tutorialActive.value = false;
  chatInput.value = '';
  await sendMessage(example);
  await nextTick();
  scrollToBottom();
}

function handleFillForm() {
  if (guardMessage.value) return;
  const params = confirmParams();
  emit('update:modelValue', false);
  emit('fill-form', params);
}

function handleContinueEdit() {
  continueEditing();
}

function isFieldFilled(key: string): boolean {
  if (!previewParams.value) return false;
  const val = previewParams.value[key];
  if (val === null || val === undefined || val === '') return false;
  if (Array.isArray(val) && val.length === 0) return false;
  return true;
}

function formatPreviewValue(key: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '未填写';
  if (Array.isArray(value)) {
    if (value.length === 0) return '未填写';
    return value.map((item, i) => {
      if (typeof item === 'object' && item !== null) {
        const parts = Object.entries(item)
          .filter(([, v]) => v !== '' && v !== 0 && v !== null)
          .map(([k, v]) => `${k}: ${v}`);
        return `(${i + 1}) ${parts.join(', ')}`;
      }
      return String(item);
    }).join('\n');
  }
  if (key === 'productCategory') {
    const map: Record<string, string> = {
      FINISHED_PRODUCT: '成品', RAW_MATERIAL: '原料', PACKAGING: '包辅材',
      SEMI_FINISHED: '半成品',
      SEASONING: '调味品', CUSTOMER_MATERIAL: '客户自带原料加工',
      CONTRACT_MANUFACTURING: '纯代工',
    };
    return map[String(value)] || String(value);
  }
  if (key === 'purchaseType') {
    const map: Record<string, string> = { DIRECT: '直接采购', HQ_UNIFIED: '总部统采', URGENT: '紧急采购' };
    return map[String(value)] || String(value);
  }
  return String(value);
}

watch(() => props.modelValue, (visible) => {
  if (visible) {
    reset();
    chatInput.value = '';
    showTutorial.value = false;
    tutorialActive.value = false;
    tutorialStep.value = 0;
  }
});
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    :title="config.title"
    size="45%"
    direction="rtl"
    class="ai-entry-drawer"
  >
    <template #header="{ titleId, titleClass }">
      <div class="drawer-header">
        <div class="drawer-header-left">
          <span :id="titleId" :class="titleClass">{{ config.title }}</span>
          <span class="scope-badge">{{ config.scopeLabel }}</span>
        </div>
        <el-button
          v-if="!showTutorial"
          text
          class="tutorial-btn"
          @click="startTutorial"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right: 4px; vertical-align: -2px">
            <circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><line x1="12" y1="17" x2="12.01" y2="17"/>
          </svg>
          使用教程
        </el-button>
        <el-button
          v-else
          text
          class="tutorial-btn"
          @click="backToChat"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right: 4px; vertical-align: -2px">
            <line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/>
          </svg>
          返回对话
        </el-button>
      </div>
    </template>

    <div class="ai-chat-container">

      <!-- ============ Animated Tutorial Walkthrough ============ -->
      <Transition name="fade-slide" mode="out-in">
        <div v-if="showTutorial && tutorialActive" class="tutorial-walkthrough" :key="'walkthrough'">
          <!-- Progress bar + dots -->
          <div class="progress-section">
            <div class="progress-track">
              <div class="progress-fill" :style="{ width: tutorialProgress + '%' }" />
            </div>
            <div class="progress-dots">
              <span
                v-for="(_, idx) in config.tutorialSteps"
                :key="idx"
                class="progress-dot"
                :class="{ active: idx === tutorialStep, done: idx < tutorialStep }"
                @click="tutorialStep = idx"
              />
            </div>
          </div>

          <!-- Animated step card -->
          <Transition name="step-slide" mode="out-in">
            <div class="step-card" :key="tutorialStep">
              <div class="step-card-number">{{ currentTutorialData.icon }}</div>
              <div class="step-card-title">{{ currentTutorialData.title }}</div>
              <div class="step-card-desc">{{ currentTutorialData.description }}</div>

              <!-- Visual demo area -->
              <div class="step-demo">
                <div v-if="tutorialStep === 0" class="demo-input-mock">
                  <div class="demo-label">输入框</div>
                  <div class="demo-typing">
                    <span class="typing-text">{{ config.examples[0] }}</span>
                    <span class="typing-cursor">|</span>
                  </div>
                </div>
                <div v-else-if="tutorialStep === 1" class="demo-chat-mock">
                  <div class="demo-bubble demo-user">{{ config.examples[0].slice(0, 20) }}...</div>
                  <div class="demo-bubble demo-ai">请问交货日期是哪天？</div>
                  <div class="demo-bubble demo-user">明天</div>
                </div>
                <div v-else-if="tutorialStep === 2" class="demo-preview-mock">
                  <div class="demo-preview-row" v-for="f in config.fields.slice(0, 3)" :key="f.key">
                    <span class="demo-pv-label">{{ f.label }}</span>
                    <span class="demo-pv-value">...</span>
                  </div>
                </div>
                <div v-else class="demo-form-mock">
                  <div class="demo-form-row" v-for="f in config.fields.slice(0, 3)" :key="f.key">
                    <span class="demo-f-label">{{ f.label }}</span>
                    <div class="demo-f-input" />
                  </div>
                  <div class="demo-form-btn">确定</div>
                </div>
              </div>
            </div>
          </Transition>

          <!-- Navigation buttons -->
          <div class="walkthrough-nav">
            <el-button v-if="tutorialStep > 0" @click="prevStep" round>上一步</el-button>
            <span v-else />
            <el-button text @click="skipTutorial" class="skip-btn">跳过</el-button>
            <el-button type="primary" @click="nextStep" round>
              {{ tutorialStep < config.tutorialSteps.length - 1 ? '下一步' : '开始使用' }}
            </el-button>
          </div>
        </div>

        <!-- ============ Tutorial Reference (after walkthrough done) ============ -->
        <div v-else-if="showTutorial && !tutorialActive" class="tutorial-panel" :key="'reference'">
          <div class="tutorial-title">操作指南</div>

          <div class="tutorial-steps">
            <div
              v-for="(step, idx) in config.tutorialSteps"
              :key="idx"
              class="tutorial-step anim-step"
              :style="{ animationDelay: idx * 0.1 + 's' }"
            >
              <div class="step-number">{{ step.icon }}</div>
              <div class="step-content">
                <div class="step-title">{{ step.title }}</div>
                <div class="step-desc">{{ step.description }}</div>
              </div>
            </div>
          </div>

          <div class="tutorial-section anim-section" style="animation-delay: 0.4s">
            <div class="tutorial-subtitle">试试这些示例</div>
            <div class="tutorial-examples">
              <div
                v-for="(ex, idx) in config.examples"
                :key="idx"
                class="example-card"
                @click="handleExampleClick(ex)"
              >
                <span class="example-icon">&#x27A4;</span>
                <span class="example-text">{{ ex }}</span>
              </div>
            </div>
          </div>

          <div class="tutorial-section anim-section" style="animation-delay: 0.5s">
            <div class="tutorial-subtitle">需要收集的信息</div>
            <div class="field-list">
              <span
                v-for="field in config.fields"
                :key="field.key"
                class="field-tag"
                :class="{ required: field.required }"
              >
                {{ field.label }}
                <span v-if="field.required" class="req-dot">*</span>
              </span>
            </div>
            <div class="field-hint">标 * 为必填项，其余可选</div>
          </div>

          <div class="tutorial-tip anim-section" style="animation-delay: 0.6s">
            <div class="tip-title">提示</div>
            <ul class="tip-list">
              <li>支持自然语言，不需要按固定格式输入</li>
              <li>日期可以说"明天""下周一"，AI 会自动转换</li>
              <li>信息不全时 AI 会自动追问，不用一次说完</li>
              <li>最终会弹出表单确认，AI 不会直接创建记录</li>
            </ul>
          </div>

          <div class="tutorial-bottom anim-section" style="animation-delay: 0.7s">
            <el-button type="primary" size="large" round @click="backToChat" style="width: 100%">
              开始使用
            </el-button>
          </div>
        </div>

        <!-- ============ Chat Mode ============ -->
        <template v-else :key="'chat'">
          <div class="chat-area-wrapper">
            <div class="chat-messages" ref="chatContainer">
              <!-- Welcome -->
              <div v-if="messages.length === 0" class="welcome-area">
                <div class="welcome-avatar">
                  <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#2D8B57" stroke-width="1.5">
                    <rect x="3" y="11" width="18" height="10" rx="2"/><circle cx="8.5" cy="16" r="1.5" fill="#2D8B57"/><circle cx="15.5" cy="16" r="1.5" fill="#2D8B57"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>
                  </svg>
                </div>
                <div class="welcome-text">{{ config.welcomeMessage }}</div>
                <div class="welcome-hint">点击下方示例快速开始，或直接输入你的需求</div>
                <div class="quick-examples">
                  <div
                    v-for="(ex, idx) in config.examples"
                    :key="idx"
                    class="quick-example-chip anim-chip"
                    :style="{ animationDelay: idx * 0.1 + 0.2 + 's' }"
                    @click="handleExampleClick(ex)"
                  >
                    <span class="chip-arrow">&#x27A4;</span>
                    <span class="chip-text">{{ ex }}</span>
                  </div>
                </div>
              </div>

              <!-- Messages with animation -->
              <TransitionGroup name="msg-slide">
                <div v-for="(msg, index) in messages" :key="'msg-'+index" :class="['chat-message', msg.role]">
                  <!-- Avatar -->
                  <div class="msg-avatar" v-if="msg.role === 'assistant'">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#2D8B57" stroke-width="2">
                      <rect x="3" y="11" width="18" height="10" rx="2"/><circle cx="8.5" cy="16" r="1.5" fill="#2D8B57"/><circle cx="15.5" cy="16" r="1.5" fill="#2D8B57"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>
                    </svg>
                  </div>
                  <div class="chat-bubble" v-html="renderMarkdown(msg.content)" />
                </div>
              </TransitionGroup>

              <Transition name="fade-up">
                <div v-if="loading" class="chat-message assistant">
                  <div class="msg-avatar">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#2D8B57" stroke-width="2">
                      <rect x="3" y="11" width="18" height="10" rx="2"/><circle cx="8.5" cy="16" r="1.5" fill="#2D8B57"/><circle cx="15.5" cy="16" r="1.5" fill="#2D8B57"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>
                    </svg>
                  </div>
                  <div class="chat-bubble loading-bubble">
                    <span class="dot-anim"><span /><span /><span /></span>
                    <span class="loading-text">思考中</span>
                  </div>
                </div>
              </Transition>

              <!-- Preview Card -->
              <Transition name="card-pop">
                <div v-if="previewParams" class="preview-card">
                  <div class="preview-header">
                    <span class="preview-title">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#2D8B57" stroke-width="2" style="vertical-align: -3px; margin-right: 6px">
                        <path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>
                      </svg>
                      AI 收集到的信息
                    </span>
                    <span class="preview-count">{{ filledFieldCount }}/{{ config.fields.length }} 已填</span>
                  </div>
                  <div class="preview-body">
                    <div
                      v-for="field in config.fields"
                      :key="field.key"
                      class="preview-row"
                      :class="{ 'is-empty': !isFieldFilled(field.key) }"
                    >
                      <span class="preview-label">
                        <span v-if="field.required" class="required-star">*</span>
                        {{ field.label }}
                      </span>
                      <span class="preview-value" :class="{ 'is-array': Array.isArray(previewParams[field.key]) }">
                        {{ formatPreviewValue(field.key, previewParams[field.key]) }}
                      </span>
                      <span class="preview-status">
                        <svg v-if="isFieldFilled(field.key)" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#67c23a" stroke-width="2.5">
                          <polyline points="20 6 9 17 4 12"/>
                        </svg>
                        <span v-else class="status-dash">—</span>
                      </span>
                    </div>
                  </div>
                  <div class="preview-actions">
                    <el-alert v-if="guardMessage" :title="guardMessage" type="warning" :closable="false" show-icon class="preview-guard" />
                    <el-button v-if="guardMessage && guardActionLabel" type="warning" plain @click="$emit('guard-action')">
                      {{ guardActionLabel }}
                    </el-button>
                    <el-button @click="handleContinueEdit" round>继续修改</el-button>
                    <el-button type="primary" :disabled="Boolean(guardMessage)" @click="handleFillForm" round>
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right: 4px; vertical-align: -2px">
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>
                      </svg>
                      填入表单
                    </el-button>
                  </div>
                </div>
              </Transition>
            </div>

            <!-- Input area — modern inline style -->
            <div class="chat-input-area">
              <div class="input-wrapper">
                <el-input
                  v-model="chatInput"
                  type="textarea"
                  :rows="1"
                  :autosize="{ minRows: 1, maxRows: 4 }"
                  :placeholder="config.placeholder"
                  @keydown="handleInputKeydown"
                  resize="none"
                />
                <button
                  class="send-btn"
                  @click="handleSend"
                  :disabled="!chatInput.trim() || loading"
                  :class="{ active: chatInput.trim() && !loading }"
                >
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>
                  </svg>
                </button>
              </div>
              <div class="input-hint">Enter 发送 · Shift+Enter 换行</div>
            </div>
          </div>
        </template>
      </Transition>
    </div>
  </el-drawer>
</template>

<style lang="scss" scoped>
// ==================== Animations ====================
@keyframes fadeSlideIn {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes chipSlideIn {
  from { opacity: 0; transform: translateX(-10px); }
  to { opacity: 1; transform: translateX(0); }
}

@keyframes pulse {
  0%, 80%, 100% { opacity: 0.3; transform: scale(0.8); }
  40% { opacity: 1; transform: scale(1); }
}

@keyframes shimmer {
  0% { background-position: -200% 0; }
  100% { background-position: 200% 0; }
}

// Transition: fade-slide (tutorial <-> chat)
.fade-slide-enter-active { transition: all 0.3s ease-out; }
.fade-slide-leave-active { transition: all 0.2s ease-in; }
.fade-slide-enter-from { opacity: 0; transform: translateX(20px); }
.fade-slide-leave-to { opacity: 0; transform: translateX(-20px); }

// Transition: step slide (tutorial step change)
.step-slide-enter-active { transition: all 0.35s cubic-bezier(0.34, 1.56, 0.64, 1); }
.step-slide-leave-active { transition: all 0.2s ease-in; }
.step-slide-enter-from { opacity: 0; transform: translateX(40px) scale(0.95); }
.step-slide-leave-to { opacity: 0; transform: translateX(-40px) scale(0.95); }

// Transition: message slide in
.msg-slide-enter-active { transition: all 0.3s ease-out; }
.msg-slide-enter-from { opacity: 0; transform: translateY(10px); }

// Transition: fade up
.fade-up-enter-active { transition: all 0.3s ease-out; }
.fade-up-leave-active { transition: all 0.2s ease-in; }
.fade-up-enter-from { opacity: 0; transform: translateY(8px); }
.fade-up-leave-to { opacity: 0; }

// Transition: card pop
.card-pop-enter-active { transition: all 0.4s cubic-bezier(0.34, 1.56, 0.64, 1); }
.card-pop-leave-active { transition: all 0.2s ease-in; }
.card-pop-enter-from { opacity: 0; transform: translateY(20px) scale(0.9); }
.card-pop-leave-to { opacity: 0; transform: scale(0.9); }

// Staggered entrance
.anim-step, .anim-section { animation: fadeSlideIn 0.4s ease-out both; }
.anim-chip { animation: chipSlideIn 0.3s ease-out both; }

// ==================== Drawer Header ====================
.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.drawer-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.scope-badge {
  font-size: 11px;
  color: #7A8599;
  background: #F4F6F9;
  border: 1px solid #EDF2F7;
  border-radius: 10px;
  padding: 2px 10px;
  white-space: nowrap;
  letter-spacing: 0.02em;
}

.tutorial-btn {
  font-size: 13px;
  color: #2D8B57;
  padding: 4px 12px;
  border-radius: 6px;
  transition: all 0.2s;

  &:hover { background: rgba(45, 139, 87, 0.06); }
}

// ==================== Container ====================
.ai-chat-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 100px);
}

.chat-area-wrapper {
  display: flex;
  flex-direction: column;
  height: 100%;
}

// ==================== Tutorial Walkthrough ====================
.tutorial-walkthrough {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 24px 20px;
}

.progress-section { margin-bottom: 20px; }

.progress-track {
  height: 3px;
  background: #EDF2F7;
  border-radius: 2px;
  overflow: hidden;
  margin-bottom: 12px;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #2D8B57, #409EFF);
  border-radius: 2px;
  transition: width 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.progress-dots {
  display: flex;
  justify-content: center;
  gap: 8px;
}

.progress-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #EDF2F7;
  cursor: pointer;
  transition: all 0.3s;

  &.active {
    background: #2D8B57;
    transform: scale(1.3);
    box-shadow: 0 0 0 3px rgba(45, 139, 87, 0.15);
  }
  &.done { background: #67c23a; }
}

.step-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.step-card-number {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2D8B57, #409EFF);
  color: #fff;
  font-size: 24px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
  box-shadow: 0 4px 16px rgba(45, 139, 87, 0.25);
}

.step-card-title {
  font-size: 20px;
  font-weight: 600;
  color: #1A2332;
  margin-bottom: 8px;
}

.step-card-desc {
  font-size: 14px;
  color: #7A8599;
  text-align: center;
  max-width: 360px;
  line-height: 1.6;
  margin-bottom: 24px;
}

// Step demo mock areas
.step-demo {
  width: 100%;
  max-width: 320px;
  padding: 16px;
  background: #F4F6F9;
  border-radius: 10px;
  border: 1px solid #EDF2F7;
  box-shadow: 0 2px 12px rgba(45, 139, 87, 0.06);
}

.demo-input-mock {
  .demo-label { font-size: 11px; color: #7A8599; margin-bottom: 8px; }
  .demo-typing {
    background: #fff;
    border: 1px solid #EDF2F7;
    border-radius: 8px;
    padding: 10px 12px;
    font-size: 13px;
    color: #1A2332;
  }
  .typing-text { animation: fadeSlideIn 0.6s ease-out both; }
  .typing-cursor { animation: pulse 1s infinite; color: #2D8B57; font-weight: 300; }
}

.demo-chat-mock { display: flex; flex-direction: column; gap: 8px; }

.demo-bubble {
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 12px;
  max-width: 80%;
  animation: fadeSlideIn 0.3s ease-out both;

  &.demo-user {
    background: linear-gradient(135deg, #2D8B57, #409EFF);
    color: #fff;
    align-self: flex-end;
    border-radius: 10px 10px 4px 10px;
    &:nth-child(1) { animation-delay: 0s; }
    &:nth-child(3) { animation-delay: 0.4s; }
  }
  &.demo-ai {
    background: #fff;
    color: #1A2332;
    align-self: flex-start;
    border: 1px solid #EDF2F7;
    border-radius: 10px 10px 10px 4px;
    animation-delay: 0.2s;
  }
}

.demo-preview-mock { display: flex; flex-direction: column; gap: 6px; }

.demo-preview-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px solid #EDF2F7;
  font-size: 12px;
  animation: fadeSlideIn 0.3s ease-out both;
  &:nth-child(1) { animation-delay: 0s; }
  &:nth-child(2) { animation-delay: 0.1s; }
  &:nth-child(3) { animation-delay: 0.2s; }
}

.demo-pv-label { color: #7A8599; font-weight: 500; }
.demo-pv-value { color: #1A2332; }

.demo-form-mock { display: flex; flex-direction: column; gap: 8px; }

.demo-form-row {
  display: flex; align-items: center; gap: 10px;
  animation: fadeSlideIn 0.3s ease-out both;
  &:nth-child(1) { animation-delay: 0s; }
  &:nth-child(2) { animation-delay: 0.1s; }
  &:nth-child(3) { animation-delay: 0.2s; }
}

.demo-f-label { font-size: 12px; color: #7A8599; width: 60px; flex-shrink: 0; }
.demo-f-input { flex: 1; height: 28px; background: #fff; border: 1px solid #EDF2F7; border-radius: 6px; }
.demo-form-btn {
  margin-top: 4px;
  padding: 6px 0;
  background: linear-gradient(135deg, #2D8B57, #409EFF);
  color: #fff;
  text-align: center;
  border-radius: 6px;
  font-size: 12px;
  animation: fadeSlideIn 0.3s ease-out 0.3s both;
}

.walkthrough-nav {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 20px;
  border-top: 1px solid #EDF2F7;
}

.skip-btn { font-size: 13px; color: #7A8599 !important; }

// ==================== Tutorial Reference Panel ====================
.tutorial-panel {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.tutorial-title {
  font-size: 18px;
  font-weight: 600;
  color: #1A2332;
  margin-bottom: 20px;
  text-align: center;
  animation: fadeSlideIn 0.3s ease-out both;
}

.tutorial-steps { display: flex; flex-direction: column; gap: 16px; margin-bottom: 28px; }

.tutorial-step { display: flex; gap: 14px; align-items: flex-start; }

.step-number {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2D8B57, #409EFF);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 2px 8px rgba(45, 139, 87, 0.2);
}

.step-content { flex: 1; padding-top: 2px; }
.step-title { font-size: 15px; font-weight: 600; color: #1A2332; margin-bottom: 4px; }
.step-desc { font-size: 13px; color: #7A8599; line-height: 1.5; }

.tutorial-section { margin-bottom: 24px; }
.tutorial-subtitle { font-size: 14px; font-weight: 600; color: #1A2332; margin-bottom: 12px; }

.tutorial-examples { display: flex; flex-direction: column; gap: 8px; }

.example-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  background: #F4F6F9;
  border: 1px solid #EDF2F7;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.25s ease;

  &:hover {
    background: rgba(45, 139, 87, 0.06);
    border-color: rgba(45, 139, 87, 0.2);
    transform: translateX(6px);
    box-shadow: 0 2px 12px rgba(45, 139, 87, 0.08);
  }
}

.example-icon { color: #2D8B57; font-size: 14px; flex-shrink: 0; }
.example-text { font-size: 13px; color: #1A2332; line-height: 1.4; }

.field-list { display: flex; flex-wrap: wrap; gap: 8px; }

.field-tag {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 6px;
  background: #F4F6F9;
  color: #606266;
  border: 1px solid #EDF2F7;

  &.required {
    background: #fef0f0;
    color: #f56c6c;
    border-color: #fbc4c4;
    font-weight: 500;
  }
  .req-dot { color: #f56c6c; margin-left: 2px; }
}

.field-hint { font-size: 12px; color: #7A8599; margin-top: 8px; }

.tutorial-tip {
  background: linear-gradient(135deg, #fdf6ec, #fefaf3);
  border: 1px solid #faecd8;
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 20px;
}

.tip-title { font-size: 13px; font-weight: 600; color: #e6a23c; margin-bottom: 8px; }
.tip-list {
  margin: 0;
  padding-left: 18px;
  li { font-size: 13px; color: #606266; line-height: 1.8; }
}

.tutorial-bottom { padding-top: 4px; }

// ==================== Welcome Area ====================
.welcome-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 20px 20px;
  animation: fadeSlideIn 0.4s ease-out both;
}

.welcome-avatar {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(45, 139, 87, 0.08), rgba(64, 158, 255, 0.08));
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
}

.welcome-text { font-size: 16px; font-weight: 600; color: #1A2332; margin-bottom: 6px; }
.welcome-hint { font-size: 13px; color: #7A8599; margin-bottom: 24px; }

.quick-examples {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.quick-example-chip {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid #EDF2F7;
  border-radius: 10px;
  font-size: 13px;
  color: #1A2332;
  cursor: pointer;
  transition: all 0.25s ease;
  line-height: 1.5;

  &:hover {
    background: rgba(45, 139, 87, 0.04);
    border-color: rgba(45, 139, 87, 0.2);
    color: #2D8B57;
    transform: translateX(4px);
    box-shadow: 0 2px 8px rgba(45, 139, 87, 0.08);
  }
}

.chip-arrow {
  color: #2D8B57;
  flex-shrink: 0;
  font-size: 13px;
  line-height: 1.5;
}

.chip-text { flex: 1; }

// ==================== Chat Messages ====================
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  scroll-behavior: smooth;

  // Subtle scrollbar
  &::-webkit-scrollbar { width: 4px; }
  &::-webkit-scrollbar-track { background: transparent; }
  &::-webkit-scrollbar-thumb { background: #ddd; border-radius: 2px; }
  &::-webkit-scrollbar-thumb:hover { background: #bbb; }
}

.chat-message {
  margin-bottom: 16px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.chat-message.user {
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(45, 139, 87, 0.08), rgba(64, 158, 255, 0.08));
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}

.chat-message.user .chat-bubble {
  background: linear-gradient(135deg, #2D8B57, #409EFF);
  color: white;
  text-align: left;
  border-radius: 14px 14px 4px 14px;
  box-shadow: 0 2px 8px rgba(45, 139, 87, 0.15);
}

.chat-message.assistant .chat-bubble {
  background: #fff;
  color: #1A2332;
  border-radius: 14px 14px 14px 4px;
  border: 1px solid #EDF2F7;
  box-shadow: 0 1px 4px rgba(45, 139, 87, 0.04);
}

.chat-bubble {
  padding: 10px 14px;
  border-radius: 14px;
  max-width: 80%;
  word-break: break-word;
  line-height: 1.6;
  font-size: 14px;

  :deep(p) { margin: 4px 0; }
  :deep(ul), :deep(ol) { padding-left: 20px; margin: 4px 0; }
  :deep(code) { background: rgba(45, 139, 87, 0.06); padding: 2px 4px; border-radius: 4px; font-size: 13px; }
  :deep(strong) { font-weight: 600; color: #1A2332; }
}

.loading-bubble {
  color: #7A8599;
  font-style: normal;
  display: flex;
  align-items: center;
  gap: 8px;
}

.loading-text { font-size: 13px; }

.dot-anim {
  display: inline-flex;
  gap: 3px;

  span {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #2D8B57;
    opacity: 0.4;
    animation: pulse 1.2s infinite;

    &:nth-child(2) { animation-delay: 0.2s; }
    &:nth-child(3) { animation-delay: 0.4s; }
  }
}

// ==================== Preview Card ====================
.preview-card {
  margin: 16px 0;
  border: 1px solid #EDF2F7;
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
  box-shadow: 0 4px 16px rgba(45, 139, 87, 0.08);
}

.preview-header {
  padding: 12px 16px;
  background: linear-gradient(135deg, rgba(45, 139, 87, 0.04), rgba(64, 158, 255, 0.06));
  border-bottom: 1px solid #EDF2F7;
  display: flex;
  justify-content: space-between;
  align-items: center;
  .preview-title { font-size: 14px; font-weight: 600; color: #1A2332; }
}

.preview-count {
  font-size: 12px;
  color: #7A8599;
  background: #F4F6F9;
  padding: 2px 8px;
  border-radius: 10px;
}

.preview-body { padding: 4px 16px; }

.preview-row {
  display: flex;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid #F4F6F9;
  font-size: 14px;
  transition: background 0.2s;

  &:last-child { border-bottom: none; }
  &.is-empty { opacity: 0.5; }
}

.preview-label {
  width: 100px;
  flex-shrink: 0;
  color: #7A8599;
  font-weight: 500;
  font-size: 13px;
  .required-star { color: #f56c6c; margin-right: 2px; }
}

.preview-value {
  flex: 1;
  color: #1A2332;
  font-weight: 500;
  &.is-array { white-space: pre-line; }
}

.preview-status {
  width: 24px;
  flex-shrink: 0;
  text-align: center;
}

.status-dash { color: #ddd; font-size: 12px; }

.preview-actions {
  padding: 12px 16px;
  border-top: 1px solid #EDF2F7;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

// ==================== Input Area ====================
.chat-input-area {
  padding: 12px 16px;
  border-top: 1px solid #EDF2F7;
  background: #fff;
}

.input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  background: #F4F6F9;
  border: 1px solid #EDF2F7;
  border-radius: 12px;
  padding: 6px 6px 6px 14px;
  transition: border-color 0.2s, box-shadow 0.2s;

  &:focus-within {
    border-color: rgba(45, 139, 87, 0.3);
    box-shadow: 0 0 0 3px rgba(45, 139, 87, 0.06);
    background: #fff;
  }

  :deep(.el-textarea__inner) {
    background: transparent !important;
    border: none !important;
    box-shadow: none !important;
    padding: 4px 0;
    font-size: 14px;
    line-height: 1.5;
    resize: none;
    min-height: unset !important;
  }
}

.send-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: none;
  background: #EDF2F7;
  color: #bbb;
  cursor: not-allowed;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.25s ease;

  &.active {
    background: linear-gradient(135deg, #2D8B57, #409EFF);
    color: #fff;
    cursor: pointer;
    box-shadow: 0 2px 8px rgba(45, 139, 87, 0.25);

    &:hover {
      transform: scale(1.05);
      box-shadow: 0 4px 12px rgba(45, 139, 87, 0.3);
    }
    &:active { transform: scale(0.95); }
  }
}

.input-hint {
  text-align: center;
  font-size: 11px;
  color: #bbb;
  margin-top: 6px;
  letter-spacing: 0.02em;
}
</style>
