<!--
  StrategiesList.vue — 5 种策略分组展示, 优先级排序.

  Per spec §5: TIERED / PROMOTION / MEMBER / BUNDLE / CYCLE 分组卡片.
  显示 priority, valid_from/to 进度条, enabled toggle, 操作按钮.
-->
<template>
  <div class="strategies-list">
    <div class="filter-bar">
      <el-input
        v-model="searchKeyword"
        placeholder="搜索策略代码 / 名称"
        clearable
        size="default"
        style="width: 240px"
      />
      <el-select v-model="filterType" placeholder="按类型筛选" clearable style="width: 160px">
        <el-option label="TIERED 阶梯" value="TIERED" />
        <el-option label="PROMOTION 促销" value="PROMOTION" />
        <el-option label="MEMBER 会员" value="MEMBER" />
        <el-option label="BUNDLE 套餐" value="BUNDLE" />
        <el-option label="CYCLE 跨周期" value="CYCLE" />
      </el-select>
      <el-select v-model="filterEnabled" placeholder="按启用状态" clearable style="width: 140px">
        <el-option label="启用中" :value="true" />
        <el-option label="已禁用" :value="false" />
      </el-select>
      <el-button @click="emit('reload')" :loading="loading" size="default">刷新</el-button>
    </div>

    <div v-if="loading" class="loading">
      <el-skeleton :rows="4" animated />
    </div>

    <el-empty v-else-if="grouped.length === 0" description="暂无策略, 点击右上角新建" />

    <div v-else class="groups">
      <div v-for="group in grouped" :key="group.type" class="group">
        <div class="group-header">
          <span class="group-icon">{{ typeIcon(group.type) }}</span>
          <span class="group-label">{{ typeLabel(group.type) }}</span>
          <span class="group-count">({{ group.items.length }})</span>
        </div>

        <div class="cards">
          <el-card
            v-for="s in group.items"
            :key="s.id"
            shadow="hover"
            class="strategy-card"
            :class="{ 'is-disabled': !s.enabled }"
          >
            <div class="card-top">
              <div class="card-title">
                <span class="strategy-code">{{ s.strategyCode }}</span>
                <span v-if="s.strategyName" class="strategy-name">— {{ s.strategyName }}</span>
              </div>
              <el-switch
                v-model="s.enabled"
                :before-change="() => toggleConfirm(s)"
                size="small"
              />
            </div>

            <div class="card-meta">
              <el-tag size="small" effect="plain">优先级 {{ s.priority }}</el-tag>
              <el-tag v-if="s.scopeFilterJson?.productCategories?.length" size="small" type="info">
                类目: {{ s.scopeFilterJson.productCategories.join(', ') }}
              </el-tag>
              <el-tag v-if="s.scopeFilterJson?.customerGroups?.length" size="small" type="info">
                客户: {{ s.scopeFilterJson.customerGroups.join(', ') }}
              </el-tag>
              <el-tag v-if="s.scopeFilterJson?.regions?.length" size="small" type="info">
                区域: {{ s.scopeFilterJson.regions.join(', ') }}
              </el-tag>
            </div>

            <div class="card-rules">
              <code>{{ formatRules(s) }}</code>
            </div>

            <div class="card-validity">
              <span class="validity-label">有效期:</span>
              <span class="validity-range">
                {{ s.validFrom || '不限' }} → {{ s.validTo || '不限' }}
              </span>
              <el-progress
                v-if="s.validFrom && s.validTo"
                :percentage="validityProgress(s.validFrom, s.validTo)"
                :status="validityStatus(s.validFrom, s.validTo)"
                :stroke-width="6"
                style="flex:1; margin-left:8px"
              />
            </div>

            <div class="card-actions">
              <el-button link size="small" type="primary" @click="emit('edit', s)">
                编辑
              </el-button>
              <el-button link size="small" type="danger" @click="emit('delete', s)">
                删除
              </el-button>
            </div>
          </el-card>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { ElMessageBox } from 'element-plus';
import type {
  PricingStrategy,
  PricingStrategyType,
  TieredTier,
} from '@/api/pricingStrategyApi';

const props = defineProps<{
  factoryId: string;
  strategies: PricingStrategy[];
  loading: boolean;
}>();

const emit = defineEmits<{
  (e: 'edit', strategy: PricingStrategy): void;
  (e: 'toggle', strategy: PricingStrategy): void;
  (e: 'delete', strategy: PricingStrategy): void;
  (e: 'reload'): void;
}>();

const searchKeyword = ref('');
const filterType = ref<PricingStrategyType | ''>('');
const filterEnabled = ref<boolean | null>(null);

const filtered = computed(() => {
  return props.strategies.filter(s => {
    if (filterType.value && s.strategyType !== filterType.value) return false;
    if (filterEnabled.value !== null && s.enabled !== filterEnabled.value) return false;
    if (searchKeyword.value) {
      const kw = searchKeyword.value.toLowerCase();
      const hit = s.strategyCode.toLowerCase().includes(kw)
        || (s.strategyName?.toLowerCase().includes(kw) ?? false);
      if (!hit) return false;
    }
    return true;
  });
});

const grouped = computed(() => {
  const groups: Record<string, PricingStrategy[]> = {};
  for (const s of filtered.value) {
    if (!groups[s.strategyType]) groups[s.strategyType] = [];
    groups[s.strategyType].push(s);
  }
  // sort within group by priority ASC
  for (const type in groups) {
    groups[type].sort((a, b) => a.priority - b.priority);
  }
  const order: PricingStrategyType[] = ['TIERED', 'PROMOTION', 'MEMBER', 'BUNDLE', 'CYCLE'];
  return order
    .filter(t => groups[t]?.length)
    .map(type => ({ type, items: groups[type] }));
});

function typeLabel(type: PricingStrategyType): string {
  const map: Record<PricingStrategyType, string> = {
    TIERED: '阶梯定价',
    PROMOTION: '促销满减',
    MEMBER: '会员折扣',
    BUNDLE: '套餐价',
    CYCLE: '跨周期返点',
  };
  return map[type] || type;
}

function typeIcon(type: PricingStrategyType): string {
  const map: Record<PricingStrategyType, string> = {
    TIERED: '📊',
    PROMOTION: '🎉',
    MEMBER: '💎',
    BUNDLE: '📦',
    CYCLE: '🔁',
  };
  return map[type] || 'ⓘ';
}

function formatRules(s: PricingStrategy): string {
  const r = s.rulesJson;
  if (!r) return '(规则未配置)';
  try {
    switch (s.strategyType) {
      case 'TIERED': {
        const tiers = (r as { tiers?: TieredTier[] }).tiers;
        if (Array.isArray(tiers) && tiers.length) {
          return tiers
            .map(t => `${t.minQty}${t.maxQty ? `-${t.maxQty}` : '+'} → ${t.discountPct}%`)
            .join(', ');
        }
        return JSON.stringify(r);
      }
      case 'PROMOTION': {
        const p = r as { thresholdAmount?: number; discountAmount?: number; discountPct?: number; discountRate?: number };
        if (p.discountAmount) return `满 ¥${p.thresholdAmount} 减 ¥${p.discountAmount}`;
        if (p.discountPct) return `满 ¥${p.thresholdAmount} 享 ${p.discountPct}% off`;
        if (p.discountRate) return `满 ¥${p.thresholdAmount} 享 ${(p.discountRate * 100).toFixed(1)}% off`;
        return JSON.stringify(r);
      }
      case 'MEMBER': {
        const m = r as { membershipTier?: string; discountPct?: number; tierDiscounts?: Record<string, number> };
        if (m.tierDiscounts) {
          return Object.entries(m.tierDiscounts).map(([k, v]) => `${k}: ${v}%`).join(', ');
        }
        if (m.membershipTier) return `${m.membershipTier} → ${m.discountPct}%`;
        return JSON.stringify(r);
      }
      case 'BUNDLE': {
        const b = r as { items?: Array<{ productId: string; qty: number }>; bundlePrice?: number; discountPct?: number };
        if (b.items?.length) {
          const desc = b.items.map(i => `${i.productId}×${i.qty}`).join(' + ');
          return b.bundlePrice ? `${desc} = ¥${b.bundlePrice}` : `${desc} (${b.discountPct}% off)`;
        }
        return JSON.stringify(r);
      }
      case 'CYCLE': {
        const c = r as { cycle?: string; tiers?: Array<{ minAmount: number; rebatePct: number }> };
        if (c.tiers?.length) {
          return `${c.cycle || 'MONTH'}: ${c.tiers.map(t => `≥¥${t.minAmount}→${t.rebatePct}%`).join(', ')}`;
        }
        return JSON.stringify(r);
      }
      default:
        return JSON.stringify(r);
    }
  } catch {
    return JSON.stringify(r);
  }
}

function validityProgress(from: string, to: string): number {
  const start = new Date(from).getTime();
  const end = new Date(to).getTime();
  const now = Date.now();
  if (now < start) return 0;
  if (now > end) return 100;
  return Math.round(((now - start) / (end - start)) * 100);
}

function validityStatus(from: string, to: string): 'success' | 'warning' | 'exception' {
  const now = Date.now();
  const end = new Date(to).getTime();
  const remainDays = (end - now) / (1000 * 60 * 60 * 24);
  if (remainDays < 0) return 'exception';
  if (remainDays < 7) return 'warning';
  return 'success';
}

async function toggleConfirm(s: PricingStrategy): Promise<boolean> {
  try {
    await ElMessageBox.confirm(
      `${s.enabled ? '禁用' : '启用'}策略 "${s.strategyName || s.strategyCode}"?`,
      '操作确认',
      { type: 'warning' }
    );
    emit('toggle', s);
    return true;
  } catch {
    return false; // user cancelled, switch reverts
  }
}
</script>

<style scoped>
.strategies-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.filter-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.loading {
  padding: 16px;
}
.groups {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.group-icon { font-size: 18px; }
.group-count { color: var(--el-text-color-secondary); font-weight: 400; }
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 12px;
}
.strategy-card { transition: opacity 0.2s; }
.strategy-card.is-disabled { opacity: 0.55; }
.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.strategy-name { color: var(--el-text-color-regular); font-weight: 400; margin-left: 4px; }
.card-meta {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.card-rules {
  background: var(--el-fill-color-light);
  padding: 8px 10px;
  border-radius: 4px;
  margin-bottom: 8px;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-all;
}
.card-rules code {
  font-family: 'Courier New', monospace;
  color: var(--el-color-primary);
}
.card-validity {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.validity-label { font-weight: 500; }
.validity-range { color: var(--el-text-color-regular); }
.card-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 6px;
}
</style>
