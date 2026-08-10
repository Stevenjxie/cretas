import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import WorkflowAuxiliaryNode from '../WorkflowAuxiliaryNode.vue';

// WorkflowAuxiliaryNode 用 @vue-flow/core 的 <Handle>，真实实现要求组件挂在 <VueFlow>
// provider 下才能拿到画布上下文；单测只关心辅料 cell 本身的展示/交互逻辑，跟连线
// handle 无关，桩掉即可（同 WorkflowMaterialNode.spec.ts 的做法）。
const HandleStub = defineComponent({
  name: 'Handle',
  inheritAttrs: false,
  props: { id: String, type: String, position: String },
  setup(props) {
    return () => h('span', {
      'data-testid': 'handle-stub',
      'data-handle-id': props.id,
      'data-handle-type': props.type,
      'data-position': props.position,
    });
  },
});

const baseData = {
  processName: '卤制',
  processNodeId: 'p1',
  usageSupported: true,
  rows: [
    { id: 'r1', materialName: '八角', dosageText: '2 g/kg',
      markers: [{ glyph: '◷', kind: 'pot', title: '首锅 100% · 后续 60%' }] },
    { id: 'r2', materialName: '生抽', dosageText: '15 g/kg', markers: [] },
  ],
};

describe('辅料 cell', () => {
  it('renders the exact bottom source handle used by the derived edge', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: baseData },
    });
    const handle = w.get('[data-testid="handle-stub"]');
    expect(handle.attributes('data-handle-id')).toBe('bom-aux-out');
    expect(handle.attributes('data-handle-type')).toBe('source');
    expect(handle.attributes('data-position')).toBe('bottom');
  });

  it('渲染工序名与行数', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: baseData } });
    expect(w.text()).toContain('卤制');
    expect(w.text()).toContain('2 种');
  });

  it('有锅序时副标题说出对报工的后果', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: baseData } });
    expect(w.text(), '技术员勾一个开关车间就多两栏, 必须写出来').toContain('报工需录锅数');
  });

  it('无锅序时不说报工', () => {
    const data = { ...baseData, rows: [{ id: 'r2', materialName: '生抽', dosageText: '15 g/kg', markers: [] }] };
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data } });
    expect(w.text()).not.toContain('报工需录锅数');
  });

  it('标记渲染出 glyph 且 title 可查', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: baseData } });
    const marker = w.find('[data-testid="aux-marker-pot"]');
    expect(marker.exists()).toBe(true);
    expect(marker.attributes('title')).toContain('60');
  });

  it('空态显示「未配」而不是空白', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: { ...baseData, rows: [] } } });
    expect(w.text()).toContain('未配');
    expect(w.find('[data-testid="aux-empty"]').exists()).toBe(true);
  });

  it('灰态说明原因且不给加辅料入口', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: { ...baseData, usageSupported: false, rows: [] } },
    });
    expect(w.text()).toContain('换算契约');
    expect(w.find('[data-testid="aux-add"]').exists()).toBe(false);
  });

  it('可配时给加辅料入口并 emit', async () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: baseData } });
    await w.find('[data-testid="aux-add"]').trigger('click');
    expect(w.emitted('add-row')).toBeTruthy();
  });

  it('只读用户(canWrite=false)即使可配也不给加辅料入口', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: false, data: baseData },
    });
    expect(w.find('[data-testid="aux-add"]').exists()).toBe(false);
  });

  it('只读用户点击行不触发 edit-row', async () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: false, data: baseData },
    });
    await w.find('.aux-row').trigger('click');
    expect(w.emitted('edit-row')).toBeFalsy();
  });

  // must-fix #3 (final whole-branch review of Phase 3-1): usageSupported 必须是三态。
  // meta 缺失(数据未加载/加载失败/无配方/修订节点 id 不匹配)是"不知道", 不能显示成
  // "已确认该工序不可换算"那句具体诊断——代码给不出这个结论的证据(禁止降级处理)。
  it('usageSupported=null(未知)与 usageSupported=false(已确认不可换算)显示不同的文案', () => {
    const unknown = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: { ...baseData, usageSupported: null, rows: [] } },
    });
    expect(unknown.find('[data-testid="aux-unknown-reason"]').exists()).toBe(true);
    expect(unknown.find('[data-testid="aux-greyed-reason"]').exists()).toBe(false);
    expect(unknown.text(), '未知态不能冒充"已确认不可换算"的具体诊断').not.toContain('换算契约');

    const confirmed = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: { ...baseData, usageSupported: false, rows: [] } },
    });
    expect(confirmed.find('[data-testid="aux-greyed-reason"]').exists()).toBe(true);
    expect(confirmed.find('[data-testid="aux-unknown-reason"]').exists()).toBe(false);
    expect(confirmed.text()).toContain('换算契约');

    // 两态都要灰化 + 不给"加辅料"入口
    expect(unknown.find('[data-testid="aux-add"]').exists()).toBe(false);
    expect(confirmed.find('[data-testid="aux-add"]').exists()).toBe(false);
  });

  it('灰态(不可换算/未知)下已配置的行仍然渲染, 不能连数据一起藏起来', () => {
    // 场景: 一道工序原本配了 3 种辅料, 之后它的投入基准被判定为不可换算(或状态变得
    // 不确定)—— 用户必须还能看到已经配置了什么, 灰态只该关掉"新增"入口。
    const rows = [
      { id: 'r1', materialName: '八角', dosageText: '2 g/kg', markers: [] },
      { id: 'r2', materialName: '桂皮', dosageText: '3 g/kg', markers: [] },
      { id: 'r3', materialName: '香叶', dosageText: null, markers: [] },
    ];
    const unsupportedWithRows = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: { ...baseData, usageSupported: false, rows } },
    });
    expect(unsupportedWithRows.text()).toContain('八角');
    expect(unsupportedWithRows.text()).toContain('桂皮');
    expect(unsupportedWithRows.text()).toContain('香叶');
    expect(unsupportedWithRows.findAll('.aux-row')).toHaveLength(3);
    // 灰态说明仍然在, 只是不再独占——数据与理由同屏
    expect(unsupportedWithRows.find('[data-testid="aux-greyed-reason"]').exists()).toBe(true);
    expect(unsupportedWithRows.find('[data-testid="aux-add"]').exists()).toBe(false);

    const unknownWithRows = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: { ...baseData, usageSupported: null, rows } },
    });
    expect(unknownWithRows.text()).toContain('八角');
    expect(unknownWithRows.findAll('.aux-row')).toHaveLength(3);
  });

  it('联合生产: 与其它产出共用工序时标出当前实际生效的配方所属产出', () => {
    const shared = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: {
        id: 'x', canWrite: true,
        data: { ...baseData, sharedAcrossRecipes: true, recipeOutputName: '酱鸭腿' },
      },
    });
    expect(shared.find('[data-testid="aux-shared-recipe"]').exists()).toBe(true);
    expect(shared.text()).toContain('酱鸭腿');

    const notShared = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: baseData },
    });
    expect(notShared.find('[data-testid="aux-shared-recipe"]').exists()).toBe(false);
  });
});
