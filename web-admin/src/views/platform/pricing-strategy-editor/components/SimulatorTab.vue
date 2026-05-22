<!--
  SimulatorTab.vue — 价格策略模拟器 (preview, 不写日志).

  Per spec §5 + fool-proof Rule 1: 输入场景, 实时显示 finalPrice + appliedStrategies + warnings.
  调用 POST /strategies/simulate.
-->
<template>
  <div class="simulator">
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="never" class="input-card">
          <template #header>
            <div class="card-title">输入场景</div>
          </template>
          <el-form :model="form" label-width="120px" label-position="left" size="default">
            <el-form-item label="商品 ID" required>
              <el-input v-model="form.productId" placeholder="必填" />
            </el-form-item>
            <el-form-item label="数量">
              <el-input-number v-model="form.quantity" :min="1" :step="1" controls-position="right" />
            </el-form-item>
            <el-form-item label="标价">
              <el-input-number v-model="form.unitPriceList" :min="0" :step="10" :precision="2" controls-position="right" />
              <span class="suffix">元 / 件</span>
            </el-form-item>
            <el-form-item label="客户 ID">
              <el-input-number v-model="form.customerId" :min="0" :step="1" controls-position="right" placeholder="可选" />
            </el-form-item>
            <el-form-item label="客户分组">
              <el-select v-model="form.customerGroup" clearable placeholder="可选 (MEMBER 策略需要)" style="width: 100%">
                <el-option label="VIP" value="VIP" />
                <el-option label="IMPORTANT" value="IMPORTANT" />
                <el-option label="NORMAL" value="NORMAL" />
                <el-option label="LOW" value="LOW" />
              </el-select>
            </el-form-item>
            <el-form-item label="商品类目">
              <el-select v-model="form.productCategory" clearable filterable allow-create placeholder="可选 (类目过滤需要)" style="width: 100%">
                <el-option label="frozen 冻品" value="frozen" />
                <el-option label="deli 熟食" value="deli" />
                <el-option label="dry 干货" value="dry" />
              </el-select>
            </el-form-item>
            <el-form-item label="区域">
              <el-select v-model="form.region" clearable filterable allow-create placeholder="可选" style="width: 100%">
                <el-option label="east 华东" value="east" />
                <el-option label="south 华南" value="south" />
                <el-option label="north 华北" value="north" />
              </el-select>
            </el-form-item>
            <el-form-item label="成本估算">
              <el-input-number v-model="form.costEstimate" :min="0" :step="1" :precision="2" controls-position="right" placeholder="可选 (触发防呆 warning)" />
              <span class="suffix">元 / 件</span>
              <div class="hint">填入后引擎会检查 final price 是否 &lt; cost, 触发 warning (不阻塞)</div>
            </el-form-item>

            <el-button type="primary" :loading="loading" @click="onSimulate">
              {{ loading ? '计算中...' : '模拟计算' }}
            </el-button>
            <el-button @click="onReset">重置</el-button>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card shadow="never" class="result-card">
          <template #header>
            <div class="card-title">计算结果</div>
          </template>

          <el-empty v-if="!result && !error" description="填写左侧场景后点击模拟计算" />

          <el-alert
            v-if="error"
            :title="error"
            type="error"
            show-icon
            :closable="false"
            style="margin-bottom: 12px"
          />

          <template v-if="result">
            <!-- Price summary -->
            <div class="price-summary">
              <div class="price-row">
                <span class="label">原价 (单价 × 数量)</span>
                <span class="value original">¥{{ result.originalPrice.toFixed(2) }}</span>
              </div>
              <div class="price-row">
                <span class="label">折扣总额</span>
                <span class="value discount">- ¥{{ result.totalDiscount.toFixed(2) }}</span>
              </div>
              <el-divider style="margin: 8px 0" />
              <div class="price-row final">
                <span class="label">最终价</span>
                <span class="value final">¥{{ result.finalPrice.toFixed(2) }}</span>
              </div>
              <div class="price-row" v-if="result.originalPrice > 0">
                <span class="label">优惠比例</span>
                <span class="value pct">{{ discountPercent }}%</span>
              </div>
            </div>

            <!-- Warnings (fool-proof) -->
            <div v-if="result.warnings && result.warnings.length" class="warnings-block">
              <el-alert
                v-for="(w, idx) in result.warnings"
                :key="idx"
                :title="w"
                type="warning"
                show-icon
                :closable="false"
                style="margin-bottom: 6px"
              />
            </div>

            <!-- Applied strategies -->
            <div class="applied-strategies">
              <div class="block-title">应用的策略 ({{ result.appliedStrategies.length }})</div>
              <el-empty v-if="result.appliedStrategies.length === 0" description="未匹配到任何策略" :image-size="60" />
              <el-table v-else :data="result.appliedStrategies" border size="small">
                <el-table-column prop="strategyCode" label="策略代码" />
                <el-table-column prop="strategyType" label="类型" width="120">
                  <template #default="{ row }">
                    <el-tag size="small">{{ row.strategyType }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="折扣 (单件)" width="140">
                  <template #default="{ row }">
                    ¥{{ Number(row.discountApplied).toFixed(2) }}
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { simulateStrategy, type PricingResult, type SimulateRequest } from '@/api/pricingStrategyApi';

const props = defineProps<{
  factoryId: string;
}>();

const form = reactive<SimulateRequest>({
  productId: '',
  quantity: 1,
  unitPriceList: 100,
  customerId: null,
  customerGroup: null,
  productCategory: null,
  region: null,
  costEstimate: null,
});

const result = ref<PricingResult | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

const discountPercent = computed(() => {
  if (!result.value || result.value.originalPrice <= 0) return '0.00';
  return ((result.value.totalDiscount / result.value.originalPrice) * 100).toFixed(2);
});

async function onSimulate() {
  if (!form.productId?.trim()) {
    ElMessage.warning('请填写商品 ID');
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const req: SimulateRequest = {
      productId: form.productId.trim(),
      quantity: form.quantity ?? 1,
      unitPriceList: form.unitPriceList ?? 0,
      customerId: form.customerId || null,
      customerGroup: form.customerGroup || null,
      productCategory: form.productCategory || null,
      region: form.region || null,
      costEstimate: form.costEstimate ?? null,
    };
    result.value = await simulateStrategy(props.factoryId, req);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '模拟计算失败';
    result.value = null;
  } finally {
    loading.value = false;
  }
}

function onReset() {
  form.productId = '';
  form.quantity = 1;
  form.unitPriceList = 100;
  form.customerId = null;
  form.customerGroup = null;
  form.productCategory = null;
  form.region = null;
  form.costEstimate = null;
  result.value = null;
  error.value = null;
}
</script>

<style scoped>
.simulator {
  height: 100%;
  padding: 8px;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
}
.input-card, .result-card {
  height: 100%;
  min-height: 500px;
}
.hint {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.suffix {
  margin-left: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.price-summary {
  background: var(--el-fill-color-light);
  padding: 12px 16px;
  border-radius: 6px;
  margin-bottom: 12px;
}
.price-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 4px 0;
  font-size: 14px;
}
.price-row .label { color: var(--el-text-color-regular); }
.price-row .value { font-weight: 600; }
.price-row .value.original { color: var(--el-text-color-primary); }
.price-row .value.discount { color: var(--el-color-warning); }
.price-row.final { font-size: 16px; }
.price-row .value.final { color: var(--el-color-success); font-size: 20px; }
.price-row .value.pct { color: var(--el-color-primary); }
.warnings-block {
  margin: 12px 0;
}
.applied-strategies {
  margin-top: 16px;
}
.block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 8px;
}
</style>
