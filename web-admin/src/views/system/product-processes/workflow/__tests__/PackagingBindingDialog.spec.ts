import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import PackagingBindingDialog, { type PackagingMaterialOption } from '../PackagingBindingDialog.vue';
import type { BomItemSubstituteView, BomRecipeItemView } from '@/api/bom';

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

const BOXED_MATERIAL: PackagingMaterialOption = {
  id: 'PK-BOX', name: '塑料桶 20L', category: '包材', primaryCode: 'BOX01', unit: 'pcs', quantityUnit: 'pcs',
};
const BAG_MATERIAL: PackagingMaterialOption = {
  id: 'PK-BAG', name: '内袋', category: '包材', primaryCode: 'BOX01', unit: 'pcs', quantityUnit: 'pcs',
};
const LABEL_MATERIAL_KG: PackagingMaterialOption = {
  id: 'PK-LABEL', name: '称重标签', category: '包材', primaryCode: 'BOX01', unit: 'kg', quantityUnit: 'kg',
};

function mountDialog(overrides: Record<string, unknown> = {}) {
  return mount(PackagingBindingDialog, {
    props: {
      modelValue: true,
      factoryId: 'F006',
      recipeId: 'R1',
      outputName: '鸭油',
      baseUnit: 'kg',
      row: null,
      materials: [BOXED_MATERIAL, BAG_MATERIAL, LABEL_MATERIAL_KG],
      substituteRelations: [],
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

function selectMaterial(wrapper: ReturnType<typeof mountDialog>, materialId: string) {
  wrapper.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('update:modelValue', materialId);
}

function setQuantity(wrapper: ReturnType<typeof mountDialog>, value: number | null) {
  wrapper.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:modelValue', value);
}

async function clickSave(wrapper: ReturnType<typeof mountDialog>) {
  await wrapper.findAll('button').find((button) => button.text().includes('保存'))?.trigger('click');
  await flushPromises();
}

function substitute(overrides: Partial<BomItemSubstituteView>): BomItemSubstituteView {
  return {
    id: 'sub-1',
    recipeId: 'R1',
    parentKind: 'RECIPE_ITEM',
    parentRecipeItemId: 42,
    parentMaterialTypeId: 'PK-BOX',
    materialCategory: 'PACKAGING',
    substituteMaterialTypeId: 'PK-BAG',
    conversionFactor: 1,
    ...overrides,
  };
}

describe('包材编辑弹窗', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    addItem.mockResolvedValue({ success: true, data: { id: 99 } });
    updateItem.mockResolvedValue({ success: true, data: { id: 42 } });
  });

  it('用量输入框的分母来自传入的 baseUnit, 不是写死的「盒」', () => {
    // 按重量卖的副产品 baseUnit 是 kg, 写死会算错
    const wrapper = mountDialog({ baseUnit: 'kg' });
    expect(wrapper.text()).toContain('kg');
    expect(wrapper.text()).not.toContain('盒');
  });

  it('用量为空或非正数时不允许保存', async () => {
    // 禁止降级: 不能默默存 0
    const wrapper = mountDialog();
    selectMaterial(wrapper, 'PK-BOX');
    await flushPromises();
    await clickSave(wrapper);
    expect(addItem).not.toHaveBeenCalled();
    expect(elMessage.warning).toHaveBeenCalled();

    setQuantity(wrapper, 0);
    await flushPromises();
    await clickSave(wrapper);
    expect(addItem).not.toHaveBeenCalled();

    setQuantity(wrapper, -1);
    await flushPromises();
    await clickSave(wrapper);
    expect(addItem).not.toHaveBeenCalled();
  });

  it('跨单位替代物料未填等价系数时不允许保存', async () => {
    // 系统不猜换算关系
    const wrapper = mountDialog();
    selectMaterial(wrapper, 'PK-BOX');
    setQuantity(wrapper, 2);
    await flushPromises();
    wrapper.findAllComponents({ name: 'ElSelect' })[1].vm.$emit('update:modelValue', ['PK-LABEL']);
    await flushPromises();
    await clickSave(wrapper);
    expect(addItem).not.toHaveBeenCalled();
    expect(elMessage.warning).toHaveBeenCalled();

    wrapper.get('[data-testid="packaging-substitute-factor-PK-LABEL"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:modelValue', 3);
    await flushPromises();
    await clickSave(wrapper);
    expect(addItem).toHaveBeenCalledWith('F006', 'R1', expect.objectContaining({
      substitutes: [{ materialTypeId: 'PK-LABEL', conversionFactor: 3 }],
    }));
  });

  it('同单位替代默认 1:1 且只读', async () => {
    const wrapper = mountDialog();
    selectMaterial(wrapper, 'PK-BOX');
    setQuantity(wrapper, 2);
    await flushPromises();
    wrapper.findAllComponents({ name: 'ElSelect' })[1].vm.$emit('update:modelValue', ['PK-BAG']);
    await flushPromises();
    expect(wrapper.find('[data-testid="packaging-substitute-factor-PK-BAG"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('同单位默认 1:1');

    await clickSave(wrapper);
    expect(addItem).toHaveBeenCalledWith('F006', 'R1', expect.objectContaining({
      substitutes: [{ materialTypeId: 'PK-BAG', conversionFactor: null }],
    }));
  });

  it('编辑既有行时回填的是落库字段而不是表单同名字段', async () => {
    // Phase 1 事故: 表单叫 naturalQuantity, 落库在 standardQuantity。
    // row.naturalQuantity 故意写一个错误值, row.standardQuantity 才是权威值 —— 回填必须读后者。
    const row: Partial<BomRecipeItemView> = {
      id: 42,
      materialTypeId: 'PK-BOX',
      materialName: '塑料桶 20L',
      standardQuantity: 5,
      naturalQuantity: 999,
      unit: 'pcs',
      isOptional: false,
      remark: '备注文本',
    };
    const wrapper = mountDialog({ row });
    await flushPromises();
    expect(wrapper.findComponent({ name: 'ElInputNumber' }).props('modelValue')).toBe(5);

    await clickSave(wrapper);
    expect(updateItem).toHaveBeenCalledWith('F006', 42, expect.objectContaining({
      standardQuantity: 5,
    }));
  });

  it('编辑已有行的替代物料关系由父组件按行过滤后传入并回填', async () => {
    const row: Partial<BomRecipeItemView> = {
      id: 42, materialTypeId: 'PK-BOX', materialName: '塑料桶 20L', standardQuantity: 5, unit: 'pcs',
    };
    const wrapper = mountDialog({ row, substituteRelations: [substitute({ conversionFactor: 1 })] });
    await flushPromises();
    expect(wrapper.text()).toContain('内袋');
  });

  it('保存失败时不 emit saved, 并把后端 message 原样显示', async () => {
    // 禁止降级: 不吞错误、不显示「操作失败」这种 generic 文案
    addItem.mockRejectedValue({ message: '包材档案已停用，无法引用' });
    const wrapper = mountDialog();
    selectMaterial(wrapper, 'PK-BOX');
    setQuantity(wrapper, 2);
    await flushPromises();
    await clickSave(wrapper);

    expect(wrapper.emitted('saved')).toBeUndefined();
    expect(elMessage.error).toHaveBeenCalledWith('包材档案已停用，无法引用');
  });
});
