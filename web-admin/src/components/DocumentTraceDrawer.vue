<script setup lang="ts">
/**
 * 单据追踪抽屉 (销售 / 采购 / 调拨 共用)。
 *
 * 客户 2026-07-30 原话: 在一张单子上看不到它的上下游、也没有「返回原单」——「严重影响工作效率」。
 * 本抽屉把后端算好的**真实外键**链路按 上游来源 → 执行环节 → 结算与出库 铺开, 每条都能一键前往;
 * 上游那几条就是「返回原单」。
 *
 * 禁止降级: 加载失败**不**渲染空时间轴装作"没有关联单据", 而是把后端 message 原样贴出来 +
 * 给重试按钮; 后端报的 missingLinks (记录了但解析不出来的链接) 也逐条显示, 不静默省略。
 */
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { ApiResponse } from '@/types/api';
import type { DocumentTrace, TraceDocument } from '@/types/businessDocumentTrace';
import {
  documentTraceTarget,
  traceDirectionLabel,
  traceDocumentLabel,
} from '@/utils/documentTraceNavigation';
import { traceStatusLabel } from '@/utils/enumDisplay';
import { getErrorMessage } from '@/utils/errorToast';

const props = defineProps<{
  modelValue: boolean;
  /** 抽屉标题, e.g. 「销售订单单据追踪」 */
  title: string;
  /** 锚点单据的中文名, e.g. 「销售订单」 */
  anchorLabel: string;
  /** 拉取函数由调用方给 —— 三个入口路径不同, 但加载/报错/渲染逻辑在这里只有一份。 */
  fetcher: () => Promise<ApiResponse<DocumentTrace>>;
}>();

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>();

const router = useRouter();
const loading = ref(false);
const trace = ref<DocumentTrace | null>(null);
const loadError = ref('');

async function load() {
  loading.value = true;
  loadError.value = '';
  trace.value = null;
  try {
    const response = await props.fetcher();
    // 禁止降级: success=false 或 data 缺失一律当失败, 不拿空对象顶上去
    if (!response.success || !response.data) {
      throw new Error(response.message || '加载单据追踪失败');
    }
    trace.value = response.data;
  } catch (error) {
    // 后端 message 原样展示 (request.ts 拦截器已弹 sticky toast, 这里再钉在抽屉里免得用户错过)
    loadError.value = getErrorMessage(error, '加载单据追踪失败，请检查网络后重试');
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) void load();
  },
  { immediate: true },
);

function close() {
  emit('update:modelValue', false);
}

function openDocument(document: TraceDocument) {
  const target = documentTraceTarget(document);
  if (!target) {
    ElMessage.info(`${traceDocumentLabel(document.documentType)}已记录在当前单据中，无独立详情页`);
    return;
  }
  close();
  void router.push(target);
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="620px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="document-trace">
      <el-alert
        v-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />
      <el-button v-if="loadError" type="primary" @click="load">重新加载</el-button>

      <template v-if="trace && !loadError">
        <el-descriptions :column="2" border style="margin-bottom: 16px">
          <el-descriptions-item :label="anchorLabel">
            {{ trace.anchorNumber || trace.anchorId }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ traceStatusLabel(trace.anchorType, trace.anchorStatus) }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- 后端记录了链接但当前解析不出来 (源单被删/跨工厂不可见/超出展示条数) —— 明说, 不静默省略 -->
        <el-alert
          v-for="missing in trace.missingLinks"
          :key="missing"
          :title="missing"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 10px"
        />

        <el-empty v-if="trace.documents.length === 0" description="当前单据暂无已关联的上下游单据" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="document in trace.documents"
            :key="`${document.documentType}-${document.documentId}`"
            :timestamp="document.occurredAt || undefined"
            placement="top"
          >
            <el-card shadow="never" class="trace-document-card">
              <div class="trace-document-header">
                <div>
                  <el-tag size="small" effect="plain">{{ traceDirectionLabel(document.direction) }}</el-tag>
                  <strong>{{ traceDocumentLabel(document.documentType) }}</strong>
                </div>
                <el-button type="primary" link @click="openDocument(document)">前往单据</el-button>
              </div>
              <div class="trace-document-number">{{ document.documentNumber || document.documentId }}</div>
              <div class="trace-document-meta">
                <span>{{ document.relation || '-' }}</span>
                <!-- 按单据类型选状态表: 同一个码在不同单据含义不同 (REJECTED 采购收货是「已退回」
                     别处是「已驳回」), 全局表必然有一半显示错。 -->
                <el-tag v-if="document.status" size="small" type="info">
                  {{ traceStatusLabel(document.documentType, document.status) }}
                </el-tag>
              </div>
            </el-card>
          </el-timeline-item>
        </el-timeline>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.trace-document-card {
  border: 1px solid var(--el-border-color-lighter);
}
.trace-document-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.trace-document-header strong {
  margin-left: 8px;
}
.trace-document-number {
  margin-top: 6px;
  font-weight: 600;
}
.trace-document-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
