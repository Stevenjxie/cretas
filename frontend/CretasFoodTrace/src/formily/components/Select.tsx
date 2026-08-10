/**
 * Select - 下拉选择组件
 *
 * 使用 React Native Paper Menu 实现下拉选择
 * 支持单选和多选模式
 */

import React, { useState, useCallback, useMemo } from 'react';
import { View, StyleSheet, Pressable, ScrollView, Modal } from 'react-native';
import {
  TextInput,
  Menu,
  Chip,
  useTheme,
  Divider,
  Text,
  Icon,
  IconButton,
  TouchableRipple,
} from 'react-native-paper';
import { connect, mapProps } from '@formily/react';
import type { Field } from '@formily/core';

interface SelectOption {
  label: string;
  value: string | number;
  disabled?: boolean;
}

interface SelectProps {
  value?: string | number | (string | number)[];
  onChange?: (value: string | number | (string | number)[] | undefined) => void;
  onBlur?: () => void;
  placeholder?: string;
  disabled?: boolean;
  readOnly?: boolean;
  error?: boolean;
  options?: SelectOption[];
  multiple?: boolean;
  searchable?: boolean;
  style?: object;
}

export const InternalSelect: React.FC<SelectProps> = ({
  value,
  onChange,
  onBlur,
  placeholder = '请选择',
  disabled,
  readOnly,
  error,
  options = [],
  multiple = false,
  searchable = false,
  style,
}) => {
  const theme = useTheme();
  const [visible, setVisible] = useState(false);
  const [searchText, setSearchText] = useState('');

  const isDisabled = disabled || readOnly;

  // 过滤选项
  const filteredOptions = useMemo(() => {
    if (!searchable || !searchText) return options;
    const lowerSearch = searchText.toLowerCase();
    return options.filter(opt =>
      opt.label.toLowerCase().includes(lowerSearch)
    );
  }, [options, searchText, searchable]);

  // 获取显示文本
  const displayText = useMemo(() => {
    if (multiple && Array.isArray(value)) {
      if (value.length === 0) return '';
      return value
        .map(v => options.find(opt => opt.value === v)?.label)
        .filter(Boolean)
        .join(', ');
    }

    if (value === undefined || value === null) return '';
    return options.find(opt => opt.value === value)?.label ?? '';
  }, [value, options, multiple]);

  const handleSelect = useCallback((optValue: string | number) => {
    if (multiple) {
      const currentValues = Array.isArray(value) ? value : [];
      const newValues = currentValues.includes(optValue)
        ? currentValues.filter(v => v !== optValue)
        : [...currentValues, optValue];
      onChange?.(newValues);
    } else {
      onChange?.(optValue);
      setVisible(false);
    }
  }, [value, onChange, multiple]);

  const handleClear = useCallback(() => {
    onChange?.(multiple ? [] : undefined);
  }, [onChange, multiple]);

  const openMenu = () => {
    if (!isDisabled) {
      setVisible(true);
      setSearchText('');
    }
  };

  const closeMenu = () => {
    setVisible(false);
    onBlur?.();
  };

  const isSelected = (optValue: string | number): boolean => {
    if (multiple && Array.isArray(value)) {
      return value.includes(optValue);
    }
    return value === optValue;
  };

  const anchor = (
    <Pressable
      testID="formily-select-anchor"
      onPress={openMenu}
      disabled={isDisabled}
      accessibilityRole="combobox"
      accessibilityState={{ disabled: isDisabled, expanded: visible }}
      style={styles.anchor}
    >
      <TextInput
        mode="outlined"
        value={displayText}
        placeholder={placeholder}
        disabled={isDisabled}
        error={error}
        editable={false}
        pointerEvents="none"
        style={styles.input}
        outlineColor={theme.colors.outline}
        right={
          <TextInput.Icon
            icon={visible ? 'chevron-up' : 'chevron-down'}
            disabled={isDisabled}
          />
        }
      />
    </Pressable>
  );

  const selectedChips = multiple && Array.isArray(value) && value.length > 0 && (
    <View style={styles.chipsContainer}>
      {value.map((v) => {
        const opt = options.find(o => o.value === v);
        if (!opt) return null;
        return (
          <Chip
            key={String(v)}
            mode="flat"
            onClose={isDisabled ? undefined : () => handleSelect(v)}
            style={styles.chip}
            compact
          >
            {opt.label}
          </Chip>
        );
      })}
    </View>
  );

  // Searchable lists can be long and React Native Paper Menu's Portal is
  // intermittent on Expo Web. A native Modal gives web and mobile users the
  // same deterministic, focusable selection surface without changing simple
  // non-searchable selects.
  if (searchable) {
    return (
      <View style={[styles.container, style]}>
        {anchor}
        <Modal
          visible={visible}
          transparent
          animationType="fade"
          onRequestClose={closeMenu}
        >
          <View style={styles.modalRoot} testID="formily-select-modal">
            <Pressable
              style={styles.modalBackdrop}
              onPress={closeMenu}
              accessibilityLabel="关闭选择列表"
              testID="formily-select-modal-backdrop"
            />
            <View style={styles.modalContent} accessibilityViewIsModal>
              <View style={styles.modalHeader}>
                <Text variant="titleMedium" style={styles.modalTitle}>
                  {placeholder}
                </Text>
                <IconButton
                  icon="close"
                  size={22}
                  onPress={closeMenu}
                  accessibilityLabel="关闭"
                  testID="formily-select-modal-close"
                />
              </View>

              <View style={styles.searchContainer}>
                <TextInput
                  mode="outlined"
                  value={searchText}
                  onChangeText={setSearchText}
                  placeholder="搜索..."
                  dense
                  autoFocus
                  style={styles.searchInput}
                  left={<TextInput.Icon icon="magnify" size={18} />}
                  testID="formily-select-search"
                />
              </View>
              <Divider />

              <ScrollView
                style={styles.modalOptionsList}
                keyboardShouldPersistTaps="handled"
                contentContainerStyle={filteredOptions.length === 0 ? styles.emptyOptionsList : undefined}
              >
                {filteredOptions.length === 0 ? (
                  <View style={styles.emptyContainer}>
                    <Text style={styles.emptyText}>暂无匹配选项</Text>
                  </View>
                ) : (
                  filteredOptions.map((option) => {
                    const selected = isSelected(option.value);
                    return (
                      <TouchableRipple
                        key={String(option.value)}
                        onPress={() => handleSelect(option.value)}
                        disabled={option.disabled}
                        accessibilityRole="button"
                        accessibilityState={{ selected, disabled: option.disabled }}
                        style={[styles.modalOption, selected && styles.selectedItem]}
                        testID={`formily-select-option-${String(option.value)}`}
                      >
                        <View style={styles.modalOptionContent}>
                          <Icon
                            source={multiple
                              ? selected ? 'checkbox-marked' : 'checkbox-blank-outline'
                              : selected ? 'radiobox-marked' : 'radiobox-blank'}
                            size={22}
                            color={selected ? theme.colors.primary : theme.colors.onSurfaceVariant}
                          />
                          <Text
                            style={[styles.modalOptionText, option.disabled && styles.disabledOptionText]}
                            numberOfLines={2}
                          >
                            {option.label}
                          </Text>
                        </View>
                      </TouchableRipple>
                    );
                  })
                )}
              </ScrollView>

              {multiple && (
                <>
                  <Divider />
                  <TouchableRipple
                    onPress={closeMenu}
                    style={styles.modalDone}
                    accessibilityRole="button"
                    testID="formily-select-modal-done"
                  >
                    <Text style={[styles.modalDoneText, { color: theme.colors.primary }]}>确定</Text>
                  </TouchableRipple>
                </>
              )}
            </View>
          </View>
        </Modal>
        {selectedChips}
      </View>
    );
  }

  return (
    <View style={[styles.container, style]}>
      <Menu
        visible={visible}
        onDismiss={closeMenu}
        anchor={anchor}
        contentStyle={styles.menuContent}
      >
        {searchable && (
          <>
            <View style={styles.searchContainer}>
              <TextInput
                mode="outlined"
                value={searchText}
                onChangeText={setSearchText}
                placeholder="搜索..."
                dense
                style={styles.searchInput}
                left={<TextInput.Icon icon="magnify" size={18} />}
              />
            </View>
            <Divider />
          </>
        )}

        <ScrollView style={styles.optionsList}>
          {filteredOptions.length === 0 ? (
            <View style={styles.emptyContainer}>
              <Text style={styles.emptyText}>暂无选项</Text>
            </View>
          ) : (
            filteredOptions.map((option) => (
              <Menu.Item
                key={String(option.value)}
                title={option.label}
                disabled={option.disabled}
                onPress={() => handleSelect(option.value)}
                leadingIcon={
                  multiple
                    ? isSelected(option.value)
                      ? 'checkbox-marked'
                      : 'checkbox-blank-outline'
                    : isSelected(option.value)
                      ? 'radiobox-marked'
                      : 'radiobox-blank'
                }
                style={isSelected(option.value) ? styles.selectedItem : undefined}
              />
            ))
          )}
        </ScrollView>

        {multiple && (
          <>
            <Divider />
            <View style={styles.footer}>
              <Menu.Item
                title="确定"
                onPress={closeMenu}
                leadingIcon="check"
              />
            </View>
          </>
        )}
      </Menu>

      {selectedChips}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {},
  anchor: {
    minHeight: 56,
  },
  input: {
    backgroundColor: 'transparent',
  },
  menuContent: {
    maxHeight: 300,
  },
  searchContainer: {
    padding: 8,
  },
  searchInput: {
    height: 40,
  },
  optionsList: {
    maxHeight: 200,
  },
  selectedItem: {
    backgroundColor: 'rgba(0, 0, 0, 0.05)',
  },
  emptyContainer: {
    padding: 16,
    alignItems: 'center',
  },
  emptyText: {
    color: '#888',
  },
  footer: {
    paddingVertical: 4,
  },
  modalRoot: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 16,
  },
  modalBackdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(15, 23, 42, 0.45)',
  },
  modalContent: {
    width: '100%',
    maxWidth: 440,
    maxHeight: '75%',
    backgroundColor: '#FFFFFF',
    borderRadius: 16,
    overflow: 'hidden',
    elevation: 8,
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.2,
    shadowRadius: 12,
  },
  modalHeader: {
    minHeight: 56,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingLeft: 16,
    paddingRight: 4,
  },
  modalTitle: {
    flex: 1,
    fontWeight: '600',
  },
  modalOptionsList: {
    maxHeight: 420,
  },
  emptyOptionsList: {
    flexGrow: 1,
    justifyContent: 'center',
  },
  modalOption: {
    minHeight: 52,
    justifyContent: 'center',
  },
  modalOptionContent: {
    minHeight: 52,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    gap: 12,
  },
  modalOptionText: {
    flex: 1,
    lineHeight: 20,
  },
  disabledOptionText: {
    opacity: 0.45,
  },
  modalDone: {
    minHeight: 52,
    alignItems: 'center',
    justifyContent: 'center',
  },
  modalDoneText: {
    fontSize: 15,
    fontWeight: '600',
  },
  chipsContainer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginTop: 8,
    gap: 4,
  },
  chip: {
    marginRight: 4,
    marginBottom: 4,
  },
});

/**
 * 连接 Formily 字段状态
 */
export const Select = connect(
  InternalSelect,
  mapProps((props, field) => {
    const fieldState = field as Field;

    // 从 field.dataSource 或 props.enum 获取选项
    let options: SelectOption[] = props.options ?? [];

    if (fieldState.dataSource && Array.isArray(fieldState.dataSource)) {
      options = fieldState.dataSource.map((item: any) => ({
        label: item.label ?? item.title ?? String(item.value),
        value: item.value,
        disabled: item.disabled,
      }));
    }

    return {
      ...props,
      value: fieldState.value,
      onChange: (value: any) => {
        fieldState.setValue(value);
        fieldState.validate('onInput');
      },
      onBlur: () => {
        fieldState.validate('onBlur');
      },
      disabled: fieldState.disabled,
      readOnly: fieldState.readOnly,
      error: !!fieldState.selfErrors?.length,
      options,
    };
  })
);

export default Select;
