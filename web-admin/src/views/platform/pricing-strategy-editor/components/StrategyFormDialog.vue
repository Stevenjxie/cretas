<!--
  StrategyFormDialog.vue — Create / Edit 价格策略.

  5 种 type-specific form (per spec §1):
    - TIERED:    editable table (minQty / maxQty / discountPct rows)
    - PROMOTION: thresholdAmount + (discountAmount | discountPct | discountRate radio)
    - MEMBER:    membershipTier select + discountPct (single) 或 tierDiscounts JSON map
    - BUNDLE:    productIds 多选 + bundlePrice / discountPct / discountAmount radio
    - CYCLE:     cycleThreshold + rebateRate tiers
  共通字段: strategyCode, strategyName, priority, enabled, valid_from/to, scope_filter_json
-->
<template>
  <el-dialog
    :model-value="visible"
    :title="isEdit ? '编辑价格策略' : '新建价格策略'"
    width="720px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="120px" v-loading="saving">
      <!-- Common fields -->
      <el-form-item label="策略代码" prop="strategyCode">
        <el-input v-model="form.strategyCode" placeholder="如: DINGDONG_TIERED_2026" :disabled="isEdit" />
        <div class="hint">业务唯一键, 创建后不可修改</div>
      </el-form-item>

      <el-form-item label="策略名称" prop="strategyName">
        <el-input v-model="form.strategyName" placeholder="如: 叮咚批发阶梯折扣" />
      </el-form-item>

      <el-form-item label="策略类型" prop="strategyType">
        <el-select v-model="form.strategyType" :disabled="isEdit" style="width: 100%">
          <el-option label="TIERED — 阶梯定价" value="TIERED" />
          <el-option label="PROMOTION — 促销满减" value="PROMOTION" />
          <el-option label="MEMBER — 会员折扣" value="MEMBER" />
          <el-option label="BUNDLE — 套餐价" value="BUNDLE" />
          <el-option label="CYCLE — 跨周期返点" value="CYCLE" />
        </el-select>
      </el-form-item>

      <el-form-item label="优先级">
        <el-slider v-model="form.priority" :min="1" :max="200" show-input />
        <div class="hint">数字越小, 优先级越高 (default 100)</div>
      </el-form-item>

      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>

      <el-form-item label="有效期">
        <el-date-picker
          v-model="validityRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          style="width: 100%"
        />
        <div class="hint">空 = 不限</div>
      </el-form-item>

      <!-- Scope filter -->
      <el-divider content-position="left">适用范围 (scope filter)</el-divider>

      <el-form-item label="商品类目">
        <el-select
          v-model="scopeProductCategories"
          multiple
          filterable
          allow-create
          placeholder="留空 = 全部类目"
          style="width: 100%"
        >
          <el-option label="frozen 冻品" value="frozen" />
          <el-option label="deli 熟食" value="deli" />
          <el-option label="dry 干货" value="dry" />
        </el-select>
      </el-form-item>

      <el-form-item label="客户分组">
        <el-select
          v-model="scopeCustomerGroups"
          multiple
          filterable
          allow-create
          placeholder="留空 = 全部客户"
          style="width: 100%"
        >
          <el-option label="VIP" value="VIP" />
          <el-option label="IMPORTANT" value="IMPORTANT" />
          <el-option label="NORMAL" value="NORMAL" />
          <el-option label="LOW" value="LOW" />
        </el-select>
      </el-form-item>

      <el-form-item label="区域">
        <el-select
          v-model="scopeRegions"
          multiple
          filterable
          allow-create
          placeholder="留空 = 全部区域"
          style="width: 100%"
        >
          <el-option label="east 华东" value="east" />
          <el-option label="south 华南" value="south" />
          <el-option label="north 华北" value="north" />
        </el-select>
      </el-form-item>

      <!-- Type-specific rules -->
      <el-divider content-position="left">规则配置 ({{ form.strategyType }})</el-divider>

      <!-- TIERED form -->
      <template v-if="form.strategyType === 'TIERED'">
        <el-form-item label="阶梯档位">
          <div class="tiered-table">
            <el-table :data="tieredTiers" border size="small">
              <el-table-column label="最小数量" width="120">
                <template #default="{ row }">
                  <el-input-number v-model="row.minQty" :min="0" :step="10" size="small" controls-position="right" />
                </template>
              </el-table-column>
              <el-table-column label="最大数量 (含)" width="140">
                <template #default="{ row }">
                  <el-input-number v-model="row.maxQty" :min="0" :step="10" size="small" controls-position="right" placeholder="无上限" />
                </template>
              </el-table-column>
              <el-table-column label="折扣 %" width="120">
                <template #default="{ row }">
                  <el-input-number v-model="row.discountPct" :min="0" :max="100" :step="0.5" :precision="2" size="small" controls-position="right" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" @click="removeTier($index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button size="small" @click="addTier" style="margin-top: 8px">+ 增加档位</el-button>
          </div>
        </el-form-item>
      </template>

      <!-- PROMOTION form -->
      <template v-if="form.strategyType === 'PROMOTION'">
        <el-form-item label="满减门槛">
          <el-input-number v-model="promotionRules.thresholdAmount" :min="0" :step="100" controls-position="right" />
          <span class="suffix">元</span>
        </el-form-item>
        <el-form-item label="折扣方式">
          <el-radio-group v-model="promotionDiscountType">
            <el-radio value="amount">固定减额</el-radio>
            <el-radio value="pct">百分比 %</el-radio>
            <el-radio value="rate">折扣率 (0-1)</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="promotionDiscountType === 'amount'" label="减额">
          <el-input-number v-model="promotionRules.discountAmount" :min="0" :step="10" controls-position="right" />
          <span class="suffix">元</span>
        </el-form-item>
        <el-form-item v-else-if="promotionDiscountType === 'pct'" label="折扣百分比">
          <el-input-number v-model="promotionRules.discountPct" :min="0" :max="100" :step="0.5" :precision="2" controls-position="right" />
          <span class="suffix">%</span>
        </el-form-item>
        <el-form-item v-else label="折扣率">
          <el-input-number v-model="promotionRules.discountRate" :min="0" :max="1" :step="0.01" :precision="4" controls-position="right" />
          <span class="suffix">(0.95 = 95 折)</span>
        </el-form-item>
      </template>

      <!-- MEMBER form -->
      <template v-if="form.strategyType === 'MEMBER'">
        <el-form-item label="配置方式">
          <el-radio-group v-model="memberMode">
            <el-radio value="single">单等级</el-radio>
            <el-radio value="multi">多等级映射</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="memberMode === 'single'">
          <el-form-item label="会员等级">
            <el-select v-model="memberRules.membershipTier" placeholder="选择等级 (匹配 Customer.importance 4 枚举值)" style="width: 100%">
              <el-option label="VIP" value="VIP" />
              <el-option label="IMPORTANT" value="IMPORTANT" />
              <el-option label="NORMAL" value="NORMAL" />
              <el-option label="LOW" value="LOW" />
            </el-select>
          </el-form-item>
          <el-form-item label="折扣百分比">
            <el-input-number v-model="memberRules.discountPct" :min="0" :max="100" :step="0.5" :precision="2" controls-position="right" />
            <span class="suffix">%</span>
          </el-form-item>
          <el-form-item label="客户评级过滤">
            <el-input v-model="memberRules.customerRatingFilter" placeholder="如: A (可选, 客户 rating >= 此值才触发)" />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="等级折扣映射">
            <div class="tier-discounts">
              <div v-for="(d, tier) in memberTierMap" :key="tier" class="tier-row">
                <el-input :model-value="tier" disabled size="small" style="width: 120px" />
                <el-input-number :model-value="d" @change="(v: number | undefined) => updateMemberTier(tier as string, v)" :min="0" :max="100" :step="0.5" :precision="2" size="small" controls-position="right" />
                <span class="suffix">%</span>
                <el-button link type="danger" size="small" @click="removeMemberTier(tier as string)">删除</el-button>
              </div>
              <div class="tier-row">
                <el-input v-model="newTierName" placeholder="新等级名 (如 VIP)" size="small" style="width: 120px" />
                <el-input-number v-model="newTierPct" :min="0" :max="100" :step="0.5" :precision="2" size="small" controls-position="right" />
                <span class="suffix">%</span>
                <el-button size="small" type="primary" @click="addMemberTier">+ 添加</el-button>
              </div>
            </div>
          </el-form-item>
        </template>
      </template>

      <!-- BUNDLE form -->
      <template v-if="form.strategyType === 'BUNDLE'">
        <el-form-item label="商品列表">
          <div class="bundle-items">
            <div v-for="(item, idx) in bundleItems" :key="idx" class="bundle-row">
              <el-input v-model="item.productId" placeholder="商品 ID (如 P-001)" size="small" style="width: 200px" />
              <el-input-number v-model="item.qty" :min="1" :step="1" size="small" controls-position="right" />
              <span class="suffix">件</span>
              <el-button link type="danger" size="small" @click="removeBundleItem(idx)">删除</el-button>
            </div>
            <el-button size="small" @click="addBundleItem">+ 增加商品</el-button>
          </div>
        </el-form-item>
        <el-form-item label="折扣方式">
          <el-radio-group v-model="bundleDiscountType">
            <el-radio value="price">套餐统一价</el-radio>
            <el-radio value="pct">折扣百分比</el-radio>
            <el-radio value="amount">折扣金额</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="bundleDiscountType === 'price'" label="套餐价">
          <el-input-number v-model="bundleRules.bundlePrice" :min="0" :step="10" controls-position="right" />
          <span class="suffix">元</span>
        </el-form-item>
        <el-form-item v-else-if="bundleDiscountType === 'pct'" label="折扣 %">
          <el-input-number v-model="bundleRules.discountPct" :min="0" :max="100" :step="0.5" :precision="2" controls-position="right" />
          <span class="suffix">%</span>
        </el-form-item>
        <el-form-item v-else label="折扣金额">
          <el-input-number v-model="bundleRules.discountAmount" :min="0" :step="10" controls-position="right" />
          <span class="suffix">元</span>
        </el-form-item>
        <el-alert
          type="info"
          show-icon
          :closable="false"
          title="BUNDLE 多 line cart-aware 待 follow-up 实现"
          description="当前 Phase 4b skeleton 仅支持单 line 近似匹配 (productId 在 line 中匹配即触发). 真实多 line 联合识别需在 SalesService.createSalesOrder 处加 cart context. 见 follow-up issue."
          style="margin-top: 8px"
        />
      </template>

      <!-- CYCLE form -->
      <template v-if="form.strategyType === 'CYCLE'">
        <el-form-item label="结算周期">
          <el-select v-model="cycleRules.cycle" style="width: 100%">
            <el-option label="月度 MONTH" value="MONTH" />
            <el-option label="季度 QUARTER" value="QUARTER" />
            <el-option label="年度 YEAR" value="YEAR" />
          </el-select>
        </el-form-item>
        <el-form-item label="返点阶梯">
          <el-table :data="cycleRules.tiers" border size="small">
            <el-table-column label="累计金额 ≥" width="160">
              <template #default="{ row }">
                <el-input-number v-model="row.minAmount" :min="0" :step="1000" size="small" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column label="返点率 %" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.rebatePct" :min="0" :max="100" :step="0.5" :precision="2" size="small" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button link type="danger" size="small" @click="removeCycleTier($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button size="small" @click="addCycleTier" style="margin-top: 8px">+ 增加档位</el-button>
        </el-form-item>
        <el-alert
          type="warning"
          show-icon
          :closable="false"
          title="CYCLE 月底批处理待 follow-up 实现"
          description="跨周期返点不在 SO 建单时即时计算, 需 month-end batch job 累计客户购买额并生成 rebate credit note. 当前 SalesService.createSalesOrder 调用 pricingEngine.calculate 时, CYCLE 策略被 skip (返 SKIP_CYCLE_INLINE). 见 follow-up issue."
          style="margin-top: 8px"
        />
      </template>
    </el-form>

    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSubmit">
        {{ isEdit ? '保存' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, reactive } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance } from 'element-plus';
import {
  createStrategy,
  updateStrategy,
  type PricingStrategy,
  type PricingStrategyType,
  type TieredTier,
  type PromotionRules,
  type MemberRules,
  type BundleRules,
  type CycleRules,
  type StrategyRequest,
} from '@/api/pricingStrategyApi';

const props = defineProps<{
  visible: boolean;
  factoryId: string;
  editTarget: PricingStrategy | null;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'saved', s: PricingStrategy): void;
}>();

const isEdit = computed(() => !!props.editTarget);
const formRef = ref<FormInstance>();
const saving = ref(false);

const form = reactive({
  strategyCode: '',
  strategyName: '',
  strategyType: 'TIERED' as PricingStrategyType,
  priority: 100,
  enabled: true,
});

// Validity range as [from, to] array for el-date-picker
const validityRange = ref<[string, string] | null>(null);

// Scope filter
const scopeProductCategories = ref<string[]>([]);
const scopeCustomerGroups = ref<string[]>([]);
const scopeRegions = ref<string[]>([]);

// Type-specific rules state
const tieredTiers = ref<TieredTier[]>([{ minQty: 0, discountPct: 0 }]);

const promotionRules = reactive<PromotionRules>({
  thresholdAmount: 0,
  discountAmount: undefined,
  discountPct: undefined,
  discountRate: undefined,
});
const promotionDiscountType = ref<'amount' | 'pct' | 'rate'>('amount');

const memberRules = reactive<MemberRules>({
  membershipTier: '',
  discountPct: 0,
  customerRatingFilter: '',
});
const memberMode = ref<'single' | 'multi'>('single');
const memberTierMap = ref<Record<string, number>>({});
const newTierName = ref('');
const newTierPct = ref<number | undefined>(0);

const bundleRules = reactive<BundleRules>({
  bundlePrice: undefined,
  discountPct: undefined,
  discountAmount: undefined,
});
const bundleItems = ref<Array<{ productId: string; qty: number }>>([{ productId: '', qty: 1 }]);
const bundleDiscountType = ref<'price' | 'pct' | 'amount'>('price');

const cycleRules = reactive<CycleRules>({
  cycle: 'MONTH',
  tiers: [{ minAmount: 0, rebatePct: 0 }],
});

const rules = {
  strategyCode: [{ required: true, message: '请填写策略代码', trigger: 'blur' }],
  strategyType: [{ required: true, message: '请选择策略类型', trigger: 'change' }],
};

// Initialize form from editTarget
watch(
  () => props.editTarget,
  (target) => {
    if (target) {
      form.strategyCode = target.strategyCode;
      form.strategyName = target.strategyName || '';
      form.strategyType = target.strategyType;
      form.priority = target.priority;
      form.enabled = target.enabled;
      validityRange.value = target.validFrom && target.validTo
        ? [target.validFrom, target.validTo]
        : null;

      scopeProductCategories.value = target.scopeFilterJson?.productCategories || [];
      scopeCustomerGroups.value = target.scopeFilterJson?.customerGroups || [];
      scopeRegions.value = target.scopeFilterJson?.regions || [];

      const r = (target.rulesJson || {}) as Record<string, unknown>;
      switch (target.strategyType) {
        case 'TIERED':
          tieredTiers.value = Array.isArray(r.tiers) ? (r.tiers as TieredTier[]) : [{ minQty: 0, discountPct: 0 }];
          break;
        case 'PROMOTION':
          promotionRules.thresholdAmount = (r.thresholdAmount as number) ?? 0;
          promotionRules.discountAmount = r.discountAmount as number | undefined;
          promotionRules.discountPct = r.discountPct as number | undefined;
          promotionRules.discountRate = r.discountRate as number | undefined;
          if (promotionRules.discountAmount !== undefined) promotionDiscountType.value = 'amount';
          else if (promotionRules.discountPct !== undefined) promotionDiscountType.value = 'pct';
          else if (promotionRules.discountRate !== undefined) promotionDiscountType.value = 'rate';
          break;
        case 'MEMBER':
          if (r.tierDiscounts) {
            memberMode.value = 'multi';
            memberTierMap.value = { ...(r.tierDiscounts as Record<string, number>) };
          } else {
            memberMode.value = 'single';
            memberRules.membershipTier = (r.membershipTier as string) || '';
            memberRules.discountPct = (r.discountPct as number) ?? 0;
            memberRules.customerRatingFilter = (r.customerRatingFilter as string) || '';
          }
          break;
        case 'BUNDLE':
          bundleItems.value = Array.isArray(r.items)
            ? (r.items as Array<{ productId: string; qty: number }>)
            : [{ productId: '', qty: 1 }];
          bundleRules.bundlePrice = r.bundlePrice as number | undefined;
          bundleRules.discountPct = r.discountPct as number | undefined;
          bundleRules.discountAmount = r.discountAmount as number | undefined;
          if (bundleRules.bundlePrice !== undefined) bundleDiscountType.value = 'price';
          else if (bundleRules.discountPct !== undefined) bundleDiscountType.value = 'pct';
          else if (bundleRules.discountAmount !== undefined) bundleDiscountType.value = 'amount';
          break;
        case 'CYCLE':
          cycleRules.cycle = ((r.cycle as 'MONTH' | 'QUARTER' | 'YEAR') ?? 'MONTH');
          cycleRules.tiers = Array.isArray(r.tiers)
            ? (r.tiers as Array<{ minAmount: number; rebatePct: number }>)
            : [{ minAmount: 0, rebatePct: 0 }];
          break;
      }
    } else {
      resetForm();
    }
  },
  { immediate: true }
);

function resetForm() {
  form.strategyCode = '';
  form.strategyName = '';
  form.strategyType = 'TIERED';
  form.priority = 100;
  form.enabled = true;
  validityRange.value = null;
  scopeProductCategories.value = [];
  scopeCustomerGroups.value = [];
  scopeRegions.value = [];
  tieredTiers.value = [{ minQty: 0, discountPct: 0 }];
  promotionRules.thresholdAmount = 0;
  promotionRules.discountAmount = undefined;
  promotionRules.discountPct = undefined;
  promotionRules.discountRate = undefined;
  promotionDiscountType.value = 'amount';
  memberMode.value = 'single';
  memberRules.membershipTier = '';
  memberRules.discountPct = 0;
  memberRules.customerRatingFilter = '';
  memberTierMap.value = {};
  bundleRules.bundlePrice = undefined;
  bundleRules.discountPct = undefined;
  bundleRules.discountAmount = undefined;
  bundleItems.value = [{ productId: '', qty: 1 }];
  bundleDiscountType.value = 'price';
  cycleRules.cycle = 'MONTH';
  cycleRules.tiers = [{ minAmount: 0, rebatePct: 0 }];
}

// TIERED row ops
function addTier() {
  const last = tieredTiers.value[tieredTiers.value.length - 1];
  const nextMin = last && last.maxQty != null ? last.maxQty + 1 : (last?.minQty ?? 0) + 100;
  tieredTiers.value.push({ minQty: nextMin, discountPct: 0 });
}
function removeTier(idx: number) {
  tieredTiers.value.splice(idx, 1);
  if (tieredTiers.value.length === 0) tieredTiers.value.push({ minQty: 0, discountPct: 0 });
}

// MEMBER multi-tier
function addMemberTier() {
  if (!newTierName.value.trim() || newTierPct.value === undefined) {
    ElMessage.warning('请填写等级名和折扣百分比');
    return;
  }
  memberTierMap.value[newTierName.value.trim()] = newTierPct.value;
  newTierName.value = '';
  newTierPct.value = 0;
}
function updateMemberTier(tier: string, val: number | undefined) {
  if (val === undefined) return;
  memberTierMap.value[tier] = val;
}
function removeMemberTier(tier: string) {
  delete memberTierMap.value[tier];
}

// BUNDLE items
function addBundleItem() {
  bundleItems.value.push({ productId: '', qty: 1 });
}
function removeBundleItem(idx: number) {
  bundleItems.value.splice(idx, 1);
  if (bundleItems.value.length === 0) bundleItems.value.push({ productId: '', qty: 1 });
}

// CYCLE tiers
function addCycleTier() {
  const last = cycleRules.tiers[cycleRules.tiers.length - 1];
  const nextMin = last ? last.minAmount + 10000 : 0;
  cycleRules.tiers.push({ minAmount: nextMin, rebatePct: 0 });
}
function removeCycleTier(idx: number) {
  cycleRules.tiers.splice(idx, 1);
  if (cycleRules.tiers.length === 0) cycleRules.tiers.push({ minAmount: 0, rebatePct: 0 });
}

function buildRulesJson(): Record<string, unknown> {
  switch (form.strategyType) {
    case 'TIERED':
      return {
        tiers: tieredTiers.value.map(t => ({
          minQty: t.minQty,
          ...(t.maxQty != null ? { maxQty: t.maxQty } : {}),
          discountPct: t.discountPct,
        })),
      };
    case 'PROMOTION': {
      const out: Record<string, unknown> = { thresholdAmount: promotionRules.thresholdAmount };
      if (promotionDiscountType.value === 'amount' && promotionRules.discountAmount !== undefined) {
        out.discountAmount = promotionRules.discountAmount;
      } else if (promotionDiscountType.value === 'pct' && promotionRules.discountPct !== undefined) {
        out.discountPct = promotionRules.discountPct;
      } else if (promotionDiscountType.value === 'rate' && promotionRules.discountRate !== undefined) {
        out.discountRate = promotionRules.discountRate;
      }
      return out;
    }
    case 'MEMBER': {
      if (memberMode.value === 'multi') {
        return { tierDiscounts: { ...memberTierMap.value } };
      }
      const out: Record<string, unknown> = {};
      if (memberRules.membershipTier) out.membershipTier = memberRules.membershipTier;
      if (memberRules.discountPct !== undefined) out.discountPct = memberRules.discountPct;
      if (memberRules.customerRatingFilter) out.customerRatingFilter = memberRules.customerRatingFilter;
      return out;
    }
    case 'BUNDLE': {
      const out: Record<string, unknown> = {
        items: bundleItems.value.filter(i => i.productId.trim()).map(i => ({ productId: i.productId.trim(), qty: i.qty })),
      };
      if (bundleDiscountType.value === 'price' && bundleRules.bundlePrice !== undefined) {
        out.bundlePrice = bundleRules.bundlePrice;
      } else if (bundleDiscountType.value === 'pct' && bundleRules.discountPct !== undefined) {
        out.discountPct = bundleRules.discountPct;
      } else if (bundleDiscountType.value === 'amount' && bundleRules.discountAmount !== undefined) {
        out.discountAmount = bundleRules.discountAmount;
      }
      return out;
    }
    case 'CYCLE':
      return {
        cycle: cycleRules.cycle,
        tiers: cycleRules.tiers.map(t => ({ minAmount: t.minAmount, rebatePct: t.rebatePct })),
      };
    default:
      return {};
  }
}

function buildScopeFilter(): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  if (scopeProductCategories.value.length) out.productCategories = scopeProductCategories.value;
  if (scopeCustomerGroups.value.length) out.customerGroups = scopeCustomerGroups.value;
  if (scopeRegions.value.length) out.regions = scopeRegions.value;
  return out;
}

// F4 (PR #40 review I3, score 80): client-side validation for TIERED / BUNDLE / MEMBER multi-tier
// before submit. Server-side already validates; this catches obvious mistakes early w/ Chinese msg.
function validateTieredTiers(): string | null {
  if (form.strategyType !== 'TIERED') return null;
  if (!tieredTiers.value || tieredTiers.value.length === 0) {
    return '请至少添加 1 个阶梯';
  }
  for (let i = 0; i < tieredTiers.value.length; i++) {
    const tier = tieredTiers.value[i];
    if (tier.minQty == null || tier.minQty < 0) {
      return `阶梯 ${i + 1}: minQty 不可为负数`;
    }
    if (tier.maxQty != null && tier.minQty > tier.maxQty) {
      return `阶梯 ${i + 1}: minQty (${tier.minQty}) 必须 ≤ maxQty (${tier.maxQty})`;
    }
    if (tier.discountPct == null || tier.discountPct < 0 || tier.discountPct > 100) {
      return `阶梯 ${i + 1}: discountPct 必须 0-100`;
    }
  }
  // Overlap check (sort by minQty + verify no overlap)
  const sorted = [...tieredTiers.value].sort((a, b) => a.minQty - b.minQty);
  for (let i = 1; i < sorted.length; i++) {
    const prev = sorted[i - 1];
    const curr = sorted[i];
    if (prev.maxQty != null && curr.minQty <= prev.maxQty) {
      return `阶梯重叠: [${prev.minQty}-${prev.maxQty}] 跟 [${curr.minQty}-${curr.maxQty ?? '∞'}] 区间冲突`;
    }
  }
  return null;
}

function validateBundle(): string | null {
  if (form.strategyType !== 'BUNDLE') return null;
  const validItems = bundleItems.value.filter(i => i.productId && i.productId.trim());
  if (validItems.length === 0) {
    return '请至少添加 1 个商品 (productId 不可为空)';
  }
  return null;
}

function validateMemberMulti(): string | null {
  if (form.strategyType !== 'MEMBER') return null;
  if (memberMode.value !== 'multi') return null;
  if (Object.keys(memberTierMap.value).length === 0) {
    return '多等级映射模式: 请至少添加 1 个等级→折扣映射';
  }
  return null;
}

async function onSubmit() {
  if (!formRef.value) return;

  // F4: pre-validate type-specific rules before el-form rules trigger
  const tieredErr = validateTieredTiers();
  if (tieredErr) {
    ElMessage.error(tieredErr);
    return;
  }
  const bundleErr = validateBundle();
  if (bundleErr) {
    ElMessage.error(bundleErr);
    return;
  }
  const memberErr = validateMemberMulti();
  if (memberErr) {
    ElMessage.error(memberErr);
    return;
  }
  // CYCLE skip (per spec §10 Q2, batch path TBD).

  try {
    await formRef.value.validate();
  } catch {
    return;
  }

  const req: StrategyRequest = {
    strategyCode: form.strategyCode.trim(),
    strategyName: form.strategyName.trim() || undefined,
    strategyType: form.strategyType,
    priority: form.priority,
    enabled: form.enabled,
    validFrom: validityRange.value?.[0] || null,
    validTo: validityRange.value?.[1] || null,
    scopeFilterJson: buildScopeFilter(),
    rulesJson: buildRulesJson(),
  };

  saving.value = true;
  try {
    let saved: PricingStrategy;
    if (props.editTarget) {
      saved = await updateStrategy(props.factoryId, props.editTarget.id, req);
      ElMessage.success('策略更新成功');
    } else {
      saved = await createStrategy(props.factoryId, req);
      ElMessage.success('策略创建成功');
    }
    emit('saved', saved);
  } catch (e) {
    // axios interceptor showed error toast
    console.error('Save strategy failed:', e);
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.suffix {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.tiered-table {
  width: 100%;
}
.tier-discounts {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.tier-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bundle-items {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.bundle-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
