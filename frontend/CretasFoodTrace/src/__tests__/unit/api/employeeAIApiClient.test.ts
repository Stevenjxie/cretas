// @ts-nocheck

import MockAdapter from 'axios-mock-adapter';
import { apiClient } from '../../../services/api/apiClient';
import { employeeAIApiClient } from '../../../services/api/employeeAIApiClient';

const FACTORY_ID = 'F-EMPLOYEE-AI-API';
const BASE = `/api/mobile/${FACTORY_ID}/ai/analysis/employee/21`;

describe('employeeAIApiClient request contract', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient as any);
  });

  afterEach(() => {
    mock.restore();
  });

  it('sends analyze options as request params with no JSON body', async () => {
    const responseData = { employeeId: 21, aiInsight: null };
    mock.onPost(BASE).reply(200, {
      code: 200,
      message: 'ok',
      data: responseData,
    });

    const result = await employeeAIApiClient.analyzeEmployee(
      21,
      {
        days: 45,
        question: '只解释已有事实',
        sessionId: 'session-analyze',
      },
      FACTORY_ID
    );

    expect(result).toEqual(responseData);
    expect(mock.history.post).toHaveLength(1);
    expect(mock.history.post[0].params).toEqual({
      days: 45,
      question: '只解释已有事实',
      sessionId: 'session-analyze',
    });
    expect(mock.history.post[0].data).toBeNull();
  });

  it('sends followup session id as a request param and only question in the body', async () => {
    const responseData = { employeeId: 21, aiInsight: '追问回答' };
    mock.onPost(`${BASE}/followup`).reply(200, {
      code: 200,
      message: 'ok',
      data: responseData,
    });

    const result = await employeeAIApiClient.followupAnalysis(
      21,
      { sessionId: 'session-followup', question: '这个计数来自哪里？' },
      FACTORY_ID
    );

    expect(result).toEqual(responseData);
    expect(mock.history.post).toHaveLength(1);
    expect(mock.history.post[0].params).toEqual({ sessionId: 'session-followup' });
    expect(JSON.parse(mock.history.post[0].data)).toEqual({
      question: '这个计数来自哪里？',
    });
  });
});
