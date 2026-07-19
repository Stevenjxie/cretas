import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { describe, expect, it } from 'vitest';
import type { BomCopyCandidate } from '@/api/bom';
import BomCopySuggestionDialog from '../BomCopySuggestionDialog.vue';

const candidates: BomCopyCandidate[] = [
  {
    sourceProductTypeId: 'P400',
    sourceProductName: '干式熟成脆皮鸡 400g',
    sourceRecipeId: 'R400',
    sourceRecipeCode: 'BOM-400',
    sourceRecipeVersion: 2,
    sharedProcesses: [
      { workProcessId: 'ROLL', processName: '滚揉' },
      { workProcessId: 'ROAST', processName: '烤制' },
    ],
    bomItems: [
      { id: 11, materialTypeId: 'M1', materialName: '黄油鸡', materialCategory: 'RAW', standardQuantity: null, unit: '只' },
      { id: 12, materialTypeId: 'M2', materialName: '包装袋', materialCategory: 'PACKAGING', standardQuantity: 1, unit: '袋' },
    ],
    seasoningItems: [
      { id: 21, workProcessId: 'ROLL', workProcessName: '滚揉', materialTypeId: 'M3', name: '腌料', dosagePerKgG: 12, unit: 'g' },
    ],
    processInjectionConfigs: [
      { id: 31, workProcessId: 'ROLL', workProcessName: '滚揉', injectionAmountKg: 2 },
    ],
  },
];

function mountDialog() {
  return mount(BomCopySuggestionDialog, {
    props: {
      modelValue: true,
      targetProductName: '干式熟成脆皮鸡 350g',
      targetProductTypeId: 'P350',
      candidates,
    },
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        transition: false,
        ElDialog: {
          props: ['modelValue', 'title'],
          emits: ['update:modelValue'],
          template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>',
        },
      },
    },
  });
}

describe('BomCopySuggestionDialog', () => {
  it('selects all eligible rule groups by default and emits an explicit draft-copy payload', async () => {
    const wrapper = mountDialog();
    await flushPromises();

    expect(wrapper.text()).toContain('共享 2 道工序');
    expect(wrapper.text()).toContain('数量不会按规格自动缩放');
    await wrapper.get('[data-testid="copy-selected-rules"]').trigger('click');

    expect(wrapper.emitted('copy')?.[0]).toEqual([{
      targetProductTypeId: 'P350',
      sourceRecipeId: 'R400',
      recipeItemIds: [11, 12],
      seasoningItemIds: [21],
      processInjectionConfigIds: [31],
    }]);
  });

  it('supports deselecting an individual rule before copying', async () => {
    const wrapper = mountDialog();
    await flushPromises();

    await wrapper.get('[data-testid="copy-bom-item-12"] input').setValue(false);
    await wrapper.get('[data-testid="copy-selected-rules"]').trigger('click');

    expect(wrapper.emitted('copy')?.[0]?.[0]).toMatchObject({
      recipeItemIds: [11],
      seasoningItemIds: [21],
    });
  });

  it('offers a separate blank-create action without submitting a copy', async () => {
    const wrapper = mountDialog();
    const blankButton = wrapper.findAll('button').find((button) => button.text().includes('跳过，空白创建'));
    await blankButton?.trigger('click');

    expect(wrapper.emitted('blank-create')).toHaveLength(1);
    expect(wrapper.emitted('copy')).toBeUndefined();
  });
});
