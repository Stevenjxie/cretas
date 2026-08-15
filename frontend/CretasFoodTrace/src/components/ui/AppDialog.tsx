import React, { useEffect, useState } from 'react';
import { Modal, View, Text, TextInput, TouchableOpacity, StyleSheet, Platform } from 'react-native';

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
export type AppPromptOptions = {
  placeholder?: string;
  defaultValue?: string;
  confirmText?: string;
  cancelText?: string;
  /** 为 true 时空输入不允许提交（确定按钮置灰）。默认 true —— 驳回原因这类场景不该允许空提交。 */
  required?: boolean;
  multiline?: boolean;
};

type PromptState = AppPromptOptions & { onSubmit: (value: string) => void };

/** 单选项 —— 防呆 Rule 3: 「为什么」这类字段用受约束的选择, 不要自由文本。 */
export type AppChoice = { value: string; label: string; description?: string };

type ChooseState = { choices: AppChoice[]; onPick: (choice: AppChoice) => void };

type DialogState = {
  title: string;
  message?: string;
  buttons: AppDialogButton[];
  prompt?: PromptState;
  choose?: ChooseState;
};

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

/**
 * 跨平台文本输入弹窗 —— `Alert.prompt` 的替代品。
 *
 * ⛔ `Alert.prompt` **只有 iOS 有**。Android 上它根本不存在, 调用即什么都不发生 ——
 * 按钮点下去没有任何反馈, 也不报错。2026-08-15 实测仓里有 4 处 `Alert.prompt`,
 * 只有 `QIScanScreen` 做了 `typeof Alert.prompt === 'function'` 守卫, 另外三处裸调:
 *   · `ProcessTaskApprovalScreen`（报工驳回原因）—— 工人用的是 Android
 *   · `PurchaseOrderDetailScreen`
 *   · `EmployeeProcessSegmentScreen`
 * 也就是说这三处的「驳回/填原因」在 Android 上是死按钮。
 *
 * react-native-web 上同理不渲染, 所以 Expo web 调试时也看不见。
 */
export function appPrompt(
  title: string,
  message: string | undefined,
  onSubmit: (value: string) => void,
  opts: AppPromptOptions = {},
): void {
  if (_show) {
    _show({
      title,
      message,
      buttons: [],
      prompt: { ...opts, required: opts.required !== false, onSubmit },
    });
    return;
  }
  // host 未挂载兜底
  if (Platform.OS === 'web' && typeof window !== 'undefined') {
    // eslint-disable-next-line no-alert
    const v = window.prompt(message ? `${title}\n\n${message}` : title, opts.defaultValue ?? '');
    if (v !== null && (opts.required === false || v.trim())) onSubmit(v);
  }
}

/**
 * 跨平台单选弹窗 —— 防呆设计 Rule 3:「取消原因 / 驳回原因 / 审批意见」这类
 * 「为什么」字段要用**标准选项**, 不要让用户对着空白框自己编。
 *
 * <p>自由文本对使用者(仓管、操作员)是负担, 对统计也没有价值 ——
 * 同一个原因十个人能写出十种说法。
 */
export function appChoose(
  title: string,
  message: string | undefined,
  choices: AppChoice[],
  onPick: (choice: AppChoice) => void,
): void {
  if (_show) {
    _show({ title, message, buttons: [], choose: { choices, onPick } });
    return;
  }
  // host 未挂载兜底
  if (Platform.OS === 'web' && typeof window !== 'undefined') {
    const lines = choices.map((c, i) => `${i + 1}. ${c.label}`).join('\n');
    const head = message ? `${title}\n${message}` : title;
    // eslint-disable-next-line no-alert
    const v = window.prompt(`${head}\n\n${lines}\n\n请输入序号:`, '1');
    const idx = Number(v) - 1;
    if (choices[idx]) onPick(choices[idx]);
  }
}

export const AppDialogHost: React.FC = () => {
  const [state, setState] = useState<DialogState | null>(null);
  const [inputValue, setInputValue] = useState('');
  useEffect(() => {
    _show = (s) => {
      setInputValue(s.prompt?.defaultValue ?? '');
      setState(s);
    };
    return () => {
      _show = null;
    };
  }, []);
  if (!state) return null;
  const close = () => setState(null);

  if (state.choose) {
    const c = state.choose;
    return (
      <Modal transparent animationType="fade" visible onRequestClose={close}>
        <View style={styles.overlay}>
          <View style={styles.card}>
            <Text style={styles.title}>{state.title}</Text>
            {!!state.message && <Text style={styles.message}>{state.message}</Text>}
            {c.choices.map((choice, i) => (
              <TouchableOpacity
                key={choice.value}
                testID={`app-dialog-choice-${i}`}
                style={styles.choiceRow}
                onPress={() => {
                  close();
                  c.onPick(choice);
                }}
              >
                <Text style={styles.choiceLabel}>{choice.label}</Text>
                {!!choice.description && <Text style={styles.choiceDesc}>{choice.description}</Text>}
              </TouchableOpacity>
            ))}
            <TouchableOpacity testID="app-dialog-choice-cancel" style={[styles.btn, styles.btnCancel]} onPress={close}>
              <Text style={[styles.btnText, styles.btnTextCancel]}>取消</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    );
  }

  if (state.prompt) {
    const p = state.prompt;
    const canSubmit = p.required === false || inputValue.trim().length > 0;
    return (
      <Modal transparent animationType="fade" visible onRequestClose={close}>
        <View style={styles.overlay}>
          <View style={styles.card}>
            <Text style={styles.title}>{state.title}</Text>
            {!!state.message && <Text style={styles.message}>{state.message}</Text>}
            <TextInput
              testID="app-dialog-input"
              style={[styles.input, p.multiline && styles.inputMultiline]}
              value={inputValue}
              onChangeText={setInputValue}
              placeholder={p.placeholder}
              placeholderTextColor="#999"
              multiline={p.multiline}
              autoFocus
            />
            <View style={styles.btnRow}>
              <TouchableOpacity
                testID="app-dialog-prompt-cancel"
                style={[styles.btn, styles.btnCancel]}
                onPress={close}
              >
                <Text style={[styles.btnText, styles.btnTextCancel]}>{p.cancelText ?? '取消'}</Text>
              </TouchableOpacity>
              <TouchableOpacity
                testID="app-dialog-prompt-confirm"
                style={[styles.btn, !canSubmit && styles.btnDisabled]}
                disabled={!canSubmit}
                onPress={() => {
                  // ⚠️ 不能只靠上面的 disabled: 「按钮看起来点不动」和「提交被拒绝」是两件事,
                  // 测试环境里 disabled 的 TouchableOpacity 照样能 press 出来。必填校验要落在这里。
                  if (!canSubmit) return;
                  const v = inputValue;
                  close();
                  p.onSubmit(v);
                }}
              >
                <Text style={[styles.btnText, !canSubmit && styles.btnTextDisabled]}>
                  {p.confirmText ?? '确定'}
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    );
  }
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
  input: {
    borderWidth: 2,
    borderColor: '#111',
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 15,
    color: '#111',
    marginBottom: 18,
    backgroundColor: '#fff',
  },
  inputMultiline: { minHeight: 88, textAlignVertical: 'top' },
  choiceRow: {
    borderWidth: 2,
    borderColor: '#111',
    borderRadius: 10,
    paddingVertical: 14,
    paddingHorizontal: 14,
    marginBottom: 10,
    backgroundColor: '#fff',
  },
  choiceLabel: { fontSize: 16, fontWeight: '700', color: '#111' },
  choiceDesc: { fontSize: 13, color: '#666', marginTop: 4 },
  btnCancel: { backgroundColor: '#fff' },
  btnDisabled: { backgroundColor: '#eee', borderColor: '#bbb' },
  btnTextDisabled: { color: '#999' },
  btnDestructive: { backgroundColor: '#FF453A' },
  btnText: { fontSize: 15, fontWeight: '800', color: '#111' },
  btnTextCancel: { color: '#555' },
  btnTextDestructive: { color: '#fff' },
});
