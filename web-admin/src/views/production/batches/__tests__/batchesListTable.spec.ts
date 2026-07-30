/**
 * 生产批次列表的表头与列宽 —— 两条客户 Sheet 反馈:
 *
 *  A. 「每个表头套了独立边框盒子」: 盒子感来自 加深的表头底色 + 逐格右边框 +
 *     每格一个独立控件三层叠加。出成率总览已在 PR #1991 拆掉三层, 这张表是同一套
 *     CSS 的另一份拷贝, 现在对齐。排序入口保留, 且必须带 aria-label ——
 *     el-table 原生 sortable 只给一对没有名字的箭头, 屏幕阅读器读不出按哪列排。
 *  B. Row 13「调好的列宽刷新就没了」: 列宽落 localStorage, 按租户+页面隔离。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { nextTick } from 'vue';
import ElementPlus from 'element-plus';

const apiGet = vi.fn();

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canViewPrice: true }),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: vi.fn(),
}));
vi.mock('@/api/printApi', () => ({ safePrint: vi.fn() }));

import BatchesList from '../list.vue';

const STORAGE_KEY_F006 = 'cretas_table_col_width:F006:production.batches.list';

/** 模板里各列的书写顺序 —— <col> 与它一一对应 (末列是「操作」, 不参与记忆)。 */
const COLUMN_ORDER = [
  'batchNumber', 'productTypeName', 'plannedQuantity', 'actualQuantity',
  'sourceProcess', 'status', 'supervisorName', 'createdAt', 'actions',
] as const;

const DEFAULT_RENDERED_WIDTHS: Record<string, string> = {
  batchNumber: '280', productTypeName: '260', plannedQuantity: '120', actualQuantity: '120',
  sourceProcess: '160', status: '120', supervisorName: '120', createdAt: '190', actions: '220',
};

function mountList() {
  return mount(BatchesList, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        transition: false,
        ConceptDisambiguationAlert: true,
        AiEntryDrawer: true,
        RowActionMenu: true,
      },
    },
  });
}

function renderedWidths(wrapper: ReturnType<typeof mountList>): Record<string, string | undefined> {
  const cols = wrapper.findAll('col').slice(0, COLUMN_ORDER.length);
  const result: Record<string, string | undefined> = {};
  COLUMN_ORDER.forEach((prop, index) => {
    result[prop] = cols[index]?.attributes('width');
  });
  return result;
}

describe('生产批次列表 — 表头与列宽', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiGet.mockResolvedValue({
      success: true,
      data: {
        content: [{
          id: 'B1', batchNumber: 'PB-20260730-AAA', productTypeName: '卤猪蹄',
          plannedQuantity: 100, actualQuantity: 90, status: 'IN_PROGRESS',
          supervisorName: '张权', createdAt: '2026-07-30T08:00:00',
        }],
        totalElements: 1,
      },
    });
    localStorage.clear();
  });

  // ── A. 表头 ───────────────────────────────────────────────────────

  it('每个可排序表头都是一个有名字的按钮 (屏幕阅读器读得出按哪列排)', async () => {
    const wrapper = mountList();
    await flushPromises();

    const sortButtons = wrapper.findAll('button.batch-sort-trigger');
    // 计划/批次号、计划数量、实际数量、创建时间 —— 四列可排序
    expect(sortButtons.length).toBe(4);
    expect(sortButtons.every((button) => button.attributes('type') === 'button')).toBe(true);

    const labels = sortButtons.map((button) => button.attributes('aria-label'));
    expect(labels).toEqual([
      '按计划/批次号排序，连续操作可切换升序和降序',
      '按计划数量排序，连续操作可切换升序和降序',
      '按实际数量排序，连续操作可切换升序和降序',
      '按创建时间排序，连续操作可切换升序和降序',
    ]);
  });

  it('表头不再叠加三层视觉外壳 (客户: 每个表头套了独立边框盒子)', () => {
    const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');
    expect(source).not.toContain('--el-table-header-bg-color');
    expect(source).not.toContain('--el-table-header-text-color');
    expect(source).not.toContain('border-right-color');
    // 但排序入口本身要留着 —— 去掉盒子不等于去掉入口
    expect(source).toContain(`class: 'batch-sort-trigger'`);
  });

  it('生产计划归组行整行加粗，而不只加粗第一格标题', async () => {
    apiGet.mockResolvedValue({
      success: true,
      data: {
        content: [{
          id: 'WIP-1',
          batchNumber: 'WIP-20260731-01',
          batchType: 'CLERK_WIP',
          sourcePlanId: 'PLAN-1',
          sourcePlanNumber: 'PP-20260731-01',
          productTypeName: '卤猪蹄',
          plannedQuantity: 100,
          actualQuantity: 90,
          status: 'COMPLETED',
        }],
        totalElements: 1,
      },
    });

    const wrapper = mountList();
    await flushPromises();

    expect(wrapper.find('tr.plan-group-row').exists()).toBe(true);
    const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');
    expect(source).toContain(':row-class-name="batchRowClassName"');
    expect(source).toContain('tr.plan-group-row > td.el-table__cell .cell');
    expect(source).toContain('font-weight: 600');
  });

  // ── B. 列宽记忆 ───────────────────────────────────────────────────

  it('没存过时每一列都保持改造前的默认宽度', async () => {
    const wrapper = mountList();
    await flushPromises();

    expect(renderedWidths(wrapper)).toEqual(DEFAULT_RENDERED_WIDTHS);
    expect(wrapper.text()).not.toContain('恢复默认列宽');
  });

  it('存过的列宽在挂载时读回来, 其余列不受影响', async () => {
    localStorage.setItem(STORAGE_KEY_F006, JSON.stringify({ status: 333, batchNumber: 444 }));

    const wrapper = mountList();
    await flushPromises();

    const widths = renderedWidths(wrapper);
    expect(widths.status).toBe('333');
    expect(widths.batchNumber).toBe('444');
    expect(widths.createdAt).toBe(DEFAULT_RENDERED_WIDTHS.createdAt);
    expect(wrapper.text()).toContain('恢复默认列宽');
  });

  it('拖完表头就落库在本租户的 key 下, 重新挂载还在', async () => {
    const wrapper = mountList();
    await flushPromises();

    wrapper.findComponent({ name: 'ElTable' }).vm.$emit('header-dragend', 260, 120, { property: 'status' });
    await nextTick();

    expect(JSON.parse(localStorage.getItem(STORAGE_KEY_F006) || 'null')).toEqual({ status: 260 });

    wrapper.unmount();
    const reopened = mountList();
    await flushPromises();
    expect(renderedWidths(reopened).status).toBe('260');
  });

  it('没有 prop 的「来源工序」列靠 column-key 也能记住', async () => {
    const wrapper = mountList();
    await flushPromises();

    wrapper.findComponent({ name: 'ElTable' }).vm.$emit('header-dragend', 300, 160, { columnKey: 'sourceProcess' });
    await nextTick();

    expect(renderedWidths(wrapper).sourceProcess).toBe('300');
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY_F006) || 'null')).toEqual({ sourceProcess: 300 });
  });

  it('存储内容损坏时照常渲染默认宽度, 不抛错', async () => {
    localStorage.setItem(STORAGE_KEY_F006, 'not json at all {');

    const wrapper = mountList();
    await flushPromises();

    expect(renderedWidths(wrapper)).toEqual(DEFAULT_RENDERED_WIDTHS);
  });
});
