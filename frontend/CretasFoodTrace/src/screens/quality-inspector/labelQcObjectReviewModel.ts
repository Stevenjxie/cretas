import {
  LabelQcBoundingBox,
  LabelQcObjectReviewItem,
  LabelQcObjectReviewPayload,
  LabelQcObjectType,
  LabelQcPhoto,
  LabelQcPresence,
  LabelQcTrayObjectReview,
} from '../../types/labelQc';

export interface ObjectReviewItemDraft extends LabelQcObjectReviewItem {
  key: string;
}

export interface TrayObjectReviewDraft extends Omit<LabelQcTrayObjectReview, 'labels'> {
  key: string;
  confirmed: boolean;
  labels: ObjectReviewItemDraft[];
}

export interface PhotoObjectReviewDraft {
  photoId: string;
  trays: TrayObjectReviewDraft[];
  rejectedAiTrayKeys: string[];
}

type RawLabel = { type?: string; bbox?: number[] };
type RawTray = {
  index?: number;
  bbox?: number[];
  hasWhite?: boolean;
  hasColor?: boolean;
  labels?: RawLabel[];
};

const validBox = (value?: number[]): LabelQcBoundingBox | null => {
  if (!value || value.length !== 4 || value.some((part) => !Number.isFinite(part))) return null;
  const [xMin, yMin, xMax, yMax] = value;
  if (xMin === undefined || yMin === undefined || xMax === undefined || yMax === undefined) return null;
  if (xMin < 0 || yMin < 0 || xMax > 1 || yMax > 1 || xMax <= xMin || yMax <= yMin) return null;
  return { xMin, yMin, xMax, yMax };
};

const parseTrays = (raw?: string): RawTray[] => {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as { trays?: unknown };
    return Array.isArray(parsed.trays) ? parsed.trays as RawTray[] : [];
  } catch {
    return [];
  }
};

const initialPresence = (explicit: boolean | undefined, count: number): LabelQcPresence => (
  explicit === false && count === 0 ? 'MISSING' : count > 0 || explicit === true ? 'PRESENT' : 'UNJUDGEABLE'
);

export const hydrateObjectReviewDraft = (photo: LabelQcPhoto): PhotoObjectReviewDraft => {
  if (photo.objectReview) {
    return {
      photoId: photo.id,
      rejectedAiTrayKeys: [...photo.objectReview.rejectedAiTrayKeys],
      trays: photo.objectReview.trays.map((tray) => ({
        ...tray,
        key: tray.aiTrayKey ?? `human-tray-${tray.trayIndex}`,
        confirmed: true,
        rejectedAiObjectKeys: [...tray.rejectedAiObjectKeys],
        labels: tray.labels.map((label, index) => ({
          ...label,
          key: label.aiObjectKey ?? `human-label-${tray.trayIndex}-${index}`,
        })),
      })),
    };
  }
  const trays = parseTrays(photo.screeningDetail).flatMap((rawTray, order) => {
    const trayIndex = Number.isInteger(rawTray.index) ? rawTray.index! : order;
    const bbox = validBox(rawTray.bbox);
    if (!bbox) return [];
    const labels = (rawTray.labels ?? []).flatMap((rawLabel, labelIndex) => {
      const labelBox = validBox(rawLabel.bbox);
      if (!labelBox || (rawLabel.type !== 'white' && rawLabel.type !== 'color')) return [];
      return [{
        key: `label-${trayIndex}-${labelIndex}`,
        aiObjectKey: `label-${trayIndex}-${labelIndex}`,
        type: rawLabel.type === 'white' ? 'WHITE_LABEL' as const : 'COLOR_LABEL' as const,
        bbox: labelBox,
        decision: 'CONFIRMED' as const,
        truncated: false,
      }];
    });
    const whiteCount = labels.filter((label) => label.type === 'WHITE_LABEL').length;
    const colorCount = labels.filter((label) => label.type === 'COLOR_LABEL').length;
    return [{
      key: `tray-${trayIndex}`,
      trayIndex,
      aiTrayKey: `tray-${trayIndex}`,
      bbox,
      decision: 'CONFIRMED' as const,
      whitePresence: initialPresence(rawTray.hasWhite, whiteCount),
      colorPresence: initialPresence(rawTray.hasColor, colorCount),
      labels,
      rejectedAiObjectKeys: [] as string[],
      confirmed: true,
    }];
  });
  return { photoId: photo.id, trays, rejectedAiTrayKeys: [] };
};

export const setObjectPresence = (
  draft: PhotoObjectReviewDraft,
  trayKey: string,
  type: LabelQcObjectType,
  presence: LabelQcPresence,
): PhotoObjectReviewDraft => ({
  ...draft,
  trays: draft.trays.map((tray) => {
    if (tray.key !== trayKey) return tray;
    const removed = presence === 'MISSING'
      ? tray.labels.filter((label) => label.type === type)
      : [];
    return {
      ...tray,
      confirmed: false,
      whitePresence: type === 'WHITE_LABEL' ? presence : tray.whitePresence,
      colorPresence: type === 'COLOR_LABEL' ? presence : tray.colorPresence,
      labels: presence === 'MISSING'
        ? tray.labels.filter((label) => label.type !== type)
        : tray.labels,
      rejectedAiObjectKeys: [
        ...new Set([
          ...tray.rejectedAiObjectKeys,
          ...removed.flatMap((label) => label.aiObjectKey ? [label.aiObjectKey] : []),
        ]),
      ],
    };
  }),
});

export const addMobileObjectLabel = (
  draft: PhotoObjectReviewDraft,
  trayKey: string,
  type: LabelQcObjectType,
  key: string,
): PhotoObjectReviewDraft => ({
  ...draft,
  trays: draft.trays.map((tray) => {
    if (tray.key !== trayKey) return tray;
    const width = (tray.bbox.xMax - tray.bbox.xMin) * 0.35;
    const height = (tray.bbox.yMax - tray.bbox.yMin) * 0.25;
    const centerX = (tray.bbox.xMin + tray.bbox.xMax) / 2;
    const centerY = (tray.bbox.yMin + tray.bbox.yMax) / 2;
    const label: ObjectReviewItemDraft = {
      key,
      type,
      bbox: {
        xMin: centerX - width / 2,
        yMin: centerY - height / 2,
        xMax: centerX + width / 2,
        yMax: centerY + height / 2,
      },
      decision: 'ADDED',
      truncated: false,
    };
    return {
      ...tray,
      confirmed: false,
      whitePresence: type === 'WHITE_LABEL' ? 'PRESENT' : tray.whitePresence,
      colorPresence: type === 'COLOR_LABEL' ? 'PRESENT' : tray.colorPresence,
      labels: [...tray.labels, label],
    };
  }),
});

export const removeMobileObjectLabel = (
  draft: PhotoObjectReviewDraft,
  trayKey: string,
  objectKey: string,
): PhotoObjectReviewDraft => ({
  ...draft,
  trays: draft.trays.map((tray) => {
    if (tray.key !== trayKey) return tray;
    const removed = tray.labels.find((label) => label.key === objectKey);
    if (!removed) return tray;
    return {
      ...tray,
      confirmed: false,
      labels: tray.labels.filter((label) => label.key !== objectKey),
      rejectedAiObjectKeys: removed.aiObjectKey
        ? [...new Set([...tray.rejectedAiObjectKeys, removed.aiObjectKey])]
        : tray.rejectedAiObjectKeys,
      whitePresence: removed.type === 'WHITE_LABEL'
        && tray.labels.filter((label) => label.type === 'WHITE_LABEL' && label.key !== objectKey).length === 0
        ? 'UNJUDGEABLE'
        : tray.whitePresence,
      colorPresence: removed.type === 'COLOR_LABEL'
        && tray.labels.filter((label) => label.type === 'COLOR_LABEL' && label.key !== objectKey).length === 0
        ? 'UNJUDGEABLE'
        : tray.colorPresence,
    };
  }),
});

export const updateMobileObjectLabelBox = (
  draft: PhotoObjectReviewDraft,
  trayKey: string,
  objectKey: string,
  bbox: LabelQcBoundingBox,
): PhotoObjectReviewDraft => ({
  ...draft,
  trays: draft.trays.map((tray) => {
    if (tray.key !== trayKey) return tray;
    return {
      ...tray,
      confirmed: false,
      labels: tray.labels.map((label) => label.key === objectKey
        ? {
          ...label,
          bbox,
          decision: label.decision === 'CONFIRMED' ? 'CORRECTED' : label.decision,
        }
        : label),
    };
  }),
});

export const validateObjectTray = (tray: TrayObjectReviewDraft): string | null => {
  const whiteCount = tray.labels.filter((label) => label.type === 'WHITE_LABEL').length;
  const colorCount = tray.labels.filter((label) => label.type === 'COLOR_LABEL').length;
  if (tray.whitePresence === 'PRESENT' && whiteCount === 0) return '选择“有白标”后需要保留或补一个白标框';
  if (tray.whitePresence === 'MISSING' && whiteCount > 0) return '选择“缺白标”前需要删除白标框';
  if (tray.colorPresence === 'PRESENT' && colorCount === 0) return '选择“有彩标”后需要保留或补一个彩标框';
  if (tray.colorPresence === 'MISSING' && colorCount > 0) return '选择“缺彩标”前需要删除彩标框';
  const outside = tray.labels.some((label) => {
    const centerX = (label.bbox.xMin + label.bbox.xMax) / 2;
    const centerY = (label.bbox.yMin + label.bbox.yMax) / 2;
    return centerX < tray.bbox.xMin || centerX > tray.bbox.xMax
      || centerY < tray.bbox.yMin || centerY > tray.bbox.yMax;
  });
  if (outside) return '标签框中心需要放在当前盒子里面';
  return null;
};

export const confirmObjectTray = (
  draft: PhotoObjectReviewDraft,
  trayKey: string,
): PhotoObjectReviewDraft => ({
  ...draft,
  trays: draft.trays.map((tray) => (
    tray.key === trayKey ? { ...tray, confirmed: validateObjectTray(tray) === null } : tray
  )),
});

export const buildObjectReviewPayload = (
  draft: PhotoObjectReviewDraft,
): LabelQcObjectReviewPayload => {
  for (const tray of draft.trays) {
    const error = validateObjectTray(tray);
    if (error) throw new Error(`盒子 ${tray.trayIndex + 1}：${error}`);
  }
  return {
    version: 1,
    complete: true,
    rejectedAiTrayKeys: [...draft.rejectedAiTrayKeys],
    trays: draft.trays.map(({ key: _key, confirmed: _confirmed, labels, ...tray }) => ({
      ...tray,
      labels: labels.map(({ key: _labelKey, ...label }) => label),
    })),
  };
};
