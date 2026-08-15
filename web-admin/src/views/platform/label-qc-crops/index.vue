<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import {
  listLabelQcTrayCrops,
  reviewLabelQcTrayCrop,
  type LabelQcPlatformLabelReview,
  type LabelQcPlatformTrayReview,
  type LabelQcPresence,
  type LabelQcTrayCrop,
  type LabelQcTrayCropStatus,
} from '@/api/labelQc';

const auth = useAuthStore();
const status = ref<LabelQcTrayCropStatus>('PENDING');
const crops = ref<LabelQcTrayCrop[]>([]);
const selectedId = ref<string | null>(null);
const draft = ref<LabelQcPlatformTrayReview | null>(null);
const loading = ref(false);
const saving = ref(false);
const dirty = ref(false);

const selected = computed(() => crops.value.find((crop) => crop.id === selectedId.value) ?? null);
const cropStyle = computed(() => {
  const crop = selected.value;
  if (!crop) return {};
  const width = crop.cropBox.xMax - crop.cropBox.xMin;
  const height = crop.cropBox.yMax - crop.cropBox.yMin;
  return {
    aspectRatio: `${crop.originalImageWidth * width} / ${crop.originalImageHeight * height}`,
  };
});
const imageStyle = computed(() => {
  const crop = selected.value;
  if (!crop) return {};
  const width = crop.cropBox.xMax - crop.cropBox.xMin;
  const height = crop.cropBox.yMax - crop.cropBox.yMin;
  return {
    width: `${100 / width}%`,
    height: `${100 / height}%`,
    left: `${-crop.cropBox.xMin / width * 100}%`,
    top: `${-crop.cropBox.yMin / height * 100}%`,
  };
});

function cloneReview(value: LabelQcPlatformTrayReview): LabelQcPlatformTrayReview {
  return JSON.parse(JSON.stringify(value)) as LabelQcPlatformTrayReview;
}

function labelStyle(label: LabelQcPlatformLabelReview) {
  return {
    left: `${label.bbox.xMin * 100}%`,
    top: `${label.bbox.yMin * 100}%`,
    width: `${(label.bbox.xMax - label.bbox.xMin) * 100}%`,
    height: `${(label.bbox.yMax - label.bbox.yMin) * 100}%`,
  };
}

function validateReview(value: LabelQcPlatformTrayReview): string | null {
  if (value.unjudgeable) return value.labels.length ? '整盒无法判断时请先删除所有标签框' : null;
  for (const [index, label] of value.labels.entries()) {
    const { xMin, yMin, xMax, yMax } = label.bbox;
    if (xMin < 0 || yMin < 0 || xMax > 1 || yMax > 1 || xMin >= xMax || yMin >= yMax) {
      return `第 ${index + 1} 个标签框坐标无效`;
    }
  }
  const white = value.labels.filter((item) => item.type === 'WHITE_LABEL').length;
  const color = value.labels.filter((item) => item.type === 'COLOR_LABEL').length;
  if (value.whitePresence === 'PRESENT' && !white) return '选择“有白标”时至少需要一个白标框';
  if (value.whitePresence === 'MISSING' && white) return '选择“缺白标”时不能保留白标框';
  if (value.colorPresence === 'PRESENT' && !color) return '选择“有彩标”时至少需要一个彩标框';
  if (value.colorPresence === 'MISSING' && color) return '选择“缺彩标”时不能保留彩标框';
  return null;
}

async function load(): Promise<void> {
  if (!auth.factoryId) return;
  loading.value = true;
  try {
    const response = await listLabelQcTrayCrops(auth.factoryId, { status: status.value, size: 100 });
    crops.value = response.data?.content ?? [];
    selectedId.value = crops.value[0]?.id ?? null;
    const first = crops.value[0];
    draft.value = first ? cloneReview(first.platformReview ?? first.factoryProposals) : null;
    dirty.value = false;
  } finally {
    loading.value = false;
  }
}

async function saveCurrent(): Promise<boolean> {
  if (!dirty.value) return true;
  if (!auth.factoryId || !selected.value || !draft.value) return false;
  const error = validateReview(draft.value);
  if (error) {
    ElMessage.warning(`${error}，请修正后再切换`);
    return false;
  }
  saving.value = true;
  try {
    const response = await reviewLabelQcTrayCrop(
      auth.factoryId,
      selected.value.id,
      selected.value.version,
      { ...draft.value, complete: true },
    );
    if (response.data) {
      const index = crops.value.findIndex((item) => item.id === response.data!.id);
      if (index >= 0) crops.value[index] = response.data;
    }
    dirty.value = false;
    ElMessage.success('当前单盒标签已保存');
    return true;
  } catch {
    return false;
  } finally {
    saving.value = false;
  }
}

async function selectCrop(crop: LabelQcTrayCrop): Promise<void> {
  if (crop.id === selectedId.value) return;
  if (!await saveCurrent()) return;
  selectedId.value = crop.id;
  draft.value = cloneReview(crop.platformReview ?? crop.factoryProposals);
  dirty.value = false;
}

async function changeStatus(nextStatus: LabelQcTrayCropStatus): Promise<void> {
  if (nextStatus === status.value) return;
  if (!await saveCurrent()) return;
  status.value = nextStatus;
  await load();
}

function changePresence(type: 'WHITE_LABEL' | 'COLOR_LABEL', presence: LabelQcPresence): void {
  if (!draft.value) return;
  if (type === 'WHITE_LABEL') draft.value.whitePresence = presence;
  else draft.value.colorPresence = presence;
  if (presence === 'MISSING') draft.value.labels = draft.value.labels.filter((label) => label.type !== type);
  draft.value.unjudgeable = false;
  dirty.value = true;
}

function addLabel(type: 'WHITE_LABEL' | 'COLOR_LABEL'): void {
  if (!draft.value) return;
  draft.value.labels.push({
    type,
    truncated: false,
    bbox: { xMin: 0.3, yMin: 0.35, xMax: 0.7, yMax: 0.65 },
  });
  if (type === 'WHITE_LABEL') draft.value.whitePresence = 'PRESENT';
  else draft.value.colorPresence = 'PRESENT';
  draft.value.unjudgeable = false;
  dirty.value = true;
}

function markUnjudgeable(): void {
  if (!draft.value) return;
  draft.value.unjudgeable = true;
  draft.value.whitePresence = 'UNJUDGEABLE';
  draft.value.colorPresence = 'UNJUDGEABLE';
  draft.value.labels = [];
  dirty.value = true;
}

onMounted(load);
</script>

<template>
  <section class="crop-workbench" v-loading="loading">
    <header>
      <div><p>平台标注</p><h1>单盒标签精修</h1></div>
      <div class="filters">
        <el-select :model-value="status" @change="changeStatus">
          <el-option label="待精修" value="PENDING" />
          <el-option label="已完成" value="REVIEWED" />
          <el-option label="无法判断" value="UNJUDGEABLE" />
        </el-select>
        <el-button :loading="saving" type="primary" @click="saveCurrent">保存当前</el-button>
      </div>
    </header>

    <div v-if="crops.length" class="workspace">
      <aside class="queue">
        <button
          v-for="crop in crops"
          :key="crop.id"
          type="button"
          :class="{ active: crop.id === selectedId }"
          @click="selectCrop(crop)"
        >
          <strong>照片 {{ crop.photoId.slice(0, 8) }} · 盒 {{ crop.trayIndex + 1 }}</strong>
          <span>{{ crop.sourceDecision }} · {{ crop.status }}</span>
        </button>
      </aside>

      <main v-if="selected && draft" class="editor">
        <div class="lineage">
          <span>裁切 {{ selected.cropAlgorithmVersion }}</span>
          <span>审核 SHA {{ selected.objectReviewSha256.slice(0, 12) }}</span>
          <span>裁切 SHA {{ selected.cropSpecSha256.slice(0, 12) }}</span>
        </div>
        <div class="crop-canvas" :style="cropStyle">
          <img :src="selected.originalImageUrl" :style="imageStyle" alt="人工最终盒子裁切" draggable="false">
          <div
            v-for="(label, index) in draft.labels"
            :key="index"
            class="label-box"
            :class="label.type === 'WHITE_LABEL' ? 'white' : 'color'"
            :style="labelStyle(label)"
          >
            {{ label.type === 'WHITE_LABEL' ? '白标' : '彩标' }}
          </div>
        </div>

        <div class="presence-row">
          <template v-for="type in (['WHITE_LABEL', 'COLOR_LABEL'] as const)" :key="type">
            <strong>{{ type === 'WHITE_LABEL' ? '白标' : '彩标' }}</strong>
            <button
              v-for="option in (['PRESENT', 'MISSING', 'UNJUDGEABLE'] as const)"
              :key="option"
              type="button"
              :class="{ on: (type === 'WHITE_LABEL' ? draft.whitePresence : draft.colorPresence) === option }"
              @click="changePresence(type, option)"
            >{{ { PRESENT: '有', MISSING: '缺', UNJUDGEABLE: '看不清' }[option] }}</button>
            <button type="button" @click="addLabel(type)">+ 补框</button>
          </template>
          <button type="button" class="unjudgeable" @click="markUnjudgeable">整盒无法判断</button>
        </div>

        <div class="label-grid">
          <article v-for="(label, index) in draft.labels" :key="index">
            <select v-model="label.type" @change="dirty = true">
              <option value="WHITE_LABEL">白标</option><option value="COLOR_LABEL">彩标</option>
            </select>
            <label v-for="field in (['xMin', 'yMin', 'xMax', 'yMax'] as const)" :key="field">
              {{ field }}
              <input v-model.number="label.bbox[field]" type="number" min="0" max="1" step="0.001" @input="dirty = true">
            </label>
            <label><input v-model="label.truncated" type="checkbox" @change="dirty = true"> 边缘残缺</label>
            <button type="button" class="delete" @click="draft.labels.splice(index, 1); dirty = true">删除</button>
          </article>
        </div>
        <p class="hint">切换队列项即保存当前精修；工厂整图盒子真值不会被这里覆盖。</p>
      </main>
    </div>
    <el-empty v-else description="当前没有单盒标签精修任务" />
  </section>
</template>

<style scoped>
.crop-workbench { min-height: calc(100vh - 92px); padding: 24px; color: #17251f; background: #f4f6f4; }
header { display: flex; align-items: end; justify-content: space-between; margin-bottom: 18px; }
header p { margin: 0; color: #66756f; font-size: 12px; letter-spacing: .12em; } h1 { margin: 3px 0 0; font-size: 28px; }
.filters { display: flex; gap: 10px; }.filters .el-select { width: 130px; }
.workspace { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 16px; min-height: 640px; }
.queue { display: flex; flex-direction: column; gap: 8px; overflow: auto; }
.queue button { min-height: 64px; padding: 10px 12px; border: 1px solid #d5ded9; border-radius: 10px; text-align: left; background: #fff; cursor: pointer; }
.queue button.active { border-color: #0b8b70; box-shadow: 0 0 0 2px #0b8b7020; }.queue span { display: block; margin-top: 5px; color: #6a7872; font-size: 11px; }
.editor { padding: 16px; border: 1px solid #dde5e1; border-radius: 14px; background: #fff; }
.lineage { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }.lineage span { padding: 5px 8px; border-radius: 99px; color: #53635c; background: #eef3f0; font-size: 11px; }
.crop-canvas { position: relative; max-height: 520px; overflow: hidden; border-radius: 12px; background: #17231f; }.crop-canvas img { position: absolute; max-width: none; object-fit: fill; }
.label-box { position: absolute; box-sizing: border-box; border: 3px solid; color: #fff; font-size: 11px; font-weight: 800; }.label-box.white { border-color: #0891b2; background: #0891b222; }.label-box.color { border-color: #9333ea; background: #9333ea22; }
.presence-row { display: grid; grid-template-columns: 50px repeat(4, minmax(70px, auto)); gap: 8px; align-items: center; margin-top: 14px; }.presence-row button { min-height: 36px; border: 1px solid #cad7d1; border-radius: 8px; background: #fff; cursor: pointer; }.presence-row button.on { color: #fff; border-color: #08745f; background: #08745f; }.presence-row .unjudgeable { grid-column: 1 / -1; color: #8b4b10; }
.label-grid { display: grid; gap: 10px; margin-top: 14px; }.label-grid article { display: grid; grid-template-columns: 120px repeat(4, 1fr) 110px 70px; gap: 8px; align-items: end; padding: 10px; border-radius: 10px; background: #f7f9f8; }.label-grid label { color: #52615b; font-size: 11px; }.label-grid input[type=number], .label-grid select { width: 100%; min-height: 34px; box-sizing: border-box; }.delete { min-height: 34px; color: #a12d23; border: 1px solid #e4b7b2; border-radius: 7px; background: #fff; }
.hint { margin: 14px 0 0; color: #66756f; font-size: 12px; }
@media (max-width: 980px) { .workspace { grid-template-columns: 1fr; }.queue { max-height: 180px; }.label-grid article { grid-template-columns: repeat(2, 1fr); } }
</style>
