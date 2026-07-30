import { describe, expect, it } from 'vitest';
import { presentStockShortage } from '../processStockShortage';

describe('presentStockShortage', () => {
  it('localizes backend units and keeps the original message for diagnosis', () => {
    const raw = '当前只能保存草稿，生产库中投料量不足。短缺明细：包装盒（包材）：需要 2box，可用 0box，缺少 2box；标签（包材）：需要 1.25slice，可用 1slice，缺少 0.25slice，请联系仓管补料';
    const view = presentStockShortage(raw);

    expect(view.items).toEqual([
      {
        materialName: '包装盒（包材）',
        requiredText: '2 盒',
        availableText: '0 盒',
        shortageText: '2 盒',
      },
      {
        materialName: '标签（包材）',
        requiredText: '1.25 片',
        availableText: '1 片',
        shortageText: '0.25 片',
      },
    ]);
    expect(view.action).toBe('联系仓管补料');
    expect(view.rawMessage).toBe(raw);
  });

  it('does not invent material or quantity when the backend message has no detail list', () => {
    const raw = '当前只能保存草稿，生产库中投料量不足，请联系仓管补料';
    const view = presentStockShortage(raw);

    expect(view.items).toEqual([]);
    expect(view.rawMessage).toBe(raw);
  });
});
