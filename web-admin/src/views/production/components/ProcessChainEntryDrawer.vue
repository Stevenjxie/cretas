<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Plus, Delete } from '@element-plus/icons-vue';
import {
  submitProcessChain,
  type ProcessChainEntryRequest, type BatchEntry, type StepEntry,
} from '@/api/processEntry';

const props = defineProps<{
  visible: boolean;
  factoryId: string;
  planId: string;
  productTypeId: string;
  /** 该产品的工序列表(来自 ProductWorkProcess), [{processOrder, processName, processCategory}] */
  processList: Array<{ processOrder: number; processName: string; processCategory?: string | null }>;
  plannedQuantity: number;
  productName?: string;
}>();
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void; (e: 'submitted', r: any): void }>();

const submitting = ref(false);
const batches = ref<BatchEntry[]>([]);
const idempotencyKey = ref<string>('');

function blankStep(p: { processOrder: number; processName: string; processCategory?: string | null }): StepEntry {
  return {
    processOrder: p.processOrder, processName: p.processName, processCategory: p.processCategory ?? null,
    inputQuantity: null, outputQuantity: null, unit: 'kg',
    laborStartTime: null, laborEndTime: null, workerCount: null,
    byproducts: [], wasteQuantity: null, sampleRetainQuantity: null,
    rawMaterialInputs: [], potCount: null, potRawKgs: [], upstreamSources: [],
  };
}
function addBatch(finished: boolean) {
  const key = (finished ? '成品' : '半成品') + (batches.value.length + 1);
  batches.value.push({
    clientBatchKey: key, productTypeId: props.productTypeId, finished,
    steps: props.processList.map(blankStep),
  });
}
function removeBatch(b: BatchEntry) { batches.value = batches.value.filter((x) => x !== b); }

// 半成品批 keys (供熟制上游来源选择)
const wipBatchKeys = computed(() =>
  batches.value.filter((b) => !b.finished).map((b) => b.clientBatchKey));

watch(() => props.visible, (v) => {
  if (v && batches.value.length === 0) {
    addBatch(false);
    addBatch(true);
    if (!idempotencyKey.value) {
      idempotencyKey.value = 'web-process-' + props.planId + '-' + Date.now();
    }
  }
});

// 防呆 Rule 1: 每道投入默认=上道产出
function prevOutput(b: BatchEntry, idx: number): number | null {
  return idx > 0 ? b.steps[idx - 1].outputQuantity : null;
}
function isWaterStep(s: StepEntry): boolean {
  return (s.processName || '').includes('滚揉') || (s.processName || '').includes('注');
}
function isCookingStep(s: StepEntry): boolean {
  return s.processCategory === 'SEASONING' || (s.processName || '').includes('熟制');
}

function validate(): string | null {
  if (batches.value.length === 0) return '至少添加一个批次';
  for (const b of batches.value) {
    for (let i = 0; i < b.steps.length; i++) {
      const s = b.steps[i];
      if (s.outputQuantity != null && s.inputQuantity != null
          && !isWaterStep(s) && Number(s.outputQuantity) > Number(s.inputQuantity)) {
        return `${b.clientBatchKey} 「${s.processName}」产出 ${s.outputQuantity} > 投入 ${s.inputQuantity}(非注水工序不允许)`;
      }
      // 熟制来源合计校验
      if (isCookingStep(s) && s.upstreamSources && s.upstreamSources.length) {
        const sum = s.upstreamSources.reduce((a, u) => a + Number(u.feedQuantityKg || 0), 0);
        if (s.inputQuantity != null && Math.abs(sum - Number(s.inputQuantity)) > 0.001) {
          return `${b.clientBatchKey} 熟制: 上游投料合计 ${sum} ≠ 投入 ${s.inputQuantity}`;
        }
      }
    }
  }
  return null;
}

async function submit() {
  const err = validate();
  if (err) { ElMessage({ message: err, type: 'warning', duration: 0, showClose: true }); return; }
  const body: ProcessChainEntryRequest = {
    idempotencyKey: idempotencyKey.value,
    batches: batches.value,
  };
  submitting.value = true;
  try {
    const resp = await submitProcessChain(props.factoryId, props.planId, body);
    const r = resp.data;
    if (r?.warnings?.length) {
      ElMessage({ message: '已提交(含提示): ' + r.warnings.join('; '), type: 'warning', duration: 0, showClose: true });
    } else {
      ElMessage.success(`逐道录入成功, 成品批 ${r?.finishedBatchNumber || ''}`);
    }
    emit('submitted', r);
    emit('update:visible', false);
  } catch (e: any) {
    ElMessage({ message: e.message || '提交失败', type: 'error', duration: 0, showClose: true });
  } finally {
    submitting.value = false;
  }
}
function close() {
  batches.value = [];
  idempotencyKey.value = '';
  emit('update:visible', false);
}
</script>

<template>
  <el-drawer :model-value="visible" @update:model-value="emit('update:visible', $event)"
             :title="`逐道工序录入 — ${productName || productTypeId} (计划数量 ${plannedQuantity})`"
             size="78%" :close-on-click-modal="false">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
      title="先录半成品批(原料→焯水), 再录成品批(熟制混锅→包装)。熟制工序选上游来源批 + 各自投料kg。" />

    <div v-if="processList.length === 0">
      <el-empty description="该产品未配置工序">
        <el-button type="primary" @click="$router.push('/system/product-processes')">去配置产品-工序</el-button>
      </el-empty>
    </div>

    <template v-else>
      <div style="margin-bottom:10px">
        <el-button :icon="Plus" @click="addBatch(false)">+ 半成品批</el-button>
        <el-button :icon="Plus" type="primary" @click="addBatch(true)">+ 成品批</el-button>
      </div>

      <el-collapse>
        <el-collapse-item v-for="(b, bi) in batches" :key="bi"
          :title="`${b.clientBatchKey} ${b.finished ? '(成品)' : '(半成品)'}`" :name="bi">
          <template #title>
            <span>{{ b.clientBatchKey }} {{ b.finished ? '(成品)' : '(半成品)' }}</span>
            <el-button link type="danger" :icon="Delete" style="margin-left:auto"
              @click.stop="removeBatch(b)">删批</el-button>
          </template>

          <el-input v-model="b.clientBatchKey" size="small" style="width:220px;margin-bottom:8px">
            <template #prepend>批次标识</template>
          </el-input>

          <el-card v-for="(s, si) in b.steps" :key="si" shadow="never" style="margin-bottom:8px">
            <template #header>
              <b>{{ s.processOrder }}. {{ s.processName }}</b>
              <el-tag v-if="isWaterStep(s)" size="small" type="warning" style="margin-left:8px">注水(可&gt;100%)</el-tag>
              <el-tag v-if="isCookingStep(s)" size="small" style="margin-left:8px">熟制(混锅)</el-tag>
            </template>

            <el-form label-width="96px" size="small">
              <el-form-item label="投入重量">
                <el-input-number v-model="s.inputQuantity" :precision="2" :min="0" />
                <span style="margin-left:8px;color:#909399" v-if="prevOutput(b, si) != null">
                  上道产出 {{ prevOutput(b, si) }}（投入参考）</span>
              </el-form-item>
              <el-form-item label="产出重量">
                <el-input-number v-model="s.outputQuantity" :precision="2" :min="0"
                  :max="isWaterStep(s) ? undefined : (s.inputQuantity ?? undefined)" />
                <span v-if="!isWaterStep(s) && s.inputQuantity != null" style="margin-left:8px;color:#909399">
                  ≤ 投入 {{ s.inputQuantity }}</span>
              </el-form-item>
              <el-form-item label="人工">
                <el-time-select v-model="s.laborStartTime" placeholder="开始" start="00:00" step="00:15" end="23:45" style="width:110px" />
                <el-time-select v-model="s.laborEndTime" placeholder="结束" start="00:00" step="00:15" end="23:45" style="width:110px;margin:0 8px" />
                <el-input-number v-model="s.workerCount" :min="0" :precision="0" placeholder="人数" />
              </el-form-item>

              <!-- 首道领料 -->
              <el-form-item v-if="si === 0 && !b.finished" label="领料">
                <el-button size="small" @click="s.rawMaterialInputs!.push({ materialBatchId: '', quantity: null })">+ 原料批</el-button>
                <div v-for="(ri, ri2) in s.rawMaterialInputs" :key="ri2" style="margin-top:4px">
                  <el-input v-model="ri.materialBatchId" size="small" placeholder="原料 MaterialBatch.id" style="width:240px" />
                  <el-input-number v-model="ri.quantity" :precision="2" :min="0" placeholder="数量" style="margin-left:8px" />
                  <el-button link type="danger" @click="s.rawMaterialInputs!.splice(ri2,1)">删</el-button>
                </div>
              </el-form-item>

              <!-- 熟制: 锅数 + 上游来源 -->
              <template v-if="isCookingStep(s)">
                <el-form-item label="锅数">
                  <el-input-number v-model="s.potCount" :min="1" :precision="0" />
                </el-form-item>
                <el-form-item label="上游来源">
                  <el-button size="small" :disabled="wipBatchKeys.length === 0"
                    @click="s.upstreamSources!.push({ sourceClientBatchKey: '', feedQuantityKg: null })">+ 来源批</el-button>
                  <el-empty v-if="wipBatchKeys.length === 0" :image-size="40" description="先添加并录入半成品批" />
                  <div v-for="(us, us2) in s.upstreamSources" :key="us2" style="margin-top:4px">
                    <el-select v-model="us.sourceClientBatchKey" placeholder="来源半成品批" style="width:200px">
                      <el-option v-for="k in wipBatchKeys" :key="k" :label="k" :value="k" />
                    </el-select>
                    <el-input-number v-model="us.feedQuantityKg" :precision="2" :min="0" placeholder="投料kg" style="margin-left:8px" />
                    <el-button link type="danger" @click="s.upstreamSources!.splice(us2,1)">删</el-button>
                  </div>
                </el-form-item>
              </template>

              <!-- 末道留样 -->
              <el-form-item v-if="si === b.steps.length - 1 && b.finished" label="留样(盒)">
                <el-input-number v-model="s.sampleRetainQuantity" :min="0" :precision="0" />
              </el-form-item>
              <el-form-item label="损耗">
                <el-input-number v-model="s.wasteQuantity" :precision="2" :min="0" />
              </el-form-item>
            </el-form>
          </el-card>
        </el-collapse-item>
      </el-collapse>
    </template>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交逐道录入</el-button>
    </template>
  </el-drawer>
</template>
