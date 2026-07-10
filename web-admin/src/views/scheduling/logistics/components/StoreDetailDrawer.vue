<script setup lang="ts">
import { computed } from 'vue';
import type { StoreOrder } from '../types';

const props = defineProps<{
  stores: StoreOrder[];
  selectedStoreId: string | null;
}>();

const emit = defineEmits<{
  (event: 'select-store', storeId: string | null): void;
}>();

const selectedStore = computed(() => (
  props.stores.find((store) => store.id === props.selectedStoreId) ?? null
));

function close(): void {
  emit('select-store', null);
}
</script>

<template>
  <div v-if="selectedStore" class="drawer-layer">
    <button class="drawer-scrim" type="button" aria-label="关闭门店详情" @click="close" />
    <aside
      data-testid="store-detail-drawer"
      class="store-drawer"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="`store-drawer-title-${selectedStore.id}`"
    >
      <header class="drawer-header">
        <div>
          <p>{{ selectedStore.id }} · {{ selectedStore.area }}</p>
          <h2 :id="`store-drawer-title-${selectedStore.id}`">{{ selectedStore.name }}</h2>
        </div>
        <button
          data-testid="close-store-drawer"
          class="close-button"
          type="button"
          aria-label="关闭门店详情"
          @click="close"
        >
          ×
        </button>
      </header>

      <dl class="detail-grid">
        <div data-testid="store-pieces">
          <dt>件数</dt>
          <dd>{{ selectedStore.pieces }} 件</dd>
        </div>
        <div data-testid="store-boxes">
          <dt>箱数</dt>
          <dd>{{ selectedStore.boxes }} 箱</dd>
        </div>
        <div data-testid="store-weight">
          <dt>重量</dt>
          <dd>{{ selectedStore.weightKg }} kg</dd>
        </div>
        <div data-testid="store-volume">
          <dt>体积</dt>
          <dd>{{ selectedStore.volumeCbm }} m³</dd>
        </div>
      </dl>

      <dl class="detail-list">
        <div data-testid="store-address">
          <dt>配送地址</dt>
          <dd>{{ selectedStore.address }}</dd>
        </div>
        <div data-testid="store-window">
          <dt>配送时间窗</dt>
          <dd>{{ selectedStore.window }}</dd>
        </div>
      </dl>
    </aside>
  </div>
</template>

<style scoped lang="scss">
.drawer-layer {
  position: fixed;
  z-index: 2000;
  inset: 0;
  display: flex;
  justify-content: flex-end;
}

.drawer-scrim {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  padding: 0;
  background: rgba(16, 24, 40, 0.32);
  border: 0;
  cursor: default;
}

.store-drawer {
  position: relative;
  width: min(420px, calc(100vw - 32px));
  height: 100%;
  padding: 24px;
  overflow-y: auto;
  color: #101828;
  background: #ffffff;
  border-left: 1px solid #edf2f7;
  box-shadow: -10px 0 32px rgba(16, 24, 40, 0.12);
}

.drawer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 20px;
  border-bottom: 1px solid #edf2f7;

  p {
    margin: 0 0 4px;
    color: #344054;
    font-size: 13px;
    font-weight: 650;
  }

  h2 {
    margin: 0;
    color: #101828;
    font-size: 20px;
    line-height: 1.4;
  }
}

.close-button {
  display: grid;
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  padding: 0;
  place-items: center;
  color: #344054;
  font: inherit;
  font-size: 24px;
  line-height: 1;
  background: #f4f6f9;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  cursor: pointer;

  &:hover,
  &:focus-visible {
    color: #101828;
    border-color: #1b65a8;
    outline: none;
  }
}

.detail-grid,
.detail-list {
  margin: 20px 0 0;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;

  div {
    padding: 14px;
    background: #f4f6f9;
    border: 1px solid #edf2f7;
    border-radius: 10px;
  }
}

.detail-list {
  display: grid;
  gap: 12px;

  div {
    padding: 14px 0;
    border-bottom: 1px solid #edf2f7;
  }
}

dt {
  margin-bottom: 5px;
  color: #344054;
  font-size: 13px;
}

dd {
  margin: 0;
  color: #101828;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.6;
}

@media (max-width: 600px) {
  .store-drawer {
    width: 100%;
    padding: 20px;
  }
}
</style>
