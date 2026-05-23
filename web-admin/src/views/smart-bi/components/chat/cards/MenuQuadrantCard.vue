<script setup lang="ts">
import { computed } from 'vue';
import type { SectionPayload } from '@/types/restaurant-chat';

const props = defineProps<{ section: SectionPayload }>();

interface DishItem {
  name: string;
  soldQty?: number;
  marginRatio?: number;
}

interface QuadrantData {
  star: DishItem[];
  cash_cow: DishItem[];
  puzzle: DishItem[];
  dog: DishItem[];
}

const quadrants = computed<QuadrantData>(() => {
  const d = props.section.data as Record<string, unknown>;
  const q = (d.quadrants ?? {}) as Record<string, DishItem[]>;
  return {
    star: q.star ?? [],
    cash_cow: q.cash_cow ?? (q.cashCow as DishItem[]) ?? [],
    puzzle: q.puzzle ?? [],
    dog: q.dog ?? [],
  };
});

const recommendations = computed<string[]>(() => {
  const d = props.section.data as Record<string, unknown>;
  return (d.recommendations ?? []) as string[];
});

const summary = computed<Record<string, number>>(() => {
  const d = props.section.data as Record<string, unknown>;
  return (d.summary ?? {}) as Record<string, number>;
});
</script>

<template>
  <div class="quadrant-card">
    <div class="card-label">▸ 菜品工程 4 象限 (Kasavana-Smith)</div>
    <div v-if="summary.total_items" class="quadrant-summary">
      共分析 {{ summary.total_items }} 道菜 ·
      <span class="quad-count star">Star {{ summary.star_count ?? quadrants.star.length }}</span>
      <span class="quad-count cow">Cow {{ summary.cow_count ?? quadrants.cash_cow.length }}</span>
      <span class="quad-count puzzle">Puzzle {{ summary.puzzle_count ?? quadrants.puzzle.length }}</span>
      <span class="quad-count dog">Dog {{ summary.dog_count ?? quadrants.dog.length }}</span>
    </div>
    <div class="quadrant-grid">
      <div class="quadrant-cell cell-star">
        <div class="cell-header">
 <span class="cell-symbol"></span>
          <span class="cell-title">Star · 招牌菜</span>
          <span class="cell-count">{{ quadrants.star.length }}</span>
        </div>
        <ul class="item-list">
          <li v-for="dish in quadrants.star.slice(0, 5)" :key="dish.name">{{ dish.name }}</li>
        </ul>
      </div>
      <div class="quadrant-cell cell-cow">
        <div class="cell-header">
          <span class="cell-symbol">●</span>
          <span class="cell-title">Cash Cow · 走量</span>
          <span class="cell-count">{{ quadrants.cash_cow.length }}</span>
        </div>
        <ul class="item-list">
          <li v-for="dish in quadrants.cash_cow.slice(0, 5)" :key="dish.name">{{ dish.name }}</li>
        </ul>
      </div>
      <div class="quadrant-cell cell-puzzle">
        <div class="cell-header">
          <span class="cell-symbol">?</span>
          <span class="cell-title">Puzzle · 高利无人点</span>
          <span class="cell-count">{{ quadrants.puzzle.length }}</span>
        </div>
        <ul class="item-list">
          <li v-for="dish in quadrants.puzzle.slice(0, 5)" :key="dish.name">{{ dish.name }}</li>
        </ul>
      </div>
      <div class="quadrant-cell cell-dog">
        <div class="cell-header">
 <span class="cell-symbol"></span>
          <span class="cell-title">Dog · 淘汰候选</span>
          <span class="cell-count">{{ quadrants.dog.length }}</span>
        </div>
        <ul class="item-list">
          <li v-for="dish in quadrants.dog.slice(0, 5)" :key="dish.name">{{ dish.name }}</li>
        </ul>
      </div>
    </div>
    <ul v-if="recommendations.length > 0" class="recs">
      <li v-for="(rec, idx) in recommendations" :key="idx">{{ rec }}</li>
    </ul>
  </div>
</template>

<style scoped>
.quadrant-card {
  margin-top: 12px;
  background: #fefcf6;
  border: 1px solid #d4cdb8;
  padding: 14px 18px;
  border-radius: 4px;
}
.card-label {
  font-family: monospace;
  font-size: 10px;
  color: #a68449;
  letter-spacing: 1.5px;
  margin-bottom: 12px;
  text-transform: uppercase;
}
.quadrant-summary {
  font-family: 'Noto Serif SC', serif;
  font-size: 11px;
  color: #6b6b6b;
  margin-bottom: 10px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.quad-count {
  font-family: monospace;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
}
.quad-count.star { background: #fef9c3; color: #854d0e; }
.quad-count.cow { background: #dcfce7; color: #166534; }
.quad-count.puzzle { background: #dbeafe; color: #1e40af; }
.quad-count.dog { background: #fee2e2; color: #991b1b; }
.quadrant-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.quadrant-cell {
  padding: 10px 12px;
  border: 1px solid #d4cdb8;
  border-radius: 4px;
  background: #fdf9ed;
}
.cell-star { border-left: 4px solid #f59e0b; }
.cell-cow { border-left: 4px solid #22c55e; }
.cell-puzzle { border-left: 4px solid #3b82f6; }
.cell-dog { border-left: 4px solid #b91c1c; }
.cell-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.cell-symbol { font-size: 14px; }
.cell-title {
  font-weight: 700;
  font-size: 11px;
  color: #2d4a3e;
  font-family: 'Noto Serif SC', serif;
  flex: 1;
}
.cell-count {
  font-family: monospace;
  font-size: 10px;
  color: #a68449;
}
.item-list {
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 10px;
  color: #3d3d3d;
  font-family: 'Noto Serif SC', serif;
  line-height: 1.6;
}
.recs {
  margin-top: 12px;
  padding: 10px;
  background: #f2ece0;
  border-radius: 4px;
  font-size: 11px;
  color: #3d3d3d;
  font-family: 'Noto Serif SC', serif;
  list-style-position: inside;
}
</style>
