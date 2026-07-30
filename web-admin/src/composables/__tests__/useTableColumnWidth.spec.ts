/**
 * 列宽记忆 (客户 Sheet Row 13: 「调好的列宽刷新就没了」)。
 *
 * 这组测试钉三件事:
 *   1. 拖过的宽度确实落进 localStorage, 重新挂载还能读回来 —— 这是客户抱怨的那条;
 *   2. 租户 / 页面之间互不串味;
 *   3. 存储禁用 / 内容损坏 / 值离谱时**静默回默认宽度**, 不抛异常。
 *      列宽是锦上添花, 不能让它把整张表打不开。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ref, nextTick } from 'vue';
import {
  useTableColumnWidth,
  TABLE_COLUMN_WIDTH_STORAGE_PREFIX,
  MIN_PERSISTED_COLUMN_WIDTH,
  MAX_PERSISTED_COLUMN_WIDTH,
  type ColumnWidthStorageLike,
} from '../useTableColumnWidth';

const DEFAULTS = { status: 120, createdAt: 190 } as const;

function keyFor(scope: string, pageKey: string): string {
  return `${TABLE_COLUMN_WIDTH_STORAGE_PREFIX}:${scope}:${pageKey}`;
}

describe('useTableColumnWidth', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('没拖过的列用调用方给的默认宽度, 没默认值的列返回 undefined', () => {
    const table = useTableColumnWidth({ pageKey: 'p.a', scope: 'F006', defaults: DEFAULTS });

    expect(table.columnWidth('status')).toBe(120);
    expect(table.columnWidth('createdAt')).toBe(190);
    // batchNumber 用 min-width 自适应, 这里必须让位给模板上的 min-width
    expect(table.columnWidth('batchNumber')).toBeUndefined();
    expect(table.hasStoredColumnWidths.value).toBe(false);
  });

  it('拖动表头后宽度落库, 下次进页面读回来 (客户原始抱怨)', () => {
    const first = useTableColumnWidth({ pageKey: 'p.a', scope: 'F006', defaults: DEFAULTS });
    first.handleHeaderDragend(260, 120, { property: 'status' });

    expect(first.columnWidth('status')).toBe(260);
    expect(first.hasStoredColumnWidths.value).toBe(true);
    expect(JSON.parse(localStorage.getItem(keyFor('F006', 'p.a')) || 'null')).toEqual({ status: 260 });

    // 模拟刷新: 全新的 composable 实例, 只靠 localStorage 恢复
    const afterReload = useTableColumnWidth({ pageKey: 'p.a', scope: 'F006', defaults: DEFAULTS });
    expect(afterReload.columnWidth('status')).toBe(260);
    // 没拖过的列不受影响
    expect(afterReload.columnWidth('createdAt')).toBe(190);
  });

  it('没有 property 的列退回 columnKey, 两者都没有就不记', () => {
    const table = useTableColumnWidth({ pageKey: 'p.a', scope: 'F006' });

    table.handleHeaderDragend(300, 160, { columnKey: 'sourceProcess' });
    expect(table.columnWidth('sourceProcess')).toBe(300);

    table.handleHeaderDragend(300, 160, {});
    table.handleHeaderDragend(300, 160, null);
    expect(JSON.parse(localStorage.getItem(keyFor('F006', 'p.a')) || 'null')).toEqual({ sourceProcess: 300 });
  });

  it('不同租户 / 不同页面各存各的, 不串味', () => {
    useTableColumnWidth({ pageKey: 'p.a', scope: 'F006' }).handleHeaderDragend(260, 120, { property: 'status' });
    useTableColumnWidth({ pageKey: 'p.a', scope: 'LIUSHANMEN' }).handleHeaderDragend(400, 120, { property: 'status' });
    useTableColumnWidth({ pageKey: 'p.b', scope: 'F006' }).handleHeaderDragend(500, 120, { property: 'status' });

    expect(useTableColumnWidth({ pageKey: 'p.a', scope: 'F006' }).columnWidth('status')).toBe(260);
    expect(useTableColumnWidth({ pageKey: 'p.a', scope: 'LIUSHANMEN' }).columnWidth('status')).toBe(400);
    expect(useTableColumnWidth({ pageKey: 'p.b', scope: 'F006' }).columnWidth('status')).toBe(500);
  });

  it('租户切换时重新读该租户的记忆, 而不是把上一个租户的宽度带过去', async () => {
    localStorage.setItem(keyFor('F006', 'p.a'), JSON.stringify({ status: 260 }));
    localStorage.setItem(keyFor('LIUSHANMEN', 'p.a'), JSON.stringify({ status: 400 }));

    const scope = ref('F006');
    const table = useTableColumnWidth({ pageKey: 'p.a', scope, defaults: DEFAULTS });
    expect(table.columnWidth('status')).toBe(260);

    scope.value = 'LIUSHANMEN';
    await nextTick();
    expect(table.columnWidth('status')).toBe(400);
  });

  it('没有租户时归到匿名槽, 不会写进某个具体租户的 key', () => {
    const table = useTableColumnWidth({ pageKey: 'p.a', scope: undefined });
    table.handleHeaderDragend(260, 120, { property: 'status' });

    expect(table.storageKey.value).toBe(keyFor('_', 'p.a'));
    expect(localStorage.getItem(keyFor('F006', 'p.a'))).toBeNull();
  });

  it('恢复默认列宽会同时清掉内存与 localStorage', () => {
    const table = useTableColumnWidth({ pageKey: 'p.a', scope: 'F006', defaults: DEFAULTS });
    table.handleHeaderDragend(260, 120, { property: 'status' });
    expect(localStorage.getItem(keyFor('F006', 'p.a'))).not.toBeNull();

    table.resetColumnWidths();

    expect(table.columnWidth('status')).toBe(120);
    expect(table.hasStoredColumnWidths.value).toBe(false);
    expect(localStorage.getItem(keyFor('F006', 'p.a'))).toBeNull();
  });

  // ── 容错: 三种坏情况都必须静默回默认宽度 ─────────────────────────────

  it('存的内容不是合法 JSON 时回默认宽度, 并把坏条目清掉', () => {
    localStorage.setItem(keyFor('F006', 'p.a'), '{不是 JSON');

    const table = useTableColumnWidth({ pageKey: 'p.a', scope: 'F006', defaults: DEFAULTS });

    expect(table.columnWidth('status')).toBe(120);
    expect(table.hasStoredColumnWidths.value).toBe(false);
    expect(localStorage.getItem(keyFor('F006', 'p.a'))).toBeNull();
  });

  it('JSON 合法但结构/数值不可信时, 逐条丢掉坏的, 留下好的', () => {
    localStorage.setItem(keyFor('F006', 'p.a'), JSON.stringify({
      status: 260,                                  // 好
      createdAt: '190',                             // 字符串 — 丢
      supervisorName: Number.NaN,                   // NaN 序列化成 null — 丢
      plannedQuantity: MIN_PERSISTED_COLUMN_WIDTH - 1,  // 太窄 — 丢
      actualQuantity: MAX_PERSISTED_COLUMN_WIDTH + 1,   // 太宽 — 丢
    }));

    const table = useTableColumnWidth({ pageKey: 'p.a', scope: 'F006', defaults: DEFAULTS });

    expect(table.columnWidth('status')).toBe(260);
    expect(table.columnWidth('createdAt')).toBe(190);
    expect(table.columnWidth('plannedQuantity')).toBeUndefined();
    expect(table.columnWidth('actualQuantity')).toBeUndefined();
  });

  it('存的是数组/裸值这种完全不对的结构时整条忽略', () => {
    localStorage.setItem(keyFor('F006', 'p.a'), JSON.stringify([1, 2, 3]));
    expect(useTableColumnWidth({ pageKey: 'p.a', scope: 'F006', defaults: DEFAULTS }).columnWidth('status')).toBe(120);

    localStorage.setItem(keyFor('F006', 'p.b'), JSON.stringify(42));
    expect(useTableColumnWidth({ pageKey: 'p.b', scope: 'F006', defaults: DEFAULTS }).columnWidth('status')).toBe(120);
  });

  it('拖出界的宽度不入库 —— 宁可不记也不留一列废宽度', () => {
    const table = useTableColumnWidth({ pageKey: 'p.a', scope: 'F006', defaults: DEFAULTS });

    table.handleHeaderDragend(5, 120, { property: 'status' });
    table.handleHeaderDragend(Number.NaN, 120, { property: 'createdAt' });

    expect(table.columnWidth('status')).toBe(120);
    expect(table.columnWidth('createdAt')).toBe(190);
    expect(localStorage.getItem(keyFor('F006', 'p.a'))).toBeNull();
  });

  it('localStorage 被禁用 (每个方法都抛) 时不抛异常, 只是记不住', () => {
    const throwing: ColumnWidthStorageLike = {
      getItem: vi.fn(() => { throw new DOMException('denied', 'SecurityError'); }),
      setItem: vi.fn(() => { throw new DOMException('denied', 'SecurityError'); }),
      removeItem: vi.fn(() => { throw new DOMException('denied', 'SecurityError'); }),
    };

    const table = useTableColumnWidth({
      pageKey: 'p.a', scope: 'F006', defaults: DEFAULTS, storage: throwing,
    });

    expect(table.columnWidth('status')).toBe(120);
    expect(() => table.handleHeaderDragend(260, 120, { property: 'status' })).not.toThrow();
    // 本次会话内仍然生效, 只是刷新后记不住
    expect(table.columnWidth('status')).toBe(260);
    expect(() => table.resetColumnWidths()).not.toThrow();
    expect(table.columnWidth('status')).toBe(120);
  });

  it('写入配额满时不抛异常 (setItem 抛, 读没问题)', () => {
    const quotaFull: ColumnWidthStorageLike = {
      getItem: () => null,
      setItem: () => { throw new DOMException('quota', 'QuotaExceededError'); },
      removeItem: () => { /* noop */ },
    };

    const table = useTableColumnWidth({
      pageKey: 'p.a', scope: 'F006', defaults: DEFAULTS, storage: quotaFull,
    });

    expect(() => table.handleHeaderDragend(260, 120, { property: 'status' })).not.toThrow();
    expect(table.columnWidth('status')).toBe(260);
  });

  it('显式传 storage: null 表示不持久化, 也不去碰 localStorage', () => {
    const table = useTableColumnWidth({ pageKey: 'p.a', scope: 'F006', defaults: DEFAULTS, storage: null });
    table.handleHeaderDragend(260, 120, { property: 'status' });

    expect(table.columnWidth('status')).toBe(260);
    expect(localStorage.getItem(keyFor('F006', 'p.a'))).toBeNull();
  });
});
