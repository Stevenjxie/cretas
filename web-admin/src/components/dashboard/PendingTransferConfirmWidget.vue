<script setup lang="ts">
/**
 * 待确认入库调拨 widget — 客户 2026-07-30 反馈「调拨单有问题, 审核后 库存没有过来」。
 *
 * 同厂调拨审批通过后状态停在 APPROVED, 库存<b>并没有</b>过账; 必须再回单据点一次
 * 「确认调拨入库」, TransferServiceImpl#confirmTransfer 的 intraFactory 分支才会
 * 扣减调出仓、在调入仓建批次。客户把「已批准」理解成办完了, 实测 3 张单卡在 APPROVED,
 * 最早一张卡了 6 周 —— 期间调出仓货没少、调入仓货没到。
 *
 * 防呆 Rule 5 (dead-end 改导航): 首页主动把"还差一步"的单子摆出来 + 一键跳去确认。
 * 只对能处理它的人显示 (仓储角色 / 工厂超管), 且无待办时整块隐藏, 不打扰其他角色。
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';

interface TransferRow {
  id: string;
  transferNumber: string;
  status: string;
  sourceFactoryId?: string | null;
  targetFactoryId?: string | null;
  sourceWarehouseId?: string | null;
  targetWarehouseId?: string | null;
  transferDate?: string | null;
}

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const rows = ref<TransferRow[]>([]);
const loading = ref(false);
const loadError = ref('');
const warehouseNames = ref<Record<string, string>>({});

/** 仅对能执行「确认入库」的角色显示; 其他角色看到也没用, 徒增噪音。 */
const roleAllowed = computed(() =>
  authStore.hasRole(['factory_super_admin', 'warehouse_manager', 'warehouse_admin']));

/** 无待办时整块隐藏 (与 PendingApprovalsWidget 同策略)。 */
const visible = computed(() => roleAllowed.value && rows.value.length > 0);

function warehouseName(id?: string | null): string {
  if (!id) return '';
  return warehouseNames.value[id] || '';
}

async function loadWarehouses(): Promise<void> {
  try {
    const resp = await get<Array<{ id: string; name: string }>>(`/${factoryId.value}/warehouses`);
    if (resp.success && Array.isArray(resp.data)) {
      const map: Record<string, string> = {};
      for (const w of resp.data) map[w.id] = w.name;
      warehouseNames.value = map;
    }
  } catch {
    // 仓名只用于让提示更好懂, 拿不到就退化成不显示仓名, 不影响主功能。
  }
}

async function load(): Promise<void> {
  if (!factoryId.value || !roleAllowed.value) return;
  loading.value = true;
  loadError.value = '';
  try {
    const resp = await get<{ content?: TransferRow[] } | TransferRow[]>(
      `/${factoryId.value}/transfers`, { params: { status: 'APPROVED', page: 1, size: 50 } });
    if (resp.success && resp.data) {
      const list = Array.isArray(resp.data) ? resp.data : (resp.data.content || []);
      // 只留同厂调拨: 跨厂 APPROVED 的下一步是发运/签收, 不是确认入库, 混在一起会误导。
      rows.value = list.filter((r) => String(r.status) === 'APPROVED'
        && String(r.sourceFactoryId || '') === String(r.targetFactoryId || ''));
      if (rows.value.length > 0) await loadWarehouses();
    } else {
      loadError.value = resp.message || '加载待确认调拨失败';
    }
  } catch (e: unknown) {
    // 静默降级 — widget 不应阻断首页渲染。
    loadError.value = e instanceof Error ? e.message : '加载待确认调拨失败';
  } finally {
    loading.value = false;
  }
}

function goConfirm(row: TransferRow): void {
  router.push(`/transfer/${row.id}`);
}

onMounted(() => { load(); });
</script>

<template>
  <el-card v-if="visible" class="pending-transfer-widget" shadow="hover" v-loading="loading">
    <template #header>
      <div class="widget-header">
        <span class="title">
          待确认入库的调拨单
          <el-badge :value="rows.length" class="count-badge" type="warning" />
        </span>
        <el-button type="primary" link :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>

    <el-alert
      type="warning" show-icon :closable="false" style="margin-bottom: 12px"
      title="这些调拨单已审批通过，但库存还没有过账"
      description="需要点「去确认入库」完成最后一步，调出仓才会扣减、调入仓才会收到货。" />

    <el-alert v-if="loadError" :title="loadError" type="warning" show-icon :closable="false"
      style="margin-bottom: 12px" />

    <el-table :data="rows" stripe size="small">
      <el-table-column prop="transferNumber" label="调拨编号" min-width="170" />
      <el-table-column label="调出 → 调入" min-width="200">
        <template #default="{ row }">
          {{ warehouseName(row.sourceWarehouseId) || '调出仓' }} → {{ warehouseName(row.targetWarehouseId) || '调入仓' }}
        </template>
      </el-table-column>
      <el-table-column prop="transferDate" label="调拨日期" width="120" />
      <el-table-column label="操作" width="120" align="center">
        <template #default="{ row }">
          <el-button type="success" link size="small" @click="goConfirm(row)">去确认入库</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<style lang="scss" scoped>
.pending-transfer-widget { margin-bottom: 16px; }
.widget-header { display: flex; justify-content: space-between; align-items: center; }
.widget-header .title { font-weight: 600; display: inline-flex; align-items: center; gap: 8px; }
.count-badge { margin-left: 4px; }
</style>
