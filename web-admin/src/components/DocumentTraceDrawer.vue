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
import { computed, ref, watch } from 'vue';
import type { ApiResponse } from '@/types/api';
import type { DocumentTrace, TraceDocument } from '@/types/businessDocumentTrace';
import {
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

const loading = ref(false);
const trace = ref<DocumentTrace | null>(null);
const loadError = ref('');

/**
 * 就地展开的那一条 (客户 2026-07-31 拍板: 追踪里看详情**不跳页**)。
 *
 * 原本每条右边是「前往单据」→ `router.push` 跳到别的模块, 销售订单这一屏就没了,
 * 想看下一条得从头再来。客户原话:「这边最多只是把单据信息放出来, 不会做跳转」。
 *
 * 选中一条时抽屉**整体变宽**, 右侧并排出详情 —— 而不是再叠一层抽屉盖住列表,
 * 那样来回对比要反复开关, 且两层叠着容易一次关掉两层退回订单页。
 */
const selectedKey = ref('');
const documentKey = (d: TraceDocument) => `${d.documentType}-${d.documentId}`;
const selected = computed(
  () => trace.value?.documents.find((d) => documentKey(d) === selectedKey.value) ?? null,
);
/** 选中才变宽; 没选中保持原来的 620px, 不平白占半个屏。 */
const drawerSize = computed(() => (selected.value ? '1080px' : '620px'));

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

/** 同一条再点一次收起 —— 不然想收起来只能关掉整个抽屉。 */
function toggleDocument(document: TraceDocument) {
  const key = documentKey(document);
  selectedKey.value = selectedKey.value === key ? '' : key;
}

watch(
  () => props.modelValue,
  (visible) => {
    // 关掉再打开时不该还停在上次选中的那条
    if (!visible) selectedKey.value = '';
  },
);
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    :size="drawerSize"
    class="document-trace-drawer"
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
        <div v-else class="trace-split" :class="{ 'is-expanded': !!selected }">
          <el-timeline class="trace-list">
            <el-timeline-item
              v-for="document in trace.documents"
              :key="documentKey(document)"
              :timestamp="document.occurredAt || undefined"
              placement="top"
            >
              <el-card
                shadow="never"
                class="trace-document-card"
                :class="{ 'is-selected': documentKey(document) === selectedKey }"
                data-testid="trace-document-card"
                @click="toggleDocument(document)"
              >
                <div class="trace-document-header">
                  <div>
                    <el-tag size="small" effect="plain">{{ traceDirectionLabel(document.direction) }}</el-tag>
                    <strong>{{ traceDocumentLabel(document.documentType) }}</strong>
                  </div>
                  <!-- 不再是「前往单据」跳走 —— 客户 2026-07-31:「最多只是把单据信息放出来, 不会做跳转」 -->
                  <el-button
                    type="primary"
                    link
                    data-testid="trace-detail-toggle"
                    :aria-expanded="documentKey(document) === selectedKey"
                    @click.stop="toggleDocument(document)"
                  >{{ documentKey(document) === selectedKey ? '收起' : '查看详情' }}</el-button>
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

          <aside v-if="selected" class="trace-detail" data-testid="trace-detail-panel">
            <div class="trace-detail-head">
              <strong>{{ traceDocumentLabel(selected.documentType) }}</strong>
              <span class="trace-detail-relation">{{ selected.relation || '' }}</span>
            </div>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="单号">
                {{ selected.documentNumber || selected.documentId }}
              </el-descriptions-item>
              <el-descriptions-item v-if="selected.status" label="状态">
                {{ traceStatusLabel(selected.documentType, selected.status) }}
              </el-descriptions-item>
              <el-descriptions-item v-if="selected.occurredAt" label="发生时间">
                {{ selected.occurredAt }}
              </el-descriptions-item>
              <!-- 后端构建链路时本来就把实体读出来了, 关键字段一并带回 —— 零新增接口。
                   拿不到的字段后端**直接不放**, 所以这里不会渲染出空标签。 -->
              <el-descriptions-item
                v-for="field in selected.details || []"
                :key="field.label"
                :label="field.label"
              >{{ field.value }}</el-descriptions-item>
            </el-descriptions>
            <p v-if="!(selected.details || []).length" class="trace-detail-thin">
              该单据类型暂未提供更多字段，以上为链路本身记录的信息。
            </p>
          </aside>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
/* 选中一条时抽屉变宽, 列表与详情并排 —— 列表不被盖住 (客户 2026-07-31) */
.trace-split {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
  align-items: start;
}
.trace-split.is-expanded {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.05fr);
}
/* 窄屏放不下并排, 退回上下堆叠 —— 抽屉宽度也会被 el-drawer 的百分比上限收住 */
@media (max-width: 900px) {
  .trace-split.is-expanded { grid-template-columns: 1fr; }
}
.trace-list { min-width: 0; }
.trace-detail {
  min-width: 0;
  position: sticky;
  top: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 12px;
  background: var(--el-fill-color-lighter);
}
.trace-detail-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 10px;
}
.trace-detail-relation {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.trace-detail-thin {
  margin: 10px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.trace-document-card {
  border: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}
.trace-document-card.is-selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
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
