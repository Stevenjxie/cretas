<template>
  <section class="tpl-grid-section">
    <header class="tpl-grid-header">
      <div class="tpl-grid-title">
 <span class="tpl-grid-icon"></span>
        <span class="tpl-grid-title-text">模板分析</span>
        <span class="tpl-grid-subtitle">AI 自动为本次上传生成的 {{ codes.length }} 项分析</span>
      </div>
      <el-button
        v-if="!loading"
        size="small"
        type="primary"
        plain
        :loading="loading"
        @click="refresh"
      >
        <el-icon style="margin-right: 4px"><Refresh /></el-icon>
        刷新
      </el-button>
    </header>
    <div v-if="loadError" class="tpl-grid-error">
      加载模板分析失败: {{ loadError }}
    </div>
    <div class="tpl-grid" v-else>
      <TemplateCard
        v-for="code in codes"
        :key="code"
        :code="code"
        :item="itemsMap[code]"
        :status="statusFor(code)"
        @go-upload="onGoUpload"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import TemplateCard from './TemplateCard.vue';
import {
  getAnalysisResults,
  type AnalysisResultItem,
} from '@/api/smartbi/analysisResults';
import { getPageCodes } from '../composables/useTemplateMap';

const router = useRouter();

// UX P2-10: 空 state CTA — 跳 SmartBI 主页 (含上传入口)
function onGoUpload(_code: string) {
  router.push('/smart-bi/analysis');
}

const props = defineProps<{
  /** Page key (dashboard|finance|trend|restaurantv2) */
  pageKey: string;
  /** Factory id — usually from useFactoryStore in parent */
  factoryId: string;
  /** Optional: pin to one upload; omit for latest-per-code resolution */
  uploadId?: number;
}>();

const loading = ref(true);
const loadError = ref('');
const items = ref<AnalysisResultItem[]>([]);
const missingCodes = ref<string[]>([]);
const neverCodes = ref<string[]>([]);

const codes = computed(() => getPageCodes(props.pageKey));

const itemsMap = computed(() => {
  const m: Record<string, AnalysisResultItem> = {};
  for (const i of items.value) m[i.templateCode] = i;
  return m;
});

const hasAnyData = computed(() => items.value.length > 0);

function statusFor(code: string): 'loading' | 'loaded' | 'missing' | 'never' {
  if (loading.value) return 'loading';
  if (itemsMap.value[code]) return 'loaded';
  if (missingCodes.value.includes(code)) return 'missing';
  if (neverCodes.value.includes(code)) return 'never';
  return 'never'; // safe fallback
}

async function load() {
  if (!props.factoryId || codes.value.length === 0) return;
  loading.value = true;
  loadError.value = '';
  try {
    // pythonFetch returns the body directly (no {success, data} wrap).
    const body = await getAnalysisResults(
      props.factoryId,
      [...codes.value],
      props.uploadId !== undefined ? { uploadId: props.uploadId } : {},
    );
    items.value = body.items || [];
    missingCodes.value = body.missingCodes || [];
    neverCodes.value = body.neverMaterializedCodes || [];
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e);
    console.error('[TemplateGrid] load failed', e);
  } finally {
    loading.value = false;
  }
}

function refresh() {
  load();
}

onMounted(load);
watch(() => [props.factoryId, props.pageKey, props.uploadId], load);
</script>

<style scoped>
/* Give the template section its own visual container so users recognize
 * it as a distinct auto-generated analysis block (N3). */
.tpl-grid-section {
  margin-top: 32px;
  padding: 20px;
  background: linear-gradient(180deg, #f8faff 0%, #ffffff 100%);
  border-radius: 8px;
  border: 1px solid #e4e9f2;
}
.tpl-grid-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e4e9f2;
}
.tpl-grid-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.tpl-grid-icon {
  font-size: 20px;
}
.tpl-grid-title-text {
  font-size: 17px;
  font-weight: 700;
  color: #1f2937;
  letter-spacing: 0.5px;
}
.tpl-grid-subtitle {
  font-size: 12px;
  color: #909399;
  margin-left: 4px;
}
/* N9: align all cards to equal height in the row so empty-chart cards
 * don't create stair-step gaps. */
.tpl-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(380px, 1fr));
  grid-auto-rows: 1fr;
  gap: 16px;
  align-items: stretch;
}
.tpl-grid > * {
  height: 100%;
}
.tpl-grid-error {
  padding: 16px;
  background: #fef0f0;
  color: #f56c6c;
  border-radius: 4px;
}
</style>
