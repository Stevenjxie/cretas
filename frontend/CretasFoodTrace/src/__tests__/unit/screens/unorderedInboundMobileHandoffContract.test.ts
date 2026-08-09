import fs from 'fs';
import path from 'path';

const srcRoot = path.resolve(__dirname, '../../../');
const readSource = (relativeFromSrc: string): string =>
  fs.readFileSync(path.join(srcRoot, relativeFromSrc), 'utf8');

describe('unordered inbound mobile handoff contract', () => {
  const list = readSource('screens/warehouse/inbound/WHPurchaseReceiveListScreen.tsx');
  const receipt = readSource('screens/warehouse/inbound/WHUnorderedInboundReceiveScreen.tsx');
  const inboundHome = readSource('screens/warehouse/inbound/WHInboundListScreen.tsx');
  const navigator = readSource('navigation/warehouse/WHInboundStackNavigator.tsx');

  it('keeps the workflow inside the existing warehouse inbound entry', () => {
    expect(inboundHome).toContain('待收货任务');
    expect(list).toContain('采购与无订单入库统一处理');
    expect(navigator).toContain('name="WHUnorderedInboundReceive"');
  });

  it('uses shared server tasks and refreshes them when the screen regains focus', () => {
    expect(list).toContain('listCustomerMaterialArrivalTasks');
    expect(list).toContain('useFocusEffect');
    expect(list).toContain("navigation.navigate('WHUnorderedInboundReceive'");
  });

  it('uses searchable material and warehouse selectors without manual IDs', () => {
    expect(receipt).toContain('<MaterialSelectModal');
    expect(receipt).toContain('placeholder="搜索仓库名称或编码"');
    expect(receipt).not.toContain('label="原料ID');
    expect(receipt).not.toContain('label="仓库ID');
  });

  it('locks the unit to material master data and distinguishes partial from final receipt', () => {
    expect(receipt).toContain('selectedMaterial?.defaultUnit');
    expect(receipt).toContain('还有下一车，保留待办');
    expect(receipt).toContain('货已全部到齐，结束预告');
    expect(receipt).toContain('确认入库并结束预告？');
  });

  it('uses cross-platform dialogs instead of the no-op React Native Web Alert', () => {
    expect(receipt).toContain('<Dialog');
    expect(receipt).toContain('visible={confirmationVisible}');
    expect(receipt).toContain('visible={Boolean(successMessage)}');
    expect(receipt).not.toContain('Alert.alert');
  });

  it('does not route unordered inbound through quality inspection', () => {
    expect(receipt).not.toContain("navigate('WHInspect");
    expect(receipt).toContain('不进入生产前质检');
  });
});
