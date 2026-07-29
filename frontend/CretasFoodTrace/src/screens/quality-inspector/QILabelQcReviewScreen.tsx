/* eslint-disable react-hooks/refs -- Gesture Handler callbacks intentionally keep live gesture state in refs outside render. */
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  Image,
  LayoutChangeEvent,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { RouteProp, useNavigation, useRoute } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import {
  Gesture,
  GestureDetector,
  GestureHandlerRootView,
} from 'react-native-gesture-handler';
import {
  ActivityIndicator,
  Button,
  Dialog,
  Portal,
  TouchableRipple,
} from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { labelQcApi } from '../../services/api/labelQcApi';
import { useAuthStore } from '../../store/authStore';
import {
  LabelQcBoundingBox,
  LabelQcLabel,
  LabelQcPhoto,
  LabelQcTaskDetail,
} from '../../types/labelQc';
import {
  QI_COLORS,
  QualityInspectorStackParamList,
} from '../../types/qualityInspector';
import {
  buildScreeningReferenceBoxes,
  LABEL_QC_ALL_LAYERS_VISIBLE,
  LABEL_QC_LAYER_META,
  LABEL_QC_LAYER_ORDER,
  LabelQcLayerVisibility,
  LabelQcScreenLayer,
  LabelQcScreenReferenceBox,
  parseScreeningTrays,
} from './labelQcScreeningLayers';
import {
  addHumanAnnotation,
  buildLabelQcReviewRequest,
  createLabelQcReviewRequestId,
  getLabelQcReviewConflictMessage,
  hydrateLabelQcReviewDrafts,
  isDefectLabel,
  isPhotoReviewComplete,
  LabelQcReviewAnnotationDraft,
  LabelQcReviewPhotoDraft,
  markPhotoReviewed,
  nextIncompletePhotoIndex,
  pendingAnnotationCount,
  removeDraftAnnotation,
  resizeBoundingBox,
  translateBoundingBox,
  updateDraftAnnotation,
} from './labelQcReviewModel';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;
type RouteProps = RouteProp<QualityInspectorStackParamList, 'QILabelQcReview'>;

interface Viewport {
  scale: number;
  translateX: number;
  translateY: number;
}

interface SurfaceSize {
  width: number;
  height: number;
  left: number;
  top: number;
}

const LABEL_COPY: Record<LabelQcLabel, string> = {
  MISSING_WHITE_LABEL: '缺白标',
  MISSING_COLOR_LABEL: '缺彩标',
  NO_DEFECT: '无异常',
  UNJUDGEABLE: '无法判断',
};

/**
 * 已定结论的框按结论上色, 和 web-admin 复核台一致:
 * 缺白标红 / 缺彩标橙 / 无法判断灰 / 无异常绿。
 * 尚未判定的框保持"来源色"(AI 橙 / 人工绿), 让"还没处理"一眼可见。
 */
const VERDICT_COLORS: Record<LabelQcLabel, string> = {
  MISSING_WHITE_LABEL: '#E54D42',
  MISSING_COLOR_LABEL: '#D97706',
  UNJUDGEABLE: '#6B7280',
  NO_DEFECT: '#16A36A',
};

const PENDING_AI_COLOR = '#F5A000';
const PENDING_HUMAN_COLOR = '#00A883';

const REVIEWABLE_STATUSES = ['NEEDS_REVIEW', 'ANALYSIS_FAILED'];
const MIN_SCALE = 1;
const MAX_SCALE = 4;

const clamp = (value: number, min: number, max: number): number =>
  Math.min(Math.max(value, min), max);

const getErrorMessage = (error: unknown): string => {
  const responseMessage = (
    error as { response?: { data?: { message?: string } } }
  )?.response?.data?.message;
  if (responseMessage) return responseMessage;
  if (error instanceof Error && error.message) return error.message;
  return '审核保存失败，请检查网络后重试';
};

const makeHumanKey = (): string =>
  `human-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

const pointInsideBox = (
  x: number,
  y: number,
  bbox?: LabelQcBoundingBox,
): boolean =>
  Boolean(
    bbox
      && x >= bbox.xMin
      && x <= bbox.xMax
      && y >= bbox.yMin
      && y <= bbox.yMax,
  );

function AnnotationBox({
  annotation,
  selected,
  surface,
  viewportScale,
  readOnly,
  onSelect,
  onMove,
  onResize,
}: {
  annotation: LabelQcReviewAnnotationDraft;
  selected: boolean;
  surface: SurfaceSize;
  viewportScale: number;
  readOnly: boolean;
  onSelect: () => void;
  onMove: (deltaX: number, deltaY: number) => void;
  onResize: (deltaX: number, deltaY: number) => void;
}) {
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });
  const [resizeOffset, setResizeOffset] = useState({ x: 0, y: 0 });
  const bbox = annotation.bbox;

  const moveGesture = useMemo(
    () =>
      Gesture.Pan()
        .enabled(!readOnly)
        .minDistance(3)
        .onUpdate((event) => {
          setDragOffset({
            x: event.translationX / viewportScale,
            y: event.translationY / viewportScale,
          });
        })
        .onEnd((event) => {
          onMove(
            event.translationX / (surface.width * viewportScale),
            event.translationY / (surface.height * viewportScale),
          );
          setDragOffset({ x: 0, y: 0 });
        })
        .onFinalize(() => setDragOffset({ x: 0, y: 0 }))
        .runOnJS(true),
    [onMove, readOnly, surface.height, surface.width, viewportScale],
  );

  const resizeGesture = useMemo(
    () =>
      Gesture.Pan()
        .enabled(!readOnly)
        .minDistance(1)
        .onUpdate((event) => {
          setResizeOffset({
            x: event.translationX / viewportScale,
            y: event.translationY / viewportScale,
          });
        })
        .onEnd((event) => {
          onResize(
            event.translationX / (surface.width * viewportScale),
            event.translationY / (surface.height * viewportScale),
          );
          setResizeOffset({ x: 0, y: 0 });
        })
        .onFinalize(() => setResizeOffset({ x: 0, y: 0 }))
        .runOnJS(true),
    [onResize, readOnly, surface.height, surface.width, viewportScale],
  );

  if (!bbox) return null;

  const color = annotation.label
    ? VERDICT_COLORS[annotation.label]
    : annotation.source === 'AI'
      ? PENDING_AI_COLOR
      : PENDING_HUMAN_COLOR;
  const finalLabel = annotation.label
    ? LABEL_COPY[annotation.label]
    : annotation.source === 'AI'
      ? 'AI 疑点'
      : '人工新增';

  return (
    <GestureDetector gesture={moveGesture}>
      <View
        style={[
          styles.annotationBox,
          {
            left: bbox.xMin * surface.width + dragOffset.x,
            top: bbox.yMin * surface.height + dragOffset.y,
            width:
              (bbox.xMax - bbox.xMin) * surface.width
              + resizeOffset.x,
            height:
              (bbox.yMax - bbox.yMin) * surface.height
              + resizeOffset.y,
            borderColor: color,
            borderWidth: selected ? 3 : 2,
          },
        ]}
        testID={`qi-label-qc-annotation-${annotation.key}`}
      >
        <Pressable
          style={StyleSheet.absoluteFill}
          onPress={onSelect}
          accessibilityRole="button"
          accessibilityLabel={`${annotation.source === 'AI' ? 'AI' : '人工'}标注，${finalLabel}`}
        />
        <View style={[styles.annotationTag, { backgroundColor: color }]}>
          <Text style={styles.annotationTagText} numberOfLines={1}>
            {finalLabel}
          </Text>
        </View>
        {!readOnly && selected && (
          <GestureDetector gesture={resizeGesture}>
            <View
              style={[styles.resizeHandle, { backgroundColor: color }]}
              accessibilityLabel="拖动缩放标注框"
            />
          </GestureDetector>
        )}
      </View>
    </GestureDetector>
  );
}

/**
 * AI 初筛参考层。只读、细线、不接触摸事件 —— 它是背景证据, 不能和人工标注框抢
 * 视觉, 更不能挡住"点空白处补框"的手势。
 */
function ReferenceLayer({
  boxes,
  surface,
}: {
  boxes: LabelQcScreenReferenceBox[];
  surface: SurfaceSize;
}) {
  if (!boxes.length) return null;
  return (
    <>
      {boxes.map((box) => (
        <View
          key={box.key}
          pointerEvents="none"
          accessibilityLabel={box.caption}
          style={[
            styles.referenceBox,
            box.layer === 'tray' && styles.referenceBoxTray,
            {
              left: box.bbox.xMin * surface.width,
              top: box.bbox.yMin * surface.height,
              width: (box.bbox.xMax - box.bbox.xMin) * surface.width,
              height: (box.bbox.yMax - box.bbox.yMin) * surface.height,
              borderColor: box.color,
            },
          ]}
          testID={`qi-label-qc-reference-${box.key}`}
        />
      ))}
    </>
  );
}

/** 手机上没有键盘, 图层开关只能是可点的实体 —— 色点 + 中文名, 关掉即变灰。 */
function LayerToggleBar({
  visible,
  onToggle,
}: {
  visible: LabelQcLayerVisibility;
  onToggle: (layer: LabelQcScreenLayer) => void;
}) {
  return (
    <View style={styles.layerBar} testID="qi-label-qc-layer-bar">
      <Text style={styles.layerBarTitle}>AI 识别</Text>
      {LABEL_QC_LAYER_ORDER.map((layer) => {
        const meta = LABEL_QC_LAYER_META[layer];
        const on = visible[layer];
        return (
          <Pressable
            key={layer}
            style={[styles.layerChip, !on && styles.layerChipOff]}
            onPress={() => onToggle(layer)}
            accessibilityRole="switch"
            accessibilityState={{ checked: on }}
            accessibilityLabel={`${meta.text}标注`}
            testID={`qi-label-qc-layer-${layer}`}
          >
            <View
              style={[
                styles.layerDot,
                { borderColor: meta.color },
                on && { backgroundColor: meta.color },
              ]}
            />
            <Text style={[styles.layerChipText, !on && styles.layerChipTextOff]}>
              {meta.text}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

function HumanInlineToolbar({
  annotation,
  surface,
  viewport,
  readOnly,
  onLabel,
  onDelete,
}: {
  annotation: LabelQcReviewAnnotationDraft;
  surface: SurfaceSize;
  viewport: Viewport;
  readOnly: boolean;
  onLabel: (label: LabelQcLabel) => void;
  onDelete: () => void;
}) {
  if (!annotation.bbox || readOnly) return null;
  const width = 214;
  const boxCenterX =
    ((annotation.bbox.xMin + annotation.bbox.xMax) / 2) * surface.width;
  const left = clamp(boxCenterX - width / 2, 4, surface.width - width - 4);
  const preferredTop = annotation.bbox.yMin * surface.height - 44 / viewport.scale;
  const top =
    preferredTop >= 4
      ? preferredTop
      : annotation.bbox.yMax * surface.height + 7 / viewport.scale;

  return (
    <View
      style={[
        styles.inlineToolbar,
        {
          left,
          top,
          width,
          transform: [{ scale: 1 / viewport.scale }],
        },
      ]}
      testID="qi-label-qc-human-inline-toolbar"
    >
      {(
        [
          ['MISSING_WHITE_LABEL', '缺白'],
          ['MISSING_COLOR_LABEL', '缺彩'],
          ['UNJUDGEABLE', '不清'],
        ] as const
      ).map(([label, copy]) => (
        <Pressable
          key={label}
          style={[
            styles.inlineButton,
            annotation.label === label && styles.inlineButtonActive,
          ]}
          onPress={() => onLabel(label)}
          accessibilityRole="button"
          accessibilityLabel={LABEL_COPY[label]}
        >
          <Text
            style={[
              styles.inlineButtonText,
              annotation.label === label && styles.inlineButtonTextActive,
            ]}
          >
            {copy}
          </Text>
        </Pressable>
      ))}
      <Pressable
        style={[styles.inlineButton, styles.inlineDeleteButton]}
        onPress={onDelete}
        accessibilityRole="button"
        accessibilityLabel="删除人工标注框"
      >
        <Ionicons name="trash-outline" size={15} color="#B9322B" />
      </Pressable>
    </View>
  );
}

function PhotoCanvas({
  photo,
  draft,
  referenceBoxes,
  selectedKey,
  viewport,
  readOnly,
  onSelect,
  onViewport,
  onAdd,
  onMove,
  onResize,
  onHumanLabel,
  onDelete,
}: {
  photo: LabelQcPhoto;
  draft: LabelQcReviewPhotoDraft;
  referenceBoxes: LabelQcScreenReferenceBox[];
  selectedKey: string | null;
  viewport: Viewport;
  readOnly: boolean;
  onSelect: (key: string | null) => void;
  onViewport: (viewport: Viewport) => void;
  onAdd: (x: number, y: number) => void;
  onMove: (key: string, deltaX: number, deltaY: number) => void;
  onResize: (key: string, deltaX: number, deltaY: number) => void;
  onHumanLabel: (key: string, label: LabelQcLabel) => void;
  onDelete: (key: string) => void;
}) {
  const [container, setContainer] = useState({ width: 1, height: 1 });
  const startViewport = useRef(viewport);
  const viewportRef = useRef(viewport);

  useEffect(() => {
    viewportRef.current = viewport;
  }, [viewport]);

  const surface = useMemo<SurfaceSize>(() => {
    const imageWidth = Math.max(photo.imageWidth, 1);
    const imageHeight = Math.max(photo.imageHeight, 1);
    const ratio = Math.min(
      container.width / imageWidth,
      container.height / imageHeight,
    );
    const width = imageWidth * ratio;
    const height = imageHeight * ratio;
    return {
      width,
      height,
      left: (container.width - width) / 2,
      top: (container.height - height) / 2,
    };
  }, [container, photo.imageHeight, photo.imageWidth]);

  const keepInside = useCallback(
    (candidate: Viewport): Viewport => {
      const scale = clamp(candidate.scale, MIN_SCALE, MAX_SCALE);
      const maxX = Math.max(0, (surface.width * scale - container.width) / 2);
      const maxY = Math.max(0, (surface.height * scale - container.height) / 2);
      return {
        scale,
        translateX: clamp(candidate.translateX, -maxX, maxX),
        translateY: clamp(candidate.translateY, -maxY, maxY),
      };
    },
    [container.height, container.width, surface.height, surface.width],
  );

  const panGesture = useMemo(
    () =>
      Gesture.Pan()
        .enabled(viewport.scale > 1)
        .minDistance(7)
        .onBegin(() => {
          startViewport.current = viewportRef.current;
        })
        .onUpdate((event) => {
          onViewport(
            keepInside({
              ...startViewport.current,
              translateX:
                startViewport.current.translateX + event.translationX,
              translateY:
                startViewport.current.translateY + event.translationY,
            }),
          );
        })
        .runOnJS(true),
    [keepInside, onViewport, viewport.scale],
  );

  const pinchGesture = useMemo(
    () =>
      Gesture.Pinch()
        .enabled(!readOnly || Boolean(photo.imageUrl))
        .onBegin(() => {
          startViewport.current = viewportRef.current;
        })
        .onUpdate((event) => {
          onViewport(
            keepInside({
              ...startViewport.current,
              scale: startViewport.current.scale * event.scale,
            }),
          );
        })
        .runOnJS(true),
    [keepInside, onViewport, photo.imageUrl, readOnly],
  );

  const doubleTapGesture = useMemo(
    () =>
      Gesture.Tap()
        .numberOfTaps(2)
        .maxDuration(280)
        .onEnd(() => {
          const current = viewportRef.current;
          onViewport(
            current.scale > 1
              ? { scale: 1, translateX: 0, translateY: 0 }
              : keepInside({ scale: 2, translateX: 0, translateY: 0 }),
          );
        })
        .runOnJS(true),
    [keepInside, onViewport],
  );

  const singleTapGesture = useMemo(
    () =>
      Gesture.Tap()
        .numberOfTaps(1)
        .maxDuration(250)
        .onEnd((event) => {
          const current = viewportRef.current;
          const localX =
            (event.x
              - surface.left
              - surface.width / 2
              - current.translateX)
              / current.scale
            + surface.width / 2;
          const localY =
            (event.y
              - surface.top
              - surface.height / 2
              - current.translateY)
              / current.scale
            + surface.height / 2;
          const x = localX / surface.width;
          const y = localY / surface.height;
          if (x < 0 || x > 1 || y < 0 || y > 1) return;
          const hit = [...draft.annotations]
            .filter(
              (annotation) =>
                !(annotation.source === 'AI' && annotation.label === 'NO_DEFECT'),
            )
            .reverse()
            .find((annotation) => pointInsideBox(x, y, annotation.bbox));
          if (hit) {
            onSelect(hit.key);
          } else if (!readOnly) {
            onAdd(x, y);
          }
        })
        .runOnJS(true),
    [draft.annotations, onAdd, onSelect, readOnly, surface],
  );

  const combinedGesture = useMemo(
    () =>
      Gesture.Race(
        Gesture.Simultaneous(pinchGesture, panGesture),
        Gesture.Exclusive(doubleTapGesture, singleTapGesture),
      ),
    [doubleTapGesture, panGesture, pinchGesture, singleTapGesture],
  );

  const selectedHuman = draft.annotations.find(
    (annotation) =>
      annotation.key === selectedKey && annotation.source === 'HUMAN',
  );

  const handleLayout = (event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    setContainer({ width, height });
  };

  return (
    <GestureDetector gesture={combinedGesture}>
      <View
        style={styles.canvas}
        onLayout={handleLayout}
        testID="qi-label-qc-photo-canvas"
      >
        <View
          style={[
            styles.photoSurface,
            {
              width: surface.width,
              height: surface.height,
              left: surface.left,
              top: surface.top,
              transform: [
                { translateX: viewport.translateX },
                { translateY: viewport.translateY },
                { scale: viewport.scale },
              ],
            },
          ]}
        >
          {photo.imageUrl ? (
            <Image
              source={{ uri: photo.imageUrl }}
              style={StyleSheet.absoluteFill}
              resizeMode="stretch"
              accessibilityLabel={`第 ${photo.orderIndex + 1} 张质检照片`}
            />
          ) : (
            <View style={styles.imageUnavailable}>
              <Ionicons name="image-outline" size={40} color="#78928A" />
              <Text style={styles.imageUnavailableText}>照片暂时无法显示</Text>
            </View>
          )}
          {/* 参考层画在人工标注框之前 —— RN 按渲染顺序叠放, 先画即在下层 */}
          <ReferenceLayer boxes={referenceBoxes} surface={surface} />
          {draft.annotations
            .filter(
              (annotation) =>
                !(annotation.source === 'AI' && annotation.label === 'NO_DEFECT'),
            )
            .map((annotation) => (
            <AnnotationBox
              key={annotation.key}
              annotation={annotation}
              selected={annotation.key === selectedKey}
              surface={surface}
              viewportScale={viewport.scale}
              readOnly={readOnly}
              onSelect={() => onSelect(annotation.key)}
              onMove={(deltaX, deltaY) =>
                onMove(annotation.key, deltaX, deltaY)
              }
              onResize={(deltaX, deltaY) =>
                onResize(annotation.key, deltaX, deltaY)
              }
            />
          ))}
          {selectedHuman && (
            <HumanInlineToolbar
              annotation={selectedHuman}
              surface={surface}
              viewport={viewport}
              readOnly={readOnly}
              onLabel={(label) => onHumanLabel(selectedHuman.key, label)}
              onDelete={() => onDelete(selectedHuman.key)}
            />
          )}
        </View>

        <View style={styles.zoomBadge} pointerEvents="none">
          <Text style={styles.zoomBadgeText}>
            {viewport.scale > 1
              ? `${viewport.scale.toFixed(1)}× · 拖动查看`
              : '双指放大 · 双击缩放'}
          </Text>
        </View>
      </View>
    </GestureDetector>
  );
}

function CurrentActionCard({
  photo,
  selected,
  readOnly,
  onSelectFirstPending,
  onConfirmAi,
  onRejectAi,
  onMarkReviewed,
}: {
  photo: LabelQcReviewPhotoDraft;
  selected?: LabelQcReviewAnnotationDraft;
  readOnly: boolean;
  onSelectFirstPending: () => void;
  onConfirmAi: () => void;
  onRejectAi: () => void;
  onMarkReviewed: () => void;
}) {
  const pending = pendingAnnotationCount(photo);
  const hasConfirmedDefect = photo.annotations.some((annotation) =>
    isDefectLabel(annotation.label),
  );

  if (readOnly) {
    return (
      <View style={[styles.actionCard, styles.actionCardDone]}>
        <Ionicons name="checkmark-circle" size={24} color="#08795A" />
        <View style={styles.actionCopy}>
          <Text style={styles.actionTitle}>本图审核已完成</Text>
          <Text style={styles.actionBody}>框和结论均为已保存的人工真值。</Text>
        </View>
      </View>
    );
  }

  if (selected?.source === 'AI' && !selected.label) {
    const aiLabel = selected.aiLabel ?? 'UNJUDGEABLE';
    return (
      <View style={styles.actionCard}>
        <View style={styles.actionCardHeader}>
          <View style={styles.stepBadge}>
            <Text style={styles.stepBadgeText}>当前操作</Text>
          </View>
          <Text style={styles.actionTitle}>确认 AI 疑点</Text>
        </View>
        <Text style={styles.actionBody} numberOfLines={1}>
          AI 判断：{LABEL_COPY[aiLabel]}{selected.aiEvidence ? ` · ${selected.aiEvidence}` : ''}
        </Text>
        <View style={styles.actionButtons}>
          <Button
            mode="contained"
            buttonColor={QI_COLORS.primary}
            style={styles.actionButton}
            onPress={onConfirmAi}
            testID="qi-label-qc-confirm-ai-button"
          >
            确认：{LABEL_COPY[aiLabel]}
          </Button>
          <Button
            mode="outlined"
            textColor="#B9322B"
            style={[styles.actionButton, styles.rejectButton]}
            onPress={onRejectAi}
            testID="qi-label-qc-reject-ai-button"
          >
            拒绝疑点
          </Button>
        </View>
      </View>
    );
  }

  if (pending > 0) {
    return (
      <View style={styles.actionCard}>
        <View style={styles.actionCardHeader}>
          <View style={styles.stepBadge}>
            <Text style={styles.stepBadgeText}>当前操作</Text>
          </View>
          <Text style={styles.actionTitle}>还有 {pending} 个框待判断</Text>
        </View>
        <Text style={styles.actionBody}>
          {selected?.source === 'HUMAN'
            ? '请在照片中绿色框旁选择“缺白、缺彩或不清”。'
            : '点击下方按钮定位到下一个待确认框。'}
        </Text>
        {!selected && (
          <Button mode="contained" buttonColor={QI_COLORS.primary} onPress={onSelectFirstPending}>
            定位待确认框
          </Button>
        )}
      </View>
    );
  }

  if (!photo.reviewed) {
    return (
      <View style={styles.actionCard}>
        <View style={styles.actionCardHeader}>
          <View style={styles.stepBadge}>
            <Text style={styles.stepBadgeText}>最后一步</Text>
          </View>
          <Text style={styles.actionTitle}>给出本图结论</Text>
        </View>
        <Text style={styles.actionBody}>
          再看整张照片一遍，确认没有遗漏后再通过。
        </Text>
        <Button
          mode="contained"
          buttonColor={QI_COLORS.primary}
          onPress={onMarkReviewed}
          testID="qi-label-qc-photo-conclusion-button"
        >
          {hasConfirmedDefect ? '确认本图标注无误' : '整图正常 · 没有其他问题'}
        </Button>
      </View>
    );
  }

  return (
    <View style={[styles.actionCard, styles.actionCardDone]}>
      <Ionicons name="checkmark-circle" size={24} color="#08795A" />
      <View style={styles.actionCopy}>
        <Text style={styles.actionTitle}>本图已完成</Text>
        <Text style={styles.actionBody}>可以查看其他照片，任何修改都会要求重新确认本图。</Text>
      </View>
    </View>
  );
}

export default function QILabelQcReviewScreen() {
  const route = useRoute<RouteProps>();
  const navigation = useNavigation<NavigationProp>();
  const insets = useSafeAreaInsets();
  const factoryId = useAuthStore((state) => state.user?.factoryId);
  const [detail, setDetail] = useState<LabelQcTaskDetail | null>(null);
  const [drafts, setDrafts] = useState<LabelQcReviewPhotoDraft[]>([]);
  const [photoIndex, setPhotoIndex] = useState(0);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [viewports, setViewports] = useState<Record<string, Viewport>>({});
  const [visibleLayers, setVisibleLayers] = useState<LabelQcLayerVisibility>(
    LABEL_QC_ALL_LAYERS_VISIBLE,
  );
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conflictMessage, setConflictMessage] = useState<string | null>(null);
  const reviewRequestIdRef = useRef(
    createLabelQcReviewRequestId(route.params.taskId),
  );

  const load = useCallback(async () => {
    if (!factoryId) {
      setError('登录信息缺少工厂，请重新登录');
      setLoading(false);
      return;
    }
    try {
      setLoading(true);
      setError(null);
      setConflictMessage(null);
      const taskDetail = await labelQcApi.getTask(route.params.taskId, factoryId);
      const initialDrafts = hydrateLabelQcReviewDrafts(taskDetail);
      setDetail(taskDetail);
      setDrafts(initialDrafts);
      setSelectedKey(
        initialDrafts[0]?.annotations.find((annotation) => !annotation.label)?.key
          ?? null,
      );
    } catch (loadError) {
      setError(getErrorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, [factoryId, route.params.taskId]);

  useEffect(() => {
    const timer = setTimeout(() => {
      void load();
    }, 0);
    return () => clearTimeout(timer);
  }, [load]);

  const photo = detail?.photos[photoIndex];
  const draft = drafts[photoIndex];
  const selected = draft?.annotations.find(
    (annotation) => annotation.key === selectedKey,
  );
  const readOnly = detail?.task.status === 'REVIEWED';
  const completedCount = drafts.filter(isPhotoReviewComplete).length;
  const allComplete =
    drafts.length > 0 && completedCount === drafts.length;

  // 图层开关跨照片保持 —— 质检员关掉盒子框是一种看图习惯, 翻到下一张不该被重置
  const screenTrays = useMemo(
    () => parseScreeningTrays(photo?.screeningDetail),
    [photo?.screeningDetail],
  );
  const referenceBoxes = useMemo(
    () => buildScreeningReferenceBoxes(screenTrays, visibleLayers),
    [screenTrays, visibleLayers],
  );
  const toggleLayer = useCallback((layer: LabelQcScreenLayer) => {
    setVisibleLayers((current) => ({ ...current, [layer]: !current[layer] }));
  }, []);

  const setPhotoDraft = useCallback(
    (
      updater: (
        photoDraft: LabelQcReviewPhotoDraft,
      ) => LabelQcReviewPhotoDraft,
    ) => {
      setDrafts((current) =>
        current.map((item, index) =>
          index === photoIndex ? updater(item) : item,
        ),
      );
    },
    [photoIndex],
  );

  const selectPhoto = (index: number) => {
    setPhotoIndex(index);
    setSelectedKey(
      drafts[index]?.annotations.find((annotation) => !annotation.label)?.key
        ?? null,
    );
  };

  const handleAdd = (x: number, y: number) => {
    const key = makeHumanKey();
    setPhotoDraft((current) => addHumanAnnotation(current, x, y, key));
    setSelectedKey(key);
  };

  const handleAnnotationUpdate = (
    key: string,
    updates: Partial<LabelQcReviewAnnotationDraft>,
  ) => {
    setPhotoDraft((current) => updateDraftAnnotation(current, key, updates));
  };

  const resolveAi = (accept: boolean) => {
    if (!selected || selected.source !== 'AI' || !draft) return;
    const nextPending = draft.annotations.find(
      (annotation) => annotation.key !== selected.key && !annotation.label,
    );
    handleAnnotationUpdate(selected.key, {
      label: accept ? selected.aiLabel ?? 'UNJUDGEABLE' : 'NO_DEFECT',
    });
    setSelectedKey(nextPending?.key ?? null);
  };

  const markCurrentPhoto = () => {
    setPhotoDraft(markPhotoReviewed);
    setSelectedKey(null);
  };

  const goNext = () => {
    if (!drafts.length) return;
    if (allComplete) {
      void submitReview();
      return;
    }
    const next = nextIncompletePhotoIndex(drafts, photoIndex);
    if (next !== null) {
      selectPhoto(next);
      return;
    }
    selectPhoto((photoIndex + 1) % drafts.length);
  };

  const submitReview = async () => {
    if (!factoryId || !detail || submitting) return;
    try {
      setSubmitting(true);
      setError(null);
      const request = buildLabelQcReviewRequest(
        drafts,
        detail.task.version,
        reviewRequestIdRef.current,
      );
      const reviewed = await labelQcApi.reviewTask(detail.task.id, request, factoryId);
      setDetail(reviewed);
      setDrafts(hydrateLabelQcReviewDrafts(reviewed));
      navigation.replace('QILabelQcSubmitted', {
        taskId: reviewed.task.id,
        skuCode: reviewed.task.skuCode,
        skuName: reviewed.task.skuName,
        batchNumber: reviewed.task.batchNumber,
        productionDate: reviewed.task.productionDate,
      });
    } catch (submitError) {
      const conflict = getLabelQcReviewConflictMessage(submitError);
      if (conflict) {
        setConflictMessage(conflict);
      } else {
        setError(getErrorMessage(submitError));
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <View style={styles.centerState} testID="qi-label-qc-review-loading">
        <ActivityIndicator size="large" color={QI_COLORS.primary} />
        <Text style={styles.centerStateText}>正在加载照片和 AI 疑点…</Text>
      </View>
    );
  }

  if (!detail || !photo || !draft) {
    return (
      <View style={styles.centerState}>
        <Ionicons name="alert-circle-outline" size={48} color={QI_COLORS.danger} />
        <Text style={styles.centerStateTitle}>审核任务暂时无法打开</Text>
        <Text style={styles.centerStateText}>{error ?? '照片数据不完整'}</Text>
        <Button mode="contained" buttonColor={QI_COLORS.primary} onPress={load}>
          重新加载
        </Button>
      </View>
    );
  }

  const isReviewable =
    readOnly || REVIEWABLE_STATUSES.includes(detail.task.status);
  if (!isReviewable) {
    return (
      <View style={styles.centerState}>
        <Ionicons name="hourglass-outline" size={48} color={QI_COLORS.secondary} />
        <Text style={styles.centerStateTitle}>AI 初筛还未完成</Text>
        <Text style={styles.centerStateText}>
          当前状态：{detail.task.status}。完成后会自动进入待审核列表。
        </Text>
        <Button mode="outlined" textColor={QI_COLORS.primary} onPress={() => navigation.goBack()}>
          返回任务列表
        </Button>
      </View>
    );
  }

  const viewport = viewports[photo.id] ?? {
    scale: 1,
    translateX: 0,
    translateY: 0,
  };
  const currentComplete = isPhotoReviewComplete(draft);
  const nextLabel = allComplete
    ? '提交人工审核'
    : currentComplete
      ? '下一张未审核'
      : drafts.length > 1
        ? '暂存并看下一张'
        : '请先完成本图';

  return (
    <GestureHandlerRootView
      style={styles.screen}
      testID="qi-label-qc-review-screen"
    >
      <Portal>
        <Dialog
          visible={Boolean(conflictMessage)}
          dismissable={false}
          onDismiss={() => undefined}
          testID="qi-label-qc-review-conflict-dialog"
        >
          <Dialog.Title>这条任务已处理</Dialog.Title>
          <Dialog.Content>
            <Text>
              {conflictMessage}
            </Text>
          </Dialog.Content>
          <Dialog.Actions>
            <Button
              mode="contained"
              buttonColor={QI_COLORS.primary}
              onPress={() => navigation.replace('QILabelQcQueue')}
              testID="qi-label-qc-review-conflict-back-button"
            >
              返回待审核列表
            </Button>
          </Dialog.Actions>
        </Dialog>
      </Portal>

      <View style={styles.contextBar}>
        <View style={styles.contextMain}>
          <Text style={styles.skuName} numberOfLines={1}>
            {detail.task.skuName}
          </Text>
          <Text style={styles.contextMeta} numberOfLines={1}>
            {detail.task.skuCode} · {detail.task.batchNumber} · {detail.task.productionDate}
          </Text>
        </View>
        <View style={styles.progressPill}>
          <Text style={styles.progressText}>{completedCount}/{drafts.length} 张</Text>
        </View>
      </View>

      <View style={styles.thumbnailRow}>
        {detail.photos.map((item, index) => {
          const itemDraft = drafts[index];
          const complete = itemDraft && isPhotoReviewComplete(itemDraft);
          const pending = itemDraft ? pendingAnnotationCount(itemDraft) : 0;
          return (
            <TouchableRipple
              key={item.id}
              style={[
                styles.thumbnail,
                index === photoIndex && styles.thumbnailActive,
              ]}
              onPress={() => selectPhoto(index)}
              borderless
              accessibilityRole="button"
              accessibilityLabel={`第 ${index + 1} 张，${complete ? '已完成' : '未完成'}`}
              testID={`qi-label-qc-thumbnail-${index}`}
            >
              <View style={styles.thumbnailContent}>
                {item.imageUrl ? (
                  <Image source={{ uri: item.imageUrl }} style={styles.thumbnailImage} />
                ) : (
                  <View style={styles.thumbnailPlaceholder}>
                    <Ionicons name="image-outline" size={20} color="#78928A" />
                  </View>
                )}
                <View
                  style={[
                    styles.thumbnailStatus,
                    complete
                      ? styles.thumbnailStatusDone
                      : styles.thumbnailStatusPending,
                  ]}
                >
                  <Text style={styles.thumbnailStatusText}>
                    {complete ? '✓' : pending || '!'}
                  </Text>
                </View>
                <Text style={styles.thumbnailIndex}>{index + 1}</Text>
              </View>
            </TouchableRipple>
          );
        })}
      </View>

      <View style={styles.instructionBar}>
        <Ionicons name="hand-left-outline" size={17} color="#8A5100" />
        <Text style={styles.instructionText}>
          轻点空白处补框 · 拖动框移动 · 右下角缩放 · 双指放大照片
        </Text>
      </View>

      {screenTrays.length > 0 && (
        <LayerToggleBar visible={visibleLayers} onToggle={toggleLayer} />
      )}

      <View style={styles.canvasWrap}>
        <PhotoCanvas
          photo={photo}
          draft={draft}
          referenceBoxes={referenceBoxes}
          selectedKey={selectedKey}
          viewport={viewport}
          readOnly={readOnly}
          onSelect={setSelectedKey}
          onViewport={(next) =>
            setViewports((current) => ({ ...current, [photo.id]: next }))
          }
          onAdd={handleAdd}
          onMove={(key, deltaX, deltaY) => {
            const annotation = draft.annotations.find((item) => item.key === key);
            if (!annotation?.bbox) return;
            handleAnnotationUpdate(key, {
              bbox: translateBoundingBox(annotation.bbox, deltaX, deltaY),
            });
          }}
          onResize={(key, deltaX, deltaY) => {
            const annotation = draft.annotations.find((item) => item.key === key);
            if (!annotation?.bbox) return;
            handleAnnotationUpdate(key, {
              bbox: resizeBoundingBox(annotation.bbox, deltaX, deltaY),
            });
          }}
          onHumanLabel={(key, label) => {
            const nextPending = draft.annotations.find(
              (annotation) => annotation.key !== key && !annotation.label,
            );
            handleAnnotationUpdate(key, { label });
            setSelectedKey(nextPending?.key ?? null);
          }}
          onDelete={(key) => {
            setPhotoDraft((current) => removeDraftAnnotation(current, key));
            setSelectedKey(null);
          }}
        />
      </View>

      {error && (
        <View style={styles.stickyError}>
          <Ionicons name="alert-circle-outline" size={18} color="#A12D23" />
          <Text style={styles.stickyErrorText}>{error}</Text>
        </View>
      )}

      <CurrentActionCard
        photo={draft}
        selected={selected}
        readOnly={readOnly}
        onSelectFirstPending={() => {
          const nextPending = draft.annotations.find((annotation) => !annotation.label);
          setSelectedKey(nextPending?.key ?? null);
        }}
        onConfirmAi={() => resolveAi(true)}
        onRejectAi={() => resolveAi(false)}
        onMarkReviewed={markCurrentPhoto}
      />

      <View style={[styles.bottomNav, { paddingBottom: Math.max(insets.bottom, 8) }]}>
        <Button
          mode="outlined"
          textColor={QI_COLORS.textSecondary}
          style={styles.previousButton}
          disabled={photoIndex === 0 || submitting}
          onPress={() => selectPhoto(photoIndex - 1)}
          testID="qi-label-qc-previous-button"
        >
          上一张
        </Button>
        {readOnly ? (
          <Button
            mode="contained"
            buttonColor={QI_COLORS.primary}
            style={styles.nextButton}
            onPress={() =>
              photoIndex < drafts.length - 1
                ? selectPhoto(photoIndex + 1)
                : navigation.goBack()
            }
          >
            {photoIndex < drafts.length - 1 ? '下一张' : '完成查看'}
          </Button>
        ) : (
          <Button
            mode="contained"
            buttonColor={QI_COLORS.primary}
            style={styles.nextButton}
            disabled={(!allComplete && drafts.length === 1 && !currentComplete) || submitting}
            loading={submitting}
            onPress={goNext}
            testID="qi-label-qc-next-button"
          >
            {nextLabel}
          </Button>
        )}
      </View>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#EFF3F1' },
  centerState: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 28,
    backgroundColor: QI_COLORS.background,
  },
  centerStateTitle: {
    marginTop: 14,
    fontSize: 20,
    fontWeight: '800',
    color: QI_COLORS.text,
    textAlign: 'center',
  },
  centerStateText: {
    marginVertical: 12,
    fontSize: 14,
    lineHeight: 21,
    color: QI_COLORS.textSecondary,
    textAlign: 'center',
  },
  contextBar: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 58,
    paddingHorizontal: 14,
    paddingVertical: 8,
    backgroundColor: QI_COLORS.card,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: QI_COLORS.border,
  },
  contextMain: { flex: 1, minWidth: 0 },
  skuName: { fontSize: 15, fontWeight: '800', color: QI_COLORS.text },
  contextMeta: { marginTop: 2, fontSize: 11, color: QI_COLORS.textSecondary },
  progressPill: {
    marginLeft: 8,
    borderRadius: 999,
    paddingHorizontal: 9,
    paddingVertical: 5,
    backgroundColor: '#E3F5EF',
  },
  progressText: { fontSize: 11, fontWeight: '700', color: '#08795A' },
  thumbnailRow: {
    flexDirection: 'row',
    gap: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#F7F8F6',
  },
  thumbnail: {
    width: 48,
    height: 54,
    borderRadius: 9,
    borderWidth: 2,
    borderColor: 'transparent',
    overflow: 'hidden',
  },
  thumbnailActive: { borderColor: QI_COLORS.primary },
  thumbnailContent: { flex: 1, backgroundColor: '#DCE4E0' },
  thumbnailImage: { flex: 1, width: '100%' },
  thumbnailPlaceholder: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  thumbnailStatus: {
    position: 'absolute',
    top: 2,
    right: 2,
    minWidth: 16,
    height: 16,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 3,
  },
  thumbnailStatusDone: { backgroundColor: '#00A883' },
  thumbnailStatusPending: { backgroundColor: '#F5A000' },
  thumbnailStatusText: { color: '#fff', fontSize: 9, fontWeight: '900' },
  thumbnailIndex: {
    position: 'absolute',
    left: 3,
    bottom: 2,
    color: '#fff',
    fontSize: 9,
    fontWeight: '900',
    textShadowColor: '#000',
    textShadowRadius: 3,
  },
  instructionBar: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: 12,
    marginBottom: 7,
    paddingHorizontal: 10,
    paddingVertical: 7,
    borderRadius: 9,
    backgroundColor: '#FFF2D8',
  },
  instructionText: { flex: 1, marginLeft: 6, fontSize: 11, color: '#724400' },
  canvasWrap: {
    flex: 1,
    minHeight: 170,
    marginHorizontal: 12,
    borderRadius: 14,
    overflow: 'hidden',
    backgroundColor: '#152722',
  },
  canvas: { flex: 1, overflow: 'hidden' },
  photoSurface: {
    position: 'absolute',
    backgroundColor: '#20342F',
  },
  imageUnavailable: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#DCE4E0',
  },
  imageUnavailableText: { marginTop: 7, fontSize: 12, color: '#526A62' },
  zoomBadge: {
    position: 'absolute',
    top: 8,
    right: 8,
    borderRadius: 999,
    paddingHorizontal: 9,
    paddingVertical: 6,
    backgroundColor: 'rgba(15,36,31,0.82)',
  },
  zoomBadgeText: { color: '#fff', fontSize: 10, fontWeight: '700' },
  annotationBox: {
    position: 'absolute',
    borderRadius: 7,
    backgroundColor: 'rgba(255,255,255,0.06)',
  },
  // AI 初筛参考层: 1px 细线 + 无填充, 压在人工标注框下面不抢视觉。
  // 不设 borderRadius —— Android 上圆角会让 dashed 退化成实线, 那样盒子层
  // 就和标签层看起来一样了, 三层框的意义正在于一眼可分。
  referenceBox: {
    position: 'absolute',
    borderWidth: 1,
    opacity: 0.9,
  },
  referenceBoxTray: {
    borderStyle: 'dashed',
    opacity: 0.65,
  },
  layerBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    paddingHorizontal: 12,
    paddingVertical: 7,
    backgroundColor: QI_COLORS.card,
    borderBottomWidth: 1,
    borderBottomColor: QI_COLORS.border,
  },
  layerBarTitle: {
    fontSize: 11,
    fontWeight: '700',
    color: QI_COLORS.textSecondary,
  },
  layerChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    minHeight: 32,
    paddingHorizontal: 11,
    borderRadius: 999,
    borderWidth: 1,
    borderColor: QI_COLORS.border,
    backgroundColor: '#F4F7F6',
  },
  layerChipOff: { backgroundColor: '#EAEDEC', opacity: 0.6 },
  layerDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    borderWidth: 2,
  },
  layerChipText: { fontSize: 12, fontWeight: '700', color: QI_COLORS.text },
  layerChipTextOff: { color: QI_COLORS.textSecondary },
  annotationTag: {
    position: 'absolute',
    left: -2,
    top: -23,
    maxWidth: 110,
    height: 22,
    justifyContent: 'center',
    borderRadius: 5,
    paddingHorizontal: 7,
  },
  annotationTagText: { color: '#fff', fontSize: 10, fontWeight: '800' },
  resizeHandle: {
    position: 'absolute',
    right: -9,
    bottom: -9,
    width: 22,
    height: 22,
    borderRadius: 11,
    borderWidth: 3,
    borderColor: '#fff',
  },
  inlineToolbar: {
    position: 'absolute',
    height: 40,
    flexDirection: 'row',
    alignItems: 'center',
    padding: 3,
    borderRadius: 10,
    backgroundColor: '#fff',
    shadowColor: '#000',
    shadowOpacity: 0.22,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 3 },
    elevation: 7,
    transformOrigin: 'top left',
  },
  inlineButton: {
    flex: 1,
    height: 34,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 7,
  },
  inlineButtonActive: { backgroundColor: '#00A883' },
  inlineButtonText: { fontSize: 11, fontWeight: '700', color: '#29443C' },
  inlineButtonTextActive: { color: '#fff' },
  inlineDeleteButton: { flex: 0, width: 38, backgroundColor: '#FFF0EE' },
  stickyError: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: 12,
    marginTop: 6,
    paddingHorizontal: 10,
    paddingVertical: 7,
    borderRadius: 9,
    backgroundColor: '#FFE9E6',
  },
  stickyErrorText: { flex: 1, marginLeft: 7, fontSize: 11, color: '#8F2E27' },
  actionCard: {
    minHeight: 100,
    marginHorizontal: 12,
    marginTop: 7,
    padding: 11,
    borderRadius: 13,
    borderWidth: 1,
    borderColor: '#91D8C5',
    backgroundColor: '#ECFBF6',
  },
  actionCardDone: {
    minHeight: 64,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#E3F5EF',
  },
  actionCardHeader: { flexDirection: 'row', alignItems: 'center' },
  stepBadge: {
    marginRight: 8,
    borderRadius: 999,
    paddingHorizontal: 8,
    paddingVertical: 4,
    backgroundColor: '#00A883',
  },
  stepBadgeText: { fontSize: 10, fontWeight: '800', color: '#fff' },
  actionCopy: { flex: 1, marginLeft: 9 },
  actionTitle: { flex: 1, fontSize: 14, fontWeight: '800', color: '#173B31' },
  actionBody: { marginTop: 4, fontSize: 11, lineHeight: 16, color: '#4A665E' },
  actionButtons: { flexDirection: 'row', gap: 8, marginTop: 8 },
  actionButton: { flex: 1 },
  rejectButton: { borderColor: '#E3A59F', backgroundColor: '#FFF9F8' },
  bottomNav: {
    flexDirection: 'row',
    gap: 9,
    paddingHorizontal: 12,
    paddingTop: 8,
    backgroundColor: QI_COLORS.card,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: QI_COLORS.border,
  },
  previousButton: { flex: 0.38 },
  nextButton: { flex: 1 },
});
