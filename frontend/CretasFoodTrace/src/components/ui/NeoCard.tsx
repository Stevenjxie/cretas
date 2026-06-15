import React from 'react';
import { View, StyleSheet, ViewStyle, StyleProp, Pressable } from 'react-native';
import { theme } from '../../theme';

interface NeoCardProps {
  children: React.ReactNode;
  style?: StyleProp<ViewStyle>;
  variant?: 'elevated' | 'outlined' | 'flat';
  padding?: keyof typeof theme.custom.spacing;
  onPress?: () => void;
  testID?: string;
}

export const NeoCard: React.FC<NeoCardProps> = ({
  children,
  style,
  variant = 'elevated',
  padding = 'l',
  onPress,
  testID,
}) => {
  const getShadowStyle = () => {
    if (variant === 'elevated') {
      return theme.custom.shadows.small;
    }
    return {};
  };

  const getBorderStyle = () => {
    if (variant === 'outlined') {
      return {
        borderWidth: 1,
        borderColor: theme.colors.outline,
      };
    }
    return {};
  };

  const getBackgroundStyle = () => {
    if (variant === 'flat') {
      return {
        backgroundColor: theme.colors.surfaceVariant,
      };
    }
    return {
      backgroundColor: theme.colors.surface,
    };
  };

  const cardStyle = [
    styles.card,
    { padding: theme.custom.spacing[padding] },
    getBackgroundStyle(),
    getShadowStyle(),
    getBorderStyle(),
    style,
  ];

  if (onPress) {
    return (
      <Pressable
        style={({ pressed }) => [
          cardStyle,
          pressed && styles.pressed,
        ]}
        onPress={onPress}
        testID={testID}
      >
        {children}
      </Pressable>
    );
  }

  return <View style={cardStyle} testID={testID}>{children}</View>;
};

const styles = StyleSheet.create({
  card: {
    borderRadius: theme.custom.borderRadius.m,
  },
  pressed: {
    opacity: 0.7,
    transform: [{ scale: 0.98 }],
  },
});
