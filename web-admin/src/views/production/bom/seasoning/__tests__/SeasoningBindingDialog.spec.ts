import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SeasoningBindingDialog from '../SeasoningBindingDialog.vue';

const createBinding = vi.fn();
const resolveRoute = vi.fn(() => ({ href: '/warehouse/material-types?keyword=辣椒粉' }));

vi.mock('@/api/bom', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/bom')>();
  return {
    ...original,
    bomSeasoningApi: {
      ...original.bomSeasoningApi,
      createBinding: (...args: unknown[]) => createBinding(...args),
    },
  };
});
vi.mock('vue-router', () => ({
  useRouter: () => ({ resolve: resolveRoute }),
  useRoute: () => ({ fullPath: '/production/bom?productTypeId=P1' }),
}));

const process = {
  workProcessId: 'ROLL', processOrder: 1, processName: '滚揉', bindings: [],
};

function mountDialog(price: number | null) {
  return mount(SeasoningBindingDialog, {
    props: {
      modelValue: true,
      factoryId: 'F006',
      recipeId: 'R1',
      process,
      binding: null,
      materials: [{ id: 'M1', name: '辣椒粉', unit: 'g', movingAvgPrice: price }],
      revision: 4,
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

async function fillRequiredFields(wrapper: ReturnType<typeof mountDialog>, value: number, unit: 'g' | 'kg') {
  const selects = wrapper.findAllComponents({ name: 'ElSelect' });
  selects[0].vm.$emit('update:modelValue', 'M1');
  selects[1].vm.$emit('update:modelValue', unit);
  wrapper.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:modelValue', value);
  await flushPromises();
}

describe('SeasoningBindingDialog', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uses the natural sentence and converts kg input back to g/kg for the API', async () => {
    createBinding.mockResolvedValue({ success: true, data: {} });
    const wrapper = mountDialog(18);
    await fillRequiredFields(wrapper, 1.5, 'kg');

    expect(wrapper.get('[data-testid="seasoning-dosage-sentence"]').text())
      .toContain('每生产 1 kg 本工序半成品，需要投入');
    await wrapper.findAll('button').find((button) => button.text().includes('保存到本工序'))?.trigger('click');
    await flushPromises();

    expect(createBinding).toHaveBeenCalledWith('F006', 'R1', 'ROLL', expect.objectContaining({
      dosagePerKgG: 1500,
    }));
  });

  it('keeps the form open and offers a price shortcut when moving average price is missing', async () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    const wrapper = mountDialog(null);
    await fillRequiredFields(wrapper, 5, 'g');

    expect(wrapper.get('[data-testid="configure-seasoning-price"]').exists()).toBe(true);
    await wrapper.findAll('button').find((button) => button.text().includes('保存到本工序'))?.trigger('click');
    expect(createBinding).not.toHaveBeenCalled();
    expect(wrapper.emitted('update:modelValue')).toBeUndefined();

    await wrapper.get('[data-testid="configure-seasoning-price"]').trigger('click');
    expect(open).toHaveBeenCalledWith('/warehouse/material-types?keyword=辣椒粉', '_blank', 'noopener');
    open.mockRestore();
  });
});
