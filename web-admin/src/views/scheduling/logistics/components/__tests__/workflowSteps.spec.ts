import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it } from 'vitest';
import Workbench from '../../workbench/index.vue';
import { resetLogisticsDemoState } from '../../useLogisticsDemoState';

describe('logistics workbench steps', () => {
  beforeEach(() => resetLogisticsDemoState());

  it('shows one task stage at a time', async () => {
    const wrapper = mount(Workbench, { global: { stubs: { ElButton: false } } });

    expect(wrapper.find('[data-testid="import-step"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="map-step"]').exists()).toBe(false);

    await wrapper.get('[data-testid="import-orders"]').trigger('click');
    await wrapper.get('[data-testid="generate-routes"]').trigger('click');

    expect(wrapper.find('[data-testid="map-step"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="export-step"]').exists()).toBe(false);
  });
});
