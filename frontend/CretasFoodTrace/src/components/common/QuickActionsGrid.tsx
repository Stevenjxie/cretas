/**
 * QuickActionsGrid - 可配置的快捷操作网格
 *
 * 功能:
 * - 显示快捷操作按钮网格
 * - 编辑模式 (显隐切换)
 * - 持久化用户配置
 * - 首次使用气泡引导
 */
import React, { useState, useEffect, useRef } from 'react';
import { View, Text, TouchableOpacity, StyleSheet, Alert, Animated } from 'react-native';
import { registerTutorialTarget } from '../../store/tutorialStore';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useQuickActionsStore, QuickAction } from '../../store/quickActionsStore';

type MaterialIconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];

interface Props {
  role: string;
  allActions: QuickAction[];
  onActionPress: (action: QuickAction) => void;
  /** 气泡引导配置 */
  guides?: Record<string, string>; // { actionId: '说明文字' }
  /** Tutorial spotlight: 映射 actionId → targetKey */
  tutorialTargets?: Record<string, string>;
}

// 气泡提示组件
function GuideBubble({ text, onDismiss }: { text: string; onDismiss: () => void }) {
  const [opacity] = useState(new Animated.Value(0));

  useEffect(() => {
    Animated.timing(opacity, { toValue: 1, duration: 300, useNativeDriver: true }).start();
  }, []);

  return (
    <Animated.View style={[styles.bubble, { opacity }]}>
      <View style={styles.bubbleArrow} />
      <Text style={styles.bubbleText}>{text}</Text>
      <TouchableOpacity onPress={onDismiss} style={styles.bubbleDismiss}>
        <Text style={styles.bubbleDismissText}>知道了</Text>
      </TouchableOpacity>
    </Animated.View>
  );
}

export function QuickActionsGrid({ role, allActions, onActionPress, guides, tutorialTargets }: Props) {
  // Refs for individual button measurement (tutorial spotlight)
  const btnRefs = useRef<Record<string, View | null>>({});
  const [editMode, setEditMode] = useState(false);
  const [guideShown, setGuideShown] = useState<Record<string, boolean>>({});
  const [activeGuide, setActiveGuide] = useState<string | null>(null);
  const { getVisibleActions, toggleAction, isActionVisible, resetToDefault } = useQuickActionsStore();

  const visibleActions = getVisibleActions(role, allActions);
  const displayActions = editMode ? allActions : visibleActions;

  // 首次使用引导
  useEffect(() => {
    if (!guides) return;
    (async () => {
      const key = `quick_action_guide_${role}`;
      const shown = await AsyncStorage.getItem(key);
      if (!shown) {
        // 找第一个有引导的 visible action
        const firstGuide = visibleActions.find(a => guides[a.id]);
        if (firstGuide) {
          setActiveGuide(firstGuide.id);
        }
      } else {
        try { setGuideShown(JSON.parse(shown)); } catch {}
      }
    })();
  }, [role, guides]);

  const dismissGuide = async (actionId: string) => {
    const newShown = { ...guideShown, [actionId]: true };
    setGuideShown(newShown);

    // 找下一个未展示的引导
    const remaining = visibleActions.filter(a => guides?.[a.id] && !newShown[a.id]);
    if (remaining.length > 0) {
      const nextGuide = remaining[0];
      if (nextGuide) {
        setActiveGuide(nextGuide.id);
      }
    } else {
      setActiveGuide(null);
      await AsyncStorage.setItem(`quick_action_guide_${role}`, JSON.stringify(newShown));
    }
  };

  const handleDone = () => setEditMode(false);

  const handleReset = () => {
    Alert.alert('重置', '确定恢复默认快捷操作？', [
      { text: '取消', style: 'cancel' },
      { text: '确定', onPress: () => { resetToDefault(role); setEditMode(false); } },
    ]);
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>快捷操作</Text>
        {editMode ? (
          <View style={styles.editActions}>
            <TouchableOpacity onPress={handleReset}>
              <Text style={styles.resetText}>重置</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={handleDone} style={styles.doneBtn}>
              <Text style={styles.doneText}>完成</Text>
            </TouchableOpacity>
          </View>
        ) : (
          <TouchableOpacity onPress={() => setEditMode(true)}>
            <MaterialCommunityIcons name="pencil-outline" size={18} color="#999" />
          </TouchableOpacity>
        )}
      </View>

      {editMode && (
        <Text style={styles.editHint}>点击图标切换显示/隐藏</Text>
      )}

      <View style={styles.grid}>
        {displayActions.map((action) => {
          const visible = isActionVisible(role, action.id);
          const showGuide = activeGuide === action.id && !editMode;
          const guideText = guides?.[action.id];

          return (
            <View
              key={action.id}
              style={styles.actionWrapper}
              ref={(el) => { if (tutorialTargets?.[action.id]) btnRefs.current[action.id] = el; }}
              onLayout={() => {
                const tKey = tutorialTargets?.[action.id];
                if (tKey) {
                  setTimeout(() => {
                    btnRefs.current[action.id]?.measureInWindow((x, y, w, h) => {
                      if (w > 0 && h > 0) registerTutorialTarget(tKey, { x, y, width: w, height: h });
                    });
                  }, 150);
                }
              }}
            >
              <TouchableOpacity
                style={[styles.actionBtn, editMode && !visible && styles.actionBtnHidden]}
                onPress={() => {
                  if (editMode) {
                    toggleAction(role, action.id);
                  } else {
                    if (showGuide) dismissGuide(action.id);
                    onActionPress(action);
                  }
                }}
                activeOpacity={0.7}
              >
                <View style={[
                  styles.iconWrap,
                  { backgroundColor: action.iconBg },
                  editMode && !visible && styles.iconWrapHidden,
                ]}>
                  <MaterialCommunityIcons
                    name={action.icon as MaterialIconName}
                    size={22}
                    color={editMode && !visible ? '#ccc' : action.iconColor}
                  />
                  {editMode && (
                    <View style={[styles.toggleBadge, visible ? styles.toggleVisible : styles.toggleHidden]}>
                      <MaterialCommunityIcons
                        name={visible ? 'check' : 'close'}
                        size={10}
                        color="#fff"
                      />
                    </View>
                  )}
                </View>
                <Text style={[
                  styles.actionLabel,
                  editMode && !visible && styles.actionLabelHidden,
                ]}>
                  {action.label}
                </Text>
              </TouchableOpacity>

              {/* 气泡引导 */}
              {showGuide && guideText && (
                <GuideBubble
                  text={guideText}
                  onDismiss={() => dismissGuide(action.id)}
                />
              )}
            </View>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { marginBottom: 16 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  title: { fontSize: 16, fontWeight: '700', color: '#1a1a1a' },
  editActions: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  resetText: { fontSize: 13, color: '#999' },
  doneBtn: { paddingHorizontal: 12, paddingVertical: 4, backgroundColor: '#059669', borderRadius: 12 },
  doneText: { fontSize: 13, color: '#fff', fontWeight: '600' },
  editHint: { fontSize: 12, color: '#fa8c16', marginBottom: 8 },

  grid: { flexDirection: 'row', flexWrap: 'wrap' },
  actionWrapper: { width: '25%', alignItems: 'center', marginBottom: 16, position: 'relative' },
  actionBtn: { alignItems: 'center' },
  actionBtnHidden: { opacity: 0.5 },
  iconWrap: {
    width: 48, height: 48, borderRadius: 14,
    alignItems: 'center', justifyContent: 'center',
    marginBottom: 6, position: 'relative',
  },
  iconWrapHidden: { backgroundColor: '#f5f5f5' },
  actionLabel: { fontSize: 12, color: '#333', textAlign: 'center' },
  actionLabelHidden: { color: '#ccc' },

  toggleBadge: {
    position: 'absolute', top: -4, right: -4,
    width: 16, height: 16, borderRadius: 8,
    alignItems: 'center', justifyContent: 'center',
  },
  toggleVisible: { backgroundColor: '#52c41a' },
  toggleHidden: { backgroundColor: '#ff4d4f' },

  // Guide bubble
  bubble: {
    position: 'absolute', top: 62, left: -20, right: -20,
    backgroundColor: '#333', borderRadius: 10, padding: 12,
    zIndex: 100, elevation: 10,
    shadowColor: '#000', shadowOffset: { width: 0, height: 4 }, shadowOpacity: 0.2, shadowRadius: 8,
  },
  bubbleArrow: {
    position: 'absolute', top: -8, left: '45%',
    width: 0, height: 0,
    borderLeftWidth: 8, borderRightWidth: 8, borderBottomWidth: 8,
    borderLeftColor: 'transparent', borderRightColor: 'transparent', borderBottomColor: '#333',
  },
  bubbleText: { color: '#fff', fontSize: 13, lineHeight: 20, marginBottom: 8 },
  bubbleDismiss: { alignSelf: 'flex-end', paddingHorizontal: 12, paddingVertical: 4, backgroundColor: 'rgba(255,255,255,0.2)', borderRadius: 10 },
  bubbleDismissText: { color: '#fff', fontSize: 12, fontWeight: '600' },
});
