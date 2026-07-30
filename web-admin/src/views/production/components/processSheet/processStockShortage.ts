import { displayProcessUnit } from '@/utils/processSheetUnits';

export interface StockShortageLine {
  materialName: string;
  requiredText: string;
  availableText: string;
  shortageText: string;
}

export interface StockShortagePresentation {
  title: string;
  items: StockShortageLine[];
  action: string;
  rawMessage: string;
}

function amountText(token: string): string {
  const match = token.trim().match(/^([+-]?\d+(?:\.\d+)?)\s*(.*)$/);
  if (!match) return token.trim();
  const unit = match[2].trim();
  return unit ? `${match[1]} ${displayProcessUnit(unit)}` : match[1];
}

/**
 * 后端错误文案保持原样；这里仅把其中的短缺明细整理成操作员可扫读的三段式展示。
 * 解析失败时仍保留 rawMessage，绝不猜物料或数量。
 */
export function presentStockShortage(message: string): StockShortagePresentation {
  const rawMessage = message.trim();
  const details = rawMessage.match(/短缺明细：(.+?)(?:，请联系仓管补料)?$/)?.[1] ?? '';
  const items = details.split('；').map((item) => item.trim()).filter(Boolean).flatMap((item) => {
    const match = item.match(/^(.+?)：需要\s*([^，]+)，可用\s*([^，]+)，缺少\s*([^，；]+)$/);
    if (!match) return [];
    return [{
      materialName: match[1].trim(),
      requiredText: amountText(match[2]),
      availableText: amountText(match[3]),
      shortageText: amountText(match[4]),
    }];
  });

  return {
    title: '生产库投料不足，本行只能保存草稿',
    items,
    action: rawMessage.includes('联系仓管补料') ? '联系仓管补料' : '核对生产库库存后再正式报工',
    rawMessage,
  };
}
