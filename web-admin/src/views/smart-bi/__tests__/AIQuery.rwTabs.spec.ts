/**
 * AI 读写分离 P2 (2026-07-23): 咨询/操作 双 tab 行为测试。
 * - 咨询 tab 发 mode=READ; 操作 tab 发 mode=OPERATE (独立 session)
 * - READ_MODE_WRITE_BLOCKED → 拦截卡 (前往操作页自动重发 / 只读账号提示)
 * - WRITE_CONFIRM_REQUIRED (操作 tab) → 自动 previewOnly 重发, 渲染操作预览卡
 * - 无写权限账号 → 不渲染 tabs UI
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

const executeIntentMock = vi.fn();
const confirmIntentActionMock = vi.fn();
const chatAnalysisStreamMock = vi.fn(() => ({ abort: vi.fn() }));
vi.mock('@/api/smartbi/intent-chat', () => ({
  executeIntent: (...args: unknown[]) => executeIntentMock(...args),
  confirmIntentAction: (...args: unknown[]) => confirmIntentActionMock(...args),
  fetchCachedXlsx: vi.fn(),
  submitIntentFeedback: vi.fn(async () => true),
}));

vi.mock('@/api/smartbi', () => ({
  chatAnalysis: vi.fn(async () => ({ summary: '', charts: [] })),
  chatAnalysisStream: (...args: unknown[]) => chatAnalysisStreamMock(...args),
  getUploadHistory: vi.fn(async () => []),
  deduplicateUploads: vi.fn(async () => ({})),
  nl2sql: vi.fn(async () => ({})),
  logFeedback: vi.fn(async () => ({})),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({
    factoryId: 'F001',
    businessDomain: 'FACTORY',
    user: { id: 1 },
  }),
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock('@/utils/echarts', () => ({
  default: { init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }) },
}));
vi.mock('@/utils/echarts-fmt', () => ({ processEChartsOptions: (o: unknown) => o }));
vi.mock('@/composables/useChartResize', () => ({
  useChartResize: () => ({ observe: vi.fn(), disconnect: vi.fn() }),
}));
vi.mock('marked', () => ({ marked: (s: string) => s }));
vi.mock('dompurify', () => ({ default: { sanitize: (s: string) => s } }));
vi.mock('@/components/smartbi', () => ({ AIInsightPanel: { template: '<div />' } }));
vi.mock('@/components/smartbi/SmartBIEmptyState.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/components/smart-bi/MaterializedAnalysisPanel.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/api/smartbi/materialized', () => ({ listFactoryTemplates: vi.fn(async () => []) }));

import AIQuery from '../AIQuery.vue';
import { usePermissionStore } from '@/store/modules/permission';

const globalStubs = {
  'el-button': {
    props: ['disabled', 'loading'],
    emits: ['click'],
    template: '<button class="el-button" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-link': { emits: ['click'], template: '<a class="el-link" @click="$emit(\'click\')"><slot /></a>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-input': { template: '<input />' },
  'el-autocomplete': { template: '<div class="el-autocomplete"><slot :item="{ value: \'\' }" /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-table': { template: '<table><slot /></table>' },
  'el-table-column': { template: '<td><slot /></td>' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-switch': { template: '<input type="checkbox" />' },
  'el-empty': { template: '<div><slot /></div>' },
  'el-alert': { template: '<div><slot /></div>' },
  'el-segmented': {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template: '<div class="el-segmented" />',
  },
};

interface AIQueryVm {
  inputQuery: string;
  activeAiTab: 'consult' | 'operate';
  handleSendMessage: () => Promise<void>;
}

function mountWithRole(role: string): Promise<VueWrapper> {
  usePermissionStore().setRole(role, 'F001', 'FACTORY', 1, { skipDbLoad: true });
  const wrapper = mount(AIQuery, { global: { stubs: globalStubs } });
  return flushPromises().then(() => wrapper);
}

async function send(wrapper: VueWrapper, question: string) {
  const vm = wrapper.vm as unknown as AIQueryVm;
  vm.inputQuery = question;
  await vm.handleSendMessage();
  await flushPromises();
}

const successResponse = {
  status: 'SUCCESS',
  intentRecognized: true,
  intentCode: 'MATERIAL_BATCH_QUERY',
  intentName: '原料批次查询',
  message: '查询完成',
  formattedText: '查询完成',
  resultData: null,
};

describe('AIQuery AI read/write tabs (P2)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    sessionStorage.clear();
    executeIntentMock.mockReset();
    confirmIntentActionMock.mockReset();
    chatAnalysisStreamMock.mockReset();
    chatAnalysisStreamMock.mockImplementation(() => ({ abort: vi.fn() }));
  });

  it('sends mode=READ on 咨询 tab and mode=OPERATE on 操作 tab with separate sessions', async () => {
    executeIntentMock.mockResolvedValue(successResponse);
    const wrapper = await mountWithRole('factory_super_admin');

    await send(wrapper, '查一下今天的库存');
    expect(executeIntentMock).toHaveBeenCalledTimes(1);
    expect(executeIntentMock.mock.calls[0][2].mode).toBe('READ');

    (wrapper.vm as unknown as AIQueryVm).activeAiTab = 'operate';
    await send(wrapper, '给五花肉入库 200kg');
    expect(executeIntentMock).toHaveBeenCalledTimes(2);
    expect(executeIntentMock.mock.calls[1][2].mode).toBe('OPERATE');

    // 独立 session: 操作 tab 不复用咨询会话
    const consultSession = executeIntentMock.mock.calls[0][2].sessionId;
    const operateSession = executeIntentMock.mock.calls[1][2].sessionId;
    expect(consultSession).toEqual(expect.any(String));
    expect(operateSession).toEqual(expect.any(String));
    expect(operateSession).not.toBe(consultSession);
  });

  it('renders the jump card on READ_MODE_WRITE_BLOCKED and resends on 操作 tab after click', async () => {
    executeIntentMock
      .mockResolvedValueOnce({
        ...successResponse,
        status: 'READ_MODE_WRITE_BLOCKED',
        aiMode: 'READ',
        message: '这是操作类请求，请切换到【操作】页处理。',
      })
      .mockResolvedValueOnce(successResponse);
    const wrapper = await mountWithRole('factory_super_admin');

    await send(wrapper, '给五花肉入库 200kg');
    expect(wrapper.find('.read-blocked-card').exists()).toBe(true);
    expect(wrapper.text()).toContain('这是操作类请求，请切换到【操作】页处理。');

    const jumpBtn = wrapper.findAll('button.el-button').find((b) => b.text().includes('前往操作页'));
    expect(jumpBtn).toBeTruthy();
    await jumpBtn!.trigger('click');
    await flushPromises();

    expect((wrapper.vm as unknown as AIQueryVm).activeAiTab).toBe('operate');
    expect(executeIntentMock).toHaveBeenCalledTimes(2);
    expect(executeIntentMock.mock.calls[1][1]).toBe('给五花肉入库 200kg');
    expect(executeIntentMock.mock.calls[1][2].mode).toBe('OPERATE');
  });

  it('shows the read-only hint (no jump button) when the account has no write access', async () => {
    executeIntentMock.mockResolvedValue({
      ...successResponse,
      status: 'READ_MODE_WRITE_BLOCKED',
      message: '这是操作类请求，请切换到【操作】页处理。',
    });
    const wrapper = await mountWithRole('viewer');

    await send(wrapper, '给五花肉入库 200kg');
    expect(wrapper.find('.read-blocked-card').exists()).toBe(true);
    expect(wrapper.text()).toContain('您当前是只读账号，无法执行操作类请求');
    const jumpBtn = wrapper.findAll('button.el-button').find((b) => b.text().includes('前往操作页'));
    expect(jumpBtn).toBeUndefined();
  });

  it('auto re-sends previewOnly=true after WRITE_CONFIRM_REQUIRED on 操作 tab and renders the preview card', async () => {
    executeIntentMock
      .mockResolvedValueOnce({
        ...successResponse,
        status: 'WRITE_CONFIRM_REQUIRED',
        intentName: '原料入库',
        message: '该操作需要确认。',
      })
      .mockResolvedValueOnce({
        ...successResponse,
        status: 'PREVIEW',
        intentName: '原料入库',
        message: '{"material":"五花肉","quantity":"200kg"}',
        confirmableAction: {
          confirmToken: 'tok-123',
          commandDigest: 'digest-abc',
          expiresAt: '2026-07-24T00:05:00Z',
          expiresInSeconds: 300,
          description: '入库 五花肉 200kg',
          previewData: { material: '五花肉', quantity: '200kg', factoryId: 'F001' },
        },
      });
    const wrapper = await mountWithRole('factory_super_admin');
    (wrapper.vm as unknown as AIQueryVm).activeAiTab = 'operate';

    await send(wrapper, '给五花肉入库 200kg');

    expect(executeIntentMock).toHaveBeenCalledTimes(2);
    expect(executeIntentMock.mock.calls[1][2].previewOnly).toBe(true);
    expect(executeIntentMock.mock.calls[1][2].mode).toBe('OPERATE');
    // 同一 session 的续发 (slot-filling / token 绑定同会话)
    expect(executeIntentMock.mock.calls[1][2].sessionId)
      .toBe(executeIntentMock.mock.calls[0][2].sessionId);

    expect(wrapper.text()).toContain('原料入库 — 操作预览');
    expect(wrapper.text()).toContain('确认执行');
    expect(wrapper.text()).toContain('五花肉');
    // 内部键被隐藏
    expect(wrapper.text()).not.toContain('factoryId');
  });

  it('hides the tabs UI entirely for accounts without any write access', async () => {
    executeIntentMock.mockResolvedValue(successResponse);
    const viewerWrapper = await mountWithRole('viewer');
    expect(viewerWrapper.find('.ai-mode-tabs').exists()).toBe(false);
    // 只读账号仍走 READ 模式
    await send(viewerWrapper, '查一下今天的库存');
    expect(executeIntentMock.mock.calls[0][2].mode).toBe('READ');
  });

  it('shows the tabs UI for accounts with write access', async () => {
    const adminWrapper = await mountWithRole('factory_super_admin');
    expect(adminWrapper.find('.ai-mode-tabs').exists()).toBe(true);
  });
});
