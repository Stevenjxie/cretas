<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { bomRecipeApi, type BomItemSubstituteView, type BomRecipeItemPayload, type BomRecipeItemView } from '@/api/bom';
import { canonicalUnitCode, displayUnit } from '@/utils/unitPricing';

/**
 * 画布上的包材编辑弹窗 —— Task 1 (2026-08-05 bom-canvas-phase3-2)。
 *
 * 走 `bomRecipeApi.addItem` / `bomRecipeApi.updateItem`(见 task-1-report.md Step 1 表),
 * 不新增任何 API 函数/端点。这两个函数原本是旧 BOM 页(`views/production/bom/index.vue`)
 * 的包材新增/编辑路径在用的; 该页 2026-08-07 阶段 5 已删, 本组件成为唯一调用方。
 *
 * ⚠️ Phase 1 事故教训(来自那个已删页面的 :1252-1291): 它的表单字段叫
 * `naturalQuantity`,但落库/编辑回填走的是 `standardQuantity` —— `naturalQuantity`
 * 只是发给后端的包装层级兼容字段,从未被任何回填路径读取。本组件不复用这个容易
 * 混淆的表单字段名,内部状态叫 `form.quantity`;编辑回填只读 `row.standardQuantity`,
 * 提交时把它同时写进 payload 的 `standardQuantity`(权威)与 `naturalQuantity`(兼容,
 * 值相同)两个字段。
 */

export interface PackagingMaterialOption {
  id: string;
  name: string;
  code?: string | null;
  primaryCode?: string | null;
  category?: string | null;
  materialCategory?: string | null;
  unit?: string | null;
  quantityUnit?: string | null;
  priceUnit?: string | null;
  movingAvgPrice?: number | null;
  unitPrice?: number | null;
  taxRate?: string | null;
  /**
   * 三级物料分类节点 id(后端 RawMaterialTypeDTO.classificationId ←
   * raw_material_types.classification_segment_id)。替代包材的作用域判据就是它。
   */
  classificationId?: number | string | null;
}

/** 与落库行同形 —— 直接复用 API 的响应类型, 避免维护一份平行、容易漂移的 DTO。 */
export type PackagingRowPayload = BomRecipeItemView;

const props = withDefaults(defineProps<{
  modelValue: boolean;
  factoryId: string;
  recipeId: string;
  outputName: string;
  /** 产出 SKU 的基本单位 —— 用量分母的权威来源, 禁止硬编码。 */
  baseUnit: string;
  row?: PackagingRowPayload | null;
  materials: PackagingMaterialOption[];
  /** 由调用方按 `parentKind === 'RECIPE_ITEM' && parentRecipeItemId === row.id` 预先过滤好传入
   *  (与 SeasoningBindingDialog 的 substituteRelations 契约一致)。 */
  substituteRelations?: BomItemSubstituteView[];
}>(), {
  row: null,
  substituteRelations: () => [],
});

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  saved: [];
  conflict: [];
}>();

const form = reactive({
  materialTypeId: '',
  quantity: null as number | null,
  isOptional: false,
  remark: '',
  substituteMaterialTypeIds: [] as string[],
  substituteFactors: {} as Record<string, number | null>,
});
const saving = reactive({ value: false });

const selectedMaterial = computed(() => props.materials.find((item) => item.id === form.materialTypeId));

/** 与 index.vue 的 recipeUnitForMaterial(category='PACKAGING') 同规则: 缺档案单位兜底 pcs。 */
const quantityUnit = computed(() => (
  canonicalUnitCode(selectedMaterial.value?.quantityUnit || selectedMaterial.value?.unit) || 'pcs'
));
const quantityUnitLabel = computed(() => displayUnit(quantityUnit.value));
const baseUnitLabel = computed(() => displayUnit(props.baseUnit));

/**
 * 替代包材的作用域判据 —— 只有同一个三级分类节点的包材才允许互替。
 *
 * <h3>🔴 2026-08-13 真机实测: 旧判据在生产上恒为空</h3>
 * 旧写法是「编码是 10 位以上纯数字 → 取前 10 位; 否则 category 不是『包材』→ 用 category」。
 * 两条分支在生产上**都走不到**:
 *
 * - 10 位数字码: 全库 552 个启用物料, 命中 **0 个**(本仓编码是 BC001/WL0xx 这种短码,
 *   「不再固定 16 位」之后再没有纯数字长码)。
 * - category 分支: 能进包材选择器的 category 只有 `包材/packaging/PACKAGING`
 *   (见 utils/materialCategory.ts#PACKAGING_CATEGORY_VALUES), 而这条分支正好把它们排除 ——
 *   **构造上就死的**, 不是当前数据碰巧走不到。
 *
 * 于是「替代包材」这一栏对所有工厂、所有包材 100% 禁用, 而提示说「主包材缺少可验证的
 * 分类」, 指向一个**没有任何可达数据状态能满足**的条件: 用户以为是自己档案没维护好,
 * 实际上怎么维护都没用。闸的意图是对的(不能让「标签」被「真空袋」替代),
 * 坏的是没人有路遵守它。
 *
 * <p>改用 `classificationId` —— 系统本来就为「分类/包装作用域」设计的载体
 * (raw_material_types.classification_segment_id, 三级分类节点)。它可空;
 * 空的时候仍然禁用替代, 但提示改成一个**做得到**的动作(去物料档案挂分类)。
 */
function packagingClassificationKey(material: PackagingMaterialOption | undefined): string {
  if (!material) return '';
  const segmentId = String(material.classificationId ?? '').trim();
  return segmentId ? `seg:${segmentId}` : '';
}

const substituteCandidates = computed<PackagingMaterialOption[]>(() => {
  const selectedKey = packagingClassificationKey(selectedMaterial.value);
  if (!selectedKey) return [];
  return props.materials.filter((item) => (
    item.id !== form.materialTypeId && packagingClassificationKey(item) === selectedKey
  ));
});

function substituteUnit(materialTypeId: string): string {
  const material = props.materials.find((item) => item.id === materialTypeId);
  return canonicalUnitCode(material?.quantityUnit || material?.unit);
}

function substituteNeedsExplicitFactor(materialTypeId: string): boolean {
  return substituteUnit(materialTypeId) !== quantityUnit.value;
}

watch(() => [props.modelValue, props.row] as const, () => {
  if (!props.modelValue) return;
  form.materialTypeId = props.row?.materialTypeId || '';
  // ⚠️ 只读 standardQuantity(落库字段), 不读 row.naturalQuantity —— 见头部注释。
  form.quantity = props.row?.standardQuantity != null ? Number(props.row.standardQuantity) : null;
  form.isOptional = Boolean(props.row?.isOptional);
  form.remark = props.row?.remark || '';
  form.substituteMaterialTypeIds = props.substituteRelations.map((relation) => relation.substituteMaterialTypeId);
  form.substituteFactors = Object.fromEntries(props.substituteRelations.map((relation) => [
    relation.substituteMaterialTypeId,
    relation.conversionFactor == null ? null : Number(relation.conversionFactor),
  ]));
}, { immediate: true });

function isRevisionConflict(error: unknown): boolean {
  const candidate = error as { response?: { status?: number }; status?: number };
  return candidate?.response?.status === 409 || candidate?.status === 409;
}

async function submit() {
  if (!form.materialTypeId) return ElMessage.warning('请选择包材');
  // 禁止降级处理: 空值/非正数一律拒绝保存, 不能默默落库为 0。
  if (form.quantity == null || !Number.isFinite(form.quantity) || form.quantity <= 0) {
    return ElMessage.warning('请填写大于 0 的每份成品包材用量');
  }
  const missingFactor = form.substituteMaterialTypeIds.find((materialTypeId) => {
    if (!substituteNeedsExplicitFactor(materialTypeId)) return false;
    const factor = Number(form.substituteFactors[materialTypeId]);
    return !Number.isFinite(factor) || factor <= 0;
  });
  if (missingFactor) return ElMessage.warning('不同单位的替代包材必须填写大于0的明确等价换算系数');

  saving.value = true;
  try {
    const payload: BomRecipeItemPayload = {
      materialTypeId: form.materialTypeId,
      workflowMaterialNodeId: props.row?.workflowMaterialNodeId ?? null,
      workflowInputPortId: props.row?.workflowInputPortId ?? null,
      workflowEdgeId: props.row?.workflowEdgeId ?? null,
      costScope: 'OUTPUT_EXCLUSIVE',
      standardQuantity: form.quantity,
      yieldRate: 100,
      unit: quantityUnit.value,
      unitPrice: Number.isFinite(Number(selectedMaterial.value?.movingAvgPrice ?? selectedMaterial.value?.unitPrice))
        ? Number(selectedMaterial.value?.movingAvgPrice ?? selectedMaterial.value?.unitPrice)
        : 0,
      taxRate: selectedMaterial.value?.taxRate === 'TAX_9' ? 9 : selectedMaterial.value?.taxRate === 'TAX_13' ? 13 : 0,
      materialCategory: 'PACKAGING',
      sortOrder: props.row?.sortOrder ?? 0,
      isOptional: form.isOptional,
      substituteGroup: null,
      packagingSpecId: null,
      packagingRole: 'PRIMARY_CONTAINER',
      naturalQuantity: form.quantity,
      substitutes: form.substituteMaterialTypeIds.map((materialTypeId) => ({
        materialTypeId,
        conversionFactor: substituteNeedsExplicitFactor(materialTypeId)
          ? form.substituteFactors[materialTypeId] ?? null
          : null,
      })),
      remark: form.remark.trim() || null,
      perPortion: false,
      semiFinishedRefCode: null,
      subProductTypeId: null,
    };
    if (props.row?.id) {
      await bomRecipeApi.updateItem(props.factoryId, Number(props.row.id), payload);
    } else {
      await bomRecipeApi.addItem(props.factoryId, props.recipeId, payload);
    }
    emit('update:modelValue', false);
    emit('saved');
  } catch (error: unknown) {
    if (isRevisionConflict(error)) {
      ElMessage.warning('配方已被其他人修改，请重新加载后再操作');
      emit('conflict');
    } else {
      // 禁止降级处理: 原样显示后端 message, 不用 generic「操作失败」吞掉具体原因。
      ElMessage.error((error as { message?: string })?.message || '保存包材失败');
    }
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="row ? '编辑包材' : '添加包材'" width="560px" @close="emit('update:modelValue', false)">
    <el-form label-width="130px" data-testid="packaging-binding-dialog">
      <el-form-item label="投入产出">
        <el-input data-testid="locked-output-context" :model-value="outputName" disabled />
        <div class="form-tip">产出由入口锁定，保存只影响该产出的包材。</div>
      </el-form-item>
      <el-form-item label="选择包材" required>
        <el-select v-model="form.materialTypeId" filterable placeholder="从包材档案选择…" style="width: 100%">
          <el-option v-for="material in materials" :key="material.id" :label="material.name" :value="material.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="每 1 份成品用量" required>
        <div style="display: flex; align-items: center; gap: 8px; width: 100%;">
          <el-input-number
            v-model="form.quantity"
            :precision="4"
            :step="0.01"
            :controls="false"
            placeholder="请输入用量"
            style="flex: 1;"
          />
          <span class="unit-suffix" data-testid="packaging-quantity-unit">{{ quantityUnitLabel }}</span>
        </div>
        <div class="form-tip">
          每生产 1{{ baseUnitLabel }}成品使用多少该包材。单位固定来自包材档案，不在此处修改。
        </div>
      </el-form-item>
      <el-form-item label="档案单位">
        <el-input :model-value="quantityUnitLabel" disabled />
        <div class="form-tip">单位从物料档案自动继承且只读；如不正确，请先维护物料档案。</div>
      </el-form-item>
      <el-form-item label="替代包材（可选）">
        <el-select
          v-model="form.substituteMaterialTypeIds"
          multiple
          filterable
          collapse-tags
          collapse-tags-tooltip
          placeholder="选择可替代当前主项的包材…"
          style="width: 100%"
          data-testid="packaging-substitute-select"
        >
          <el-option
            v-for="item in substituteCandidates"
            :key="item.id"
            :value="item.id"
            :label="`${item.name} · ${displayUnit(item.quantityUnit || item.unit)}`"
          />
        </el-select>
        <div class="form-tip">替代包材必须与主包材属于同一分类/包装作用域；不会作为额外需求重复计算。</div>
        <el-alert
          v-if="form.materialTypeId && !packagingClassificationKey(selectedMaterial)"
          type="warning"
          :closable="false"
          show-icon
          title="该包材还没挂物料分类，暂不能配置替代包材"
          description="替代范围按三级物料分类判定。请到「仓储管理 → 原料类型字典」编辑这个包材、选好分类后再回来配置。"
        />
        <div v-if="form.substituteMaterialTypeIds.length" class="substitute-factors">
          <div v-for="materialTypeId in form.substituteMaterialTypeIds" :key="materialTypeId" class="substitute-factor-row">
            <span>{{ materials.find((item) => item.id === materialTypeId)?.name || materialTypeId }}</span>
            <template v-if="substituteNeedsExplicitFactor(materialTypeId)">
              <el-input-number
                v-model="form.substituteFactors[materialTypeId]"
                :data-testid="`packaging-substitute-factor-${materialTypeId}`"
                :min="0.000001"
                :precision="6"
                :controls="false"
                placeholder="等价换算系数"
              />
              <small>跨单位必须明确换算</small>
            </template>
            <el-tag v-else size="small" type="info">同单位默认 1:1</el-tag>
          </div>
        </div>
      </el-form-item>
      <el-form-item label="需求性质">
        <el-switch
          v-model="form.isOptional"
          :active-value="false"
          :inactive-value="true"
          active-text="必需"
          inactive-text="可省略"
        />
        <div class="form-tip">默认必需；标记可省略后，生产时允许不投入该包材。</div>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving.value" @click="submit">保存包材</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-tip { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.unit-suffix { min-width: 40px; color: var(--el-text-color-regular); font-weight: 600; }
.substitute-factors { display: grid; gap: 6px; width: 100%; margin-top: 8px; }
.substitute-factor-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.substitute-factor-row span { overflow: hidden; color: var(--el-text-color-regular); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.substitute-factor-row :deep(.el-input-number) { width: 150px; }
</style>
