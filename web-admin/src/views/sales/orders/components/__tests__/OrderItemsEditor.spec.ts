// web-admin/src/views/sales/orders/components/__tests__/OrderItemsEditor.spec.ts
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import OrderItemsEditor from '../OrderItemsEditor.vue';

const products = [
  { id: 'P1', name: '卤牛腱', unit: 'kg' },
  { id: 'P2', name: '卤猪蹄', unit: '份' },
];
const stubs = {
  'el-table': { template: '<table><slot/></table>' },
  'el-table-column': { template: '<td><slot :row="$attrs.row"/></td>' },
  'el-select': { props: ['modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value); $emit(\'change\',$event.target.value)"><slot/></select>' },
  'el-option': { props: ['value','label'], template: '<option :value="value">{{label}}</option>' },
  'el-input-number': { props: ['modelValue'], template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))"/>' },
  'el-input': { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)"/>' },
  'el-button': { template: '<button @click="$emit(\'click\')"><slot/></button>' },
};

describe('OrderItemsEditor', () => {
  it('渲染传入的行 + 增行', async () => {
    const wrapper = mount(OrderItemsEditor, {
      props: { items: [{ productTypeId: 'P1', quantity: 10, unit: 'kg', unitPrice: 5, taxRate: 13 }], products },
      global: { stubs },
    });
    await wrapper.find('button').trigger('click');
    const emitted = wrapper.emitted('update:items');
    expect(emitted).toBeTruthy();
    const last = emitted![emitted!.length - 1][0] as unknown[];
    expect(last.length).toBe(2);
  });

  it('产品为空 → 渲染默认插槽内容', () => {
    const wrapper = mount(OrderItemsEditor, {
      props: { items: [], products: [] },
      slots: { 'empty-products': '<div class="np">无产品引导</div>' },
      global: { stubs },
    });
    expect(wrapper.find('.np').exists()).toBe(true);
  });
  it('supports searching product options by code and category label', () => {
    const wrapper = mount(OrderItemsEditor, {
      props: {
        items: [{ productTypeId: '', quantity: 0, unit: 'kg', unitPrice: 0, taxRate: 13 }],
        products: [
          { id: 'P1', code: 'YL0001', name: 'Raw beef', unit: 'kg', productCategory: 'RAW_MATERIAL' },
          { id: 'P2', code: 'KH0001', name: 'Customer supplied pork', unit: 'kg', productCategory: 'CUSTOMER_MATERIAL' },
        ],
      },
      global: { stubs },
    });

    const vm = wrapper.vm as unknown as {
      productOptionLabel: (product: Record<string, unknown>) => string;
    };
    const rawLabel = vm.productOptionLabel({ code: 'YL0001', name: 'Raw beef', productCategory: 'RAW_MATERIAL' });
    const customerMaterialLabel = vm.productOptionLabel({
      code: 'KH0001',
      name: 'Customer supplied pork',
      productCategory: 'CUSTOMER_MATERIAL',
    });

    expect(rawLabel).toContain('YL0001');
    expect(rawLabel).toContain('原料');
    expect(customerMaterialLabel).toContain('KH0001');
    expect(customerMaterialLabel).toContain('客供料');
  });
});
