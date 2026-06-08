import React, { useMemo } from 'react';
import { StyleSheet, ViewStyle, TextStyle, StyleProp } from 'react-native';
import { Button, ButtonProps } from 'react-native-paper';
import { theme } from '../../theme';

interface NeoButtonProps extends Omit<ButtonProps, 'theme'> {
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger';
  size?: 'small' | 'medium' | 'large';
  style?: StyleProp<ViewStyle>;
  labelStyle?: StyleProp<TextStyle>;
}

// P3 Fix: Extract button config mapping outside component
const BUTTON_CONFIG = {
  primary: {
    mode: 'contained' as const,
    buttonColor: theme.colors.primary,
    textColor: theme.colors.onPrimary,
    style: undefined,
  },
  secondary: {
    mode: 'contained-tonal' as const,
    buttonColor: theme.colors.secondaryContainer,
    textColor: theme.colors.onSecondaryContainer,
    style: undefined,
  },
  outline: {
    mode: 'outlined' as const,
    buttonColor: 'transparent',
    textColor: theme.colors.primary,
    style: { borderColor: theme.colors.outline },
  },
  ghost: {
    mode: 'text' as const,
    buttonColor: 'transparent',
    textColor: theme.colors.primary,
    style: undefined,
  },
  danger: {
    mode: 'contained' as const,
    buttonColor: theme.colors.error,
    textColor: theme.colors.onError,
    style: undefined,
  },
} as const;

// P3 Fix: Extract size styles outside component
const SIZE_STYLES = {
  small: { height: 32, paddingHorizontal: 12 },
  medium: { height: 44, paddingHorizontal: 20 },
  large: { height: 56, paddingHorizontal: 32 },
} as const;

// P3 Fix: Extract font sizes outside component
const FONT_SIZES = {
  small: 13,
  medium: 15,
  large: 17,
} as const;

// P3 Fix: Wrap component with React.memo
export const NeoButton: React.FC<NeoButtonProps> = React.memo(({
  variant = 'primary',
  size = 'medium',
  style,
  labelStyle,
  children,
  mode, // ignore passed mode, use variant to determine
  ...props
}) => {
  const config = BUTTON_CONFIG[variant] || BUTTON_CONFIG.primary;
  const sizeStyle = SIZE_STYLES[size] || SIZE_STYLES.medium;
  const fontSize = FONT_SIZES[size] || FONT_SIZES.medium;

  // P3 Fix: Memoize label style to avoid object recreation
  const computedLabelStyle = useMemo(() => [
    styles.label,
    { fontSize },
    labelStyle,
  ], [fontSize, labelStyle]);

  return (
    <Button
      mode={config.mode}
      buttonColor={config.buttonColor}
      textColor={config.textColor}
      style={[
        styles.button,
        sizeStyle,
        config.style,
        style,
      ]}
      labelStyle={computedLabelStyle}
      contentStyle={styles.content}
      {...props}
    >
      {children}
    </Button>
  );
});

NeoButton.displayName = 'NeoButton';

const styles = StyleSheet.create({
  button: {
    borderRadius: theme.custom.borderRadius.s,
    justifyContent: 'center',
  },
  label: {
    fontWeight: '600',
    letterSpacing: 0.5,
    marginVertical: 0,
    // A2: removed marginHorizontal: 0 — it collapsed the gap between RNP icon SVG
    // and the button label text (e.g. 退出登录 danger button icon="logout" overlap).
    // Restoring RNP default icon spacing app-wide.
  },
  content: {
    height: '100%',
  },
});
