<!--
  AdditiveLimitsList — Sub-tab 3 / 8.

  GB 2760-2014 食品添加剂限量库, 系统级 seed 数据 (无 factory_id).
  只读 — factory 不可修改国标. Skill `food-additive-compliance` 主要查询点.
-->
<template>
  <div class="additive-limits-list">
    <el-alert
      title="GB 2760-2014 食品添加剂使用标准 (只读)"
      type="warning"
      :closable="false"
      show-icon
      class="info-banner"
    >
      此为食品安全国家标准强制规定, 由系统统一维护. 超出最大限量即违法.
      <strong>无法新增或修改条目</strong> — 国标更新由系统种子升级.
    </el-alert>

    <div class="toolbar">
      <el-select
        v-model="foodCategory"
        placeholder="按食品类目过滤"
        clearable
        style="width: 280px;"
        @change="reload"
      >
        <el-option label="全部" value="" />
        <el-option label="08.02 熟肉制品" value="08.02 熟肉制品" />
        <el-option label="07.0 焙烤食品" value="07.0 焙烤食品" />
        <el-option label="14.0 饮料类" value="14.0 饮料类" />
      </el-select>
      <el-input
        v-model="search"
        placeholder="搜索添加剂名称 / INS 代码"
        clearable
        style="width: 240px; margin-left: 12px;"
      />
      <el-text type="info" size="small" style="margin-left: 12px;">
        共 {{ filteredRows.length }} 条 / 全库 {{ rows.length }} 条
      </el-text>
    </div>

    <el-table
      v-loading="loading"
      :data="filteredRows"
      class="table"
      empty-text="暂无添加剂限量数据 (请重新加载)"
      stripe
    >
      <el-table-column prop="additiveName" label="添加剂名称" min-width="160" show-overflow-tooltip />
      <el-table-column prop="additiveCode" label="INS 代码" width="120" />
      <el-table-column prop="foodCategory" label="食品类目" min-width="180" show-overflow-tooltip />
      <el-table-column label="最大限量" width="160">
        <template #default="{ row }">
          {{ row.maxLimit }} {{ row.unit }}
        </template>
      </el-table-column>
      <el-table-column prop="regulationRef" label="法规引用" width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.active" type="success" size="small">现行</el-tag>
          <el-tag v-else type="info" size="small">已废止</el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { listAdditiveLimits, type AdditiveLimit } from '@/api/foodSafetyHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const rows = ref<AdditiveLimit[]>([])
const foodCategory = ref('')
const search = ref('')

const filteredRows = computed(() => {
  if (!search.value.trim()) return rows.value
  const kw = search.value.toLowerCase()
  return rows.value.filter(
    (r) =>
      (r.additiveName || '').toLowerCase().includes(kw) ||
      (r.additiveCode || '').toLowerCase().includes(kw),
  )
})

async function reload() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listAdditiveLimits(props.factoryId, foodCategory.value || undefined)
    rows.value = res.success && res.data ? res.data : []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void reload()
})
</script>

<style scoped>
.additive-limits-list {
  padding: 0;
}
.info-banner {
  margin-bottom: 12px;
}
.toolbar {
  margin-bottom: 12px;
  display: flex;
  align-items: center;
}
.table {
  margin-top: 8px;
}
</style>
