<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import { post } from '@/api/request'
import { getRestockBoard, type RestockRow } from '@/api/restockBoard'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Warning } from '@element-plus/icons-vue'

const authStore = useAuthStore()
const factoryId = computed(() => authStore.factoryId)

const loading = ref(false)
const deliveryDate = ref(new Date().toISOString().slice(0, 10))
const rows = ref<RestockRow[]>([])
const summary = ref({ totalProducts: 0, shortfallProducts: 0, fullySatisfiedProducts: 0 })

async function load() {
  if (!factoryId.value) return
  loading.value = true
  try {
    const res = await getRestockBoard(factoryId.value, deliveryDate.value)
    if (res.success && res.data) {
      rows.value = res.data.rows
      summary.value = res.data.summary
    } else if (res.success === false) {
      ElMessage({ message: res.message || '加载失败', type: 'error', duration: 0, showClose: true })
    }
  } catch {
    // errors already shown by request interceptor
  } finally {
    loading.value = false
  }
}

function statusTag(s: string): { type: 'success' | 'warning' | 'info'; text: string } {
  if (s === 'SATISFIED') return { type: 'success', text: '满足' }
  if (s === 'SHORTFALL') return { type: 'warning', text: '补产' }
  return { type: 'info', text: '单位不一致' }
}

async function createPlan(row: RestockRow) {
  if (!row.shortfallQty || row.shortfallQty <= 0) return
  // 计划日期不能早于今天 (后端 @FutureOrPresent 约束): 查历史交货日缺口时, 计划日取今天
  const today = new Date().toISOString().slice(0, 10)
  const plannedDate = deliveryDate.value >= today ? deliveryDate.value : today
  try {
    await ElMessageBox.confirm(
      `产品: ${row.productName}\n建议补产: ${row.shortfallQty} ${row.unit}\n交期: ${deliveryDate.value}\n计划生产日: ${plannedDate}\n是否生成生产计划草稿?`,
      '缺口转生产计划草稿',
      { confirmButtonText: '生成草稿', cancelButtonText: '取消', type: 'warning' }
    )
    const res = await post(`/${factoryId.value}/production-plans`, {
      sourceType: 'MANUAL',
      productTypeId: row.productTypeId,
      plannedQuantity: row.shortfallQty,
      plannedDate,
      notes: `来自 ${deliveryDate.value} 备货看板缺口`,
    })
    if (res.success) {
      ElMessage.success('生产计划草稿已生成')
      load()
    } else {
      ElMessage({ message: res.message || '生成失败', type: 'error', duration: 0, showClose: true })
    }
  } catch {
    // 用户取消或拦截器已处理
  }
}

onMounted(load)
</script>

<template>
  <div style="padding: 12px">
    <el-card style="margin-bottom: 12px">
      <el-space wrap>
        <span>交货日</span>
        <el-date-picker
          v-model="deliveryDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择交货日"
        />
        <el-button type="primary" @click="load">查询</el-button>
        <el-tag>共 {{ summary.totalProducts }} 品</el-tag>
        <el-tag type="warning">缺口 {{ summary.shortfallProducts }}</el-tag>
        <el-tag type="success">满足 {{ summary.fullySatisfiedProducts }}</el-tag>
      </el-space>
    </el-card>

    <el-card>
      <el-table :data="rows" v-loading="loading" stripe empty-text="该日无订单">
        <el-table-column prop="productName" label="产品" min-width="180" />
        <el-table-column prop="demandQty" label="需求(盒)" width="100">
          <template #default="{ row }">
            <span v-if="row.demandQty !== null">{{ row.demandQty }}</span>
            <span v-else style="color: #e6a23c">—</span>
          </template>
        </el-table-column>
        <el-table-column label="成品可用" width="110">
          <template #default="{ row }">
            {{ row.fgAvailableQty }}
            <el-tooltip
              v-if="row.fgAvailableQty > 0"
              content="未预留成品, 多日订单请人工分配"
              placement="top"
            >
              <el-icon style="color: #e6a23c; vertical-align: middle"><Warning /></el-icon>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="在产(估)" width="100">
          <template #default="{ row }">
            <span v-if="row.wipEstimatedQty !== null">
              {{ row.wipEstimatedQty }}&nbsp;<el-tag size="small" type="info">估</el-tag>
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="scheduledQty" label="已排产" width="90" />
        <el-table-column label="缺口" width="90">
          <template #default="{ row }">
            <span
              v-if="row.shortfallQty !== null"
              :style="{ color: row.shortfallQty > 0 ? '#f56c6c' : '#67c23a', fontWeight: 'bold' }"
            >
              {{ row.shortfallQty }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status).type">{{ statusTag(row.status).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.shortfallQty && row.shortfallQty > 0"
              type="primary"
              text
              @click="createPlan(row)"
            >
              建计划
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="提示" min-width="160">
          <template #default="{ row }">
            <span v-if="row.conversionWarning" style="color: #e6a23c; font-size: 12px">
              {{ row.conversionWarning }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
