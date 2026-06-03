<template>
  <div class="voice-capture">
    <!-- Rule 2: context header -->
    <div class="voice-header">
      <span class="voice-title">语音录入 — {{ today }} 当班</span>
      <el-tag v-if="!voiceAvailable" type="info" size="small">语音服务不可用</el-tag>
    </div>

    <div class="voice-controls">
      <el-button
        v-if="!recording"
        type="primary"
        :icon="Microphone"
        :disabled="busy || !voiceAvailable"
        @click="startRecording"
      >
        按住说话 / 点击录音
      </el-button>
      <el-button v-else type="danger" :icon="VideoPause" @click="stopRecording">
        停止 ({{ seconds }}s)
      </el-button>
      <span v-if="busy" class="voice-status">识别中...</span>
    </div>

    <!-- 识别 + 解析结果 (Rule 2: 显示原文 → 匹配食材) -->
    <el-alert
      v-if="slot"
      :type="slot.matchConfidence >= 0.7 ? 'success' : 'warning'"
      :closable="false"
      show-icon
      style="margin-top: 12px"
    >
      <template #title>{{ slot.message }}</template>
      <div v-if="slot.matchConfidence < 0.7" class="low-conf-hint">
        识别不太确定，请在下方表单确认或手动修正
      </div>
    </el-alert>

    <!-- Rule 3: 识别失败原因 dropdown (可选反馈) -->
    <el-alert
      v-if="errorMsg"
      type="error"
      :closable="true"
      show-icon
      style="margin-top: 12px"
      :title="errorMsg"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { Microphone, VideoPause } from '@element-plus/icons-vue';
import { get, post } from '@/api/request';
import { handleCatchError } from '@/utils/errorToast';

interface VoiceSlot {
  ingredientName: string | null;
  quantity: number | null;
  unit: string | null;
  matchedMaterialTypeId: string | null;
  matchedMaterialName: string | null;
  matchConfidence: number;
  rawText: string | null;
  message: string;
}

const props = defineProps<{ factoryId: string }>();
const emit = defineEmits<{ (e: 'parsed', slot: VoiceSlot): void }>();

const recording = ref(false);
const busy = ref(false);
const seconds = ref(0);
const slot = ref<VoiceSlot | null>(null);
const errorMsg = ref('');
const voiceAvailable = ref(true);

let mediaRecorder: MediaRecorder | null = null;
let chunks: BlobPart[] = [];
let timer: ReturnType<typeof setInterval> | null = null;

const today = computed(() => new Date().toISOString().slice(0, 10));

onMounted(async () => {
  try {
    const resp = await get<{ available: boolean }>(`/voice/health`);
    voiceAvailable.value = !!resp.data?.available;
  } catch {
    voiceAvailable.value = false;
  }
});

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
  if (mediaRecorder && mediaRecorder.state !== 'inactive') mediaRecorder.stop();
});

async function startRecording() {
  errorMsg.value = '';
  slot.value = null;
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    chunks = [];
    mediaRecorder = new MediaRecorder(stream);
    mediaRecorder.ondataavailable = (e) => chunks.push(e.data);
    mediaRecorder.onstop = () => {
      stream.getTracks().forEach((t) => t.stop());
      void processAudio();
    };
    mediaRecorder.start();
    recording.value = true;
    seconds.value = 0;
    timer = setInterval(() => {
      seconds.value += 1;
      if (seconds.value >= 30) stopRecording();  // 上限 30s
    }, 1000);
  } catch (e) {
    errorMsg.value = '无法访问麦克风，请检查浏览器权限';
  }
}

function stopRecording() {
  if (timer) { clearInterval(timer); timer = null; }
  if (mediaRecorder && mediaRecorder.state !== 'inactive') mediaRecorder.stop();
  recording.value = false;
}

async function processAudio() {
  busy.value = true;
  try {
    const blob = new Blob(chunks, { type: 'audio/webm' });
    const base64 = await blobToBase64(blob);
    // 1. 语音识别
    const recResp = await post<{ text?: string }>(`/${props.factoryId}/voice/recognize`, {
      audioData: base64,
      format: 'webm',
      encoding: 'webm',
      sampleRate: 16000,
      language: 'zh_cn',
    });
    const text = recResp.data?.text;
    if (!text) {
      errorMsg.value = '未识别到语音内容，请重试';
      return;
    }
    // 2. slot 解析 (不写库)
    const draftResp = await post<VoiceSlot>(`/${props.factoryId}/restaurant/requisitions/voice-draft`, {
      voiceText: text,
    });
    if (draftResp.success && draftResp.data) {
      slot.value = draftResp.data;
      emit('parsed', draftResp.data);
    }
  } catch (e) {
    handleCatchError(e, '语音录入失败');
  } finally {
    busy.value = false;
  }
}

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      const result = reader.result as string;
      // strip "data:...;base64," prefix
      resolve(result.split(',')[1] || '');
    };
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}
</script>

<style scoped lang="scss">
.voice-capture {
  padding: 8px 0;
}
.voice-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.voice-title {
  font-weight: 600;
  font-size: 14px;
}
.voice-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}
.voice-status {
  color: var(--el-color-primary);
  font-size: 13px;
}
.low-conf-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
</style>
