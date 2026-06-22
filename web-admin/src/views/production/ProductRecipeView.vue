<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  listRecipes, createRecipe, updateRecipe, deleteRecipe,
  type ProductRecipe, type RecipeIngredient, type SaveRecipePayload,
} from '@/api/productRecipe';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Delete } from '@element-plus/icons-vue';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId as string);

const loading = ref(false);
const rows = ref<ProductRecipe[]>([]);
const dialogVisible = ref(false);
const editingId = ref<string | null>(null);
const form = ref<SaveRecipePayload>(blankForm());

function blankForm(): SaveRecipePayload {
  return {
    productTypeId: '', name: '', injectionRate: null,
    cookingPotBaseKg: null, subsequentPotRatio: 0.3333, ingredients: [],
  };
}
function addIngredient(section: 'INJECTION' | 'COOKING') {
  form.value.ingredients.push({
    section, name: '', dosagePerKgG: null,
    priceSource1: null, priceSource2: null, countInSeasoning: true, remark: '',
  });
}
function removeIngredient(i: RecipeIngredient) {
  form.value.ingredients = form.value.ingredients.filter((x) => x !== i);
}
const injectionRows = computed(() => form.value.ingredients.filter((i) => i.section === 'INJECTION'));
const cookingRows = computed(() => form.value.ingredients.filter((i) => i.section === 'COOKING'));

async function load() {
  loading.value = true;
  try {
    const resp = await listRecipes(factoryId.value);
    rows.value = resp.data || [];
  } catch (e: any) {
    ElMessage({ message: e.message || '加载失败', type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}
function openCreate() { editingId.value = null; form.value = blankForm(); dialogVisible.value = true; }
function openEdit(row: ProductRecipe) {
  editingId.value = row.id;
  form.value = {
    productTypeId: row.productTypeId, name: row.name, injectionRate: row.injectionRate ?? null,
    cookingPotBaseKg: row.cookingPotBaseKg ?? null, subsequentPotRatio: row.subsequentPotRatio ?? 0.3333,
    ingredients: row.ingredients.map((i) => ({ ...i })),
  };
  dialogVisible.value = true;
}
async function save() {
  if (!form.value.productTypeId) { ElMessage.warning('请选择产品 SKU'); return; }
  if (!form.value.name) { ElMessage.warning('请填配方名'); return; }
  if (form.value.ingredients.length === 0) { ElMessage.warning('至少加一条料'); return; }
  try {
    if (editingId.value) await updateRecipe(factoryId.value, editingId.value, form.value);
    else await createRecipe(factoryId.value, form.value);
    ElMessage.success('保存成功');
    dialogVisible.value = false;
    await load();
  } catch (e: any) {
    ElMessage({ message: e.message || '保存失败', type: 'error', duration: 0, showClose: true });
  }
}
function onDelete(row: ProductRecipe) {
  ElMessageBox.confirm(`停用配方「${row.name}」？`, '警告', { type: 'warning' }).then(async () => {
    try {
      await deleteRecipe(factoryId.value, row.id);
      ElMessage.success('已停用');
      await load();
    } catch (e: any) {
      ElMessage({ message: e.message || '停用失败', type: 'error', duration: 0, showClose: true });
    }
  }).catch(() => {});
}
onMounted(load);
</script>

<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center;">
        <span>配方维护 (注射 + 熟制, 1 SKU 1 配方)</span>
        <div>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建配方</el-button>
          <el-button :icon="Refresh" @click="load" />
        </div>
      </div>
    </template>

    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="name" label="配方名" />
      <el-table-column prop="productTypeId" label="产品 SKU" />
      <el-table-column label="每kg原料(第一锅)" >
        <template #default="{ row }">¥{{ row.costPerKgFirstPot?.toFixed(2) ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="每kg原料(第二锅起)">
        <template #default="{ row }">¥{{ row.costPerKgSubsequentPot?.toFixed(2) ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" :icon="Delete" @click="onDelete(row)">停用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑配方' : '新建配方'" width="900px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="产品 SKU"><el-input v-model="form.productTypeId" placeholder="product_type_id" /></el-form-item>
        <el-form-item label="配方名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="注射率"><el-input-number v-model="form.injectionRate" :precision="4" :step="0.01" /></el-form-item>
        <el-form-item label="每锅基准原料kg"><el-input-number v-model="form.cookingPotBaseKg" :precision="3" /></el-form-item>
        <el-form-item label="第二锅起比例"><el-input-number v-model="form.subsequentPotRatio" :precision="4" :min="0.0001" :max="1" /></el-form-item>
      </el-form>

      <el-divider>注射配方</el-divider>
      <el-button size="small" @click="addIngredient('INJECTION')">+ 注射料</el-button>
      <el-table :data="injectionRows" size="small">
        <el-table-column label="料名"><template #default="{ row }"><el-input v-model="row.name" /></template></el-table-column>
        <el-table-column label="每kg用量g"><template #default="{ row }"><el-input-number v-model="row.dosagePerKgG" :precision="4" /></template></el-table-column>
        <el-table-column label="单价1"><template #default="{ row }"><el-input-number v-model="row.priceSource1" :precision="4" /></template></el-table-column>
        <el-table-column label="单价2"><template #default="{ row }"><el-input-number v-model="row.priceSource2" :precision="4" /></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" @click="removeIngredient(row)">删</el-button></template></el-table-column>
      </el-table>

      <el-divider>熟制配方 (老汤勾去「计入调料」)</el-divider>
      <el-button size="small" @click="addIngredient('COOKING')">+ 熟制料</el-button>
      <el-table :data="cookingRows" size="small">
        <el-table-column label="料名"><template #default="{ row }"><el-input v-model="row.name" /></template></el-table-column>
        <el-table-column label="每kg用量g"><template #default="{ row }"><el-input-number v-model="row.dosagePerKgG" :precision="4" /></template></el-table-column>
        <el-table-column label="单价1"><template #default="{ row }"><el-input-number v-model="row.priceSource1" :precision="4" /></template></el-table-column>
        <el-table-column label="单价2"><template #default="{ row }"><el-input-number v-model="row.priceSource2" :precision="4" /></template></el-table-column>
        <el-table-column label="计入调料" width="90"><template #default="{ row }"><el-switch v-model="row.countInSeasoning" /></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" @click="removeIngredient(row)">删</el-button></template></el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
