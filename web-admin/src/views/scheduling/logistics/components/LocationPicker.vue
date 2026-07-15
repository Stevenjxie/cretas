<script setup lang="ts">
/**
 * 门店定位面板 —— 待定位门店手动补录经纬度, 仿导航 app 交互(客户要求):
 * 搜索联想找地标 + 地图上可拖拽 pin 精确微调, 确认后回填坐标。
 *
 * 打开时用 AMap.Geocoder 对地址做一次初始猜测定位(诚实：猜测失败则回落配送中心坐标, 不阻断打开),
 * 用户在此基础上用搜索框(AMap.AutoComplete 联想)跳转或直接拖拽 pin 精确调整, 确认后触发 confirm。
 */
import { nextTick, ref, watch } from 'vue';
import type { LogisticsDeliveryOrder } from '@/api/logistics';
import {
  ensurePicker,
  loadAmap,
  type AMapAutoComplete,
  type AMapInstance,
  type AMapMarker,
  type AMapNamespace,
} from '../amapLoader';
import { DEPOT_LNGLAT } from '../mapDepot';

const props = defineProps<{
  modelValue: boolean;
  order: LogisticsDeliveryOrder | null;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
  (e: 'confirm', payload: { orderId: string; longitude: number; latitude: number }): void;
}>();

interface Suggestion { value: string; district?: string; lng: number; lat: number }

const mapEl = ref<HTMLElement>();
const searchKeyword = ref('');
const pickedCoord = ref<[number, number] | null>(null);
const mapReady = ref(false);
const pluginsReady = ref(false);

let amap: AMapNamespace | null = null;
let map: AMapInstance | null = null;
let marker: AMapMarker | null = null;
let autoComplete: AMapAutoComplete | null = null;

function close(): void {
  emit('update:modelValue', false);
}

function placeMarker(lng: number, lat: number, recenter = true): void {
  pickedCoord.value = [lng, lat];
  if (!map || !amap) return;
  if (!marker) {
    marker = new amap.Marker({
      position: [lng, lat],
      draggable: true,
      cursor: 'move',
      zIndex: 200,
    });
    marker.on('dragend', () => {
      const pos = marker!.getPosition();
      pickedCoord.value = [pos.getLng(), pos.getLat()];
    });
    map.add([marker]);
  } else {
    marker.setPosition([lng, lat]);
  }
  if (recenter) {
    map.setFitView([marker], false, [80, 80, 80, 80]);
  }
}

/** 打开面板：挂地图 + 懒加载搜索/地理编码插件 + 对地址做一次初始猜测定位（诚实：失败就回落配送中心坐标）。 */
async function initPanel(): Promise<void> {
  mapReady.value = false;
  pluginsReady.value = false;
  searchKeyword.value = '';
  pickedCoord.value = null;
  await nextTick();
  if (!mapEl.value) return;

  try {
    amap = await loadAmap();
  } catch {
    return; // 无 key / 网络失败 → 诚实什么都不做, 面板显示"地图不可用"由模板兜底
  }

  const order = props.order;
  const initialCenter: [number, number] = order?.longitude != null && order?.latitude != null
    ? [order.longitude, order.latitude]
    : DEPOT_LNGLAT;

  map = new amap.Map(mapEl.value, {
    zoom: 15,
    center: initialCenter,
    viewMode: '2D',
    lang: 'zh_cn',
  });
  map.on('complete', () => { mapReady.value = true; });
  map.on('click', (e: unknown) => {
    // 直接点地图任意位置也能定位(比只能拖 pin 更直觉)
    const evt = e as { lnglat?: { getLng(): number; getLat(): number } };
    if (evt.lnglat) placeMarker(evt.lnglat.getLng(), evt.lnglat.getLat(), false);
  });

  await ensurePicker();
  const AutoCompleteCtor = amap.AutoComplete;
  const GeocoderCtor = amap.Geocoder;
  pluginsReady.value = Boolean(AutoCompleteCtor && GeocoderCtor);
  if (AutoCompleteCtor) {
    autoComplete = new AutoCompleteCtor({ city: '全国' });
  }

  // 已有坐标(哪怕是错的旧坐标)先落 pin, 让用户直观看到"当前在哪、要往哪拖"; 全新待定位门店则用
  // Geocoder 对地址做一次初始猜测(找不到就落配送中心, 用户直接搜索/拖拽从零定位, 诚实不伪造精确度)。
  if (order?.longitude != null && order?.latitude != null) {
    placeMarker(order.longitude, order.latitude);
  } else if (GeocoderCtor && order?.address) {
    const geocoder = new GeocoderCtor();
    geocoder.getLocation(order.address, (status, result) => {
      const first = result.geocodes?.[0]?.location;
      if (status === 'complete' && first) {
        placeMarker(first.getLng(), first.getLat());
      } else {
        placeMarker(DEPOT_LNGLAT[0], DEPOT_LNGLAT[1]);
      }
    });
  } else {
    placeMarker(DEPOT_LNGLAT[0], DEPOT_LNGLAT[1]);
  }
}

function teardownPanel(): void {
  if (map) {
    map.destroy();
    map = null;
  }
  marker = null;
  autoComplete = null;
  amap = null;
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) void initPanel();
    else teardownPanel();
  },
);

/** el-autocomplete 标准协议: (查询词, 回调) → 回调传入建议数组供其内置下拉渲染。 */
function querySearch(queryString: string, cb: (results: Suggestion[]) => void): void {
  const kw = queryString.trim();
  if (!kw || !autoComplete) {
    cb([]);
    return;
  }
  autoComplete.search(kw, (status, result) => {
    if (status !== 'complete' || !result.tips) {
      cb([]);
      return;
    }
    const items = result.tips
      .filter((t) => t.location)
      .map((t): Suggestion => {
        const loc = t.location as unknown as { lng?: number; lat?: number; getLng?: () => number; getLat?: () => number };
        const lng = typeof loc.getLng === 'function' ? loc.getLng() : (loc.lng ?? 0);
        const lat = typeof loc.getLat === 'function' ? loc.getLat() : (loc.lat ?? 0);
        return { value: t.name, district: t.district, lng, lat };
      })
      .filter((t) => t.lng && t.lat);
    cb(items);
  });
}

function handleSelect(item: Suggestion): void {
  placeMarker(item.lng, item.lat);
}

function confirmLocation(): void {
  if (!props.order || !pickedCoord.value) return;
  const [lng, lat] = pickedCoord.value;
  emit('confirm', { orderId: props.order.id, longitude: lng, latitude: lat });
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="补录门店定位"
    width="560px"
    append-to-body
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div v-if="order" class="lp-body">
      <!-- 上下文: 门店名 + 地址常显(fool-proof Rule 2), 让调度员清楚在给谁定位 -->
      <div class="lp-ctx">
        <div class="lp-store">{{ order.storeName }}</div>
        <div class="lp-addr">{{ order.address }}</div>
      </div>

      <!-- 搜索联想: 仿导航 app 输入地标关键词跳转; 插件加载失败时诚实降级为纯地图点选(不留死角) -->
      <el-autocomplete
        v-if="pluginsReady"
        v-model="searchKeyword"
        :fetch-suggestions="querySearch"
        placeholder="搜索地标 / 小区 / 商圈名称跳转到该位置"
        clearable
        class="lp-search-input"
        :trigger-on-focus="false"
        @select="handleSelect"
      >
        <template #default="{ item }">
          <div class="lp-sug-row">
            <span class="lp-sug-name">{{ item.value }}</span>
            <span v-if="item.district" class="lp-sug-district">{{ item.district }}</span>
          </div>
        </template>
      </el-autocomplete>
      <el-alert
        v-else-if="mapReady"
        type="info" :closable="false" show-icon
        title="搜索定位暂不可用，请直接在下方地图上拖动图钉或点击定位"
      />

      <!-- 地图 + 可拖拽 pin, 点地图任意处也能定位 -->
      <div class="lp-map-wrap">
        <div ref="mapEl" class="lp-map" />
        <div v-if="!mapReady" class="lp-map-loading">地图加载中…</div>
      </div>
      <p class="lp-hint">拖动地图上的图钉，或点击地图任意位置，精确调整到门店实际位置。</p>
    </div>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :disabled="!pickedCoord" @click="confirmLocation">确认定位</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.lp-body { display: flex; flex-direction: column; gap: 12px; }
.lp-ctx { padding: 10px 12px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; }
.lp-store { color: #0f172a; font-weight: 700; font-size: 15px; }
.lp-addr { color: #94a3b8; font-size: 12.5px; margin-top: 2px; }
.lp-search-input { width: 100%; }
.lp-sug-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.lp-sug-name { color: #101828; }
.lp-sug-district { color: #98a2b3; font-size: 11.5px; flex: none; }
.lp-map-wrap { position: relative; height: 320px; border-radius: 10px; overflow: hidden; border: 1px solid #e2e8f0; }
.lp-map { position: absolute; inset: 0; width: 100%; height: 100%; }
.lp-map-loading { position: absolute; inset: 0; display: grid; place-items: center; background: #f4f6f9; color: #98a2b3; font-size: 13px; }
.lp-hint { margin: 0; color: #98a2b3; font-size: 12px; }
</style>
