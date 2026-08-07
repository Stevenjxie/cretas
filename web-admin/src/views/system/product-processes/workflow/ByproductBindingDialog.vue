<template>
  <el-dialog
    :model-value="modelValue"
    :title="row ? '编辑副产' : '声明副产'"
    width="520px"
    append-to-body
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-form label-width="132px" label-position="right">
      <el-form-item label="产出">
        <el-input :model-value="outputName" disabled />
        <div class="hint">副产归属该产出，保存只影响这一份配方。</div>
      </el-form-item>

      <!--
        ⛔ 空下拉必须自己解释 (fool-proof-design Rule 5: 死胡同要么变导航要么变解释)。
        副产物料是按物料档案上的**副产标记**筛的, 不是按材质分类 —— 2026-08-06 实测
        六膳门 128 个在用物料里被勾选「副产」的是 0 个, 所以这个空态是客户当下的真实
        状态, 不是边缘情况。只给一个空下拉, 用户会以为是系统坏了或者数据没加载完。
      -->
      <el-form-item v-if="materials.length === 0" label="副产物料">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="物料档案里还没有勾选「副产」的物料"
          data-testid="byp-empty-hint"
        >
          <!--
            ⛔ 2026-08-06 实测: 六膳门 128 个在用物料里, 勾了「副产」的是 0 个, 而
            像副产的候选(猪大骨/膝软骨/碎肉/去皮五花肉)**全是采购来的主材**——
            鸡架、骨头这类生产产出物的 SKU **根本还没建**。
            所以文案不能只说「去勾开关」: 用户去了会发现一个可勾的都没有, 白跑一趟。
            先说「新建」, 再说「已有就勾」。
          -->
          <div class="hint">
            副产物料按物料档案上的<strong>副产标记</strong>筛选（与材质分类无关）。
            请先到「仓储管理 → 原料类型字典」<strong>新建</strong>鸡架、骨头、下脚料这类
            <strong>生产产出、没有采购来源</strong>的物料，并打开<strong>副产</strong>开关；
            若该物料已在档案里，编辑它打开开关即可。完成后回到这里声明。
          </div>
        </el-alert>
      </el-form-item>

      <!-- 已有行仍要能看见/改它, 哪怕档案侧的标记被人取消了 —— 别把既有数据藏起来。 -->
      <el-form-item v-if="materials.length > 0 || row" label="副产物料" required>
        <el-select
          v-model="form.materialTypeId"
          filterable
          clearable
          placeholder="从物料档案选择…"
          style="width: 100%"
          data-testid="byp-material-select"
        >
          <el-option
            v-for="item in materials"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item :label="`每 1 ${baseUnit}成品产出`" required>
        <el-input-number
          v-model="form.quantity"
          :min="0"
          :step="0.01"
          :precision="4"
          controls-position="right"
          style="width: 200px"
          data-testid="byp-quantity"
        />
        <span class="unit">{{ selectedUnit || '—' }}</span>
        <!--
          副产是产出不是投入: 这里填的是「产出多少」。单位同样只从物料档案继承,
          BOM 不提供切换 —— 与包材一致, 避免同一物料在两处出现两种单位口径。
        -->
        <div class="hint">单位由物料档案带入且只读；如不正确请先维护物料档案。</div>
      </el-form-item>

    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="materials.length === 0 && !row"
        data-testid="byp-save"
        @click="submit"
      >
        保存副产
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, watch, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { bomRecipeApi } from '@/api/bom';
import type { BomRecipeItemView } from '@/api/bom';

export interface ByproductMaterialOption {
  id: string;
  name: string;
  unit?: string | null;
}

const props = withDefaults(defineProps<{
  modelValue: boolean;
  factoryId: string;
  recipeId: string;
  outputName: string;
  /** 产出 SKU 基本单位 —— 分母权威来源, 禁止硬编码。 */
  baseUnit: string;
  row?: BomRecipeItemView | null;
  materials: ByproductMaterialOption[];
}>(), { row: null });

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  saved: [];
}>();

const saving = ref(false);
// 刻意没有备注字段: BomRecipeItemView / BomRecipeItemPayload 两侧都没有 notes,
// 放个存得进去读不回来的输入框等于造假控件。
const form = reactive<{ materialTypeId: string; quantity: number | null }>({
  materialTypeId: '',
  quantity: null,
});

const selectedUnit = computed(
  () => props.materials.find((item) => item.id === form.materialTypeId)?.unit ?? '',
);

watch(
  () => [props.modelValue, props.row] as const,
  ([open]) => {
    if (!open) return;
    form.materialTypeId = props.row?.materialTypeId ?? '';
    // 回填读 standardQuantity —— 这是权威用量列。naturalQuantity 当前 UI 从不写入,
    // 读它会得到恒为空的值(Phase 1 已踩过一次)。
    form.quantity = props.row?.standardQuantity == null ? null : Number(props.row.standardQuantity);
  },
  { immediate: true },
);

async function submit(): Promise<void> {
  if (!form.materialTypeId) {
    return void ElMessage.warning('请选择副产物料');
  }
  // 产出量必须是正数: 0 或负数不是「未声明」而是错误声明, 静默放行会污染成本归集。
  if (form.quantity == null || !Number.isFinite(form.quantity) || form.quantity <= 0) {
    return void ElMessage.warning('请填写大于 0 的副产产出量');
  }

  saving.value = true;
  try {
    const payload = {
      materialTypeId: form.materialTypeId,
      materialCategory: 'BYPRODUCT',
      standardQuantity: form.quantity,
      unit: selectedUnit.value || undefined,
    };
    const response = props.row?.id != null
      ? await bomRecipeApi.updateItem(props.factoryId, Number(props.row.id), payload)
      : await bomRecipeApi.addItem(props.factoryId, props.recipeId, payload);
    if (!response?.success) {
      ElMessage.error(response?.message || '保存副产失败');
      return;
    }
    ElMessage.success('副产已保存');
    emit('update:modelValue', false);
    emit('saved');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存副产失败');
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.hint { color: #909399; font-size: 12px; line-height: 1.5; }
.unit { margin-left: 8px; color: #606266; }
</style>
