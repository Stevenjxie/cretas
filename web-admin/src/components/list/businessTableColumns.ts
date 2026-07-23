import { computed, ref, watch, type ComputedRef, type Ref } from 'vue';

export interface BusinessTableColumn {
  key: string;
  label: string;
}

interface UseBusinessTableColumnsOptions {
  storageKey: string;
  columns: () => BusinessTableColumn[];
  defaults: string[];
  max: number;
}

function storageAvailable(): boolean {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined';
}

export function normalizeVisibleColumnKeys(
  selected: unknown,
  availableKeys: string[],
  defaults: string[],
  max: number,
): string[] {
  const allowed = new Set(availableKeys);
  const requested = Array.isArray(selected)
    ? selected.filter((key): key is string => typeof key === 'string')
    : [];
  const valid = [...new Set(requested)].filter((key) => allowed.has(key)).slice(0, max);
  if (valid.length > 0) return valid;
  return [...new Set(defaults)].filter((key) => allowed.has(key)).slice(0, max);
}

export function readVisibleColumnKeys(
  storageKey: string,
  availableKeys: string[],
  defaults: string[],
  max: number,
): string[] {
  if (!storageAvailable()) {
    return normalizeVisibleColumnKeys([], availableKeys, defaults, max);
  }
  try {
    const raw = window.localStorage.getItem(storageKey);
    return normalizeVisibleColumnKeys(raw ? JSON.parse(raw) : [], availableKeys, defaults, max);
  } catch {
    return normalizeVisibleColumnKeys([], availableKeys, defaults, max);
  }
}

export function useBusinessTableColumns(options: UseBusinessTableColumnsOptions): {
  columns: ComputedRef<BusinessTableColumn[]>;
  visibleKeys: Ref<string[]>;
  isVisible: (key: string) => boolean;
  reset: () => void;
} {
  const columns = computed(options.columns);
  const visibleKeys = ref<string[]>([]);
  let hydrated = false;

  watch(
    columns,
    (nextColumns) => {
      const availableKeys = nextColumns.map((column) => column.key);
      visibleKeys.value = hydrated
        ? normalizeVisibleColumnKeys(visibleKeys.value, availableKeys, options.defaults, options.max)
        : readVisibleColumnKeys(options.storageKey, availableKeys, options.defaults, options.max);
      hydrated = true;
    },
    { immediate: true },
  );

  watch(
    visibleKeys,
    (keys) => {
      if (!hydrated || !storageAvailable()) return;
      const normalized = normalizeVisibleColumnKeys(
        keys,
        columns.value.map((column) => column.key),
        options.defaults,
        options.max,
      );
      if (normalized.join('|') !== keys.join('|')) {
        visibleKeys.value = normalized;
        return;
      }
      try {
        window.localStorage.setItem(options.storageKey, JSON.stringify(normalized));
      } catch {
        // Storage may be blocked by browser privacy settings. Column selection
        // remains valid for the current session.
      }
    },
    { deep: true },
  );

  return {
    columns,
    visibleKeys,
    isVisible: (key: string) => visibleKeys.value.includes(key),
    reset: () => {
      visibleKeys.value = normalizeVisibleColumnKeys(
        options.defaults,
        columns.value.map((column) => column.key),
        options.defaults,
        options.max,
      );
    },
  };
}
