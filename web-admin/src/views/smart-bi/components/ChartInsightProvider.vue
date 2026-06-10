<template>
  <!--
    ChartInsightProvider.vue — Phase B keystone (2026-06-10).

    Problem solved:
      useChartInsight contains a Vue watcher that MUST be called at the top level
      of setup(). It CANNOT be called inside a v-for loop. Many surfaces render
      dynamic arrays of charts (unknown length, runtime types) and need per-chart
      insight state.

    Solution:
      This thin wrapper component calls useChartInsight ONCE in its own setup().
      Because each <ChartInsightProvider> is its own Vue component instance, mounting
      it N times inside a v-for is safe — each instance's watcher runs at that
      instance's setup() top level, never inside a loop.

    Usage in a v-for context (now safe):
      <ChartInsightProvider
        v-for="item in charts"
        :key="item.id"
        :chart="item.chart"
        :perms="userPerms"
        :factory-id="factoryId"
      />

    Props:
      chart      — ChartWithMeta | null (the chart object with .meta + .config).
                   Pass null to skip (renders nothing, no Tier2 call).
      perms      — UserPermissions (canViewFinance gate).
      factoryId  — Optional factory ID string (forwarded to Tier2 request body).
                   Defaults to ''.
      autoTier2  — Whether to automatically call Tier2 when Tier1 returns null.
                   Defaults to true.
      depth      — Passed through to <ChartInsight>. 'concise' (default) or 'detailed'.

    Reactive:
      Props are reactive. When the `chart` prop changes, the source getter re-fires
      and the watcher inside useChartInsight re-evaluates insight state.
  -->
  <ChartInsight :insight="insight" :loading="loading" :depth="depth" />
</template>

<script setup lang="ts">
import ChartInsight from './ChartInsight.vue';
import { useChartInsight } from '@/composables/useChartInsight';
import type { ChartWithMeta, UserPermissions } from './chartInsight';

const props = withDefaults(
  defineProps<{
    /**
     * The chart object (ChartWithMeta). Pass null to skip insight (renders nothing).
     * When this prop changes reactively, the composable's watcher re-evaluates.
     */
    chart: ChartWithMeta | null;
    /**
     * Current user's permission context.
     * Gates whether finance absolute ¥ values are visible.
     */
    perms: UserPermissions;
    /**
     * Factory ID forwarded to Tier2 request body.
     * Must match the JWT factoryId. Defaults to ''.
     */
    factoryId?: string;
    /**
     * Whether to automatically call Tier2 when Tier1 returns null.
     * Defaults to true (Tier2 is called on Tier1-null by default).
     */
    autoTier2?: boolean;
    /**
     * Display depth passed through to <ChartInsight>.
     * 'concise' = finding line only (default).
     * 'detailed' = finding + implication + suggestion.
     */
    depth?: 'concise' | 'detailed';
  }>(),
  {
    factoryId: '',
    autoTier2: true,
    depth: 'concise',
  },
);

/**
 * useChartInsight is called ONCE in this component's setup().
 *
 * v-for safety: each <ChartInsightProvider> instance has its own setup() execution,
 * so this watcher registration always happens at the top level of that instance's
 * setup — never inside a parent's loop body. Vue 3 composition API requires watchers
 * to be registered at setup() call time (not in event handlers or nested scopes).
 * Mounting N instances in a v-for creates N independent watchers, one per instance.
 *
 * Source getter: reads props.chart reactively. When `chart` prop changes (e.g.
 * the parent v-for updates), this getter returns a new value and the composable's
 * deep watcher fires, re-evaluating insight state for this instance.
 */
const { insight, loading } = useChartInsight(
  // source: reactive getter — returns { chart } when chart prop is non-null, null otherwise
  () => (props.chart != null ? { chart: props.chart } : null),
  // perms: reactive getter — re-reads props.perms on each evaluation
  () => props.perms,
  {
    factoryId: () => props.factoryId ?? '',
    autoTier2: props.autoTier2 !== false,
  },
);
</script>
