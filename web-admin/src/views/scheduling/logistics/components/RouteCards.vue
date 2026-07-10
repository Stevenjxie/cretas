<script setup lang="ts">
import { computed } from 'vue';
import type { RouteTrip, StoreOrder } from '../types';

const props = defineProps<{
  stores: StoreOrder[];
  trips: RouteTrip[];
  selectedTripId: string | null;
  selectedStoreId: string | null;
}>();

const emit = defineEmits<{
  (event: 'select-trip', tripId: string): void;
  (event: 'select-store', storeId: string): void;
}>();

const storesById = computed(() => new Map(props.stores.map((store) => [store.id, store])));

const statusLabels: Record<RouteTrip['status'], string> = {
  draft: '待确认',
  needs_vehicle: '待匹配车辆',
  needs_route_data: '缺少路线数据',
  confirmed: '已确认',
};

function storeName(storeId: string): string {
  return storesById.value.get(storeId)?.name ?? storeId;
}

function vehicleLabel(trip: RouteTrip): string {
  return trip.vehiclePlate ?? trip.vehicleId ?? '待匹配车辆';
}

function selectTrip(tripId: string): void {
  emit('select-trip', tripId);
}
</script>

<template>
  <section class="route-cards" aria-label="配送线路">
    <article
      v-for="(trip, index) in trips"
      :key="trip.id"
      data-testid="route-card"
      :data-trip-id="trip.id"
      :class="['route-card', { selected: trip.id === selectedTripId }]"
      @click="selectTrip(trip.id)"
    >
      <header class="card-header">
        <button
          type="button"
          data-testid="route-select"
          :data-trip-id="trip.id"
          class="route-select-button"
          :aria-pressed="trip.id === selectedTripId"
          :aria-label="`选择线路 ${index + 1}，第 ${trip.tripNo} 趟配送`"
          @click.stop="selectTrip(trip.id)"
        >
          <span class="route-eyebrow">线路 {{ String(index + 1).padStart(2, '0') }}</span>
          <span class="route-title">第 {{ trip.tripNo }} 趟配送</span>
        </button>
        <span :class="['route-status', `status-${trip.status}`]">
          {{ statusLabels[trip.status] }}
        </span>
      </header>

      <div class="vehicle-row">
        <span class="field-label">车辆</span>
        <strong>{{ vehicleLabel(trip) }}</strong>
      </div>

      <div class="store-chain" :aria-label="`线路 ${index + 1} 门店顺序`">
        <template v-for="(storeId, storeIndex) in trip.storeIds" :key="storeId">
          <button
            type="button"
            data-testid="route-store"
            :data-store-id="storeId"
            :class="['store-chip', { selected: storeId === selectedStoreId }]"
            @click.stop="emit('select-store', storeId)"
          >
            {{ storeName(storeId) }}
          </button>
          <span v-if="storeIndex < trip.storeIds.length - 1" class="chain-arrow" aria-hidden="true">→</span>
        </template>
      </div>

      <dl class="route-metrics">
        <div>
          <dt>里程</dt>
          <dd>{{ trip.totalDistanceKm.toFixed(1) }} km</dd>
        </div>
        <div>
          <dt>装载</dt>
          <dd>{{ trip.totalVolumeCbm.toFixed(1) }} m³ · {{ Math.round(trip.loadRate * 100) }}%</dd>
        </div>
      </dl>
    </article>
  </section>
</template>

<style scoped lang="scss">
.route-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
  width: 100%;
}

.route-card {
  min-width: 0;
  padding: 16px;
  color: #101828;
  background: #ffffff;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;

  &:hover,
  &:focus-within {
    border-color: rgba(27, 101, 168, 0.48);
    box-shadow: 0 6px 18px rgba(27, 101, 168, 0.12);
    outline: none;
    transform: translateY(-1px);
  }

  &.selected {
    border-color: #1b65a8;
    box-shadow: 0 0 0 2px rgba(27, 101, 168, 0.12), 0 6px 18px rgba(27, 101, 168, 0.12);
  }
}

.card-header,
.vehicle-row,
.route-metrics {
  display: flex;
  align-items: center;
}

.card-header {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;

}

.route-select-button {
  display: grid;
  min-width: 0;
  padding: 0;
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: pointer;

  &:focus-visible {
    outline: 2px solid #1b65a8;
    outline-offset: 4px;
  }
}

.route-eyebrow {
  color: #344054;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.route-title {
  margin-top: 2px;
  color: #101828;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.4;
}

.route-status {
  flex: 0 0 auto;
  padding: 4px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.status-draft {
  color: #175cd3;
  background: #eff8ff;
}

.status-needs_vehicle {
  color: #b54708;
  background: #fffaeb;
}

.status-needs_route_data {
  color: #b42318;
  background: #fef3f2;
}

.status-confirmed {
  color: #027a48;
  background: #ecfdf3;
}

.vehicle-row {
  gap: 10px;
  min-height: 24px;
  color: #344054;
  font-size: 14px;

  strong {
    color: #101828;
    font-weight: 700;
  }
}

.field-label {
  color: #344054;
}

.store-chain {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-height: 68px;
  margin: 14px 0;
  padding: 10px;
  background: #f4f6f9;
  border-radius: 10px;
}

.store-chip {
  padding: 4px 7px;
  color: #344054;
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.4;
  background: #ffffff;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  cursor: pointer;

  &:hover,
  &:focus-visible,
  &.selected {
    color: #101828;
    border-color: #1b65a8;
    outline: none;
  }
}

.chain-arrow {
  color: #344054;
  font-weight: 800;
}

.route-metrics {
  justify-content: space-between;
  gap: 12px;
  margin: 0;
  padding-top: 12px;
  border-top: 1px solid #edf2f7;

  div {
    min-width: 0;
  }

  dt {
    margin-bottom: 3px;
    color: #344054;
    font-size: 12px;
  }

  dd {
    margin: 0;
    color: #101828;
    font-size: 14px;
    font-weight: 750;
    white-space: nowrap;
  }
}

@media (max-width: 720px) {
  .route-cards {
    grid-template-columns: 1fr;
  }
}
</style>
