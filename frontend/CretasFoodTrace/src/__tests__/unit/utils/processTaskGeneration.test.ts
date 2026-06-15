import { buildPlannedQuantitiesForProcesses } from '../../../utils/processTaskGeneration';

describe('buildPlannedQuantitiesForProcesses', () => {
  it('maps active reportable product work processes to the plan quantity', () => {
    const result = buildPlannedQuantitiesForProcesses(
      [
        { id: 1, productTypeId: 'PT-1', workProcessId: 'WP-CUT', isActive: true, reportingRequired: true },
        { id: 2, productTypeId: 'PT-1', workProcessId: 'WP-PACK', isActive: true },
        { id: 3, productTypeId: 'PT-1', workProcessId: 'WP-SKIP', isActive: true, reportingRequired: false },
        { id: 4, productTypeId: 'PT-1', workProcessId: 'WP-OFF', isActive: false, reportingRequired: true },
      ],
      380,
    );

    expect(result).toEqual({
      'WP-CUT': 380,
      'WP-PACK': 380,
    });
  });

  it('returns an empty map when quantity is not positive', () => {
    expect(buildPlannedQuantitiesForProcesses(
      [{ id: 1, productTypeId: 'PT-1', workProcessId: 'WP-CUT', isActive: true }],
      0,
    )).toEqual({});
  });
});
