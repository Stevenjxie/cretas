/**
 * AI 初筛参考层。
 *
 * 模型除了给出"哪盒疑似缺标"的候选, 还知道每个盒子里实际识别到了哪些标签及其
 * 位置。把 盒子 / 白标 / 彩标 画成三层只读参考框, 质检员就能看到"白标在这、
 * 彩标在这、缺的那个位置是空的", 而不是只看到一个孤立的疑点框。
 *
 * 参考层不参与人工判定, 是纯背景信息 —— 所以任何解析异常都静默降级成"没有参考
 * 层", 绝不能让一段坏掉的明细 JSON 拖垮整个复核台。质检员宁可少看到辅助信息,
 * 也不能打不开审核页。
 *
 * 颜色与语义和 web-admin `LabelQcReviewWorkbench.vue` 的参考层保持一致, 两端
 * 看到的是同一套视觉约定。
 */
import { LabelQcBoundingBox } from '../../types/labelQc';

export type LabelQcScreenLayer = 'tray' | 'white' | 'color';

export interface LabelQcScreenLabelBox {
  type?: string;
  confidence?: number;
  bbox?: number[];
}

export interface LabelQcScreenTray {
  index: number;
  bbox?: number[];
  trayConfidence?: number;
  screenVerdict?: string;
  labels?: LabelQcScreenLabelBox[];
}

export interface LabelQcScreenReferenceBox {
  key: string;
  layer: LabelQcScreenLayer;
  color: string;
  bbox: LabelQcBoundingBox;
  /** 无障碍朗读用, 画面上不显示文字 —— 小框上叠字反而挡住证据 */
  caption: string;
}

export const LABEL_QC_LAYER_ORDER: LabelQcScreenLayer[] = ['tray', 'white', 'color'];

export const LABEL_QC_LAYER_META: Record<
  LabelQcScreenLayer,
  { text: string; color: string }
> = {
  tray: { text: '盒子', color: '#2F6FDD' },
  white: { text: '白标', color: '#06B6D4' },
  color: { text: '彩标', color: '#A855F7' },
};

export type LabelQcLayerVisibility = Record<LabelQcScreenLayer, boolean>;

export const LABEL_QC_ALL_LAYERS_VISIBLE: LabelQcLayerVisibility = {
  tray: true,
  white: true,
  color: true,
};

export function parseScreeningTrays(raw?: string | null): LabelQcScreenTray[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as { trays?: unknown };
    if (!Array.isArray(parsed?.trays)) return [];
    return parsed.trays.filter(
      (tray): tray is LabelQcScreenTray =>
        typeof tray === 'object' && tray !== null,
    );
  } catch {
    return [];
  }
}

/**
 * 归一化坐标 → 定位用的 bbox。模型给的是 [x0, y0, x1, y1]。
 * 只接受四个有限数且构成正面积的框: 退化框在屏幕上是一条看不见的线,
 * 留着只会让"我明明看到有标却没画出来"更难排查。
 */
function toBoundingBox(bbox?: number[]): LabelQcBoundingBox | null {
  if (!Array.isArray(bbox) || bbox.length !== 4) return null;
  const [xMin, yMin, xMax, yMax] = bbox;
  if (
    xMin === undefined
    || yMin === undefined
    || xMax === undefined
    || yMax === undefined
  ) {
    return null;
  }
  if (![xMin, yMin, xMax, yMax].every((value) => Number.isFinite(value))) {
    return null;
  }
  if (xMax <= xMin || yMax <= yMin) return null;
  return { xMin, yMin, xMax, yMax };
}

function formatConfidence(confidence?: number): string {
  return typeof confidence === 'number' && Number.isFinite(confidence)
    ? ` ${Math.round(confidence * 100)}%`
    : '';
}

export function buildScreeningReferenceBoxes(
  trays: LabelQcScreenTray[],
  visible: LabelQcLayerVisibility,
): LabelQcScreenReferenceBox[] {
  const boxes: LabelQcScreenReferenceBox[] = [];
  trays.forEach((tray, trayOrder) => {
    const index = Number.isFinite(tray.index) ? tray.index : trayOrder;
    if (visible.tray) {
      const trayBox = toBoundingBox(tray.bbox);
      if (trayBox) {
        boxes.push({
          key: `tray-${index}`,
          layer: 'tray',
          color: LABEL_QC_LAYER_META.tray.color,
          bbox: trayBox,
          caption: `盒子 ${index + 1}`,
        });
      }
    }
    (tray.labels ?? []).forEach((label, labelOrder) => {
      const layer: LabelQcScreenLayer = label.type === 'white' ? 'white' : 'color';
      if (!visible[layer]) return;
      const labelBox = toBoundingBox(label.bbox);
      if (!labelBox) return;
      boxes.push({
        key: `label-${index}-${labelOrder}`,
        layer,
        color: LABEL_QC_LAYER_META[layer].color,
        bbox: labelBox,
        caption: `盒子 ${index + 1} 的${LABEL_QC_LAYER_META[layer].text}${formatConfidence(
          label.confidence,
        )}`,
      });
    });
  });
  return boxes;
}
