<script setup lang="ts">
import { ref, computed } from 'vue';
import { Download, Search } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import FlywheelHeader from './components/FlywheelHeader.vue';
import { useFlywheelDomain } from './composables/useFlywheelDomain';
import { flywheelApi, type DatasetExportResult } from '@/api/smartbi/ai-flywheel';

const { domain } = useFlywheelDomain();

const dateRange = ref<[string, string] | null>(null);
const contractPass = ref<'all' | 'pass' | 'fail'>('all');
const served = ref<'all' | 'yes' | 'no'>('all');
const feedback = ref<'all' | 'up' | 'down'>('all');

const exporting = ref(false);
const result = ref<DatasetExportResult | null>(null);
const error = ref('');

const previewLines = computed(() => {
  if (!result.value) return [];
  return result.value.jsonl.split('\n').slice(0, 5);
});

async function runExport() {
  exporting.value = true;
  error.value = '';
  try {
    result.value = await flywheelApi.exportDataset({
      domain: domain.value,
      start_date: dateRange.value?.[0],
      end_date: dateRange.value?.[1],
      contract_pass: contractPass.value === 'all' ? undefined : contractPass.value === 'pass',
      served: served.value === 'all' ? undefined : served.value === 'yes',
      feedback: feedback.value === 'all' ? undefined : feedback.value,
    });
    ElMessage.success(`已生成 ${result.value.count} 条训练对, 可下载 JSONL`);
  } catch (e) {
    // 禁止降级处理: 导出失败就明确失败, result 保持 null (不渲染任何编造的训练对预览/
    // 下载不了假 JSONL), 常驻错误横幅 + sticky toast。
    const msg = e instanceof Error ? e.message : String(e);
    error.value = msg;
    result.value = null;
    ElMessage({ message: `导出失败: ${msg}`, type: 'error', duration: 0, showClose: true });
  } finally {
    exporting.value = false;
  }
}

function downloadJsonl() {
  if (!result.value) return;
  const blob = new Blob([result.value.jsonl], { type: 'application/jsonl;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const stamp = new Date().toISOString().slice(0, 10);
  a.href = url;
  a.download = `flywheel-dataset-${domain.value}-${stamp}.jsonl`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
</script>

<template>
  <div class="page-container">
    <FlywheelHeader v-model:domain="domain" />

    <el-card shadow="never" class="filter-card">
      <template #header><span>筛选条件</span></template>
      <el-form label-width="90px" class="filter-form">
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="时间范围">
              <el-date-picker
                v-model="dateRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="5">
            <el-form-item label="契约通过">
              <el-select v-model="contractPass" style="width: 100%">
                <el-option value="all" label="全部" />
                <el-option value="pass" label="仅通过" />
                <el-option value="fail" label="仅失败" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="5">
            <el-form-item label="是否已服务">
              <el-select v-model="served" style="width: 100%">
                <el-option value="all" label="全部" />
                <el-option value="yes" label="已服务" />
                <el-option value="no" label="未服务" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="6">
            <el-form-item label="反馈">
              <el-select v-model="feedback" style="width: 100%">
                <el-option value="all" label="全部" />
                <el-option value="up" label="仅 👍" />
                <el-option value="down" label="仅 👎" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item>
          <el-button type="primary" :icon="Search" :loading="exporting" @click="runExport">生成训练对</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-alert
      v-if="error"
      :title="`后端接口不可用: ${error}`"
      type="error"
      :closable="false"
      show-icon
      class="load-error-alert"
      data-test="flywheel-dataset-error"
    />

    <el-card v-if="result" shadow="never" class="result-card">
      <template #header>
        <div class="section-header">
          <span>导出结果</span>
          <el-button type="success" :icon="Download" @click="downloadJsonl">下载 JSONL ({{ result.count }} 条)</el-button>
        </div>
      </template>
      <p class="preview-hint">格式: 问句 → sealed plan → 反馈标签。以下为前 {{ previewLines.length }} 条预览:</p>
      <pre class="jsonl-preview">{{ previewLines.join('\n') }}</pre>
    </el-card>

    <el-empty v-else-if="!error" description="设置筛选条件后点击「生成训练对」查看预览并下载" :image-size="90" />
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
}
.filter-card {
  margin-bottom: 16px;
}
.load-error-alert {
  margin-bottom: 16px;
}
.filter-form :deep(.el-form-item) {
  margin-bottom: 12px;
}
.result-card {
  margin-bottom: 16px;
}
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.preview-hint {
  color: #909399;
  font-size: 13px;
  margin: 0 0 8px;
}
.jsonl-preview {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 12px;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow-y: auto;
}

@media (max-width: 768px) {
  .page-container {
    padding: 12px;
  }
}
</style>
