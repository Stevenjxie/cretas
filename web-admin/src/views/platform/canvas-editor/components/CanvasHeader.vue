<!-- CanvasHeader.vue — status-aware header with version + actions -->
<template>
  <div class="canvas-header">
    <div class="header-left">
      <span class="header-title">Canvas 配置编辑器</span>
      <el-divider direction="vertical" />
      <span class="header-factory">{{ factoryName }}</span>
      <el-divider direction="vertical" />
      <span class="header-version">{{ versionLabel }}</span>
      <span v-if="dirtyCount > 0" class="header-dirty">· {{ dirtyCount }} 项未保存</span>
    </div>
    <div class="header-actions">
      <!-- Round 7a fix: every action button now has :loading + :disabled in-flight lock
           to prevent double-submit (Scenario 1/3/9/11 from R7a Subagent E). Parent view
           toggles `inFlightAction` to the action code while awaiting the API. -->
      <!-- DRAFT actions -->
      <template v-if="status === 'DRAFT'">
 <el-button size="small" :loading="inFlightAction==='save'" :disabled="inFlightAction!==null && inFlightAction!=='save'" @click="emitLocked('save')"> 保存草稿</el-button>
        <el-button size="small" type="primary" :loading="inFlightAction==='submit-review'" :disabled="dirtyCount > 0 || (inFlightAction!==null && inFlightAction!=='submit-review')" @click="emitLocked('submit-review')">
          提交审核 →
        </el-button>
      </template>
      <!-- PENDING_REVIEW actions -->
      <template v-if="status === 'PENDING_REVIEW'">
        <el-tag type="warning">等待审核中</el-tag>
 <el-button size="small" type="success" :loading="inFlightAction==='approve'" :disabled="inFlightAction!==null && inFlightAction!=='approve'" @click="emitLocked('approve')">通过 </el-button>
 <el-button size="small" type="danger" :loading="inFlightAction==='reject'" :disabled="inFlightAction!==null && inFlightAction!=='reject'" @click="emitLocked('reject')">驳回 </el-button>
      </template>
      <!-- APPROVED actions -->
      <template v-if="status === 'APPROVED'">
        <span class="countdown" v-if="countdown">⏰ {{ countdown }}</span>
 <el-button size="small" type="warning" :loading="inFlightAction==='publish-now'" :disabled="inFlightAction!==null && inFlightAction!=='publish-now'" @click="emitLocked('publish-now')"> 立即发布</el-button>
        <el-button size="small" :loading="inFlightAction==='cancel-approval'" :disabled="inFlightAction!==null && inFlightAction!=='cancel-approval'" @click="emitLocked('cancel-approval')">取消发布</el-button>
      </template>
      <!-- PUBLISHED actions -->
      <template v-if="status === 'PUBLISHED'">
        <el-button size="small" type="primary" :loading="inFlightAction==='new-draft'" :disabled="inFlightAction!==null && inFlightAction!=='new-draft'" @click="emitLocked('new-draft')">新建草稿</el-button>
        <el-button size="small" :loading="inFlightAction==='rollback'" :disabled="inFlightAction!==null && inFlightAction!=='rollback'" @click="emitLocked('rollback')">回滚</el-button>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useCanvasEditor } from '../composables/useCanvasEditor'

const { versionLabel, status, dirtyCount, factoryId, inFlightAction } = useCanvasEditor()

const emit = defineEmits<{
  save: []
  'submit-review': []
  approve: []
  reject: []
  'publish-now': []
  'cancel-approval': []
  'new-draft': []
  rollback: []
}>()

// Round 7a: single-flight emit — blocks double-click by gating on inFlightAction.
// The parent view must set inFlightAction = '<code>' before its await and reset
// to null in finally. Double-click within loading window is silently dropped.
type ActionCode = 'save' | 'submit-review' | 'approve' | 'reject' | 'publish-now' | 'cancel-approval' | 'new-draft' | 'rollback'
function emitLocked(action: ActionCode) {
  if (inFlightAction.value !== null) return  // already running, drop duplicate
  emit(action as any)
}

const factoryName = computed(() => factoryId.value || '未选择工厂')

// Countdown for APPROVED state
const countdown = ref('')
let timer: ReturnType<typeof setInterval> | null = null

function updateCountdown() {
  if (status.value !== 'APPROVED') { countdown.value = ''; return }
  const now = new Date()
  const target = new Date()
  target.setHours(22, 0, 0, 0) // Default publish window 22:00
  if (now >= target) { countdown.value = '发布窗口已开启'; return }
  const diff = target.getTime() - now.getTime()
  const h = Math.floor(diff / 3600000)
  const m = Math.floor((diff % 3600000) / 60000)
  countdown.value = `距离发布窗口 ${h}h ${m}m`
}

onMounted(() => { updateCountdown(); timer = setInterval(updateCountdown, 60000) })
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.canvas-header {
  height: 48px; display: flex; align-items: center; justify-content: space-between;
  padding: 0 16px; border-bottom: 1px solid var(--el-border-color);
  background: var(--el-bg-color); flex-shrink: 0;
}
.header-left { display: flex; align-items: center; gap: 4px; font-size: 13px; }
.header-title { font-weight: bold; }
.header-factory { color: var(--el-color-primary); }
.header-dirty { color: var(--el-color-warning); font-size: 12px; }
.header-actions { display: flex; align-items: center; gap: 8px; }
.countdown { font-size: 12px; color: var(--el-color-warning); }
</style>
