import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ByproductBindingDialog, { type ByproductMaterialOption } from '../ByproductBindingDialog.vue';
import type { BomRecipeItemView } from '@/api/bom';

const addItem = vi.fn();
const updateItem = vi.fn();

vi.mock('@/api/bom', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/bom')>();
  return {
    ...original,
    bomRecipeApi: {
      ...original.bomRecipeApi,
      addItem: (...args: unknown[]) => addItem(...args),
      updateItem: (...args: unknown[]) => updateItem(...args),
    },
  };
});

const elMessage = vi.hoisted(() => ({ error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() }));
vi.mock('element-plus', async (importOriginal) => {
  const original = await importOriginal<typeof import('element-plus')>();
  return { ...original, ElMessage: elMessage };
});

const CHICKEN_FRAME: ByproductMaterialOption = { id: 'M-FRAME', name: '鸡架', unit: 'kg' };

function mountDialog(overrides: Record<string, unknown> = {}) {
  return mount(ByproductBindingDialog, {
    props: {
      modelValue: true,
      factoryId: 'F006',
      recipeId: 'R1',
      outputName: '干式熟成鸡 400g',
      baseUnit: '袋',
      row: null,
      materials: [CHICKEN_FRAME],
      ...overrides,
    },
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        transition: false,
        ElDialog: {
          props: ['modelValue', 'title'],
          template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>',
        },
      },
    },
  });
}

beforeEach(() => {
  addItem.mockReset();
  updateItem.mockReset();
  elMessage.error.mockReset();
  elMessage.warning.mockReset();
  elMessage.success.mockReset();
  addItem.mockResolvedValue({ success: true });
  updateItem.mockResolvedValue({ success: true });
});

describe('ByproductBindingDialog', () => {
  it('新建: 选物料 + 填产出量 → 以 materialCategory=BYPRODUCT 写入', async () => {
    const wrapper = mountDialog();
    wrapper.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('update:modelValue', 'M-FRAME');
    wrapper.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:modelValue', 0.12);
    await flushPromises();

    await wrapper.find('[data-testid="byp-save"]').trigger('click');
    await flushPromises();

    expect(addItem).toHaveBeenCalledTimes(1);
    const [factoryId, recipeId, payload] = addItem.mock.calls[0];
    expect(factoryId).toBe('F006');
    expect(recipeId).toBe('R1');
    expect(payload).toMatchObject({
      materialTypeId: 'M-FRAME',
      materialCategory: 'BYPRODUCT',
      standardQuantity: 0.12,
      unit: 'kg',
    });
  });

  it('产出量 0 不是「未声明」而是错误声明, 不放行', async () => {
    const wrapper = mountDialog();
    wrapper.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('update:modelValue', 'M-FRAME');
    wrapper.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:modelValue', 0);
    await flushPromises();

    await wrapper.find('[data-testid="byp-save"]').trigger('click');
    await flushPromises();

    expect(addItem).not.toHaveBeenCalled();
    expect(elMessage.warning).toHaveBeenCalled();
  });

  /**
   * 2026-08-06 实测: 六膳门 128 个在用物料里勾了「副产」的是 0 个 —— 修好物料源之后,
   * 客户看到的第一屏就是这个空态。空下拉必须自己解释, 否则用户只会以为系统坏了。
   */
  it('档案里没有副产标记时: 给出解释与去处, 而不是一个空下拉', () => {
    const wrapper = mountDialog({ materials: [] });
    const hint = wrapper.find('[data-testid="byp-empty-hint"]');
    expect(hint.exists()).toBe(true);
    expect(hint.text()).toContain('原料类型字典');
    expect(wrapper.find('[data-testid="byp-material-select"]').exists()).toBe(false);
  });

  it('档案为空时保存按钮禁用 —— 不让用户点了才知道点不动', () => {
    const wrapper = mountDialog({ materials: [] });
    expect(wrapper.find('[data-testid="byp-save"]').attributes('disabled')).toBeDefined();
  });

  it('已有行即使档案侧标记被取消, 仍要能打开并看到那一行 (别把既有数据藏起来)', () => {
    const row = {
      id: 9, materialTypeId: 'M-FRAME', materialCategory: 'BYPRODUCT', standardQuantity: 0.2,
    } as unknown as BomRecipeItemView;
    const wrapper = mountDialog({ materials: [], row });
    expect(wrapper.find('[data-testid="byp-material-select"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="byp-save"]').attributes('disabled')).toBeUndefined();
  });
});
