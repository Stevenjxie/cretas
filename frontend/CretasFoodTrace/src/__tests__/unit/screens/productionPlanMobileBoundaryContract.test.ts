import fs from 'fs';
import path from 'path';

const screenSource = fs.readFileSync(
  path.resolve(__dirname, '../../../screens/processing/ProductionPlanManagementScreen.tsx'),
  'utf8',
);

describe('production plan mobile boundary', () => {
  it('does not model a separate start-production action', () => {
    expect(screenSource).not.toContain('handleStartProduction');
    expect(screenSource).not.toContain('确认开始生产');
  });

  it('explains the PC and operator handoff', () => {
    expect(screenSource).toContain('创建或调整生产计划、结单请使用 PC');
    expect(screenSource).toContain('现场操作员在各自任务中录入工序报工');
  });

  it('guards all completion UI with the mobile completion permission', () => {
    expect(screenSource).toContain("canCompletePlan && plan.status?.toLowerCase() === 'pending'");
    expect(screenSource).toContain("canCompletePlan && plan.status?.toLowerCase() === 'in_progress'");
    expect(screenSource).toContain('visible={canCompletePlan && showCompleteDialog}');
  });
});
