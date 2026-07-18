<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { ElMessage } from 'element-plus';
import {
  isRestaurantAgentRunActive,
  replayGrossMarginDeclineRun,
  streamGrossMarginDeclineRun,
} from '@/api/smartbi/restaurant-agent-runs';
import type {
  RestaurantAgentEventType,
  RestaurantAgentEventV1,
  RestaurantAgentRunState,
  RestaurantAgentTerminalOutcome,
} from '@/types/restaurant-agent-run';
import { RESTAURANT_AGENT_RUN_ROUTE } from '@/types/restaurant-agent-run';

const props = defineProps<{
  factoryId: string;
  startDate: string;
  endDate: string;
  eligible: boolean;
}>();

const isReceiving = ref(false);
const stoppedReceiving = ref(false);
const runId = ref<string | null>(null);
const lastSequence = ref(0);
const events = ref<RestaurantAgentEventV1[]>([]);
const runState = ref<RestaurantAgentRunState | null>(null);
const terminalOutcome = ref<RestaurantAgentTerminalOutcome | null>(null);
const failureCode = ref<string | null>(null);
const errorMessage = ref('');
let controller: AbortController | null = null;

const hasValidWindow = computed(() => (
  /^\d{4}-\d{2}-\d{2}$/.test(props.startDate)
  && /^\d{4}-\d{2}-\d{2}$/.test(props.endDate)
  && props.startDate <= props.endDate
));

const isAvailable = computed(() => (
  isRestaurantAgentRunActive()
  && props.eligible
  && Boolean(props.factoryId.trim())
  && hasValidWindow.value
));

const stateLabel = computed(() => {
  if (stoppedReceiving.value) return '已停止接收';
  if (isReceiving.value) return '正在接收持久化事件';
  const labels: Partial<Record<RestaurantAgentRunState, string>> = {
    COMPLETED: '分析完成',
    PARTIAL: '部分完成',
    FAILED: '分析失败',
    CANCELLED: '服务端已记录取消',
    BUDGET_EXCEEDED: '分析预算已用尽',
    RUNNING: '运行中',
  };
  return runState.value ? labels[runState.value] ?? runState.value : '';
});

const eventLabels: Record<RestaurantAgentEventType, string> = {
  RUN_STARTED: '运行已创建',
  ROUTE_SELECTED: '已选择固定毛利归因路线',
  PLAN_CREATED: '读取计划已持久化',
  STEP_STARTED: '开始读取数据',
  STEP_COMPLETED: '数据读取完成',
  STEP_FAILED: '数据读取失败',
  BUDGET_EXCEEDED: '运行预算已用尽',
  RUN_CANCELLED: '服务端记录运行取消',
  RUN_COMPLETED: '运行完成',
  RUN_FAILED: '运行失败',
};

function mergeEvents(incoming: RestaurantAgentEventV1[]) {
  const bySequence = new Map(events.value.map((event) => [event.sequence, event]));
  for (const event of incoming) bySequence.set(event.sequence, event);
  events.value = Array.from(bySequence.values()).sort((a, b) => a.sequence - b.sequence);
  lastSequence.value = Math.max(lastSequence.value, ...incoming.map((event) => event.sequence), 0);
}

async function refreshReplay(afterSequence: number) {
  if (!runId.value) return;
  const replay = await replayGrossMarginDeclineRun(
    props.factoryId,
    runId.value,
    afterSequence,
  );
  mergeEvents(replay.events);
  runState.value = replay.state;
  terminalOutcome.value = replay.terminalOutcome;
  failureCode.value = replay.failureCode;
}

async function startRun() {
  if (!isAvailable.value || isReceiving.value) return;
  controller?.abort();
  controller = new AbortController();
  const localController = controller;
  isReceiving.value = true;
  stoppedReceiving.value = false;
  runId.value = null;
  lastSequence.value = 0;
  events.value = [];
  runState.value = 'RUNNING';
  terminalOutcome.value = null;
  failureCode.value = null;
  errorMessage.value = '';

  try {
    const stream = await streamGrossMarginDeclineRun(
      props.factoryId,
      {
        schemaVersion: '1.0',
        routeCode: RESTAURANT_AGENT_RUN_ROUTE,
        startDate: props.startDate,
        endDate: props.endDate,
      },
      {
        onRunId: (id) => { runId.value = id; },
        onEvent: (event) => {
          runId.value = event.runId;
          mergeEvents([event]);
        },
      },
      localController.signal,
    );
    if (stream.runId) runId.value = stream.runId;
    lastSequence.value = Math.max(lastSequence.value, stream.lastSequence);
    if (!stoppedReceiving.value) await refreshReplay(lastSequence.value);
  } catch (error) {
    if (localController.signal.aborted || stoppedReceiving.value) return;
    errorMessage.value = error instanceof Error ? error.message : '毛利下降分析连接失败';
    ElMessage.error(errorMessage.value);
  } finally {
    if (controller === localController) controller = null;
    isReceiving.value = false;
  }
}

function stopReceiving() {
  if (!isReceiving.value) return;
  stoppedReceiving.value = true;
  controller?.abort();
  isReceiving.value = false;
}

onBeforeUnmount(() => {
  controller?.abort();
  controller = null;
});
</script>

<template>
  <section v-if="isAvailable" class="gross-margin-run" data-testid="gross-margin-decline-run">
    <div class="run-heading">
      <div>
        <strong>毛利下降归因</strong>
        <div class="run-window">使用看板区间：{{ startDate }} 至 {{ endDate }}</div>
      </div>
      <el-button
        v-if="!isReceiving"
        type="primary"
        size="small"
        data-testid="gross-margin-run-start"
        @click="startRun"
      >
        分析毛利下降原因
      </el-button>
      <el-button
        v-else
        size="small"
        data-testid="gross-margin-run-stop"
        @click="stopReceiving"
      >
        停止接收
      </el-button>
    </div>

    <div v-if="stateLabel" class="run-state" data-testid="gross-margin-run-state">
      {{ stateLabel }}
      <span v-if="runId" class="run-id">Run {{ runId }}</span>
    </div>
    <div v-if="stoppedReceiving" class="run-note">
      已停止接收。此操作只关闭当前页面连接，不代表服务端任务已取消。
    </div>
    <div v-if="errorMessage" class="run-error">{{ errorMessage }}</div>

    <ol v-if="events.length" class="run-events" data-testid="gross-margin-run-events">
      <li v-for="event in events" :key="event.sequence">
        <span class="event-sequence">#{{ event.sequence }}</span>
        {{ eventLabels[event.eventType] }}
        <span v-if="event.toolName" class="event-tool">{{ event.toolName }}</span>
      </li>
    </ol>

    <div v-if="terminalOutcome" class="run-outcome" data-testid="gross-margin-run-outcome">
      <div class="outcome-title">
        持久化结论：{{ terminalOutcome.status }}
        <span>{{ terminalOutcome.attributionSupported ? '支持归因' : '证据不足以归因' }}</span>
      </div>
      <ul v-if="terminalOutcome.claims.length" class="claim-list">
        <li v-for="claim in terminalOutcome.claims" :key="`${claim.evidenceId}:${claim.factId}`">
          <strong>{{ claim.metric }}</strong> {{ claim.value }}{{ claim.unit || '' }}
          <small>证据引用 {{ claim.evidenceId }} / {{ claim.factId }}</small>
        </li>
      </ul>
      <ul v-if="terminalOutcome.observations.length" class="observation-list">
        <li v-for="item in terminalOutcome.observations" :key="item">{{ item }}</li>
      </ul>
      <ul v-if="terminalOutcome.blockers.length" class="blocker-list">
        <li v-for="item in terminalOutcome.blockers" :key="item">阻塞：{{ item }}</li>
      </ul>
      <div v-if="failureCode" class="run-error">失败代码：{{ failureCode }}</div>
      <div class="run-note">这里只展示持久化事件和结论引用，不代表完整 EvidenceEnvelope。</div>
    </div>
  </section>
</template>

<style scoped>
.gross-margin-run {
  margin: 12px 20px 0;
  padding: 14px;
  border: 1px solid #d8c9a7;
  border-radius: 10px;
  background: #fffdf7;
  color: #2d4a3e;
}
.run-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.run-window,
.run-note,
.run-id {
  margin-top: 4px;
  color: #7a7468;
  font-size: 12px;
}
.run-state {
  margin-top: 10px;
  font-weight: 600;
}
.run-id {
  margin-left: 8px;
  font-weight: 400;
}
.run-events,
.claim-list,
.observation-list,
.blocker-list {
  margin: 10px 0 0;
  padding-left: 20px;
  font-size: 13px;
}
.run-events li,
.claim-list li {
  margin-top: 6px;
}
.event-sequence {
  color: #9a7b42;
}
.event-tool {
  margin-left: 6px;
  color: #71664f;
  font-family: monospace;
}
.run-outcome {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #e6dcc7;
}
.outcome-title {
  font-weight: 700;
}
.outcome-title span {
  margin-left: 8px;
  color: #8b6d34;
  font-size: 12px;
}
.claim-list small {
  display: block;
  color: #837d71;
}
.run-error,
.blocker-list {
  margin-top: 8px;
  color: #b42318;
  font-size: 13px;
}
</style>
