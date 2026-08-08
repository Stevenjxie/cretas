import fs from 'fs';
import path from 'path';

const homeSource = fs.readFileSync(
  path.resolve(__dirname, '../../../screens/factory-admin/home/FAHomeScreen.tsx'),
  'utf8',
);
const homeStackSource = fs.readFileSync(
  path.resolve(__dirname, '../../../navigation/factory-admin/FAHomeStackNavigator.tsx'),
  'utf8',
);

describe('factory admin production workdesk navigation contract', () => {
  it('registers the production plan destination in the stack that owns the home screen', () => {
    expect(homeSource).toContain("navigation.navigate('ProductionPlanManagement', params)");
    expect(homeStackSource).toContain(
      'import ProductionPlanManagementScreen from "../../screens/processing/ProductionPlanManagementScreen"',
    );
    expect(homeStackSource).toContain('name="ProductionPlanManagement"');
    expect(homeStackSource).toContain('component={ProductionPlanManagementScreen}');
  });
});
