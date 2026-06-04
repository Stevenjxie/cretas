import React, { useEffect, useState } from 'react';
import { Modal, View, Text, TouchableOpacity, StyleSheet, Platform } from 'react-native';

/**
 * 跨平台对话框 — Alert.alert 的 drop-in 替换。
 *
 * 背景: react-native-web 不渲染 Alert.alert (web 上确认框/错误提示完全看不见, 防呆失效)。
 * 本组件用 RN <Modal> 实现, web 和真机都能渲染, API 与 Alert.alert(title, message, buttons) 一致,
 * 调用点无需改逻辑, 只把 Alert.alert( 换成 appAlert(。
 *
 * 用法: 在屏幕 return 顶层挂一个 <AppDialogHost />, 任意处调用 appAlert(...)。
 */
export type AppDialogButton = {
  text?: string;
  style?: 'default' | 'cancel' | 'destructive';
  onPress?: () => void;
};
type DialogState = { title: string; message?: string; buttons: AppDialogButton[] };

let _show: ((s: DialogState) => void) | null = null;

export function appAlert(title: string, message?: string, buttons?: AppDialogButton[]): void {
  const btns: AppDialogButton[] = buttons && buttons.length ? buttons : [{ text: '知道了' }];
  if (_show) {
    _show({ title, message, buttons: btns });
    return;
  }
  // host 未挂载兜底 (理论上不该发生)
  if (Platform.OS === 'web' && typeof window !== 'undefined') {
    const full = message ? `${title}\n\n${message}` : title;
    if (btns.length > 1) {
      const confirmBtn = btns.find((b) => b.style !== 'cancel') ?? btns[btns.length - 1];
      const cancelBtn = btns.find((b) => b.style === 'cancel');
      // eslint-disable-next-line no-alert
      if (window.confirm(full)) confirmBtn?.onPress?.();
      else cancelBtn?.onPress?.();
    } else {
      // eslint-disable-next-line no-alert
      window.alert(full);
      btns[0]?.onPress?.();
    }
  }
}

export const AppDialogHost: React.FC = () => {
  const [state, setState] = useState<DialogState | null>(null);
  useEffect(() => {
    _show = (s) => setState(s);
    return () => {
      _show = null;
    };
  }, []);
  if (!state) return null;
  const close = () => setState(null);
  return (
    <Modal transparent animationType="fade" visible onRequestClose={close}>
      <View style={styles.overlay}>
        <View style={styles.card}>
          <Text style={styles.title}>{state.title}</Text>
          {!!state.message && <Text style={styles.message}>{state.message}</Text>}
          <View style={[styles.btnRow, state.buttons.length > 2 && styles.btnCol]}>
            {state.buttons.map((b, i) => (
              <TouchableOpacity
                key={i}
                testID={`app-dialog-btn-${i}`}
                style={[
                  styles.btn,
                  b.style === 'cancel' && styles.btnCancel,
                  b.style === 'destructive' && styles.btnDestructive,
                ]}
                onPress={() => {
                  close();
                  b.onPress?.();
                }}
              >
                <Text
                  style={[
                    styles.btnText,
                    b.style === 'cancel' && styles.btnTextCancel,
                    b.style === 'destructive' && styles.btnTextDestructive,
                  ]}
                >
                  {b.text ?? '确定'}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.45)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  card: {
    width: '100%',
    maxWidth: 420,
    backgroundColor: '#fff',
    borderRadius: 14,
    borderWidth: 2,
    borderColor: '#111',
    padding: 20,
  },
  title: { fontSize: 18, fontWeight: '800', color: '#111', marginBottom: 8 },
  message: { fontSize: 15, lineHeight: 22, color: '#333', marginBottom: 18 },
  btnRow: { flexDirection: 'row', justifyContent: 'flex-end', gap: 10 },
  btnCol: { flexDirection: 'column-reverse', alignItems: 'stretch', gap: 10 },
  btn: {
    minWidth: 80,
    paddingVertical: 11,
    paddingHorizontal: 18,
    borderRadius: 10,
    backgroundColor: '#FFD60A',
    borderWidth: 2,
    borderColor: '#111',
    alignItems: 'center',
  },
  btnCancel: { backgroundColor: '#fff' },
  btnDestructive: { backgroundColor: '#FF453A' },
  btnText: { fontSize: 15, fontWeight: '800', color: '#111' },
  btnTextCancel: { color: '#555' },
  btnTextDestructive: { color: '#fff' },
});
