import MockAdapter from 'axios-mock-adapter';

import { labelQcApi } from '../../../services/api/labelQcApi';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import { LabelQcReviewTaskRequest } from '../../../types/labelQc';

const FACTORY_ID = 'F006';
const BASE = `/api/mobile/${FACTORY_ID}/label-qc`;

describe('labelQcApi mobile review contract', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = createApiMock();
  });

  afterEach(() => {
    resetApiMock(mock);
  });

  it('loads the mobile review queue with comma-separated task statuses', async () => {
    const page = {
      content: [],
      page: 1,
      currentPage: 1,
      size: 50,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    };
    mock.onGet(`${BASE}/tasks`).reply(200, {
      success: true,
      data: page,
      message: 'ok',
    });

    await expect(
      labelQcApi.listTasks(
        { statuses: ['NEEDS_REVIEW', 'ANALYSIS_FAILED'], page: 1, size: 50 },
        FACTORY_ID,
      ),
    ).resolves.toEqual(page);
    expect(mock.history.get[0]!.params).toEqual({
      statuses: 'NEEDS_REVIEW,ANALYSIS_FAILED',
      archived: false,
      page: 1,
      size: 50,
    });
  });

  it('submits AI decisions and human additions to the existing review endpoint', async () => {
    const request: LabelQcReviewTaskRequest = {
      expectedVersion: 3,
      reviewRequestId: 'review-device-a',
      photos: [
        {
          photoId: 'photo-1',
          annotations: [
            {
              annotationId: 'ai-1',
              label: 'MISSING_WHITE_LABEL',
              bbox: { xMin: 0.1, yMin: 0.2, xMax: 0.3, yMax: 0.4 },
            },
            {
              label: 'MISSING_COLOR_LABEL',
              bbox: { xMin: 0.5, yMin: 0.6, xMax: 0.8, yMax: 0.9 },
              notes: '移动端人工补框',
            },
          ],
        },
      ],
    };
    const detail = {
      task: { id: 'task-1', status: 'REVIEWED' },
      photos: [],
    };
    mock.onPut(`${BASE}/tasks/task-1/review`).reply(200, {
      success: true,
      data: detail,
      message: '人工审核已完成',
    });

    await expect(
      labelQcApi.reviewTask('task-1', request, FACTORY_ID),
    ).resolves.toEqual(detail);
    expect(JSON.parse(mock.history.put[0]!.data)).toEqual(request);
  });
});
