import { describe, expect, it } from 'vitest';
import { presentStockShortage } from '../processStockShortage';

describe('presentStockShortage', () => {
  it('localizes the structured backend payload and keeps the original message for diagnosis', () => {
    const raw = '后端可自由调整这段展示文案，请联系仓管补料';
    const view = presentStockShortage(raw, {
      items: [
        {
          materialTypeId: 'PKG-BOX',
          materialName: '包装盒',
          sourceType: 'PACKAGING',
          required: 2,
          available: 0,
          shortage: 2,
          unit: 'box',
        },
        {
          materialTypeId: 'PKG-LABEL',
          materialName: '标签',
          sourceType: 'PACKAGING',
          required: 1.25,
          available: 1,
          shortage: 0.25,
          unit: 'slice',
        },
      ],
    });

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
    const raw = '当前只能保存草稿，生产库中投料量不足。短缺明细：文案看起来像结构化数据，请联系仓管补料';
    const view = presentStockShortage(raw);

    expect(view.items).toEqual([]);
    expect(view.rawMessage).toBe(raw);
  });
});
