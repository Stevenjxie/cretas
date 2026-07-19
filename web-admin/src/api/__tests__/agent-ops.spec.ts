import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../request', () => ({ get: vi.fn(), post: vi.fn() }));

import { get, post } from '../request';
import { compareExperiments, createEvalSet, getRunTrace, listEvalSets, rerunExperiment, runExperiment } from '../agent-ops';

const mockedGet = vi.mocked(get);
const mockedPost = vi.mocked(post);
const ID1 = '00000000-0000-4000-8000-000000000001';
const ID2 = '00000000-0000-4000-8000-000000000002';
const REQUEST_ID = '00000000-0000-4000-8000-000000000003';

beforeEach(() => {
  mockedGet.mockReset();
  mockedPost.mockReset();
  mockedGet.mockResolvedValue({ success: true, data: { items: [] }, message: '' });
  mockedPost.mockResolvedValue({ success: true, data: {}, message: '' });
});

describe('AgentOps API', () => {
  it('builds tenant-bound Java facade paths without query identity', async () => {
    await listEvalSets('R001');
    expect(mockedGet).toHaveBeenCalledWith('/R001/agent-ops/eval-sets');
    await getRunTrace('R001', ID1, 10, 100);
    expect(mockedGet).toHaveBeenLastCalledWith(`/R001/agent-ops/traces/${ID1}`, { params: { afterSequence: 10, limit: 100 } });
  });

  it('sends no tenant or actor fields in Eval Set body', async () => {
    const body = {
      schemaVersion: '1.0' as const,
      requestId: REQUEST_ID,
      name: 'baseline', version: 1, description: '',
      cases: [{
        caseId: 'c1', expectedRoute: 'GROSS_MARGIN_DECLINE_ATTRIBUTION' as const,
        requiredTools: ['margin'], numericTruthRefs: { 'e1:f1': '1' },
        maxRounds: 2, maxToolCalls: 2,
      }],
    };
    await createEvalSet('R001', body);
    expect(mockedPost).toHaveBeenCalledWith('/R001/agent-ops/eval-sets', body);
    const sent = mockedPost.mock.calls[0][1] as Record<string, unknown>;
    expect(sent).not.toHaveProperty('factoryId');
    expect(sent).not.toHaveProperty('userId');
  });

  it('builds compare query and rejects unsafe IDs and bounds before network', async () => {
    await compareExperiments('R001', ID1, ID2);
    expect(mockedGet).toHaveBeenCalledWith(`/R001/agent-ops/experiments/${ID1}/compare`, {
      params: { baselineId: ID2 },
    });
    expect(() => listEvalSets('../R002')).toThrow('FACTORY_ID_REQUIRED');
    expect(() => getRunTrace('R001', 'not-a-uuid')).toThrow('VALID_UUID_REQUIRED');
    expect(() => getRunTrace('R001', ID1, 0, 101)).toThrow('TRACE_LIMIT_INVALID');
  });

  it('sends a complete reproducible experiment contract without client evaluator identity', async () => {
    const body = {
      schemaVersion: '1.0' as const,
      requestId: REQUEST_ID,
      evalSetId: ID1,
      configSnapshot: {
        promptSnapshotDigest: '1'.repeat(64),
        modelSnapshotDigest: '2'.repeat(64),
        toolSnapshotDigest: '3'.repeat(64),
      },
      actualSnapshots: {
        c1: { routeCode: 'GROSS_MARGIN_DECLINE_ATTRIBUTION', tools: ['margin'], numericTruthRefs: {}, roundsUsed: 1, toolCallsUsed: 1 },
      },
      bounds: { maxCases: 100, maxConcurrency: 2, perCaseTimeoutMs: 1000 },
    };
    await runExperiment('R001', body);
    expect(mockedPost).toHaveBeenCalledWith('/R001/agent-ops/experiments', body);
    expect(mockedPost.mock.calls[0][1]).not.toHaveProperty('evaluatorVersion');
  });

  it('sends a versioned idempotent rerun body', async () => {
    const body = { schemaVersion: '1.0' as const, requestId: REQUEST_ID };
    await rerunExperiment('R001', ID1, body);
    expect(mockedPost).toHaveBeenCalledWith(`/R001/agent-ops/experiments/${ID1}/rerun`, body);
  });

  it('rejects invalid request IDs and write bodies over 4 MiB before network', () => {
    expect(() => createEvalSet('R001', {
      schemaVersion: '2.0' as '1.0', requestId: REQUEST_ID, name: 'baseline', version: 1,
      description: '', cases: [],
    })).toThrow('AGENT_OPS_SCHEMA_VERSION_UNSUPPORTED');
    expect(() => createEvalSet('R001', {
      schemaVersion: '1.0', requestId: 'not-a-uuid', name: 'baseline', version: 1,
      description: '', cases: [],
    })).toThrow('VALID_UUID_REQUIRED');

    expect(() => createEvalSet('R001', {
      schemaVersion: '1.0', requestId: REQUEST_ID, name: 'x'.repeat(4 * 1024 * 1024),
      version: 1, description: '', cases: [],
    })).toThrow('AGENT_OPS_REQUEST_PAYLOAD_TOO_LARGE');
    expect(mockedPost).not.toHaveBeenCalled();
  });
});
