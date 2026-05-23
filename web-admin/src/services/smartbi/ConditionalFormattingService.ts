/**
 * ConditionalFormattingService - SmartBI 条件格式化服务
 * 支持 threshold / gradient / iconSet / dataBar 四种规则类型
 */

// ==================== Type Definitions ====================

export interface ThresholdCondition {
  operator: '>' | '<' | '>=' | '<=' | '==' | 'between';
  value: number | [number, number];
  style: {
    backgroundColor?: string;
    color?: string;
    fontWeight?: string;
    icon?: string;
  };
}

export interface GradientStop {
  value: number;
  color: string;
}

export interface FormatRule {
  id: string;
  field: string;
  type: 'threshold' | 'gradient' | 'iconSet' | 'dataBar';

  /** threshold */
  conditions?: ThresholdCondition[];

  /** gradient */
  gradient?: {
    min: GradientStop;
    mid?: GradientStop;
    max: GradientStop;
  };

  /** iconSet */
  iconSet?: {
    type: 'arrows' | 'traffic' | 'stars' | 'flags';
    thresholds: number[];
  };

  /** dataBar */
  dataBar?: {
    minValue?: number;
    maxValue?: number;
    positiveColor: string;
    negativeColor: string;
  };
}

export interface CellStyle {
  backgroundColor?: string;
  color?: string;
  fontWeight?: string;
  icon?: string;
}

// ==================== Icon Sets ====================

const ICON_SETS: Record<string, string[]> = {
  arrows: ['↑', '→', '↓'],          // 3 levels: up, flat, down
 traffic: ['', '', ''], // 3 levels: green, yellow, red
  stars: ['⭐⭐⭐', '⭐⭐', '⭐'],    // 3 levels
 flags: ['', '️', ''], // 3 levels
};

// ==================== Color Interpolation ====================

/** Parse hex color string to RGB */
function hexToRgb(hex: string): [number, number, number] | null {
  const clean = hex.replace('#', '');
  if (clean.length === 3) {
    const r = parseInt(clean[0] + clean[0], 16);
    const g = parseInt(clean[1] + clean[1], 16);
    const b = parseInt(clean[2] + clean[2], 16);
    return [r, g, b];
  }
  if (clean.length === 6) {
    return [
      parseInt(clean.substring(0, 2), 16),
      parseInt(clean.substring(2, 4), 16),
      parseInt(clean.substring(4, 6), 16),
    ];
  }
  return null;
}

/** Linearly interpolate between two colors at ratio t ∈ [0, 1] */
function lerpColor(a: string, b: string, t: number): string {
  const rgbA = hexToRgb(a) ?? [255, 255, 255];
  const rgbB = hexToRgb(b) ?? [255, 255, 255];
  const r = Math.round(rgbA[0] + (rgbB[0] - rgbA[0]) * t);
  const g = Math.round(rgbA[1] + (rgbB[1] - rgbA[1]) * t);
  const bl = Math.round(rgbA[2] + (rgbB[2] - rgbA[2]) * t);
  return `rgb(${r}, ${g}, ${bl})`;
}

// ==================== ConditionalFormattingService Class ====================

export class ConditionalFormattingService {
  private rules: Map<string, FormatRule[]> = new Map();

  // ---------- Rule Management ----------

  addRule(tableId: string, rule: FormatRule): void {
    if (!this.rules.has(tableId)) {
      this.rules.set(tableId, []);
    }
    const list = this.rules.get(tableId)!;
    const idx = list.findIndex(r => r.id === rule.id);
    if (idx >= 0) {
      list[idx] = rule;
    } else {
      list.push(rule);
    }
  }

  removeRule(tableId: string, ruleId: string): void {
    const list = this.rules.get(tableId);
    if (!list) return;
    const idx = list.findIndex(r => r.id === ruleId);
    if (idx >= 0) list.splice(idx, 1);
  }

  getRules(tableId: string): FormatRule[] {
    return this.rules.get(tableId) ?? [];
  }

  clearRules(tableId: string): void {
    this.rules.delete(tableId);
  }

  // ---------- Cell Evaluation ----------

  evaluateCell(tableId: string, field: string, value: number): CellStyle {
    const rules = this.getRules(tableId).filter(r => r.field === field);
    const result: CellStyle = {};

    for (const rule of rules) {
      if (rule.type === 'threshold' && rule.conditions) {
        for (const cond of rule.conditions) {
          if (this._matchCondition(value, cond)) {
            Object.assign(result, cond.style);
            break;
          }
        }
      } else if (rule.type === 'gradient' && rule.gradient) {
        const color = this.interpolateColor(value, rule.gradient);
        result.backgroundColor = color;
        // choose text contrast
        result.color = this._contrastText(color);
      }
      // dataBar and iconSet don't produce backgroundColor in cell style directly
      // (they're rendered via dedicated methods)
    }

    return result;
  }

  getIcon(tableId: string, field: string, value: number): string {
    const rules = this.getRules(tableId).filter(r => r.field === field && r.type === 'iconSet');
    for (const rule of rules) {
      if (!rule.iconSet) continue;
      const icons = ICON_SETS[rule.iconSet.type] ?? ICON_SETS.arrows;
      const thresholds = rule.iconSet.thresholds;
      // thresholds split value space into (thresholds.length + 1) buckets
      // icons[0] = top bucket, icons[last] = bottom bucket
      let level = icons.length - 1;
      for (let i = 0; i < thresholds.length; i++) {
        if (value >= thresholds[i]) {
          level = i;
          break;
        }
      }
      return icons[Math.min(level, icons.length - 1)];
    }
    return '';
  }

  getDataBarWidth(tableId: string, field: string, value: number): { width: number; color: string } {
    const rules = this.getRules(tableId).filter(r => r.field === field && r.type === 'dataBar');
    for (const rule of rules) {
      if (!rule.dataBar) continue;
      const { minValue = 0, maxValue = 100, positiveColor, negativeColor } = rule.dataBar;
      const range = maxValue - minValue || 1;
      const width = Math.min(100, Math.max(0, ((value - minValue) / range) * 100));
      const color = value >= 0 ? positiveColor : negativeColor;
      return { width, color };
    }
    return { width: 0, color: '#2D8B57' };
  }

  interpolateColor(value: number, gradient: FormatRule['gradient']): string {
    if (!gradient) return '#ffffff';
    const { min, mid, max } = gradient;

    if (value <= min.value) return min.color;
    if (value >= max.value) return max.color;

    if (mid) {
      if (value <= mid.value) {
        const t = (value - min.value) / (mid.value - min.value || 1);
        return lerpColor(min.color, mid.color, t);
      } else {
        const t = (value - mid.value) / (max.value - mid.value || 1);
        return lerpColor(mid.color, max.color, t);
      }
    }

    const t = (value - min.value) / (max.value - min.value || 1);
    return lerpColor(min.color, max.color, t);
  }

  // ---------- Private Helpers ----------

  private _matchCondition(value: number, cond: ThresholdCondition): boolean {
    const { operator, value: condVal } = cond;
    if (operator === 'between') {
      const [lo, hi] = condVal as [number, number];
      return value >= lo && value <= hi;
    }
    const v = condVal as number;
    switch (operator) {
      case '>': return value > v;
      case '<': return value < v;
      case '>=': return value >= v;
      case '<=': return value <= v;
      case '==': return value === v;
      default: return false;
    }
  }

  /** Choose black or white text based on background luminance */
  private _contrastText(bg: string): string {
    // Try to parse rgb(...) or #hex
    let r = 255, g = 255, b = 255;
    const rgbMatch = bg.match(/rgb\((\d+),\s*(\d+),\s*(\d+)\)/);
    if (rgbMatch) {
      r = parseInt(rgbMatch[1]);
      g = parseInt(rgbMatch[2]);
      b = parseInt(rgbMatch[3]);
    } else {
      const hex = hexToRgb(bg);
      if (hex) [r, g, b] = hex;
    }
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luminance > 0.6 ? '#303133' : '#ffffff';
  }

  // ---------- Preset Templates ----------

  static readonly presets: {
    achievementRate: FormatRule;
    growthRate: FormatRule;
    trafficLight: FormatRule;
    variance: FormatRule;
  } = {
    achievementRate: {
      id: 'preset-achievement',
      field: 'achievementRate',
      type: 'threshold',
      conditions: [
        {
          operator: '>=',
          value: 100,
          style: { backgroundColor: '#d4f7dc', color: '#1a7a3c', fontWeight: '600' },
        },
        {
          operator: 'between',
          value: [80, 99.99],
          style: { backgroundColor: '#fff7d6', color: '#8a6000', fontWeight: '500' },
        },
        {
          operator: '<',
          value: 80,
          style: { backgroundColor: '#fde8e8', color: '#c0392b', fontWeight: '600' },
        },
      ],
    },
    growthRate: {
      id: 'preset-growth',
      field: 'growthRate',
      type: 'threshold',
      conditions: [
        {
          operator: '>',
          value: 0,
          style: { backgroundColor: '#d4f7dc', color: '#1a7a3c', icon: '▲' },
        },
        {
          operator: '==',
          value: 0,
          style: { backgroundColor: '#f5f5f5', color: '#909399' },
        },
        {
          operator: '<',
          value: 0,
          style: { backgroundColor: '#fde8e8', color: '#c0392b', icon: '▼' },
        },
      ],
    },
    trafficLight: {
      id: 'preset-traffic',
      field: 'status',
      type: 'iconSet',
      iconSet: {
        type: 'traffic',
        thresholds: [80, 60],
      },
    },
    variance: {
      id: 'preset-variance',
      field: 'variance',
      type: 'gradient',
      gradient: {
        min: { value: -100, color: '#f56c6c' },
        mid: { value: 0, color: '#ffffff' },
        max: { value: 100, color: '#36B37E' },
      },
    },
  };
}

// ==================== Singleton ====================

let _instance: ConditionalFormattingService | null = null;

export function getConditionalFormattingService(): ConditionalFormattingService {
  if (!_instance) _instance = new ConditionalFormattingService();
  return _instance;
}
