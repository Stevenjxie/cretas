/**
 * AiOperationCard 组件测试 (AI 读写分离 P2, 2026-07-23):
 * - PREVIEW + confirmableAction → 预览网格 + 确认执行 (confirmIntentAction token/digest) + 成功结果
 * - DEMO_WRITE_BLOCKED 确认结果 → 演示环境 tag
 * - PREVIEW 无 confirmableAction → 不支持一键确认提示
 * - NO_PERMISSION/PERMISSION_DENIED → 权限中文名字典渲染 (未知码回落原始 code)
 * - 过期 (expiresInSeconds=0) → 按钮禁用 + 重新发起提示
 * - 取消 → 「已取消」一行
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const confirmIntentActionMock = vi.fn();
// 双重确认 (2026-07-24 写操作契约): ElMessageBox.confirm 默认放行;
// 单测里可改 mockRejectedValueOnce 模拟用户在二次确认处取消。
const messageBoxConfirmMock = vi.fn(async () => 'confirm');
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>();
  return {
    ...actual,
    ElMessageBox: { ...actual.ElMessageBox, confirm: (...args: unknown[]) => messageBoxConfirmMock(...args) },
  };
});

vi.mock('@/api/smartbi/intent-chat', () => ({
  confirmIntentAction: (...args: unknown[]) => confirmIntentActionMock(...args),
  executeIntent: vi.fn(),
  fetchCachedXlsx: vi.fn(),
  submitIntentFeedback: vi.fn(async () => true),
}));

import AiOperationCard from '../AiOperationCard.vue';
import { permissionDisplayName } from '../permissionNames';

const globalStubs = {
  'el-button': {
    props: ['disabled', 'loading'],
    emits: ['click'],
    template: '<button class="el-button" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-icon': { template: '<i><slot /></i>' },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-alert': {
    props: ['title', 'type'],
    template: '<div class="el-alert">{{ title }}<slot /></div>',
  },
};

function mountCard(response: Record<string, unknown>) {
  return mount(AiOperationCard, {
    props: {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      response: response as any,
      factoryId: 'F001',
    },
    global: { stubs: globalStubs },
  });
}

const previewResponse = {
  status: 'PREVIEW',
  intentRecognized: true,
  intentCode: 'MATERIAL_INBOUND',
  intentName: '原料入库',
  message: '{"material":"五花肉"}',
  formattedText: '',
  confirmableAction: {
    confirmToken: 'tok-123',
    commandDigest: 'digest-abc',
    expiresAt: '2026-07-24T00:05:00Z',
    expiresInSeconds: 300,
    description: '入库 五花肉 200kg',
    previewData: {
      material: '五花肉',
      quantity: '200kg',
      factoryId: 'F001',
      userId: 22,
      userRole: 'factory_super_admin',
      intentCode: 'MATERIAL_INBOUND',
      userInput: '给五花肉入库 200kg',
    },
  },
};

describe('AiOperationCard', () => {
  beforeEach(() => {
    confirmIntentActionMock.mockReset();
  });

  it('renders the preview grid (skipping internal keys) with confirm/cancel footer', () => {
    const wrapper = mountCard(previewResponse);
    expect(wrapper.text()).toContain('原料入库 — 操作预览');
    expect(wrapper.text()).toContain('入库 五花肉 200kg');
    expect(wrapper.text()).toContain('material');
    expect(wrapper.text()).toContain('五花肉');
    expect(wrapper.text()).toContain('quantity');
    // 内部键不进预览网格
    for (const hidden of ['factoryId', 'userId', 'userRole', 'intentCode', 'userInput']) {
      expect(wrapper.text()).not.toContain(hidden);
    }
    expect(wrapper.text()).toContain('确认执行');
    expect(wrapper.text()).toContain('取消');
    expect(wrapper.text()).toContain('后过期');
  });

  it('confirms via confirmIntentAction with token + digest and shows the success message', async () => {
    confirmIntentActionMock.mockResolvedValue({
      status: 'SUCCESS',
      message: '入库完成：五花肉 200kg',
    });
    const wrapper = mountCard(previewResponse);

    const confirmBtn = wrapper.findAll('button.el-button').find((b) => b.text().includes('确认执行'));
    await confirmBtn!.trigger('click');
    await flushPromises();

    expect(confirmIntentActionMock).toHaveBeenCalledTimes(1);
    expect(confirmIntentActionMock).toHaveBeenCalledWith('F001', 'tok-123', {
      commandDigest: 'digest-abc',
      expiresAt: '2026-07-24T00:05:00Z',
    });
    expect(wrapper.text()).toContain('入库完成：五花肉 200kg');
    // footer 已被结果替换
    expect(wrapper.text()).not.toContain('确认执行');
  });

  it('tags the result as 演示环境 when confirm returns DEMO_WRITE_BLOCKED', async () => {
    confirmIntentActionMock.mockResolvedValue({
      status: 'DEMO_WRITE_BLOCKED',
      message: '演示环境不落库，操作未实际执行。',
    });
    const wrapper = mountCard(previewResponse);

    const confirmBtn = wrapper.findAll('button.el-button').find((b) => b.text().includes('确认执行'));
    await confirmBtn!.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('演示环境不落库');
    expect(wrapper.text()).toContain('演示环境');
  });

  it('disables confirm and shows the expiry hint when the preview is already expired', () => {
    const wrapper = mountCard({
      ...previewResponse,
      confirmableAction: { ...previewResponse.confirmableAction, expiresInSeconds: 0 },
    });
    expect(wrapper.text()).toContain('预览已过期，请重新发起');
    const confirmBtn = wrapper.findAll('button.el-button').find((b) => b.text().includes('确认执行'));
    expect(confirmBtn!.attributes('disabled')).toBeDefined();
  });

  it('collapses to 已取消 on cancel', async () => {
    const wrapper = mountCard(previewResponse);
    const cancelBtn = wrapper.findAll('button.el-button').find((b) => b.text() === '取消');
    await cancelBtn!.trigger('click');
    expect(wrapper.text()).toBe('已取消');
    expect(wrapper.emitted('cancelled')).toBeTruthy();
  });

  it('renders the manual-operation hint for PREVIEW without confirmableAction', () => {
    const wrapper = mountCard({ ...previewResponse, confirmableAction: null });
    expect(wrapper.text()).toContain('该操作暂不支持一键确认，请到对应功能页面手工操作。');
  });

  it.each([
    ['inventory:write', '库存·写'],
    ['warehouse:read_write', '仓储·读写'],
    ['finance:read_write', '财务·读写'],
    ['production:write', '生产·写'],
    ['quality:write', '质检·写'],
  ])('renders permission-denied with the Chinese name for %s', (code, label) => {
    const wrapper = mountCard({
      status: 'NO_PERMISSION',
      intentName: '原料入库',
      message: '无权限',
      requiredPermission: code,
    });
    expect(wrapper.text()).toContain(`需要 ${label} 权限，请联系管理员开通`);
  });

  it('falls back to the raw code for unknown permissions and to message when absent', () => {
    const unknown = mountCard({
      status: 'PERMISSION_DENIED',
      message: '无权限',
      requiredPermission: 'foo:bar',
    });
    expect(unknown.text()).toContain('需要 foo:bar 权限');

    const noCode = mountCard({
      status: 'PERMISSION_DENIED',
      message: '您没有执行该操作的权限',
      requiredPermission: null,
    });
    expect(noCode.text()).toContain('您没有执行该操作的权限');
  });

  it('shows the transient preview-loading state for WRITE_CONFIRM_REQUIRED', () => {
    const wrapper = mountCard({
      status: 'WRITE_CONFIRM_REQUIRED',
      intentName: '原料入库',
      message: '该操作需要确认。',
    });
    expect(wrapper.text()).toContain('「原料入库」需要确认');
    expect(wrapper.text()).toContain('正在生成操作预览…');
  });

  it('renders DEMO_WRITE_BLOCKED and PENDING_APPROVAL info cards', () => {
    const demo = mountCard({ status: 'DEMO_WRITE_BLOCKED', intentName: '原料入库', message: '演示环境不落库。' });
    expect(demo.text()).toContain('演示环境');
    expect(demo.text()).toContain('演示环境不落库。');

    const approval = mountCard({ status: 'PENDING_APPROVAL', intentName: '原料入库', message: '已提交主管审批。' });
    expect(approval.text()).toContain('已提交审批');
    expect(approval.text()).toContain('已提交主管审批。');
  });
});

describe('permissionNames dictionary', () => {
  it('maps all ten seeded codes and falls back to the raw code', () => {
    expect(permissionDisplayName('restaurant:read_write')).toBe('餐饮·读写');
    expect(permissionDisplayName('system:read_write')).toBe('系统·读写');
    expect(permissionDisplayName('procurement:write')).toBe('采购·写');
    expect(permissionDisplayName('sales:write')).toBe('销售·写');
    expect(permissionDisplayName('hr:write')).toBe('人事·写');
    expect(permissionDisplayName('unknown:code')).toBe('unknown:code');
    expect(permissionDisplayName(null)).toBe('');
  });
});

describe('AiOperationCard — 写操作影响契约 (2026-07-24)', () => {
  const previewWithImpact = {
    status: 'PREVIEW',
    intentCode: 'MATERIAL_BATCH_UPDATE',
    intentName: '修改批次数量',
    message: '{}',
    formattedText: '',
    confirmableAction: {
      confirmToken: 'tok-1', commandDigest: 'd'.repeat(64),
      expiresAt: new Date(Date.now() + 300000).toISOString(), expiresInSeconds: 300,
      currentValues: { 数量: '320kg' },
      newValues: { 数量: '520kg' },
      impactSummary: '此操作将修改「修改批次数量」相关的现有数据，确认后立即生效。 本次共 1 项字段变更，详见上方对比。',
      actionType: 'UPDATE', riskLevel: 'MEDIUM',
    },
  } as never;

  it('renders impact summary alert and before/after diff table', () => {
    const wrapper = mountCard(previewWithImpact);
    expect(wrapper.text()).toContain('此操作将修改');
    expect(wrapper.text()).toContain('1 项字段变更');
    expect(wrapper.text()).toContain('当前');
    expect(wrapper.text()).toContain('修改后');
    expect(wrapper.text()).toContain('320kg');
    expect(wrapper.text()).toContain('520kg');
  });

  it('double-confirm: cancelling the second confirmation does NOT execute', async () => {
    messageBoxConfirmMock.mockRejectedValueOnce(new Error('cancel'));
    const wrapper = mountCard(previewWithImpact);
    await wrapper.find('button.el-button').trigger('click');
    await new Promise((r) => setTimeout(r, 0));
    expect(confirmIntentActionMock).not.toHaveBeenCalled();
    // 卡片保持可再次确认 (不进入 pending/done)
    expect(wrapper.text()).toContain('确认执行');
  });

  it('double-confirm: second confirmation shows impact text before executing', async () => {
    confirmIntentActionMock.mockResolvedValueOnce({ status: 'SUCCESS', message: '已执行' });
    const wrapper = mountCard(previewWithImpact);
    await wrapper.find('button.el-button').trigger('click');
    await new Promise((r) => setTimeout(r, 0));
    expect(messageBoxConfirmMock).toHaveBeenCalled();
    const [msg] = messageBoxConfirmMock.mock.calls[messageBoxConfirmMock.mock.calls.length - 1];
    expect(String(msg)).toContain('此操作将修改');
    expect(confirmIntentActionMock).toHaveBeenCalled();
  });
});
