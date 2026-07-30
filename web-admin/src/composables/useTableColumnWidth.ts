/**
 * 表格列宽记忆 —— 客户 Sheet Row 13: 「调好的列宽刷新就没了」.
 *
 * el-table 自带拖拽改列宽 (border + resizable), 但改完只活在内存里, 刷新即丢.
 * 这个 composable 把宽度按「租户 + 页面 + 列 prop」写进 localStorage, 下次进页面
 * 直接绑回 `:width`.
 *
 * 三条设计约束:
 *
 * 1. **不臆造宽度**. 没存过的列返回 `undefined`, 让调用方自己的 `width` / `min-width`
 *    继续生效 —— 记忆机制只在用户真的拖过之后才介入。
 * 2. **存取全程容错**. localStorage 可能被企业策略/隐私模式禁用 (读 `window.localStorage`
 *    这一步就会抛), 也可能存着别的版本写坏的内容。任何一环出问题都**静默退回默认宽度**,
 *    绝不把异常抛到渲染路径上 —— 列宽是锦上添花, 不该让整张表打不开。
 *    注意这不是「降级处理」: 默认宽度是本来就有的真实排版, 不是编造的业务数据。
 * 3. **按租户+页面隔离**. key 里带 factoryId 与页面 key, 换租户/换页面不串味。
 *
 * 用法:
 * ```ts
 * const { columnWidth, handleHeaderDragend, resetColumnWidths, hasStoredColumnWidths }
 *   = useTableColumnWidth({
 *     pageKey: 'production.batches.list',
 *     scope: factoryId,                       // ref / getter / 字符串都行
 *     defaults: { status: 120, createdAt: 190 },
 *   });
 * ```
 * ```vue
 * <el-table border @header-dragend="handleHeaderDragend">
 *   <el-table-column prop="status" :width="columnWidth('status')" />
 * </el-table>
 * ```
 */
import { computed, ref, toValue, watch, type ComputedRef, type MaybeRefOrGetter } from 'vue';

/** localStorage key 前缀 —— 换 schema 时改这里, 旧 key 自然被忽略。 */
export const TABLE_COLUMN_WIDTH_STORAGE_PREFIX = 'cretas_table_col_width';

/** 低于这个宽度的列点不中也读不了, 高于上限的多半是坏数据 —— 两端都当无效丢掉。 */
export const MIN_PERSISTED_COLUMN_WIDTH = 40;
export const MAX_PERSISTED_COLUMN_WIDTH = 2000;

/**
 * el-table `header-dragend` 回调里的列对象。
 * 只声明用得上的两个字段, 避免为了一个 prop 去依赖 element-plus 的内部类型。
 */
export interface ResizableColumnLike {
  property?: string;
  columnKey?: string;
}

/** localStorage 的最小子集 —— 测试可以注入假实现 (包括「会抛的实现」)。 */
export interface ColumnWidthStorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface UseTableColumnWidthOptions {
  /** 页面/表格维度, 全局唯一。建议 `模块.页面.表格`, 如 `production.batches.list`。 */
  pageKey: string;
  /** 租户维度 (factoryId)。缺省时归到匿名槽, 不会与任何具体租户混。 */
  scope?: MaybeRefOrGetter<string | null | undefined>;
  /** 各列默认宽度 (prop → px)。用户没拖过时 `columnWidth()` 返回这里的值。 */
  defaults?: Readonly<Record<string, number>>;
  /**
   * 存储实现。默认取 `window.localStorage`;
   * 显式传 `null` 表示「不持久化」(测试或 SSR)。
   */
  storage?: ColumnWidthStorageLike | null;
}

export interface UseTableColumnWidthReturn {
  /** 绑到 `<el-table-column :width>`。没记忆也没默认值时返回 undefined。 */
  columnWidth: (prop: string) => number | undefined;
  /** 绑到 `<el-table @header-dragend>`。 */
  handleHeaderDragend: (
    newWidth: number,
    oldWidth: number,
    column: ResizableColumnLike | null | undefined,
  ) => void;
  /** 清掉本表格的记忆, 回到默认宽度。 */
  resetColumnWidths: () => void;
  /** 是否存在记住的列宽 —— 用来决定要不要显示「恢复默认列宽」入口。 */
  hasStoredColumnWidths: ComputedRef<boolean>;
  /** 当前生效的 localStorage key (调试/测试用)。 */
  storageKey: ComputedRef<string>;
}

type ColumnWidthMap = Record<string, number>;

/**
 * 把任意来源的宽度收敛成一个可信的整数像素值。
 * 非有限数 / 越界 / 非 number 一律判为无效, 返回 null。
 */
function normalizeWidth(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null;
  const rounded = Math.round(value);
  if (rounded < MIN_PERSISTED_COLUMN_WIDTH) return null;
  if (rounded > MAX_PERSISTED_COLUMN_WIDTH) return null;
  return rounded;
}

/** 逐条过滤反序列化结果 —— 坏条目丢掉, 好条目留下, 不因为一条坏的就整表放弃。 */
function sanitizeWidthMap(parsed: unknown): ColumnWidthMap {
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
  const result: ColumnWidthMap = {};
  for (const [prop, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (!prop) continue;
    const width = normalizeWidth(value);
    if (width === null) continue;
    result[prop] = width;
  }
  return result;
}

/**
 * 拿存储实现。`window.localStorage` 这个 getter 本身在禁用场景会抛,
 * 所以取值也要包在 try 里。
 */
function resolveStorage(explicit: ColumnWidthStorageLike | null | undefined): ColumnWidthStorageLike | null {
  if (explicit !== undefined) return explicit;
  try {
    if (typeof window === 'undefined') return null;
    return window.localStorage;
  } catch {
    return null;
  }
}

export function useTableColumnWidth(options: UseTableColumnWidthOptions): UseTableColumnWidthReturn {
  const { pageKey, scope, defaults } = options;
  const storage = resolveStorage(options.storage);

  const storageKey = computed(() => {
    const tenant = toValue(scope);
    const tenantSlot = tenant ? String(tenant) : '_';
    return `${TABLE_COLUMN_WIDTH_STORAGE_PREFIX}:${tenantSlot}:${pageKey}`;
  });

  const widths = ref<ColumnWidthMap>({});

  function read(key: string): ColumnWidthMap {
    if (!storage) return {};
    let raw: string | null;
    try {
      raw = storage.getItem(key);
    } catch {
      return {};
    }
    if (!raw) return {};
    try {
      return sanitizeWidthMap(JSON.parse(raw) as unknown);
    } catch {
      // 内容损坏 (手改过 / 旧版本写的别的结构): 自愈式清掉, 本次回默认宽度。
      try {
        storage.removeItem(key);
      } catch {
        // 只读存储 —— 清不掉也无所谓, 下次照样按默认宽度渲染。
      }
      return {};
    }
  }

  function write(key: string, map: ColumnWidthMap): void {
    if (!storage) return;
    try {
      if (Object.keys(map).length === 0) {
        storage.removeItem(key);
        return;
      }
      storage.setItem(key, JSON.stringify(map));
    } catch {
      // 配额满 / 禁写: 本次会话内存里的宽度仍然生效, 只是刷新后记不住。
    }
  }

  watch(
    storageKey,
    (key) => {
      widths.value = read(key);
    },
    { immediate: true },
  );

  function columnWidth(prop: string): number | undefined {
    const stored = widths.value[prop];
    if (stored !== undefined) return stored;
    return defaults?.[prop];
  }

  function handleHeaderDragend(
    newWidth: number,
    _oldWidth: number,
    column: ResizableColumnLike | null | undefined,
  ): void {
    const prop = column?.property || column?.columnKey;
    if (!prop) return;
    const width = normalizeWidth(newWidth);
    // 拖出界的值不入库 —— 宁可不记, 也不要下次打开是一列废宽度。
    if (width === null) return;
    const next: ColumnWidthMap = { ...widths.value, [prop]: width };
    widths.value = next;
    write(storageKey.value, next);
  }

  function resetColumnWidths(): void {
    widths.value = {};
    write(storageKey.value, {});
  }

  const hasStoredColumnWidths = computed(() => Object.keys(widths.value).length > 0);

  return {
    columnWidth,
    handleHeaderDragend,
    resetColumnWidths,
    hasStoredColumnWidths,
    storageKey,
  };
}
