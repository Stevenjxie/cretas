import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

const state = vi.hoisted(() => ({ factoryId: 'R001', businessDomain: 'RESTAURANT' }));
const api = vi.hoisted(() => ({
  listEvalSets: vi.fn(),
  createEvalSet: vi.fn(),
  importRuntimeCorpus: vi.fn(),
  listExperiments: vi.fn(),
  compareExperiments: vi.fn(),
  runExperiment: vi.fn(),
  runRuntimeShadow: vi.fn(),
  rerunExperiment: vi.fn(),
  getRunTrace: vi.fn(),
}));

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => state }));
vi.mock('@/api/agent-ops', () => api);
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}));

import AgentOpsShell from '../AgentOpsShell.vue';
import EvalSetsView from '../EvalSetsView.vue';
import ExperimentsView from '../ExperimentsView.vue';
import RunTraceView from '../RunTraceView.vue';

const stubs = {
  'el-alert': { props: ['title'], template: '<div class="alert">{{ title }}</div>' },
  'el-empty': { props: ['description'], template: '<div class="empty">{{ description }}</div>' },
  'el-skeleton': { template: '<div class="skeleton" />' },
  'el-button': {
    props: ['disabled', 'loading'], emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-input': {
    props: ['modelValue'], emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  'el-select': {
    props: ['modelValue'], emits: ['update:modelValue'],
    template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
  },
  'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
  'el-table': { props: ['data'], template: '<div class="table" />' },
  'el-table-column': { template: '<div><slot :row="{}" /></div>' },
  'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-divider': { template: '<div><slot /></div>' },
  'el-input-number': {
    props: ['modelValue'], emits: ['update:modelValue'],
    template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />',
  },
  'router-link': { props: ['to'], template: '<a><slot /></a>' },
  'router-view': { template: '<div class="router-view" />' },
};

beforeEach(() => {
  state.factoryId = 'R001';
  state.businessDomain = 'RESTAURANT';
  Object.values(api).forEach((mock) => mock.mockReset());
  api.listEvalSets.mockResolvedValue({ data: { items: [] } });
  api.listExperiments.mockResolvedValue({ data: { items: [] } });
});

describe('AgentOps views', () => {
  it('shows a permission-safe tenant warning instead of rendering child data', () => {
    state.factoryId = '';
    const wrapper = mount(AgentOpsShell, { global: { stubs } });
    expect(wrapper.text()).toContain('当前账号未绑定餐饮租户');
    expect(wrapper.find('.router-view').exists()).toBe(false);
  });

  it('requires the positive RESTAURANT business domain even when a tenant exists', () => {
    state.businessDomain = 'FACTORY';
    const wrapper = mount(AgentOpsShell, { global: { stubs } });
    expect(wrapper.find('.router-view').exists()).toBe(false);
  });

  it('renders Eval Set empty state and explicit load error without fake rows', async () => {
    api.listEvalSets.mockResolvedValueOnce({ data: { items: [] } });
    const empty = mount(EvalSetsView, { global: { stubs } });
    await flushPromises();
    expect(empty.get('[data-testid="eval-empty"]').text()).toContain('尚无 Eval Set');

    api.listEvalSets.mockRejectedValueOnce(new Error('STORE_UNAVAILABLE'));
    const failed = mount(EvalSetsView, { global: { stubs } });
    await flushPromises();
    expect(failed.get('[data-testid="eval-error"]').text()).toContain('STORE_UNAVAILABLE');
  });

  it('submits a server-generated runtime shadow request without client actual snapshots', async () => {
    const evalSetId = '00000000-0000-4000-8000-000000000010';
    api.listEvalSets.mockResolvedValue({ data: { items: [{
      evalSetId, name: 'margin', version: 1, description: '', caseCount: 1,
      contentDigest: 'a'.repeat(64), createdBy: '1', createdAt: 'now',
    }] } });
    api.runRuntimeShadow.mockResolvedValue({ data: {} });
    const wrapper = mount(ExperimentsView, { global: { stubs } });
    await flushPromises();
    await wrapper.get('[data-testid="run-eval-set"]').setValue(evalSetId);
    await wrapper.get('[data-testid="prompt-digest"]').setValue('1'.repeat(64));
    await wrapper.get('[data-testid="model-digest"]').setValue('2'.repeat(64));
    await wrapper.get('[data-testid="tool-digest"]').setValue('3'.repeat(64));
    await wrapper.get('[data-testid="run-experiment"]').trigger('click');
    await flushPromises();
    expect(api.runRuntimeShadow).toHaveBeenCalledOnce();
    const sent = api.runRuntimeShadow.mock.calls[0][1];
    expect(sent.evalSetId).toBe(evalSetId);
    expect(sent.configSnapshot.promptSnapshotDigest).toBe('1'.repeat(64));
    expect(sent.requestId).toMatch(/^[0-9a-f-]{36}$/);
    expect(sent).not.toHaveProperty('evaluatorVersion');
    expect(sent).not.toHaveProperty('actualSnapshots');
    expect(wrapper.find('[data-testid="actual-snapshots"]').exists()).toBe(false);
  });

  it('reuses create request IDs after failure and rotates them after input change or success', async () => {
    api.importRuntimeCorpus
      .mockRejectedValueOnce(new Error('NETWORK'))
      .mockRejectedValueOnce(new Error('NETWORK'))
      .mockRejectedValueOnce(new Error('NETWORK'))
      .mockResolvedValueOnce({ data: {} })
      .mockResolvedValueOnce({ data: {} });
    const wrapper = mount(EvalSetsView, { global: { stubs } });
    await flushPromises();
    const inputs = wrapper.findAll('input');
    await inputs[0].setValue('baseline');
    const submit = wrapper.get('[data-testid="import-runtime-corpus"]');

    await submit.trigger('click'); await flushPromises();
    await submit.trigger('click'); await flushPromises();
    const firstId = api.importRuntimeCorpus.mock.calls[0][1].requestId;
    expect(api.importRuntimeCorpus.mock.calls[1][1].requestId).toBe(firstId);

    await inputs[2].setValue('changed');
    await submit.trigger('click'); await flushPromises();
    const changedId = api.importRuntimeCorpus.mock.calls[2][1].requestId;
    expect(changedId).not.toBe(firstId);

    await submit.trigger('click'); await flushPromises();
    expect(api.importRuntimeCorpus.mock.calls[3][1].requestId).toBe(changedId);
    await submit.trigger('click'); await flushPromises();
    expect(api.importRuntimeCorpus.mock.calls[4][1].requestId).not.toBe(changedId);
  });

  it('reuses run request IDs after failure and rotates them after input change or success', async () => {
    const evalSetId = '00000000-0000-4000-8000-000000000010';
    api.listEvalSets.mockResolvedValue({ data: { items: [{
      evalSetId, name: 'margin', version: 1, description: '', caseCount: 1,
      contentDigest: 'a'.repeat(64), createdBy: '1', createdAt: 'now',
    }] } });
    api.runRuntimeShadow
      .mockRejectedValueOnce(new Error('NETWORK'))
      .mockRejectedValueOnce(new Error('NETWORK'))
      .mockRejectedValueOnce(new Error('NETWORK'))
      .mockResolvedValueOnce({ data: {} })
      .mockResolvedValueOnce({ data: {} });
    const wrapper = mount(ExperimentsView, { global: { stubs } });
    await flushPromises();
    await wrapper.get('[data-testid="run-eval-set"]').setValue(evalSetId);
    await wrapper.get('[data-testid="prompt-digest"]').setValue('1'.repeat(64));
    await wrapper.get('[data-testid="model-digest"]').setValue('2'.repeat(64));
    await wrapper.get('[data-testid="tool-digest"]').setValue('3'.repeat(64));
    const submit = wrapper.get('[data-testid="run-experiment"]');

    await submit.trigger('click'); await flushPromises();
    await submit.trigger('click'); await flushPromises();
    const firstId = api.runRuntimeShadow.mock.calls[0][1].requestId;
    expect(api.runRuntimeShadow.mock.calls[1][1].requestId).toBe(firstId);

    await wrapper.get('[data-testid="tool-digest"]').setValue('4'.repeat(64));
    await submit.trigger('click'); await flushPromises();
    const changedId = api.runRuntimeShadow.mock.calls[2][1].requestId;
    expect(changedId).not.toBe(firstId);
    await submit.trigger('click'); await flushPromises();
    expect(api.runRuntimeShadow.mock.calls[3][1].requestId).toBe(changedId);
    await submit.trigger('click'); await flushPromises();
    expect(api.runRuntimeShadow.mock.calls[4][1].requestId).not.toBe(changedId);
  });

  it('reuses a rerun request ID after failure and clears it after success', async () => {
    const experimentId = '00000000-0000-4000-8000-000000000001';
    api.listExperiments.mockResolvedValue({ data: { items: [{
      experimentId, evalSetId: 'set', evalSetName: 'margin', evalSetVersion: 1,
      evaluatorVersion: 'v1', evaluatorBuild: 'b1', snapshotDigest: 'a'.repeat(64),
      operationKind: 'RUN', sourceExperimentId: null,
      configSnapshot: { promptSnapshotDigest: '1'.repeat(64), modelSnapshotDigest: '2'.repeat(64), toolSnapshotDigest: '3'.repeat(64) },
      runnerBounds: { maxCases: 100, maxConcurrency: 2, perCaseTimeoutMs: 1000 },
      aggregate: { caseCount: 1, passedCount: 1, failedCount: 0, passRate: '1', routePassCount: 1, trajectoryPassCount: 1, numericTruthPassCount: 1 },
      createdBy: '1', createdAt: 'now',
    }] } });
    api.rerunExperiment
      .mockRejectedValueOnce(new Error('NETWORK'))
      .mockRejectedValueOnce(new Error('NETWORK'))
      .mockResolvedValueOnce({ data: {} })
      .mockResolvedValueOnce({ data: {} });
    const wrapper = mount(ExperimentsView, { global: { stubs } });
    await flushPromises();
    const rerun = wrapper.get(`[data-testid="rerun-${experimentId}"]`);

    await rerun.trigger('click'); await flushPromises();
    await rerun.trigger('click'); await flushPromises();
    const firstId = api.rerunExperiment.mock.calls[0][2].requestId;
    expect(api.rerunExperiment.mock.calls[1][2].requestId).toBe(firstId);
    await rerun.trigger('click'); await flushPromises();
    expect(api.rerunExperiment.mock.calls[2][2].requestId).toBe(firstId);
    await wrapper.get(`[data-testid="rerun-${experimentId}"]`).trigger('click'); await flushPromises();
    expect(api.rerunExperiment.mock.calls[3][2].requestId).not.toBe(firstId);
  });

  it('renders experiment evaluator/version diff and regression count', async () => {
    const one = {
      experimentId: '00000000-0000-4000-8000-000000000001', evalSetId: 'set',
      evalSetName: 'margin', evalSetVersion: 1, evaluatorVersion: 'v1', evaluatorBuild: 'b1', snapshotDigest: 'a'.repeat(64),
      configSnapshot: { promptSnapshotDigest: '1'.repeat(64), modelSnapshotDigest: '2'.repeat(64), toolSnapshotDigest: '3'.repeat(64) },
      runnerBounds: { maxCases: 100, maxConcurrency: 2, perCaseTimeoutMs: 1000 },
      aggregate: { caseCount: 1, passedCount: 1, failedCount: 0, passRate: '1', routePassCount: 1, trajectoryPassCount: 1, numericTruthPassCount: 1 },
      createdBy: '1', createdAt: 'now',
    };
    const two = { ...one, experimentId: '00000000-0000-4000-8000-000000000002', evaluatorVersion: 'v2' };
    api.listExperiments.mockResolvedValue({ data: { items: [one, two] } });
    api.compareExperiments.mockResolvedValue({ data: {
      experimentId: two.experimentId, baselineExperimentId: one.experimentId,
      sameEvalSetVersion: true, currentEvaluatorVersion: 'v2', baselineEvaluatorVersion: 'v1', evaluatorChanged: true,
      currentEvaluatorBuild: 'b2', baselineEvaluatorBuild: 'b1', evaluatorBuildChanged: true,
      currentEvalSetVersion: 1, baselineEvalSetVersion: 1, passRateDelta: '-1',
      improvedCaseIds: [], regressedCaseIds: ['c1'], sharedCaseCount: 1,
      promptSnapshotChanged: true, modelSnapshotChanged: false, toolSnapshotChanged: true,
    } });
    const wrapper = mount(ExperimentsView, { global: { stubs } });
    await flushPromises();
    const selects = wrapper.findAll('select');
    await selects[1].setValue(one.experimentId);
    await selects[2].setValue(two.experimentId);
    await wrapper.findAll('button')[1].trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="experiment-diff"]').text()).toContain('回归 1');
    expect(wrapper.get('[data-testid="experiment-diff"]').text()).toContain('v1 → v2');
    expect(wrapper.get('[data-testid="snapshot-digest-diff"]').text()).toContain('Prompt changed');
    expect(wrapper.get('[data-testid="experiment-run-form"]').exists()).toBe(true);
  });

  it('keeps trace failures explicit and never synthesizes an event', async () => {
    api.getRunTrace.mockRejectedValue(new Error('RUN_NOT_FOUND'));
    const wrapper = mount(RunTraceView, { global: { stubs } });
    await wrapper.get('input').setValue('00000000-0000-4000-8000-000000000001');
    await wrapper.get('button').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="trace-error"]').text()).toContain('RUN_NOT_FOUND');
    expect(wrapper.find('.event-card').exists()).toBe(false);
  });
});
