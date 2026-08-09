import fs from 'fs';
import path from 'path';

const screenSource = fs.readFileSync(
  path.resolve(__dirname, '../../../screens/processing/ProductionPlanManagementScreen.tsx'),
  'utf8',
);

describe('production plan customer display contract', () => {
  it('shows the persisted source customer name before legacy nested customer data', () => {
    expect(screenSource).toContain(
      "plan.sourceCustomerName || plan.customerName || plan.customer?.name || '未指定'",
    );
  });
});
