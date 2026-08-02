/**
 * QuickActionCardGrid unit tests
 * Tests ROLE_CARDS configuration logic without full component rendering.
 * Uses manual react-native mock (__mocks__/react-native.js) and calls
 * the component function directly to inspect the React element tree.
 */

/* eslint-disable @typescript-eslint/no-explicit-any */

jest.mock('@expo/vector-icons', () => ({
  MaterialCommunityIcons: 'MaterialCommunityIcons',
}));

import QuickActionCardGrid from '../../../components/ai/QuickActionCardGrid';

function getRenderedTree(userRole: string, isRestaurantMode = false) {
  const mockOnSendIntent = jest.fn();
  const mockOnNavigate = jest.fn();
  const element = (QuickActionCardGrid as any)({
    userRole,
    isRestaurantMode,
    onSendIntent: mockOnSendIntent,
    onNavigate: mockOnNavigate,
  });
  return { element, mockOnSendIntent, mockOnNavigate };
}

function getCards(element: any): any[] {
  if (!element || !element.props || !element.props.children) return [];
  return element.props.children;
}

function getCardLabel(card: any): string {
  const children = card.props.children;
  const labelText = children[1]; // [View(icon), Text(label), Text(desc)]
  return labelText.props.children;
}

describe('QuickActionCardGrid card configs', () => {
  it('FA role renders 6 cards', () => {
    const { element } = getRenderedTree('factory_super_admin');
    expect(getCards(element)).toHaveLength(6);
  });

  it('WS role renders 4 cards', () => {
    const { element } = getRenderedTree('workshop_supervisor');
    expect(getCards(element)).toHaveLength(4);
  });

  it('WM role renders 4 cards', () => {
    const { element } = getRenderedTree('warehouse_manager');
    expect(getCards(element)).toHaveLength(4);
  });

  it('unknown role returns null', () => {
    const { element } = getRenderedTree('unknown_role');
    expect(element).toBeNull();
  });

  it('restaurant mode overrides the factory role with six restaurant analysis cards', () => {
    const cards = getCards(getRenderedTree('factory_super_admin', true).element);
    expect(cards).toHaveLength(6);
    expect(cards.map(getCardLabel)).toEqual([
      '今日经营', '门店对比', '菜品表现', '损耗分析', '库存风险', '经营建议',
    ]);
  });

  it('restaurant cards send concrete LLM questions and never navigate to factory screens', () => {
    const { element, mockOnSendIntent, mockOnNavigate } = getRenderedTree('factory_super_admin', true);
    getCards(element)[1].props.onPress();
    expect(mockOnSendIntent).toHaveBeenCalledWith(expect.stringContaining('最近7天各门店'));
    expect(mockOnNavigate).not.toHaveBeenCalled();
  });

  it('FA cards have correct labels', () => {
    const expected = ['生产报工', '原料入库', '查库存', 'BOM达成率', '成本分析', '毛利率'];
    const cards = getCards(getRenderedTree('factory_super_admin').element);
    cards.forEach((card: any, i: number) => {
      expect(getCardLabel(card)).toBe(expected[i]);
    });
  });

  it('WS cards have correct labels', () => {
    const expected = ['生产报工', '我的任务', '人员状态', '设备状态'];
    const cards = getCards(getRenderedTree('workshop_supervisor').element);
    cards.forEach((card: any, i: number) => {
      expect(getCardLabel(card)).toBe(expected[i]);
    });
  });

  it('WM cards have correct labels', () => {
    const expected = ['原料入库', '出库发货', '查库存', '低库存'];
    const cards = getCards(getRenderedTree('warehouse_manager').element);
    cards.forEach((card: any, i: number) => {
      expect(getCardLabel(card)).toBe(expected[i]);
    });
  });

  it('FA card-0 calls onSendIntent("我要报工")', () => {
    const { element, mockOnSendIntent } = getRenderedTree('factory_super_admin');
    getCards(element)[0].props.onPress();
    expect(mockOnSendIntent).toHaveBeenCalledWith('我要报工');
  });

  it('FA card-3 (BOM达成率) calls onNavigate("BomAchievement")', () => {
    const { element, mockOnNavigate } = getRenderedTree('factory_super_admin');
    getCards(element)[3].props.onPress();
    expect(mockOnNavigate).toHaveBeenCalledWith('BomAchievement');
  });

  it('WS card-1 (我的任务) calls onNavigate("ProcessTaskList")', () => {
    const { element, mockOnNavigate } = getRenderedTree('workshop_supervisor');
    getCards(element)[1].props.onPress();
    expect(mockOnNavigate).toHaveBeenCalledWith('ProcessTaskList');
  });

  it('WM card-0 (原料入库) calls onNavigate("MaterialReceiptAI")', () => {
    const { element, mockOnNavigate } = getRenderedTree('warehouse_manager');
    getCards(element)[0].props.onPress();
    expect(mockOnNavigate).toHaveBeenCalledWith('MaterialReceiptAI');
  });
});
