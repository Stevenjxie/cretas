import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WorkProcessAIChatPanel from '../WorkProcessAIChatPanel.vue';

const requestMocks = vi.hoisted(() => ({
  post: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  default: { post: requestMocks.post },
}));

interface SourceIdentity {
  factoryId: string;
  productTypeId: string;
}

interface PanelMessage {
  role: string;
  content: string;
  sourceIdentity?: SourceIdentity;
  diffPreview?: Array<{ params: Record<string, unknown> }>;
}

interface PanelVm {
  input: string;
  loading: boolean;
  messages: PanelMessage[];
  send: () => Promise<void>;
  emitDraft: (draft: Record<string, unknown>, sourceIdentity?: SourceIdentity) => void;
}

describe('WorkProcessAIChatPanel identity isolation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
      configurable: true,
      value: vi.fn(),
    });
  });

  it('clears A messages and ignores an A response that resolves after switching to B', async () => {
    const a = deferred<{ data: Record<string, unknown> }>();
    requestMocks.post.mockReturnValue(a.promise);
    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as PanelVm;
    vm.input = 'Generate A';
    const sendPromise = vm.send();
    await flushPromises();

    await wrapper.setProps({ factoryId: 'F007', productTypeId: 'PT-B' });
    a.resolve({
      data: {
        reply: 'A reply',
        diffs: [{
          type: 'PRODUCT_PROCESS_WORKFLOW_PATCH',
          tool: 'workflow',
          params: { patches: [] },
          description: 'A diff',
        }],
      },
    });
    await sendPromise;

    expect(vm.messages).toHaveLength(1);
    expect(vm.messages[0].role).toBe('system');
    expect(vm.loading).toBe(false);
    expect(wrapper.emitted('applyDraft')).toBeUndefined();
  });

  it('emits a draft with the factory and product captured by its request message', async () => {
    const draft = { patches: [{ op: 'REMOVE_NODE', nodeId: 'material:raw' }] };
    requestMocks.post.mockResolvedValue({
      data: {
        reply: 'B reply',
        diffs: [{
          type: 'PRODUCT_PROCESS_WORKFLOW_PATCH',
          tool: 'workflow',
          params: draft,
          description: 'B diff',
        }],
      },
    });
    const selectionContext = { selectedNodeIds: ['process:packing'], selectedEdgeIds: ['edge:packing'] };
    const wrapper = mountPanel({ factoryId: 'F007', productTypeId: 'PT-B', context: selectionContext });
    const vm = wrapper.vm as unknown as PanelVm;
    vm.input = 'Generate B';
    await vm.send();
    expect(requestMocks.post).toHaveBeenCalledWith('/workflow-ai', expect.objectContaining({
      params: expect.objectContaining({ context: selectionContext }),
    }));
    const assistant = vm.messages.find((message) => message.role === 'assistant');
    expect(assistant?.sourceIdentity).toEqual({ factoryId: 'F007', productTypeId: 'PT-B' });

    vm.emitDraft(draft, assistant?.sourceIdentity);

    expect(wrapper.emitted('applyDraft')).toEqual([[
      draft,
      { factoryId: 'F007', productTypeId: 'PT-B' },
    ]]);
  });

  it('renders selected cells and lines as an attachment inside the composer', () => {
    const wrapper = mountPanel({ contextLabel: '当前范围：2 个 Cell、6 条线' });
    const composer = wrapper.get('[data-testid="workflow-ai-compose-bar"]');

    expect(composer.get('.ai-context-attachment').text()).toContain('2 个 Cell、6 条线');
    expect(composer.get('.ai-single-input').attributes('placeholder')).toBe('输入想要的修改');
    expect(wrapper.find('.ai-context-chip').exists()).toBe(false);
  });
});

function mountPanel(overrides: Partial<{
  factoryId: string;
  productTypeId: string;
  context: Record<string, unknown>;
  contextLabel: string;
}> = {}) {
  return shallowMount(WorkProcessAIChatPanel, {
    props: {
      factoryId: overrides.factoryId ?? 'F006',
      productTypeId: overrides.productTypeId ?? 'PT-A',
      endpoint: '/workflow-ai',
      moduleCode: 'product_process_workflow_config',
      context: overrides.context ?? {},
      contextLabel: overrides.contextLabel,
    },
  });
}

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}
