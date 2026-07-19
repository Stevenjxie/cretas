<script setup lang="ts">
import { ref } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { getRunTrace } from '@/api/agent-ops';
import type { AgentRunTrace } from '@/api/agent-ops';

const auth = useAuthStore();
const runId = ref('');
const loading = ref(false);
const error = ref('');
const trace = ref<AgentRunTrace | null>(null);

async function search() {
  if (!auth.factoryId || !runId.value.trim()) return;
  loading.value = true;
  error.value = '';
  trace.value = null;
  try {
    trace.value = (await getRunTrace(auth.factoryId, runId.value.trim())).data;
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'Run Trace 加载失败';
  } finally { loading.value = false; }
}

function safeJson(value: Record<string, unknown>): string {
  return JSON.stringify(value, null, 2);
}
</script>

<template>
  <div class="panel" data-testid="run-trace-view">
    <div class="panel-head"><div><h2>Run Trace</h2><p>只读查看租户内已有 run/event 账本；敏感字段由服务端 allowlist 与脱敏策略拦截。</p></div></div>
    <div class="search-row">
      <el-input v-model="runId" placeholder="输入 Run UUID" clearable @keyup.enter="search" />
      <el-button type="primary" :loading="loading" @click="search">读取轨迹</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon data-testid="trace-error" />
    <el-empty v-else-if="!trace && !loading" description="输入 Run UUID 查看路由、预算、证据步骤和终态。" data-testid="trace-empty" />
    <el-skeleton v-else-if="loading" :rows="6" animated />

    <template v-else-if="trace">
      <section class="summary-grid">
        <div><span>状态</span><strong>{{ trace.state }}</strong></div>
        <div><span>路由</span><strong>{{ trace.routeCode }}</strong></div>
        <div><span>轮次 / Tool</span><strong>{{ trace.counters.roundsUsed ?? 0 }} / {{ trace.counters.toolCallsUsed ?? 0 }}</strong></div>
        <div><span>Facts</span><strong>{{ trace.counters.factsUsed ?? 0 }}</strong></div>
      </section>
      <el-alert v-if="trace.failureCode" :title="trace.failureCode" type="error" :closable="false" />
      <div class="trace-list">
        <article v-for="event in trace.events" :key="event.sequence" class="event-card">
          <div class="sequence">{{ event.sequence }}</div>
          <div class="event-main">
            <div class="event-head"><strong>{{ event.eventType }}</strong><span>{{ event.toolName || event.stepId || 'run' }}</span></div>
            <pre>{{ safeJson(event.payload) }}</pre>
          </div>
        </article>
      </div>
    </template>
  </div>
</template>

<style scoped>
.panel { padding: 24px; border: 1px solid #dce4e7; border-radius: 16px; background: #fff; }
.panel-head { margin-bottom: 18px; } h2 { margin: 0 0 6px; font-size: 20px; } p { margin: 0; color: #718086; }
.search-row { display: grid; grid-template-columns: minmax(260px, 620px) auto; gap: 10px; margin-bottom: 18px; }
.summary-grid { display: grid; grid-template-columns: repeat(4,1fr); gap: 12px; margin-bottom: 20px; }
.summary-grid div { padding: 15px; border: 1px solid #e0e7e9; border-radius: 10px; background: #f8faf9; }
.summary-grid span { display: block; margin-bottom: 6px; color: #78858a; font-size: 12px; } .summary-grid strong { font-size: 15px; overflow-wrap: anywhere; }
.trace-list { position: relative; margin-top: 18px; } .event-card { display: grid; grid-template-columns: 34px 1fr; gap: 12px; margin-bottom: 12px; }
.sequence { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 50%; background: #356f60; color: #fff; font-size: 12px; }
.event-main { padding: 14px 16px; border: 1px solid #dce4e7; border-radius: 10px; } .event-head { display: flex; justify-content: space-between; gap: 12px; }
.event-head span { color: #718086; font-size: 12px; } pre { margin: 10px 0 0; max-height: 220px; overflow: auto; color: #3c4c52; background: #f5f8f7; padding: 10px; border-radius: 7px; font-size: 12px; }
@media (max-width: 760px) { .search-row, .summary-grid { grid-template-columns: 1fr; } }
</style>
