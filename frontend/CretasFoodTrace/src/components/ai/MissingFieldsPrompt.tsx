/**
 * MissingFieldsPrompt - AI 入库缺失字段提示组件
 *
 * 当 AI 返回 NEED_MORE_INFO 且有缺失字段时展示。
 * 对于 supplier / materialType 类型字段使用下拉选择器，
 * 其他字段使用文本输入。
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  TextInput,
  ScrollView,
  Modal,
  Platform,
  FlatList,
} from 'react-native';
import { IconButton } from 'react-native-paper';

/** 单个缺失字段的描述 */
export interface MissingField {
  /** 字段键名 (e.g. 'supplierId', 'materialTypeId', 'quantity') */
  fieldName: string;
  /** 用户友好的显示名 */
  displayName: string;
  /** 字段描述/提示 */
  description?: string;
  /** 是否必填 */
  required?: boolean;
  /** 可选值列表 — 如果存在则渲染为选择器而非文本框 */
  options?: Array<{ label: string; value: string }>;
  /** 键盘类型 */
  keyboardType?: 'default' | 'numeric' | 'decimal-pad';
}

export interface MissingFieldsPromptProps {
  /** 是否可见 */
  visible: boolean;
  /** 缺失字段列表 */
  fields: MissingField[];
  /** 提交填写结果 */
  onSubmit: (values: Record<string, string>) => void;
  /** 取消 */
  onCancel: () => void;
}

/** 下拉选择器（简单底部弹窗风格） */
function FieldPicker({
  options,
  selectedValue,
  onSelect,
  placeholder,
}: {
  options: Array<{ label: string; value: string }>;
  selectedValue: string;
  onSelect: (value: string) => void;
  placeholder: string;
}) {
  const [pickerVisible, setPickerVisible] = useState(false);
  const selectedOption = options.find(o => o.value === selectedValue);

  const handleSelect = useCallback((value: string) => {
    onSelect(value);
    setPickerVisible(false);
  }, [onSelect]);

  return (
    <>
      <TouchableOpacity
        style={styles.pickerButton}
        onPress={() => setPickerVisible(true)}
      >
        <Text style={selectedOption ? styles.pickerText : styles.pickerPlaceholder}>
          {selectedOption ? selectedOption.label : placeholder}
        </Text>
        <IconButton icon="chevron-down" size={18} iconColor="#999" style={styles.pickerIcon} />
      </TouchableOpacity>

      <Modal
        visible={pickerVisible}
        animationType="slide"
        transparent
        onRequestClose={() => setPickerVisible(false)}
      >
        <TouchableOpacity
          style={styles.pickerOverlay}
          activeOpacity={1}
          onPress={() => setPickerVisible(false)}
        >
          <View style={styles.pickerModal}>
            <View style={styles.pickerHeader}>
              <Text style={styles.pickerHeaderTitle}>请选择</Text>
              <TouchableOpacity onPress={() => setPickerVisible(false)}>
                <IconButton icon="close" size={22} iconColor="#666" />
              </TouchableOpacity>
            </View>
            <FlatList
              data={options}
              keyExtractor={item => item.value}
              renderItem={({ item }) => (
                <TouchableOpacity
                  style={[
                    styles.pickerOption,
                    item.value === selectedValue && styles.pickerOptionSelected,
                  ]}
                  onPress={() => handleSelect(item.value)}
                >
                  <Text
                    style={[
                      styles.pickerOptionText,
                      item.value === selectedValue && styles.pickerOptionTextSelected,
                    ]}
                  >
                    {item.label}
                  </Text>
                  {item.value === selectedValue && (
                    <IconButton icon="check" size={18} iconColor="#4F46E5" />
                  )}
                </TouchableOpacity>
              )}
              style={styles.pickerList}
            />
          </View>
        </TouchableOpacity>
      </Modal>
    </>
  );
}

export function MissingFieldsPrompt({
  visible,
  fields,
  onSubmit,
  onCancel,
}: MissingFieldsPromptProps) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (visible) {
      setValues({});
      setErrors({});
    }
  }, [visible]);

  const handleChange = (fieldName: string, value: string) => {
    setValues(prev => ({ ...prev, [fieldName]: value }));
    if (errors[fieldName]) {
      setErrors(prev => {
        const next = { ...prev };
        delete next[fieldName];
        return next;
      });
    }
  };

  const handleSubmit = () => {
    const newErrors: Record<string, string> = {};
    fields.forEach(f => {
      const currentValue = values[f.fieldName] ?? '';
      if (f.required && currentValue.trim() === '') {
        newErrors[f.fieldName] = `${f.displayName}是必填项`;
      }
    });
    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      return;
    }
    onSubmit(values);
  };

  return (
    <Modal
      visible={visible}
      animationType="slide"
      transparent
      onRequestClose={onCancel}
    >
      <View style={styles.overlay}>
        <View style={styles.container}>
          {/* Header */}
          <View style={styles.header}>
            <View style={styles.headerLeft}>
              <IconButton icon="alert-circle-outline" size={24} iconColor="#FF9800" />
              <View>
                <Text style={styles.headerTitle}>请补充信息</Text>
                <Text style={styles.headerSubtitle}>AI 需要以下信息完成入库</Text>
              </View>
            </View>
            <TouchableOpacity onPress={onCancel}>
              <IconButton icon="close" size={22} iconColor="#666" />
            </TouchableOpacity>
          </View>

          {/* Fields */}
          <ScrollView style={styles.fieldsContainer} showsVerticalScrollIndicator={false}>
            {fields.map(field => (
              <View key={field.fieldName} style={styles.fieldGroup}>
                <View style={styles.fieldLabelRow}>
                  <Text style={styles.fieldLabel}>{field.displayName}</Text>
                  {field.required && <Text style={styles.requiredMark}>*</Text>}
                </View>
                {field.description && (
                  <Text style={styles.fieldDescription}>{field.description}</Text>
                )}

                {field.options && field.options.length > 0 ? (
                  <FieldPicker
                    options={field.options}
                    selectedValue={values[field.fieldName] || ''}
                    onSelect={v => handleChange(field.fieldName, v)}
                    placeholder={`请选择${field.displayName}`}
                  />
                ) : (
                  <TextInput
                    style={[styles.textInput, errors[field.fieldName] && styles.textInputError]}
                    value={values[field.fieldName] || ''}
                    onChangeText={v => handleChange(field.fieldName, v)}
                    placeholder={`请输入${field.displayName}`}
                    placeholderTextColor="#999"
                    keyboardType={field.keyboardType || 'default'}
                  />
                )}

                {errors[field.fieldName] && (
                  <Text style={styles.errorText}>{errors[field.fieldName]}</Text>
                )}
              </View>
            ))}
          </ScrollView>

          {/* Actions */}
          <View style={styles.footer}>
            <TouchableOpacity style={styles.cancelButton} onPress={onCancel}>
              <Text style={styles.cancelButtonText}>取消</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.submitButton} onPress={handleSubmit}>
              <Text style={styles.submitButtonText}>确认</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'flex-end',
  },
  container: {
    backgroundColor: '#fff',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    maxHeight: '80%',
    paddingBottom: Platform.OS === 'ios' ? 34 : 20,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  headerLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  headerTitle: {
    fontSize: 17,
    fontWeight: '600',
    color: '#333',
  },
  headerSubtitle: {
    fontSize: 12,
    color: '#999',
    marginTop: 2,
  },
  fieldsContainer: {
    paddingHorizontal: 16,
    paddingTop: 12,
    maxHeight: 400,
  },
  fieldGroup: {
    marginBottom: 20,
  },
  fieldLabelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 6,
  },
  fieldLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  requiredMark: {
    fontSize: 14,
    color: '#F44336',
    marginLeft: 4,
  },
  fieldDescription: {
    fontSize: 12,
    color: '#999',
    marginBottom: 6,
  },
  textInput: {
    borderWidth: 1,
    borderColor: '#e0e0e0',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 14,
    color: '#333',
    backgroundColor: '#fafafa',
    minHeight: 44,
  },
  textInputError: {
    borderColor: '#F44336',
  },
  errorText: {
    fontSize: 12,
    color: '#F44336',
    marginTop: 4,
  },
  // Picker styles
  pickerButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderWidth: 1,
    borderColor: '#e0e0e0',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    backgroundColor: '#fafafa',
    minHeight: 44,
  },
  pickerText: {
    fontSize: 14,
    color: '#333',
    flex: 1,
  },
  pickerPlaceholder: {
    fontSize: 14,
    color: '#999',
    flex: 1,
  },
  pickerIcon: {
    margin: 0,
    padding: 0,
  },
  pickerOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.4)',
    justifyContent: 'flex-end',
  },
  pickerModal: {
    backgroundColor: '#fff',
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    maxHeight: '60%',
  },
  pickerHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  pickerHeaderTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  pickerList: {
    paddingHorizontal: 8,
  },
  pickerOption: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 14,
    paddingHorizontal: 12,
    borderBottomWidth: 0.5,
    borderBottomColor: '#f0f0f0',
  },
  pickerOptionSelected: {
    backgroundColor: '#f0f4ff',
  },
  pickerOptionText: {
    fontSize: 15,
    color: '#333',
    flex: 1,
  },
  pickerOptionTextSelected: {
    color: '#4F46E5',
    fontWeight: '600',
  },
  // Footer
  footer: {
    flexDirection: 'row',
    paddingHorizontal: 16,
    paddingTop: 16,
    gap: 12,
    borderTopWidth: 1,
    borderTopColor: '#f0f0f0',
  },
  cancelButton: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#e0e0e0',
    alignItems: 'center',
  },
  cancelButtonText: {
    fontSize: 15,
    color: '#666',
    fontWeight: '500',
  },
  submitButton: {
    flex: 2,
    paddingVertical: 14,
    borderRadius: 10,
    backgroundColor: '#4F46E5',
    alignItems: 'center',
  },
  submitButtonText: {
    fontSize: 15,
    color: '#fff',
    fontWeight: '600',
  },
});

export default MissingFieldsPrompt;
