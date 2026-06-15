/**
 * 批次标签打印服务
 *
 * 方案 A: expo-print HTML → 系统打印对话框 (Print.printAsync)
 *
 * 热敏标签规格: 57mm / 80mm 宽纸 (两种常见仓库标签)
 * 系统打印对话框支持: 蓝牙热敏打印机 / WiFi 打印机 / AirPrint — 无需 App 层 BLE 协议
 *
 * QR 生成: 使用 qrcode npm 包 (纯 JS, 无 native, OTA 安全)
 *   toString(text, { type: 'svg' }) → 返回 SVG 字符串, 直接内嵌 HTML
 *
 * 使用方:
 *   const ok = await printBatchLabel({ materialName, batchNumber, ... });
 */
import * as Print from 'expo-print';

// qrcode 无官方 TS 类型, 用 require + 局部类型声明绕过
// eslint-disable-next-line @typescript-eslint/no-var-requires
const QRCode = require('qrcode') as {
  toString(text: string, options: { type: 'svg'; width?: number; margin?: number }): Promise<string>;
  toDataURL(text: string, options?: { type?: string; width?: number }): Promise<string>;
};

export interface BatchLabelData {
  materialName: string;
  batchNumber: string;
  factoryNumber?: string | null;
  originPlace?: string | null;
  quantity?: number | null;
  quantityUnit?: string | null;
  /** 二维码内容 (traceCode 或 labelCode) */
  qrContent?: string | null;
  /** 标签生成时间 */
  labelCreatedAt?: string | null;
}

/**
 * 生成热敏标签 HTML (async — 需要先生成 QR SVG)
 * 适配 57mm / 80mm 宽打印纸: 高 60mm 简洁布局
 * QR 码: qrcode.toString(type:'svg') 返回内联 SVG, 可直接嵌入 HTML img/div
 */
async function buildLabelHtml(data: BatchLabelData): Promise<string> {
  const {
    materialName,
    batchNumber,
    factoryNumber,
    originPlace,
    quantity,
    quantityUnit,
    qrContent,
    labelCreatedAt,
  } = data;

  const qtyLine =
    quantity != null && quantityUnit
      ? `${quantity} ${quantityUnit}`
      : quantity != null
      ? String(quantity)
      : '';

  const dateStr = labelCreatedAt
    ? labelCreatedAt.replace('T', ' ').slice(0, 16)
    : new Date().toISOString().replace('T', ' ').slice(0, 16);

  const qrText = qrContent ?? batchNumber;

  // 生成真实 QR 码 SVG (纯 JS, 无 native 依赖, OTA 安全)
  // width=68 对应约 18mm@96dpi, margin=1 留最小静区
  let qrSvg: string;
  try {
    qrSvg = await QRCode.toString(qrText, { type: 'svg', width: 68, margin: 1 });
  } catch {
    // QR 生成失败: 降级为文字占位 (不阻断打印)
    const qrDisplay = qrText.length > 20 ? qrText.slice(0, 20) + '…' : qrText;
    qrSvg = `<div class="qr-fallback">${escapeHtml(qrDisplay)}</div>`;
  }

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
    width: 72mm;          /* 80mm 纸扣边距; 57mm 纸改 50mm */
    padding: 4mm 4mm 4mm 4mm;
    font-size: 9pt;
    line-height: 1.4;
    color: #000;
  }
  .title {
    font-size: 13pt;
    font-weight: 700;
    letter-spacing: 0.5px;
    margin-bottom: 2mm;
    word-break: break-all;
  }
  .row {
    display: flex;
    justify-content: space-between;
    margin-bottom: 1mm;
  }
  .label { color: #444; }
  .value { font-weight: 600; }
  .divider {
    border-top: 1px dashed #bbb;
    margin: 2mm 0;
  }
  .qr-block {
    display: flex;
    align-items: center;
    gap: 3mm;
    margin-top: 2mm;
  }
  .qr-svg-wrap {
    width: 18mm;
    height: 18mm;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .qr-svg-wrap svg {
    width: 100%;
    height: 100%;
  }
  .qr-fallback {
    width: 18mm;
    height: 18mm;
    border: 1.5px solid #000;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    font-size: 6pt;
    text-align: center;
    word-break: break-all;
    padding: 2px;
    color: #333;
  }
  .qr-caption {
    font-size: 6pt;
    color: #666;
    word-break: break-all;
  }
  .batch-num {
    font-size: 11pt;
    font-weight: 700;
    letter-spacing: 1px;
  }
  .footer {
    font-size: 7pt;
    color: #777;
    margin-top: 2mm;
    text-align: right;
  }
</style>
</head>
<body>
  <div class="title">${escapeHtml(materialName)}</div>
  <div class="divider"></div>
  <div class="row">
    <span class="label">批次号</span>
    <span class="batch-num">${escapeHtml(batchNumber)}</span>
  </div>
  ${qtyLine ? `<div class="row"><span class="label">数量</span><span class="value">${escapeHtml(qtyLine)}</span></div>` : ''}
  ${factoryNumber ? `<div class="row"><span class="label">厂号</span><span class="value">${escapeHtml(factoryNumber)}</span></div>` : ''}
  ${originPlace ? `<div class="row"><span class="label">产地</span><span class="value">${escapeHtml(originPlace)}</span></div>` : ''}
  <div class="divider"></div>
  <div class="qr-block">
    <div class="qr-svg-wrap">${qrSvg}</div>
    <div class="qr-caption">扫码追溯<br/>${escapeHtml(qrText)}</div>
  </div>
  <div class="footer">白垩纪食品溯源 · ${escapeHtml(dateStr)}</div>
</body>
</html>`;
}

function escapeHtml(s: string | null | undefined): string {
  if (!s) return '';
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/**
 * 调用系统打印对话框打印批次标签
 * @returns true 表示打印流程已触发 (用户可能在对话框取消, 取消不视为错误)
 * @throws 非取消原因的错误会 rethrow
 */
export async function printBatchLabel(data: BatchLabelData): Promise<boolean> {
  const html = await buildLabelHtml(data);
  await Print.printAsync({ html });
  return true;
}
